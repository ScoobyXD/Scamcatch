package com.antiscam.app.capture

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Live diagnostics serve the dashboard UI and are never persisted by the dashboard. No events go to Logcat. */
object RunMonitor {
    private const val RECORDING_POLICY = "incident_fields_only_v1"
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false
    private var protectionServiceConnected = false
    private lateinit var appContext: Context
    private var appVersion = "unknown"
    private var sequence = 0L
    private var captureId = 0
    private var session: RunSession? = null
    private val sessions = mutableMapOf<String, RunSession>()
    private var currentApp = "Not capturing"
    private var currentPackage: String? = null
    private var lastExternalApp = "Not capturing"
    private var typing = "idle"
    private var lastInteraction = "none"
    private var captureStatus = "idle"
    private var ocrStatus = "idle"
    private val counters = linkedMapOf("requests" to 0, "screenshots" to 0, "failed" to 0, "ocrCompleted" to 0, "ocrFailed" to 0, "skipped" to 0)

    private data class RunSession(val id: String, val startedAt: Long, val mode: RunMode, val voiceScript: VoiceScripts.VoiceScript)

    fun activeVoiceScript(): VoiceScripts.VoiceScript? = session?.voiceScript

    private fun scriptRecord(script: VoiceScripts.VoiceScript) = mapOf(
        "id" to script.id, "name" to script.name, "revision" to script.revision,
    )

