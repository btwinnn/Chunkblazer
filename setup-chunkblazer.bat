@echo off
setlocal enabledelayedexpansion
title ChunkBlazer First-Time Setup
color 0E

:: Set up logging
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "LOG_FILE=%SCRIPT_DIR%\setup-log.txt"

:: Clear previous log and start fresh
echo ========================================> "%LOG_FILE%"
echo ChunkBlazer Setup Log>> "%LOG_FILE%"
echo Started: %DATE% %TIME%>> "%LOG_FILE%"
echo ========================================>> "%LOG_FILE%"
echo.>> "%LOG_FILE%"

call :log "========================================"
call :log "    ChunkBlazer First-Time Setup"
call :log "========================================"
call :log ""
call :log "This script will:"
call :log "  1. Clone RuneLite source code (if needed)"
call :log "  2. Copy ChunkBlazer plugin into RuneLite"
call :log "  3. Build RuneLite with Gradle"
call :log ""
call :log "REQUIREMENTS:"
call :log "  - Git installed and in PATH"
call :log "  - Java 11+ installed (JDK, not just JRE)"
call :log ""
echo Press any key to continue or Ctrl+C to cancel...
pause >nul

:: Set paths
set "CHUNKBLAZER_DIR=%SCRIPT_DIR%"
set "RUNELITE_DIR=C:\runelite"
set "PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%\src\main\java\net\runelite\client\plugins\chunkblazer"
set "PLUGIN_RESOURCES=%CHUNKBLAZER_DIR%\src\main\resources\net\runelite\client\plugins\chunkblazer"

call :log "Configuration:"
call :log "  CHUNKBLAZER_DIR: %CHUNKBLAZER_DIR%"
call :log "  RUNELITE_DIR: %RUNELITE_DIR%"
call :log "  LOG_FILE: %LOG_FILE%"
call :log ""

:: Check for Java
call :log "[1/4] Checking for Java..."
java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log ""
    call :log "ERROR: Java is not installed or not in PATH."
    call :log "Please install Java JDK 11 or higher from https://adoptium.net/"
    call :log ""
    call :logfail
    pause
    exit /b 1
)
:: Get Java version for log
for /f "tokens=*" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    call :log "  %%i"
)
call :log "Java found!"
call :log ""

:: Check for Git
call :log "Checking for Git..."
git --version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log ""
    call :log "ERROR: Git is not installed or not in PATH."
    call :log "Please install Git from https://git-scm.com/download/win"
    call :log ""
    call :logfail
    pause
    exit /b 1
)
for /f "tokens=*" %%i in ('git --version') do call :log "  %%i"
call :log "Git found!"
call :log ""

:: Check if RuneLite already exists and has gradlew
if exist "%RUNELITE_DIR%\gradlew.bat" (
    call :log "[2/4] RuneLite directory already exists at %RUNELITE_DIR%"
    call :log "  Skipping clone..."
) else (
    if exist "%RUNELITE_DIR%" (
        call :log "[2/4] RuneLite directory exists but appears incomplete."
        call :log "  Removing and re-cloning..."
        rd /s /q "%RUNELITE_DIR%" 2>nul
    ) else (
        call :log "[2/4] Cloning RuneLite repository..."
    )
    call :log "  Target: %RUNELITE_DIR%"
    call :log "  This may take a few minutes..."
    git clone https://github.com/runelite/runelite.git "%RUNELITE_DIR%" >> "%LOG_FILE%" 2>&1
    if %ERRORLEVEL% NEQ 0 (
        call :log "ERROR: Failed to clone RuneLite"
        call :log "Check log for details: %LOG_FILE%"
        call :logfail
        pause
        exit /b 1
    )
    call :log "  Clone complete!"
)
call :log ""

:: Verify gradlew exists
if not exist "%RUNELITE_DIR%\gradlew.bat" (
    call :log "ERROR: gradlew.bat not found in %RUNELITE_DIR%"
    call :log "The RuneLite clone may be corrupted. Try deleting C:\runelite and running again."
    call :logfail
    pause
    exit /b 1
)

:: Copy ChunkBlazer plugin into RuneLite (instead of symlinks)
call :log "[3/4] Copying ChunkBlazer plugin into RuneLite..."
set "JAVA_PLUGINS_DIR=%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins"
set "RES_PLUGINS_DIR=%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins"

:: Create plugin directories if they don't exist
if not exist "%JAVA_PLUGINS_DIR%" (
    call :log "  Creating: %JAVA_PLUGINS_DIR%"
    md "%JAVA_PLUGINS_DIR%" 2>nul
)
if not exist "%RES_PLUGINS_DIR%" (
    call :log "  Creating: %RES_PLUGINS_DIR%"
    md "%RES_PLUGINS_DIR%" 2>nul
)

