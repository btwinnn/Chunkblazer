package com.chunkblazer;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.util.List;

@Data
public class NuzlockeChunk
{
	@SerializedName("region_id")
	@JsonAdapter(IntOrArrayDeserializer.class)
	private List<Integer> regionIds;

	@SerializedName("friendly_name")
	private String friendlyName;

	@SerializedName("Friendly_Name")
	private String friendlyNameAlt;

	/**
	 * The area this chunk belongs to (e.g., "Misthalin", "Asgarnia", "Zeah").
	 * Set at load time based on the JSON file name.
	 */
	private String area;

	@SerializedName("neighbor_ids")
	private List<Integer> neighborIds;

	@SerializedName("unlock_cost")
	private Integer unlockCost;

	/**
	 * Optional classifier for the chunk, e.g. "CHARTER" for the sailable port
	 * chunks. Null for ordinary task-area chunks. Lets future features enumerate
	 * a category of chunks generically instead of hardcoding region IDs.
	 */
	@SerializedName("chunk_type")
	private String chunkType;

	/**
	 * For boss chunks (chunk_type "BOSS"): the stable key identifying the boss/raid
	 * (e.g. "toa"). Used to attribute the once-per-boss completion that earns a
	 * Boss Token, and reported to the server in the sync's bossCompletions list.
	 */
	@SerializedName("boss_key")
	private String bossKey;

	/**
	 * For boss chunks hosting MORE THAN ONE token-boss on a single region (e.g. the
	 * Varrock chunk that carries both Scurrius and Bryophyta): the list of boss keys,
	 * each earning its own once-per-boss token. When present it supersedes
	 * {@link #bossKey}; {@link #getBossKeys()} returns the effective set of either.
	 */
	@SerializedName("boss_keys")
	private java.util.List<String> bossKeys;

	/**
	 * Boss key -> the NPC ids whose DEATH signals that boss's completion (for the
	 * once-per-boss token). Authored per boss chunk so detection is data-driven — a new
	 * world boss needs no plugin change, just its ids here. E.g. the Scurrius+Bryophyta
	 * chunk: {"scurrius":[7221,7222],"bryophyta":[8195]}. Raids (ToA/CoX) leave this null
	 * and use the completion chat message instead (their bosses despawn / are multi-phase).
	 */
	@SerializedName("boss_npc_ids")
	private java.util.Map<String, java.util.List<Integer>> bossNpcIds;

	/**
	 * Outbound charter/sail routes from this chunk (port-to-port). Currently
	 * informational — parsed and held for a future travel feature; nothing reads
	 * it yet, but keeping it modeled means the data isn't silently dropped.
	 */
	@SerializedName("travel_connections")
	private List<TravelConnection> travelConnections;

	private List<NuzlockeTask> tasks;

	@SerializedName("optional_quests_unlocked")
	private List<OptionalQuest> optionalQuests;

	@SerializedName("is_completed")
	private boolean isCompleted;

	public String getName()
	{
		if (friendlyName != null && !friendlyName.isEmpty())
		{
			return friendlyName;
		}
		if (friendlyNameAlt != null && !friendlyNameAlt.isEmpty())
		{
			return friendlyNameAlt;
		}
		if (regionIds != null && !regionIds.isEmpty())
		{
			return "Region " + regionIds.get(0);
		}
		return "Unknown Region";
	}

	public int getUnlockCostValue()
	{
		return unlockCost != null ? unlockCost : 1;
	}

	public String getArea()
	{
		return area;
	}

	public void setArea(String area)
	{
		this.area = area;
	}

	/**
	 * @return true if this chunk is a charter (sailable port) chunk.
	 */
	public boolean isCharter()
	{
		return "CHARTER".equalsIgnoreCase(chunkType);
	}

	/**
	 * @return true if this chunk is a boss chunk — unlocked with a Boss Token
	 * (not points), granting every task on the chunk at once. See docs/BOSS-CHUNKS.
	 */
	public boolean isBoss()
	{
		return "BOSS".equalsIgnoreCase(chunkType);
	}

	/**
	 * @return the boss/raid key for a boss chunk (e.g. "toa"), or null if this is
	 * not a boss chunk / none was authored.
	 */
	public String getBossKey()
	{
		return bossKey;
	}

	/**
	 * @return every boss key this chunk hosts — the {@code boss_keys} list if authored,
	 * else the single {@code boss_key} (as a one-element list), else empty. This is the
	 * accessor the token logic should use so one chunk can carry multiple token-bosses.
	 */
	public java.util.List<String> getBossKeys()
	{
		if (bossKeys != null && !bossKeys.isEmpty())
		{
			return bossKeys;
		}
		if (bossKey != null && !bossKey.isEmpty())
		{
			return java.util.Collections.singletonList(bossKey);
		}
		return java.util.Collections.emptyList();
	}

	/** @return boss key -> NPC ids whose death completes that boss, or null if none authored. */
	public java.util.Map<String, java.util.List<Integer>> getBossNpcIds()
	{
		return bossNpcIds;
	}

	@Data
	public static class OptionalQuest
	{
		private String name;
		private int points;
	}

	/**
	 * A single outbound sail route from a charter port to another chunk.
	 */
	@Data
	public static class TravelConnection
	{
		@SerializedName("destination_id")
		private Integer destinationId;

		@SerializedName("travel_name")
		private String travelName;
	}
}
