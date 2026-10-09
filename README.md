# Scamcatch run console

1. Android runs capture, Jev analysis, and automatic voice warnings with or without a connected laptop.
2. Desktop runs add a dashboard and saved run records.
3. The agreed task scope is in [current-task/requirements.txt](current-task/requirements.txt).

## Edit Jev questions and voice prompts

1. Edit [app/ai-instructions/AIInstructions.kt](app/ai-instructions/AIInstructions.kt). It is the single source for all Jev questions and voice prompts.
2. The file also defines both models, the Jev question-set version and threshold, and the voice name. The debug build compiles it directly.
3. After an edit, stop the run, build, install, and restart Antiscam. Restore or enter the required keys. Edits do not reload live.
4. Ask the user to audit any new Jev question before adding it.

## Start with the desktop

1. Start the Android emulator or connect a debugging-enabled Android device.
2. Build with `scripts\build.ps1`.
3. Install Antiscam with `scripts\install.ps1`.
4. Open **Antiscam Dev**. Read the updated disclosure and allow capture and cloud text analysis.
5. Open Android Accessibility settings. Enable **Antiscam interaction capture**.
6. Double-click **Start Dashboard.cmd**, or run `scripts\dashboard.ps1`.
7. Select the device and choose **Connect** or **Switch device**.
8. Select **Jev key** in the desktop toolbar. Enter the TypeSafe key and send it to the phone. Windows selects **Remember on this PC** by default.
9. Select **OpenAI key** and send the OpenAI API key. Saved keys restore on reconnect. Key indicators confirm loading only, not valid credentials or permissions.
10. In Android setup, allow automatic voice warnings and microphone access. Then select **Start run** in the dashboard.
11. Use an app on the selected phone or emulator. Type, pause, tap, scroll, or switch apps.
12. Read Jev results on the left, full OCR text in the middle, and activity on the right. Each list shows newest entries first.
13. A flagged check opens a centered warning and starts voice automatically when setup is complete. The red X closes it and resumes capture.
14. Select **Stop**. The saved folder under `runs` contains the record for analysis.
15. Select **Export ZIP** to share the saved run for analysis.

```powershell
.\scripts\build.ps1
.\scripts\install.ps1 -Serial emulator-5554
.\scripts\dashboard.ps1 -Serial emulator-5554
```

## Start without the laptop

1. Install the current **Antiscam Dev** build with the build and install scripts above.
2. Open Antiscam. Accept the version 3 capture/cloud disclosure, which covers phone and desktop runs.
3. Enable **Antiscam interaction capture** in Android Accessibility settings. Enable monitoring in Antiscam.
4. Enter the TypeSafe and OpenAI API keys under **Phone keys**. Key readiness confirms loading, not authentication or model access.
5. Select **Remember on this phone** only if you want encrypted keys retained across app restarts. The checkbox starts cleared.
6. Allow automatic voice warnings and microphone access. Allow notifications for the fallback if Android cannot open a warning.
7. Keep the phone connected to the Internet. Select **Start Run**, then use other apps.
8. A flagged check vibrates the phone and opens the warning. Voice starts automatically when the warning opens and setup remains complete.
9. The red X stops voice and resumes screen checks. Select **Stop Run** in Antiscam to end the phone run.
10. Connecting or disconnecting a laptop does not stop a phone run. The desktop receives status only, without OCR or evidence records.
11. A phone run ends when the app process ends or the phone restarts. Saved keys restore, but you must start another run.
12. Stop the phone run before starting a desktop run. Only desktop runs save records under `runs`.

## Connected phone

1. Connect the configured Galaxy S10+ by USB and accept the USB debugging prompt.
2. Open **Start Phone Dashboard.cmd** to connect the original console at `http://127.0.0.1:8765/` to the phone.
3. To change devices in that console, stop the run, select **Device**, choose the target, and select **Switch device**.
4. Both shortcuts reuse one console. Desktop-started phone and emulator runs save under `runs`, with the selected device recorded in each run.
5. Earlier phone records remain under `runs/phone-R58N83XN7BE`.
6. Stop the current run before building and installing an app update with the existing scripts.
7. The launcher connects the console. It does not watch source files or install app updates.

## Requirements

1. Android Studio, Android Platform 36, Build Tools 35.0.0, and Platform Tools.
2. JDK 21 recommended. The pinned Gradle/AGP versions support JDK 17 through 23.
3. Python 3.11 or later for the dashboard. It uses the standard library and needs no pip packages.
4. A current desktop browser for the local dashboard.
5. Android 11/API 30 or later. All supported versions use accessibility display screenshots.
6. Set `JAVA_HOME` and `ANDROID_HOME` when the helper scripts cannot find your tools.
7. Select the supported JDK in Android Studio's Gradle settings. The IDE runtime can differ from the build runtime.
8. The first Gradle build needs access to download dependencies.
9. Jev analysis and OpenAI voice require Internet access on the phone.

