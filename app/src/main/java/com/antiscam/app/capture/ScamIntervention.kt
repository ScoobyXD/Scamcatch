package com.antiscam.app.capture

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.antiscam.app.ScamWarningActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

interface VoiceConnection {
    val isStopped: Boolean get() = true

    /** Stop local audio immediately. Complete after the connection and audio workers stop. */
    fun close(onClosed: () -> Unit)
}

data class WarningIncident(val runId: String, val captureId: Int, val evidence: String)

/** One incident owns capture suspension, the warning, and the voice connection. Main thread only. */
object ScamIntervention {
    private const val WARNING_COOLDOWN_MILLIS = 5_000L
    private val mutableIncident = MutableStateFlow<WarningIncident?>(null)
    val incident: StateFlow<WarningIncident?> = mutableIncident.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var observer: Job? = null
    private var service = WeakReference<AccessibilityService>(null)
    private var connection: VoiceConnection? = null
    private var attempted = false
    private var closingVoice = false
    private var dismissing = false
    private var statusListener: ((String) -> Unit)? = null
    private var levelListener: ((Float) -> Unit)? = null
    private val closeWaiters = mutableListOf<() -> Unit>()
    private val dismissWaiters = mutableListOf<() -> Unit>()
    private var launchTimeout: Runnable? = null
    private var warningCooldownUntilMillis = 0L
    var generation: Long = 0
        private set

    fun bind(owner: AccessibilityService) {
        service = WeakReference(owner)
        observer?.cancel()
        observer = scope.launch {
            CaptureStore.state.collect { state ->
                val current = incident.value ?: return@collect
                if (!state.consentGranted || !state.monitoringEnabled || !state.serviceConnected || state.runId != current.runId) {
                    dismiss {}
                } else if (attempted && (!state.voiceConsentGranted || !state.voiceReady)) {
                    suspendVoice()
                }
            }
        }
    }

    fun unbind() {
        service.clear()
        observer?.cancel()
        observer = null
        dismiss {}
    }

    fun offer(runId: String, captureId: Int, evidence: String, sourceGeneration: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val owner = service.get() ?: return
        val state = CaptureStore.state.value
        if (!LiveVoice.available || incident.value != null || dismissing || sourceGeneration != generation ||
            SystemClock.elapsedRealtime() < warningCooldownUntilMillis ||
            state.runId != runId || !state.consentGranted || !state.monitoringEnabled || !state.serviceConnected) return
        generation++
        attempted = false
        CaptureStore.setWarningActive(true)
        CaptureStore.setVoiceStatus("Connecting voice…")
        mutableIncident.value = WarningIncident(runId, captureId, evidence)
        RunMonitor.emit("warning_opened", mapOf("runId" to runId, "captureId" to captureId))
        try {
            @Suppress("DEPRECATION")
            val vibrator = owner.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 65, 120, 65), -1))
        } catch (_: RuntimeException) {
            // An unavailable vibrator must not prevent the warning from opening.
        }
        try {
            owner.startActivity(Intent(owner, ScamWarningActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            // Android can reject a background launch without throwing. Keep an actionable fallback.
            launchTimeout = Runnable {
                if (!attempted && incident.value != null) {
                    status("Open ScamCatch and select Return to scam warning.")
                    JevWarning.update(owner, captureId)
                }
            }.also { handler.postDelayed(it, 2500) }
        } catch (_: RuntimeException) {
            status("Open ScamCatch and select Return to scam warning.")
            JevWarning.update(owner, captureId)
        }
    }

    fun attach(context: Context, onStatus: (String) -> Unit, onLevel: (Float) -> Unit) {
        val current = incident.value ?: return
        statusListener = onStatus
        levelListener = onLevel
        onStatus(CaptureStore.state.value.voiceStatus)
        launchTimeout?.let(handler::removeCallbacks)
        launchTimeout = null
        if (attempted || dismissing) return
        attempted = true
        val state = CaptureStore.state.value
        val unavailable = when {
            !state.voiceConsentGranted -> "Allow automatic voice in ScamCatch setup for future warnings."
            !LiveVoice.ready -> "Set the OpenAI key in ScamCatch setup for future voice warnings."
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Allow microphone access in ScamCatch setup for future voice warnings."
            else -> null
        }
        if (unavailable != null) {
            status(unavailable)
            return
        }
        connection = LiveVoice.start(context, current.evidence, ::status) { level ->
            if (!dismissing && !closingVoice && incident.value === current) levelListener?.invoke(level)
        }
    }

    fun suspendVoice() {
        if (incident.value == null || dismissing) return
        val wasActive = connection?.isStopped == false
        stopVoice {
            if (wasActive && incident.value != null && !dismissing) {
                status("Voice stopped. Close this warning to resume screen checks.")
            }
        }
        statusListener = null
        levelListener = null
    }

    fun dismiss(onClosed: () -> Unit) {
        if (incident.value == null && !dismissing) {
            onClosed()
            return
        }
        dismissWaiters += onClosed
        if (dismissing) return
        dismissing = true
        launchTimeout?.let(handler::removeCallbacks)
        launchTimeout = null
        status("Closing voice…")
        stopVoice {
            val current = incident.value
            if (current != null) warningCooldownUntilMillis = SystemClock.elapsedRealtime() + WARNING_COOLDOWN_MILLIS
            generation++
            mutableIncident.value = null
            service.get()?.let { JevWarning.update(it, null) }
            CaptureStore.setVoiceStatus("idle")
            CaptureStore.setWarningActive(false)
            statusListener = null
            levelListener = null
            dismissing = false
            if (current != null) RunMonitor.emit("warning_closed", mapOf("runId" to current.runId, "captureId" to current.captureId))
            val callbacks = dismissWaiters.toList()
            dismissWaiters.clear()
            callbacks.forEach { it() }
        }
    }

    private fun stopVoice(onClosed: () -> Unit) {
        closeWaiters += onClosed
        if (closingVoice) return
        closingVoice = true
        levelListener?.invoke(0f)
        val finish = {
            connection = null
            closingVoice = false
            val callbacks = closeWaiters.toList()
            closeWaiters.clear()
            callbacks.forEach { it() }
        }
        val active = connection
        if (active == null) finish() else active.close(finish)
    }

    private fun status(value: String) {
        if (incident.value == null) return
        CaptureStore.setVoiceStatus(value)
        statusListener?.invoke(value)
    }
}
