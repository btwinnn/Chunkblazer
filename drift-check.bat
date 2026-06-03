@echo off
:: Wrapper so you can double-click or run drift-check from cmd without worrying
:: about PowerShell execution policy. Real logic lives in drift-check.ps1.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0drift-check.ps1"
exit /b %ERRORLEVEL%
