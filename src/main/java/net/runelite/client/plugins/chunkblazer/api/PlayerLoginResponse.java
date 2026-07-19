package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import net.runelite.client.plugins.chunkblazer.GameMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Response from the server when a player logs in.
 * Contains the player's full state including mode, points, unlocked regions, etc.
 */
@Data
public class PlayerLoginResponse
{
	/**
	 * Status of the login: "ok", "created", or "error"
	 */
	private String status;

	/**
	 * Error code if status is "error"
	 */
	private String error;

	/**
	 * Human-readable message
	 */
	private String message;

	/**
	 * The player's data
	 */
	private PlayerData player;

	/**
	 * API key for this player (only returned on first registration)
	 */
	@SerializedName("api_key")
	private String apiKey;

	/**
	 * Check if this response indicates a new player was created.
	 */
	public boolean isNewPlayer()
	{
		return "created".equals(status);
	}

	/**
	 * Check if the login was successful.
	 */
	public boolean isSuccess()
	{
		return "ok".equals(status) || "created".equals(status);
	}

	/**
	 * Check if the player's game mode is locked.
	 */
	public boolean isModeLocked()
	{
		return player != null && player.isModeLocked();
	}

	/**
	 * Get the player's game mode, or null if not set.
	 */
	public GameMode getGameMode()
	{
		if (player == null || player.getGameMode() == null)
		{
			return null;
		}
		try
		{
			return GameMode.valueOf(player.getGameMode());
		}
		catch (IllegalArgumentException e)
		{
			return GameMode.CASUAL;
		}
	}

	/**
	 * Create an offline/error response for when API is disabled or unreachable.
	 */
	public static PlayerLoginResponse offline()
	{
		PlayerLoginResponse response = new PlayerLoginResponse();
		response.setStatus("offline");
		response.setMessage("API is disabled or unreachable");
		return response;
	}

	/**
	 * Player data nested object.
	 */
	@Data
	public static class PlayerData
	{
		private String rsn;

		@SerializedName("game_mode")
		private String gameMode;

		@SerializedName("mode_locked")
		private boolean modeLocked;

		@SerializedName("locked_at")
		private String lockedAt;

		@SerializedName("total_points")
		private int totalPoints;

		@SerializedName("unlocked_regions")
		private List<Integer> unlockedRegions = new ArrayList<>();

		@SerializedName("completed_tasks")
		private List<String> completedTasks = new ArrayList<>();

		/**
		 * Whether this player has completed RSN ownership verification via the
		 * in-game chat handshake. If false, the plugin should kick off the
		 * verification flow on first login.
		 */
		private boolean verified;

		@SerializedName("verified_at")
		private String verifiedAt;

		/**
		 * Whether the server authorizes this account to use the Dev Controls
		 * panel. Server-issued on purpose: those tools write directly to local
		 * task state, and a dev-granted task is a real catalog task, so the
		 * server's Tier-0 points recompute agrees with the client and never
		 * flags it. Gating this client-side would leave every player an
		 * unobservable self-grant. Defaults false, so an old or malformed
		 * response denies the tools.
		 */
		@SerializedName("is_dev")
		private boolean isDev;
	}
}
