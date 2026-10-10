/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Units;

/**
 * Settings, grouped by what they affect: Server Sync, Tasks, World Map, Minimap,
 * In Game, Sounds, Chat Messages and Region Unlock.
 *
 * Only the names, descriptions, sections and positions are about presentation. Every
 * keyName is unchanged, so players' saved choices carry across any reshuffle here.
 */
@ConfigGroup("chunkblazer")
public interface ChunkBlazerConfig extends Config
{
	/** Plugin Hub's required wording for a 3rd-party-server toggle. */
	String SERVER_SYNC_WARNING = "This feature submits your IP address to a 3rd-party server "
		+ "not controlled or verified by RuneLite developers";

	// ── Internal state (hidden; managed by the plugin) ───────────────────

	@ConfigItem(
		keyName = "unlockedChunks",
		name = "Unlocked Regions",
		description = "Internal: the player's unlocked region IDs (managed by the plugin, not hand-editable)",
		position = 0,
		hidden = true
	)
	default String unlockedChunks()
	{
		return "12850";
	}

	@ConfigItem(
		keyName = "gameMode",
		name = "Game Mode",
		description = "Current game mode (Casual or Competitive)",
		position = 1,
		hidden = true
	)
	default GameMode gameMode()
	{
		return GameMode.CASUAL;
	}

	@ConfigItem(
		keyName = "accountModeHash",
		name = "Account Mode Hash",
		description = "Stores the locked game mode for this account (RSN hash)",
		position = 2,
		hidden = true
	)
	default String accountModeHash()
	{
		return "";
	}

	@ConfigItem(
		keyName = "completedTasks",
		name = "Completed Tasks",
		description = "Comma-separated list of completed task IDs",
		position = 3,
		hidden = true
	)
	default String completedTasks()
	{
		return "";
	}

