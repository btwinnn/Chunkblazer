package com.chunkblazer.api;

import com.google.gson.annotations.SerializedName;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * A snapshot of the local account's state, read from the live game client, used
 * to prove the account is a fresh "level 3" start before locking Full Nuzlocke.
 *
 * <p>The server re-derives the eligibility verdict from these raw values — it
 * never trusts a client-computed boolean — so this only ever carries facts
 * (combat level, quest points, total level, and every skill's real level),
 * never a decision.
 */
@Data
@Builder
public class EligibilitySnapshot
{
	@SerializedName("combat_level")
	private int combatLevel;

	@SerializedName("quest_points")
	private int questPoints;

	@SerializedName("total_level")
	private int totalLevel;

	/**
	 * Map of RuneLite {@code Skill.name()} (e.g. "ATTACK", "HITPOINTS") to the
	 * account's real (un-boosted) level in that skill.
	 */
	@SerializedName("skills")
	private Map<String, Integer> skills;
}
