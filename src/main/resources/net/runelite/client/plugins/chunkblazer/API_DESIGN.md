# ChunkBlazer API Design

REST API for player data persistence, anti-cheat verification, hi-score tracking,
and leaderboards.

> **Status:** This document describes the **real implemented server** — a Go service
> backed by PostgreSQL. (An earlier draft of this file described a Cloudflare
> Workers + D1 prototype that was never shipped; it has been replaced.)

---

## Stack & Architecture

| Layer        | Technology |
|--------------|------------|
| Language     | Go (`net/http` + [chi](https://github.com/go-chi/chi) router) |
| Database     | PostgreSQL (via [pgx](https://github.com/jackc/pgx) connection pool) |
| Query layer  | [sqlc](https://sqlc.dev) — type-safe Go generated from `queries/*.sql` |
| Migrations   | [golang-migrate](https://github.com/golang-migrate/migrate) — `migrations/NNNNNN_*.up.sql` |
| Logging      | `slog` (structured) |
| Packaging    | Multi-stage Docker build → static binary on `alpine` |
| Edge/ingress | Cloudflare Tunnel → Caddy (internal reverse proxy) → Go app → Postgres |

Source layout (`C:\Chunkblazer-Server`):

```
cmd/server/            entrypoint
internal/httpapi/      HTTP handlers + middleware (rate limiting, RealIP, max-body)
internal/db/           sqlc-generated query code (do not edit by hand)
internal/hiscores/     Jagex hi-scores fetcher + background refresh scheduler
internal/eventsmaint/  cb_events partition maintenance + retention
queries/*.sql          sqlc source queries
migrations/*.sql       schema migrations
```

### Background workers
- **Hi-score scheduler** (`internal/hiscores/scheduler.go`): single-threaded loop,
  one player per 2s tick. Refreshes on the **logout signal** (Jagex hi-scores only
  recalculate when a player logs out), inferred from the `is_online` flag /
  heartbeat staleness — see `queries/refresh.sql`. Re-detects account type on a 404
  (HCIM death → IRONMAN, de-iron, name change) and on a 7-day TTL.
- **Events maintenance** (`internal/eventsmaint`): pre-creates monthly `cb_events`
  partitions and drops ones past the 90-day retention window.

---

## Authentication

- **Registration** (`POST /api/player/login`) is unauthenticated. It returns an
  `api_key` (a UUID) tied to that player.
- **All authenticated endpoints** require the header `X-API-Key: <uuid>`. A missing
  key → `401 no_api_key`; a non-UUID key → `401 bad_api_key`; an unrecognized key →
  `401 unknown_api_key`.
- **Public read endpoints** (leaderboards, online players, healthz) need no key.

### Standard error envelope
Every error response uses:
```json
{ "status": "error", "error": "<machine_code>", "message": "<human readable>" }
```
The `error` code strings are stable and listed per-endpoint below.

---

## Endpoint Summary

| # | Method | Path | Auth | Purpose |
|---|--------|------|------|---------|
| 1 | GET  | `/healthz` | – | Liveness + DB ping |
| 2 | POST | `/api/player/login` | – | Register / login, issues api_key |
| 3 | POST | `/api/player/heartbeat` | ✓ | Keep-online + presence |
| 4 | POST | `/api/player/offline` | ✓ | Logout beacon |
| 5 | POST | `/api/player/lock-mode` | ✓ | Permanently lock game mode |
| 6 | POST | `/api/player/verify/start` | ✓ | Begin RSN-ownership verification |
| 7 | POST | `/api/player/verify` | ✓ | Complete verification with nonce |
| 8–11 | POST | `/api/v1/events/{npc-kill,skill-change,item-obtained,item-equipped}` | ✓ | Gameplay event reports |
| 12 | POST | `/api/v1/player/sync` | ✓ | Full client-state sync |
| 13 | POST | `/api/v1/hiscores/refresh` | ✓ | On-demand Jagex hi-score fetch |
| 14 | GET  | `/api/leaderboards/{mode}/{accountType}/{metric}` | – | Leaderboard page |
| 15 | GET  | `/api/players/online` | – | Live presence list |
| 16 | GET  | `/api/player/rank` | ✓ | Authed player's own rank |

**Rate limits** (per IP unless noted): default 200/min IP + 200/min api_key;
`/login` 60/min; `/verify/start` 10/min; `/verify` 30/min; `/hiscores/refresh`
10/min; leaderboards & online 120/min. Request bodies are capped (login 4 KB,
heartbeat/offline/lock-mode 1 KB, verify 256 B, events 64 KB, sync 32 KB).

---

## 1. Health Check

**GET /healthz** — no auth, no rate limit.

`200` → `{ "status": "ok" }`
`503` → `{ "status": "db_down" }` (Postgres ping failed)

---

## 2. Login / Registration

**POST /api/player/login** — no auth. Creates the account if new (subject to
`REGISTRATION_MODE`), else returns existing state.

Request:
```json
{ "rsn": "ExamplePlayer", "rsn_hash": "<hash>", "client_version": "0.x.y" }
```

Response (`status` is `"created"` for a new player, `"ok"` for an existing one):
```json
{
  "status": "ok",
  "player": {
    "rsn": "ExamplePlayer",
    "game_mode": "CASUAL",
    "mode_locked": true,
    "locked_at": "2026-01-11T08:46:10Z",
    "total_points": 1250,
    "unlocked_regions": [12850, 12851],
    "completed_tasks": ["defeat_mugger_fast"],
    "verified": true,
    "verified_at": "2026-01-11T09:00:00Z"
  },
  "api_key": "550e8400-e29b-41d4-a716-446655440000"
}
```
`game_mode`/`locked_at`/`verified_at` may be null. Error codes: `bad_request`,
`registration_closed` (403), `internal`.

---

## 3. Heartbeat

**POST /api/player/heartbeat** — auth. Sent every ~30s while logged in. Refreshes
`last_heartbeat_at`, sets `is_online = TRUE`, and updates world/region/visibility.

Request:
```json
{ "world": 420, "region_id": 12850, "is_visible": true }
```
Response: `{ "status": "ok" }`. Errors: `no_api_key`, `bad_api_key`,
`unknown_api_key`, `bad_request`, `internal`.

---

## 4. Logout Beacon

**POST /api/player/offline** — auth. Sent when the player leaves to the login
screen. Sets `is_online = FALSE` so the hi-score scheduler can snapshot the
just-ended session immediately (instead of waiting ~3 min for heartbeats to go
stale) and the presence list drops them at once. Best-effort: if it's lost, the
heartbeat-staleness fallback still catches the logout.

Request: `{}` (empty). Response: `{ "status": "ok" }`. Errors: `no_api_key`,
`bad_api_key`, `unknown_api_key`, `internal`.

---

## 5. Lock Game Mode

**POST /api/player/lock-mode** — auth. One-shot, irreversible.

Request: `{ "game_mode": "NUZLOCKE" }`

Success:
```json
{ "status": "ok", "message": "...", "game_mode": "NUZLOCKE",
  "mode_locked": true, "locked_at": "2026-01-11T08:46:10Z" }
```
Already locked → `200` with `status: "error"`, `error: "MODE_ALREADY_LOCKED"`.
Other errors: `no_api_key`, `bad_api_key`, `unknown_api_key`, `bad_request`,
`invalid_mode`, `internal`.

---

## 6 & 7. RSN Verification

Proves the player controls the RSN by typing a one-time code in public chat.

**POST /api/player/verify/start** — auth. Issues an 8-digit nonce (5-min TTL).
```json
{ "alreadyVerified": false, "nonce": "12345678",
  "expiresAt": "2026-01-11T08:51:10Z", "chatPhrase": "12345678" }
```
If already verified → `{ "alreadyVerified": true }`.

**POST /api/player/verify** — auth. Request: `{ "nonce": "12345678" }`.
Success: `{ "verified": true, "message": "..." }`. Bad/expired/used nonce →
`400 invalid_nonce`. Other errors: `bad_request` + the standard auth codes.

---

## 8–11. Gameplay Event Reports

**POST /api/v1/events/npc-kill**
**POST /api/v1/events/skill-change**
**POST /api/v1/events/item-obtained**
**POST /api/v1/events/item-equipped**

Auth. High-volume PvM/skilling telemetry used for task verification and the audit
log. The body is the event report JSON (server extracts `taskId` and stores the
full payload as JSONB in `cb_events`).

Response (shared shape):
```json
{
  "success": true,
  "taskCompleted": false,
  "verifiedProgress": 0,
  "pointsAwarded": 0,
  "serverTaskId": "...",
  "offlineMode": false,
  "serverTimestamp": 1736610370000
}
```
`errorMessage` / `rejectionReason` appear when relevant. Errors: the standard auth
codes plus `body_too_large`, `empty_body`, `bad_json`, `internal`.

---

## 12. Full State Sync

**POST /api/v1/player/sync** — auth. Reconciles the client's full state with the
server (anti-cheat + recovery). Body (32 KB cap):
```json
{
  "playerHash": "...", "displayName": "ExamplePlayer",
  "accountType": "IRONMAN", "gameMode": "NUZLOCKE",
  "combatLevel": 80, "totalLevel": 1500,
  "skillLevels": { "attack": 70 }, "skillXp": { "attack": 737627 },
  "currentRegionId": 12850, "unlockedRegions": [12850, 12851],
  "activeTaskId": "kill_goblins", "activeTaskProgress": 3,
  "clientPoints": 1250, "timestamp": 1736610370000,
  "clientVersion": "0.x.y", "completedTasks": ["..."]
}
```
Response:
```json
{
  "success": true, "serverPoints": 1250,
  "serverUnlockedRegions": [12850, 12851], "serverCompletedTasks": ["..."],
  "messages": ["..."], "flagged": false, "flagReason": "",
  "serverTimestamp": 1736610370000
}
```
Errors: the standard auth codes plus `bad_request`, `internal`.

---

## 13. On-Demand Hi-score Refresh

**POST /api/v1/hiscores/refresh** — auth, 10/min. Forces an immediate Jagex fetch
for the authed player (the scheduler normally handles this automatically).
```json
{
  "success": true, "snapshotId": 9001, "fetchedAt": "2026-01-11T08:46:10Z",
  "accountType": "IRONMAN", "rsn": "ExamplePlayer",
  "overallRank": 123456, "overallLevel": 1500, "overallXP": 123456789,
  "activitiesCount": 40
}
```
Jagex unreachable → `502 jagex_fetch_failed`. Other errors: the standard auth
codes plus `internal`.

---

## 14. Leaderboard

**GET /api/leaderboards/{mode}/{accountType}/{metric}** — no auth, 120/min.

Each `(mode, accountType, metric)` triple is its own board, so HCIM, IRONMAN, and
UIM rank **separately** (mirroring Jagex). A dead HCIM is auto-moved to the IRONMAN
board by the scheduler's account-type re-detection.

- `mode`: `CASUAL` | `NUZLOCKE` (case-insensitive)
- `accountType`: `STANDARD` | `IRONMAN` | `HCIM` | `UIM` | `SKILLER_3`
- `metric`: one of the 24 skill XPs (`attack_xp` … `sailing_xp`), `overall_xp`,
  `overall_level`, or the ChunkBlazer metrics `total_points`, `chunks_unlocked`,
  `tasks_completed`
- Query: `limit` (1–200, default 50), `offset` (≥0, default 0)

Response:
```json
{
  "mode": "NUZLOCKE", "accountType": "HCIM", "metric": "total_points",
  "limit": 50, "offset": 0, "total": 5432,
  "entries": [
    {
      "rank": 1, "rsn": "ChunkGod", "value": 15420,
      "overallLevel": 2277, "overallXP": 4600000000,
      "totalPoints": 15420, "chunksUnlocked": 87, "tasksCompleted": 312,
      "snapshotAt": "2026-01-11T08:00:00Z"
    }
  ],
  "fetchedAt": "2026-01-11T08:46:10Z"
}
```
`value`/`overallLevel`/`overallXP`/`snapshotAt` are null when the player has no
hi-score snapshot yet (sorted NULLS LAST). Errors: `invalid_mode`,
`invalid_account_type`, `invalid_metric`, `internal`.

---

## 15. Online Players

**GET /api/players/online** — no auth, 120/min. Query: `limit` (1–200, default 50),
`offset`. Returns players who are `is_online`, visible, and heartbeated within the
last 2 minutes.
```json
{
  "total": 142, "limit": 50, "offset": 0,
  "players": [
    {
      "rsn": "ChunkGod", "accountType": "HCIM", "gameMode": "NUZLOCKE",
      "currentWorld": 420, "currentRegionId": 12850,
      "lastHeartbeatAt": "2026-01-11T08:46:00Z",
      "totalPoints": 15420, "rank": 1
    }
  ],
  "fetchedAt": "2026-01-11T08:46:10Z", "windowSeconds": 120
}
```
`gameMode`/`currentWorld`/`currentRegionId`/`rank` may be null. Error: `internal`.

---

## 16. Player Rank

**GET /api/player/rank** — auth. Optional query `metric` (default `overall_xp`).
Returns the authed player's rank within their own `(mode, accountType)` bucket.
```json
{
  "rsn": "ExamplePlayer", "mode": "NUZLOCKE", "accountType": "IRONMAN",
  "metric": "overall_xp", "rank": 142, "total": 5432,
  "percentile": 97.3, "value": 123456789,
  "fetchedAt": "2026-01-11T08:46:10Z", "note": ""
}
```
If the player hasn't locked a mode, `mode` is `""`, `rank`/`value` are null, and
`note` explains why. Errors: the standard auth codes plus `invalid_metric`,
`internal`.

---

## Database Schema

PostgreSQL. Defined in `migrations/` and queried via sqlc.

### `players`
| Column | Type | Notes |
|--------|------|-------|
| player_id | BIGSERIAL PK | |
| rsn | TEXT NOT NULL | in-game name |
| rsn_hash | TEXT NOT NULL UNIQUE | dedupe key |
| api_key | UUID NOT NULL UNIQUE | default `gen_random_uuid()` |
| account_type | TEXT NOT NULL | default `STANDARD`; CHECK in (STANDARD, IRONMAN, HCIM, UIM, SKILLER_3) |
| game_mode | TEXT | null \| CASUAL \| NUZLOCKE (CHECK) |
| mode_locked | BOOLEAN NOT NULL | default FALSE |
| locked_at | TIMESTAMPTZ | |
| current_world / current_region_id | INTEGER | |
| is_visible | BOOLEAN NOT NULL | default TRUE |
| created_at | TIMESTAMPTZ NOT NULL | default NOW() |
| last_heartbeat_at | TIMESTAMPTZ | |
| last_hiscore_refresh_at | TIMESTAMPTZ | drives the refresh scheduler |
| account_type_detected_at | TIMESTAMPTZ | detection TTL anchor |
| verified / verified_at | BOOLEAN / TIMESTAMPTZ | RSN ownership |
| is_online | BOOLEAN NOT NULL | default TRUE; set FALSE by the logout beacon |

Indexes: `last_heartbeat_at DESC`, `last_hiscore_refresh_at NULLS FIRST`.

### `cb_player_stats`
`player_id` PK/FK→players (CASCADE), `total_points`, `chunks_unlocked`,
`tasks_completed`, `legendary_tasks_completed` (all INT NOT NULL default 0),
`updated_at`.

### `cb_events` (partitioned by month on `occurred_at`)
`event_id` BIGSERIAL, `player_id` FK→players (CASCADE), `event_type` TEXT CHECK in
(npc_kill, skill_change, item_obtained, item_equipped), `payload` JSONB,
`occurred_at` TIMESTAMPTZ. PK `(event_id, occurred_at)`. Indexed on
`(player_id, occurred_at DESC)` and `(event_type, occurred_at DESC)`. Monthly
partitions auto-managed; 90-day retention.

### `cb_unlocked_regions`
PK `(player_id, region_id)`, `unlocked_at`. FK→players (CASCADE).

### `cb_completed_tasks`
PK `(player_id, task_id)`, `completed_at`. FK→players (CASCADE).

### `hiscore_snapshots`
`snapshot_id` BIGSERIAL PK, `player_id` FK→players (CASCADE), `fetched_at`,
`account_type` (same CHECK as players), `overall_rank/level/xp`, the 24 per-skill
`*_xp` BIGINT columns (`attack_xp` … `sailing_xp`), and `activities` JSONB
(default `'[]'`). Indexed on `(player_id, fetched_at DESC)`.

### `verification_nonces`
`nonce` TEXT PK (8-digit), `player_id` FK→players (CASCADE), `created_at`,
`expires_at` (5-min TTL), `consumed_at`. Partial indexes on active (unconsumed)
nonces by player and by expiry.

---

## Deployment

Production runs on a Hetzner dedicated box via Docker Compose
(`docker-compose.prod.yml`):

```
[Cloudflare edge] → [cloudflared tunnel, outbound] → [caddy :80 internal]
    → [app :8080] → [postgres :5432]      (internal bridge network)
```

- **Zero public inbound ports** — the only path in is the outbound Cloudflare
  Tunnel, so trusting `X-Forwarded-For` (via chi's `RealIP`) is structural.
- TLS terminates at the Cloudflare edge; Caddy proxies plaintext internally.
- Secrets (`POSTGRES_PASSWORD`, `CLOUDFLARE_TUNNEL_TOKEN`) live in
  `.env.production` (gitignored, never committed).
- Daily `pg_dump` backup → local + optional Hetzner Storage Box (`scripts/backup.sh`).

See the repo's deploy bundle (`Dockerfile`, `docker-compose.prod.yml`,
`deploy/Caddyfile`, `.env.production.example`) for the full setup.
