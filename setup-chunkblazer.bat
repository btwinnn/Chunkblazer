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

:: Define a logging subroutine at the end, call it with: call :log "message"
:: For now, use a simple approach: tee each output

call :log "========================================"
call :log "    ChunkBlazer First-Time Setup"
call :log "========================================"
call :log ""
call :log "This script will:"
call :log "  1. Download Maven (if needed)"
call :log "  2. Clone RuneLite source code"
call :log "  3. Create symlinks to ChunkBlazer"
call :log "  4. Build RuneLite"
call :log ""
call :log "REQUIREMENTS:"
call :log "  - Git installed and in PATH"
call :log "  - Java 11+ installed (JDK, not just JRE)"
call :log ""
echo Press any key to continue or Ctrl+C to cancel...
pause >nul

:: Check if running as admin (needed for symlinks on some Windows configs)
net session >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log ""
    call :log "WARNING: Not running as Administrator."
    call :log "Symlink creation may fail. If it does, right-click this script"
    call :log "and select 'Run as administrator'"
    call :log ""
    pause
)

:: Set paths
set "CHUNKBLAZER_DIR=%SCRIPT_DIR%"
set "RUNELITE_DIR=C:\runelite"
set "TOOLS_DIR=%CHUNKBLAZER_DIR%\tools"
set "MAVEN_DIR=%TOOLS_DIR%\maven"
set "MAVEN_VERSION=3.9.6"
set "MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"
set "PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%\src\main\java\net\runelite\client\plugins\chunkblazer"
set "PLUGIN_RESOURCES=%CHUNKBLAZER_DIR%\src\main\resources\net\runelite\client\plugins\chunkblazer"

call :log "Configuration:"
call :log "  CHUNKBLAZER_DIR: %CHUNKBLAZER_DIR%"
call :log "  RUNELITE_DIR: %RUNELITE_DIR%"
call :log "  TOOLS_DIR: %TOOLS_DIR%"
call :log "  MAVEN_DIR: %MAVEN_DIR%"
call :log "  LOG_FILE: %LOG_FILE%"
call :log ""

:: Check for Java
call :log "Checking for Java..."
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

:: Setup Maven
call :log "[1/5] Setting up Maven..."
where mvn >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    call :log "Maven found in PATH, using system Maven."
    for /f "tokens=*" %%i in ('mvn --version 2^>^&1 ^| findstr /i "Apache Maven"') do call :log "  %%i"
    set "MVN_CMD=mvn"
    goto :maven_done
)

if exist "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" (
    call :log "Using local Maven installation."
    call :log "  Version: %MAVEN_VERSION%"
    set "MVN_CMD=%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
    goto :maven_done
)

call :log "Maven not found. Downloading Maven %MAVEN_VERSION%..."

:: Create directories using PowerShell (more reliable across Windows versions)
call :log "Creating directories..."
call :log "  Target: %MAVEN_DIR%"
powershell -Command "New-Item -ItemType Directory -Force -Path '%MAVEN_DIR%' | Out-Null" 2>nul
if not exist "%MAVEN_DIR%" (
    call :log "  PowerShell mkdir failed, trying md command..."
    md "%MAVEN_DIR%" 2>nul
)
if not exist "%MAVEN_DIR%" (
    call :log "ERROR: Failed to create directory: %MAVEN_DIR%"
    call :log "Please create this directory manually and re-run the script."
    call :logfail
    pause
    exit /b 1
)
call :log "  Directory created successfully."

:: Download Maven using PowerShell with better error handling
call :log "Downloading Maven..."
call :log "  URL: %MAVEN_URL%"
call :log "  This may take a minute..."
set "MAVEN_ZIP=%MAVEN_DIR%\maven.zip"

:: Try PowerShell Invoke-WebRequest first
call :log "  Attempting download via PowerShell..."
powershell -ExecutionPolicy Bypass -Command "$ProgressPreference = 'SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -Uri '%MAVEN_URL%' -OutFile '%MAVEN_ZIP%' -UseBasicParsing; exit 0 } catch { Write-Host $_.Exception.Message; exit 1 }"
if %ERRORLEVEL% NEQ 0 (
    call :log "  PowerShell download failed, trying curl..."
    curl -L -o "%MAVEN_ZIP%" "%MAVEN_URL%" 2>nul
    if %ERRORLEVEL% NEQ 0 (
        call :log ""
        call :log "ERROR: Failed to download Maven."
        call :log "Please check your internet connection and try again."
        call :log ""
        call :log "Alternative: Download Maven manually from:"
        call :log "  %MAVEN_URL%"
        call :log "And extract to: %MAVEN_DIR%"
        call :logfail
        pause
        exit /b 1
    )
)

:: Verify download
if not exist "%MAVEN_ZIP%" (
    call :log "ERROR: Maven download file not found."
    call :logfail
    pause
    exit /b 1
)
for %%A in ("%MAVEN_ZIP%") do set "ZIP_SIZE=%%~zA"
if "%ZIP_SIZE%"=="" set "ZIP_SIZE=0"
call :log "  Downloaded: %ZIP_SIZE% bytes"
if %ZIP_SIZE% LSS 1000000 (
    call :log "ERROR: Downloaded file is too small (%ZIP_SIZE% bytes). Download may have failed."
    del "%MAVEN_ZIP%" 2>nul
    call :logfail
    pause
    exit /b 1
)
call :log "  Download complete!"

