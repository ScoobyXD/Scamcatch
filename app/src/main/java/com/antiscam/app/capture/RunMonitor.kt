package com.antiscam.app.capture

import android.content.Context
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

/** One event model serves the live dashboard and its saved run record. No events go to Logcat. */
object RunMonitor {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false
    private var appVersion = "unknown"
    private var sequence = 0L
    private var captureId = 0
    private var session: RunSession? = null
    private var activeCapture: ActiveCapture? = null
    private var currentApp = "Unknown"
    private var currentPackage: String? = null
    private var lastExternalApp = "Unknown"
    private var typing = "idle"
    private var lastInteraction = "none"
    private var captureStatus = "idle"
    private var ocrStatus = "idle"
    private val counters = linkedMapOf("requests" to 0, "screenshots" to 0, "failed" to 0, "ocrCompleted" to 0, "ocrFailed" to 0, "skipped" to 0)

    private data class RunSession(val id: String, val startedAt: Long)
    private data class ActiveCapture(val id: Int, val phase: String, val attribution: Map<String, Any?>)

    fun initialize(context: Context) {
        CaptureStore.initialize(context)
        onMain {
            if (initialized) return@onMain
            initialized = true
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            appVersion = "${packageInfo.versionName ?: "unknown"} (${packageInfo.longVersionCode})"
            RunBridge.initialize(
                onConnect = { emit("snapshot") },
                onCommand = ::command,
                onDisconnect = { reason -> onMain { stop(reason) } },
            )
            scope.launch {
                CaptureStore.state.map {
                    listOf(it.consentGranted, it.monitoringEnabled, it.serviceConnected, it.revision)
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
        }
    }

    /** Capture callbacks must include their request runId. It is a guard, not event payload. */
    fun emit(type: String, details: Map<String, Any?> = emptyMap()) {
        onMain {
            val guard = details["runId"]
            if (guard != null && guard != session?.id) return@onMain
            if (type in CAPTURE_EVENTS && (session == null || guard != session?.id)) return@onMain
            val payload = details - "runId"
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
                    activeCapture = (payload["captureId"] as? Number)?.let {
                        ActiveCapture(it.toInt(), "screenshot_requested", payload)
                    }
                    increment("requests")
                }
                "screenshot_received" -> {
                    captureStatus = "received"
                    updateActiveCapture(payload, "screenshot_received")
                    increment("screenshots")
                }
                "capture_failed" -> {
                    captureStatus = "failed"
                    updateActiveCapture(payload, null)
                    increment("failed")
                }
                "capture_skipped" -> { captureStatus = "skipped"; if (session != null) increment("skipped") }
                "capture_discarded" -> {
                    captureStatus = "discarded"
                    if (ocrStatus == "processing") ocrStatus = "discarded"
                    updateActiveCapture(payload, null)
                }
                "pending_canceled" -> captureStatus = "canceled"
                "ocr_started" -> {
                    ocrStatus = "processing"
                    updateActiveCapture(payload, "ocr_processing")
                }
                "ocr_completed" -> {
                    ocrStatus = "completed"
                    updateActiveCapture(payload, null)
                    increment("ocrCompleted")
                }
                "ocr_failed" -> {
                    ocrStatus = "failed"
                    updateActiveCapture(payload, null)
                    increment("ocrFailed")
                }
            }
            emitNow(type, payload)
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
            "start" -> start(message)
            "stop" -> if (session == null) emitNow("command_error", mapOf("reason" to "no_active_run")) else stop("user_stopped")
            "snapshot" -> emitNow("snapshot", emptyMap())
            "pause" -> {
                stop("capture_paused")
                CaptureStore.setEnabled(false)
            }
            "resume" -> {
                if (!CaptureStore.state.value.consentGranted) emitNow("command_error", mapOf("reason" to "consent_required"))
                else CaptureStore.setEnabled(true)
            }
            else -> emitNow("command_error", mapOf("reason" to "unknown_command"))
        }
    }

    private fun start(message: JSONObject) {
        val state = CaptureStore.state.value
        val id = message.optString("runId")
        val reason = when {
            session != null -> "run_already_active"
            !RUN_ID.matches(id) -> "invalid_run_id"
            message.opt("recordOcr") != true -> "ocr_recording_acknowledgment_required"
            !state.consentGranted -> "consent_required"
            !state.monitoringEnabled -> "capture_paused"
            !state.serviceConnected -> "service_disconnected"
            else -> null
        }
        if (reason != null) {
            emitNow("command_error", mapOf("reason" to reason, "command" to "start"))
            return
        }
        captureId = 0
        activeCapture = null
        counters.keys.forEach { counters[it] = 0 }
        typing = "idle"
        lastInteraction = "none"
        captureStatus = "idle"
        ocrStatus = "idle"
        session = RunSession(id, SystemClock.elapsedRealtime())
        CaptureStore.setRunId(id)
        emitNow("run_started", mapOf("recordOcr" to true))
    }

    private fun stop(reason: String) {
        val finished = session ?: return
        activeCapture?.let { capture ->
            emit("capture_discarded", capture.attribution + mapOf(
                "runId" to finished.id,
                "captureId" to capture.id,
                "reason" to reason,
                "phase" to capture.phase,
                "screenshotOutcome" to if (capture.phase == "screenshot_requested") "unknown" else "received",
            ))
        }
        // The revision change below invalidates screenshot and OCR callbacks immediately.
        session = null
        CaptureStore.setRunId(null)
        typing = "idle"
        captureStatus = "stopped"
        ocrStatus = if (ocrStatus == "processing") "canceled" else ocrStatus
        emitNow("pending_canceled", mapOf("reason" to reason), finished)
        emitNow("run_stopped", mapOf("reason" to reason), finished)
    }

    private fun snapshot(): JSONObject {
        val config = CaptureStore.state.value
        return JSONObject().apply {
            put("runId", config.runId ?: JSONObject.NULL)
            put("currentApp", currentApp)
            put("currentPackage", currentPackage ?: JSONObject.NULL)
            put("lastExternalApp", lastExternalApp)
            put("typing", typing)
            put("lastInteraction", lastInteraction)
            put("captureStatus", captureStatus)
            put("ocrStatus", ocrStatus)
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
        val event = JSONObject().apply {
            put("protocol", 1)
            put("seq", ++sequence)
            put("timestampMs", System.currentTimeMillis())
            put("elapsedMs", eventSession?.let { SystemClock.elapsedRealtime() - it.startedAt } ?: 0L)
            put("runId", eventSession?.id ?: JSONObject.NULL)
            put("type", type)
            put("data", JSONObject().apply { details.forEach { (key, value) -> put(key, JSONObject.wrap(value)) } })
            put("state", snapshot())
        }
        RunBridge.send(event.toString())
    }

    private fun increment(name: String) { counters[name] = counters.getValue(name) + 1 }

    private fun updateActiveCapture(details: Map<String, Any?>, phase: String?) {
        val capture = activeCapture ?: return
        if (capture.id != (details["captureId"] as? Number)?.toInt()) return
        activeCapture = phase?.let { capture.copy(phase = it) }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }

    private val RUN_ID = Regex("[A-Za-z0-9_-]{1,96}")
    private val CAPTURE_EVENTS = setOf(
        "capture_requested", "screenshot_received", "capture_failed", "capture_discarded",
        "ocr_started", "ocr_completed", "ocr_failed", "capture_skipped", "capture_waiting", "pending_canceled",
    )
}
