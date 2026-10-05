package com.antiscam.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
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
        val needsEditableFocus: Boolean,
        val sensitiveInteraction: Boolean = false,
        val sensitiveInteractionBounds: Rect? = null,
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
        val targetBounds: Rect,
        val windows: List<WindowRegion>,
        val maskedRegions: List<MaskedRegion>,
    )

    private data class InspectionBudget(var remaining: Int = MAX_INSPECTED_NODES)
    private data class NodeFrame(val node: AccessibilityNodeInfo, var childIndex: Int = -1)

    override fun onCreate() {
        super.onCreate()
        CaptureStore.initialize(this)
        RunMonitor.initialize(this)
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
        val request = requestFor(event, kind, inferredEdit)
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
        if (state.runId == null || !state.consentGranted || !state.monitoringEnabled || event.isSensitive()) {
            focusedInput = null
            return false
        }
        if (event.eventType !in INPUT_OBSERVATION_EVENTS) return false
        val root = rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != foregroundPackage || root.windowId != foregroundWindowId ||
                root.isSensitive()) return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            try {
                if (focused == null || !focused.isFocused || !focused.isEditable || focused.isSensitive()) {
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

    private fun requestFor(event: AccessibilityEvent, kind: String, inferredEdit: Boolean): Request? {
        val state = CaptureStore.state.value
        // Keep observation live between runs, without generating capture work or skip events.
        if (state.runId == null || !state.consentGranted || !state.monitoringEnabled) return null
        val target = foregroundPackage ?: return null
        val windowId = foregroundWindowId ?: return null
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            (event.packageName?.toString() != target || event.windowId != windowId)) return null
        val trigger = if (kind == "typing") "typing_start" else "${kind}_settled"
        val request = Request(generation, state.revision, state.runId, target,
            windowId, trigger, kind == "typing")
        if (kind == "content" && passiveCaptureBlocked) {
            // A new content event can resume capture once the window becomes eligible.
            // An unchanged blocked window must not restart waiting and skipped records.
            if (targetBounds(request) == null) return null
        }
        // Event text is never read. Inferred edits already checked the actual focused input.
        val source = event.source
        var sensitiveInteraction = event.isSensitive()
        var sensitiveBounds: Rect? = null
        val safeSource = try {
            if (source != null && (sensitiveInteraction || source.isSensitive())) {
                sensitiveInteraction = true
                sensitiveBounds = Rect().also(source::getBoundsInScreen).takeUnless { it.isEmpty }
            }
            source == null && (kind != "typing" || inferredEdit) || source != null &&
                source.packageName?.toString() == target &&
                (kind != "typing" || inferredEdit || source.isFocused && source.isEditable)
        } finally {
            source?.recycleCompat()
        }
        if (!safeSource) {
            if (kind == "content" && passiveCaptureBlocked) return null
            invalidate("The interaction field is unavailable.")
            passiveCaptureBlocked = true
            CaptureStore.report("Capture skipped. The interaction field is unavailable.")
            RunMonitor.emit("capture_skipped", mapOf("runId" to state.runId,
                "reason" to "The interaction field is unavailable.", "trigger" to trigger))
            return null
        }
        return request.copy(sensitiveInteraction = sensitiveInteraction,
            sensitiveInteractionBounds = sensitiveBounds)
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
            if (runId != null && state.consentGranted && state.monitoringEnabled) {
                val request = Request(generation, state.revision, runId, observedPackage,
                    observedWindowId, "app_settled", false)
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
            request.target == foregroundPackage && request.windowId == foregroundWindowId
    }

    /** Preserve run and interaction ownership. Privacy is checked across the display separately. */
    private fun targetBounds(request: Request): Rect? {
        if (!isCurrent(request)) return null
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive) return null
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != request.target || root.windowId != request.windowId) return null
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val matchingFocus = try {
                !request.needsEditableFocus || focused != null && focused.isEditable
            } finally {
                focused?.recycleCompat()
            }
            if (!matchingFocus) return null
            val window = root.window ?: return null
            try {
                if (window.id != request.windowId ||
                    (!window.isActive && !window.isFocused)) return null
                val bounds = Rect().also(window::getBoundsInScreen)
                return bounds.takeUnless { it.isEmpty }
            } finally {
                window.recycleCompat()
            }
        } catch (_: IllegalStateException) {
            return null
        } finally {
            root.recycleCompat()
        }
    }

    private fun capture(request: Request) {
        if (!isCurrent(request)) return
        // Failed eligibility checks are attempts too and must respect capture spacing.
        lastAttemptAt = SystemClock.elapsedRealtime()
        val plan = displayPlan(request)
        if (plan == null) {
            passiveCaptureBlocked = true
            CaptureStore.report("Capture skipped. The active app or display information is unavailable.")
            RunMonitor.emit("capture_skipped", request.details(
                "reason" to "The active app or display information is unavailable."))
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
                    if (displayPlan(request) != plan) {
                        RunMonitor.emit("capture_discarded", request.details("captureId" to captureId,
                            "reason" to "The run, app, window, or protected content changed before screenshot processing."))
                        finishCapture()
                        return
                    }
                    hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                    val bitmap = softwareBitmap ?: error("Screenshot conversion failed")
                    // Node bounds and OCR bounds must use the same display coordinate system.
                    check(plan.bounds.left == 0 && plan.bounds.top == 0 &&
                        bitmap.width == plan.bounds.width() && bitmap.height == plan.bounds.height())
                    val canvas = Canvas(bitmap)
                    val paint = Paint().apply { color = Color.BLACK }
                    plan.maskedRegions.forEach { canvas.drawRect(it.bounds, paint) }
                    softwareBitmap = null // OCR owns the bitmap until its task completes.
                    recognize(request, captureId, bitmap, System.currentTimeMillis(), plan)
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
        CaptureStore.report("Reading text from screenshot #$captureId on this device.")
        RunMonitor.emit("ocr_started", request.details("captureId" to captureId,
            "maskedRegions" to plan.maskedRegions.map { it.details() }))
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(mainExecutor) { text ->
                    if (displayPlan(request) == plan) {
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
                        CaptureStore.result(text.text, capturedAt)
                        RunMonitor.emit("ocr_completed", request.details("captureId" to captureId,
                            "text" to text.text, "durationMs" to SystemClock.elapsedRealtime() - startedAt,
                            "sourceAttribution" to attribution, "sourceLabel" to sourceLabel,
                            "textRegions" to regions, "maskedRegions" to plan.maskedRegions.map { it.details() }))
                    } else {
                        RunMonitor.emit("capture_discarded", request.details("captureId" to captureId,
                            "reason" to "The run, app, window, or protected content changed before OCR completed."))
                    }
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

    /** Window overlap is allowed. Only excluded or uninspectable regions become masks. */
    private fun displayPlan(request: Request): DisplayPlan? {
        val target = targetBounds(request) ?: return null
        val allWindows = try { windows } catch (_: RuntimeException) { return null }
        try {
            val display = captureWindowManager?.maximumWindowMetrics?.bounds?.let(::Rect) ?: return null
            val rotation = getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return null
            if (display.isEmpty) return null
            val reported = mutableListOf<WindowRegion>()
            val masks = mutableListOf<MaskedRegion>()
            if (request.sensitiveInteraction) {
                val bounds = request.sensitiveInteractionBounds?.let(::Rect)
                if (bounds == null || !bounds.intersect(target)) {
                    masks += MaskedRegion(Rect(target), "Sensitive interaction bounds are unavailable.")
                } else {
                    masks += MaskedRegion(bounds, "Sensitive interaction excluded.")
                }
            }
            val budget = InspectionBudget()
            val uncovered = Region(display)
            for (window in allWindows.sortedWith(compareByDescending<AccessibilityWindowInfo> { it.layer }.thenBy { it.id })) {
                if (window.displayId != Display.DEFAULT_DISPLAY) continue
                val bounds = Rect().also(window::getBoundsInScreen)
                if (!bounds.intersect(display)) continue
                uncovered.op(bounds, Region.Op.DIFFERENCE)
                val keyboard = window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                var root: AccessibilityNodeInfo? = null
                try {
                    val windowPackage = try {
                        if (!keyboard) root = window.root
                        root?.packageName?.toString()
                    } catch (_: RuntimeException) { null }
                    val kind = when {
                        keyboard || window.type == AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                        window.id == request.windowId -> "app"
                        else -> "overlay"
                    }
                    reported += WindowRegion(window.id, window.layer, window.type, Rect(bounds),
                        windowPackage, windowPackage?.let(::appLabel), kind)
                    when {
                        keyboard -> masks += MaskedRegion(Rect(bounds), "Keyboard excluded.")
                        root == null -> masks += MaskedRegion(Rect(bounds), "Window content is unavailable for privacy inspection.")
                        else -> {
                            val inspectedRoot = root
                            root = null // The traversal now owns this root, including exceptional paths.
                            inspectSensitiveRegions(inspectedRoot, bounds, masks, budget)
                        }
                    }
                } finally {
                    root?.recycleCompat()
                }
            }
            // Accessibility can omit a window behind a modal. Do not inspect its exposed pixels as known content.
            val gaps = RegionIterator(uncovered)
            val gap = Rect()
            while (gaps.next(gap)) masks += MaskedRegion(Rect(gap), "No accessible window information.")
            return DisplayPlan(display, rotation, target, reported, masks.distinct().sortedWith(
                compareBy<MaskedRegion> { it.bounds.top }.thenBy { it.bounds.left }
                    .thenBy { it.bounds.bottom }.thenBy { it.bounds.right }.thenBy { it.reason },
            ))
        } catch (_: RuntimeException) {
            return null
        } finally {
            allWindows.forEach { it.recycleCompat() }
        }
    }

    /** This traversal owns root and children. The existing node ceiling applies across all windows. */
    private fun inspectSensitiveRegions(
        root: AccessibilityNodeInfo,
        windowBounds: Rect,
        masks: MutableList<MaskedRegion>,
        budget: InspectionBudget,
    ) {
        val stack = ArrayDeque<NodeFrame>()
        stack.addLast(NodeFrame(root))
        try {
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                val node = frame.node
                if (frame.childIndex == -1) {
                    if (budget.remaining == 0) {
                        masks += MaskedRegion(Rect(windowBounds), "Privacy inspection reached the 1,000-node limit.")
                        return
                    }
                    budget.remaining--
                    frame.childIndex = 0
                    if ((node === root || node.isVisibleToUser) && node.isSensitive()) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (bounds.isEmpty || !bounds.intersect(windowBounds)) {
                            masks += MaskedRegion(Rect(windowBounds), "Sensitive field bounds are unavailable.")
                        } else {
                            masks += MaskedRegion(bounds, "Sensitive field excluded.")
                        }
                        stack.removeLast().node.recycleCompat()
                        continue
                    }
                }
                if (frame.childIndex >= node.childCount) {
                    stack.removeLast().node.recycleCompat()
                    continue
                }
                // Fetch one child at a time. A wide tree cannot allocate beyond the inspection ceiling.
                if (budget.remaining == 0) {
                    masks += MaskedRegion(Rect(windowBounds), "Privacy inspection reached the 1,000-node limit.")
                    return
                }
                val child = node.getChild(frame.childIndex++)
                if (child == null) {
                    masks += MaskedRegion(Rect(windowBounds), "A field is unavailable for privacy inspection.")
                    return
                }
                stack.addLast(NodeFrame(child))
            }
        } catch (_: RuntimeException) {
            masks += MaskedRegion(Rect(windowBounds), "Window privacy inspection failed.")
        } finally {
            stack.forEach { it.node.recycleCompat() }
        }
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

    private fun AccessibilityNodeInfo.isSensitive(): Boolean {
        val kind = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val passwordInput = (kind == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        return isPassword || passwordInput || (Build.VERSION.SDK_INT >= 34 && isAccessibilityDataSensitive)
    }

    private fun AccessibilityEvent.isSensitive(): Boolean =
        isPassword || (Build.VERSION.SDK_INT >= 34 && isAccessibilityDataSensitive)

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
        const val MAX_INSPECTED_NODES = 1000
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
