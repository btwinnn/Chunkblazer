# ChunkBlazer API Design

## Overview
Simple REST API for player data persistence and leaderboard tracking.

---

## Database Schema

### Players Table
```sql
CREATE TABLE players (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    rsn           TEXT NOT NULL UNIQUE,
    rsn_hash      TEXT NOT NULL UNIQUE,  -- SHA256 of lowercase RSN
    game_mode     TEXT NOT NULL DEFAULT 'CASUAL',  -- CASUAL or NUZLOCKE
    mode_locked   BOOLEAN DEFAULT FALSE,
    locked_at     TIMESTAMP,
    total_points  INTEGER DEFAULT 0,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_players_rsn_hash ON players(rsn_hash);
CREATE INDEX idx_players_points ON players(total_points DESC);
```

### Unlocked Regions Table
```sql
CREATE TABLE unlocked_regions (
    player_id   INTEGER REFERENCES players(id),
    region_id   INTEGER NOT NULL,
    unlocked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_id, region_id)
);
```

### Completed Tasks Table
```sql
CREATE TABLE completed_tasks (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id    INTEGER REFERENCES players(id),
    task_id      TEXT NOT NULL,
    region_id    INTEGER,
    points       INTEGER DEFAULT 0,
    completed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    verification TEXT,  -- JSON blob with kill data, timestamps, etc.
    UNIQUE(player_id, task_id)
);

CREATE INDEX idx_completed_player ON completed_tasks(player_id);
```

### Kill Reports Table (for verification/anti-cheat)
```sql
CREATE TABLE kill_reports (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id       INTEGER REFERENCES players(id),
    task_id         TEXT NOT NULL,
    npc_id          INTEGER NOT NULL,
    npc_name        TEXT,
    region_id       INTEGER,
    game_tick       INTEGER,
    damage_dealt    INTEGER,
    kill_time_ticks INTEGER,
    equipment_ids   TEXT,  -- JSON array
    loot_received   TEXT,  -- JSON array
    reported_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    verified        BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_kills_player ON kill_reports(player_id);
```

### Player Sessions Table (for online tracking)
```sql
CREATE TABLE player_sessions (
    player_id     INTEGER PRIMARY KEY REFERENCES players(id),
    world         INTEGER,
    region_id     INTEGER,
    is_visible    BOOLEAN DEFAULT TRUE,  -- Player's privacy preference
    last_heartbeat TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sessions_heartbeat ON player_sessions(last_heartbeat);
CREATE INDEX idx_sessions_world ON player_sessions(world);
```

---

## API Endpoints

### Authentication
All requests include header: `X-API-Key: {player_api_key}`
API key is generated per-player on first registration.

---

### 1. Player Registration / Login

**POST /api/player/login**

Called when player logs into game. Creates account if new, returns existing data if known.

Request:
```json
{
    "rsn": "SeaShantyBoy",
    "rsn_hash": "3f6294cc5e62a8a6..."
}
```

Response (new player):
```json
{
    "status": "created",
    "player": {
        "rsn": "SeaShantyBoy",
        "game_mode": null,
        "mode_locked": false,
        "total_points": 0,
        "unlocked_regions": [12850],
        "completed_tasks": []
    },
    "api_key": "cb_xxxxxxxxxxxxxxxxxxxx"
}
```

Response (existing player):
```json
{
    "status": "ok",
    "player": {
        "rsn": "SeaShantyBoy",
        "game_mode": "CASUAL",
        "mode_locked": true,
        "locked_at": "2026-01-11T08:46:10Z",
        "total_points": 1250,
        "unlocked_regions": [12850, 12851, 12595],
        "completed_tasks": ["defeat_mugger_fast", "obtain_bones", ...]
    }
}
```

---

### 2. Lock Game Mode

**POST /api/player/lock-mode**

Permanently locks player to a game mode. Cannot be undone (except by admin).

Request:
```json
{
    "game_mode": "NUZLOCKE"
}
```

Response:
```json
{
    "status": "ok",
    "game_mode": "NUZLOCKE",
    "mode_locked": true,
    "locked_at": "2026-01-11T08:46:10Z",
    "message": "Game mode permanently locked to Nuzlocke"
}
```

Error (already locked):
```json
{
    "status": "error",
    "error": "MODE_ALREADY_LOCKED",
    "message": "Game mode is already locked to CASUAL"
}
```

---

### 3. Report Task Completion

**POST /api/task/complete**

Called when player completes a task.

