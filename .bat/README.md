# ChunkBlazer dev/tester tooling (.bat)

Internal Windows scripts for running ChunkBlazer against a local RuneLite dev
client. **Not part of the Plugin Hub submission** - this whole folder is meant to
live on the `dev` branch and be removed from `main` before/at submission
(`git rm -r .bat/` on main). `gradlew.bat` stays at the repo root because the Hub
build needs the Gradle wrapper; it is not part of this folder.

## Scripts

| Script | What it does |
|---|---|
| `setup-chunkblazer.bat` | First-time bootstrap: clones RuneLite into `C:\runelite`, copies the plugin in, does the first (slow) build. **Deletes and re-clones `C:\runelite` each run.** |
| `run-chunkblazer.bat` | Per-session: syncs the checked-out branch into the RuneLite mirror, builds, and launches the dev client. Prints which branch it is running. |
| `sync-to-runelite.bat` | Mirror the plugin sources into `C:\runelite` without launching (for IntelliJ runs). |
| `update-runelite.bat` | Fast-forward the local RuneLite to upstream (fixes stale-revision login errors). |
| `drift-check.bat` | Wrapper for `drift-check.ps1`. |
| `jagex-wrapper.bat`, `nuke-gradle-cache.bat` | Jagex-launcher helper; clears the Gradle cache. |

## New internal tester: first-time setup

**Prerequisites** (install first, both on PATH):
- **Git** - https://git-scm.com/download/win
- **Java JDK 11** (not just a JRE) - https://adoptium.net. RuneLite is happiest on
  11; 17/21 can cause build hiccups.
- **Repo access** to `Chunkblazer` and the RuneLite fork `runelite-chunkblazer`
  (setup clones both). Optional: the `Chunkblazer-GPU` repo cloned as a sibling
  (`..\Chunkblazer-GPU`) for the GPU renderer.

**Steps:**
1. Clone the plugin repo to `C:\Chunkblazer`.
2. `git checkout dev` (this branch carries the tooling and the in-client dev
   tools; `main` is the clean submission branch).
3. Run `.bat\setup-chunkblazer.bat` **once**.
4. Run `.bat\run-chunkblazer.bat` each session after that.

That's it. Logs land in `run-log.txt` / `setup-log.txt`; per-session game output
is saved under `session_logs\`.
