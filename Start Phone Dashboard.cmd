@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\dashboard.ps1" -Serial R58N83XN7BE
if errorlevel 1 pause
