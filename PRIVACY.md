# ChunkBlazer Data & Privacy

ChunkBlazer is a **server-backed game mode**, similar to
[Wise Old Man](https://wiseoldman.net) and [TempleOSRS](https://templeosrs.com).
To save your progress across sessions and rank you on the leaderboards, the
plugin sends some data to ChunkBlazer's own servers. This document explains
exactly **what** is gathered, **where** it goes, and **how it works**, so you
can make an informed choice before enabling it.

You can review and control all of this from the plugin's settings, and you can
track your account progress any time at **https://chunkblazer.com**.

---

## How it works (in short)

ChunkBlazer is a chunk-unlock challenge: you unlock map regions ("chunks") and
complete randomly-assigned tasks for points. So that your save state, points,
unlocked chunks and leaderboard rank persist across logins and devices (and so
completions can be verified fairly), the plugin syncs that progress to the
ChunkBlazer server while you play. If you turn syncing off, the plugin still
works locally, but your progress is **not** saved to the server, and
leaderboards / player discovery are unavailable.

This is the same model Wise Old Man and TempleOSRS use: a RuneLite plugin that
reports your in-game progress to an external companion service.

---

## What data is gathered

Sent **only while "Enable Server Verification" is ON** in the plugin settings:

| Data | Why |
|---|---|
| Your RuneScape name (RSN) | Identifies your account and shows your name on leaderboards |
| Your current world and map region | Powers "players online" and region-based features |
| Progress events: NPC kills, XP / skill changes, items obtained or equipped, task completions | Verifies task completions server-side (anti-cheat) and updates your save |
| Your unlocked chunks, points, tasks and game mode | Saves and restores your account state |

ChunkBlazer does **not** collect your password, bank contents, private messages,
real-world identity, email, IP-based tracking beyond ordinary web-server logs,
or anything unrelated to the challenge.

---

## Where it goes

- All requests are sent over **HTTPS** to **`api.chunkblazer.com`**.
- Data is stored on ChunkBlazer's own servers and used solely to run the game
  mode, leaderboards, and player-discovery features.
- Your data is **not sold or shared with third parties.**

---

## What the plugin downloads

Two things are too large to ship inside the plugin, so they're fetched from the
server (read-only) and cached on your machine:

- **Task definitions:** `GET /api/tasks` (~3 MB of JSON). Fetched once, cached
  under `RUNELITE_DIR`, and revalidated with an ETag/`304 Not Modified`, so it's
  re-downloaded only when the catalog actually changes.
- **Completion jingles:** content-addressed audio (~60 MB total). Fetched on
  demand and disk-cached the same way; only the sounds you encounter are pulled.

These are one-way downloads of game content; no personal data is sent to
retrieve them.

---

## Your control

- **Play offline:** turn off **"Enable Server Verification"** in the plugin
  settings. With it off, nothing leaves your client, but server saves,
  leaderboards, and seeing other players are disabled.
- **Stay hidden:** turn off **"Visible to Others"** to keep your name off the
  "players online" list while still saving your progress.
- The plugin's side panel shows a permanent "Progress synced to chunkblazer.com"
  notice with an (ⓘ) link to this same explanation.

---

## Track your progress

Your account, unlocked chunks, tasks, points, and leaderboard rank are viewable
at **https://chunkblazer.com**.

Questions? Join the [ChunkBlazer Discord](https://discord.gg/D8DYP45DV8).
