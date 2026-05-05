@echo off
:: ============================================================
:: jagex-wrapper.bat
:: ------------------------------------------------------------
:: Target this file from the Jagex Launcher's "RuneLite path"
:: setting. The launcher will spawn it with the OAuth tokens
:: (JX_ACCESS_TOKEN / JX_REFRESH_TOKEN / JX_SESSION_ID etc.)
:: passed in as CLI arguments. We forward them via %* to the
:: dev jar, which - thanks to --insecure-write-credentials -
:: writes them to %USERPROFILE%\.runelite\credentials.properties
:: so future runs of run-chunkblazer.bat work directly.
::
:: This wrapper does NOT pull / build / sync. It assumes
:: setup-chunkblazer.bat (or run-chunkblazer.bat) has produced
:: the shaded jar at least once.
:: ============================================================

setlocal

set "RUNELITE_DIR=C:\runelite"
set "CLIENT_JAR="

:: Locate the most recent shaded jar
for %%f in ("%RUNELITE_DIR%\runelite-client\build\libs\client-*-shaded.jar") do (
    set "CLIENT_JAR=%%f"
)

if "%CLIENT_JAR%"=="" (
    echo ERROR: ChunkBlazer dev jar not found.
    echo Expected at: %RUNELITE_DIR%\runelite-client\build\libs\client-*-shaded.jar
    echo.
    echo Run setup-chunkblazer.bat first to build it.
    pause
    exit /b 1
)

java -ea -jar "%CLIENT_JAR%" --developer-mode --insecure-write-credentials %*
exit /b %ERRORLEVEL%