Request:
```json
{
    "task_id": "defeat_mugger_fast",
    "region_id": 12596,
    "points": 1,
    "verification": {
        "npc_id": 513,
        "npc_name": "Mugger",
        "kill_time_ticks": 6,
        "damage_dealt": 45,
        "equipment_ids": [1277, 1173],
        "game_tick": 1234567,
        "timestamp": 1736610370000
    }
}
```

Response:
```json
{
    "status": "ok",
    "task_id": "defeat_mugger_fast",
    "points_earned": 1,
    "total_points": 1251,
    "new_rank": 142
}
```

---

### 4. Unlock Region

**POST /api/region/unlock**

Called when player unlocks a new region.

Request:
```json
{
    "region_id": 12851,
    "points_spent": 100,
    "adjacent_to": 12850
}
```

Response:
```json
{
    "status": "ok",
    "region_id": 12851,
    "total_points": 1151,
    "unlocked_regions": [12850, 12851]
}
```

---

### 5. Get Leaderboard

**GET /api/leaderboard?mode=NUZLOCKE&limit=100&offset=0**

Returns top players for a game mode.

Response:
```json
{
    "mode": "NUZLOCKE",
    "total_players": 5432,
    "leaderboard": [
        {
            "rank": 1,
            "rsn": "ChunkGod",
            "total_points": 15420,
            "regions_unlocked": 87,
            "tasks_completed": 312
        },
        {
            "rank": 2,
            "rsn": "IronBlazer",
            "total_points": 14890,
            "regions_unlocked": 82,
            "tasks_completed": 298
        }
    ]
}
```

---

### 6. Get Player Rank

**GET /api/player/rank**

Returns current player's rank and nearby players.

Response:
```json
{
    "player": {
        "rsn": "SeaShantyBoy",
        "rank": 142,
        "total_points": 1251,
        "percentile": 97.3
    },
    "nearby": [
        {"rank": 140, "rsn": "ChunkFan99", "total_points": 1260},
        {"rank": 141, "rsn": "BlazerBob", "total_points": 1255},
        {"rank": 142, "rsn": "SeaShantyBoy", "total_points": 1251},
        {"rank": 143, "rsn": "TaskMaster", "total_points": 1248},
        {"rank": 144, "rsn": "RegionRunner", "total_points": 1245}
    ]
}
```

---

### 7. Sync Full State (Recovery)

**GET /api/player/sync**

Returns complete player state. Used on login or to recover from corrupted local data.

Response:
```json
{
    "player": {
        "rsn": "SeaShantyBoy",
        "game_mode": "CASUAL",
        "mode_locked": true,
        "total_points": 1251
    },
    "unlocked_regions": [12850, 12851, 12595, 12596],
    "completed_tasks": [
        {"task_id": "defeat_mugger_fast", "completed_at": "2026-01-10T..."},
        {"task_id": "obtain_bones", "completed_at": "2026-01-10T..."}
    ],
    "current_task": {
        "task_id": "kill_goblins",
        "progress": 3,
        "target": 10
    }
}
```

---

## Player Discovery Endpoints

These endpoints enable players to see other ChunkBlazer players in-game.

### 8. Send Heartbeat (Keep Online)

**POST /api/player/heartbeat**

Called every 30-60 seconds while player is logged in. Updates their online status.

Request:
```json
{
    "world": 420,
    "region_id": 12850,
    "is_visible": true
}
```

Response:
```json
{
    "status": "ok",
    "online_count": 142
}
```

---

### 9. Get Online Players

**GET /api/players/online?world=420**

Returns list of ChunkBlazer players currently online. Can filter by world.

Response:
```json
{
    "players": [
        {
            "rsn": "ChunkGod",
            "game_mode": "NUZLOCKE",
            "total_points": 15420,
            "rank": 1,
            "world": 420,
            "region_id": 12850
        },
        {
            "rsn": "IronBlazer",
            "game_mode": "NUZLOCKE",
            "total_points": 8320,
            "rank": 47,
            "world": 420,
            "region_id": 12596
        }
    ],
    "total_online": 142
}
```

---

### 10. Get Players on Same World

**GET /api/players/world/{worldId}**

Returns only players on a specific world (more efficient for overlay rendering).

Response:
```json
{
    "world": 420,
    "players": [
        {
            "rsn": "ChunkGod",
            "game_mode": "NUZLOCKE",
            "total_points": 15420,
            "current_task": "Defeat Elvarg"
        }
    ]
}
```

---