    fun initialize(context: Context) {
        CaptureStore.initialize(context)
        onMain {
            if (initialized) return@onMain
            initialized = true
            appContext = context.applicationContext
            val accessibilityObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    if (CaptureStore.state.value.standaloneRunRequested && !accessibilityEnabled()) {
                        stop("accessibility_disabled")
                    }
                }
            }
            appContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, accessibilityObserver,
            )
            appContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false, accessibilityObserver,
            )
            if (CaptureStore.state.value.standaloneRunRequested && !accessibilityEnabled()) {
                CaptureStore.setStandaloneRunRequested(false)
            }
            PhoneCredentials.initialize(appContext)
            VoiceScripts.initialize(appContext)
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            appVersion = "${packageInfo.versionName ?: "unknown"} (${packageInfo.longVersionCode})"
            RunBridge.initialize(
                onConnect = {
                    CaptureStore.setDashboardConnected(true)
                    emit("snapshot")
                },
                onCommand = ::command,
                onDisconnect = { reason -> onMain {
                    CaptureStore.setDashboardConnected(false)
                    if (session?.mode == RunMode.DASHBOARD) stop(reason)
                } },
            )
            scope.launch {
                CaptureStore.state.map {
                    listOf(it.consentGranted, it.monitoringEnabled, it.serviceConnected, it.revision,
                        it.jevReady, it.jevStatus, it.jevFlagged, it.jevCaptureId,
                        it.voiceReady, it.voiceConsentGranted, it.warningActive, it.voiceStatus,
                        it.standaloneRunRequested)
                }.distinctUntilChanged().collect {
                    val state = CaptureStore.state.value
                    if (!state.consentGranted || !state.monitoringEnabled || !state.serviceConnected ||
                        currentPackage !in state.approvedPackages) clearForeground()
                    session?.let { active ->
                        val reason = when {
                            !state.consentGranted -> "consent_removed"
                            !state.monitoringEnabled -> "capture_paused"
                            active.mode == RunMode.STANDALONE && !state.standaloneRunRequested -> "user_stopped"
                            !state.serviceConnected -> "service_disconnected"
                            state.runId != active.id -> "settings_reset"
                            else -> null
                        }
                        if (reason != null) stop(reason, preserveStandaloneRequest =
                            reason == "service_disconnected" && accessibilityEnabled())
                    }
                    restoreStandaloneIfReady()
                    emitNow("state_changed", emptyMap())
                }
            }
            scope.launch {
                PhoneCredentials.status.collect {
                    restoreStandaloneIfReady()
                    emitNow("state_changed", emptyMap())
                }
            }
            scope.launch {
                VoiceScripts.state.collect {
                    restoreStandaloneIfReady()
                    emitNow("state_changed", emptyMap())
                }
            }
        }
    }

    /** Capture callbacks retain their original run after capture scheduling stops. */
    fun emit(type: String, details: Map<String, Any?> = emptyMap()) {
        onMain {
            val guard = details["runId"]
            val eventSession = when (guard) {
                null -> session
                is String -> sessions[guard] ?: return@onMain
                else -> return@onMain
            }
            if (type in CAPTURE_EVENTS && (eventSession == null || guard != eventSession.id)) return@onMain
            val payload = details - "runId"
            if (eventSession !== session) {
                if (type in CAPTURE_RESULT_EVENTS || type == "voice_audio_summary") emitNow(type, payload, eventSession)
                return@onMain
            }
            when (type) {
                "app_changed" -> {
                    currentPackage = payload["packageName"] as? String
                    if (currentPackage !in CaptureStore.state.value.approvedPackages) {
                        clearForeground()
                        return@onMain
                    }
                    currentApp = payload["appLabel"] as? String ?: "Unknown"
                    if (currentPackage != null && currentPackage != "com.antiscam.app") lastExternalApp = currentApp
                }
                "interaction" -> lastInteraction = payload["kind"] as? String ?: "unknown"
                "typing_state" -> typing = payload["value"] as? String ?: "idle"
                "capture_waiting" -> captureStatus = "waiting"
                "capture_requested" -> {
                    captureStatus = "requested"
                    increment("requests")
                }
                "screenshot_received" -> {
                    captureStatus = "received"
                    increment("screenshots")
                }
                "capture_failed" -> {
                    captureStatus = "failed"
                    increment("failed")
                }
                "capture_skipped" -> { captureStatus = "skipped"; if (session != null) increment("skipped") }
                "capture_discarded" -> {
                    captureStatus = "discarded"
                    if (ocrStatus == "processing") ocrStatus = "discarded"
                }
                "pending_canceled" -> captureStatus = "canceled"
                "ocr_started" -> {
                    ocrStatus = "processing"
                }
                "ocr_completed" -> {
                    ocrStatus = "completed"
                    increment("ocrCompleted")
                }
                "ocr_failed" -> {
                    ocrStatus = "failed"
                    increment("ocrFailed")
                }
            }
            emitNow(type, payload, eventSession)
        }
    }

    fun nextCaptureId(): Int {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (session == null) return 0
        return ++captureId
    }

    /** Clear live identity without recording the app that caused capture to stop. */
    fun clearForeground() = onMain {
        if (currentPackage == null && currentApp == "Not capturing" && lastExternalApp == "Not capturing") return@onMain
        currentPackage = null
        currentApp = "Not capturing"
        lastExternalApp = "Not capturing"
        lastInteraction = "none"
        typing = "idle"
        emitNow("state_changed", emptyMap())
    }

    private fun command(line: String) = onMain {
        val message = try { JSONObject(line) } catch (_: Exception) {
            emitNow("command_error", mapOf("reason" to "invalid_json"))
            return@onMain
        }
        when (message.optString("command")) {
            "jev_key", "openai_key" -> {
                val provider = if (message.optString("command") == "openai_key")
                    PhoneCredentials.Provider.OPENAI else PhoneCredentials.Provider.JEV
                val key = message.opt("apiKey") as? String
                val rememberOnPhone = if (message.has("rememberOnPhone")) message.opt("rememberOnPhone") else false
                when {
                    rememberOnPhone !is Boolean ->
                        emitNow("command_error", mapOf("reason" to "invalid_remember_on_phone"))
                    !provider.available ->
                        emitNow("command_error", mapOf("reason" to "${provider.id}_unavailable"))
                    key == null -> emitNow("command_error", mapOf("reason" to "invalid_${provider.id}_key"))
                    rememberOnPhone && key.isEmpty() -> PhoneCredentials.clearKey(provider)
                    rememberOnPhone -> if (!PhoneCredentials.setKey(provider, key, true)) {
                        emitNow("command_error", mapOf("reason" to "invalid_${provider.id}_key"))
                    }
                    key.isEmpty() -> provider.clear()
                    !provider.set(key) ->
                        emitNow("command_error", mapOf("reason" to "invalid_${provider.id}_key"))
                }
                // Only readiness and storage status enter the event stream. Never include the received command.
                emitNow("state_changed", emptyMap())
            }
            "voice_scripts", "voice_script_select" -> {
                val reason = when {
                    session != null || CaptureStore.state.value.standaloneRunRequested ->
                        "Stop the current run before changing voice scripts."
                    message.optString("command") == "voice_scripts" -> {
                        val markdown = message.opt("markdown") as? String
                        if (markdown == null) "Send a Markdown script file." else VoiceScripts.importMarkdown(markdown)
                    }
                    else -> {
                        val id = message.optString("scriptId")
                        val script = VoiceScripts.state.value.scripts.find { it.id == id }
                        if (script == null || script.revision != message.optString("revision"))
                            "Copy the current script file to the phone before selecting this version."
                        else VoiceScripts.select(id)
                    }
                }
                if (reason != null) emitNow("command_error", mapOf("reason" to reason))
            }
            "start" -> {
                val reason = start(message.optString("runId"), RunMode.DASHBOARD)
                if (reason != null) emitNow("command_error", mapOf("reason" to reason, "command" to "start"))
            }
            "stop" -> when {
                CaptureStore.state.value.standaloneRunRequested -> emitNow("command_error", mapOf("reason" to "Stop the phone run in ScamCatch."))
                session == null -> emitNow("command_error", mapOf("reason" to "no_active_run"))
                else -> stop("user_stopped")
            }
            "snapshot" -> emitNow("snapshot", emptyMap())
            "pause" -> {
                if (CaptureStore.state.value.standaloneRunRequested) {
                    emitNow("command_error", mapOf("reason" to "Stop the phone run in ScamCatch."))
                } else {
                    stop("capture_paused")
                    CaptureStore.setEnabled(false)
                }
            }
            "resume" -> {
                if (!CaptureStore.state.value.consentGranted) emitNow("command_error", mapOf("reason" to "consent_required"))
                else CaptureStore.setEnabled(true)
            }
            else -> emitNow("command_error", mapOf("reason" to "unknown_command"))
        }
    }

    fun startStandalone(): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!initialized) return "Reopen ScamCatch before starting a run."
        if (session != null || CaptureStore.state.value.standaloneRunRequested) {
            return "Stop the current run before starting another."
        }
        val error = standaloneSetupError() ?: captureSetupError()?.let(::phoneStartError)
        if (error != null) return error
        if (!CaptureStore.setStandaloneRunRequested(true)) return "Could not save the phone run request. Try again."
        return resumeRequestedProtection()
    }

    /** Reopening setup can resume a delayed system restart, while the activity is visible. */
    fun resumeRequestedProtection(): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!initialized || !CaptureStore.state.value.standaloneRunRequested || protectionServiceConnected) return null
        val serviceError = ProtectionService.start(appContext)
        if (serviceError != null) {
            CaptureStore.setStandaloneRunRequested(false)
            CaptureStore.report("Monitoring stopped.", serviceError)
            return serviceError
        }
        if (session == null) CaptureStore.report("Starting phone protection…")
        restoreStandaloneIfReady()
        return null
    }

    private fun standaloneSetupError(): String? {
        val state = CaptureStore.state.value
        return when {
            !JevAnalysis.available || !LiveVoice.available -> "Install ScamCatch to use Jev and voice."
            !state.jevReady -> "Set the Jev API key in phone setup."
            !state.voiceReady -> "Set the OpenAI API key in phone setup."
            !state.voiceConsentGranted -> "Allow automatic voice warnings in phone setup."
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Allow microphone access in phone setup."
            else -> null
        }
    }

    private fun phoneStartError(reason: String): String = when (reason) {
        "run_already_active" -> "Stop the current run before starting another."
        "consent_required" -> "Allow screen capture and Jev analysis first."
        "capture_paused" -> "Resume monitoring before starting a run."
        "service_disconnected" -> "Enable ScamCatch in Android Accessibility settings."
        "approved_apps_required" -> "Select at least one approved app in phone setup."
        else -> reason
    }

    fun setProtectionServiceConnected(connected: Boolean) = onMain {
        protectionServiceConnected = connected
        if (connected) restoreStandaloneIfReady()
        else if (session?.mode == RunMode.STANDALONE) {
            stop("protection_service_stopped", preserveStandaloneRequest = true)
        }
        emitNow("state_changed", emptyMap())
    }

    private fun restoreStandaloneIfReady() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!initialized || !protectionServiceConnected || session != null ||
            !CaptureStore.state.value.standaloneRunRequested) return
        if (!accessibilityEnabled()) {
            stop("accessibility_disabled")
            return
        }
        val scripts = VoiceScripts.state.value
        if (PhoneCredentials.status.value.values.any { it.working } || scripts.working) return
        val setupError = standaloneSetupError()
        if (setupError != null || !scripts.ready) {
            stop("phone_setup_unavailable")
            CaptureStore.report("Phone protection stopped.", setupError ?: "Load a valid voice script before starting another run.")
            return
        }
        if (captureSetupError() != null) return
        start("phone_${UUID.randomUUID()}", RunMode.STANDALONE)
    }

    private fun accessibilityEnabled(): Boolean {
        if (Settings.Secure.getInt(appContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val component = ComponentName(appContext, TypingCaptureService::class.java)
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    }

    fun stopRun() = onMain { stop("user_stopped") }

    private fun captureSetupError(): String? {
        val state = CaptureStore.state.value
        return when {
            !state.consentGranted -> "consent_required"
            state.approvedPackages.isEmpty() -> "approved_apps_required"
            !state.monitoringEnabled -> "capture_paused"
            !state.serviceConnected -> "service_disconnected"
            VoiceScripts.selected() == null -> "Wait for a valid voice script to load before starting a run."
            else -> null
        }
    }

    private fun start(id: String, mode: RunMode): String? {
        val script = VoiceScripts.selected()
        val reason = when {
            session != null -> "run_already_active"
            !RUN_ID.matches(id) -> "invalid_run_id"
            id in sessions -> "run_id_already_used"
            mode == RunMode.DASHBOARD && CaptureStore.state.value.standaloneRunRequested -> "run_already_active"
            else -> captureSetupError()
        }
        if (reason != null) return reason
        captureId = 0
        counters.keys.forEach { counters[it] = 0 }
        typing = "idle"
        lastInteraction = "none"
        captureStatus = "idle"
        ocrStatus = "idle"
        session = RunSession(id, SystemClock.elapsedRealtime(), mode, requireNotNull(script)).also { sessions[id] = it }
        CaptureStore.setRunId(id, mode)
        emitNow("run_started", mapOf("recordingPolicy" to RECORDING_POLICY, "runMode" to mode.wireName,
            "voiceScript" to scriptRecord(script)))
        return null
    }

    private fun stop(reason: String, preserveStandaloneRequest: Boolean = false) {
        val finished = session
        val wasRequested = CaptureStore.state.value.standaloneRunRequested
        session = null
        if (!preserveStandaloneRequest) CaptureStore.setStandaloneRunRequested(false)
        if (finished == null) {
            if (wasRequested && !preserveStandaloneRequest) CaptureStore.report("Run stopped.")
            return
        }
        // Stop new capture scheduling. Existing callbacks retain this run's event envelope.
        CaptureStore.setRunId(null)
        typing = "idle"
        captureStatus = "stopped"
        ocrStatus = "idle"
        emitNow("pending_canceled", mapOf("reason" to reason), finished)
        emitNow("run_stopped", mapOf("reason" to reason), finished)
    }

    private fun snapshot(): JSONObject {
        val config = CaptureStore.state.value
        return JSONObject().apply {
            put("runId", config.runId ?: JSONObject.NULL)
            put("runMode", config.runMode?.wireName ?: JSONObject.NULL)
            put("standaloneRunRequested", config.standaloneRunRequested)
            put("protectionServiceConnected", protectionServiceConnected)
            put("voiceScripts", VoiceScripts.snapshot())
            put("voiceScript", session?.voiceScript?.let {
                JSONObject().put("id", it.id).put("name", it.name).put("revision", it.revision)
            } ?: JSONObject.NULL)
            put("dashboardConnected", config.dashboardConnected)
            put("currentApp", currentApp)
            put("currentPackage", currentPackage ?: JSONObject.NULL)
            put("lastExternalApp", lastExternalApp)
            put("typing", typing)
            put("lastInteraction", lastInteraction)
            put("captureStatus", if (config.warningActive) "paused_for_warning" else captureStatus)
            put("ocrStatus", if (config.warningActive) "paused_for_warning" else ocrStatus)
            put("voiceReady", config.voiceReady)
            put("voiceConsentGranted", config.voiceConsentGranted)
            put("warningActive", config.warningActive)
            put("voiceStatus", config.voiceStatus)
            put("jevReady", config.jevReady)
            put("jevStatus", config.jevStatus)
            put("jevFlagged", config.jevFlagged ?: JSONObject.NULL)
            put("jevCaptureId", config.jevCaptureId ?: JSONObject.NULL)
            put("phoneKeys", JSONObject().apply {
                PhoneCredentials.status.value.forEach { (provider, status) ->
                    put(provider.id, JSONObject().apply {
                        put("working", status.working)
                        put("saved", status.saved ?: JSONObject.NULL)
                        put("message", status.message ?: JSONObject.NULL)
                        put("error", status.error)
                    })
                }
            })
            put("counters", JSONObject(counters.toMap()))
            put("captureScope", "display")
            put("recordingPolicy", RECORDING_POLICY)
            put("approvedAppCount", config.approvedPackages.size)
            put("consentGranted", config.consentGranted)
            put("monitoringEnabled", config.monitoringEnabled)
            put("serviceConnected", config.serviceConnected)
            put("appVersion", appVersion)
            put("androidApi", Build.VERSION.SDK_INT)
        }
    }

    private fun emitNow(type: String, details: Map<String, Any?>, eventSession: RunSession? = session) {
        // Live diagnostics are transient. The dashboard persists only separately validated incident fields.
        val phoneEvent = eventSession?.mode == RunMode.STANDALONE
        val event = JSONObject().apply {
            put("protocol", 1)
            put("seq", ++sequence)
            put("timestampMs", System.currentTimeMillis())
            put("elapsedMs", eventSession?.let { SystemClock.elapsedRealtime() - it.startedAt } ?: 0L)
            put("runId", eventSession?.id ?: JSONObject.NULL)
            put("type", if (phoneEvent && type != "snapshot" && type != "command_error") "state_changed" else type)
            put("data", JSONObject().apply {
                if (!phoneEvent || type == "command_error") details.forEach { (key, value) ->
                    if (key in EVENT_FIELDS) put(key, JSONObject.wrap(value))
                }
            })
            put("state", snapshot())
        }
        RunBridge.send(event.toString())
    }

    private fun increment(name: String) { counters[name] = counters.getValue(name) + 1 }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }

    private val RUN_ID = Regex("[A-Za-z0-9_-]{1,96}")
    // Keep raw OCR, region contents, warning evidence, and provider messages inside the phone.
    private val EVENT_FIELDS = setOf(
        "captureId", "trigger", "packageName", "appLabel", "windowId", "captureScope",
        "sourceAttribution", "sourceLabel", "maskedRegions", "characters", "regionCount",
        "durationMs", "delayMs", "reason", "kind", "eventType", "value", "status",
        "model", "questionSetVersion", "threshold", "questions", "answers", "flagged",
        "runMode", "recordingPolicy", "voiceScript", "command", "phase", "elapsedMs",
        "httpStatus", "code", "errorType", "param", "voice", "scriptId", "scriptRevision", "transport",
        "finalizationConfirmed", "usageSeconds", "rtpBytesReceived", "rtpBytesSent",
        "rtpPacketsReceived", "rtpPacketsLost", "rtpJitterSeconds", "jitterBufferDelaySeconds",
        "jitterBufferEmittedCount", "concealedSamples", "totalSamplesReceived",
        "remoteAudioEnergy", "remoteAudioDurationSeconds",
    )
    private val CAPTURE_RESULT_EVENTS = setOf(
        "screenshot_received", "capture_failed", "capture_discarded",
        "ocr_started", "ocr_completed", "ocr_failed",
        "jev_started", "jev_completed", "jev_failed", "jev_skipped", "jev_canceled",
    )
    private val CAPTURE_EVENTS = CAPTURE_RESULT_EVENTS + setOf(
        "capture_requested", "capture_skipped", "capture_waiting", "pending_canceled",
    )
}
