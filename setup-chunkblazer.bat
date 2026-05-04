@echo off
setlocal enabledelayedexpansion
title ChunkBlazer First-Time Setup
color 0E

echo ========================================
echo     ChunkBlazer First-Time Setup
echo ========================================
echo.
echo This script will:
echo   1. Download Maven (if needed)
echo   2. Clone RuneLite source code
echo   3. Create symlinks to ChunkBlazer
echo   4. Build RuneLite
echo.
echo REQUIREMENTS:
echo   - Git installed and in PATH
echo   - Java 11+ installed (JDK, not just JRE)
echo.
echo Press any key to continue or Ctrl+C to cancel...
pause >nul

:: Check if running as admin (needed for symlinks on some Windows configs)
net session >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo WARNING: Not running as Administrator.
    echo Symlink creation may fail. If it does, right-click this script
    echo and select "Run as administrator"
    echo.
    pause
)

:: Set paths - remove any trailing backslash from dp0 for consistency
set "CHUNKBLAZER_DIR=%~dp0"
if "%CHUNKBLAZER_DIR:~-1%"=="\" set "CHUNKBLAZER_DIR=%CHUNKBLAZER_DIR:~0,-1%"
set "RUNELITE_DIR=C:\runelite"
set "TOOLS_DIR=%CHUNKBLAZER_DIR%\tools"
set "MAVEN_DIR=%TOOLS_DIR%\maven"
set "MAVEN_VERSION=3.9.6"
set "MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"
set "PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%\src\main\java\net\runelite\client\plugins\chunkblazer"
set "PLUGIN_RESOURCES=%CHUNKBLAZER_DIR%\src\main\resources\net\runelite\client\plugins\chunkblazer"

:: Check for Java
echo Checking for Java...
java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Java is not installed or not in PATH.
    echo Please install Java JDK 11 or higher from https://adoptium.net/
    pause
    exit /b 1
)
echo Java found!
echo.

:: Check for Git
echo Checking for Git...
git --version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Git is not installed or not in PATH.
    echo Please install Git from https://git-scm.com/download/win
    pause
    exit /b 1
)
echo Git found!
echo.

:: Setup Maven
echo [1/5] Setting up Maven...
where mvn >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo Maven found in PATH, using system Maven.
    set "MVN_CMD=mvn"
    goto :maven_done
)

if exist "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" (
    echo Using local Maven installation.
    set "MVN_CMD=%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
    goto :maven_done
)

echo Maven not found. Downloading Maven %MAVEN_VERSION%...

:: Create directories using PowerShell (more reliable across Windows versions)
echo Creating directories...
powershell -Command "New-Item -ItemType Directory -Force -Path '%MAVEN_DIR%' | Out-Null" 2>nul
if not exist "%MAVEN_DIR%" (
    :: Fallback to md command
    md "%MAVEN_DIR%" 2>nul
)
if not exist "%MAVEN_DIR%" (
    echo ERROR: Failed to create directory: %MAVEN_DIR%
    echo Please create this directory manually and re-run the script.
    pause
    exit /b 1
)
echo Directory created: %MAVEN_DIR%

:: Download Maven using PowerShell with better error handling
echo Downloading from %MAVEN_URL%...
echo This may take a minute...
set "MAVEN_ZIP=%MAVEN_DIR%\maven.zip"

:: Try PowerShell Invoke-WebRequest first
powershell -ExecutionPolicy Bypass -Command "$ProgressPreference = 'SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; try { Invoke-WebRequest -Uri '%MAVEN_URL%' -OutFile '%MAVEN_ZIP%' -UseBasicParsing; exit 0 } catch { Write-Host $_.Exception.Message; exit 1 }"
if %ERRORLEVEL% NEQ 0 (
    echo PowerShell download failed, trying curl...
    :: Try curl as fallback (available on Windows 10+)
    curl -L -o "%MAVEN_ZIP%" "%MAVEN_URL%" 2>nul
    if %ERRORLEVEL% NEQ 0 (
        echo.
        echo ERROR: Failed to download Maven.
        echo Please check your internet connection and try again.
        echo.
        echo Alternative: Download Maven manually from:
        echo %MAVEN_URL%
        echo And extract to: %MAVEN_DIR%
        pause
        exit /b 1
    )
)

:: Verify download
if not exist "%MAVEN_ZIP%" (
    echo ERROR: Maven download file not found.
    pause
    exit /b 1
)
for %%A in ("%MAVEN_ZIP%") do set "ZIP_SIZE=%%~zA"
if "%ZIP_SIZE%"=="" set "ZIP_SIZE=0"
if %ZIP_SIZE% LSS 1000000 (
    echo ERROR: Downloaded file is too small ^(%ZIP_SIZE% bytes^). Download may have failed.
    del "%MAVEN_ZIP%" 2>nul
    pause
    exit /b 1
)
echo Download complete! ^(%ZIP_SIZE% bytes^)

