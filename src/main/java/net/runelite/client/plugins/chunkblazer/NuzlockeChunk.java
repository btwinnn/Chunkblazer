package net.runelite.client.plugins.chunkblazer;

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

	@SerializedName("neighbor_ids")
	private List<Integer> neighborIds;

	@SerializedName("unlock_cost")
	private Integer unlockCost;

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

	public boolean containsRegion(int regionId)
	{
		return regionIds != null && regionIds.contains(regionId);
	}

	public int getUnlockCostValue()
	{
		return unlockCost != null ? unlockCost : 1;
	}

	@Data
	public static class OptionalQuest
	{
		private String name;
		private int points;
	}
}
