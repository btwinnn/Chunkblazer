@echo off
title ChunkBlazer First-Time Setup
color 0E

echo ========================================
echo     ChunkBlazer First-Time Setup
echo ========================================
echo.
echo This script will:
echo   1. Clone RuneLite source code
echo   2. Create symlinks to ChunkBlazer
echo   3. Build RuneLite
echo.
echo REQUIREMENTS:
echo   - Git installed and in PATH
echo   - Java 11+ installed (JDK, not just JRE)
echo   - Maven installed and in PATH
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
set PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%src\main\java\net\runelite\client\plugins\chunkblazer
set PLUGIN_RESOURCES=%CHUNKBLAZER_DIR%src\main\resources\net\runelite\client\plugins\chunkblazer

:: Check if RuneLite already exists
if exist "%RUNELITE_DIR%" (
    echo RuneLite directory already exists at %RUNELITE_DIR%
    echo Skipping clone...
) else (
    echo [1/4] Cloning RuneLite repository...
    git clone https://github.com/runelite/runelite.git "%RUNELITE_DIR%"
    if %ERRORLEVEL% NEQ 0 (
        echo ERROR: Failed to clone RuneLite
        pause
        exit /b 1
    )
)
echo.

:: Create plugin directories if they don't exist
echo [2/4] Creating plugin directories...
if not exist "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins" (
    mkdir "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins"
)
if not exist "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins" (
    mkdir "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins"
)
echo.

:: Create symlinks
echo [3/4] Creating symlinks to ChunkBlazer...

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
echo [4/4] Building RuneLite (this may take a few minutes)...
cd /d "%RUNELITE_DIR%"
call mvn install -DskipTests
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
