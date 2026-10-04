package com.antiscam.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.view.Display
import android.view.WindowInsets
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
    )

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
            val bounds = targetBounds(request) ?: return null
            if (Build.VERSION.SDK_INT < 34 && legacyCrop(request, bounds) == null) return null
        }
        // Event text is never read. Inferred edits already checked the actual focused input.
        val source = event.source
        val safeSource = try {
            !event.isSensitive() && (source == null && (kind != "typing" || inferredEdit) || source != null &&
                source.packageName?.toString() == target && !source.isSensitive() &&
                (kind != "typing" || inferredEdit || source.isFocused && source.isEditable))
        } finally {
            source?.recycleCompat()
        }
        if (!safeSource) {
            if (kind == "content" && passiveCaptureBlocked) return null
            invalidate("The interaction is in a protected or unavailable field.")
            passiveCaptureBlocked = true
            CaptureStore.report("Capture skipped. The field is protected or unavailable.")
            RunMonitor.emit("capture_skipped", mapOf("runId" to state.runId,
                "reason" to "Protected or unavailable field.", "trigger" to trigger))
            return null
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

    /** Recheck app, field sensitivity, and visible content before capture and OCR publication. */
    private fun targetBounds(request: Request): Rect? {
        if (!isCurrent(request)) return null
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive) return null
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != request.target || root.windowId != request.windowId) return null
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val safeFocus = try {
                if (request.needsEditableFocus) focused != null && focused.isEditable && !focused.isSensitive()
                else focused == null || !focused.isSensitive()
            } finally {
                focused?.recycleCompat()
            }
            if (!safeFocus || containsSensitiveContent(root)) return null
            val window = root.window ?: return null
            try {
                if (window.id != request.windowId || window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
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
        val bounds = targetBounds(request)
        if (bounds == null) {
            passiveCaptureBlocked = true
            CaptureStore.report("Capture skipped. The app, field, or screen is unavailable or protected.")
            RunMonitor.emit("capture_skipped", request.details("reason" to "The active window is unavailable or contains protected content."))
            return
        }
        val windowCapture = Build.VERSION.SDK_INT >= 34
        val crop = if (windowCapture) null else legacyCrop(request, bounds)
        if (!windowCapture && crop == null) {
            passiveCaptureBlocked = true
            CaptureStore.report("Capture skipped. Another window covers the current app.")
            RunMonitor.emit("capture_skipped", request.details("reason" to "Another window covers the current app."))
            return
        }
        passiveCaptureBlocked = false
        inFlight = true
        lastAttemptAt = SystemClock.elapsedRealtime()
        val captureId = RunMonitor.nextCaptureId()
        RunMonitor.emit("capture_requested", request.details("captureId" to captureId))
        CaptureStore.report("Capturing screenshot #$captureId.")
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                var hardwareBitmap: Bitmap? = null
                var softwareBitmap: Bitmap? = null
                try {
                    RunMonitor.emit("screenshot_received", request.details("captureId" to captureId))
                    if (targetBounds(request) != bounds || (!windowCapture && legacyCrop(request, bounds) != crop)) {
                        RunMonitor.emit("capture_discarded", request.details("captureId" to captureId,
                            "reason" to "The run, app, window, or protected content changed before screenshot processing."))
                        finishCapture()
                        return
                    }
                    hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    val bitmap = softwareBitmap ?: error("Screenshot conversion failed")
                    val image = if (crop == null) bitmap else {
                        val clipped = Rect(crop)
                        check(clipped.intersect(0, 0, bitmap.width, bitmap.height))
                        Bitmap.createBitmap(bitmap, clipped.left, clipped.top, clipped.width(), clipped.height())
                    }
                    if (image !== bitmap) bitmap.recycle()
                    softwareBitmap = null // OCR owns the bitmap until its task completes.
                    recognize(request, captureId, image, System.currentTimeMillis())
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
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(request.windowId, mainExecutor, callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        } catch (_: Exception) {
            captureFailed(request, captureId, "Android rejected the screenshot request. Check accessibility access.")
            finishCapture()
        }
    }

    private fun captureFailed(request: Request, captureId: Int, reason: String) {
        RunMonitor.emit("capture_failed", request.details("captureId" to captureId, "reason" to reason))
        if (isCurrent(request)) CaptureStore.report("Capture failed.", reason)
    }

    private fun recognize(request: Request, captureId: Int, bitmap: Bitmap, capturedAt: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        CaptureStore.report("Reading text from screenshot #$captureId on this device.")
        RunMonitor.emit("ocr_started", request.details("captureId" to captureId))
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(mainExecutor) { text ->
                    if (targetBounds(request) != null) {
                        CaptureStore.result(text.text, capturedAt)
                        RunMonitor.emit("ocr_completed", request.details("captureId" to captureId,
                            "text" to text.text, "durationMs" to SystemClock.elapsedRealtime() - startedAt))
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
            "appLabel" to appLabel(target), "windowId" to windowId) + values

    /** API 30-33 capture the display. Exclude system bars, cutouts, and the keyboard. */
    private fun legacyCrop(request: Request, bounds: Rect): Rect? {
        val allWindows = windows
        try {
            val targetLayer = allWindows.firstOrNull { it.id == request.windowId }?.layer ?: return null
            val crop = Rect(bounds)
            val metrics = captureWindowManager?.maximumWindowMetrics ?: return null
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val displayContent = Rect(metrics.bounds).apply {
                left += insets.left
                top += insets.top
                right -= insets.right
                bottom -= insets.bottom
            }
            if (!crop.intersect(displayContent)) return null
            for (window in allWindows) {
                if (window.id == request.windowId || window.layer <= targetLayer) continue
                val other = Rect().also(window::getBoundsInScreen)
                if (!Rect.intersects(crop, other)) continue
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && other.bottom >= crop.bottom) {
                    crop.bottom = minOf(crop.bottom, other.top)
                } else return null
            }
            return crop.takeUnless { it.isEmpty }
        } catch (_: RuntimeException) {
            return null
        } finally {
            allWindows.forEach { it.recycleCompat() }
        }
    }

    private fun containsSensitiveContent(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        var visited = 0
        // The caller owns root. Every child belongs to this traversal.
        for (index in 0 until root.childCount) root.getChild(index)?.let(queue::addLast)
        try {
            if (root.isSensitive()) return true
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                try {
                    if (++visited > MAX_INSPECTED_NODES) return true
                    if (node.isVisibleToUser && node.isSensitive()) return true
                    for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                } finally {
                    node.recycleCompat()
                }
            }
            return false
        } finally {
            queue.forEach { it.recycleCompat() }
        }
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
