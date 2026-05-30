package net.runelite.client.plugins.chunkblazer.api;

import lombok.Data;

/**
 * Response from POST /api/player/verify. Server returns verified=true on
 * successful nonce consumption; the plugin then flips local state and hides
 * the verification prompt.
 */
@Data
public class VerifyResponse
{
	private boolean verified;
	private String message;

	public static VerifyResponse offline()
	{
		VerifyResponse r = new VerifyResponse();
		r.setVerified(false);
		r.setMessage("API is disabled or unreachable");
		return r;
	}
}
