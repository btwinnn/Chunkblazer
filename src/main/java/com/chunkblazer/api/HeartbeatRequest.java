package com.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;

/**
 * Request sent periodically to keep the player marked as online.
 */
@Data
@Builder
public class HeartbeatRequest
{
	/**
	 * The world the player is on
	 */
	private int world;

	/**
	 * The region the player is currently in
	 */
	@SerializedName("region_id")
	private int regionId;

	/**
	 * Whether the player wants to be visible to others
	 */
	@SerializedName("is_visible")
	private boolean isVisible;
}
