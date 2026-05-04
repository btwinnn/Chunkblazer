package net.runelite.client.plugins.chunkblazer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("chunkblazer")
public interface ChunkBlazerConfig extends Config
{
	@ConfigSection(
		name = "General",
		description = "General plugin settings",
		position = 0
	)
	String generalSection = "general";

	@ConfigItem(
		keyName = "unlockedChunks",
		name = "Unlocked Regions",
		description = "Comma-separated list of unlocked region IDs",
		section = generalSection,
		position = 0
	)
	default String unlockedChunks()
	{
		return "12850";
	}

	@ConfigItem(
		keyName = "gameMode",
		name = "Game Mode",
		description = "Current game mode (Casual or Nuzlocke)",
		section = generalSection,
		position = 1
	)
	default GameMode gameMode()
	{
		return GameMode.CASUAL;
	}

	@ConfigItem(
		keyName = "accountModeHash",
		name = "Account Mode Hash",
		description = "Stores the locked game mode for this account (RSN hash)",
		section = generalSection,
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
		section = generalSection,
		position = 3,
		hidden = true
	)
	default String completedTasks()
	{
		return "";
	}

	@ConfigItem(
		keyName = "assignedTasks",
		name = "Assigned Tasks",
		description = "Comma-separated list of all tasks ever assigned (cannot be reassigned)",
		section = generalSection,
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
		section = generalSection,
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
		section = generalSection,
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
		section = generalSection,
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
		section = generalSection,
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
		section = generalSection,
		position = 7,
		hidden = true
	)
	default int totalPoints()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "taskProgressData",
		name = "Task Progress Data",
		description = "Stores progress for all active tasks (format: taskId:progress,taskId2:progress2)",
		section = generalSection,
		position = 8,
		hidden = true
	)
	default String taskProgressData()
	{
		return "";
	}

	@ConfigSection(
		name = "API",
		description = "Server API settings",
		position = 1
	)
	String apiSection = "api";

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API Base URL",
		description = "Base URL for the ChunkBlazer verification server",
		section = apiSection,
		position = 0
	)
	default String apiBaseUrl()
	{
		return "https://api.chunkblazer.com";
	}

	@ConfigItem(
		keyName = "apiEnabled",
		name = "Enable API Verification",
		description = "Enable server-side task verification (requires API key)",
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
		secret = true
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
		keyName = "showChunkBorders",
		name = "Show Chunk Borders",
		description = "Highlight locked/unlocked chunk borders on the world map",
		section = displaySection,
		position = 1
	)
	default boolean showChunkBorders()
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
		description = "Automatically unlock regions when you walk into them. Requires 'Free Auto-Unlock' OR enough points for adjacent regions.",
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
		keyName = "visibleToOthers",
		name = "Visible to Others",
		description = "Allow other ChunkBlazer players to see you in-game. Disable for privacy.",
		section = playerDiscoverySection,
		position = 2
	)
	default boolean visibleToOthers()
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
}
