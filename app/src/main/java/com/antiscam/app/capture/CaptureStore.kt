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
    val selectedPackage: String = DEFAULT_TARGET_PACKAGE,
    val serviceConnected: Boolean = false,
    val status: String = "Allow local capture to begin.",
    val latestText: String = "",
    val lastCapturedAtMillis: Long? = null,
    val captureCount: Int = 0,
    val lastError: String? = null,
    // Every change that invalidates a pending capture increments this value.
    val revision: Long = 0,
)

const val DEFAULT_TARGET_PACKAGE = "com.antiscam.fixture"

/** Only settings persist. Screenshots, OCR text, and diagnostics stay in memory. */
object CaptureStore {
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    private var preferences: SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("capture_settings", Context.MODE_PRIVATE)
        preferences = prefs
        val consent = prefs.getBoolean("consent", false)
        val enabled = consent && prefs.getBoolean("enabled", false)
        mutableState.value = CaptureState(
            consentGranted = consent,
            monitoringEnabled = enabled,
            selectedPackage = prefs.getString("target", DEFAULT_TARGET_PACKAGE) ?: DEFAULT_TARGET_PACKAGE,
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
                status = if (granted) "Local capture is allowed. Enable monitoring to begin." else "Consent removed. Capture is off.",
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
                    !it.consentGranted -> "Allow local capture before enabling monitoring."
                    !active -> "Monitoring is paused."
                    !it.serviceConnected -> "Enable Antiscam in Android accessibility settings."
                    else -> "Ready. Type in the selected app."
                },
                lastError = null,
                revision = it.revision + 1,
            )
        }
        saveSettings()
    }

    fun setTargetPackage(packageName: String) {
        val target = packageName.trim()
        if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(target) || target == "com.antiscam.app") {
            report("The target package is invalid.", "Choose an app other than Antiscam.")
            return
        }
        mutableState.update {
            it.copy(
                selectedPackage = target,
                latestText = "",
                lastCapturedAtMillis = null,
                captureCount = 0,
                status = "Selected app changed. Type in that app to test capture.",
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
                status = "Result cleared. Only new typing can start another capture.",
                revision = it.revision + 1,
            )
        }
    }

    fun reset() {
        mutableState.update {
            CaptureState(serviceConnected = it.serviceConnected, revision = it.revision + 1)
        }
        saveSettings()
    }

    internal fun connected(connected: Boolean) {
        mutableState.update {
            it.copy(
                serviceConnected = connected,
                status = when {
                    !connected -> "Accessibility service is disconnected."
                    !it.consentGranted -> "Allow local capture to begin."
                    !it.monitoringEnabled -> "Monitoring is paused."
                    else -> "Ready. Type in the selected app."
                },
            )
        }
    }

    internal fun report(status: String, error: String? = null) {
        mutableState.update { it.copy(status = status, lastError = error) }
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
            ?.putBoolean("enabled", current.monitoringEnabled)
            ?.putString("target", current.selectedPackage)
            ?.apply()
    }
}
