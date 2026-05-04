@echo off
title ChunkBlazer Dev Client
color 0A

echo ========================================
echo      ChunkBlazer Dev Client Launcher
echo ========================================
echo.

:: Pull latest ChunkBlazer updates
echo [1/3] Pulling latest ChunkBlazer updates...
cd /d C:\ChunkBlazer
git pull
if %ERRORLEVEL% NEQ 0 (
    echo WARNING: Git pull failed - continuing with local version
)
echo.

:: Build RuneLite with ChunkBlazer
echo [2/3] Building RuneLite with ChunkBlazer...
cd /d C:\runelite
call mvn install -DskipTests -pl runelite-client -am
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Build failed! Check the output above for errors.
    pause
    exit /b 1
)
echo.

:: Run the client
echo [3/3] Launching RuneLite Dev Client...
echo.
call mvn -pl runelite-client exec:java

pause
