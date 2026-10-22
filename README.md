# Scamcatch run console

1. Android runs capture, Jev analysis, and automatic voice warnings with or without a connected laptop.
2. Desktop runs add a dashboard and saved diagnostic summaries.
3. The agreed task scope is in [current-task/requirements.txt](current-task/requirements.txt).
4. All privacy rules are in [docs/privacy.md](docs/privacy.md), including approved apps, capture gates, microphone use, cloud transfer, and saved keys.

## Edit Jev questions and voice scripts

1. [app/ai-instructions/AIInstructions.kt](app/ai-instructions/AIInstructions.kt) defines Jev questions, models, thresholds, the voice name, greeting, and shared evidence framing.
2. [app/src/main/assets/voice-scripts.md](app/src/main/assets/voice-scripts.md) is the single source for phone voice scripts and website demo scripts.
3. After a Kotlin edit, stop the run, build, install, and restart Antiscam. Markdown script edits use the desktop copy process below.
4. Ask the user to audit any new Jev question before adding it.

## Manage voice scripts

1. Keep 1 to 100 named scripts in [voice-scripts.md](app/src/main/assets/voice-scripts.md). Each section uses `## script_id | Display name`.
2. The library contains `baseline` (**Baseline**) and `uses_scam_language` (**Uses scam language**). Baseline uses the user's new text. Uses scam language preserves the previous text.
3. `ACTIVE_VOICE_SCRIPT = baseline` selects Baseline when the file reaches the phone. Selection is manual. The app does not run A/B experiments automatically.
4. Stop the run before changing scripts. Edit the actual Markdown file in your editor.
5. In the desktop console, open **Voice scripts** and select **Reload file**. The console displays a preview without changing the phone.
6. Select **Copy file to phone** to save the library and select `ACTIVE_VOICE_SCRIPT`. Wait for phone confirmation before disconnecting.
7. Select a copied script and choose **Use on phone** to change the phone selection. This does not edit the Markdown variable.
8. On the phone, use **Voice script**, **Choose script**, then **Save selection**. The saved library and selection survive app restarts.
9. Each run keeps its original script until it ends. A new run uses the saved selection.
10. The website's default talk action uses `baseline`. **Try the original voice** uses `uses_scam_language` for that call with the same selected scam evidence.
11. Website script selection is independent of the phone selection. Restart the local server or deploy voice after editing either website script.

## Start with the desktop

1. Start the Android emulator or connect a debugging-enabled Android device.
2. Build with `scripts\build.ps1`.
3. Install Antiscam with `scripts\install.ps1`.
4. Open **Antiscam Dev**. Read the current disclosure, grant capture/cloud consent, and select the apps to approve.
5. Open Android Accessibility settings. Enable **Antiscam interaction capture**.
6. Double-click **Start Dashboard.cmd**, or run `scripts\dashboard.ps1`.
7. Select the device and choose **Connect** or **Switch device**.
8. Select **Jev key** in the desktop toolbar. Enter the TypeSafe key if no Windows copy is saved.
9. Keep **Remember on this phone** selected to save an encrypted phone copy. **Remember on this PC** controls the separate Windows copy.
10. Send the entered key, or select **Use saved key**. Repeat for **OpenAI key**. Wait for both phone save confirmations before disconnecting.
11. This setup saves both keys for future phone runs. Key indicators confirm loading only, not valid credentials or permissions.
12. In Android setup, allow automatic voice warnings and microphone access. Then select **Start run** in the dashboard.
13. Use an approved app on the selected phone or emulator. Type, pause, tap, scroll, or switch apps.
14. Read Jev results on the left, capture summaries in the middle, and activity on the right. Each list shows newest entries first.
15. A flagged check opens a centered warning and starts voice automatically when setup is complete. The red X closes it and resumes capture.
16. Select **Stop**. The saved folder under `runs` contains diagnostic metadata.
17. Select **Export ZIP** to share a current metadata-only run.

```powershell
.\scripts\build.ps1
.\scripts\install.ps1 -Serial emulator-5554
.\scripts\dashboard.ps1 -Serial emulator-5554
```

## Start without the laptop

1. Install the current **Antiscam Dev** build with the build and install scripts above.
2. Open Antiscam. Accept the current capture/cloud disclosure and select the apps to approve.
3. Enable **Antiscam interaction capture** in Android Accessibility settings. Enable monitoring in Antiscam.
4. Keys saved from the desktop restore automatically. If either key is missing, enter it under **Phone keys**.
5. The phone's **Remember on this phone** checkbox starts cleared. Select it before saving to retain encrypted keys across app restarts.
6. Allow automatic voice warnings and microphone access. Allow notifications for the fallback if Android cannot open a warning.
7. Keep the phone connected to the Internet. Select **Start Run**, then use approved apps.
8. A flagged check vibrates the phone and opens the warning. Voice starts automatically when the warning opens and setup remains complete.
9. The red X stops voice and resumes screen checks. Select **Stop Run** in Antiscam to end the phone run.
10. Connecting or disconnecting a laptop does not stop a phone run. See [privacy rules](docs/privacy.md) for desktop data limits.
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

