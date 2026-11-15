package pl.etoo.watchbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** Wyświetlanie powiadomień z iPhone'a na zegarku. */
object Notifier {
    const val SERVICE_ID = 1
    private const val CH_SERVICE = "service"
    private const val CH_MSG = "messages"
    private const val CH_CALL = "calls"
    private const val CH_QUIET = "quiet"
    private const val NTAG = "ancs"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun createChannels(ctx: Context) {
        val nm = nm(ctx)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Połączenie z iPhone", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MSG, "Powiadomienia z iPhone", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, "Połączenia przychodzące", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "Ciche powiadomienia", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun serviceNotification(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("WatchBridge")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun updateService(ctx: Context, text: String) {
        nm(ctx).notify(SERVICE_ID, serviceNotification(ctx, text))
    }

    fun show(ctx: Context, ev: SourceEvent, app: String, a: NotificationAttributes) {
        val title = a[Ancs.ATTR_TITLE]
        val sub = a[Ancs.ATTR_SUBTITLE]
        val msg = a[Ancs.ATTR_MESSAGE]
        val pos = a[Ancs.ATTR_POSITIVE_LABEL]
        val neg = a[Ancs.ATTR_NEGATIVE_LABEL]
        val appId = a[Ancs.ATTR_APP_ID]

        if (ev.category == Ancs.CAT_INCOMING_CALL) {
            showCall(ctx, ev, app, appId, title.ifBlank { "Nieznany numer" }, pos, neg)
            return
        }

        val text = listOf(sub, msg).filter { it.isNotBlank() }.joinToString("\n")
        val b = Notification.Builder(ctx, if (ev.silent) CH_QUIET else CH_MSG)
            .setSmallIcon(AppIcons.small(appId, ev.category))
            .setLargeIcon(AppIcons.large(ctx, appId, ev.category))
            .setColor(AppIcons.color(appId))
            .setContentTitle(title.ifBlank { app })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(app)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(ev.eventId == Ancs.EVENT_MODIFIED)
        when (ev.category) {
            Ancs.CAT_SOCIAL -> b.setCategory(Notification.CATEGORY_MESSAGE)
            Ancs.CAT_EMAIL -> b.setCategory(Notification.CATEGORY_EMAIL)
            Ancs.CAT_MISSED_CALL -> b.setCategory(Notification.CATEGORY_MISSED_CALL)
        }
        if (ev.hasPositive && pos.isNotBlank()) b.addAction(action(ctx, ev.uid, Ancs.ACTION_POSITIVE, pos))
        if (ev.hasNegative && neg.isNotBlank()) b.addAction(action(ctx, ev.uid, Ancs.ACTION_NEGATIVE, neg))
        nm(ctx).notify(NTAG, ev.uid, b.build())
    }

    private fun showCall(
        ctx: Context, ev: SourceEvent, app: String, appId: String,
        caller: String, pos: String, neg: String,
    ) {
        val full = CallActivity.intent(ctx, ev.uid, caller, app, appId, ev.hasPositive, ev.hasNegative)
        val fullPi = PendingIntent.getActivity(
            ctx, ev.uid, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(ctx, CH_CALL)
            .setSmallIcon(R.drawable.ic_call)
            .setLargeIcon(AppIcons.large(ctx, appId, Ancs.CAT_INCOMING_CALL))
            .setColor(AppIcons.color(appId))
            .setContentTitle(caller)
            .setContentText("$app – połączenie przychodzące")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(fullPi)
            .setFullScreenIntent(fullPi, true)
        if (ev.hasNegative) b.addAction(action(ctx, ev.uid, Ancs.ACTION_NEGATIVE, neg.ifBlank { "Odrzuć" }))
        if (ev.hasPositive) b.addAction(action(ctx, ev.uid, Ancs.ACTION_POSITIVE, pos.ifBlank { "Odbierz" }))
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // wibruje aż do odebrania/odrzucenia
        nm(ctx).notify(NTAG, ev.uid, n)
    }

    fun cancel(ctx: Context, uid: Int) {
        nm(ctx).cancel(NTAG, uid)
        CallActivity.dismiss(uid)
    }

    private fun action(ctx: Context, uid: Int, actionId: Int, label: String): Notification.Action {
        val i = Intent(ctx, ActionReceiver::class.java)
            .putExtra(ActionReceiver.EXTRA_UID, uid)
            .putExtra(ActionReceiver.EXTRA_ACTION, actionId)
        val pi = PendingIntent.getBroadcast(
            ctx, uid * 2 + actionId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notif), label, pi).build()
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
