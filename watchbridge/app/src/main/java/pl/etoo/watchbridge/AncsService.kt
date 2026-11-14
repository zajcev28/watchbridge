package pl.etoo.watchbridge

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Serwis pierwszoplanowy:
 *  1. rozgłasza się po BLE z "solicitation" usługi ANCS, żeby iPhone mógł się połączyć,
 *  2. jako klient GATT subskrybuje ANCS na iPhonie,
 *  3. pobiera treść powiadomień i wyświetla je na zegarku.
 *
 * Cała logika BLE działa na jednym wątku (handler [h]), więc nie trzeba synchronizacji.
 */
@SuppressLint("MissingPermission")
class AncsService : Service() {

    companion object {
        const val PREFS = "bridge"
        const val KEY_IPHONE = "iphone_address"
        const val KEY_ENABLED = "enabled"
        private const val MTU = 185
        private const val REQ_TIMEOUT_MS = 5_000L
        private const val OP_TIMEOUT_MS = 5_000L

        @Volatile
        var instance: AncsService? = null
            private set

        fun start(ctx: Context) {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, true).apply()
            ctx.startForegroundService(Intent(ctx, AncsService::class.java))
        }

        fun stop(ctx: Context) {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, false).apply()
            ctx.stopService(Intent(ctx, AncsService::class.java))
        }

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private val KNOWN_APPS = mapOf(
            "net.whatsapp.WhatsApp" to "WhatsApp",
            "net.whatsapp.WhatsAppSMB" to "WhatsApp Business",
            "com.apple.mobilephone" to "Telefon",
            "com.apple.MobileSMS" to "Wiadomości",
            "com.apple.mobilemail" to "Mail",
            "com.apple.facetime" to "FaceTime",
            "com.apple.mobilecal" to "Kalendarz",
            "com.apple.reminders" to "Przypomnienia",
            "com.facebook.Messenger" to "Messenger",
            "ph.telegra.Telegraph" to "Telegram",
            "org.whispersystems.signal" to "Signal",
            "com.google.Gmail" to "Gmail",
            "com.microsoft.skype.teams" to "Teams",
        )
    }

    // ---- stan ----
    private val thread = HandlerThread("ancs").apply { start() }
    private val h = Handler(thread.looper)
    private lateinit var bm: BluetoothManager
    private lateinit var prefs: SharedPreferences

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false
    private var advWithName = true

    private var gatt: BluetoothGatt? = null
    private var setupStarted = false
    private var nsChar: BluetoothGattCharacteristic? = null
    private var cpChar: BluetoothGattCharacteristic? = null
    private var dsChar: BluetoothGattCharacteristic? = null

    private sealed class Op {
        data class Notify(val c: BluetoothGattCharacteristic, var retries: Int = 0) : Op()
        class Write(val c: BluetoothGattCharacteristic, val data: ByteArray, val isRequest: Boolean) : Op()
    }

    private sealed class Req {
        data class Notif(val uid: Int) : Req()
        data class App(val appId: String) : Req()
    }

    private val opQueue = ArrayDeque<Op>()
    private var currentOp: Op? = null
    private val reqQueue = ArrayDeque<Req>()
    private var currentReq: Req? = null
    private val dsBuffer = ByteArrayOutputStream()
    private val pending = HashMap<Int, SourceEvent>()
    private val requestedApps = HashSet<String>()

    private val statusListener: (String) -> Unit = { Notifier.updateService(this, it) }

    // ---- cykl życia ----
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        bm = getSystemService(BluetoothManager::class.java)
        prefs = AncsService.prefs(this)
        Notifier.createChannels(this)
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(btReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        Status.add(statusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                Notifier.SERVICE_ID,
                Notifier.serviceNotification(this, "Uruchamianie…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground", e)
            Status.set("Brak uprawnień Bluetooth – otwórz aplikację")
            stopSelf()
            return START_NOT_STICKY
        }
        h.post { startBle() }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        Status.remove(statusListener)
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        h.post { teardown() }
        thread.quitSafely()
        Status.set("Zatrzymany")
        super.onDestroy()
    }

    // ---- API dla UI / akcji z powiadomień ----
    fun performAction(uid: Int, actionId: Int) {
        h.post {
            val cp = cpChar ?: return@post
            enqueueOp(Op.Write(cp, Ancs.performAction(uid, actionId), isRequest = false))
        }
    }

    // ---- BLE: start / stop ----
    private fun startBle() {
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) {
            Status.set("Bluetooth wyłączony")
            return
        }
        if (gattServer == null) gattServer = bm.openGattServer(this, serverCb)
        if (gatt == null) {
            startAdvertising()
            Status.set("Czekam na iPhone'a…")
            connectSaved()
        }
    }

    private fun teardown() {
        stopAdvertising()
        resetSession()
        gatt?.close()
        gatt = null
        gattServer?.close()
        gattServer = null
    }

    private fun resetSession() {
        setupStarted = false
        nsChar = null; cpChar = null; dsChar = null
        opQueue.clear(); currentOp = null
        reqQueue.clear(); currentReq = null
        dsBuffer.reset()
        pending.clear()
        h.removeCallbacks(opWatchdog)
        h.removeCallbacks(reqWatchdog)
    }

    /** Jeśli znamy już sparowanego iPhone'a – łączymy się sami (autoConnect czeka w tle). */
    private fun connectSaved() {
        if (gatt != null) return
        val addr = prefs.getString(KEY_IPHONE, null) ?: return
        val dev = bm.adapter?.bondedDevices?.firstOrNull { it.address == addr } ?: return
        Log.i(TAG, "autoConnect do $addr")
        connectClient(dev, auto = true)
    }

    private fun connectClient(dev: BluetoothDevice, auto: Boolean) {
        resetSession()
        gatt = dev.connectGatt(this, auto, clientCb, BluetoothDevice.TRANSPORT_LE)
    }

    // ---- advertising ----
    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "advertising OK")
        }

        override fun onStartFailure(errorCode: Int) {
            h.post {
                Log.w(TAG, "advertising błąd $errorCode")
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return@post
                advertising = false
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && advWithName) {
                    advWithName = false
                    startAdvertising()
                } else {
                    Status.set("Błąd rozgłaszania BLE ($errorCode)")
                }
            }
        }
    }

    private fun startAdvertising() {
        if (advertising) return
        val adv = bm.adapter?.bluetoothLeAdvertiser ?: run {
            Status.set("Brak BLE advertisera")
            return
        }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // "Solicitation" ANCS = sygnał dla iOS, że to akcesorium chce powiadomień.
        val data = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(Ancs.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scan = if (advWithName) AdvertiseData.Builder().setIncludeDeviceName(true).build() else null
        advertising = true
        adv.startAdvertising(settings, data, scan, advCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        advertising = false
    }

    // ---- serwer GATT: tu iPhone łączy się jako central ----
    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            h.post {
                Log.i(TAG, "server: ${device.address} state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED && gatt == null) {
                    // Na istniejącym łączu otwieramy klienta GATT, żeby czytać ANCS z iPhone'a.
                    connectClient(device, auto = false)
                }
            }
        }
    }

    // ---- klient GATT (ANCS) ----
    private val clientCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post { onClientState(g, status, newState) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            h.post {
                Log.i(TAG, "MTU=$mtu status=$status")
                if (g == gatt) g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post { onServices(g, status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            h.post { if (g == gatt) onOpDone(status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.post { if (g == gatt) onOpDone(status) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val v = value.copyOf()
            val u = c.uuid
            h.post {
                if (g != gatt) return@post
                when (u) {
                    Ancs.NOTIFICATION_SOURCE -> onSource(v)
                    Ancs.DATA_SOURCE -> onData(v)
                }
            }
        }
    }

    private fun onClientState(g: BluetoothGatt, status: Int, newState: Int) {
        if (g != gatt) {
            g.close()
            return
        }
        Log.i(TAG, "client: state=$newState status=$status bond=${g.device.bondState}")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                stopAdvertising()
                when (g.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> beginSetup(g)
                    BluetoothDevice.BOND_BONDING -> Status.set("Parowanie… potwierdź na iPhonie")
                    else -> {
                        Status.set("Parowanie… potwierdź na iPhonie")
                        if (!g.device.createBond()) beginSetup(g)
                    }
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                resetSession()
                g.close()
                gatt = null
                Status.set("Rozłączono – czekam na iPhone'a…")
                startAdvertising()
                h.postDelayed({ connectSaved() }, 1_500)
            }
        }
    }

    private fun onBondState(dev: BluetoothDevice, state: Int) {
        val g = gatt ?: return
        if (g.device.address != dev.address) return
        Log.i(TAG, "bond state=$state")
        when (state) {
            BluetoothDevice.BOND_BONDED -> beginSetup(g)
            BluetoothDevice.BOND_NONE -> {
                Status.set("Parowanie odrzucone/nieudane")
                g.disconnect()
            }
        }
    }

    private fun beginSetup(g: BluetoothGatt) {
        if (setupStarted) return
        setupStarted = true
        Status.set("Łączenie z ANCS…")
        if (!g.requestMtu(MTU)) g.discoverServices()
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        if (g != gatt) return
        val s = g.getService(Ancs.SERVICE)
        if (status != BluetoothGatt.GATT_SUCCESS || s == null) {
            Status.set("Brak ANCS na urządzeniu (to nie iPhone?)")
            g.disconnect()
            return
        }
        nsChar = s.getCharacteristic(Ancs.NOTIFICATION_SOURCE)
        cpChar = s.getCharacteristic(Ancs.CONTROL_POINT)
        dsChar = s.getCharacteristic(Ancs.DATA_SOURCE)
        val ns = nsChar
        val ds = dsChar
        if (ns == null || ds == null || cpChar == null) {
            Status.set("Niepełna usługa ANCS")
            g.disconnect()
            return
        }
        prefs.edit().putString(KEY_IPHONE, g.device.address).apply()
        // Najpierw Data Source, potem Notification Source (zalecenie Apple).
        enqueueOp(Op.Notify(ds))
        enqueueOp(Op.Notify(ns))
        Status.set("Połączono: ${g.device.name ?: "iPhone"}")
    }

    // ---- kolejka operacji GATT (Android pozwala na jedną naraz) ----
    private val opWatchdog = Runnable {
        Log.w(TAG, "timeout operacji GATT")
        onOpDone(BluetoothGatt.GATT_FAILURE)
    }

    private fun enqueueOp(op: Op) {
        opQueue.addLast(op)
        nextOp()
    }

    private fun nextOp() {
        if (currentOp != null) return
        val g = gatt ?: run { opQueue.clear(); return }
        val op = opQueue.removeFirstOrNull() ?: return
        currentOp = op
        val rc = when (op) {
            is Op.Notify -> {
                g.setCharacteristicNotification(op.c, true)
                val d = op.c.getDescriptor(Ancs.CCCD)
                if (d == null) BluetoothStatusCodes.ERROR_UNKNOWN
                else g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            is Op.Write -> g.writeCharacteristic(op.c, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        if (rc == BluetoothStatusCodes.SUCCESS) {
            h.postDelayed(opWatchdog, OP_TIMEOUT_MS)
        } else {
            Log.w(TAG, "operacja GATT odrzucona rc=$rc")
            currentOp = null
            when (op) {
                is Op.Notify -> if (op.retries++ < 5) opQueue.addFirst(op)
                is Op.Write -> if (op.isRequest) failRequest()
            }
            h.postDelayed({ nextOp() }, 300)
        }
    }

    private fun onOpDone(status: Int) {
        h.removeCallbacks(opWatchdog)
        val op = currentOp
        currentOp = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "operacja GATT nieudana status=$status")
            when (op) {
                is Op.Notify -> if (op.retries++ < 5) {
                    opQueue.addFirst(op)
                    h.postDelayed({ nextOp() }, 500)
                    return
                }
                is Op.Write -> if (op.isRequest) failRequest()
                null -> {}
            }
        }
        nextOp()
    }

    // ---- ANCS: zdarzenia ----
    private fun onSource(v: ByteArray) {
        val ev = SourceEvent.parse(v) ?: return
        Log.d(TAG, "source: $ev")
        when (ev.eventId) {
            Ancs.EVENT_REMOVED -> {
                pending.remove(ev.uid)
                reqQueue.removeAll { it is Req.Notif && it.uid == ev.uid }
                Notifier.cancel(this, ev.uid)
            }
            Ancs.EVENT_ADDED, Ancs.EVENT_MODIFIED -> {
                // Po połączeniu iPhone wysyła wszystkie stare powiadomienia – pomijamy je.
                if (ev.preExisting) return
                pending[ev.uid] = ev
                if (reqQueue.none { it is Req.Notif && it.uid == ev.uid }) enqueueReq(Req.Notif(ev.uid))
            }
        }
    }

    // ---- ANCS: zapytania o treść (jedno naraz, odpowiedź może przyjść w kawałkach) ----
    private val reqWatchdog = Runnable {
        Log.w(TAG, "timeout odpowiedzi ANCS")
        failRequest()
    }

    private fun enqueueReq(r: Req) {
        reqQueue.addLast(r)
        pumpReq()
    }

    private fun pumpReq() {
        if (currentReq != null) return
        val cp = cpChar ?: return
        val r = reqQueue.removeFirstOrNull() ?: return
        currentReq = r
        dsBuffer.reset()
        val bytes = when (r) {
            is Req.Notif -> Ancs.getNotificationAttributes(r.uid)
            is Req.App -> Ancs.getAppAttributes(r.appId)
        }
        enqueueOp(Op.Write(cp, bytes, isRequest = true))
        h.postDelayed(reqWatchdog, REQ_TIMEOUT_MS)
    }

    private fun finishRequest() {
        h.removeCallbacks(reqWatchdog)
        currentReq = null
        dsBuffer.reset()
        pumpReq()
    }

    private fun failRequest() {
        val r = currentReq
        if (r is Req.Notif) pending.remove(r.uid)
        finishRequest()
    }

    private fun onData(v: ByteArray) {
        val r = currentReq ?: return
        dsBuffer.write(v)
        val buf = dsBuffer.toByteArray()
        try {
            when (r) {
                is Req.Notif -> {
                    val a = Ancs.parseNotificationAttributes(buf) ?: return
                    finishRequest()
                    show(a)
                }
                is Req.App -> {
                    val (appId, name) = Ancs.parseAppAttributes(buf) ?: return
                    if (name.isNotBlank()) prefs.edit().putString("app:$appId", name).apply()
                    finishRequest()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "błąd parsowania ANCS", e)
            failRequest()
        }
    }

    private fun show(a: NotificationAttributes) {
        val ev = pending.remove(a.uid) ?: return
        val appId = a[Ancs.ATTR_APP_ID]
        Notifier.show(this, ev, appName(appId), a)
    }

    private fun appName(appId: String): String {
        KNOWN_APPS[appId]?.let { return it }
        prefs.getString("app:$appId", null)?.let { return it }
        if (appId.isNotEmpty() && requestedApps.add(appId)) enqueueReq(Req.App(appId))
        return appId.substringAfterLast('.').ifEmpty { "iPhone" }
    }

    // ---- zdarzenia systemowe Bluetooth ----
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    h.post { onBondState(dev, st) }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    h.post {
                        when (st) {
                            BluetoothAdapter.STATE_ON -> startBle()
                            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                                teardown()
                                Status.set("Bluetooth wyłączony")
                            }
                        }
                    }
                }
            }
        }
    }
}
