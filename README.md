# Antiscam run console

An Android capture service with direct Jev analysis, a separate desktop dashboard, and saved run records.

The agreed task scope is in [current-task/requirements.txt](current-task/requirements.txt).

## Start

1. Start the Android emulator or connect a debugging-enabled Android device.
2. Build with `scripts\build.ps1`.
3. Install Antiscam with `scripts\install.ps1`.
4. Open **Antiscam Dev**. Read the updated disclosure and allow capture and cloud text analysis.
5. Open Android Accessibility settings. Enable **Antiscam interaction capture**.
6. Double-click **Start Dashboard.cmd**, or run `scripts\dashboard.ps1`.
7. Select the device and choose **Connect** or **Switch device**.
8. Select **Jev key** in the desktop toolbar. Enter the TypeSafe key, send it, and wait for Jev readiness before **Start run**.
9. Use an app on the selected phone or emulator. Type, pause, tap, scroll, or switch apps.
10. Read Jev results on the left, full OCR text in the middle, and activity on the right. Each list shows newest entries first.
11. Select **Stop run**. The saved folder under `runs` contains the record for analysis.
12. Select **Export ZIP** to share the saved run for analysis.

```powershell
.\scripts\build.ps1
.\scripts\install.ps1 -Serial emulator-5554
.\scripts\dashboard.ps1 -Serial emulator-5554
```

## Connected phone

1. Connect the configured Galaxy S10+ by USB and accept the USB debugging prompt.
2. Open **Start Phone Dashboard.cmd** to connect the original console at `http://127.0.0.1:8765/` to the phone.
3. To change devices in that console, stop the run, select **Device**, choose the target, and select **Switch device**.
4. Both shortcuts reuse one console. New phone and emulator runs save under `runs`, with the selected device recorded in each run.
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

## Capture rules

1. An active run, consent, enabled monitoring, and a connected accessibility service are required.
2. Screenshots capture the visible default display. Foreground app interactions trigger capture. There is no app selector or app allowlist.
3. Typing requests one initial screenshot and one after a one-second pause.
4. Taps, long presses, scrolling, focus, selection, and screen changes request capture after 600 milliseconds of settling, with a three-second maximum wait.
5. Repeated events reset timers instead of producing a screenshot for every character or scroll event.
6. Direct-action capture attempts stay at least 1.5 seconds apart. Passive content changes use a five-second minimum interval. Only one screenshot/OCR operation runs at a time.
7. Stop, pause, app/window changes, consent withdrawal, and connection loss cancel only screenshots that have not started. Captured work continues through OCR and Jev.
8. Keyboard windows are masked before OCR. Other regions remain unmasked, including password fields, sensitive regions, overlays, and the lock screen. Android capture restrictions still apply.
9. When stopped, interactions update live status without creating capture requests or skipped-capture noise.
10. Overlapping windows do not automatically cancel capture. The app no longer traverses nodes to inspect sensitive content.
11. Source labels use window estimates where possible. Ambiguous sources remain uncertain. The triggering app is recorded separately as context.
12. Android reports interactive windows, so accessibility metadata does not guarantee identification of every visible overlay. Missing privacy metadata does not add masks or block capture.
13. Keep detailed masking behavior in [docs/masking-rules.md](docs/masking-rules.md). See [the current requirements](current-task/requirements.txt) for the full task scope.

## Run records

1. Each run has a unique ID, timestamps, status, counters, and a capture ID for each screenshot request.
2. `events.jsonl` preserves the ordered event record.
3. `ocr` contains extracted text files and capture JSON files with OCR metadata and available Jev analysis.
4. `summary.txt` and `run.json` describe the run and its outcome. OCR records retain keyboard masks and historical mask metadata.
5. **Export ZIP** packages the saved run for analysis.
6. Events save as they arrive. A lost connection produces an interrupted record.
7. Starting another run resets the dashboard's history. Earlier folders remain available under `runs`.
8. Jev records retain the exact questions, answers, model, question-set version, and threshold supplied by Android.
9. Stop does not cancel analysis. Late OCR and Jev results save to their original run while the recording connection remains available.
10. Older runs without Jev records remain readable and exportable.