## Capture rules

1. An active run, consent, enabled monitoring, and a connected accessibility service are required.
2. Screenshots capture the visible default display. Foreground app interactions trigger capture. There is no app selector or app allowlist.
3. Typing requests one initial screenshot and one after a one-second pause.
4. Taps, long presses, scrolling, focus, selection, and screen changes request capture after 600 milliseconds of settling, with a three-second maximum wait.
5. Repeated events reset timers instead of producing a screenshot for every character or scroll event.
6. Direct-action capture attempts stay at least 1.5 seconds apart. Passive content changes use a five-second minimum interval. Only one screenshot/OCR operation runs at a time.
7. Stop, pause, app/window changes, consent withdrawal, and service loss cancel pending screenshots. Desktop disconnection also stops desktop runs. Captured work continues through OCR and Jev.
8. Keyboard windows are masked before OCR. Other regions remain unmasked, including password fields, sensitive regions, overlays, and the lock screen. Android capture restrictions still apply.
9. When stopped, interactions update live status without creating capture requests or skipped-capture noise.
10. Overlapping windows do not automatically cancel capture. The app no longer traverses nodes to inspect sensitive content.
11. Source labels use window estimates where possible. Ambiguous sources remain uncertain. The triggering app is recorded separately as context.
12. Android reports interactive windows, so accessibility metadata does not guarantee identification of every visible overlay. Missing privacy metadata does not add masks or block capture.
13. Keep detailed masking behavior in [docs/masking-rules.md](docs/masking-rules.md). See [the current requirements](current-task/requirements.txt) for the full task scope.
14. An active scam warning pauses new screenshots and OCR. Discard screenshot and OCR work that crosses a warning boundary.
15. Phone **Start Run** requires both keys, voice consent, and microphone permission in addition to the capture requirements above.
16. Phone and desktop runs share one capture, OCR, Jev, and warning flow. Only one run can be active.

## Run records

1. Each run has a unique ID, timestamps, status, counters, and capture IDs. Only desktop-started runs save OCR and event records.
2. `events.jsonl` preserves the ordered event record.
3. `ocr` contains extracted text files and capture JSON files with OCR metadata and available Jev analysis.
4. `summary.txt` and `run.json` describe the run and its outcome. OCR records retain keyboard masks and historical mask metadata.
5. **Export ZIP** packages the saved run for analysis.
6. Desktop events save as they arrive. A lost desktop connection stops its run and produces an interrupted record.
7. Starting another run resets the dashboard's history. Earlier folders remain available under `runs`.
8. Jev records retain the exact questions, answers, model, question-set version, and threshold supplied by Android.
9. Stop does not cancel analysis. Late OCR and Jev results save to their original run while the recording connection remains available.
10. Older runs without Jev records remain readable and exportable.
11. Phone runs retain results in app memory only. A connected dashboard receives status, without phone-run OCR or evidence records.

## Jev experiment rules

1. Android sends OCR text directly to the TypeSafe HTTPS API. The desktop recorder does not call Jev.
2. Send the five approved Noul questions from [AIInstructions.kt](app/ai-instructions/AIInstructions.kt) together in one request.
3. The shared instruction file pins the Jev model, question-set version, and threshold.
4. A completed response triggers **Probably scam** when any answer reaches the configured threshold.
5. This flag is an experiment result, not proof of fraud. Answers below the threshold do not prove safety.
6. Missing keys, empty text, and failed requests remain explicit. They never become successful checks with zero probabilities.
7. Analyze each captured text even after typing, scrolling, screen updates, app changes, or newer captures. Associate results with their original run and capture.
8. Each dashboard column has one scrollbar. New records preserve that column's reading position.
9. Enter the TypeSafe key in **Phone keys** or the desktop **Jev key** dialog. Key inputs are masked and clear after submission.
10. This development path needs no AWS service, OVH server, or managed database.
11. Any new Jev question requires the user's audit before implementation.
12. A completed flagged check from the active run opens the centered scam warning. Old Jev results cannot reopen a dismissed warning.
13. If Android blocks the warning launch, a notification can reopen it. Android 13 and later require notification permission for this fallback.
14. Stopping a run clears the phone warning. Checks continue, and late results remain attached to the stopped run.
15. The local dashboard forwards the key through the ADB debug bridge to phone memory. Windows can retain a copy under the saved-key rules below.
16. The phone's Jev readiness state confirms a loaded key. It does not validate credentials.
17. Clearing the key prevents new Jev submissions. Already submitted requests continue with their original key. Ending the phone process ends in-memory work.

