package net.runelite.client.plugins.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Response from POST /api/player/verify/start. The server either:
 *   - says the player is already verified (alreadyVerified=true, rest null)
 *   - hands out a 6-char nonce + the exact chat phrase to type
 *
 * The chatPhrase is "chunkblazer ABC123" — the plugin's chat listener watches
 * for the local player saying exactly this in public chat, then POSTs verify.
 */
@Data
public class VerifyStartResponse
{
	@SerializedName("alreadyVerified")
	private boolean alreadyVerified;

	private String nonce;

	@SerializedName("expiresAt")
	private String expiresAt;

	@SerializedName("chatPhrase")
	private String chatPhrase;

	/**
	 * Offline fallback used when the API is disabled or unreachable.
	 */
	public static VerifyStartResponse offline()
	{
		VerifyStartResponse r = new VerifyStartResponse();
		r.setAlreadyVerified(false);
		return r;
	}
}
