# ChunkBlazer Plugin Hub submission

## Data & server

ChunkBlazer talks to one first-party server (`api.chunkblazer.com`, HTTPS, no
third parties).

- **Player sync (the core reason for the server):** unlocked chunks, completed
  tasks, points, and game mode keyed to a hashed RSN. Enables saves across
  devices, leaderboards, and server-side completion verification.
- **Task catalog (read-only):** `GET /api/tasks` returns the task definitions,
  ~3 MB of JSON, fetched once and cached under `RUNELITE_DIR`, revalidated with
  ETag/304 (no re-download unless it changes).
- **Sounds (read-only):** completion jingles, content-addressed, ~60 MB of
  audio, too large to ship, fetched on demand and disk-cached the same way.

Server sync is OFF by default: nothing leaves the client until the user turns it
on. Nothing else is sent, the RSN is hashed, and users see a data-use disclosure in the plugin the first
time they consider enabling it. Full detail in `PRIVACY.md`.

The optional companion addon, ChunkBlazer GPU (greyscale rendering for locked
chunks), submits separately from its own repo with its own `plugins/chunkblazer-gpu`
manifest.
