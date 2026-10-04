package com.antiscam.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.InputType
import android.view.Display
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

/** Typing events are the only capture trigger. Window events only invalidate work. */
class TypingCaptureService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recognizer: TextRecognizer
    private var pending: Runnable? = null
    private var generation = 0L
    private var inFlight = false
    private var destroyed = false
    private var lastAttemptAt = 0L
    private var activeWindowId: Int? = null

    private data class Request(val generation: Long, val revision: Long, val target: String, val windowId: Int)

    override fun onCreate() {
        super.onCreate()
        CaptureStore.initialize(this)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        scope.launch {
            CaptureStore.state.map { it.revision }.distinctUntilChanged().collect { invalidate() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureStore.connected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || destroyed) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val root = rootInActiveWindow
            val stillSelected = root != null && root.packageName?.toString() == CaptureStore.state.value.selectedPackage &&
                (activeWindowId == null || root.windowId == activeWindowId)
            root?.recycleCompat()
            if (!stillSelected) {
                val wasWaiting = pending != null || (inFlight && activeWindowId != null)
                invalidate()
                if (wasWaiting) CaptureStore.report("Pending capture canceled. The selected app is no longer active.")
            }
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        val state = CaptureStore.state.value
        if (!state.consentGranted || !state.monitoringEnabled) return
        if (event.packageName?.toString() != state.selectedPackage || event.packageName?.toString() == packageName) return
        val inputMethods = getSystemService(InputMethodManager::class.java).inputMethodList
        if (inputMethods.any { it.packageName == state.selectedPackage }) return

        // Never read or retain event.text or the typed characters.
        val source = event.source
        val acceptable = source != null && source.packageName?.toString() == state.selectedPackage &&
            source.isFocused && source.isEditable && !event.isPassword && !source.isSensitive()
        source?.recycleCompat()
        if (!acceptable) {
            invalidate()
            CaptureStore.report("Skipped typing in a protected or unavailable field.")
            return
        }
        invalidate()
        activeWindowId = event.windowId
        val request = Request(generation, state.revision, state.selectedPackage, event.windowId)
        CaptureStore.report("Typing detected. Waiting for a short pause.")
        schedule(request, TYPING_PAUSE_MS)
    }

    private fun schedule(request: Request, delayMillis: Long) {
        pending?.let(handler::removeCallbacks)
        pending = Runnable {
            pending = null
            capture(request)
        }.also { handler.postDelayed(it, delayMillis) }
    }

    private fun invalidate() {
        generation++
        pending?.let(handler::removeCallbacks)
        pending = null
        activeWindowId = null
    }

    private fun isCurrent(request: Request): Boolean {
        val state = CaptureStore.state.value
        return !destroyed && request.generation == generation && request.revision == state.revision &&
            state.consentGranted && state.monitoringEnabled && request.target == state.selectedPackage
    }

    /** Recheck the app, focus, and sensitivity before capture and before publishing OCR. */
    private fun targetBounds(request: Request): Rect? {
        if (!isCurrent(request)) return null
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive) return null
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != request.target || root.windowId != request.windowId) return null
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            val safeFocus = focused.isEditable && !focused.isSensitive()
            focused.recycleCompat()
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
        if (inFlight) {
            schedule(request, 200L)
            return
        }
        val bounds = targetBounds(request)
        if (bounds == null) {
            CaptureStore.report("Capture skipped. The selected app or safe text field is no longer active.")
            return
        }
        val remaining = MIN_CAPTURE_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastAttemptAt)
        if (remaining > 0) {
            schedule(request, remaining)
            return
        }
        val windowCapture = Build.VERSION.SDK_INT >= 34
        val crop = if (windowCapture) null else legacyCrop(request, bounds)
        if (!windowCapture && crop == null) {
            CaptureStore.report("Capture skipped. Another window covers the selected app.")
            return
        }
        inFlight = true
        lastAttemptAt = SystemClock.elapsedRealtime()
        CaptureStore.report("Capturing the selected app.")
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                var hardwareBitmap: Bitmap? = null
                var softwareBitmap: Bitmap? = null
                try {
                    if (targetBounds(request) != bounds || (!windowCapture && legacyCrop(request, bounds) != crop)) {
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
                    recognize(request, image, System.currentTimeMillis())
                } catch (_: Exception) {
                    if (isCurrent(request)) CaptureStore.report("Capture failed.", "Android could not prepare the screenshot for OCR.")
                    finishCapture()
                } finally {
                    softwareBitmap?.recycle()
                    hardwareBitmap?.recycle()
                    buffer.close()
                }
            }

            override fun onFailure(errorCode: Int) {
                if (isCurrent(request)) CaptureStore.report("Capture failed.", screenshotError(errorCode))
                finishCapture()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(request.windowId, mainExecutor, callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        } catch (_: Exception) {
            if (isCurrent(request)) CaptureStore.report("Capture failed.", "Android rejected the screenshot request. Check accessibility access.")
            finishCapture()
        }
    }

    private fun recognize(request: Request, bitmap: Bitmap, capturedAt: Long) {
        CaptureStore.report("Reading screenshot text on this device.")
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(mainExecutor) { text ->
                    if (targetBounds(request) != null) CaptureStore.result(text.text, capturedAt)
                }
                .addOnFailureListener(mainExecutor) {
                    if (isCurrent(request)) CaptureStore.report("OCR failed.", "The local text model could not read this screenshot. Type again to retry.")
                }
                .addOnCompleteListener(mainExecutor) {
                    bitmap.recycle()
                    finishCapture()
                }
        } catch (_: Exception) {
            bitmap.recycle()
            if (isCurrent(request)) CaptureStore.report("OCR failed.", "The local text model could not start.")
            finishCapture()
        }
    }

    /** API 30-33 capture the display. Keep only the target region and exclude the keyboard. */
    private fun legacyCrop(request: Request, bounds: Rect): Rect? {
        val allWindows = windows
        try {
            val targetLayer = allWindows.firstOrNull { it.id == request.windowId }?.layer ?: return null
            val crop = Rect(bounds)
            for (window in allWindows) {
                if (window.id == request.windowId || window.layer <= targetLayer) continue
                val other = Rect().also(window::getBoundsInScreen)
                if (!Rect.intersects(crop, other)) continue
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && other.bottom >= crop.bottom) {
                    crop.bottom = minOf(crop.bottom, other.top)
                } else return null
            }
            return crop.takeUnless { it.isEmpty }
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

    private fun finishCapture() {
        inFlight = false
        if (destroyed) recognizer.close()
    }

    override fun onInterrupt() {
        invalidate()
        CaptureStore.report("Android interrupted the accessibility service.")
    }

    override fun onDestroy() {
        destroyed = true
        invalidate()
        scope.cancel()
        if (!inFlight) recognizer.close()
        CaptureStore.connected(false)
        super.onDestroy()
    }

    private fun screenshotError(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Android needs more time between screenshots. Type again to retry."
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
        const val TYPING_PAUSE_MS = 700L
        const val MIN_CAPTURE_INTERVAL_MS = 1500L
        const val MAX_INSPECTED_NODES = 1000
    }
}
