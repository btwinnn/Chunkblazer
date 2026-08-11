@echo off
setlocal enabledelayedexpansion
title Sync ChunkBlazer -> RuneLite
color 0A

:: ============================================================
:: sync-to-runelite.bat
:: ------------------------------------------------------------
:: Mirrors plugin sources from C:\Chunkblazer into the
:: runelite-client folder under C:\runelite. Use this after
:: editing/committing in C:\Chunkblazer so the dev client
:: picks up the latest plugin code.
::
:: Pipeline:
::   [1/3] Tasks_JSON\<sub>\*.json -> src\main\resources\...\chunkblazer\
::         For each file in TASK_JSONS, walks JSON_SEARCH_DIRS
::         (in priority order) and copies the first match found.
::         Current canonical homes:
::           Misthalin/Asgarnia/Kandarin/Varlamore/Zeah_Tasks.json
::                                   -> All_Areas_Task_Folder\
::           Free_Chunks.json        -> Free_Chunks_Task_Folder\
::         If a JSON moves to a new subfolder, just add it to
::         JSON_SEARCH_DIRS — no need to rewrite the loop.
::   [2/3] src\main\java\...   -> runelite\runelite-client\...\java\
::   [3/3] src\main\resources\... -> runelite\runelite-client\...\resources\
::
:: Mirrors (deletions propagate) by removing the destination
:: folders first, then xcopy /E /I /H /Y. No build is run -
:: launch via run-chunkblazer.bat or rebuild from your IDE
:: afterwards.
::
:: This is the lightweight counterpart to setup-chunkblazer.bat
:: (which also reclones runelite from scratch).
:: ============================================================

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "CHUNKBLAZER_DIR=%SCRIPT_DIR%"
set "RUNELITE_DIR=C:\runelite"

set "TASKS_JSON_SRC=%CHUNKBLAZER_DIR%\Tasks_JSON"
set "PLUGIN_JAVA_SRC=%CHUNKBLAZER_DIR%\src\main\java\com\chunkblazer"
set "PLUGIN_RES_SRC=%CHUNKBLAZER_DIR%\src\main\resources\com\chunkblazer"

set "JAVA_DEST=%RUNELITE_DIR%\runelite-client\src\main\java\com\chunkblazer"
set "RES_DEST=%RUNELITE_DIR%\runelite-client\src\main\resources\com\chunkblazer"

:: Files the plugin actually loads at runtime (must match
:: ChunkBlazerPlugin.TASK_JSON_FILES). If you add a new task
:: JSON to TASK_JSON_FILES, add it here too.
:: The GLOBAL task files are the exception: Quest_Tasks.json and
:: Progression_Tasks.json are NOT in TASK_JSON_FILES (they share their own
:: loader, loadGlobalTasks(), because they are chunk-independent) but they
:: still have to be staged from the authoring tree to resources like any other
:: data file, or edits there never reach the client. Omitting one is silent:
:: the file simply isn't on the classpath, loadGlobalTaskFile logs a miss, and
:: that whole tier stops existing in-game while everything else looks fine.
:: Free_Chunks.json is the same kind of exception: not in TASK_JSON_FILES (it
:: has its own loader, loadFreeChunks()), but it MUST be staged here. It was
:: omitted from this list until 2026-07-30, so three commits' worth of authoring
:: (236 of 325 free chunks) sat in Tasks_JSON and never reached the client while
:: the shipped copy stayed frozen at its 2026-07-04 state.
set TASK_JSONS=Misthalin_Tasks.json Asgarnia_Tasks.json Kandarin_Tasks.json Karamja_Tasks.json Desert_Tasks.json Varlamore_Tasks.json Zeah_Tasks.json Fremennik_Tasks.json Tirannwn_Tasks.json Morytania_Tasks.json Wilderness_Tasks.json Quest_Tasks.json Progression_Tasks.json Free_Chunks.json

:: Subfolders under Tasks_JSON\ that may contain TASK_JSONS files.
:: Searched in priority order — first match wins. The trailing "."
:: means "Tasks_JSON top-level itself" (legacy path before the
:: per-area subfolder reorg, kept for back-compat).
set JSON_SEARCH_DIRS=All_Areas_Task_Folder Quest_Tasks_Folder Free_Chunks_Task_Folder .

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

:: ---- Regenerate charter aggregate from per-port folder ------------------
:: NOTE: this rebuilds Charter_Tasks.json ONLY. build-charter-tasks.ps1
:: deliberately does not touch Free_Chunks.json (charter ports are opened by
:: seeding, not by the free list — see its header). Free_Chunks.json is staged
:: in [1/3] like any other authored data file.
echo [0/3] Rebuilding Charter_Tasks.json from Charter_Tasks_Folder...
powershell -NoProfile -ExecutionPolicy Bypass -File "%CHUNKBLAZER_DIR%\Tasks_JSON\build-charter-tasks.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: charter aggregation failed.
    pause
    exit /b 1
)
echo.

:: ---- Refresh JSON resources from Tasks_JSON -----------------------------
echo [1/3] Refreshing task JSONs from Tasks_JSON subfolders...
echo   From: %TASKS_JSON_SRC%
echo   To:   %PLUGIN_RES_SRC%
if not exist "%TASKS_JSON_SRC%" (
    echo   WARNING: %TASKS_JSON_SRC% not found - skipping JSON refresh.
) else (
    if not exist "%PLUGIN_RES_SRC%" (
        echo ERROR: Resources folder missing: %PLUGIN_RES_SRC%
        pause
        exit /b 1
    )
    set MISSING_COUNT=0
    for %%F in (%TASK_JSONS%) do (
        set "FOUND_AT="
        set "FOUND_DIR="
        for %%D in (%JSON_SEARCH_DIRS%) do (
            if not defined FOUND_AT (
                if exist "%TASKS_JSON_SRC%\%%D\%%F" (
                    set "FOUND_AT=%TASKS_JSON_SRC%\%%D\%%F"
                    set "FOUND_DIR=%%D"
                )
            )
        )
        if defined FOUND_AT (
            copy /Y "!FOUND_AT!" "%PLUGIN_RES_SRC%\%%F" >nul
            if !ERRORLEVEL! NEQ 0 (
                echo   ERROR: Copy failed for %%F
                pause
                exit /b 1
            )
            echo   Copied %%F ^(from !FOUND_DIR!^)
        ) else (
            echo   WARNING: %%F not found in any of [%JSON_SEARCH_DIRS%] - resources copy left untouched.
            set /a MISSING_COUNT+=1
        )
    )
)
echo.

:: ---- Java sources -------------------------------------------------------
echo [2/3] Mirroring Java sources...
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
echo [3/3] Mirroring resources...
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
