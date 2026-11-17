package com.android.portguard.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.android.portguard.MainActivity

/**
 * Optional, permission-gated notifications for on-screen Shizuku monitoring.
 * No polling or background service is started here.
 */
object PortWatchNotifier {
    private const val CHANNEL = "portguard_watch_events_v1"

    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    fun notifyAppeared(context: Context, portKeys: List<String>) {
        if (portKeys.isEmpty() || !canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "PortGuard · отслеживаемые порты",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Появление портов, которые пользователь добавил в наблюдение" }
        )

        val details = portKeys.take(3).joinToString(", ") { it.uppercase().replace(":", "/") }
        val message = if (portKeys.size > 3) "$details и ещё ${portKeys.size - 3}" else details
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context, 1244, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("PortGuard: обнаружены отслеживаемые порты")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message + "\nТребуется проверка; это не подтверждённая угроза."))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        // One summary notification per scan to avoid a flood of alerts.
        try {
            manager.notify(1244, notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked after checking it.
        }
    }
}