### 11. Set Visibility Preference

**POST /api/player/visibility**

Allows player to opt-out of being visible to others.

Request:
```json
{
    "is_visible": false
}
```

Response:
```json
{
    "status": "ok",
    "is_visible": false,
    "message": "You are now hidden from other players"
}
```

---

### 12. Go Offline

**POST /api/player/offline**

Called when player logs out or closes client. Removes them from online list.

Response:
```json
{
    "status": "ok"
}
```

---

## Cloudflare Workers Implementation (Example)

```javascript
// worker.js - Cloudflare Worker with D1 Database

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        const path = url.pathname;

        // CORS headers
        const headers = {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*'
        };

        try {
            // Route handling
            if (path === '/api/player/login' && request.method === 'POST') {
                return handleLogin(request, env, headers);
            }
            if (path === '/api/player/lock-mode' && request.method === 'POST') {
                return handleLockMode(request, env, headers);
            }
            if (path === '/api/task/complete' && request.method === 'POST') {
                return handleTaskComplete(request, env, headers);
            }
            if (path === '/api/leaderboard' && request.method === 'GET') {
                return handleLeaderboard(request, env, headers);
            }

            return new Response(JSON.stringify({error: 'Not found'}), {
                status: 404, headers
            });
        } catch (err) {
            return new Response(JSON.stringify({error: err.message}), {
                status: 500, headers
            });
        }
    }
};

async function handleLogin(request, env, headers) {
    const body = await request.json();
    const { rsn, rsn_hash } = body;

    // Check if player exists
    let player = await env.DB.prepare(
        'SELECT * FROM players WHERE rsn_hash = ?'
    ).bind(rsn_hash).first();

    if (!player) {
        // Create new player
        const apiKey = 'cb_' + crypto.randomUUID().replace(/-/g, '');

        await env.DB.prepare(`
            INSERT INTO players (rsn, rsn_hash, api_key)
            VALUES (?, ?, ?)
        `).bind(rsn, rsn_hash, apiKey).run();

        // Add default starting region
        const newPlayer = await env.DB.prepare(
            'SELECT id FROM players WHERE rsn_hash = ?'
        ).bind(rsn_hash).first();

        await env.DB.prepare(`
            INSERT INTO unlocked_regions (player_id, region_id)
            VALUES (?, 12850)
        `).bind(newPlayer.id).run();

        return new Response(JSON.stringify({
            status: 'created',
            player: {
                rsn,
                game_mode: null,
                mode_locked: false,
                total_points: 0,
                unlocked_regions: [12850],
                completed_tasks: []
            },
            api_key: apiKey
        }), { headers });
    }

    // Existing player - fetch full state
    const regions = await env.DB.prepare(`
        SELECT region_id FROM unlocked_regions WHERE player_id = ?
    `).bind(player.id).all();

    const tasks = await env.DB.prepare(`
        SELECT task_id FROM completed_tasks WHERE player_id = ?
    `).bind(player.id).all();

    // Update last seen
    await env.DB.prepare(`
        UPDATE players SET last_seen = CURRENT_TIMESTAMP WHERE id = ?
    `).bind(player.id).run();

    return new Response(JSON.stringify({
        status: 'ok',
        player: {
            rsn: player.rsn,
            game_mode: player.game_mode,
            mode_locked: player.mode_locked,
            locked_at: player.locked_at,
            total_points: player.total_points,
            unlocked_regions: regions.results.map(r => r.region_id),
            completed_tasks: tasks.results.map(t => t.task_id)
        }
    }), { headers });
}

async function handleLockMode(request, env, headers) {
    const apiKey = request.headers.get('X-API-Key');
    const body = await request.json();
    const { game_mode } = body;

    // Validate API key and get player
    const player = await env.DB.prepare(
        'SELECT * FROM players WHERE api_key = ?'
    ).bind(apiKey).first();

    if (!player) {
        return new Response(JSON.stringify({
            status: 'error',
            error: 'INVALID_API_KEY'
        }), { status: 401, headers });
    }

    if (player.mode_locked) {
        return new Response(JSON.stringify({
            status: 'error',
            error: 'MODE_ALREADY_LOCKED',
            message: `Game mode is already locked to ${player.game_mode}`
        }), { status: 400, headers });
    }

    // Lock the mode
    await env.DB.prepare(`
        UPDATE players
        SET game_mode = ?, mode_locked = TRUE, locked_at = CURRENT_TIMESTAMP
        WHERE id = ?
    `).bind(game_mode, player.id).run();

    return new Response(JSON.stringify({
        status: 'ok',
        game_mode,
        mode_locked: true,
        locked_at: new Date().toISOString(),
        message: `Game mode permanently locked to ${game_mode}`
    }), { headers });
}

async function handleTaskComplete(request, env, headers) {
    const apiKey = request.headers.get('X-API-Key');
    const body = await request.json();
    const { task_id, region_id, points, verification } = body;

    const player = await env.DB.prepare(
        'SELECT * FROM players WHERE api_key = ?'
    ).bind(apiKey).first();

    if (!player) {
        return new Response(JSON.stringify({
            status: 'error',
            error: 'INVALID_API_KEY'
        }), { status: 401, headers });
    }

    // Check if already completed
    const existing = await env.DB.prepare(
        'SELECT id FROM completed_tasks WHERE player_id = ? AND task_id = ?'
    ).bind(player.id, task_id).first();

    if (existing) {
        return new Response(JSON.stringify({
            status: 'error',
            error: 'TASK_ALREADY_COMPLETED'
        }), { status: 400, headers });
    }

    // Record completion
    await env.DB.prepare(`
        INSERT INTO completed_tasks (player_id, task_id, region_id, points, verification)
        VALUES (?, ?, ?, ?, ?)
    `).bind(player.id, task_id, region_id, points, JSON.stringify(verification)).run();

    // Update total points
    const newPoints = player.total_points + points;
    await env.DB.prepare(`
        UPDATE players SET total_points = ? WHERE id = ?
    `).bind(newPoints, player.id).run();

    // Calculate new rank
    const rankResult = await env.DB.prepare(`
        SELECT COUNT(*) + 1 as rank FROM players
        WHERE total_points > ? AND game_mode = ?
    `).bind(newPoints, player.game_mode).first();

    return new Response(JSON.stringify({
        status: 'ok',
        task_id,
        points_earned: points,
        total_points: newPoints,
        new_rank: rankResult.rank
    }), { headers });
}

async function handleLeaderboard(request, env, headers) {
    const url = new URL(request.url);
    const mode = url.searchParams.get('mode') || 'NUZLOCKE';
    const limit = parseInt(url.searchParams.get('limit') || '100');
    const offset = parseInt(url.searchParams.get('offset') || '0');

    const players = await env.DB.prepare(`
        SELECT rsn, total_points,
            (SELECT COUNT(*) FROM unlocked_regions WHERE player_id = players.id) as regions_unlocked,
            (SELECT COUNT(*) FROM completed_tasks WHERE player_id = players.id) as tasks_completed
        FROM players
        WHERE game_mode = ? AND mode_locked = TRUE
        ORDER BY total_points DESC
        LIMIT ? OFFSET ?
    `).bind(mode, limit, offset).all();

    const totalResult = await env.DB.prepare(`
        SELECT COUNT(*) as total FROM players WHERE game_mode = ? AND mode_locked = TRUE
    `).bind(mode).first();

    return new Response(JSON.stringify({
        mode,
        total_players: totalResult.total,
        leaderboard: players.results.map((p, i) => ({
            rank: offset + i + 1,
            rsn: p.rsn,
            total_points: p.total_points,
            regions_unlocked: p.regions_unlocked,
            tasks_completed: p.tasks_completed
        }))
    }), { headers });
}
```

---

## Deployment (Cloudflare Workers)

1. Install Wrangler CLI:
```bash
npm install -g wrangler
```

2. Create project:
```bash
wrangler init chunkblazer-api
```

3. Create D1 database:
```bash
wrangler d1 create chunkblazer-db
```

4. Add to wrangler.toml:
```toml
[[d1_databases]]
binding = "DB"
database_name = "chunkblazer-db"
database_id = "xxxxx-xxxx-xxxx-xxxx"
```

5. Run migrations:
```bash
wrangler d1 execute chunkblazer-db --file=./schema.sql
```

6. Deploy:
```bash
wrangler deploy
```

Your API will be live at: `https://chunkblazer-api.{your-subdomain}.workers.dev`

---

## Cost Estimate

| Players | Requests/month | D1 Reads | Cost |
|---------|---------------|----------|------|
| 1,000   | ~500k         | ~2M      | Free |
| 10,000  | ~5M           | ~20M     | ~$5  |
| 100,000 | ~50M          | ~200M    | ~$25 |

The free tier covers up to ~100k requests/day, which handles ~5-10k active players easily.
