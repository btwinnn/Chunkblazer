@echo off
setlocal enabledelayedexpansion
title Update RuneLite Dev Client
color 0B

:: ============================================================
:: update-runelite.bat
:: ------------------------------------------------------------
:: Pulls latest runelite/runelite, stops Gradle daemons,
:: clears the project Gradle cache, and rebuilds runelite-client.
::
:: Use this when:
::  - The dev client throws AbstractMethodError on login
::    (api / injected-client revision skew)
::  - You want to make sure your runelite is current before
::    a play session
::
:: This does NOT touch C:\Chunkblazer or the plugin sources
:: copied into runelite-client. Untracked files (your plugin
:: copy) survive the pull. setup-chunkblazer.bat is the
:: nuclear option (deletes + reclones C:\runelite); this is
:: the day-to-day refresh.
:: ============================================================

set "RUNELITE_DIR=C:\runelite"
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "LOG_FILE=%SCRIPT_DIR%\update-runelite-log.txt"

echo ========================================> "%LOG_FILE%"
echo Update RuneLite Log>> "%LOG_FILE%"
echo Started: %DATE% %TIME%>> "%LOG_FILE%"
echo ========================================>> "%LOG_FILE%"
echo.>> "%LOG_FILE%"

call :log "========================================"
call :log "    Update RuneLite Dev Client"
call :log "========================================"
call :log ""

if not exist "%RUNELITE_DIR%\gradlew.bat" (
    call :log "ERROR: RuneLite not found at %RUNELITE_DIR%"
    call :log "Run setup-chunkblazer.bat first."
    pause
    exit /b 1
)

cd /d "%RUNELITE_DIR%"

:: ---- 1/4 Pull latest ------------------------------------------------
call :log "[1/4] Pulling runelite/runelite master..."
git -C "%RUNELITE_DIR%" rev-parse HEAD > "%TEMP%\rl_old_head.txt" 2>nul
set /p OLD_HEAD=<"%TEMP%\rl_old_head.txt"
del "%TEMP%\rl_old_head.txt" >nul 2>&1

git pull origin master >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log ""
    call :log "ERROR: git pull failed."
    call :log "Common causes:"
    call :log "  - Local edits conflict with incoming commits"
    call :log "      Resolve with: cd %RUNELITE_DIR% ^&^& git status"
    call :log "      Then: git stash, git pull, git stash pop"
    call :log "  - Network issue"
    call :log ""
    call :log "Full log: %LOG_FILE%"
    pause
    exit /b 1
)

git -C "%RUNELITE_DIR%" rev-parse HEAD > "%TEMP%\rl_new_head.txt" 2>nul
set /p NEW_HEAD=<"%TEMP%\rl_new_head.txt"
del "%TEMP%\rl_new_head.txt" >nul 2>&1

if "!OLD_HEAD!"=="!NEW_HEAD!" (
    call :log "  Already up to date (HEAD: !NEW_HEAD:~0,8!)"
) else (
    call :log "  Pulled: !OLD_HEAD:~0,8! -> !NEW_HEAD:~0,8!"
    git log --oneline !OLD_HEAD!..!NEW_HEAD! >> "%LOG_FILE%" 2>&1
)
call :log ""

:: ---- 2/4 Stop Gradle daemons ----------------------------------------
call :log "[2/4] Stopping Gradle daemons..."
cd /d "%RUNELITE_DIR%"
call "%RUNELITE_DIR%\gradlew.bat" --stop >> "%LOG_FILE%" 2>&1
call :log ""

:: ---- 3/4 Clear .gradle cache ----------------------------------------
call :log "[3/4] Clearing project .gradle cache..."
if exist "%RUNELITE_DIR%\.gradle" (
    rmdir /s /q "%RUNELITE_DIR%\.gradle" 2>nul
    call :log "  .gradle cleared"
) else (
    call :log "  .gradle not present (already clean)"
)
call :log ""

:: ---- 4/4 Build ------------------------------------------------------
call :log "[4/4] Building runelite-client (this may take a minute)..."
echo Building... see %LOG_FILE% for live progress.
cd /d "%RUNELITE_DIR%"
call "%RUNELITE_DIR%\gradlew.bat" --no-daemon :client:build -x test -x checkstyleMain -x pmdMain >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log ""
    call :log "========================================"
    call :log "    BUILD FAILED"
    call :log "========================================"
    call :log "Full log: %LOG_FILE%"
    pause
    exit /b 1
)

call :log ""
call :log "========================================"
call :log "    RuneLite Updated!"
call :log "========================================"
call :log ""

cd /d "%RUNELITE_DIR%"
for /f "tokens=*" %%i in ('git log --oneline -1') do call :log "  HEAD: %%i"
call :log ""
call :log "Now relaunch via IntelliJ or run-chunkblazer.bat."
call :log ""
call :log "Finished: %DATE% %TIME%"

pause
exit /b 0

:log
if "%~1"=="" (
    echo.
    echo.>> "%LOG_FILE%"
) else (
    echo %~1
    echo %~1>> "%LOG_FILE%"
)
goto :eof
