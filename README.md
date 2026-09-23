# ChunkBlazer

Visit the [Chunkblazer](https://chunkblazer.com) website for more info!

ChunkBlazer is a Randomized, Automated, Task rolling [RuneLite](https://github.com/runelite/runelite) plugin for Old School RuneScape.

The world is carved into "chunks": 64×64-tile squares of the map. You start with a single chunk and earn points by completing random tasks inside the chunks you already own. Spend those points to unlock new chunks and grow your world one piece at a time.

# Data & the ChunkBlazer server

ChunkBlazer is a server-backed plugin. We send data over HTTPS to
`api.chunkblazer.com` it **syncs your progress** (unlocked chunks, completed
tasks, points, game mode, keyed to a hashed RSN) for saves, leaderboards, and
completion verification. It also **downloads** the task catalog and completion
sounds, cached locally and revalidated so they're pulled
only when they change. Nothing is shared with third parties; you can play
offline by disabling server verification. Full detail in [PRIVACY.md](PRIVACY.md).

# Acknowledgements

The ChunkBlazer GPU renderer is heavily inspired by [Region Locker GPU](https://github.com/SlayToStay/region-locker) by slaytostay and [RuneLite's GPU plugin](https://github.com/runelite/runelite/wiki/GPU).
