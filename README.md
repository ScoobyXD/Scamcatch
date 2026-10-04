# Antiscam run console

An Android background capture service with a separate desktop dashboard and a saved record for each run.

The agreed task scope is in [current-task/requirements.txt](current-task/requirements.txt).

## Start

1. Start the Android emulator or connect a debugging-enabled Android device.
2. Build with `scripts\build.ps1`.
3. Install Antiscam with `scripts\install.ps1`.
4. Open **Antiscam Dev** once. Read the disclosure and allow local capture if needed.
5. Open Android Accessibility settings. Enable **Antiscam interaction capture**.
6. Double-click **Start Dashboard.cmd**, or run `scripts\dashboard.ps1`.
7. Select **Start run** in the desktop dashboard.
8. Use any app in the emulator. Type, pause, tap, scroll, or switch apps.
9. Read the full extracted text in the left column and the activity log in the right column.
10. Select **Stop run**. The saved folder under `runs` contains the record for analysis.
11. Select **Export ZIP** to share the saved run for analysis.

```powershell
.\scripts\build.ps1
.\scripts\install.ps1 -Serial emulator-5554
.\scripts\dashboard.ps1 -Serial emulator-5554
```

## Requirements

1. Android Studio, Android Platform 36, Build Tools 35.0.0, and Platform Tools.
2. JDK 21 recommended. The pinned Gradle/AGP versions support JDK 17 through 23.
3. Python 3.11 or later for the dashboard. It uses the standard library and needs no pip packages.
4. A current desktop browser for the local dashboard.
5. Android 11/API 30 or later. API 34+ provides target-window screenshots.
6. Set `JAVA_HOME` and `ANDROID_HOME` when the helper scripts cannot find your tools.
7. Select the supported JDK in Android Studio's Gradle settings. The IDE runtime can differ from the build runtime.
8. The first Gradle build needs access to download dependencies.

## Capture rules

1. An active run, consent, enabled monitoring, and a connected accessibility service are required.
2. Screenshot capture follows the foreground app. There is no app selector or app allowlist.
3. Typing requests one initial screenshot and one after a one-second pause.
4. Taps and scrolling request capture after 600 milliseconds without another relevant interaction.
5. Repeated events reset timers instead of producing a screenshot for every character or scroll event.
6. Capture attempts stay at least 1.5 seconds apart. Only one screenshot/OCR operation runs at a time.
7. Stop, pause, app/window changes, consent withdrawal, and connection loss cancel pending work. A settled new foreground window requests a capture during a run.
8. Password fields, Android-marked sensitive content, and secure windows prevent capture.
9. When stopped, interactions update live status without creating capture requests or skipped-capture noise.
10. See [all run rules](docs/run-rules.md) and [validation results](docs/run-validation.md).

## Run records

1. Each run has a unique ID, timestamps, status, counters, and a capture ID for each screenshot request.
2. `events.jsonl` preserves the ordered event record.
3. `ocr` contains the full text extracted from each completed capture.
4. `summary.txt` and `run.json` describe the run and its outcome.
5. **Export ZIP** packages the saved run for analysis.
6. Events save as they arrive. A lost connection produces an interrupted record.
7. Starting another run resets the dashboard's history. Earlier folders remain available under `runs`.

## Data boundary

1. Starting a run records extracted text on the connected computer. That text can contain private screen content.
2. Screenshot images remain in Android memory. The recorder does not save screenshot files.
3. Run folders and dashboard runtime files are ignored by Git. Do not manually commit or publish them.
4. OCR text never goes to Logcat or console diagnostics.
5. Stop and consent withdrawal prevent future capture. They do not erase records already saved on the computer.
6. ML Kit uses the bundled Latin model on the device. No cloud analysis is present.
7. The Android manifest removes Internet and network-state permissions.
8. The debug bridge uses an ADB-forwarded local socket. Release builds expose no dashboard socket.
9. The desktop server binds only to this computer. Browser updates use a pushed event stream.
10. Android does not expose every physical touch as an accessibility event. Unknown states must remain explicit.
11. `isAccessibilityTool` remains false. This is not a Google Play approval claim.

## Code

1. `app` contains the accessibility service, local OCR, event stream, and minimal consent/accessibility setup.
2. `dashboard` contains the desktop server, recorder, and browser interface.
3. `scripts` contains build, install, and dashboard launch helpers for Antiscam only.
4. The default build creates the debug app and runs Android lint. It does not run unit tests under the current project instructions.
5. Device checks use real apps. The demo app and its module were removed.
6. AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.3.10, ML Kit text recognition 16.0.1, target/compile SDK 36.
7. Scam judgments, cloud analysis, warning notifications, and voice are outside this version.
