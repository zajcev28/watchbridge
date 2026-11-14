package pl.etoo.watchbridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusTv: TextView
    private lateinit var hintTv: TextView
    private val listener: (String) -> Unit = { statusTv.text = it }

    private val perms = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (resources.displayMetrics.widthPixels * 0.12).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "WatchBridge"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        statusTv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(0x90, 0xCA, 0xF9))
            gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, (8 * dp).toInt())
        }
        col.addView(statusTv)

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        col.addView(btn("Start") { ensurePermsAndStart() })
        col.addView(btn("Stop") { AncsService.stop(this) })
        col.addView(btn("Bez oszczędzania baterii") { askBattery() })
        col.addView(btn("Pełny ekran połączeń") { askFullScreen() })
        col.addView(btn("Zapomnij iPhone'a") {
            AncsService.prefs(this).edit().remove(AncsService.KEY_IPHONE).apply()
            Toast.makeText(this, "Usuń też parowanie w ustawieniach BT", Toast.LENGTH_LONG).show()
        })

        hintTv = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        col.addView(hintTv)

        setContentView(ScrollView(this).apply { addView(col) })

        if (savedInstanceState == null) ensurePermsAndStart()
    }

    override fun onStart() {
        super.onStart()
        statusTv.text = Status.text
        Status.add(listener)
        refreshHints()
    }

    override fun onStop() {
        Status.remove(listener)
        super.onStop()
    }

    private fun refreshHints() {
        val hints = mutableListOf<String>()
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) hints += "• Optymalizacja baterii włączona"
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) hints += "• Brak zgody na pełny ekran połączeń"
        hints += "iPhone: Ustawienia → Bluetooth → ten zegarek → „Udostępniaj powiadomienia systemowe”"
        hintTv.text = hints.joinToString("\n")
    }

    private fun ensurePermsAndStart() {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) AncsService.start(this)
        else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val bt = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        if (bt.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            AncsService.start(this)
        } else {
            statusTv.text = "Brak uprawnień Bluetooth"
        }
    }

    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Użyj adb (patrz README)", Toast.LENGTH_LONG).show()
        }
    }

    private fun askFullScreen() {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Użyj adb (patrz README)", Toast.LENGTH_LONG).show()
        }
    }
}
