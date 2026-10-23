package com.antiscam.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.antiscam.app.MainActivity
import com.antiscam.app.R
import com.antiscam.app.ScamWarningActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps a user-started phone run independent of the setup activity's task. */
class ProtectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        RunMonitor.initialize(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ScamCatch monitoring", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RunMonitor.stopRun()
            finish()
            return START_NOT_STICKY
        }
        // Promote before loading keys or waiting for accessibility to reconnect.
        try {
            val notification = notification(CaptureStore.state.value)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            RunMonitor.stopRun()
            CaptureStore.report("Monitoring stopped.", "Android could not start background protection. Reopen ScamCatch and start a run.")
            finish()
            return START_NOT_STICKY
        }
        if (!CaptureStore.state.value.standaloneRunRequested) {
            finish()
            return START_NOT_STICKY
        }
        RunMonitor.setProtectionServiceConnected(true)
        observeState()
        return START_STICKY
    }

    private var observing = false

    private fun observeState() {
        if (observing) return
        observing = true
        scope.launch {
            CaptureStore.state.map {
                listOf(it.standaloneRunRequested, it.runId != null, it.serviceConnected,
                    it.warningActive, it.jevReady, it.voiceReady)
            }.distinctUntilChanged().collect {
                val state = CaptureStore.state.value
                if (!state.standaloneRunRequested) {
                    finish()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                }
            }
        }
    }

    private fun notification(state: CaptureState): Notification {
        val target = if (state.warningActive) ScamWarningActivity::class.java else MainActivity::class.java
        val open = PendingIntent.getActivity(this, 71, Intent(this, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 72,
            Intent(this, ProtectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = when {
            state.warningActive -> "Warning open. Screen checks are paused. Tap to return."
            !state.serviceConnected -> "Waiting for accessibility to reconnect."
            !state.jevReady || !state.voiceReady -> "Waiting for API keys. Open ScamCatch if setup is needed."
            state.runId == null -> "Restoring monitoring. Tap to view setup."
            else -> "Checking approved apps. You can remove ScamCatch from Recents."
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_scam_flag)
            .setContentTitle(if (state.runId != null) "ScamCatch monitoring is on" else "ScamCatch monitoring is waiting")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop monitoring", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun finish() {
        RunMonitor.setProtectionServiceConnected(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        RunMonitor.setProtectionServiceConnected(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "scamcatch_monitoring"
        private const val NOTIFICATION_ID = 71
        private const val ACTION_STOP = "com.antiscam.app.STOP_MONITORING"

        /** Call from the visible Start Run action. System sticky restarts need no new launch. */
        fun start(context: Context): String? = try {
            context.startForegroundService(Intent(context, ProtectionService::class.java))
            null
        } catch (_: RuntimeException) {
            "Android could not start background protection. Keep ScamCatch open and try Start Run again."
        }
    }
}
