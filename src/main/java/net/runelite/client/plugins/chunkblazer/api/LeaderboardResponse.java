package net.runelite.client.plugins.chunkblazer.api;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Response containing leaderboard data.
 *
 * <p>Mirrors the server's {@code GET /api/leaderboards/{mode}/{accountType}/{metric}}
 * payload exactly. Each board is scoped to one game mode, one account type, and one
 * ranking metric, so Hardcore Ironmen, regular Ironmen, and UIM are distinct boards.
 * Field names are camelCase to match the server JSON under RuneLite's default Gson.
 */
@Data
public class LeaderboardResponse
{
	/** The game mode this board is for (CASUAL or NUZLOCKE). */
	private String mode;

	/** The account-type bucket this board is for (STANDARD, IRONMAN, HCIM, UIM, SKILLER_3). */
	private String accountType;

	/** The metric this board is ranked by (e.g. total_points, overall_xp, attack_xp). */
	private String metric;

	/** Page size echoed back by the server. */
	private int limit;

	/** Page offset echoed back by the server. */
	private int offset;

	/** Total players in this (mode, accountType) bucket, across all pages. */
	private long total;

	/** The leaderboard entries for the requested page. */
	private List<LeaderboardEntry> entries = new ArrayList<>();

	/** Server timestamp (RFC3339) for when the response was assembled. */
	private String fetchedAt;

	/**
	 * A single leaderboard entry.
	 */
	@Data
	public static class LeaderboardEntry
	{
		/** Player's rank within this board (1-indexed). */
		private long rank;

		/** Player's RuneScape name. */
		private String rsn;

		/**
		 * The value of the ranked metric for this player. Null when the player
		 * has no hi-score snapshot yet (server sorts these NULLS LAST).
		 */
		private Long value;

		/** Overall level from the latest hi-score snapshot; null if none yet. */
		private Integer overallLevel;

		/** Overall XP from the latest hi-score snapshot; null if none yet. */
		private Long overallXP;

		/** ChunkBlazer total points. */
		private int totalPoints;

		/** Number of chunks unlocked. */
		private int chunksUnlocked;

		/** Number of tasks completed. */
		private int tasksCompleted;

		/**
		 * When the player's hi-score snapshot was taken (RFC3339). Omitted by the
		 * server (and thus null here) when the player has no snapshot yet.
		 */
		private String snapshotAt;
	}

	/**
	 * Create an empty response for offline mode / error fallback.
	 */
	public static LeaderboardResponse empty(String mode, String accountType, String metric)
	{
		LeaderboardResponse response = new LeaderboardResponse();
		response.setMode(mode);
		response.setAccountType(accountType);
		response.setMetric(metric);
		return response;
	}
}
