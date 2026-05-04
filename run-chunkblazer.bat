@echo off
setlocal enabledelayedexpansion
title ChunkBlazer Dev Client
color 0A

echo ========================================
echo      ChunkBlazer Dev Client Launcher
echo ========================================
echo.

:: Set paths
set "CHUNKBLAZER_DIR=%~dp0"
if "%CHUNKBLAZER_DIR:~-1%"=="\" set "CHUNKBLAZER_DIR=%CHUNKBLAZER_DIR:~0,-1%"
set "RUNELITE_DIR=C:\runelite"

:: Check RuneLite exists
if not exist "%RUNELITE_DIR%\gradlew.bat" (
    echo ERROR: RuneLite not found at %RUNELITE_DIR%
    echo Please run setup-chunkblazer.bat first.
    pause
    exit /b 1
)

:: Pull latest ChunkBlazer updates
echo [1/3] Pulling latest ChunkBlazer updates...
cd /d "%CHUNKBLAZER_DIR%"
git pull
if %ERRORLEVEL% NEQ 0 (
    echo WARNING: Git pull failed - continuing with local version
)
echo.

:: Build RuneLite with ChunkBlazer
echo [2/3] Building RuneLite with ChunkBlazer...
echo      (This may take a minute...)
cd /d "%RUNELITE_DIR%"
call "%RUNELITE_DIR%\gradlew.bat" :runelite-client:build -x test
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Build failed! Check the output above for errors.
    pause
    exit /b 1
)
echo.

:: Find the shaded jar
echo [3/3] Launching RuneLite Dev Client...
set "CLIENT_JAR="
for %%f in ("%RUNELITE_DIR%\runelite-client\build\libs\client-*-shaded.jar") do (
    set "CLIENT_JAR=%%f"
)

if "%CLIENT_JAR%"=="" (
    echo ERROR: Could not find client shaded jar.
    echo Expected in: %RUNELITE_DIR%\runelite-client\build\libs\
    pause
    exit /b 1
)

echo Starting: %CLIENT_JAR%
echo.
java -jar "%CLIENT_JAR%" --developer-mode

pause
