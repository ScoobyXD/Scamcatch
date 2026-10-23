package com.antiscam.app.capture

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RunMode(val wireName: String) {
    DASHBOARD("dashboard"),
    STANDALONE("standalone"),
}

data class CaptureState(
    val consentGranted: Boolean = false,
    val monitoringEnabled: Boolean = false,
    val approvedPackages: Set<String> = emptySet(),
    val serviceConnected: Boolean = false,
    val status: String = "Allow capture to begin.",
    val lastCapturedAtMillis: Long? = null,
    val captureCount: Int = 0,
    val lastError: String? = null,
    val jevReady: Boolean = false,
    val jevStatus: String = "idle",
    val jevFlagged: Boolean? = null,
    val jevCaptureId: Int? = null,
    val voiceReady: Boolean = false,
    val voiceConsentGranted: Boolean = false,
    val warningActive: Boolean = false,
    val voiceStatus: String = "idle",
    // Run IDs and results stay in memory. Only standalone user intent can survive this process.
    val runId: String? = null,
    val runMode: RunMode? = null,
    val standaloneRunRequested: Boolean = false,
    val dashboardConnected: Boolean = false,
    // Every change that invalidates a pending capture increments this value.
    val revision: Long = 0,
)

/** Only setup and same-boot standalone intent persist. Run IDs, OCR and Jev results do not. */
object CaptureStore {
    private const val CONSENT_VERSION = 3
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    private var preferences: SharedPreferences? = null
    private var bootCount = -1

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("capture_settings", Context.MODE_PRIVATE)
        preferences = prefs
        val consent = prefs.getBoolean("consent", false) && prefs.getInt("consentVersion", 0) == CONSENT_VERSION
        val enabled = consent && prefs.getBoolean("enabled", false)
        bootCount = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: RuntimeException) {
            -1
        }
        val requested = prefs.getBoolean("standaloneRunRequested", false)
        val restoreRequested = requested && consent && enabled &&
            prefs.getBoolean("voiceConsent", false) && bootCount >= 0 &&
            prefs.getInt("standaloneBootCount", -1) == bootCount &&
            !wasExplicitlyStopped(context, prefs.getLong("standaloneRequestedAt", 0L))
        mutableState.value = CaptureState(
            consentGranted = consent,
            voiceConsentGranted = consent && prefs.getBoolean("voiceConsent", false),
            monitoringEnabled = enabled,
            approvedPackages = prefs.getStringSet("approvedPackages", emptySet())?.toSet().orEmpty(),
            standaloneRunRequested = restoreRequested,
            status = if (enabled) "Capture waits for an approved app." else "Monitoring is paused.",
        )
        if (requested && !restoreRequested) clearStandaloneRequest()
    }

    fun setApprovedPackages(packages: Set<String>) {
        val approved = packages.filter { it.isNotBlank() }.toSet()
        if (approved.isEmpty()) setStandaloneRunRequested(false)
        mutableState.update {
            if (it.approvedPackages == approved) it else it.copy(
                approvedPackages = approved,
                lastCapturedAtMillis = null,
                lastError = null,
                jevStatus = "idle",
                jevCaptureId = null,
                jevFlagged = null,
                status = if (approved.isEmpty()) "Choose an app to allow capture." else "Approved apps updated.",
                revision = it.revision + 1,
            )
        }
        saveSettings()
    }

    fun setConsent(granted: Boolean) {
        if (!granted) setStandaloneRunRequested(false)
        mutableState.update {
            it.copy(
                consentGranted = granted,
                voiceConsentGranted = granted && it.voiceConsentGranted,
                monitoringEnabled = granted && it.monitoringEnabled,
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
        if (!enabled) setStandaloneRunRequested(false)
        mutableState.update {
            val active = enabled && it.consentGranted
            it.copy(
                monitoringEnabled = active,
                status = when {
                    !it.consentGranted -> "Allow capture before enabling monitoring."
                    !active -> "Monitoring is paused."
                    it.approvedPackages.isEmpty() -> "Choose an app to allow capture."
                    !it.serviceConnected -> "Enable ScamCatch in Android accessibility settings."
                    it.runId == null -> "Ready. Start a run here or in the desktop dashboard."
                    else -> "Run active. Capture waits for an approved app."
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
        setStandaloneRunRequested(false)
        PhoneCredentials.clearKey(PhoneCredentials.Provider.JEV)
        PhoneCredentials.clearKey(PhoneCredentials.Provider.OPENAI)
        mutableState.update {
            CaptureState(serviceConnected = it.serviceConnected, dashboardConnected = it.dashboardConnected, revision = it.revision + 1)
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
                    it.approvedPackages.isEmpty() -> "Choose an app to allow capture."
                    it.runId == null -> "Ready. Start a run here or in the desktop dashboard."
                    else -> "Run active. Capture waits for an approved app."
                },
            )
        }
    }

    internal fun setDashboardConnected(connected: Boolean) {
        mutableState.update { it.copy(dashboardConnected = connected) }
    }

    internal fun setStandaloneRunRequested(requested: Boolean): Boolean {
        if (state.value.standaloneRunRequested == requested) return true
        val saved = if (requested) {
            preferences?.edit()
                ?.putBoolean("standaloneRunRequested", true)
                ?.putInt("standaloneBootCount", bootCount)
                ?.putLong("standaloneRequestedAt", System.currentTimeMillis())
                ?.commit() == true
        } else {
            clearStandaloneRequest()
        }
        if (!requested || saved) mutableState.update { it.copy(standaloneRunRequested = requested) }
        return saved
    }

    private fun clearStandaloneRequest(): Boolean = preferences?.edit()
        ?.remove("standaloneRunRequested")
        ?.remove("standaloneBootCount")
        ?.remove("standaloneRequestedAt")
        ?.commit() == true

    private fun wasExplicitlyStopped(context: Context, requestedAt: Long): Boolean {
        if (requestedAt <= 0L) return true
        return try {
            val exit = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            exit != null && exit.timestamp >= requestedAt && exit.reason in setOf(
                ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_USER_STOPPED,
                ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            )
        } catch (_: RuntimeException) {
            // Unknown stop history must not silently undo a deliberate stop.
            true
        }
    }

    internal fun setRunId(runId: String?, mode: RunMode? = null) {
        mutableState.update {
            if (it.runId == runId) it else it.copy(
                runId = runId,
                runMode = mode.takeIf { runId != null },
                jevStatus = "idle",
                jevCaptureId = null,
                jevFlagged = null,
                lastCapturedAtMillis = if (runId != null) null else it.lastCapturedAtMillis,
                captureCount = if (runId != null) 0 else it.captureCount,
                status = if (runId != null) "Run active. Open an approved app to start screen checks." else "Run stopped.",
                lastError = null,
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

    fun setVoiceConsent(granted: Boolean) {
        if (!granted) setStandaloneRunRequested(false)
        mutableState.update { it.copy(voiceConsentGranted = granted && it.consentGranted) }
        saveSettings()
    }

    internal fun setVoiceReady(ready: Boolean) {
        mutableState.update { it.copy(voiceReady = ready) }
    }

    internal fun setVoiceStatus(status: String) {
        mutableState.update { it.copy(voiceStatus = status) }
    }

    internal fun setWarningActive(active: Boolean) {
        mutableState.update {
            if (it.warningActive == active) it else it.copy(
                warningActive = active,
                status = if (active) "Scam warning open. Screenshots and OCR are paused." else "Warning closed. Capture can resume.",
                revision = it.revision + 1,
            )
        }
    }

    internal fun setJevResult(status: String, captureId: Int?, flagged: Boolean? = null) {
        mutableState.update { it.copy(jevStatus = status, jevCaptureId = captureId, jevFlagged = flagged) }
    }

    internal fun result(text: String, capturedAt: Long) {
        mutableState.update {
            it.copy(
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
            ?.putBoolean("voiceConsent", current.voiceConsentGranted)
            ?.putStringSet("approvedPackages", current.approvedPackages)
            ?.remove("target")
            ?.apply()
    }
}
