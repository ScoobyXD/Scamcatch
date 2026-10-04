# Antiscam foundation

An Android development prototype: **type in a selected app → capture its screen → run local ML Kit OCR → view extracted text**.

This version does not detect scams. It has no Jev integration, backend, voice agent, or warning notification.

## Projects

- `app`: **Antiscam Dev**, the accessibility service and OCR development screen.
- `fixture`: **Antiscam Test Chat**, a separate app with synthetic messages and a reply field. No account is needed.

## Requirements

- Android Studio and JDK 21. This project's AGP and Gradle versions support JDK 17 through 23.
- Android SDK Platform 36, Build Tools 35.0.0, Platform Tools, and Android Emulator.
- Android 11/API 30 or later on a device or emulator. API 34+ supports target-window screenshots.
- Gradle downloads dependencies on the first build. The wrapper pins Gradle 8.13.

Open this folder in Android Studio. In **Settings > Build, Execution, Deployment > Build Tools > Gradle**, select JDK 21 as the Gradle JDK. Let the project sync.

On this computer, JDK 21 is installed at `%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21`.
Studio's bundled Java 25 can run the IDE, but Gradle 8.13 cannot use it to run builds.

From PowerShell, the helper uses `JAVA_HOME` when it points to Java. Otherwise, it checks the JDK 21 path before the standard Studio runtime paths.
It rejects unsupported Java versions before starting Gradle. When `ANDROID_HOME` is unset, it uses `%LOCALAPPDATA%\Android\Sdk`:

```powershell
.\scripts\build.ps1
```

Or set `JAVA_HOME` to JDK 21 and `ANDROID_HOME` to your SDK, then run:

```powershell
.\gradlew.bat :app:assembleDebug :fixture:assembleDebug :app:testDebugUnitTest :app:lintDebug :fixture:lintDebug
```

## First test

1. Start an emulator in Android Studio's Device Manager, or connect a debugging-enabled phone.
2. Run both `app` and `fixture` configurations once to install them. You can also use `scripts\install.ps1`.
3. Open **Antiscam Dev**. Read the disclosure, select its checkbox, and choose **Allow local capture**.
4. Keep **Test conversation** selected.
5. Choose **Open Accessibility settings**. Enable **Antiscam typing capture** under installed/downloaded apps.
6. Return to Antiscam Dev. Confirm the service is connected.
7. Choose **Open selected app**. Type `parcel check 731` in the reply field.
8. Stop typing for at least three seconds. The first OCR run can take longer.
9. Choose **Return to AntiScam**. The result should include the visible parcel message and your reply.

Only text changes trigger capture. Opening or reading a conversation does not trigger a capture.

The service waits 700 ms after a typing burst and spaces capture attempts by at least 1.5 seconds. Results arrive only while the original eligible field/window is still active. If you return too soon, the pending result is discarded. Type again and wait longer.

## Negative checks

- Leave the fixture open without typing. No new OCR result should appear.
- Pause capture. Type in the fixture. The result must not change.
- Type and immediately switch apps. That pending result must not replace the previous result.
- Enable **Password field** in the fixture and type. Capture must be skipped.
- Enable **Block screenshots**, keep password mode off, and type. The app must report a capture failure or skip.
- Select a different app in Antiscam Dev, then type in the fixture. The fixture must not trigger capture.
- Withdraw consent. The previous text must clear, and capture must stop.

Other target presets are available for later tests. Their presence does not mean their layouts or privacy behavior are verified.

## Data boundary

- Screenshots and OCR results stay in memory. This app does not save them to files or logs.
- The app's own saved settings contain only consent, pause state, and the selected package. ML Kit can also store internal metadata.
- The merged app manifest removes Internet and network-state permissions from library manifests.
- The bundled Latin OCR model does not require a model download.
- Password or Android-marked sensitive fields cause a skip. Unmarked private text can still appear in a screenshot.
- Android secure windows remain protected. This app does not bypass them.
- The service declares `isAccessibilityTool=false`. This development build has no Google Play approval.

## Edit and run

Edit code, choose **Run**, open the fixture, type, wait, and return to inspect OCR. Service or manifest changes can require disabling and enabling the accessibility service again.

The UI uses Kotlin and Compose. The fixture uses a native editable field to provide predictable accessibility events.

## Build versions

AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.3.10, Compose BOM 2025.10.01, ML Kit text recognition 16.0.1, target/compile SDK 36, minimum SDK 30.

See `docs/validation.md` for the tested configuration and limits.
