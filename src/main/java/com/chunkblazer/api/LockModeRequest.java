package com.chunkblazer.api;

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

	/**
	 * Fresh-account snapshot, required when {@link #gameMode} is "NUZLOCKE" so
	 * the server can re-validate eligibility at lock time. Null (and omitted
	 * from the JSON) for CASUAL.
	 */
	@SerializedName("eligibility")
	private EligibilitySnapshot eligibility;
}