:: Extract Maven using PowerShell
echo Extracting Maven...
powershell -ExecutionPolicy Bypass -Command "try { Expand-Archive -Path '%MAVEN_ZIP%' -DestinationPath '%MAVEN_DIR%' -Force; exit 0 } catch { Write-Host $_.Exception.Message; exit 1 }"
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to extract Maven.
    echo Please extract manually: %MAVEN_ZIP%
    echo To: %MAVEN_DIR%
    pause
    exit /b 1
)

:: Verify extraction
if not exist "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" (
    echo ERROR: Maven extraction verification failed.
    echo Expected file not found: %MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd
    pause
    exit /b 1
)

:: Clean up zip file
del "%MAVEN_ZIP%" 2>nul

echo Maven installed successfully!
set "MVN_CMD=%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"

:maven_done
echo.

:: Check if RuneLite already exists
if exist "%RUNELITE_DIR%" (
    echo [2/5] RuneLite directory already exists at %RUNELITE_DIR%
    echo Skipping clone...
) else (
    echo [2/5] Cloning RuneLite repository...
    git clone --depth 1 https://github.com/runelite/runelite.git "%RUNELITE_DIR%"
    if %ERRORLEVEL% NEQ 0 (
        echo ERROR: Failed to clone RuneLite
        pause
        exit /b 1
    )
)
echo.

:: Create plugin directories if they don't exist
echo [3/5] Creating plugin directories...
set "JAVA_PLUGINS_DIR=%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins"
set "RES_PLUGINS_DIR=%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins"

if not exist "%JAVA_PLUGINS_DIR%" (
    md "%JAVA_PLUGINS_DIR%" 2>nul
    if not exist "%JAVA_PLUGINS_DIR%" (
        powershell -Command "New-Item -ItemType Directory -Force -Path '%JAVA_PLUGINS_DIR%' | Out-Null"
    )
)
if not exist "%RES_PLUGINS_DIR%" (
    md "%RES_PLUGINS_DIR%" 2>nul
    if not exist "%RES_PLUGINS_DIR%" (
        powershell -Command "New-Item -ItemType Directory -Force -Path '%RES_PLUGINS_DIR%' | Out-Null"
    )
)
echo Plugin directories ready.
echo.

:: Create symlinks
echo [4/5] Creating symlinks to ChunkBlazer...

set "JAVA_LINK=%JAVA_PLUGINS_DIR%\chunkblazer"
set "RES_LINK=%RES_PLUGINS_DIR%\chunkblazer"

:: Remove existing symlinks/folders if they exist
if exist "%JAVA_LINK%" (
    rmdir "%JAVA_LINK%" 2>nul
    if exist "%JAVA_LINK%" rd /s /q "%JAVA_LINK%" 2>nul
)
if exist "%RES_LINK%" (
    rmdir "%RES_LINK%" 2>nul
    if exist "%RES_LINK%" rd /s /q "%RES_LINK%" 2>nul
)

:: Create new symlinks
mklink /D "%JAVA_LINK%" "%PLUGIN_JAVA_SRC%"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Failed to create Java symlink.
    echo.
    echo This usually means you need to run as Administrator.
    echo Right-click setup-chunkblazer.bat and select "Run as administrator"
    echo.
    pause
    exit /b 1
)

mklink /D "%RES_LINK%" "%PLUGIN_RESOURCES%"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Failed to create resources symlink.
    echo.
    echo This usually means you need to run as Administrator.
    echo Right-click setup-chunkblazer.bat and select "Run as administrator"
    echo.
    pause
    exit /b 1
)
echo Symlinks created successfully!
echo.

:: Build RuneLite
echo [5/5] Building RuneLite (this may take several minutes on first run)...
echo.
cd /d "%RUNELITE_DIR%"
call "%MVN_CMD%" install -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ========================================
    echo     BUILD FAILED
    echo ========================================
    echo.
    echo Check the output above for errors.
    echo Common issues:
    echo   - Java JDK not installed (need JDK, not just JRE)
    echo   - Network issues downloading dependencies
    echo   - Disk space issues
    echo.
    pause
    exit /b 1
)

echo.
echo ========================================
echo     Setup Complete!
echo ========================================
echo.
echo You can now use run-chunkblazer.bat to launch the dev client.
echo.
pause
