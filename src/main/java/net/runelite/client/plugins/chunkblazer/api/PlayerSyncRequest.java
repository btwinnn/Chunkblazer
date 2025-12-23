package net.runelite.client.plugins.chunkblazer.api;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Request to sync player state with server.
 * Sent on login and periodically to ensure state consistency.
 */
@Data
@Builder
public class PlayerSyncRequest
{
    /** Player RSN hash */
    private String playerHash;

    /** Current RSN (for display, not verification) */
    private String displayName;

    /** Account type (NORMAL, IRONMAN, HARDCORE_IRONMAN, etc.) */
    private String accountType;

    /** ChunkBlazer game mode (CASUAL, NUZLOCKE) */
    private String gameMode;

    /** Combat level */
    private int combatLevel;

    /** Total level */
    private int totalLevel;

    /** All skill levels */
    private Map<String, Integer> skillLevels;

    /** All skill XP */
    private Map<String, Integer> skillXp;

    /** Current region ID */
    private int currentRegionId;

    /** List of unlocked region IDs */
    private List<Integer> unlockedRegions;

    /** Current active task ID */
    private String activeTaskId;

    /** Current task progress */
    private int activeTaskProgress;

    /** Client-side points (for verification) */
    private int clientPoints;

    /** Client timestamp */
    private long timestamp;

    /** Client version */
    private String clientVersion;
}
