# ChunkBlazer

Visit the [Chunkblazer](https://chunkblazer.com) website for more info!

A chunk-unlocking adventure mode for Old School RuneScape, built on [RuneLite](https://github.com/runelite/runelite).

> **Coming soon to the RuneLite Plugin Hub.**

The world is carved into "chunks": 64×64-tile squares of the map. You start with a single chunk and earn points by completing random tasks inside the chunks you already own. Spend those points to unlock new chunks and grow your world one piece at a time. Think of it as a make-your-own, gamified adventure where every new area is something you actually *earned*.

# Data & the ChunkBlazer server

ChunkBlazer is server-backed (like Wise Old Man or TempleOSRS). Over HTTPS to
`api.chunkblazer.com` it **syncs your progress** (unlocked chunks, completed
tasks, points, game mode, keyed to a hashed RSN) for saves, leaderboards, and
completion verification. It also **downloads** the task catalog and completion
sounds (too large to bundle), cached locally and revalidated so they're pulled
only when they change. Nothing is shared with third parties; you can play
offline by disabling server verification. Full detail in [PRIVACY.md](PRIVACY.md).

# Acknowledgements

The ChunkBlazer GPU renderer is heavily inspired by [Region Locker GPU](https://github.com/SlayToStay/region-locker) by slaytostay and [RuneLite's GPU plugin](https://github.com/runelite/runelite/wiki/GPU).
