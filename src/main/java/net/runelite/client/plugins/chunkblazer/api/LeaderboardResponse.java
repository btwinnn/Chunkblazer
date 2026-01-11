package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Response containing leaderboard data.
 */
@Data
public class LeaderboardResponse
{
	/**
	 * The game mode this leaderboard is for
	 */
	private String mode;

	/**
	 * Total number of players in this mode
	 */
	@SerializedName("total_players")
	private int totalPlayers;

	/**
	 * The leaderboard entries
	 */
	private List<LeaderboardEntry> leaderboard = new ArrayList<>();

	/**
	 * A single leaderboard entry.
	 */
	@Data
	public static class LeaderboardEntry
	{
		/**
		 * Player's rank (1-indexed)
		 */
		private int rank;

		/**
		 * Player's RuneScape name
		 */
		private String rsn;

		/**
		 * Total points earned
		 */
		@SerializedName("total_points")
		private int totalPoints;

		/**
		 * Number of regions unlocked
		 */
		@SerializedName("regions_unlocked")
		private int regionsUnlocked;

		/**
		 * Number of tasks completed
		 */
		@SerializedName("tasks_completed")
		private int tasksCompleted;
	}

	/**
	 * Create an empty response for offline mode.
	 */
	public static LeaderboardResponse empty(String mode)
	{
		LeaderboardResponse response = new LeaderboardResponse();
		response.setMode(mode);
		response.setTotalPlayers(0);
		return response;
	}
}
