@echo off
setlocal
rem Runs website\scamcatch\server.py: the site and its live voice endpoint at http://localhost:5180. Close the server window to stop.
set "PORT=5180"
where python >nul 2>nul && (set "PY=python") || (set "PY=py -3")
start "ScamCatch website - close this window to stop" cmd /k %PY% "%~dp0website\scamcatch\server.py" --port %PORT%
timeout /t 2 /nobreak >nul
start "" "http://localhost:%PORT%/"