## Jev experiment rules

1. Android sends OCR text directly to the TypeSafe HTTPS API. The desktop recorder does not call Jev.
2. The approved six Noul questions are sent together in one request. Android defines the questions once. Topics cover crypto, private apps, crypto investment, moving another person's money, bank-account login, and scam attempts.
3. The experiment pins `jev-1.13.0` and identifies the question set as `financial-topics-v2`.
4. A completed response triggers **Probably scam** when any answer is at least `0.7`.
5. This flag is an experiment result, not proof of fraud. Answers below the threshold do not prove safety.
6. Missing keys, empty text, and failed requests remain explicit. They never become successful checks with zero probabilities.
7. Analyze each captured text even after typing, scrolling, screen updates, app changes, or newer captures. Save results with their original run and capture.
8. Each dashboard column has one scrollbar. New records preserve that column's reading position.
9. Enter the key in the desktop **Jev key** dialog. Its masked input clears after submission or dismissal.
10. This development path needs no AWS service, OVH server, or managed database.
11. Any new Jev question requires the user's audit before implementation.
12. A completed flagged check from the active run shows a silent **Probably scam** notification with its original capture number. It contains no OCR text.
13. Android 13 and later need notification permission. Enable it from Android setup if phone warnings are wanted.
14. Stopping a run clears the phone warning. Checks continue, and late results remain attached to the stopped run.
15. The local dashboard forwards the key once through the ADB debug bridge to phone memory. It does not save or echo the key.
16. Successful delivery is not confirmation that Jev is ready. The phone's readiness state confirms key setup.
17. Clearing the key prevents new Jev submissions. Already submitted requests continue with their original key. Ending the phone process ends in-memory work.

## Data boundary

1. Starting a run records extracted text on the connected computer. That text can include private messages and visible passwords.
2. Screenshot images remain in Android memory. The recorder does not save screenshot files.
3. Run folders and dashboard runtime files are ignored by Git. Do not manually commit or publish them.
4. OCR text never goes to Logcat or console diagnostics.
5. Stop and consent withdrawal prevent future capture. They do not erase records already saved on the computer.
6. ML Kit runs OCR on the device. With consent and a configured key, the debug app sends extracted text to TypeSafe for Jev analysis.
7. The debug app has Internet access for Jev. The updated disclosure requires consent to this cloud text transfer.
8. The debug bridge uses an ADB-forwarded local socket. Release builds expose no dashboard socket.
9. The desktop server binds only to this computer. Browser updates use a pushed event stream.
10. Android does not expose every physical touch as an accessibility event. Unknown states must remain explicit.
11. `isAccessibilityTool` remains false. This is not a Google Play approval claim.
12. After delivery, the key remains only in phone process memory. Browser and server memory hold it briefly during delivery.
13. Do not store the key in preferences, source files, APK resources, run files, browser storage, or diagnostics.
14. Enter the key in the dashboard beside the chat. Do not send it in chat messages or scripts.
15. The phone uses the key as the TypeSafe HTTPS authorization credential. The recorder never includes it in events or exports.

## Code

1. `app` contains the accessibility service, local OCR, direct debug Jev client, event stream, and consent/accessibility setup.
2. `dashboard` contains the desktop server, recorder, and browser interface.
3. `scripts` contains build, install, and dashboard launch helpers for Antiscam only.
4. The default build creates the debug app and runs Android lint. It does not run unit tests under the current project instructions.
5. Device checks use real apps. The demo app and its module were removed.
6. AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.3.10, ML Kit text recognition 16.0.1, target/compile SDK 36.
7. Voice and public-release credential distribution remain outside this development version.
