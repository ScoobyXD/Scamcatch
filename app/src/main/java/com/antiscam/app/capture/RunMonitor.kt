package com.antiscam.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** One event model serves the live dashboard and its saved run record. No events go to Logcat. */
object RunMonitor {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false
    private lateinit var appContext: Context
    private var appVersion = "unknown"
    private var sequence = 0L
    private var captureId = 0
    private var session: RunSession? = null
    private val sessions = mutableMapOf<String, RunSession>()
    private var currentApp = "Unknown"
    private var currentPackage: String? = null
    private var lastExternalApp = "Unknown"
    private var typing = "idle"
    private var lastInteraction = "none"
    private var captureStatus = "idle"
    private var ocrStatus = "idle"
    private val counters = linkedMapOf("requests" to 0, "screenshots" to 0, "failed" to 0, "ocrCompleted" to 0, "ocrFailed" to 0, "skipped" to 0)

    private data class RunSession(val id: String, val startedAt: Long, val mode: RunMode, val voiceScript: VoiceScripts.VoiceScript)

    fun activeVoiceScript(): VoiceScripts.VoiceScript? = session?.voiceScript

    private fun scriptRecord(script: VoiceScripts.VoiceScript) = mapOf(
        "id" to script.id, "name" to script.name, "revision" to script.revision, "instructions" to script.instructions,
    )

    fun initialize(context: Context) {
        CaptureStore.initialize(context)
        onMain {
            if (initialized) return@onMain
            initialized = true
            appContext = context.applicationContext
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
                        it.voiceReady, it.voiceConsentGranted, it.warningActive, it.voiceStatus)
                }.distinctUntilChanged().collect {
                    val state = CaptureStore.state.value
                    session?.let { active ->
                        val reason = when {
                            !state.consentGranted -> "consent_removed"
                            !state.monitoringEnabled -> "capture_paused"
                            !state.serviceConnected -> "service_disconnected"
                            state.runId != active.id -> "settings_reset"
                            else -> null
                        }
                        if (reason != null) stop(reason)
                    }
                    emitNow("state_changed", emptyMap())
                }
            }
            scope.launch {
                PhoneCredentials.status.collect {
                    emitNow("state_changed", emptyMap())
                }
            }
            scope.launch {
                VoiceScripts.state.collect { emitNow("state_changed", emptyMap()) }
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
                    session != null -> "Stop the current run before changing voice scripts."
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
                val reason = start(message.optString("runId"), RunMode.DASHBOARD, message.opt("recordOcr") == true)
                if (reason != null) emitNow("command_error", mapOf("reason" to reason, "command" to "start"))
            }
            "stop" -> when {
                session?.mode == RunMode.STANDALONE -> emitNow("command_error", mapOf("reason" to "Stop the phone run in Antiscam."))
                session == null -> emitNow("command_error", mapOf("reason" to "no_active_run"))
                else -> stop("user_stopped")
            }
            "snapshot" -> emitNow("snapshot", emptyMap())
            "pause" -> {
                if (session?.mode == RunMode.STANDALONE) {
                    emitNow("command_error", mapOf("reason" to "Stop the phone run in Antiscam."))
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
        if (!initialized) return "Reopen Antiscam before starting a run."
        val state = CaptureStore.state.value
        val setupError = when {
            !JevAnalysis.available || !LiveVoice.available -> "Install Antiscam Dev to use Jev and voice."
            !state.jevReady -> "Set the Jev API key in phone setup."
            !state.voiceReady -> "Set the OpenAI API key in phone setup."
            !state.voiceConsentGranted -> "Allow automatic voice warnings in phone setup."
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Allow microphone access in phone setup."
            else -> null
        }
        if (setupError != null) return setupError
        return when (val reason = start("phone_${UUID.randomUUID()}", RunMode.STANDALONE, false)) {
            null -> null
            "run_already_active" -> "Stop the current run before starting another."
            "consent_required" -> "Allow screen capture and Jev analysis first."
            "capture_paused" -> "Resume monitoring before starting a run."
            "service_disconnected" -> "Enable Antiscam in Android Accessibility settings."
            else -> "Could not start the run: $reason"
        }
    }

    fun stopRun() = onMain { stop("user_stopped") }

    private fun start(id: String, mode: RunMode, recordOcr: Boolean): String? {
        val state = CaptureStore.state.value
        val script = VoiceScripts.selected()
        val reason = when {
            session != null -> "run_already_active"
            !RUN_ID.matches(id) -> "invalid_run_id"
            id in sessions -> "run_id_already_used"
            mode == RunMode.DASHBOARD && !recordOcr -> "ocr_recording_acknowledgment_required"
            !state.consentGranted -> "consent_required"
            !state.monitoringEnabled -> "capture_paused"
            !state.serviceConnected -> "service_disconnected"
            script == null -> "Wait for a valid voice script to load before starting a run."
            else -> null
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
        emitNow("run_started", mapOf("recordOcr" to recordOcr, "runMode" to mode.wireName, "voiceScript" to scriptRecord(script)))
        return null
    }

    private fun stop(reason: String) {
        val finished = session ?: return
        // Stop new capture scheduling. Existing callbacks retain this run's event envelope.
        session = null
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
            put("consentGranted", config.consentGranted)
            put("monitoringEnabled", config.monitoringEnabled)
            put("serviceConnected", config.serviceConnected)
            put("appVersion", appVersion)
            put("androidApi", Build.VERSION.SDK_INT)
        }
    }

    private fun emitNow(type: String, details: Map<String, Any?>, eventSession: RunSession? = session) {
        // Phone runs expose live status only. A desktop run must explicitly allow OCR recording.
        val phoneEvent = eventSession?.mode == RunMode.STANDALONE
        val event = JSONObject().apply {
            put("protocol", 1)
            put("seq", ++sequence)
            put("timestampMs", System.currentTimeMillis())
            put("elapsedMs", eventSession?.let { SystemClock.elapsedRealtime() - it.startedAt } ?: 0L)
            put("runId", eventSession?.id ?: JSONObject.NULL)
            put("type", if (phoneEvent && type != "snapshot" && type != "command_error") "state_changed" else type)
            put("data", JSONObject().apply {
                if (!phoneEvent || type == "command_error") details.forEach { (key, value) -> put(key, JSONObject.wrap(value)) }
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
    private val CAPTURE_RESULT_EVENTS = setOf(
        "screenshot_received", "capture_failed", "capture_discarded",
        "ocr_started", "ocr_completed", "ocr_failed",
        "jev_started", "jev_completed", "jev_failed", "jev_skipped", "jev_canceled",
    )
    private val CAPTURE_EVENTS = CAPTURE_RESULT_EVENTS + setOf(
        "capture_requested", "capture_skipped", "capture_waiting", "pending_canceled",
    )
}
