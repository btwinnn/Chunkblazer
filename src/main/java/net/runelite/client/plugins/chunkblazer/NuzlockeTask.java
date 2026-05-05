package net.runelite.client.plugins.chunkblazer;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@Data
@JsonAdapter(NuzlockeTask.NuzlockeTaskDeserializer.class)
public class NuzlockeTask
{
	private String name;

	@SerializedName("taskID")
	private String taskId;

	private String category;

	@SerializedName("completion_type")
	private String completionType;

	@SerializedName("base_points")
	private int basePoints;

	@SerializedName("assignment_weight")
	private int assignmentWeight;

	private Integer level;

	@SerializedName("is_unlocked")
	private Boolean isUnlocked;

	@SerializedName("required_items")
	private List<RequiredItem> requiredItems;

	@SerializedName("target_npc")
	private TargetNpc targetNpc;

	private TaskConstraints constraints;

	@SerializedName("varbit_boolean")
	private Integer varbitBoolean;

	// Runtime tracking fields (not from JSON)
	private transient int currentProgress;
	private transient int targetQuantity;
	private transient boolean completed;

	public int getLevelRequirement()
	{
		return level != null ? level : 1;
	}

	public boolean isLocked()
	{
		return isUnlocked != null && !isUnlocked;
	}

	public String getDisplayName()
	{
		if (targetQuantity > 1)
		{
			return name + " (0/" + targetQuantity + ")";
		}
		return name;
	}

	public String getProgressText()
	{
		if (targetQuantity > 1)
		{
			return currentProgress + "/" + targetQuantity;
		}
		return completed ? "Complete" : "In Progress";
	}

	/**
	 * Custom deserializer that handles flexible JSON formats for NuzlockeTask.
	 * Specifically handles required_items being an object instead of array.
	 * Also handles null values gracefully.
	 */
	public static class NuzlockeTaskDeserializer implements JsonDeserializer<NuzlockeTask>
	{
		@Override
		public NuzlockeTask deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
			throws JsonParseException
		{
			NuzlockeTask task = new NuzlockeTask();
			JsonObject obj = json.getAsJsonObject();

			// Simple string fields (with null checks)
			task.setName(getStringOrNull(obj, "name"));
			task.setTaskId(getStringOrNull(obj, "taskID"));
			task.setCategory(getStringOrNull(obj, "category"));
			task.setCompletionType(getStringOrNull(obj, "completion_type"));

			// Integer fields (with null checks)
			task.setBasePoints(getIntOrDefault(obj, "base_points", 0));
			task.setAssignmentWeight(getIntOrDefault(obj, "assignment_weight", 1));
			task.setLevel(getIntOrNull(obj, "level"));

			// Boolean field (with null check)
			task.setIsUnlocked(getBooleanOrNull(obj, "is_unlocked"));

			// Handle required_items - can be array or single object
			if (obj.has("required_items"))
			{
				JsonElement itemsEl = obj.get("required_items");
				List<RequiredItem> items = new ArrayList<>();

				if (itemsEl.isJsonArray())
				{
					// Normal case - array of items
					for (JsonElement el : itemsEl.getAsJsonArray())
					{
						items.add(context.deserialize(el, RequiredItem.class));
					}
				}
				else if (itemsEl.isJsonObject())
				{
					// Single object instead of array
					items.add(context.deserialize(itemsEl, RequiredItem.class));
				}
				task.setRequiredItems(items);
			}

			// Handle target_npc - delegate to its deserializer
			if (obj.has("target_npc"))
			{
				task.setTargetNpc(context.deserialize(obj.get("target_npc"), TargetNpc.class));
			}

			// Handle constraints - some JSON files use object form ({...}), others use
			// single-element array form ([{...}]). Accept both so a malformed entry doesn't
			// tank the entire JSON file (which is what was breaking Starter_Area_Tasks.json).
			if (obj.has("constraints"))
			{
				JsonElement constraintsEl = obj.get("constraints");
				if (constraintsEl.isJsonObject())
				{
					task.setConstraints(context.deserialize(constraintsEl, TaskConstraints.class));
				}
				else if (constraintsEl.isJsonArray() && constraintsEl.getAsJsonArray().size() > 0)
				{
					task.setConstraints(context.deserialize(constraintsEl.getAsJsonArray().get(0), TaskConstraints.class));
				}
			}

			// Handle varbit_boolean for VARBIT_CHECK tasks
			task.setVarbitBoolean(getIntOrNull(obj, "varbit_boolean"));

			return task;
		}

		// Some JSON files store scalars as arrays (e.g. "category": ["Ranged", "Defence"], "level": [50, 1]).
		// Collapse to the first non-null primitive so a stray array doesn't tank the whole file.
		private JsonElement firstScalar(JsonElement el)
		{
			if (el == null || el.isJsonNull()) return null;
			if (el.isJsonArray())
			{
				JsonArray arr = el.getAsJsonArray();
				for (JsonElement e : arr)
				{
					if (e != null && !e.isJsonNull() && e.isJsonPrimitive())
					{
						return e;
					}
				}
				return null;
			}
			return el.isJsonPrimitive() ? el : null;
		}

		private String getStringOrNull(JsonObject obj, String field)
		{
			if (!obj.has(field)) return null;
			JsonElement el = firstScalar(obj.get(field));
			return el == null ? null : el.getAsString();
		}

		private Integer getIntOrNull(JsonObject obj, String field)
		{
			if (!obj.has(field)) return null;
			JsonElement el = firstScalar(obj.get(field));
			return el == null ? null : el.getAsInt();
		}

		private int getIntOrDefault(JsonObject obj, String field, int defaultValue)
		{
			if (!obj.has(field)) return defaultValue;
			JsonElement el = firstScalar(obj.get(field));
			return el == null ? defaultValue : el.getAsInt();
		}

		private Boolean getBooleanOrNull(JsonObject obj, String field)
		{
			if (!obj.has(field)) return null;
			JsonElement el = firstScalar(obj.get(field));
			return el == null ? null : el.getAsBoolean();
		}
	}
}
