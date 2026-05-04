@echo off
title ChunkBlazer Dev Client
color 0A

echo ========================================
echo      ChunkBlazer Dev Client Launcher
echo ========================================
echo.

:: Set paths
set CHUNKBLAZER_DIR=%~dp0
set RUNELITE_DIR=C:\runelite
set TOOLS_DIR=%CHUNKBLAZER_DIR%tools
set MAVEN_DIR=%TOOLS_DIR%\maven
set MAVEN_VERSION=3.9.6

:: Determine which Maven to use
where mvn >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    set "MVN_CMD=mvn"
) else (
    if exist "%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd" (
        set "MVN_CMD=%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
    ) else (
        echo ERROR: Maven not found. Please run setup-chunkblazer.bat first.
        pause
        exit /b 1
    )
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
cd /d "%RUNELITE_DIR%"
call "%MVN_CMD%" install -DskipTests -pl runelite-client -am
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
call "%MVN_CMD%" -pl runelite-client exec:java

pause