1. Apply the canonical [privacy rules](docs/privacy.md) before reading content or requesting capture.
2. Typing requests one initial screenshot and one after a one-second pause.
3. Taps, long presses, scrolling, focus, selection, and screen changes request capture after 600 milliseconds of settling, with a three-second maximum wait.
4. Repeated events reset timers instead of producing a screenshot for every character or scroll event.
5. Direct-action capture attempts stay at least 1.5 seconds apart. Passive content changes use a five-second minimum interval.
6. Only one screenshot/OCR operation runs at a time. Do not add a screen-polling loop.
7. When stopped, do not create capture requests or skipped-capture noise.
8. Phone and desktop runs share one capture, OCR, Jev, and warning flow. Only one run can be active.
9. Phone **Start Run** also requires both keys and completed voice setup.
10. [docs/privacy.md](docs/privacy.md) defines approved-app changes, late-result rejection, keyboard masks, and display-capture limits.

## Run records

1. Each desktop run has an ID, timestamps, status, counters, and capture IDs.
2. `events.jsonl` preserves safe ordered events. `summary.txt` and `run.json` describe the run and its outcome.
3. The dashboard shows capture summaries and numeric Jev results. Record contents follow [docs/privacy.md](docs/privacy.md).
4. Desktop events save as they arrive. A lost desktop connection stops its run and marks the record interrupted.
5. Starting another run resets the dashboard history.
6. **Export ZIP** supports current metadata-only runs. Legacy raw runs remain on disk but are not loaded or exported by the new dashboard.
7. Late safe results retain their original run and capture IDs. They cannot restart a stopped run or its warning.

## Jev experiment rules

1. Android sends OCR text directly to the TypeSafe HTTPS API. The desktop recorder does not call Jev.
2. Send the five approved Noul questions from [AIInstructions.kt](app/ai-instructions/AIInstructions.kt) together in one request.
3. The shared instruction file pins the Jev model, question-set version, and threshold.
4. A completed response triggers **Probably scam** when any answer reaches the configured threshold.
5. This flag is an experiment result, not proof of fraud. Answers below the threshold do not prove safety.
6. Missing keys, empty text, and failed requests remain explicit. They never become successful checks with zero probabilities.
7. Analyze only captures that pass the [privacy gates](docs/privacy.md). Associate results with their original run and capture.
8. Each dashboard column has one scrollbar. New records preserve that column's reading position.
9. Enter the TypeSafe key in **Phone keys** or the desktop **Jev key** dialog. Follow the [credential rules](docs/privacy.md#credentials-and-local-access).
10. This development path needs no AWS service, OVH server, or managed database.
11. Any new Jev question requires the user's audit before implementation.
12. A completed flagged check from the active run opens the centered scam warning. Old Jev results cannot reopen a dismissed warning.
13. If Android blocks the warning launch, a notification can reopen it. Android 13 and later require notification permission for this fallback.
14. Stopping a run clears the phone warning. Already submitted checks can finish under the [privacy rules](docs/privacy.md).
15. The local dashboard forwards the key through the ADB debug bridge. Phone and Windows copies follow [docs/privacy.md](docs/privacy.md#credentials-and-local-access).
16. The phone's Jev readiness state confirms a loaded key. It does not validate credentials.
17. Clearing the key prevents new Jev submissions. Already submitted requests continue with their original key. Ending the phone process ends in-memory work.

## Voice warning rules

1. Voice setup, warning behavior, WebRTC connections, shutdown, and data handling are documented in [docs/voice.md](docs/voice.md).

## Saved-key rules

1. [docs/privacy.md](docs/privacy.md#credentials-and-local-access) defines key entry, Windows and phone persistence, restoration, and deletion.
2. For setup, follow **Start with the desktop** or **Start without the laptop** above.

## Data boundary

1. [docs/privacy.md](docs/privacy.md) defines capture approval, raw-content restrictions, cloud transfer, microphone use, credentials, and California research.
2. Keep those rules in that file. Other documents link to it.

## Code

1. `app` contains capture, OCR, Jev analysis, warnings, debug voice, run controls, phone keys, the event stream, and consent/accessibility setup.
2. `dashboard` contains the desktop server, recorder, and browser interface.
3. `scripts` contains build, install, and dashboard launch helpers for Antiscam only.
4. The default build creates the debug app and runs Android lint. It does not run unit tests under the current project instructions.
5. Device checks use real apps. The demo app and its module were removed.
6. AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.3.10, ML Kit text recognition 16.0.1, target/compile SDK 36.
7. Live voice requires an OpenAI key with `gpt-live-1` access. Syntax checks and builds do not verify a live voice session.
8. Jev, voice, phone credentials, and the dashboard bridge remain development features. Release builds retain their existing networking and AI limits.
