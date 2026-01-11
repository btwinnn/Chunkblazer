package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import net.runelite.client.plugins.chunkblazer.GameMode;

/**
 * Response from the server when locking a player's game mode.
 */
@Data
public class LockModeResponse
{
	/**
	 * Status: "ok" or "error"
	 */
	private String status;

	/**
	 * Error code if status is "error" (e.g., "MODE_ALREADY_LOCKED")
	 */
	private String error;

	/**
	 * Human-readable message
	 */
	private String message;

	/**
	 * The locked game mode
	 */
	@SerializedName("game_mode")
	private String gameMode;

	/**
	 * Whether mode is now locked
	 */
	@SerializedName("mode_locked")
	private boolean modeLocked;

	/**
	 * Timestamp when mode was locked
	 */
	@SerializedName("locked_at")
	private String lockedAt;

	/**
	 * Check if the lock was successful.
	 */
	public boolean isSuccess()
	{
		return "ok".equals(status);
	}

	/**
	 * Check if the mode was already locked.
	 */
	public boolean isAlreadyLocked()
	{
		return "MODE_ALREADY_LOCKED".equals(error);
	}

	/**
	 * Get the GameMode enum value.
	 */
	public GameMode getGameModeEnum()
	{
		if (gameMode == null)
		{
			return null;
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

	/**
	 * Create an offline response for when API is disabled.
	 */
	public static LockModeResponse offline(GameMode mode)
	{
		LockModeResponse response = new LockModeResponse();
		response.setStatus("offline");
		response.setGameMode(mode.name());
		response.setModeLocked(true);
		response.setMessage("Mode locked locally (API offline)");
		return response;
	}

	/**
	 * Create an error response.
	 */
	public static LockModeResponse error(String message)
	{
		LockModeResponse response = new LockModeResponse();
		response.setStatus("error");
		response.setError("CLIENT_ERROR");
		response.setMessage(message);
		return response;
	}
}
