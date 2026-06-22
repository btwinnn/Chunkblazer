package net.runelite.client.plugins.chunkblazer.api;

import lombok.Data;

/**
 * Response from POST /api/player/nuzlocke/eligibility. The server decides
 * whether the account qualifies for a Full Nuzlocke start; {@code reason}
 * carries a human-readable explanation when it does not.
 */
@Data
public class NuzlockeEligibilityResponse
{
	private boolean eligible;
	private String reason;

	/**
	 * When the API is disabled or unreachable we can't confirm eligibility, so
	 * we fail closed: not eligible, with a reason the caller can surface.
	 */
	public static NuzlockeEligibilityResponse offline()
	{
		NuzlockeEligibilityResponse r = new NuzlockeEligibilityResponse();
		r.setEligible(false);
		r.setReason("Could not reach the ChunkBlazer server to check eligibility.");
		return r;
	}
}
