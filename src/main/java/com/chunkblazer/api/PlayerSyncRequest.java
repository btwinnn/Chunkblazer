package com.chunkblazer.api;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

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

	/**
	 * Points spent unlocking chunks. Mirrored to the server purely so it survives
	 * a reinstall / new profile — the server does not spend anything itself. Its
	 * total_points remains lifetime EARNED; the spendable balance is derived on
	 * the client as earned minus this.
	 */
	private int pointsSpent;

	/** All task IDs the player has completed (overwrite-sync on server). */
	private List<String> completedTasks;

	/**
	 * The per-region task roll, sent verbatim as the plugin's config string
	 * ("regionId:task1,task2|regionId2:task3"). The server stores it as an opaque
	 * blob (it grants nothing — points and completions are verified separately) so
	 * the roll and the face-down card state survive a profile switch / reinstall,
	 * instead of being regenerated en masse on the next login.
	 */
	private String regionRolledTasks;

	/** Still-face-down reveal cards, sent verbatim ("task1,task2"). Round-tripped with {@link #regionRolledTasks}. */
	private String unrevealedTasks;

	/**
	 * Boss/raid keys the player has completed at least once (e.g. "toa"). Additive
	 * on the server (never a wholesale replace); it recomputes earned Boss Tokens
	 * from the union. See docs/BOSS-CHUNKS.md.
	 */
	private List<String> bossCompletions;

	/**
	 * Declares that this sync is MEANT to destroy progress.
	 *
	 * <p>The server refuses a sync that drops a large share of a player's chunks
	 * or tasks, because sync is a wholesale overwrite and every wipe so far has
	 * been a client bug the server executed without question. A deliberate reset
	 * is indistinguishable from that bug by shape alone — so it has to say so.
	 *
	 * <p>Only ever set by an operation the PLAYER asked for. Never set it to get
	 * a rejected sync through: the rejection is the guard working.
	 */
	private boolean intentionalReset;

	/** Client timestamp */
	private long timestamp;

	/** Client version */
	private String clientVersion;
}
