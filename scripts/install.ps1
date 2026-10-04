param([string]$Serial)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkDirectory = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adbPath = Join-Path $sdkDirectory 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw 'Install Android SDK Platform Tools.' }
$deviceArguments = if ($Serial) { @('-s', $Serial) } else { @() }
foreach ($module in @('app', 'fixture')) {
    $apkPath = Join-Path $projectRoot "$module\build\outputs\apk\debug\$module-debug.apk"
    if (-not (Test-Path -LiteralPath $apkPath)) { throw 'Build both debug APKs before installing.' }
    & $adbPath @deviceArguments install -r $apkPath
    if ($LASTEXITCODE -ne 0) { throw "Installing $module failed." }
}
& $adbPath @deviceArguments shell am start -n 'com.antiscam.app/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Could not open Antiscam Dev.' }
