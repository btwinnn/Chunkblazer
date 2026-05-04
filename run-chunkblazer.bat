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
set "PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%\src\main\java\net\runelite\client\plugins\chunkblazer"
set "PLUGIN_RESOURCES=%CHUNKBLAZER_DIR%\src\main\resources\net\runelite\client\plugins\chunkblazer"

:: Check RuneLite exists
if not exist "%RUNELITE_DIR%\gradlew.bat" (
    echo ERROR: RuneLite not found at %RUNELITE_DIR%
    echo Please run setup-chunkblazer.bat first.
    pause
    exit /b 1
)

:: Pull latest ChunkBlazer updates
echo [1/4] Pulling latest ChunkBlazer updates...
cd /d "%CHUNKBLAZER_DIR%"
git pull
if %ERRORLEVEL% NEQ 0 (
    echo WARNING: Git pull failed - continuing with local version
)
echo.

:: Copy latest plugin files into RuneLite
echo [2/4] Syncing ChunkBlazer plugin files...
set "JAVA_DEST=%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins\chunkblazer"
set "RES_DEST=%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\chunkblazer"

:: Remove and re-copy Java sources
if exist "%JAVA_DEST%" rd /s /q "%JAVA_DEST%" 2>nul
xcopy "%PLUGIN_JAVA_SRC%" "%JAVA_DEST%" /E /I /H /Y >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to sync Java sources.
    pause
    exit /b 1
)

:: Remove and re-copy resources (if they exist)
if exist "%RES_DEST%" rd /s /q "%RES_DEST%" 2>nul
if exist "%PLUGIN_RESOURCES%" (
    xcopy "%PLUGIN_RESOURCES%" "%RES_DEST%" /E /I /H /Y >nul 2>&1
)
echo Plugin files synced.
echo.

:: Build RuneLite with ChunkBlazer
echo [3/4] Building RuneLite with ChunkBlazer...
echo      (This may take a minute...)
cd /d "%RUNELITE_DIR%"
call "%RUNELITE_DIR%\gradlew.bat" :client:build -x test
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Build failed! Check the output above for errors.
    pause
    exit /b 1
)
echo.

:: Find the shaded jar
echo [4/4] Launching RuneLite Dev Client...
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