	@ConfigItem(
		keyName = "pointsSpent",
		name = "Points Spent",
		description = "Running total of points spent unlocking chunks. Points EARNED is derived "
			+ "from the completed task list; the spendable balance is earned minus this.",
		position = 4,
		hidden = true
	)
	default int pointsSpent()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "progressionBaseline",
		name = "Progression Baseline",
		description = "Per-skill levels captured when this account was first seen. "
			+ "Progression tasks only pay for levels gained after this point.",
		position = 4,
		hidden = true
	)
	default String progressionBaseline()
	{
		return "";
	}

	@ConfigItem(
		keyName = "assignedTasks",
		name = "Assigned Tasks",
		description = "Comma-separated list of all tasks ever assigned (cannot be reassigned)",
		position = 4,
		hidden = true
	)
	default String assignedTasks()
	{
		return "";
	}

	@ConfigItem(
		keyName = "regionRolledTasks",
		name = "Region Rolled Tasks",
		description = "Stores the 4-5 tasks rolled per region (format: regionId:task1,task2|regionId2:task3,task4)",
		position = 5,
		hidden = true
	)
	default String regionRolledTasks()
	{
		return "";
	}

	@ConfigItem(
		keyName = "unrevealedTasks",
		name = "Unrevealed Tasks",
		description = "Rolled tasks still waiting behind a face-down card (comma-separated task IDs). "
			+ "These are NOT active and are not tracked until the card is flipped.",
		position = 6,
		hidden = true
	)
	default String unrevealedTasks()
	{
		return "";
	}

	@ConfigItem(
		keyName = "currentTaskId",
		name = "Current Task ID",
		description = "The currently active task ID",
		position = 4,
		hidden = true
	)
	default String currentTaskId()
	{
		return "";
	}

	@ConfigItem(
		keyName = "currentTaskQuantity",
		name = "Current Task Quantity",
		description = "Target quantity for current task",
		position = 5,
		hidden = true
	)
	default int currentTaskQuantity()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "currentTaskProgress",
		name = "Current Task Progress",
		description = "Progress towards current task",
		position = 6,
		hidden = true
	)
	default int currentTaskProgress()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "totalPoints",
		name = "Total Points",
		description = "Total points earned from completed tasks",
		position = 7,
		hidden = true
	)
	default int totalPoints()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "bossTokens",
		name = "Boss Tokens",
		description = "Secondary currency spent to unlock boss chunks. New players start with 2.",
		position = 8,
		hidden = true
	)
	default int bossTokens()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "taskProgressData",
		name = "Task Progress Data",
		description = "Stores progress for all active tasks (format: taskId:progress,taskId2:progress2)",
		position = 8,
		hidden = true
	)
	default String taskProgressData()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API Base URL",
		description = "Base URL for the ChunkBlazer verification server",
		position = 0,
		hidden = true
	)
	default String apiBaseUrl()
	{
		return "https://api.chunkblazer.com";
	}

	// ── Server Sync ──────────────────────────────────────────────────────

	@ConfigSection(
		name = "Server Sync",
		description = "Save your progress to chunkblazer.com for cross-device saves and leaderboards",
		position = 0
	)
	String syncSection = "sync";

	/**
	 * Master switch for ALL server communication — login, sync, event reports, and the
	 * catalog/sound fetches all gate on this. OFF by default: nothing is sent, or even
	 * downloaded, until the player turns it on (the plugin runs on the bundled seed
	 * meanwhile).
	 *
	 * A FRESH keyName ("serverSyncEnabled") means no stored value carries over from the
	 * old always-on build, so everyone starts opted-out. The in-panel prompt explains
	 * what enabling gains; PRIVACY.md carries the full data-use disclosure.
	 */
	@ConfigItem(
		keyName = "serverSyncEnabled",
		name = "Enable Server Sync",
		description = "Save your progress to chunkblazer.com for cross-device saves and leaderboards. "
			+ "Nothing is sent until you enable it.",
		warning = SERVER_SYNC_WARNING,
		section = syncSection,
		position = 0
	)
	default boolean apiEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "Sync recovery key",
		description = "Paste a saved key here to restore sync on a new device. Your key is stored per "
			+ "account and is cleared from this box once applied. Keep it private.",
		section = syncSection,
		position = 1,
		secret = true
	)
	default String apiKey()
	{
		return "";
	}

	// ── Tasks ────────────────────────────────────────────────────────────

	@ConfigSection(
		name = "Tasks",
		description = "How new tasks appear, the on-screen task tracker, and task highlighting",
		position = 1
	)
	String taskSection = "tasks";

	@ConfigItem(
		keyName = "showTaskCards",
		name = "Reveal New Tasks as Cards",
		description = "New tasks arrive as face-down cards you flip to reveal",
		section = taskSection,
		position = 0
	)
	default boolean showTaskCards()
	{
		return true;
	}

	@ConfigItem(
		keyName = "taskTrackerStyle",
		name = "Task Box",
		description = "How the task you track is shown in game. Off hides it",
		section = taskSection,
		position = 1
	)
	default TaskTrackerStyle taskTrackerStyle()
	{
		return TaskTrackerStyle.VANI;
	}

	@ConfigItem(
		keyName = "autoTrackTasks",
		name = "Auto-Track Tasks",
		description = "Using an NPC or object a task needs (attack, talk, chop, mine...) tracks that task. "
			+ "A task you picked yourself is kept",
		section = taskSection,
		position = 2
	)
	default boolean autoTrackTasks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSavedTaskTracker",
		name = "Saved Tasks Bubble",
		description = "A bubble that opens your saved tasks over the inventory (Alt + drag to move it)",
		section = taskSection,
		position = 3
	)
	default boolean showSavedTaskTracker()
	{
		return true;
	}

	@Range(min = 50, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "widgetScale",
		name = "Window Scale",
		description = "Size of the task window and the saved tasks bubble",
		section = taskSection,
		position = 3
	)
	default int widgetScale()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "taskRightClickMenu",
		name = "Right-Click Tasks Menu",
		description = "Adds a Tasks submenu when right-clicking NPCs and objects your tasks need",
		section = taskSection,
		position = 4
	)
	default boolean taskRightClickMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightTaskTargets",
		name = "Outline Task Targets",
		description = "Older on/off outline setting, kept so Outlines starts where players left it",
		section = taskSection,
		position = 5,
		hidden = true
	)
	default boolean highlightTaskTargets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "taskOutlineMode",
		name = "Outlines",
		description = "Which task NPCs and objects get an outline: all, saved tasks, ones you can do now, or none",
		section = taskSection,
		position = 5
	)
	default OutlineMode taskOutlineMode()
	{
		return highlightTaskTargets() ? OutlineMode.ALL : OutlineMode.OFF;
	}

	@ConfigItem(
		keyName = "taskHighlightColor",
		name = "Outline Colour",
		description = "Outline colour for task NPCs and objects you can do now",
		section = taskSection,
		position = 6
	)
	default java.awt.Color taskHighlightColor()
	{
		return new java.awt.Color(255, 140, 0);
	}

	@ConfigItem(
		keyName = "taskHighlightUnavailableColor",
		name = "Outline Colour (Level Too Low)",
		description = "Outline colour when you don't have the level for any of that target's tasks",
		section = taskSection,
		position = 7
	)
	default java.awt.Color taskHighlightUnavailableColor()
	{
		return new java.awt.Color(255, 60, 60);
	}

	@ConfigItem(
		keyName = "highlightEquipItems",
		name = "Highlight Task Items",
		description = "Outlines items your tasks need (gear to equip, tools like a knife or tinderbox) "
			+ "in your inventory, bank and shops",
		section = taskSection,
		position = 8
	)
	default boolean highlightEquipItems()
	{
		return true;
	}

	// ── World Map ────────────────────────────────────────────────────────

	@ConfigSection(
		name = "World Map",
		description = "What the world map shows about your chunks",
		position = 2
	)
	String worldMapSection = "worldMap";

	@ConfigItem(
		keyName = "showWorldMapChunks",
		name = "Chunk Borders",
		description = "Draw chunk borders and tints on the world map",
		section = worldMapSection,
		position = 0
	)
	default boolean showWorldMapChunks()
	{
		// Default to the scene toggle's value so splitting this out doesn't change
		// behaviour on upgrade: someone who had "Show Chunk Borders" off keeps the
		// world map clear, and someone who had it on keeps it. Once they set this
		// toggle explicitly, it takes its own stored value.
		return showSceneChunks();
	}

	@ConfigItem(
		keyName = "showChunkCostLabels",
		name = "Chunk Costs",
		description = "Write what each unlockable chunk costs (FREE, points or a Boss Token) inside it",
		section = worldMapSection,
		position = 1
	)
	default boolean showChunkCostLabels()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showChunkLegend",
		name = "Colour Legend",
		description = "Show a key explaining the chunk colours in the corner of the world map",
		section = worldMapSection,
		position = 2
	)
	default boolean showChunkLegend()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showChunkGridLines",
		name = "Lines Between Unlocked Chunks",
		description = "Draw chunk outlines between your unlocked chunks too. Off shows your unlocked area as one connected piece.",
		section = worldMapSection,
		position = 3
	)
	default boolean showChunkGridLines()
	{
		return true;
	}

	@net.runelite.client.config.Alpha
	@ConfigItem(
		keyName = "worldMapLockedColor",
		name = "Locked Chunk Colour",
		description = "Tint over chunks you can't unlock yet (lower the alpha to see more of the map)",
		section = worldMapSection,
		position = 4
	)
	default java.awt.Color worldMapLockedColor()
	{
		return new java.awt.Color(0, 0, 0, 160);
	}

	@net.runelite.client.config.Alpha
	@ConfigItem(
		keyName = "worldMapPaidColor",
		name = "Unlock With Points Colour",
		description = "Tint over chunks you can unlock with points",
		section = worldMapSection,
		position = 5
	)
	default java.awt.Color worldMapPaidColor()
	{
		return new java.awt.Color(255, 215, 0, 110);
	}

	@net.runelite.client.config.Alpha
	@ConfigItem(
		keyName = "worldMapFreeColor",
		name = "Free Chunk Colour",
		description = "Tint over chunks you unlock for free by walking in",
		section = worldMapSection,
		position = 6
	)
	default java.awt.Color worldMapFreeColor()
	{
		return new java.awt.Color(80, 230, 230, 110);
	}

	@net.runelite.client.config.Alpha
	@ConfigItem(
		keyName = "worldMapCharterColor",
		name = "Charter Port Colour",
		description = "Tint over charter port chunks",
		section = worldMapSection,
		position = 7
	)
	default java.awt.Color worldMapCharterColor()
	{
		return new java.awt.Color(90, 150, 255, 110);
	}

	@net.runelite.client.config.Alpha
	@ConfigItem(
		keyName = "worldMapBossColor",
		name = "Boss Chunk Colour",
		description = "Tint over boss chunks (unlocked with a boss token)",
		section = worldMapSection,
		position = 8
	)
	default java.awt.Color worldMapBossColor()
	{
		return new java.awt.Color(200, 110, 255, 110);
	}

	@ConfigItem(
		keyName = "worldMapTasksKey",
		name = "View Chunk Tasks Key",
		description = "Hold this key (Ctrl by default) and click an unlocked chunk on the world map to open the "
			+ "task window on that chunk's tasks",
		section = worldMapSection,
		position = 9
	)
	default Keybind worldMapTasksKey()
	{
		return Keybind.CTRL;
	}

	// ── Minimap ──────────────────────────────────────────────────────────

	@ConfigSection(
		name = "Minimap",
		description = "Chunk borders and orbs around the minimap",
		position = 3
	)
	String minimapSection = "minimap";

	@ConfigItem(
		keyName = "showMinimapChunks",
		name = "Chunk Borders & Tints",
		description = "Show chunk borders and tints on the minimap. Click a neighbouring chunk to unlock it.",
		section = minimapSection,
		position = 0
	)
	default boolean showMinimapChunks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMinimapOrbs",
		name = "Minimap Orbs",
		description = "Show the Boss Token and Points minimap orbs",
		section = minimapSection,
		position = 1
	)
	default boolean showMinimapOrbs()
	{
		return true;
	}

	// ── In Game ──────────────────────────────────────────────────────────

	@ConfigSection(
		name = "In Game",
		description = "What's drawn in the game world and on screen",
		position = 4
	)
	String inGameSection = "inGame";

	@ConfigItem(
		keyName = "showSceneChunks",
		name = "Chunk Borders",
		description = "Draw chunk borders on the ground in the game world",
		section = inGameSection,
		position = 0
	)
	default boolean showSceneChunks()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showChunkWalls",
		name = "Locked Chunk Walls",
		description = "Draw a see-through wall along the border between unlocked and locked chunks",
		section = inGameSection,
		position = 1
	)
	default boolean showChunkWalls()
	{
		return true;
	}

	@net.runelite.client.config.Alpha
	@ConfigItem(
		keyName = "chunkWallColor",
		name = "Wall Colour",
		description = "Colour of the locked chunk walls; transparency sets how see-through they are",
		section = inGameSection,
		position = 2
	)
	default java.awt.Color chunkWallColor()
	{
		return new java.awt.Color(255, 60, 60, 110);
	}

	@ConfigItem(
		keyName = "showChunkNamePopups",
		name = "Chunk Name Banner",
		description = "Show the chunk's name in a small banner at the top of the screen when you walk into a new chunk",
		section = inGameSection,
		position = 3
	)
	default boolean showChunkNamePopups()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTaskCompletionPopup",
		name = "Task Completion Popup",
		description = "Display a popup when you complete a task",
		section = inGameSection,
		position = 4
	)
	default boolean showTaskCompletionPopup()
	{
		return true;
	}

	// ── Sounds ───────────────────────────────────────────────────────────

	@ConfigSection(
		name = "Sounds",
		description = "Task completion sounds and chunk unlock jingles",
		position = 5
	)
	String soundSection = "sounds";

	@ConfigItem(
		keyName = "playTaskCompletionSound",
		name = "Task Completion Sound",
		description = "Play a region-specific sound when you complete a task",
		section = soundSection,
		position = 0
	)
	default boolean playTaskCompletionSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "taskCompletionSoundVolume",
		name = "Task Sound Volume",
		description = "Volume of the task completion sound (0 = silent, 100 = full)",
		section = soundSection,
		position = 1
	)
	@Range(min = 0, max = 100)
	default int taskCompletionSoundVolume()
	{
		// 3% baseline (~-30dB): a deliberately quiet default
		return 3;
	}

	@ConfigItem(
		keyName = "playRegionUnlockSound",
		name = "Region Unlock Jingle",
		description = "Play a region-specific jingle the first time you unlock a chunk",
		section = soundSection,
		position = 2
	)
	default boolean playRegionUnlockSound()
	{
		return true;
	}

	// NOTE: the locked-chunk GPU greyscale settings moved into the standalone
	// "ChunkBlazer GPU" plugin's own config (group "chunkblazergpu") when that
	// plugin was split out of this repo. That plugin reads our unlockedChunks
	// config value by string key — no compile-time coupling in either direction.

	// ── Chat Messages ────────────────────────────────────────────────────

	@ConfigSection(
		name = "Chat Messages",
		description = "Control which task messages appear in chat",
		position = 6
	)
	String chatSection = "chat";

	@ConfigItem(
		keyName = "showChatProgress",
		name = "Show Task Progress",
		description = "Show messages when you make progress on a task",
		section = chatSection,
		position = 0
	)
	default boolean showChatProgress()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showChatSuccess",
		name = "Show Task Success",
		description = "Show messages when you complete a task",
		section = chatSection,
		position = 1
	)
	default boolean showChatSuccess()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showChatFailed",
		name = "Show Task Failed",
		description = "Show messages when a task attempt fails",
		section = chatSection,
		position = 2
	)
	default boolean showChatFailed()
	{
		return true;
	}

	// ── Region Unlock ────────────────────────────────────────────────────

	@ConfigSection(
		name = "Region Unlock",
		description = "Region unlock settings",
		position = 7
	)
	String regionSection = "region";

	@ConfigItem(
		keyName = "showUnlockPopup",
		name = "Show Unlock Popup",
		description = "Show an in-game popup to unlock regions when you walk into them",
		section = regionSection,
		position = 0
	)
	default boolean showUnlockPopup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "worldMapUnlockKey",
		name = "Map Unlock Key",
		description = "Hold this key (Shift by default) and click a neighbouring chunk on the world map to unlock it",
		section = regionSection,
		position = 1
	)
	default Keybind worldMapUnlockKey()
	{
		return Keybind.SHIFT;
	}
}
