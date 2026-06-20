# ChunkBlazer — Plugin Hub submission checklist

The hard gate (dependencies) is already a pass: ChunkBlazer ships **zero new
runtime dependencies** — OkHttp and Gson both come transitively from
`runelite-client`, so there is nothing for a maintainer to hash-verify.

What's done:

- [x] **Data disclosure** — `apiEnabled` ("Enable Server Verification") and the
      API section tooltip now state exactly what is sent and where
      (`ChunkBlazerConfig.java`). This is the one mandatory content rule.
- [x] **Gradle scaffold** — `build.gradle`, `settings.gradle`,
      `runelite-plugin.properties` (this is additive; the Maven/in-tree dev
      workflow is untouched).

Remaining steps, in order:

## 1. Rename the Java package (required — do this deliberately, not mid-test)
`net.runelite.client.plugins.chunkblazer` → `com.chunkblazer`

The `net.runelite.*` namespace is reserved for first-party code and
split-packages against the client jar, so the Hub will reject it. This touches
every `.java` file (package decl + imports) and moves the resources:

- Source: `src/main/java/net/runelite/.../chunkblazer/`  → `src/main/java/com/chunkblazer/`
- Resources: `src/main/resources/net/runelite/.../chunkblazer/` → `src/main/resources/com/chunkblazer/`
  (icons/sounds load via `ImageUtil.loadImageResource(ChunkBlazerPlugin.class, ...)`,
  i.e. class-relative — they move with the package automatically, no path edits.)
- Update `plugins=` in `runelite-plugin.properties` to `com.chunkblazer.ChunkBlazerPlugin`.
- Update the copy paths in `sync-to-runelite.bat` so the dev loop keeps working.

Best done on a branch when you're NOT about to test in-game.

## 2. Generate the Gradle wrapper
The Hub CI builds with the wrapper:
```
gradle wrapper --gradle-version 8.10
```
Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`.

## 3. Polish (likely reviewer comments)
- Inject RuneLite's shared `OkHttpClient` (`@Inject`, then `.newBuilder()` to set
  timeouts) in `ChunkBlazerApiClient` instead of `new OkHttpClient.Builder()`.
- Use `net.runelite.client.util.LinkBrowser.browse(url)` for the Discord link in
  `ChunkBlazerPanel` instead of raw `java.awt.Desktop`.

## 4. Repo hygiene
- Public repo, `LICENSE` present at root (you have it), keep the BSD-2
  `LICENSE-region-locker` attribution for the GPU greyscale code.
- Optional: `icon.png` at repo root for the Hub listing.

## 5. Submit
1. Fork `runelite/plugin-hub`.
2. Add a file `plugins/chunkblazer` (no extension) containing:
   ```
   repository=https://github.com/btwinnn/Chunkblazer.git
   commit=<full 40-char commit hash to build>
   ```
3. Open a PR. Then be patient and respond to review comments within a day —
   stalled PRs are usually waiting on the author, not the reviewer.
