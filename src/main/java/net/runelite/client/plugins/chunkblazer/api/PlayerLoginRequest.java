package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;

/**
 * Request sent when a player logs in to register or fetch their account.
 */
@Data
@Builder
public class PlayerLoginRequest
{
	/**
	 * The player's RuneScape name.
	 */
	private String rsn;

	/**
	 * SHA-256 hash of the lowercase RSN (for privacy).
	 */
	@SerializedName("rsn_hash")
	private String rsnHash;

	/**
	 * Current client version for compatibility checking.
	 */
	@SerializedName("client_version")
	private String clientVersion;
}
