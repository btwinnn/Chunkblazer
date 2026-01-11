package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import net.runelite.client.plugins.chunkblazer.GameMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Response containing a list of online ChunkBlazer players.
 */
@Data
public class OnlinePlayersResponse
{
	/**
	 * List of online players
	 */
	private List<OnlinePlayer> players = new ArrayList<>();

	/**
	 * Total number of players online across all worlds
	 */
	@SerializedName("total_online")
	private int totalOnline;

	/**
	 * Create an empty response for offline mode.
	 */
	public static OnlinePlayersResponse empty()
	{
		return new OnlinePlayersResponse();
	}

	/**
	 * Represents a single online player.
	 */
	@Data
	public static class OnlinePlayer
	{
		private String rsn;

		@SerializedName("game_mode")
		private String gameMode;

		@SerializedName("total_points")
		private int totalPoints;

		private int rank;

		private int world;

		@SerializedName("region_id")
		private int regionId;

		@SerializedName("current_task")
		private String currentTask;

		/**
		 * Get the GameMode enum value.
		 */
		public GameMode getGameModeEnum()
		{
			if (gameMode == null)
			{
				return GameMode.CASUAL;
			}
			try
			{
				return GameMode.valueOf(gameMode);
			}
			catch (IllegalArgumentException e)
			{
				return GameMode.CASUAL;
			}
		}
	}
}
