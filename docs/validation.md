# Foundation validation

Tested on 3 October 2026. This is a development prototype.

## Configuration

- Windows host with Windows Hypervisor Platform acceleration.
- Android Emulator 37.2.12, `Antiscam_API_36`, Pixel 7 profile.
- Android 16 / API 36, Google APIs x86_64 system image.
- Temurin JDK 21.0.12.1, Gradle 8.13, Android Gradle Plugin 8.13.2.
- Bundled ML Kit Latin text recognition 16.0.1.

## Build results

The following command passed:

```powershell
.\gradlew.bat :app:assembleDebug :fixture:assembleDebug :app:testDebugUnitTest :app:lintDebug :fixture:lintDebug
```

Both debug APKs built. All six `CaptureStoreTest` tests passed. Lint reported no errors, with 10 app warnings and 5 fixture warnings.

Warnings concern dependency updates, launcher icons, fixture strings, optional KTX usage, API 30 metadata, and backup rules. The app disables backup. Explicit device-transfer exclusion rules remain a hardening task. OCR text and screenshots are not app-persisted data.

The packaged manifests of both APKs contain neither `INTERNET` nor `ACCESS_NETWORK_STATE`. OCR succeeded with these permissions absent.

## Emulator results

The test used only the synthetic conversation in `com.antiscam.fixture`.

| Check | Observed result |
| --- | --- |
| Type in the selected test app | The development screen showed the parcel message and the typed marker `foundation731`. |
| Open the selected app without typing | The result count stayed at one. |
| Type in a password field | No new result. The password marker was absent from OCR. |
| Type with `Block screenshots` enabled | Android rejected capture. The development screen showed the protected-screen error. |
| Remove screenshot protection and type again | A new OCR result appeared. |
| Pause capture, then type | The previous OCR result remained unchanged. |
| Withdraw consent | The OCR result cleared, and the consent screen appeared. |

ADB installed and exercised the two debug apps. The emulator's accessibility service was enabled through ADB for this test. The app's consent controls were exercised through its UI.

`uiautomator dump` temporarily disconnects other accessibility services while inspecting the UI. This caused a transient disconnected status in test snapshots. The service reconnected after the inspection finished.

## Limits

- No physical phone has been tested.
- The API 30–33 display-capture and crop path has not been tested on a device.
- WhatsApp, Gmail, Messages, Messenger, and Facebook remain unverified target presets.
- App switching during pending OCR has code guards, but this session did not complete a dedicated device test for that race.
- The six unit tests cover consent, withdrawal, pause, result clearing, target changes, and own-app rejection.
- This session did not measure battery use, latency distributions, OCR accuracy, or manufacturer-specific behavior.
- No classifier, warning notification, server connection, or voice agent exists in this milestone.
- A successful local test does not establish Google Play acceptance.

## Installed development tools

Android Studio 2026.2.1.8 is installed at `%LOCALAPPDATA%\Programs\Android Studio`. Its executable has a valid Google signature.

The SDK is at `%USERPROFILE%\Android\Sdk` (`C:\Users\Jonat\Android\Sdk` on this computer). The emulator, Platform 36, Build Tools 35.0.0, Platform Tools, and command-line tools are installed.

Studio's bundled Java 25 runs the IDE. The project uses the separate Java 21 installation for Gradle. The local `.gradle/config.properties` points to that runtime and is not committed.

Windows MSIX redirected the original AppData SDK path into Codex's private cache. File-handle checks confirmed the physical cache path. Studio could not see that SDK through the original path.

The SDK now uses the directory outside AppData listed above. Java 21 remains at `%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21`, which resolves to its normal physical location.
After relocation, the build, unit-test task, and lint tasks passed. Unchanged test results were reused by Gradle. Studio accepted the new SDK path and enabled OK. IDE sync still needs that dialog completed. The earlier OCR results precede the relocation.
