# ChunkBlazer — Plugin Hub submission

## Done
Package renamed to `com.chunkblazer` · checkstyle passing 0 · data disclosure in
`ChunkBlazerConfig` · Gradle scaffold · zero new runtime deps (OkHttp/Gson come
from `runelite-client`). When committing: `git add config/`.

**Compliance pass 2026-08-16** (scanned for Hub-banned patterns, all fixed):
- `ChunkBlazerApiClient` now derives from the **injected** `OkHttpClient`
  (`okHttpClient.newBuilder()`), not `new`.
- `ChunkBlazerPanel` link opening uses `LinkBrowser.browse(url)` /
  `LinkBrowser.open(path)` — removed `java.awt.Desktop` and the
  `Runtime.getRuntime().exec(...)` Notepad++ launch (external process = hard ban).
- Removed the dev var-dumper (`dumpPrayerVars`/`dumpMagicVars`/`collectVarbits`/
  `collectVarPlayers` + their two panel buttons): it used **reflection** into
  `net.runelite.api.VarPlayer`/`Varbits` and wrote to a hardcoded
  `C:\Chunkblazer\VarBit_VarPlayer.txt` — both rejected by review.
- Verified: 0 checkstyle violations, compiles, no `new OkHttpClient` / reflection
  / `exec` / `Desktop` / hardcoded absolute paths anywhere in `src/main`.
- The media pipeline (`AssetStore` etc.) was written compliant from the start:
  injected OkHttp, file I/O under `RUNELITE_DIR` only, daemon warm thread shut
  down on `shutDown()`, fetches data not code, behind `apiEnabled`.

## Blockers (must fix before submit)
1. **Shrink resources.** ✅ Largely done (2026-08-16). Was ~64 MB (~60 MB of it 83
   WAVs). Runtime fetch verified in-game, then the bundled `*.wav` were deleted
   down to a single seed jingle (`Misthalin_Sounds/Quest_Complete_1.wav`); all
   other jingles are fetched from the server as content-addressed µ-law via
   `AssetStore` (see `Chunkblazer-Server/docs/MEDIA-PIPELINE-PLAN.md`). **Plugin
   resources 64 MB → 4.4 MB.** The remaining 4.4 MB is the seed + PNGs (0.6 MB) +
   task JSON (3.1 MB); the task JSON is removed by blocker #2 below.
2. **Under the reviewer token limit.** Task JSON alone exceeds the ~200k-token
   cap. Fix = move `*_Tasks.json` to the server (see
   `Chunkblazer-Server/docs/TASK-CATALOG-MIGRATION-PLAN.md`).

## Before submit
- Generate the Gradle wrapper: `gradle wrapper --gradle-version 8.10`, then commit
  `gradlew`, `gradlew.bat`, `gradle/wrapper/*`. **(still open)**

## Submit
1. Fork `runelite/plugin-hub`.
2. Add file `plugins/chunkblazer` (no extension):
   ```
   repository=https://github.com/btwinnn/Chunkblazer.git
   commit=<full 40-char commit hash>
   ```
3. Open a PR; reply to review comments within a day.

---

**Dev run note (not a submission step):** RuneLite only auto-loads plugins under
`net.runelite.client.plugins`, so `com.chunkblazer` needs `DevLauncher` (via
`ExternalPluginManager.loadBuiltin`). `run-chunkblazer.bat` handles it; in IntelliJ
set the Main class to `com.chunkblazer.DevLauncher`. The Hub loads it fine either
way. Don't touch `@ConfigGroup("chunkblazer")` — it's the settings key, not the
package.
