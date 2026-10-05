package com.antiscam.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Accessibility events drive observation, capture timers, and the run record. */
class TypingCaptureService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val captureWindowManager by lazy {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return@lazy null
        createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recognizer: TextRecognizer
    private var typingTimer: Runnable? = null
    private var settleTimer: Runnable? = null
    private var settling: Request? = null
    private var settleStartedAt: Long? = null
    private var drainTimer: Runnable? = null
    private var pending: Request? = null
    private var generation = 0L
    private var inFlight = false
    private var destroyed = false
    private var lastAttemptAt: Long? = null
    private var foregroundPackage: String? = null
    private var foregroundWindowId: Int? = null
    private var foregroundReported = false
    private var typing = false
    private var inputMethodPackages = emptySet<String>()
    private val labels = mutableMapOf<String, String>()
    private var focusedInput: InputFingerprint? = null
    private var passiveCaptureBlocked = false

    // Only a transient fingerprint is retained. Field text never enters events or logs.
    private data class InputFingerprint(val node: Int, val length: Int, val hash: Long)

    private data class Request(
        val generation: Long,
        val revision: Long,
        val runId: String,
        val target: String,
        val windowId: Int,
        val trigger: String,
        val warningGeneration: Long = ScamIntervention.generation,
    )

    private data class WindowRegion(
        val id: Int,
        val layer: Int,
        val type: Int,
        val bounds: Rect,
        val packageName: String?,
        val appLabel: String?,
        val sourceKind: String,
    )

    private data class MaskedRegion(val bounds: Rect, val reason: String) {
        fun details(): Map<String, Any?> = mapOf("bounds" to bounds.details(), "reason" to reason)
    }

    private data class DisplayPlan(
        val bounds: Rect,
        val rotation: Int,
        val windows: List<WindowRegion>,
        val maskedRegions: List<MaskedRegion>,
    )

    override fun onCreate() {
        super.onCreate()
        CaptureStore.initialize(this)
        RunMonitor.initialize(this)
        ScamIntervention.bind(this)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        inputMethodPackages = getSystemService(InputMethodManager::class.java)
            .inputMethodList.map { it.packageName }.toSet()
        scope.launch {
            CaptureStore.state.map { it.revision }.distinctUntilChanged().collect {
                invalidate("Run or capture settings changed.")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureStore.connected(true)
        observeForeground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || destroyed) return
        observeForeground()
        if (CaptureStore.state.value.warningActive) return
        val eventPackage = event.packageName?.toString()
        // Keyboard and background events do not replace the visible app's interaction.
        if (eventPackage != null && eventPackage in inputMethodPackages) return
        val windowEvent = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!windowEvent && (eventPackage != foregroundPackage || event.windowId != foregroundWindowId)) return
        val inferredEdit = observeFocusedInput(event)
        val kind = if (inferredEdit) "typing" else when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "typing"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "tap"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "long_press"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "focus"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "selection"
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "text_selection"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "window"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
            else -> return
        }
        if (kind != "content" && kind != "window") passiveCaptureBlocked = false
        if (kind != "content") RunMonitor.emit("interaction", mapOf(
            "kind" to kind, "packageName" to foregroundPackage,
            "eventType" to AccessibilityEvent.eventTypeToString(event.eventType),
        ))

        val firstCharacter = kind == "typing" && !typing
        if (kind == "typing") {
            setTyping(true)
            typingTimer?.let(handler::removeCallbacks)
            clearSettling()
            // A new character supersedes an unexecuted settled capture, not its initial capture.
            if (pending?.trigger != "typing_start") cancelPending("Typing continued before the settled capture.")
        }

