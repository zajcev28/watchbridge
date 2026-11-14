package pl.etoo.watchbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Przyciski w powiadomieniach (Odbierz / Odrzuć / Wyczyść …) → akcja na iPhonie. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_ACTION = "action"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        val action = intent.getIntExtra(EXTRA_ACTION, -1)
        if (action < 0) return
        AncsService.instance?.performAction(uid, action)
        if (action == Ancs.ACTION_NEGATIVE) Notifier.cancel(ctx, uid)
    }
}

/** Autostart po restarcie zegarka (jeśli serwis był włączony). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val perms = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        val granted = perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted && AncsService.prefs(ctx).getBoolean(AncsService.KEY_ENABLED, false)) {
            AncsService.start(ctx)
        }
    }
}
