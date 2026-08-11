@echo off
setlocal enabledelayedexpansion
title ChunkBlazer Dev Client
color 0A

:: Set up logging
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "LOG_FILE=%SCRIPT_DIR%\run-log.txt"

:: Clear previous log and start fresh
echo ========================================> "%LOG_FILE%"
echo ChunkBlazer Run Log>> "%LOG_FILE%"
echo Started: %DATE% %TIME%>> "%LOG_FILE%"
echo ========================================>> "%LOG_FILE%"
echo.>> "%LOG_FILE%"

call :log "========================================"
call :log "     ChunkBlazer Dev Client Launcher"
call :log "========================================"
call :log ""

:: Set paths
set "CHUNKBLAZER_DIR=%SCRIPT_DIR%"
set "RUNELITE_DIR=C:\runelite"
set "PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%\src\main\java\com\chunkblazer"
set "PLUGIN_RESOURCES=%CHUNKBLAZER_DIR%\src\main\resources\com\chunkblazer"

call :log "Configuration:"
call :log "  CHUNKBLAZER_DIR: %CHUNKBLAZER_DIR%"
call :log "  RUNELITE_DIR: %RUNELITE_DIR%"
call :log "  LOG_FILE: %LOG_FILE%"
call :log ""

:: Check RuneLite exists
if not exist "%RUNELITE_DIR%\gradlew.bat" (
    call :log "ERROR: RuneLite not found at %RUNELITE_DIR%"
    call :log "Please run setup-chunkblazer.bat first."
    call :logfail
    echo ERROR: RuneLite not found. Please run setup-chunkblazer.bat first.
    pause
    exit /b 1
)

:: Pull latest RuneLite updates (prevents j5connect and other version mismatches)
call :log "[1/5] Pulling latest RuneLite updates..."
cd /d "%RUNELITE_DIR%"
git pull origin master >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log "WARNING: RuneLite git pull failed - continuing with local version"
)
call :log ""

:: Pull latest ChunkBlazer updates
call :log "[2/5] Pulling latest ChunkBlazer updates..."
cd /d "%CHUNKBLAZER_DIR%"
git pull >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log "WARNING: ChunkBlazer git pull failed - continuing with local version"
)
call :log ""

:: Copy latest plugin files into RuneLite
call :log "[3/5] Syncing ChunkBlazer plugin files..."
set "JAVA_DEST=%RUNELITE_DIR%\runelite-client\src\main\java\com\chunkblazer"
set "RES_DEST=%RUNELITE_DIR%\runelite-client\src\main\resources\com\chunkblazer"

call :log "  Copying Java sources..."
call :log "    From: %PLUGIN_JAVA_SRC%"
call :log "    To: %JAVA_DEST%"

:: Remove and re-copy Java sources
if exist "%JAVA_DEST%" rd /s /q "%JAVA_DEST%" 2>nul
xcopy "%PLUGIN_JAVA_SRC%" "%JAVA_DEST%" /E /I /H /Y >> "%LOG_FILE%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    call :log "ERROR: Failed to sync Java sources."
    call :logfail
    echo ERROR: Failed to sync Java sources.
    pause
    exit /b 1
)
call :log "  Java sources synced."

:: Remove and re-copy resources (if they exist)
call :log "  Copying resources..."
if exist "%RES_DEST%" rd /s /q "%RES_DEST%" 2>nul
if exist "%PLUGIN_RESOURCES%" (
    xcopy "%PLUGIN_RESOURCES%" "%RES_DEST%" /E /I /H /Y >> "%LOG_FILE%" 2>&1
    call :log "  Resources synced."
) else (
    call :log "  No resources folder found (skipping)."
)
call :log "Plugin files synced."
call :log ""

:: Build RuneLite with ChunkBlazer
call :log "[4/5] Building RuneLite with ChunkBlazer..."
call :log "  This may take a minute..."
echo Building... (check %LOG_FILE% for progress)
cd /d "%RUNELITE_DIR%"
call "%RUNELITE_DIR%\gradlew.bat" :client:build -x test -x pmdMain -x checkstyleMain >> "%LOG_FILE%" 2>&1
set "BUILD_RESULT=%ERRORLEVEL%"

if %BUILD_RESULT% NEQ 0 (
    call :log ""
    call :log "========================================"
    call :log "    BUILD FAILED"
    call :log "========================================"
    call :log ""
    call :log "Check the full log for errors: %LOG_FILE%"
    call :logfail
    echo.
    echo BUILD FAILED - Check log file: %LOG_FILE%
    pause
    exit /b 1
)
call :log "Build complete!"
call :log ""

:: Find the shaded jar
call :log "[5/5] Launching RuneLite Dev Client..."
set "CLIENT_JAR="
for %%f in ("%RUNELITE_DIR%\runelite-client\build\libs\client-*-shaded.jar") do (
    set "CLIENT_JAR=%%f"
)

if "%CLIENT_JAR%"=="" (
    call :log "ERROR: Could not find client shaded jar."
    call :log "Expected in: %RUNELITE_DIR%\runelite-client\build\libs\"
    call :logfail
    echo ERROR: Could not find client shaded jar.
    pause
    exit /b 1
)

call :log "Starting: %CLIENT_JAR%"
call :log ""
call :log "========================================"
call :log "    Launching Client"
call :log "========================================"
call :log "Finished: %DATE% %TIME%"

:: Set up per-session runtime log (captures plugin/game output, not just build steps)
set "SESSION_LOG_DIR=%CHUNKBLAZER_DIR%\session_logs"
if not exist "%SESSION_LOG_DIR%" mkdir "%SESSION_LOG_DIR%"

:: Sortable timestamp for filename (locale-independent via wmic)
for /f "tokens=2 delims==" %%a in ('wmic os get localdatetime /value 2^>nul ^| find "="') do set "DT=%%a"
set "TS=%DT:~0,4%-%DT:~4,2%-%DT:~6,2%_%DT:~8,2%-%DT:~10,2%-%DT:~12,2%"
set "SESSION_LOG=%SESSION_LOG_DIR%\session_%TS%.txt"

:: Prune: keep newest 5 session logs, delete the rest
for /f "skip=5 delims=" %%f in ('dir /b /o-d "%SESSION_LOG_DIR%\session_*.txt" 2^>nul') do del "%SESSION_LOG_DIR%\%%f"

call :log "Session log: %SESSION_LOG%"
echo Starting: %CLIENT_JAR%
echo Session output saving to: %SESSION_LOG%
echo.

:: Tee stdout+stderr to the session log via PowerShell so the terminal still shows live output.
:: --debug flips the root logger to DEBUG so chunkblazer module log.debug() calls actually appear.
:: Paths are passed via env vars to avoid cmd/PowerShell quoting issues with spaces.
set "JAR=%CLIENT_JAR%"
set "OUT=%SESSION_LOG%"
powershell -NoProfile -Command "& { java -ea -jar $env:JAR --developer-mode --debug --insecure-write-credentials 2>&1 | Tee-Object -FilePath $env:OUT }"

echo.
echo Session log saved to: %SESSION_LOG%
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
echo RUN FAILED: %DATE% %TIME% >> "%LOG_FILE%"
echo ======================================== >> "%LOG_FILE%"
goto :eof
