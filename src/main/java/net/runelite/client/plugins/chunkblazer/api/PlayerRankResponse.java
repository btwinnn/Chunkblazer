package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Response containing the current player's rank and nearby players.
 */
@Data
public class PlayerRankResponse
{
	/**
	 * The current player's rank info
	 */
	private PlayerRank player;

	/**
	 * Players near the current player's rank
	 */
	private List<NearbyPlayer> nearby = new ArrayList<>();

	/**
	 * Current player's rank details.
	 */
	@Data
	public static class PlayerRank
	{
		private String rsn;
		private int rank;

		@SerializedName("total_points")
		private int totalPoints;

		/**
		 * Percentile (e.g., 97.3 means top 2.7%)
		 */
		private double percentile;
	}

	/**
	 * A nearby player on the leaderboard.
	 */
	@Data
	public static class NearbyPlayer
	{
		private int rank;
		private String rsn;

		@SerializedName("total_points")
		private int totalPoints;
	}

	/**
	 * Create an unranked response for offline mode or new players.
	 */
	public static PlayerRankResponse unranked()
	{
		PlayerRankResponse response = new PlayerRankResponse();
		PlayerRank player = new PlayerRank();
		player.setRank(-1);
		player.setTotalPoints(0);
		player.setPercentile(0);
		response.setPlayer(player);
		return response;
	}
}
