package com.chunkblazer;

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

	/**
	 * Marks a task as GROUP content (raids, Nex, group bosses) — an encounter a
	 * player is expected to fight alongside others.
	 *
	 * <p>Two things follow from it in {@code NPCKillModule}:
	 * <ul>
	 *   <li>The solo-only gates (contested / startedFresh / cannon / relog-grace)
	 *       are skipped. They exist for solo speed-and-gear challenges and are
	 *       unsatisfiable in a team: a teammate's hitsplat sets {@code contested},
	 *       and the boss is only at full health for whoever lands the very first
	 *       hit of the encounter.</li>
	 *   <li>The fight record's idle TTL is extended, so a long mechanic phase
	 *       where you land no hits doesn't discard your stake in the kill.</li>
	 * </ul>
	 *
	 * <p>The always-on {@code damage > 0} check still applies — you must have
	 * personally damaged the NPC that died. This flag widens who may ALSO have
	 * hit it; it never grants credit for a kill you took no part in.
	 *
	 * <p>Authoring a group task WITH a time or equipment constraint is a
	 * contradiction and is rejected at load — see {@link #getGroupContentSchemaError()}.
	 */
	@SerializedName("group_content")
	private Boolean groupContent;

	@SerializedName("is_unlocked")
	private Boolean isUnlocked;

	@SerializedName("required_items")
	private List<RequiredItem> requiredItems;

	@SerializedName("required_object")
	private List<RequiredObject> requiredObjects;

	@SerializedName("target_npc")
	private TargetNpc targetNpc;

	private TaskConstraints constraints;

	// DEPRECATED top-level mirrors of varbit_boolean and varbit_bit. The
	// canonical home is now inside `constraints` (alongside varbit_id) so all
	// varbit-related schema lives in one place. The fields are kept here so
	// any pre-migration JSON that still puts them at the top level continues
	// to work — VarbitCheckModule falls back to these when the constraints
	// version is null. Remove after one release cycle once all task JSON has
	// been migrated.
	@Deprecated
	@SerializedName("varbit_boolean")
	private Integer varbitBoolean;

	@Deprecated
	@SerializedName("varbit_bit")
	private Integer varbitBit;

	// Runtime tracking fields (not from JSON)
	private transient int currentProgress;
	private transient int targetQuantity;
	private transient boolean completed;

	// Set true by the deserializer when the task JSON has a required_object
	// block. We don't model the full RequiredObject yet; AgilityModule needs
	// only "is this a lap-style task" to pick the right XP threshold.
	private transient boolean hasRequiredObject;

	public int getLevelRequirement()
	{
		return level != null ? level : 1;
	}

	/**
	 * True when this task is group content. Null-safe: absent means solo, so
	 * every existing task keeps today's behaviour.
	 */
	public boolean isGroupContent()
	{
		return groupContent != null && groupContent;
	}

	/**
	 * Validates the group_content flag against the rest of the task, and returns
	 * a human-readable problem or null when the task is consistent.
	 *
	 * <p>Time and equipment constraints turn on the solo-only gates, which a
	 * group encounter can never satisfy — the task would look authored-and-live
	 * but be impossible to complete, failing with a baffling "must be solo"
	 * message. That combination is a schema error, not a hard challenge, so it's
	 * caught at load rather than in-game.
	 */
	public String getGroupContentSchemaError()
	{
		if (!isGroupContent() || constraints == null)
		{
			return null;
		}
		if (constraints.hasTimeLimit())
		{
			return "group_content tasks cannot have a time limit (the solo-only gates it enables can't be met in a team)";
		}
		if (constraints.hasEquipmentConstraints())
		{
			return "group_content tasks cannot have equipment constraints (the solo-only gates they enable can't be met in a team)";
		}
		return null;
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
			task.setGroupContent(getBooleanOrNull(obj, "group_content"));

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

			// Handle required_object / required_finished_object — both populate the
			// same requiredObjects list. CONSTRUCTION tasks were renamed from
			// required_object to required_finished_object for clarity ("the finished
			// furniture's object_id"), but the data shape is identical so we just
			// accept either field name. Mirror required_items: accept array or
			// single object.
			JsonElement reqObjEl = null;
			if (obj.has("required_finished_object"))
			{
				reqObjEl = obj.get("required_finished_object");
			}
			else if (obj.has("required_object"))
			{
				reqObjEl = obj.get("required_object");
			}
			if (reqObjEl != null)
			{
				List<RequiredObject> objects = new ArrayList<>();

				if (reqObjEl.isJsonArray())
				{
					for (JsonElement el : reqObjEl.getAsJsonArray())
					{
						objects.add(context.deserialize(el, RequiredObject.class));
					}
				}
				else if (reqObjEl.isJsonObject())
				{
					objects.add(context.deserialize(reqObjEl, RequiredObject.class));
				}
				task.setRequiredObjects(objects);
			}

			// Handle constraints - some JSON files use object form ({...}), others use
			// single-element array form ([{...}]). Accept both so a malformed entry doesn't
			// tank the entire JSON file.
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
			task.setVarbitBit(getIntOrNull(obj, "varbit_bit"));

			// Note required_object presence (don't fully parse; AgilityModule
			// only needs to know lap vs shortcut so it can pick a threshold).
			task.setHasRequiredObject(obj.has("required_object") || obj.has("required_finished_object"));

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
