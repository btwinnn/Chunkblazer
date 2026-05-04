@echo off
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

:: Set paths
set CHUNKBLAZER_DIR=%~dp0
set RUNELITE_DIR=C:\runelite
set TOOLS_DIR=%CHUNKBLAZER_DIR%tools
set MAVEN_DIR=%TOOLS_DIR%\maven
set MAVEN_VERSION=3.9.6
set MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip
set PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%src\main\java\net\runelite\client\plugins\chunkblazer
set PLUGIN_RESOURCES=%CHUNKBLAZER_DIR%src\main\resources\net\runelite\client\plugins\chunkblazer

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
) else (
    if exist "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" (
        echo Using local Maven installation.
        set "MVN_CMD=%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
    ) else (
        echo Maven not found. Downloading Maven %MAVEN_VERSION%...

        :: Create tools directory
        if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%"
        if not exist "%MAVEN_DIR%" mkdir "%MAVEN_DIR%"

        :: Download Maven using PowerShell
        echo Downloading from %MAVEN_URL%...
        powershell -Command "& {[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%MAVEN_URL%' -OutFile '%MAVEN_DIR%\maven.zip'}"
        if %ERRORLEVEL% NEQ 0 (
            echo ERROR: Failed to download Maven.
            pause
            exit /b 1
        )

        :: Extract Maven using PowerShell
        echo Extracting Maven...
        powershell -Command "& {Expand-Archive -Path '%MAVEN_DIR%\maven.zip' -DestinationPath '%MAVEN_DIR%' -Force}"
        if %ERRORLEVEL% NEQ 0 (
            echo ERROR: Failed to extract Maven.
            pause
            exit /b 1
        )

        :: Clean up zip file
        del "%MAVEN_DIR%\maven.zip"

        echo Maven installed successfully!
        set "MVN_CMD=%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
    )
)
echo.

:: Check if RuneLite already exists
if exist "%RUNELITE_DIR%" (
    echo [2/5] RuneLite directory already exists at %RUNELITE_DIR%
    echo Skipping clone...
) else (
    echo [2/5] Cloning RuneLite repository...
    git clone https://github.com/runelite/runelite.git "%RUNELITE_DIR%"
    if %ERRORLEVEL% NEQ 0 (
        echo ERROR: Failed to clone RuneLite
        pause
        exit /b 1
    )
)
echo.

:: Create plugin directories if they don't exist
echo [3/5] Creating plugin directories...
if not exist "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins" (
    mkdir "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins"
)
if not exist "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins" (
    mkdir "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins"
)
echo.

:: Create symlinks
echo [4/5] Creating symlinks to ChunkBlazer...

:: Remove existing symlinks/folders if they exist
if exist "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins\chunkblazer" (
    rmdir "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins\chunkblazer" 2>nul
    del "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins\chunkblazer" 2>nul
)
if exist "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\chunkblazer" (
    rmdir "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\chunkblazer" 2>nul
    del "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\chunkblazer" 2>nul
)

:: Create new symlinks
mklink /D "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins\chunkblazer" "%PLUGIN_JAVA_SRC%"
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to create Java symlink. Try running as Administrator.
    pause
    exit /b 1
)

mklink /D "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\chunkblazer" "%PLUGIN_RESOURCES%"
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to create resources symlink. Try running as Administrator.
    pause
    exit /b 1
)
echo Symlinks created successfully!
echo.

:: Build RuneLite
echo [5/5] Building RuneLite (this may take a few minutes)...
cd /d "%RUNELITE_DIR%"
call "%MVN_CMD%" install -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Build failed! Check the output above for errors.
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