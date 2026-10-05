package com.antiscam.app.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.antiscam.app.MainActivity
import com.antiscam.app.R

/** Show the current flag without copying captured text into a notification. */
object JevWarning {
    private const val CHANNEL = "jev_flags"
    private const val NOTIFICATION_ID = 70

    fun update(context: Context, captureId: Int?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (captureId == null || !JevAnalysis.available) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Scam flags", NotificationManager.IMPORTANCE_LOW))
        if (!manager.areNotificationsEnabled()) return
        val intent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_scam_flag)
            .setContentTitle("Probably scam")
            .setContentText("A check reached the alert threshold. Review capture #$captureId in the console.")
            .setContentIntent(intent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
