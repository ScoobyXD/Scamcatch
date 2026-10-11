@echo off
setlocal
rem One-time setup so GitHub Actions can publish scamcatch.io: website\scamcatch\infra\setup_github_publish.py (needs Python 3.11+ and the AWS CLI).
cd /d "%~dp0"
where python >/dev/null 2>/dev/null && (set "PY=python") || (set "PY=py -3")
%PY% website\scamcatch\infra\setup_github_publish.py
echo.
pause
