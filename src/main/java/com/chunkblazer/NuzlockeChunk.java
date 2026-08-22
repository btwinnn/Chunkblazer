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