## Voice warning rules

1. Voice setup, warning behavior, WebRTC connections, shutdown, and data handling are documented in [docs/voice.md](docs/voice.md).

## Saved-key rules

1. On Windows, **Remember on this PC** saves the submitted key in Windows Credential Manager. The checkbox starts selected.
2. Save Jev and OpenAI credentials separately for each phone serial and Windows user. Use `CRED_PERSIST_LOCAL_MACHINE` to retain them on this PC.
3. Reconnects and reinstalls restore saved keys for the selected phone only when Android reports that the provider key is missing.
4. **Use saved key** manually loads that provider's saved key into the selected phone.
5. Desktop **Forget saved key** deletes the Windows credential and clears the active phone key. It does not delete a separately saved phone copy.
6. Sending a key with **Remember on this PC** cleared deletes any previous saved copy. The phone keeps the new key only in process memory.
7. Windows credentials remain outside this repository, browser storage, logs, and exports. The desktop server holds keys briefly in memory during delivery.
8. The browser receives saved-key availability only. It never receives a saved key value through responses, snapshots, or events.
9. Other operating systems retain manual key entry and memory-only delivery. No cloud vault or credentials bundled in APKs are part of this development feature.
10. Phone **Remember on this phone** is optional and starts cleared. It saves that provider's key with AES-GCM encryption protected by Android Keystore.
11. Encrypted phone keys stay under `noBackupFilesDir`, outside Android backups. Never store plaintext keys in preferences or logs.
12. Saved phone keys restore when the app process starts. A newer key entry or clear action takes priority over restoration.
13. Phone **Clear key** deletes the active key and that provider's saved phone copy. It does not delete Windows credentials.
14. Desktop key delivery and clearing affect phone memory only. Manage a separately saved phone copy from **Phone keys**.

## Data boundary

1. A desktop run records extracted text on the connected computer. That text can include private messages and visible passwords.
2. Screenshot images remain in Android memory. The recorder does not save screenshot files.
3. Run folders and dashboard runtime files are ignored by Git. Do not manually commit or publish them.
4. OCR text never goes to Logcat or console diagnostics.
5. Stop and consent withdrawal prevent future capture. They do not erase records already saved on the computer.
6. ML Kit runs OCR on the device. With consent and a configured key, the debug app sends extracted text to TypeSafe for Jev analysis.
7. The debug app has Internet access for Jev and OpenAI voice. Each cloud use requires its disclosed consent.
8. The debug bridge uses an ADB-forwarded local socket. Release builds expose no dashboard socket.
9. The desktop server binds only to this computer. Browser updates use a pushed event stream.
10. Android does not expose every physical touch as an accessibility event. Unknown states must remain explicit.
11. `isAccessibilityTool` remains false. This is not a Google Play approval claim.
12. Active keys stay in phone process memory. Optional encrypted phone copies and Windows credentials follow the saved-key rules above.
13. Do not store plaintext API keys in preferences, source files, APK resources, run files, browser storage, or diagnostics.
14. Enter API keys in Antiscam setup or the dashboard. Do not send them in chat messages or scripts.
15. The phone uses each key only with its provider. The recorder never includes keys in events or exports.
16. Voice sends the flagged text, detection context, and microphone audio directly to OpenAI. The app does not save voice audio or transcripts locally.
17. Desktop Jev and OpenAI keys use one masked dialog and the same validation and ADB forwarding path. Inputs clear after submission or dismissal.
18. Version 3 capture consent covers both run modes. Phone runs send OCR text to Jev without creating desktop records.

## Code

1. `app` contains capture, OCR, Jev analysis, warnings, debug voice, run controls, phone keys, the event stream, and consent/accessibility setup.
2. `dashboard` contains the desktop server, recorder, and browser interface.
3. `scripts` contains build, install, and dashboard launch helpers for Antiscam only.
4. The default build creates the debug app and runs Android lint. It does not run unit tests under the current project instructions.
5. Device checks use real apps. The demo app and its module were removed.
6. AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.3.10, ML Kit text recognition 16.0.1, target/compile SDK 36.
7. Live voice requires an OpenAI key with `gpt-live-1` access. Syntax checks and builds do not verify a live voice session.
8. Jev, voice, phone credentials, and the dashboard bridge remain development features. Release builds retain their existing networking and AI limits.
