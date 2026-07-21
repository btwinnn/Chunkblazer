package net.runelite.client.plugins.chunkblazer;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("chunkblazer")
public interface ChunkBlazerConfig extends Config
{
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
		keyName = "accountStateOwner",
		name = "Account State Owner",
		description = "RSN hash of the account whose progress is currently stored in this "
			+ "RuneLite profile. Logging in as a different account clears that state so the "
			+ "server can repopulate it for the new account.",
		position = 4,
		hidden = true
	)
	default String accountStateOwner()
	{
		return "";
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

	@ConfigSection(
		name = "Server Sync",
		description = "Saves your progress to the ChunkBlazer server. (See the side-panel \"(?)\" notice for "
			+ "the full list of what's sent.)",
		position = 10
	)
	String apiSection = "api";

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API Base URL",
		description = "Base URL for the ChunkBlazer verification server",
		section = apiSection,
		position = 0,
		hidden = true
	)
	default String apiBaseUrl()
	{
		return "https://api.chunkblazer.com";
	}

	@ConfigItem(
		keyName = "apiEnabled",
		name = "Enable Server Verification",
		description = "We send your character's API data to the server to track and save your progress. "
			+ "Turn this off to play fully offline (leaderboards and player discovery will be unavailable).",
		section = apiSection,
		position = 1
	)
	default boolean apiEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API Key",
		description = "Your ChunkBlazer API key for server verification",
		section = apiSection,
		position = 2,
		secret = true,
		hidden = true
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigSection(
		name = "Display",
		description = "Display settings",
		position = 2
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Task Overlay",
		description = "Show the current task overlay on screen",
		section = displaySection,
		position = 0
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMinimapChunks",
		name = "Show Minimap Chunks",
		description = "Highlight chunk borders on the minimap. Click on neighbor chunks to unlock them.",
		section = displaySection,
		position = 2
	)
	default boolean showMinimapChunks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSceneChunks",
		name = "Show Chunk Borders",
		description = "Draw chunk/region borders on the game scene and world map. Locked chunks get a translucent grey wash.",
		section = displaySection,
		position = 3
	)
	default boolean showSceneChunks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTaskCompletionPopup",
		name = "Show Task Completion Popup",
		description = "Display a popup notification when you complete a task",
		section = displaySection,
		position = 2
	)
	default boolean showTaskCompletionPopup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "playTaskCompletionSound",
		name = "Play Task Completion Sound",
		description = "Play a region-specific sound when you complete a task",
		section = displaySection,
		position = 3
	)
	default boolean playTaskCompletionSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "taskCompletionSoundVolume",
		name = "Task Sound Volume",
		description = "Volume of the task completion sound (0 = silent, 100 = full)",
		section = displaySection,
		position = 4
	)
	@Range(min = 0, max = 100)
	default int taskCompletionSoundVolume()
	{
		// Was hardcoded at 3%, which is effectively inaudible over game audio —
		// the sounds played fine, nobody could hear them.
		return 25;
	}

	@ConfigItem(
		keyName = "playRegionUnlockSound",
		name = "Play Region Unlock Jingle",
		description = "Play a region-specific jingle the first time you unlock a chunk",
		section = displaySection,
		position = 4
	)
	default boolean playRegionUnlockSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBossTokenCounter",
		name = "Show Boss Token Counter",
		description = "Show the Boss Token currency icon + count above the chatbox (bottom-left)",
		section = displaySection,
		position = 5
	)
	default boolean showBossTokenCounter()
	{
		return true;
	}

	// NOTE: the locked-chunk GPU greyscale settings moved into the standalone
	// "ChunkBlazer GPU" plugin's own config (group "chunkblazergpu") when that
	// plugin was split out of this repo. That plugin reads our unlockedChunks
	// config value by string key — no compile-time coupling in either direction.

	@ConfigSection(
		name = "Chat Messages",
		description = "Control which task messages appear in chat",
		position = 4
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

	@ConfigSection(
		name = "Region Unlock",
		description = "Region unlock settings",
		position = 4
	)
	String regionSection = "region";

	@ConfigItem(
		keyName = "autoUnlockRegions",
		name = "Auto-Unlock Regions",
		description = "Master switch for walk-in unlocks. Combine with 'Free Auto-Unlock' for free exploration mode. Walking never spends points — point-cost unlocks always require an explicit click in the side panel, minimap, or world map.",
		section = regionSection,
		position = 0
	)
	default boolean autoUnlockRegions()
	{
		return false;
	}

	@ConfigItem(
		keyName = "autoUnlockFree",
		name = "Free Auto-Unlock",
		description = "Unlock ANY region you walk into without spending points (exploration mode). Enable this to freely explore the map.",
		section = regionSection,
		position = 1
	)
	default boolean autoUnlockFree()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showUnlockPopup",
		name = "Show Unlock Popup",
		description = "Show an in-game popup to unlock regions when you walk into them (when Auto-Unlock is disabled).",
		section = regionSection,
		position = 2
	)
	default boolean showUnlockPopup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "worldMapUnlockKey",
		name = "Map Unlock Key",
		description = "Hold this key (Shift by default) and click a neighbouring chunk on the world map to unlock it.",
		section = regionSection,
		position = 3
	)
	default Keybind worldMapUnlockKey()
	{
		return Keybind.SHIFT;
	}

	@ConfigSection(
		name = "Player Discovery",
		description = "Settings for seeing other ChunkBlazer players",
		position = 5
	)
	String playerDiscoverySection = "playerDiscovery";

	@ConfigItem(
		keyName = "showOtherPlayers",
		name = "Show Other Players",
		description = "Display an icon and info above other ChunkBlazer players in-game",
		section = playerDiscoverySection,
		position = 0
	)
	default boolean showOtherPlayers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showChatIcons",
		name = "Show Chat Icons",
		description = "Display a ChunkBlazer icon next to player names in chat",
		section = playerDiscoverySection,
		position = 1
	)
	default boolean showChatIcons()
	{
		return true;
	}


	@ConfigItem(
		keyName = "showPlayerPoints",
		name = "Show Player Points",
		description = "Display point totals above other players",
		section = playerDiscoverySection,
		position = 3
	)
	default boolean showPlayerPoints()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPlayerRank",
		name = "Show Player Rank",
		description = "Display leaderboard rank above other players",
		section = playerDiscoverySection,
		position = 4
	)
	default boolean showPlayerRank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showMinimapHighlight",
		name = "Highlight on Minimap",
		description = "Mark nearby ChunkBlazer players with a coloured dot on the minimap",
		section = playerDiscoverySection,
		position = 5
	)
	default boolean showMinimapHighlight()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPlayerOutline",
		name = "Outline Players",
		description = "Draw a glowing outline around nearby ChunkBlazer players' models",
		section = playerDiscoverySection,
		position = 6
	)
	default boolean showPlayerOutline()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		// Key bumped from "recognitionColor" to drop stale persisted values (an old
		// default saved a dark blue into existing profiles); this re-applies the
		// flame-orange default for everyone. Still user-customisable.
		keyName = "recognitionColorV2",
		name = "Highlight Colour",
		description = "Colour for the overhead tag, model outline, and minimap dot on other ChunkBlazer players",
		section = playerDiscoverySection,
		position = 7
	)
	default Color recognitionColor()
	{
		return new Color(255, 152, 0); // flame orange (matches the ChunkBlazer theme)
	}
}
