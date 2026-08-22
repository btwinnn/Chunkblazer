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

## Data & server (paste into the PR)

ChunkBlazer talks to one first-party server (`api.chunkblazer.com`, HTTPS, no
third parties).

- **Player sync (the core reason for the server):** unlocked chunks, completed
  tasks, points, and game mode — keyed to a hashed RSN. Enables saves across
  devices, leaderboards, and server-side completion verification (anti-cheat).
- **Task catalog (read-only):** `GET /api/tasks` returns the task definitions —
  ~3 MB of JSON, too large to bundle, so fetched once and cached under
  `RUNELITE_DIR`, revalidated with ETag/304 (no re-download unless it changes).
- **Sounds (read-only):** completion jingles, content-addressed — ~60 MB of
  audio, likewise too large to ship, fetched on demand and disk-cached the same
  way.

Nothing else is sent, the RSN is hashed, and users see this in an in-plugin
data-use disclosure. Full detail in `PRIVACY.md`.

## Blockers — all cleared (2026-08-22)
1. **Shrink resources.** ✅ Done. The 83 WAVs (~60 MB) moved to the server as
   content-addressed µ-law via `AssetStore` (one seed jingle kept), then the task
   JSON (~3 MB) moved to the server too (blocker #2). **Plugin resources
   64 MB → ~1.5 MB** (seed jingle + PNGs + gzipped catalog seed). See
   `Chunkblazer-Server/docs/MEDIA-PIPELINE-PLAN.md`.
2. **Under the reviewer token limit.** ✅ Done. Task JSON is served from
   `GET /api/tasks` and cached client-side (see
   `Chunkblazer-Server/docs/TASK-CATALOG-MIGRATION-PLAN.md`). Comment-stripped
   `src/main/java` is ~129k tokens — under the ~200k cap. Tests live in `src/test`
   (not counted). Removing dead code + dev tools from `main` trimmed it further.

## Before submit — done
- ✅ Gradle wrapper committed (`gradlew` / `gradlew.bat` + `gradle/wrapper/*`,
  Gradle 8.8).
- ✅ `LICENSE` (BSD 2-Clause) for ChunkBlazer itself, alongside the
  `LICENSE-region-locker` attribution for borrowed code.
- ✅ Dev/cheat tools stripped from `main`, kept on the `dev` branch. A `pre-push`
  hook blocks dev-tool code from reaching `main`. Workflow: develop on `main`,
  `git checkout dev && git rebase main` to test with tools, submit from `main`.

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