set "JAVA_DEST=%JAVA_PLUGINS_DIR%\chunkblazer"
set "RES_DEST=%RES_PLUGINS_DIR%\chunkblazer"

call :log "  Copying Java sources..."
call :log "    From: %PLUGIN_JAVA_SRC%"
call :log "    To: %JAVA_DEST%"

:: Remove existing plugin folder if it exists
if exist "%JAVA_DEST%" (
    call :log "    Removing existing Java folder..."
    rd /s /q "%JAVA_DEST%" 2>nul
)

:: Copy Java sources
xcopy "%PLUGIN_JAVA_SRC%" "%JAVA_DEST%" /E /I /H /Y >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log "ERROR: Failed to copy Java sources."
    call :logfail
    pause
    exit /b 1
)
call :log "    Java sources copied!"

call :log "  Copying resources..."
call :log "    From: %PLUGIN_RESOURCES%"
call :log "    To: %RES_DEST%"

:: Remove existing resources folder if it exists
if exist "%RES_DEST%" (
    call :log "    Removing existing resources folder..."
    rd /s /q "%RES_DEST%" 2>nul
)

:: Copy resources (if they exist)
if exist "%PLUGIN_RESOURCES%" (
    xcopy "%PLUGIN_RESOURCES%" "%RES_DEST%" /E /I /H /Y >> "%LOG_FILE%" 2>&1
    if %ERRORLEVEL% NEQ 0 (
        call :log "WARNING: Failed to copy resources (may be empty)."
    ) else (
        call :log "    Resources copied!"
    )
) else (
    call :log "    No resources folder found (skipping)."
)

call :log "Plugin files copied successfully!"
call :log ""

:: Build RuneLite using Gradle
call :log "[4/4] Building RuneLite with Gradle..."
call :log "  This may take several minutes on first run..."
call :log "  Build output is being logged to: %LOG_FILE%"
call :log ""
echo Building... (this takes a while, check %LOG_FILE% for progress)
cd /d "%RUNELITE_DIR%"

:: Clean first to ensure Lombok processes all files
call :log "  Running: gradlew.bat :client:clean"
call "%RUNELITE_DIR%\gradlew.bat" :client:clean >> "%LOG_FILE%" 2>&1

:: Run Gradle build and capture output to log
call :log "  Running: gradlew.bat :client:build -x test"
call "%RUNELITE_DIR%\gradlew.bat" :client:build -x test >> "%LOG_FILE%" 2>&1
set "BUILD_RESULT=%ERRORLEVEL%"

if %BUILD_RESULT% NEQ 0 (
    call :log ""
    call :log "========================================"
    call :log "    BUILD FAILED"
    call :log "========================================"
    call :log ""
    call :log "Check the full log for errors: %LOG_FILE%"
    call :log ""
    call :log "Common issues:"
    call :log "  - Java JDK not installed (need JDK, not just JRE)"
    call :log "  - Wrong Java version (need Java 11+)"
    call :log "  - Network issues downloading dependencies"
    call :log "  - Disk space issues"
    call :log "  - Plugin code compilation errors"
    call :log ""
    call :logfail
    echo.
    echo BUILD FAILED - Check log file: %LOG_FILE%
    pause
    exit /b 1
)

call :log ""
call :log "========================================"
call :log "    Setup Complete!"
call :log "========================================"
call :log ""
call :log "Finished: %DATE% %TIME%"
call :log ""
call :log "You can now use run-chunkblazer.bat to launch the dev client."
call :log ""
call :log "NOTE: Plugin files were COPIED to RuneLite."
call :log "      If you update ChunkBlazer, re-run this setup script."
call :log ""
call :log "Log saved to: %LOG_FILE%"

echo.
echo ========================================
echo     Setup Complete!
echo ========================================
echo.
echo Log saved to: %LOG_FILE%
echo.
echo You can now use run-chunkblazer.bat to launch the dev client.
echo.
pause
exit /b 0

:: ========================================
:: Subroutines
:: ========================================

:log
:: Logs message to both console and file
echo %~1
echo %~1>> "%LOG_FILE%"
goto :eof

:logfail
:: Appends failure marker to log
echo.>> "%LOG_FILE%"
echo ======================================== >> "%LOG_FILE%"
echo SETUP FAILED: %DATE% %TIME% >> "%LOG_FILE%"
echo ======================================== >> "%LOG_FILE%"
goto :eof
