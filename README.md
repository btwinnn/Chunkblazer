# ChunkBlazer

A Nuzlocke-style chunk unlocking plugin for Old School RuneScape (OSRS) via RuneLite.

## Features

- **RNG Task Assignment**: Get random tasks based on your unlocked regions
- **Chunk Unlocking**: Complete tasks to unlock neighboring 64x64 map regions
- **Two Game Modes**:
  - **Casual**: Play on any account, no leaderboard tracking
  - **Full Nuzlocke**: Start as level 3 in Lumbridge, eligible for leaderboards
- **Sidebar Panel**: View current task, region info, and dev controls
- **Task Categories**: Combat, Skilling, Obtain items, Equip items, and more

## Installation

### For Development (RuneLite Source)

1. Clone the RuneLite repository
2. Copy the contents of `src/` into RuneLite's `runelite-client/src/`
3. Build RuneLite: `mvn install -DskipTests`
4. Run: `mvn -pl runelite-client exec:java`

### File Structure

```
src/
├── main/
│   ├── java/com/seashantyboy/chunkblazer/
│   │   ├── ChunkBlazerPlugin.java      # Main plugin
│   │   ├── ChunkBlazerPanel.java       # Sidebar UI
│   │   ├── ChunkBlazerConfig.java      # Configuration
│   │   ├── GameMode.java               # Game mode enum
│   │   ├── NuzlockeTask.java           # Task data model
│   │   ├── NuzlockeChunk.java          # Region data model
│   │   ├── RequiredItem.java           # Item requirement
│   │   ├── TargetNpc.java              # NPC target data
│   │   └── TaskConstraints.java        # Task constraints
│   └── resources/com/seashantyboy/chunkblazer/
│       └── Starter_Area_Tasks.JSON     # Task definitions
```

## Usage

1. Enable the ChunkBlazer plugin in RuneLite
2. Click the orange ChunkBlazer icon in the sidebar
3. Select your game mode (one-time choice per account)
4. Complete tasks to unlock new regions!

## Development

### Dev Controls

The sidebar panel includes developer controls for testing:
- **Complete Task**: Instantly complete the current task
- **Reroll Task**: Get a new random task

## License

This project is for educational and personal use with RuneLite.
