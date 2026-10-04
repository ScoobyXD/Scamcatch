param([string]$Serial)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$userSdkDirectory = Join-Path $env:USERPROFILE 'Android\Sdk'
$sdkDirectory = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif (Test-Path -LiteralPath $userSdkDirectory -PathType Container) {
    $userSdkDirectory
} else {
    "$env:LOCALAPPDATA\Android\Sdk"
}
$adbPath = Join-Path $sdkDirectory 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw 'Install Android SDK Platform Tools.' }
$deviceArguments = if ($Serial) { @('-s', $Serial) } else { @() }
$apkPath = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apkPath)) { throw 'Build the app debug APK before installing.' }
& $adbPath @deviceArguments install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'Installing Antiscam failed.' }
& $adbPath @deviceArguments shell am start -n 'com.antiscam.app/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Could not open Antiscam Dev.' }
