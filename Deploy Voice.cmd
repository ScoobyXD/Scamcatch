@echo off
setlocal
rem Deploys ScamCatch live voice to scamcatch.io: website\scamcatch\infra\deploy_voice.py (needs Python 3.11+ and the AWS CLI).
cd /d "%~dp0"
where python >nul 2>nul && (set "PY=python") || (set "PY=py -3")
%PY% website\scamcatch\infra\deploy_voice.py
echo.
pause
