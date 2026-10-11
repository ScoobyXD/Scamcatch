@echo off
setlocal
rem Serves website\scamcatch\public at http://localhost:5180 and opens it. Close the server window to stop.
set "SITE=%~dp0website\scamcatch\public"
set "PORT=5180"
where python >nul 2>nul && (set "PY=python") || (set "PY=py -3")
start "ScamCatch website - close this window to stop" /d "%SITE%" %PY% -m http.server %PORT% --bind 127.0.0.1
timeout /t 2 /nobreak >nul
start "" "http://localhost:%PORT%/"
