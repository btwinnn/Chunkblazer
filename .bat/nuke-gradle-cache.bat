@echo off
setlocal enabledelayedexpansion
title Nuke Gradle Cache
color 0C

:: ============================================================
:: nuke-gradle-cache.bat
:: ------------------------------------------------------------
:: One-time fix for stale Gradle output in C:\runelite. Use when:
::   - run-chunkblazer.bat is producing a shaded jar that doesn't
::     reflect recent plugin/JSON changes
::   - the dev client behaves differently from your IntelliJ run
::     of the same source tree
::   - chunk loading silently uses old resources
::
:: This script:
::   1. Stops the Gradle daemon (releases file locks)
::   2. Deletes C:\runelite\.gradle (project incremental build cache)
::   3. Deletes C:\runelite\runelite-client\build (the old shaded jar
::      and processed resources)
::
:: Safe to re-run. Does not touch source files, the user-level
:: ~/.gradle dependency cache, or anything in C:\Chunkblazer.
::
:: After running, the next run-chunkblazer.bat (or update-runelite.bat)
:: will do a full rebuild from current sources.
:: ============================================================

set "RUNELITE_DIR=C:\runelite"
set "GRADLE_PROJECT_CACHE=%RUNELITE_DIR%\.gradle"
set "CLIENT_BUILD_DIR=%RUNELITE_DIR%\runelite-client\build"

echo ========================================
echo    Nuke Gradle Cache
echo ========================================
echo.

if not exist "%RUNELITE_DIR%\gradlew.bat" (
    echo ERROR: %RUNELITE_DIR% missing or not a Gradle project.
    echo Run setup-chunkblazer.bat first.
    pause
    exit /b 1
)

echo [1/3] Stopping Gradle daemon...
call "%RUNELITE_DIR%\gradlew.bat" --stop >nul 2>&1
echo   Daemon stopped.
echo.

echo [2/3] Deleting %GRADLE_PROJECT_CACHE% ...
if exist "%GRADLE_PROJECT_CACHE%" (
    rd /s /q "%GRADLE_PROJECT_CACHE%" 2>nul
    if exist "%GRADLE_PROJECT_CACHE%" (
        echo   ERROR: Could not delete %GRADLE_PROJECT_CACHE%
        echo   Close any open Gradle/IntelliJ processes against C:\runelite and retry.
        pause
        exit /b 1
    )
    echo   Deleted.
) else (
    echo   Not present (already clean).
)
echo.

echo [3/3] Deleting %CLIENT_BUILD_DIR% ...
if exist "%CLIENT_BUILD_DIR%" (
    rd /s /q "%CLIENT_BUILD_DIR%" 2>nul
    if exist "%CLIENT_BUILD_DIR%" (
        echo   ERROR: Could not delete %CLIENT_BUILD_DIR%
        echo   Close any open Gradle/IntelliJ processes against C:\runelite and retry.
        pause
        exit /b 1
    )
    echo   Deleted.
) else (
    echo   Not present (already clean).
)
echo.

echo ========================================
echo    Cache nuked.
echo ========================================
echo.
echo Next: run run-chunkblazer.bat (it will rebuild the shaded jar
echo from current sources -- expect the build step to take ~1 minute).
echo.

pause
exit /b 0
