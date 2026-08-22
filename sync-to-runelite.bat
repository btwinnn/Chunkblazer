@echo off
setlocal enabledelayedexpansion
title Sync ChunkBlazer -> RuneLite
color 0A

:: ============================================================
:: sync-to-runelite.bat
:: ------------------------------------------------------------
:: Mirrors plugin sources from C:\Chunkblazer into the
:: runelite-client folder under C:\runelite. Use this after
:: editing in C:\Chunkblazer so the dev client picks up the
:: latest plugin code.
::
:: Pipeline:
::   [1/2] src\main\java\...      -> runelite\...\java\
::   [2/2] src\main\resources\... -> runelite\...\resources\
::
:: Mirrors (deletions propagate) by removing the destination
:: folders first, then xcopy /E /I /H /Y. No build is run -
:: launch via run-chunkblazer.bat or rebuild from your IDE.
::
:: NOTE (task catalog migration, 2026-08): task JSON no longer
:: lives in the plugin. Authoring + aggregation moved to the
:: SERVER repo (Chunkblazer-Server\task-authoring +
:: scripts\build-task-catalog.ps1), which writes internal\tasks\
:: data\ and regenerates the plugin's gzipped seed
:: (resources\...\tasks_catalog.json.gz). The plugin fetches the
:: rest from GET /api/tasks at runtime (see CatalogStore). So
:: there is no charter build or JSON-refresh step here anymore;
:: the seed just rides along in the [2/2] resources mirror.
:: ============================================================

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "CHUNKBLAZER_DIR=%SCRIPT_DIR%"
set "RUNELITE_DIR=C:\runelite"

set "PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%\src\main\java\com\chunkblazer"
set "PLUGIN_RES_SRC=%CHUNKBLAZER_DIR%\src\main\resources\com\chunkblazer"

set "JAVA_DEST=%RUNELITE_DIR%\runelite-client\src\main\java\com\chunkblazer"
set "RES_DEST=%RUNELITE_DIR%\runelite-client\src\main\resources\com\chunkblazer"

echo ========================================
echo    Sync ChunkBlazer -^> RuneLite
echo ========================================
echo.

if not exist "%PLUGIN_JAVA_SRC%" (
    echo ERROR: Source not found: %PLUGIN_JAVA_SRC%
    pause
    exit /b 1
)
if not exist "%RUNELITE_DIR%" (
    echo ERROR: %RUNELITE_DIR% missing. Run setup-chunkblazer.bat first.
    pause
    exit /b 1
)

:: ---- Java sources -------------------------------------------------------
echo [1/2] Mirroring Java sources...
echo   From: %PLUGIN_JAVA_SRC%
echo   To:   %JAVA_DEST%
:: Purge legacy net.* copies from before the com.chunkblazer rename, or the old
:: copy is core-discovered as a duplicate "ChunkBlazer" in the plugin list.
rd /s /q "%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins\chunkblazer" 2>nul
rd /s /q "%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\chunkblazer" 2>nul
if exist "%JAVA_DEST%" rd /s /q "%JAVA_DEST%"
xcopy "%PLUGIN_JAVA_SRC%" "%JAVA_DEST%" /E /I /H /Y >nul
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Java source copy failed.
    pause
    exit /b 1
)
echo   Done.
echo.

:: ---- Resources ----------------------------------------------------------
echo [2/2] Mirroring resources...
echo   From: %PLUGIN_RES_SRC%
echo   To:   %RES_DEST%
if exist "%RES_DEST%" rd /s /q "%RES_DEST%"
if exist "%PLUGIN_RES_SRC%" (
    xcopy "%PLUGIN_RES_SRC%" "%RES_DEST%" /E /I /H /Y >nul
    if !ERRORLEVEL! NEQ 0 (
        echo ERROR: Resource copy failed.
        pause
        exit /b 1
    )
    echo   Done.
) else (
    echo   No resources folder in source ^(skipping^).
)
echo.

echo ========================================
echo    Sync complete.
echo ========================================
echo.
echo Next: relaunch via run-chunkblazer.bat or rebuild in IntelliJ.
echo.

pause
exit /b 0
