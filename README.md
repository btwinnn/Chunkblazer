# ChunkBlazer

A Nuzlocke-style chunk-unlocking plugin for Old School RuneScape, built on top of [RuneLite](https://github.com/runelite/runelite). Each 64×64 map region is a "chunk"; you unlock new chunks by spending points earned from completing randomly-rolled tasks in the chunks you already own.

> **Status: internal alpha.** The plugin is functional and being playtested, but features and config keys are still in flux. If you're a tester, run from source per the steps below — there's no Plugin Hub release yet.

## Features

- **Per-chunk RNG task assignment.** When a chunk is unlocked, 4–5 tasks are rolled from that chunk's pool. Tasks are tracked across many activity types: combat (NPC kills with constraint support), skilling (mining, fishing, cooking, smithing, fletching, woodcutting, crafting, herblore, runecrafting, hunter), agility, firemaking, construction, thieving, equipping items, NPC dialogue, varbit/varp checks, and pure "obtain" tasks.
- **Chunk visualization on the gameplay viewport.** Continuous outlines around every visible chunk, green for unlocked / dark grey for locked. Drawn by `ChunkBorderRenderer` as chained polylines (one `Path2D` per region-side) with round caps and joins so terrain elevation joints stay smooth.
- **In-panel unlock prompt.** When you walk into a locked region, the side panel shows the region name, cost, your point total, and a one-click "Unlock for X pts" → "Confirm? Yes / No" flow. Spending points always requires explicit user input — walking never auto-spends.
- **Region-specific jingle on first unlock.** Plays a random sound from the unlocked chunk's area pack (Misthalin / Asgarnia / Kandarin / Varlamore / Zeah). Toggle via "Play Region Unlock Jingle" in config.
- **Region-specific task-completion sounds.** Same per-area sound pool plays on task completion; toggle via "Play Task Completion Sound".
- **Two game modes.**
  - **Casual** — play on any account, no leaderboard tracking.
  - **Full Nuzlocke** — start as a fresh level-3 in Lumbridge, eligible for leaderboards (server backend WIP).
- **Robust progress tracking.** Skilling tasks credit only when a watched item lands in the inventory in the same tick as a matching-skill XP drop, so banked stockpiles and GE buys don't count toward "mine N ore" tasks. OBTAIN tasks (no skill) still count inv + bank + equipment.
- **Per-session debug logging.** `run-chunkblazer.bat` saves up to 5 of the most recent session logs in `C:\Chunkblazer\session_logs\session_<timestamp>.txt`. Send the most recent one when reporting bugs.
- **Side-panel UI.** Stats, current/active tasks, completed-task history, region tasks (filterable), dev controls, and the unlock prompt — all in the standard RuneLite right-side panel.

## Quick Start (Internal Testers)

The plugin builds against a local clone of RuneLite. The bat scripts handle that for you on Windows.

### One-time setup

**Prerequisites:**

- Windows 10/11.
- [Git](https://git-scm.com/download/win).
- [Adoptium Temurin JDK 11 (Windows x64)](https://api.adoptium.net/v3/installer/latest/11/ga/windows/x64/jdk/hotspot/normal/eclipse) — JDK, not JRE.

**Setup:**

1. Clone this repo to `C:\Chunkblazer`:
   ```cmd
   git clone https://github.com/btwinnn/Chunkblazer.git C:\Chunkblazer
   ```
2. Right-click `setup-chunkblazer.bat` → **Run as administrator**.
   This clones RuneLite to `C:\runelite`, copies the plugin source into RuneLite's tree, and does the first build (5–10 min the first time).
3. When you see "Setup complete", you're done.

### Day-to-day: launching the dev client

Double-click **`run-chunkblazer.bat`**. It will, in order:

1. `git pull` the latest RuneLite (so your client doesn't fall behind upstream).
2. `git pull` the latest ChunkBlazer.
3. Mirror plugin source from `C:\Chunkblazer\src\…` into `C:\runelite\runelite-client\src\…`.
4. Build the shaded client jar.
5. Launch RuneLite with `--developer-mode --debug --insecure-write-credentials`.
6. Tee the live log to `C:\Chunkblazer\session_logs\session_<timestamp>.txt` (newest 5 kept).

If anything goes wrong during boot, the build log is in `C:\Chunkblazer\run-log.txt`. If something goes wrong in-game, grab the most recent `session_logs\session_*.txt`.

### Bug reports

Please include:

1. The most recent file in `C:\Chunkblazer\session_logs\`.
2. A short description of what you did and what you expected.
3. (If UI-related) a screenshot of the panel or the game viewport.

Drop them in the dev Discord channel.

## Sync workflow (for plugin contributors)

`C:\Chunkblazer` is the canonical source tree. `C:\runelite` is a build mirror. Three relevant scripts:

| Script | What it does |
| --- | --- |
| `setup-chunkblazer.bat` | First-time only. Clones RuneLite into `C:\runelite` and does an initial sync + build. |
| `sync-to-runelite.bat` | Mirrors `src\main\java\…\chunkblazer\` and `src\main\resources\…\chunkblazer\` from `C:\Chunkblazer` to `C:\runelite`. Also copies a curated set of task JSONs from `Tasks_JSON\<area>\` into resources. Use after editing if you're rebuilding from IntelliJ instead of via the run script. |
| `run-chunkblazer.bat` | Pulls + syncs + builds + launches in one step. Most testers will only ever need this one. |
| `update-runelite.bat` | Force-updates the `C:\runelite` mirror to upstream master (useful if RuneLite has shipped a breaking change and you need a clean re-sync). |

The plugin's runtime task-JSON list lives in `ChunkBlazerPlugin.TASK_JSON_FILES`. If you add a new top-level task JSON to that constant, also add the filename to the `TASK_JSONS` variable in `sync-to-runelite.bat` so the sync picks it up.

## File structure

```
C:\Chunkblazer
├── src/
│   ├── main/
│   │   ├── java/net/runelite/client/plugins/chunkblazer/
│   │   │   ├── ChunkBlazerPlugin.java          # main plugin lifecycle, region/unlock/event wiring
│   │   │   ├── ChunkBlazerPanel.java           # full side-panel UI (stats, unlock, tasks, dev)
│   │   │   ├── ChunkBlazerConfig.java          # config items (toggles, thresholds, sounds)
│   │   │   ├── ChunkBlazerSceneOverlay.java    # registers the chunk-border overlay
│   │   │   ├── ChunkBorderRenderer.java        # geometry + drawing for chunk borders (unit tested)
│   │   │   ├── ChunkBlazerMinimapOverlay.java  # right-click unlock on the minimap
│   │   │   ├── ChunkBlazerWorldMapOverlay.java # click-to-unlock on the world map
│   │   │   ├── ChunkBlazerPlayerOverlay.java   # icons over other ChunkBlazer players
│   │   │   ├── TaskCompletionAnimationOverlay  # in-game completion popup
│   │   │   ├── TaskCompletionSoundManager.java # per-area sound pool playback
│   │   │   ├── NuzlockeTask.java               # task data model (deserializer-tolerant)
│   │   │   ├── NuzlockeChunk.java              # chunk/region data model
│   │   │   ├── RequiredItem.java / TargetNpc.java / TaskConstraints.java
│   │   │   ├── GameMode.java
│   │   │   ├── api/                            # request/response DTOs for the (WIP) backend
│   │   │   ├── starter/                        # starter-area helpers for full Nuzlocke mode
│   │   │   ├── verification/                   # VarPlayer / Hiscore based task verification
│   │   │   └── modules/                        # per-completion-type tracking
│   │   │       ├── AbstractTaskModule.java     # base + callback contract
│   │   │       ├── TaskModuleManager.java      # routes tasks to the right module
│   │   │       ├── NPCKillModule.java          # combat tasks (incl. constraint checks)
│   │   │       ├── ObtainModule.java           # OBTAIN + all skilling production
│   │   │       ├── AgilityModule.java / FiremakingModule.java / ConstructionModule.java
│   │   │       ├── ThievingModule.java / SkillModule.java / EquipModule.java
│   │   │       ├── NpcDialogueModule.java / VarbitCheckModule.java
│   │   │       └── TaskCompletionModule.java   # interface
│   │   └── resources/net/runelite/client/plugins/chunkblazer/
│   │       ├── *.json                          # task definitions, populated by sync from Tasks_JSON/
│   │       ├── Task_Complete_Region_Sounds/    # per-area .wav pools
│   │       └── icon assets
│   └── test/java/net/runelite/client/plugins/chunkblazer/
│       ├── ChunkBorderRendererTest.java        # geometry / chain-building tests
│       ├── RollCacheTest.java                  # quantity-roll caching tests
│       └── modules/*Test.java                  # one test class per module
├── Tasks_JSON/                                 # editable task-content tree (per-area folders)
├── pom.xml                                     # Maven build for unit tests
├── setup-chunkblazer.bat / run-chunkblazer.bat / sync-to-runelite.bat / update-runelite.bat
└── README.md
```

## Configuration toggles (in the RuneLite plugin config UI)

- **Display**
  - Show Minimap Chunks — minimap chunk highlighting + right-click-to-unlock menu.
  - Show Chunk Borders — draws region outlines on the gameplay viewport.
  - Show Task Completion Popup — animated overlay on task complete.
  - Play Task Completion Sound — region-specific jingle on completion.
  - Play Region Unlock Jingle — region-specific jingle on first unlock.
- **Region**
  - Auto-Unlock Regions — master switch for walk-in unlocks. Must be combined with Free Auto-Unlock to actually unlock anything (walking never spends points; explicit click required for cost-based unlocks).
  - Free Auto-Unlock — exploration mode: every region you walk into unlocks for free.
  - Show Unlock Popup — the in-game chatbox unlock prompt.
- **Chat Messages**
  - Show Task Progress / Show Task Success — per-action chat lines.
- **Other Players**
  - Show Other Players — icons over other ChunkBlazer players visible on your world.

## Dev panel controls

The bottom of the side panel has a collapsible **Dev/Test Controls** section:

- **+10 pts / +100 pts** — adds points to your total. Points stay as points; walking never auto-spends them.
- **Rst Tasks** — clears completed-tasks list, task-progress data, rolled-task pools; re-rolls the current region's tasks.
- **Reset All** — full reset to a single starter chunk, zero points, zero progress.
- **DEBUG: Complete Task** — right-click menu entry on any active task to instantly complete it.

## Known limitations / WIP

- **GPU-side locked-chunk wash is not implemented.** A semi-transparent grey "fog" effect over locked chunks was prototyped in software (Java2D `fillPolygon` per tile) and removed because it tanked framerate. The plan is to port the Region Locker plugin's GPU-shader approach next.
- **Server-side leaderboards / cross-account state not yet live.** The `api/` package has the DTOs in place; the backend is in design.
- **Task content is unfinished outside the starter area + a handful of regions.** New JSONs land under `Tasks_JSON/<area>/` and need to be wired into `ChunkBlazerPlugin.TASK_JSON_FILES` (and `sync-to-runelite.bat`'s `TASK_JSONS` list) before the plugin loads them.

## Building & running tests

The unit tests (currently 128, covering border-chain geometry, roll caching, and each completion module) run via Maven:

```cmd
cd C:\Chunkblazer
mvn test
```

These are independent of the RuneLite mirror — they use the local `pom.xml`. Expected runtime: ~5 seconds.

## License

For educational and personal use with RuneLite. Not affiliated with Jagex or RuneLite Ltd.
