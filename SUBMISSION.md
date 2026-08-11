# ChunkBlazer — Plugin Hub submission checklist

Only open items are listed. Dependencies are already a pass: ChunkBlazer ships
**zero new runtime dependencies** (OkHttp + Gson come transitively from
`runelite-client`), so there is nothing for a maintainer to hash-verify.

**Done:** data disclosure (`ChunkBlazerConfig`) · Gradle scaffold · package rename
`net.runelite.client.plugins.chunkblazer` → `com.chunkblazer` · checkstyle wired
(`config/checkstyle/`) and passing 0 violations · removed mockup `.mp4` · `build/`
untracked. When committing, `git add config/`.

---

## 1. 🔴 Shrink the shipped resources (blocker)
`src/main/resources/com/chunkblazer/` is **~64 MB, ~60 MB of it 83 WAV files**.
Typical hub plugins are <1 MB; the jar is downloaded by every user on every
update, so this will draw a rejection as-is. Options: convert WAV→OGG, downsample
to mono/22 kHz, or curate to a smaller set. **Product call — decide before submit.**

## 2. Get the plugin under the reviewer token limit
The Hub AI reviewer has a hard ~200k-token limit; the bundled task JSON blows
past it on its own. Tracked separately in the server repo:
`Chunkblazer-Server/docs/TASK-CATALOG-MIGRATION-PLAN.md` (move `*_Tasks.json` to
the server, fetch + disk-cache client-side). Must be resolved before submit.

## 3. Generate the Gradle wrapper
Hub CI builds with the wrapper (needs a local Gradle install):
```
gradle wrapper --gradle-version 8.10
```
Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`.

## 4. Reviewer-polish (likely review comments)
- `ChunkBlazerApiClient`: inject RuneLite's shared `OkHttpClient` (`@Inject`, then
  `.newBuilder()` for timeouts) instead of `new OkHttpClient.Builder()`.
- `ChunkBlazerPanel`: use `net.runelite.client.util.LinkBrowser.browse(url)` for
  the Discord link instead of raw `java.awt.Desktop`.

## 5. Verify in-game after the package rename
Not run in-game since the move to `com.chunkblazer`. Discovery is by
`@PluginDescriptor` (package-agnostic) so it should load — if it doesn't appear,
that's the first suspect. Rollback = `git revert` of the rename commit.
(`@ConfigGroup("chunkblazer")` was left unchanged on purpose — it's the settings
key, not the package; renaming it would orphan every user's saved config.)

## 6. Submit
1. Fork `runelite/plugin-hub`.
2. Add a file `plugins/chunkblazer` (no extension):
   ```
   repository=https://github.com/btwinnn/Chunkblazer.git
   commit=<full 40-char commit hash to build>
   ```
3. Open a PR; respond to review comments within a day (stalled PRs are usually
   waiting on the author).

_Repo hygiene: public repo, root `LICENSE` present, keep the BSD-2
`LICENSE-region-locker` attribution for the GPU greyscale code. Optional:
`icon.png` at repo root for the Hub listing._
