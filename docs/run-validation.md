# Run console validation

1. Checked on 4 October 2026 using the API 36 emulator.
2. The Android debug build, Android lint, and release Kotlin compilation passed after removing the demo module. Lint reported no errors and 10 warnings.
3. Unit tests were not created or run, as required by the current project instructions.
4. Python, JavaScript, and PowerShell syntax checks passed.
5. The demo app was uninstalled. Only com.antiscam.app remains installed under the project package prefix.
6. The minimal Android setup screen was checked visually. It shows accessibility connected, the settings button, and consent withdrawal. It has no app picker, run UI, or extracted text.
7. A real-app run captured Chrome, Home, and Android Settings without selecting an app. It saved four screenshots and four OCR results with no capture or OCR failures.
8. The same run included settled app changes and scrolling. Each OCR record identified its source app.
9. Export produced a valid ZIP containing the event history and four OCR files.
10. At a 960 by 850 desktop viewport, the page fit the window. Only the extracted-text pane and activity pane scrolled. The app selector was absent.
11. Browser Start and Stop controls worked. Start was enabled again after Stop.
12. Real Chrome address typing needs a content-change fallback because this Chrome version did not emit text-change events. Validation of that change is in progress.
13. Physical phones, API 30-33 capture behavior, and signed-in messaging/social apps remain unverified.
14. Android does not expose every physical interaction as an accessibility event. Capture timing follows the events the app supplies.
15. Avoid uiautomator dump during a run because it can temporarily suppress the accessibility service. Use visual inspection of the emulator.
