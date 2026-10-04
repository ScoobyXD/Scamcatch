param([string[]]$Tasks = @(':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaCandidates = @(
    "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-21",
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
)
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    $javaRuntime = $javaCandidates | Where-Object { Test-Path -LiteralPath "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $javaRuntime) { throw 'Install JDK 21 or set JAVA_HOME to JDK 17 through 23.' }
    $env:JAVA_HOME = $javaRuntime
}
$javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javaVersionOutput = & $javaExecutable --version 2>&1
$javaExitCode = $LASTEXITCODE
if ($javaExitCode -ne 0) { throw "Java failed with exit code $javaExitCode. Check JAVA_HOME." }
$javaVersionText = $javaVersionOutput | Out-String
if ($javaVersionText -notmatch '(?m)^(?:openjdk|java) (?<major>\d+)(?:\.|\s|$)') {
    throw 'Could not read the Java version. Set JAVA_HOME to JDK 21.'
}
$javaMajorVersion = [int]$Matches['major']
if ($javaMajorVersion -lt 17 -or $javaMajorVersion -gt 23) {
    throw "Java $javaMajorVersion is unsupported by this build. AGP 8.13.2 and Gradle 8.13 require JDK 17 through 23. Set JAVA_HOME to JDK 21."
}
if (-not $env:ANDROID_HOME) {
    $userSdkDirectory = Join-Path $env:USERPROFILE 'Android\Sdk'
    $env:ANDROID_HOME = if (Test-Path -LiteralPath $userSdkDirectory -PathType Container) {
        $userSdkDirectory
    } else {
        "$env:LOCALAPPDATA\Android\Sdk"
    }
}
if (-not (Test-Path -LiteralPath $env:ANDROID_HOME)) { throw 'Install the Android SDK or set ANDROID_HOME.' }
Push-Location $projectRoot
try {
    & .\gradlew.bat @Tasks
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) { throw "Gradle failed with exit code $gradleExitCode." }
} finally { Pop-Location }
