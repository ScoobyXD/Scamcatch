package com.antiscam.app.capture

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptureState(
    val consentGranted: Boolean = false,
    val monitoringEnabled: Boolean = false,
    val serviceConnected: Boolean = false,
    val status: String = "Allow capture to begin.",
    val latestText: String = "",
    val lastCapturedAtMillis: Long? = null,
    val captureCount: Int = 0,
    val lastError: String? = null,
    val jevReady: Boolean = false,
    val jevStatus: String = "idle",
    val jevFlagged: Boolean? = null,
    val jevCaptureId: Int? = null,
    // A run exists only while the developer dashboard owns the local connection.
    val runId: String? = null,
    // Every change that invalidates a pending capture increments this value.
    val revision: Long = 0,
)

/** Only consent and monitoring settings persist. OCR, Jev results, and the API key do not. */
object CaptureStore {
    private const val CONSENT_VERSION = 2
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    private var preferences: SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("capture_settings", Context.MODE_PRIVATE)
        preferences = prefs
        val consent = prefs.getBoolean("consent", false) && prefs.getInt("consentVersion", 0) == CONSENT_VERSION
        val enabled = consent && prefs.getBoolean("enabled", false)
        mutableState.value = CaptureState(
            consentGranted = consent,
            monitoringEnabled = enabled,
            status = if (enabled) "Waiting for the accessibility service." else "Monitoring is paused.",
        )
    }

    fun setConsent(granted: Boolean) {
        mutableState.update {
            it.copy(
                consentGranted = granted,
                monitoringEnabled = granted && it.monitoringEnabled,
                latestText = if (granted) it.latestText else "",
                lastCapturedAtMillis = if (granted) it.lastCapturedAtMillis else null,
                captureCount = if (granted) it.captureCount else 0,
                status = if (granted) "Capture is allowed. Enable monitoring to begin." else "Consent removed. Capture is off.",
                lastError = null,
                revision = it.revision + 1,
            )
        }
        saveSettings()
    }

    fun setEnabled(enabled: Boolean) {
        mutableState.update {
            val active = enabled && it.consentGranted
            it.copy(
                monitoringEnabled = active,
                status = when {
                    !it.consentGranted -> "Allow capture before enabling monitoring."
                    !active -> "Monitoring is paused."
                    !it.serviceConnected -> "Enable Antiscam in Android accessibility settings."
                    it.runId == null -> "Ready. Start a run in the desktop dashboard."
                    else -> "Run active. Capture follows the current app."
                },
                lastError = null,
                revision = it.revision + 1,
            )
        }
        saveSettings()
    }

    fun clearResult() {
        mutableState.update {
            it.copy(
                latestText = "",
                lastCapturedAtMillis = null,
                captureCount = 0,
                lastError = null,
                status = "Local result cleared. Saved desktop run records are unchanged.",
                jevStatus = "idle",
                jevCaptureId = null,
                jevFlagged = null,
                revision = it.revision + 1,
            )
        }
    }

    fun reset() {
        JevAnalysis.clearKey()
        mutableState.update {
            CaptureState(serviceConnected = it.serviceConnected, revision = it.revision + 1)
        }
        saveSettings()
    }

    internal fun connected(connected: Boolean) {
        mutableState.update {
            it.copy(
                serviceConnected = connected,
                revision = if (it.serviceConnected == connected) it.revision else it.revision + 1,
                status = when {
                    !connected -> "Accessibility service is disconnected."
                    !it.consentGranted -> "Allow capture to begin."
                    !it.monitoringEnabled -> "Monitoring is paused."
                    it.runId == null -> "Ready. Start a run in the desktop dashboard."
                    else -> "Run active. Capture follows the current app."
                },
            )
        }
    }

    internal fun setRunId(runId: String?) {
        mutableState.update {
            if (it.runId == runId) it else it.copy(
                runId = runId,
                jevStatus = "idle",
                jevCaptureId = null,
                jevFlagged = null,
                latestText = if (runId != null) "" else it.latestText,
                lastCapturedAtMillis = if (runId != null) null else it.lastCapturedAtMillis,
                captureCount = if (runId != null) 0 else it.captureCount,
                revision = it.revision + 1,
            )
        }
    }

    internal fun report(status: String, error: String? = null) {
        mutableState.update { it.copy(status = status, lastError = error) }
    }

    internal fun setJevReady(ready: Boolean) {
        mutableState.update { it.copy(jevReady = ready) }
    }

    internal fun setJevResult(status: String, captureId: Int?, flagged: Boolean? = null) {
        mutableState.update { it.copy(jevStatus = status, jevCaptureId = captureId, jevFlagged = flagged) }
    }

    internal fun result(text: String, capturedAt: Long) {
        mutableState.update {
            it.copy(
                latestText = text,
                lastCapturedAtMillis = capturedAt,
                captureCount = it.captureCount + 1,
                status = if (text.isBlank()) "Capture complete. OCR found no text." else "Capture complete. OCR text is ready.",
                lastError = null,
            )
        }
    }

    private fun saveSettings() {
        val current = state.value
        preferences?.edit()
            ?.putBoolean("consent", current.consentGranted)
            ?.putInt("consentVersion", CONSENT_VERSION)
            ?.putBoolean("enabled", current.monitoringEnabled)
            ?.remove("target")
            ?.apply()
    }
}