:: Extract Maven using PowerShell
call :log "Extracting Maven..."
call :log "  Source: %MAVEN_ZIP%"
call :log "  Destination: %MAVEN_DIR%"
powershell -ExecutionPolicy Bypass -Command "try { Expand-Archive -Path '%MAVEN_ZIP%' -DestinationPath '%MAVEN_DIR%' -Force; exit 0 } catch { Write-Host $_.Exception.Message; exit 1 }"
if %ERRORLEVEL% NEQ 0 (
    call :log "ERROR: Failed to extract Maven."
    call :log "Please extract manually: %MAVEN_ZIP%"
    call :log "To: %MAVEN_DIR%"
    call :logfail
    pause
    exit /b 1
)

:: Verify extraction
if not exist "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" (
    call :log "ERROR: Maven extraction verification failed."
    call :log "Expected file not found: %MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
    call :logfail
    pause
    exit /b 1
)
call :log "  Extraction complete!"

:: Clean up zip file
del "%MAVEN_ZIP%" 2>nul
call :log "  Cleaned up zip file."

call :log "Maven installed successfully!"
set "MVN_CMD=%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"

:maven_done
call :log ""

:: Check if RuneLite already exists
if exist "%RUNELITE_DIR%" (
    call :log "[2/5] RuneLite directory already exists at %RUNELITE_DIR%"
    call :log "  Skipping clone..."
) else (
    call :log "[2/5] Cloning RuneLite repository..."
    call :log "  Target: %RUNELITE_DIR%"
    call :log "  This may take a few minutes..."
    git clone --depth 1 https://github.com/runelite/runelite.git "%RUNELITE_DIR%" >> "%LOG_FILE%" 2>&1
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

:: Create plugin directories if they don't exist
call :log "[3/5] Creating plugin directories..."
set "JAVA_PLUGINS_DIR=%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins"
set "RES_PLUGINS_DIR=%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins"

if not exist "%JAVA_PLUGINS_DIR%" (
    call :log "  Creating: %JAVA_PLUGINS_DIR%"
    md "%JAVA_PLUGINS_DIR%" 2>nul
    if not exist "%JAVA_PLUGINS_DIR%" (
        powershell -Command "New-Item -ItemType Directory -Force -Path '%JAVA_PLUGINS_DIR%' | Out-Null"
    )
)
if not exist "%RES_PLUGINS_DIR%" (
    call :log "  Creating: %RES_PLUGINS_DIR%"
    md "%RES_PLUGINS_DIR%" 2>nul
    if not exist "%RES_PLUGINS_DIR%" (
        powershell -Command "New-Item -ItemType Directory -Force -Path '%RES_PLUGINS_DIR%' | Out-Null"
    )
)
call :log "  Plugin directories ready."
call :log ""

:: Create symlinks
call :log "[4/5] Creating symlinks to ChunkBlazer..."

set "JAVA_LINK=%JAVA_PLUGINS_DIR%\chunkblazer"
set "RES_LINK=%RES_PLUGINS_DIR%\chunkblazer"

call :log "  Java source symlink:"
call :log "    Link: %JAVA_LINK%"
call :log "    Target: %PLUGIN_JAVA_SRC%"
call :log "  Resources symlink:"
call :log "    Link: %RES_LINK%"
call :log "    Target: %PLUGIN_RESOURCES%"

:: Remove existing symlinks/folders if they exist
if exist "%JAVA_LINK%" (
    call :log "  Removing existing Java link..."
    rmdir "%JAVA_LINK%" 2>nul
    if exist "%JAVA_LINK%" rd /s /q "%JAVA_LINK%" 2>nul
)
if exist "%RES_LINK%" (
    call :log "  Removing existing resources link..."
    rmdir "%RES_LINK%" 2>nul
    if exist "%RES_LINK%" rd /s /q "%RES_LINK%" 2>nul
)

:: Create new symlinks
call :log "  Creating Java symlink..."
mklink /D "%JAVA_LINK%" "%PLUGIN_JAVA_SRC%" >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log ""
    call :log "ERROR: Failed to create Java symlink."
    call :log ""
    call :log "This usually means you need to run as Administrator."
    call :log "Right-click setup-chunkblazer.bat and select 'Run as administrator'"
    call :log ""
    call :logfail
    pause
    exit /b 1
)
call :log "  Java symlink created!"

call :log "  Creating resources symlink..."
mklink /D "%RES_LINK%" "%PLUGIN_RESOURCES%" >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log ""
    call :log "ERROR: Failed to create resources symlink."
    call :log ""
    call :log "This usually means you need to run as Administrator."
    call :log "Right-click setup-chunkblazer.bat and select 'Run as administrator'"
    call :log ""
    call :logfail
    pause
    exit /b 1
)
call :log "  Resources symlink created!"
call :log "Symlinks created successfully!"
call :log ""

:: Build RuneLite
call :log "[5/5] Building RuneLite..."
call :log "  This may take several minutes on first run..."
call :log "  Build output is being logged to: %LOG_FILE%"
call :log ""
echo Building... (this takes a while, check %LOG_FILE% for progress)
cd /d "%RUNELITE_DIR%"

:: Run Maven and capture output to log
call "%MVN_CMD%" install -DskipTests >> "%LOG_FILE%" 2>&1
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
    call :log "  - Network issues downloading dependencies"
    call :log "  - Disk space issues"
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
