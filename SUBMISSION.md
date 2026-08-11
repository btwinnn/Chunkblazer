# ChunkBlazer — Plugin Hub submission

## Done
Package renamed to `com.chunkblazer` · checkstyle passing 0 · data disclosure in
`ChunkBlazerConfig` · Gradle scaffold · zero new runtime deps (OkHttp/Gson come
from `runelite-client`). When committing: `git add config/`.

## Blockers (must fix before submit)
1. **Shrink resources.** ~64 MB, ~60 MB of it 83 WAV files. Hub plugins are
   normally <1 MB. Convert WAV→OGG / downsample / curate.
2. **Under the reviewer token limit.** Task JSON alone exceeds the ~200k-token
   cap. Fix = move `*_Tasks.json` to the server (see
   `Chunkblazer-Server/docs/TASK-CATALOG-MIGRATION-PLAN.md`).

## Before submit
- Generate the Gradle wrapper: `gradle wrapper --gradle-version 8.10`, then commit
  `gradlew`, `gradlew.bat`, `gradle/wrapper/*`.
- `ChunkBlazerApiClient`: use RuneLite's injected `OkHttpClient`, not `new`.
- `ChunkBlazerPanel`: use `LinkBrowser.browse(url)` for the Discord link.

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