        // A typing burst owns its trailing capture, including suggestion and selection updates.
        if (typing && kind != "typing") return
        val request = requestFor(event, kind)
        if (kind == "typing") {
            typingTimer = Runnable {
                typingTimer = null
                setTyping(false)
                request?.let { queueCapture(it.copy(trigger = "typing_pause")) }
            }.also { handler.postDelayed(it, TYPING_PAUSE_MS) }
            if (firstCharacter && request != null) queueCapture(request.copy(trigger = "typing_start"))
        } else if (request != null) {
            scheduleSettled(request)
        }
    }

    /** Some apps report edits as parent content changes instead of text-change events. */
    private fun observeFocusedInput(event: AccessibilityEvent): Boolean {
        val state = CaptureStore.state.value
        if (state.runId == null || !state.consentGranted || !state.monitoringEnabled || state.warningActive) {
            focusedInput = null
            return false
        }
        if (event.eventType !in INPUT_OBSERVATION_EVENTS) return false
        val root = rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != foregroundPackage || root.windowId != foregroundWindowId) return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            try {
                if (focused == null || !focused.isFocused || !focused.isEditable) {
                    focusedInput = null
                    return false
                }
                val content = focused.text ?: return false
                // Hash characters directly. Do not allocate or retain a copy of field text.
                var hash = -3750763034362895579L
                for (index in 0 until content.length) hash = (hash xor content[index].code.toLong()) * 1099511628211L
                val current = InputFingerprint(focused.hashCode(), content.length, hash)
                val previous = focusedInput
                focusedInput = current
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) return false
                val changed = previous != null && previous.node == current.node && previous != current
                val firstTextSignal = previous == null &&
                    event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0
                return changed || firstTextSignal
            } finally {
                focused?.recycleCompat()
            }
        } finally {
            root.recycleCompat()
        }
    }

    private fun requestFor(event: AccessibilityEvent, kind: String): Request? {
        val state = CaptureStore.state.value
        // Keep observation live between runs, without generating capture work or skip events.
        if (state.runId == null || !state.consentGranted || !state.monitoringEnabled || state.warningActive) return null
        val target = foregroundPackage ?: return null
        val windowId = foregroundWindowId ?: return null
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            (event.packageName?.toString() != target || event.windowId != windowId)) return null
        val trigger = if (kind == "typing") "typing_start" else "${kind}_settled"
        val request = Request(generation, state.revision, state.runId, target,
            windowId, trigger)
        if (kind == "content" && passiveCaptureBlocked) {
            // A new content event can resume capture once the window becomes eligible.
            // An unchanged blocked window must not restart waiting and skipped records.
            if (displayPlan(request) == null) return null
        }
        return request
    }

    private fun scheduleSettled(request: Request) {
        if (!isCurrent(request) || typing) return
        val previous = settling
        val first = previous == null
        // Retain the action that started a screen update while its loading events arrive.
        settling = if (request.trigger == "content_settled" && previous != null) {
            request.copy(trigger = previous.trigger)
        } else request
        val now = SystemClock.elapsedRealtime()
        val startedAt = settleStartedAt ?: now.also { settleStartedAt = it }
        val delay = minOf(INTERACTION_SETTLE_MS, (startedAt + MAX_SETTLE_WAIT_MS - now).coerceAtLeast(0L))
        settleTimer?.let(handler::removeCallbacks)
        if (first && pending?.trigger != "typing_start") cancelPending("A newer interaction reset the settle time.")
        settleTimer = Runnable {
            val latest = settling
            clearSettling()
            if (!typing && latest != null) queueCapture(latest)
        }.also { handler.postDelayed(it, delay) }
        if (first) {
            if (request.trigger == "content_settled") RunMonitor.emit("interaction", request.details("kind" to "content"))
            RunMonitor.emit("capture_waiting", request.details(
                "reason" to "Waiting for the screen to settle.", "delayMs" to delay,
            ))
        }
    }

    private fun clearSettling() {
        settleTimer?.let(handler::removeCallbacks)
        settleTimer = null
        settling = null
        settleStartedAt = null
    }

    private fun observeForeground() {
        var observedPackage: String? = null
        var observedWindowId: Int? = null
        val root = rootInActiveWindow
        try {
            val rootPackage = root?.packageName?.toString()
            if (root != null && rootPackage != null && rootPackage !in inputMethodPackages) {
                observedPackage = rootPackage
                observedWindowId = root.windowId
            }
        } finally {
            root?.recycleCompat()
        }
        if (observedPackage == null) {
            val allWindows = windows
            try {
                val window = allWindows.firstOrNull {
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.isFocused
                } ?: allWindows.firstOrNull {
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.isActive
                }
                val windowRoot = window?.root
                try {
                    val candidate = windowRoot?.packageName?.toString()
                    if (window != null && candidate != null && candidate !in inputMethodPackages) {
                        observedPackage = candidate
                        observedWindowId = window.id
                    }
                } finally {
                    windowRoot?.recycleCompat()
                }
            } finally {
                allWindows.forEach { it.recycleCompat() }
            }
        }
        val windowChanged = observedPackage != foregroundPackage || observedWindowId != foregroundWindowId
        if (windowChanged) {
            invalidate("The active app or window changed.")
        }
        if (!foregroundReported || observedPackage != foregroundPackage) {
            RunMonitor.emit("app_changed", mapOf(
                "packageName" to observedPackage,
                "appLabel" to (observedPackage?.let(::appLabel) ?: "Unknown"),
            ))
            foregroundReported = true
        }
        foregroundPackage = observedPackage
        foregroundWindowId = observedWindowId
        if (windowChanged && observedPackage != null && observedWindowId != null) {
            val state = CaptureStore.state.value
            val runId = state.runId
            if (runId != null && state.consentGranted && state.monitoringEnabled && !state.warningActive) {
                val request = Request(generation, state.revision, runId, observedPackage,
                    observedWindowId, "app_settled")
                scheduleSettled(request)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appLabel(target: String): String {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        // Other packages can declare fallback HOME activities without being the launcher.
        if (target == homePackage && homePackage != "android") return "Home"
        return labels.getOrPut(target) {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(target, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                target
            }
        }
    }

    private fun setTyping(value: Boolean) {
        if (typing == value) return
        typing = value
        RunMonitor.emit("typing_state", mapOf("value" to if (value) "typing" else "idle"))
    }

    private fun queueCapture(request: Request) {
        if (!isCurrent(request)) return
        if (pending != null) cancelPending("A newer capture request replaced the pending request.")
        pending = request
        drainPending()
    }

    /** Only a new event, one deadline, or completion can resume pending capture work. */
    private fun drainPending() {
        drainTimer?.let(handler::removeCallbacks)
        drainTimer = null
        val request = pending ?: return
        if (!isCurrent(request)) {
            cancelPending("The pending capture is no longer current.")
            return
        }
        if (inFlight) {
            RunMonitor.emit("capture_waiting", request.details("reason" to "A screenshot or OCR task is still running."))
            return
        }
        val interval = if (request.trigger == "content_settled") MIN_CONTENT_CAPTURE_INTERVAL_MS else MIN_CAPTURE_INTERVAL_MS
        val remaining = lastAttemptAt?.let {
            interval - (SystemClock.elapsedRealtime() - it)
        } ?: 0L
        if (remaining > 0L) {
            RunMonitor.emit("capture_waiting", request.details(
                "reason" to "Minimum screenshot interval.", "delayMs" to remaining,
            ))
            drainTimer = Runnable { drainTimer = null; drainPending() }
                .also { handler.postDelayed(it, remaining) }
            return
        }
        pending = null
        capture(request)
    }

    private fun cancelPending(reason: String) {
        val request = pending
        pending = null
        drainTimer?.let(handler::removeCallbacks)
        drainTimer = null
        if (request != null) RunMonitor.emit("pending_canceled", request.details("reason" to reason))
    }

    private fun invalidate(reason: String) {
        generation++
        cancelPending(reason)
        val hadTimer = typingTimer != null || settleTimer != null
        typingTimer?.let(handler::removeCallbacks)
        typingTimer = null
        clearSettling()
        focusedInput = null
        passiveCaptureBlocked = false
        setTyping(false)
        if (hadTimer) RunMonitor.emit("pending_canceled", mapOf(
            "runId" to CaptureStore.state.value.runId, "reason" to reason,
        ))
    }

    private fun isCurrent(request: Request): Boolean {
        val state = CaptureStore.state.value
        return !destroyed && request.generation == generation && request.revision == state.revision &&
            request.runId == state.runId && state.consentGranted && state.monitoringEnabled &&
            !state.warningActive && request.warningGeneration == ScamIntervention.generation &&
            request.target == foregroundPackage && request.windowId == foregroundWindowId
    }

    private fun capture(request: Request) {
        if (!isCurrent(request)) return
        // Failed eligibility checks are attempts too and must respect capture spacing.
        lastAttemptAt = SystemClock.elapsedRealtime()
        val plan = displayPlan(request)
        if (plan == null) {
            passiveCaptureBlocked = true
            CaptureStore.report("Capture skipped. The display is off or unavailable.")
            RunMonitor.emit("capture_skipped", request.details(
                "reason" to "The display is off or unavailable."))
            return
        }
        passiveCaptureBlocked = false
        inFlight = true
        lastAttemptAt = SystemClock.elapsedRealtime()
        val captureId = RunMonitor.nextCaptureId()
        RunMonitor.emit("capture_requested", request.details("captureId" to captureId,
            "maskedRegions" to plan.maskedRegions.map { it.details() }))
        CaptureStore.report("Capturing screenshot #$captureId.")
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                var hardwareBitmap: Bitmap? = null
                var softwareBitmap: Bitmap? = null
                try {
                    RunMonitor.emit("screenshot_received", request.details("captureId" to captureId))
                    if (request.warningGeneration != ScamIntervention.generation || CaptureStore.state.value.warningActive) {
                        RunMonitor.emit("capture_discarded", request.details("captureId" to captureId,
                            "reason" to "A scam warning paused screenshot and OCR work."))
                        finishCapture()
                        return
                    }
                    val currentPlan = displayPlan(request)
                    val masks = (plan.maskedRegions + currentPlan?.maskedRegions.orEmpty()).distinct()
                    hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                    val bitmap = softwareBitmap ?: error("Screenshot conversion failed")
                    // Node bounds and OCR bounds must use the same display coordinate system.
                    check(plan.bounds.left == 0 && plan.bounds.top == 0 &&
                        bitmap.width == plan.bounds.width() && bitmap.height == plan.bounds.height())
                    val canvas = Canvas(bitmap)
                    val paint = Paint().apply { color = Color.BLACK }
                    masks.forEach { canvas.drawRect(it.bounds, paint) }
                    // Other metadata changes affect attribution only.
                    val sourcePlan = if (currentPlan == plan) plan else plan.copy(
                        windows = emptyList(), maskedRegions = masks,
                    )
                    softwareBitmap = null // OCR owns the bitmap until its task completes.
                    recognize(request, captureId, bitmap, System.currentTimeMillis(), sourcePlan)
                } catch (_: Exception) {
                    captureFailed(request, captureId, "Android could not prepare the screenshot for OCR.")
                    finishCapture()
                } finally {
                    softwareBitmap?.recycle()
                    hardwareBitmap?.recycle()
                    buffer.close()
                }
            }

            override fun onFailure(errorCode: Int) {
                captureFailed(request, captureId, screenshotError(errorCode))
                finishCapture()
            }
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        } catch (_: Exception) {
            captureFailed(request, captureId, "Android rejected the screenshot request. Check accessibility access.")
            finishCapture()
        }
    }

    private fun captureFailed(request: Request, captureId: Int, reason: String) {
        RunMonitor.emit("capture_failed", request.details("captureId" to captureId, "reason" to reason))
        if (isCurrent(request)) CaptureStore.report("Capture failed.", reason)
    }

    private fun recognize(request: Request, captureId: Int, bitmap: Bitmap, capturedAt: Long, plan: DisplayPlan) {
        val startedAt = SystemClock.elapsedRealtime()
        if (CaptureStore.state.value.runId == request.runId) {
            CaptureStore.report("Reading text from screenshot #$captureId on this device.")
        }
        RunMonitor.emit("ocr_started", request.details("captureId" to captureId,
            "maskedRegions" to plan.maskedRegions.map { it.details() }))
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(mainExecutor) { text ->
                    if (request.warningGeneration != ScamIntervention.generation || CaptureStore.state.value.warningActive) {
                        RunMonitor.emit("capture_discarded", request.details("captureId" to captureId,
                            "reason" to "A scam warning paused screenshot and OCR work."))
                        return@addOnSuccessListener
                    }
                    val regions = text.textBlocks.flatMap { block -> block.lines.map { line ->
                        textRegion(line.text, line.boundingBox, plan)
                    } }
                    val uncertain = regions.isEmpty() || regions.any { it["sourceAttribution"] == "uncertain" }
                    val distinctWindows = regions.mapNotNull { it["windowId"] as? Int }.distinct()
                    val attribution = when {
                        uncertain -> "uncertain"
                        distinctWindows.size == 1 -> "single_window"
                        else -> "mixed"
                    }
                    val sourceLabel = when (attribution) {
                        "single_window" -> "${regions.first()["appLabel"] ?: "Unknown"} (window estimate)"
                        "mixed" -> "Visible screen: multiple windows (window estimates)"
                        else -> "Visible screen: source uncertain"
                    }
                    if (CaptureStore.state.value.runId == request.runId) {
                        CaptureStore.result(text.text, capturedAt)
                    }
                    RunMonitor.emit("ocr_completed", request.details("captureId" to captureId,
                        "text" to text.text, "durationMs" to SystemClock.elapsedRealtime() - startedAt,
                        "sourceAttribution" to attribution, "sourceLabel" to sourceLabel,
                        "textRegions" to regions, "maskedRegions" to plan.maskedRegions.map { it.details() }))
                    JevAnalysis.evaluate(text.text, captureId, request.runId)
                }
                .addOnFailureListener(mainExecutor) {
                    ocrFailed(request, captureId, "The local text model could not read this screenshot.")
                }
                .addOnCompleteListener(mainExecutor) {
                    bitmap.recycle()
                    finishCapture()
                }
        } catch (_: Exception) {
            bitmap.recycle()
            ocrFailed(request, captureId, "The local text model could not start.")
            finishCapture()
        }
    }

    private fun ocrFailed(request: Request, captureId: Int, reason: String) {
        RunMonitor.emit("ocr_failed", request.details("captureId" to captureId, "reason" to reason))
        if (isCurrent(request)) CaptureStore.report("OCR failed.", reason)
    }

    private fun Request.details(vararg values: Pair<String, Any?>): Map<String, Any?> =
        mapOf("runId" to runId, "trigger" to trigger, "packageName" to target,
            "appLabel" to appLabel(target), "windowId" to windowId,
            "captureScope" to "display", "sourceAttribution" to "uncertain",
            "sourceLabel" to "Visible screen: source uncertain") + values

    /** Only keyboard windows are masked. Other window metadata estimates text sources. */
    private fun displayPlan(request: Request): DisplayPlan? {
        if (!getSystemService(PowerManager::class.java).isInteractive) return null
        val display: Rect
        val rotation: Int
        try {
            display = captureWindowManager?.maximumWindowMetrics?.bounds?.let(::Rect) ?: return null
            rotation = getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return null
            if (display.isEmpty) return null
        } catch (_: RuntimeException) {
            return null
        }
        val allWindows = try { windows } catch (_: RuntimeException) { emptyList() }
        val reported = mutableListOf<WindowRegion>()
        val masks = mutableListOf<MaskedRegion>()
        try {
            for (window in allWindows.sortedWith(compareByDescending<AccessibilityWindowInfo> { it.layer }.thenBy { it.id })) {
                if (window.displayId != Display.DEFAULT_DISPLAY) continue
                val bounds = Rect().also(window::getBoundsInScreen)
                if (!bounds.intersect(display)) continue
                val keyboard = window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                if (keyboard) masks += MaskedRegion(Rect(bounds), "Keyboard excluded.")
                var root: AccessibilityNodeInfo? = null
                try {
                    val windowPackage = try {
                        root = window.root
                        root?.packageName?.toString()
                    } catch (_: RuntimeException) { null }
                    val kind = when {
                        keyboard || window.type == AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                        window.id == request.windowId -> "app"
                        else -> "overlay"
                    }
                    reported += WindowRegion(window.id, window.layer, window.type, Rect(bounds),
                        windowPackage, windowPackage?.let(::appLabel), kind)
                } finally {
                    root?.recycleCompat()
                }
            }
        } catch (_: RuntimeException) {
            // A partial window list cannot establish which window owns a text region.
            reported.clear()
        } finally {
            allWindows.forEach { it.recycleCompat() }
        }
        return DisplayPlan(display, rotation, reported, masks)
    }

    private fun textRegion(text: String, bounds: Rect?, plan: DisplayPlan): Map<String, Any?> {
        val candidates = if (bounds == null || bounds.isEmpty) emptyList() else
            plan.windows.filter { Rect.intersects(it.bounds, bounds) }
        // Bounds do not establish opacity. Never choose the top layer when windows overlap.
        val source = candidates.singleOrNull()?.takeIf {
            bounds != null && it.bounds.contains(bounds) && it.packageName != null &&
                plan.maskedRegions.none { mask -> Rect.intersects(mask.bounds, bounds) }
        }
        return mapOf("text" to text, "bounds" to bounds?.details(),
            "packageName" to source?.packageName, "appLabel" to source?.appLabel,
            "windowId" to source?.id, "sourceKind" to (source?.sourceKind ?: "unknown"),
            "sourceAttribution" to if (source == null) "uncertain" else "window_bounds")
    }

    private fun finishCapture() {
        inFlight = false
        if (destroyed) recognizer.close() else drainPending()
    }

    override fun onInterrupt() {
        invalidate("Android interrupted the accessibility service.")
        CaptureStore.report("Android interrupted the accessibility service.")
    }

    override fun onDestroy() {
        destroyed = true
        invalidate("The accessibility service stopped.")
        JevWarning.update(this, null)
        ScamIntervention.unbind()
        scope.cancel()
        if (!inFlight) recognizer.close()
        CaptureStore.connected(false)
        super.onDestroy()
    }

    private fun screenshotError(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Android needs more time between screenshots."
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Screenshot access is unavailable. Check Android accessibility settings."
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "The display is unavailable."
        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "The target window is no longer available."
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This app protects its screen. Antiscam cannot capture it."
        else -> "Android could not capture this screen (error $code)."
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < 33) recycle()
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityWindowInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < 33) recycle()
    }

    private companion object {
        const val TYPING_PAUSE_MS = 1000L
        const val INTERACTION_SETTLE_MS = 600L
        const val MAX_SETTLE_WAIT_MS = 3000L
        const val MIN_CAPTURE_INTERVAL_MS = 1500L
        const val MIN_CONTENT_CAPTURE_INTERVAL_MS = 5000L
        val INPUT_OBSERVATION_EVENTS = setOf(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
    }
}

private fun Rect.details(): Map<String, Int> =
    mapOf("left" to left, "top" to top, "right" to right, "bottom" to bottom)
