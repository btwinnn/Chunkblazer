# ChunkBlazer

A chunk-unlocking adventure mode for Old School RuneScape, built on [RuneLite](https://github.com/runelite/runelite).

> **Coming soon July/August 2026 to the RuneLite Plugin Hub.**

The world is carved into "chunks" — 64×64-tile squares of the map. You start with a single chunk and earn points by completing random tasks inside the chunks you already own. Spend those points to unlock new chunks and grow your world one piece at a time. Think of it as a make-your-own, gamified adventure where every new area is something you actually *earned*.

## What it does

- **Random tasks for every chunk.** Unlock a chunk and it rolls you 4–5 tasks to chase — combat, skilling, agility, thieving, collecting items, talking to NPCs, and lots more.
- **See your chunks in the game world.** Every chunk gets an outline on screen: green for the ones you own, grey for locked ones. Chunks also show up on the world map and minimap.
- **One-click unlocks.** Walk up to a locked chunk and the side panel shows its name, what it costs, and how many points you have. Unlock it with a click — it never spends your points without asking first.
- **Sounds and flair.** Region-themed jingles play when you unlock a new area or finish a task. All of it can be toggled off.
- **Two ways to play.**
  - **Casual** — play on any account, just for fun.
  - **Full Nuzlocke** — start fresh as a level-3 in Lumbridge and play for the leaderboards (leaderboards are still being built).
- **Fair progress tracking.** Gathering tasks like "mine 50 ore" only count ore you actually mine while the task is active — banked stockpiles and Grand Exchange buys don't sneak in.
- **Everything in one place.** Your stats, current tasks, completed history, and unlock prompts all live in the RuneLite side panel.

## The greyscale add-on (ChunkBlazer GPU)

Want your locked chunks to fade to greyscale so your unlocked world really pops? That's an **optional** extra called **ChunkBlazer GPU**. It's a separate entry in the RuneLite plugin list and it's **off by default** — turn it on whenever you want it.

Two things worth knowing:

- It works by taking over RuneLite's graphics (the GPU rendering), so switching it on turns off the standard **GPU** plugin. That's normal, not a bug.
- For the same reason, it **can't run at the same time as 117 HD**. If you use 117 HD, just leave ChunkBlazer GPU off — you'll still get your chunk outlines and map markers, you just won't get the greyscale fade.

## Settings you can change

Open ChunkBlazer's settings in the RuneLite plugin panel to turn things on and off, including:

- Showing chunk outlines, minimap chunks, and the world-map overlay
- Task-completion popups and sounds, and region unlock jingles
- How auto-unlocking works (walking into a chunk never spends points on its own — cost-based unlocks always need a click)
- Greyscale style and strength (when the ChunkBlazer GPU add-on is on)

## Still being built

A few things aren't finished yet:

- **Leaderboards and cross-account features** aren't live — the groundwork is there, but the server side is still in design.
- **Task content** is complete for the starter area and a handful of regions; more areas are being filled in.

## Acknowledgements

The ChunkBlazer GPU renderer is heavily inspired by [Region Locker GPU](https://github.com/SlayToStay/region-locker) by slaytostay and [RuneLite's GPU plugin](https://github.com/runelite/runelite/wiki/GPU).
