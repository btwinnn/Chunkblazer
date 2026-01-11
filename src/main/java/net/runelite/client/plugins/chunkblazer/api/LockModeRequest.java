package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;

/**
 * Request to permanently lock a player's game mode.
 */
@Data
@Builder
public class LockModeRequest
{
	/**
	 * The game mode to lock: "CASUAL" or "NUZLOCKE"
	 */
	@SerializedName("game_mode")
	private String gameMode;
}
