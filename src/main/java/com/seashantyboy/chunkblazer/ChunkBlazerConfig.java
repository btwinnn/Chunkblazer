package com.seashantyboy.chunkblazer;

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

    @ConfigSection(
        name = "Display",
        description = "Display settings",
        position = 1
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
        description = "Highlight locked/unlocked chunk borders on the map",
        section = displaySection,
        position = 1
    )
    default boolean showChunkBorders()
    {
        return true;
    }
}
