Components:
1. Google ML Kit OCR — extracts text from those images.
2. TypeSafe Jev 1.13.0 — evaluates the five topic questions together in debug builds.
3. OkHttp 4.12.0 — sends cancelable HTTPS requests from the Android debug app.

Accessibility Methods:
1. Android AccessibilityService — receives interactions and screen changes.
2. AccessibilityEvent — reports typing, scrolling, focus, and other changes.
3. AccessibilityNodeInfo — reads accessible controls and field properties.
4. AccessibilityWindowInfo — identifies app windows.
5. takeScreenshot() / takeScreenshotOfWindow() — captures screen or window images.
