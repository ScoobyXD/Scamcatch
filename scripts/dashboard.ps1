param(
    [string]$Serial,
    [ValidateRange(1024, 65535)][int]$Port = 8765,
    [switch]$Foreground,
    [switch]$NoBrowser,
    [string]$RunsDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$serverScript = Join-Path $projectRoot 'dashboard\server.py'
$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
$pythonArguments = @()
if (-not $pythonCommand) {
    $pythonCommand = Get-Command py -ErrorAction SilentlyContinue
    $pythonArguments = @('-3')
}
if (-not $pythonCommand) { throw 'Install Python 3.11 or later, then run this launcher again.' }
$pythonExecutable = $pythonCommand.Source
& $pythonExecutable @pythonArguments -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)'
if ($LASTEXITCODE -ne 0) { throw 'This dashboard requires Python 3.11 or later.' }

$dashboardUrl = "http://127.0.0.1:$Port/"
$existing = $null
try {
    $existing = Invoke-RestMethod -Uri "${dashboardUrl}api/session" -TimeoutSec 2
} catch { }
if ($existing.token) {
    if ($Serial -and ($existing.selectedDevice -ne $Serial -or $existing.connection -eq 'disconnected')) {
        if ($existing.run.status -in @('starting', 'active', 'stopping')) {
            throw 'Stop the current run before changing devices.'
        }
        $requestHeaders = @{ Origin = $dashboardUrl.TrimEnd('/'); 'X-Antiscam-Token' = $existing.token }
        $null = Invoke-RestMethod -Method Post -Uri "${dashboardUrl}api/devices" -Headers $requestHeaders -ContentType 'application/json' -Body '{}'
        $connectBody = @{ serial = $Serial } | ConvertTo-Json -Compress
        $null = Invoke-RestMethod -Method Post -Uri "${dashboardUrl}api/connect" -Headers $requestHeaders -ContentType 'application/json' -Body $connectBody -TimeoutSec 45
    }
    if (-not $NoBrowser) { & $pythonExecutable @pythonArguments $serverScript --port $Port --open-only }
    Write-Output "Antiscam run console is already available: $dashboardUrl"
    return
}

$arguments = $pythonArguments + @($serverScript, '--port', [string]$Port)
if ($Serial) { $arguments += @('--serial', $Serial) }
if ($RunsDirectory) { $arguments += @('--runs-directory', $RunsDirectory) }
if ($NoBrowser) { $arguments += '--no-browser' }
if ($Foreground) {
    & $pythonExecutable @arguments
    if ($LASTEXITCODE -ne 0) { throw 'The dashboard stopped. Check that the port is free and Python is available.' }
} else {
    # Start-Process joins arguments into one command line on Windows.
    $quotedArguments = $arguments | ForEach-Object {
        if ($_ -match '["\r\n]') { throw 'Dashboard arguments cannot contain quotes or line breaks.' }
        '"' + $_ + '"'
    }
    $process = Start-Process -FilePath $pythonExecutable -ArgumentList $quotedArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru
    $startupDeadline = [DateTime]::UtcNow.AddSeconds(3.5)
    $ready = $false
    do {
        $process.Refresh()
        if ($process.HasExited) { throw 'The dashboard could not start. Check that its port is free and Python can access the project.' }
        try {
            $session = Invoke-RestMethod -Uri "${dashboardUrl}api/session" -TimeoutSec 1
            $ready = [bool]$session.token
        } catch { }
        if ($ready) { break }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $startupDeadline)
    $process.Refresh()
    if ($process.HasExited -or -not $ready) { throw 'The dashboard did not become ready. Run with -Foreground to inspect startup.' }
    Write-Output "Antiscam run console: $dashboardUrl"
    Write-Output "Recorder process: $($process.Id). Stop the run in the dashboard before closing the recorder."
}
