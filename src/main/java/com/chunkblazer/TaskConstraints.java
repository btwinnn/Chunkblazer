package com.chunkblazer;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@Data
@JsonAdapter(TaskConstraints.TaskConstraintsDeserializer.class)
public class TaskConstraints
{
	// ── AUTHORING RULE for RESTRICTED kills (time_in_ticks / equipment) ──────
	// These constraints are verified client-side from the local player's own
	// combat tracking, which enforces "you SOLOED it fast / while restricted",
	// not merely "you landed the killing blow". NPCKillModule requires the
	// fight to be a fresh session (relog gate) AND to have no other-player
	// damage (exclusive-damage gate). Therefore:
	//   • Only put time/equipment constraints on SOLOABLE overworld monsters
	//     a player can guarantee they fight alone.
	//   • NEVER on raid/team bosses (Verzik, Olm, ToA, GWD, Nex, Corp, etc.):
	//     the client can't see damage dealt on other players' clients, so the
	//     "fight" it measures is only your slice — both a false-pass hole and
	//     unfixable client-side. Author those as plain "defeat X" (KC-verified
	//     for tracked bosses).
	//   • Shared/multi-combat spawns are risky even solo (a passer-by's stray
	//     hit voids the attempt) — prefer instanced or low-traffic targets.
	@SerializedName("time_in_ticks")
	private Integer timeInTicks;

	@SerializedName("required_skill")
	private String requiredSkill;

	@SerializedName("required_level")
	private Integer requiredLevel;

	@SerializedName("required_xp")
	private Integer requiredXp;

	@SerializedName("required_region")
	private Integer requiredRegion;

	@SerializedName("allowed_regions")
	private List<Integer> allowedRegions;

	@SerializedName("min_combat_level")
	private Integer minCombatLevel;

	@SerializedName("max_combat_level")
	private Integer maxCombatLevel;

	@SerializedName("no_prayer")
	private Boolean noPrayer;

	@SerializedName("no_food")
	private Boolean noFood;

	// NOTE: there is deliberately no `solo_only` field. One existed here and was
	// never read by any module, so authoring it bought no enforcement while
	// reading like it did. Solo exclusivity is implied by a time or equipment
	// constraint (see NPCKillModule's contested/startedFresh gates); the inverse
	// — an encounter fought in a team — is the task-level `group_content` flag.


	// Equipment constraints
	@SerializedName("no_equipment")
	private Boolean noEquipment;  // Must have nothing equipped at all

	@SerializedName("required_equipment_ids")
	private List<Integer> requiredEquipmentIds;  // These items MUST be equipped

	@SerializedName("allowed_equipment_ids")
	private List<Integer> allowedEquipmentIds;  // ONLY these items can be equipped (all others fail)

	@SerializedName("forbidden_equipment_ids")
	private List<Integer> forbiddenEquipmentIds;  // These items must NOT be equipped

	// Slot-based equipment constraints (uses EquipmentInventorySlot indices)
	// Slots: HEAD=0, CAPE=1, AMULET=2, WEAPON=3, BODY=4, SHIELD=5, LEGS=7, GLOVES=9, BOOTS=10, RING=12, AMMO=13
	@SerializedName("must_be_empty")
	private List<Integer> mustBeEmptySlots;  // These equipment slots MUST be empty

	@SerializedName("equippable_slots")
	private List<Integer> equippableSlots;  // ONLY these slots can have equipment (all others must be empty)

	@SerializedName("equip_nothing")
	private Boolean equipNothing;  // If true, player must have ZERO equipment (nothing equipped at all)

	// Dropped item constraints (for NPC kill tasks that require specific loot)
	@SerializedName("dropped_item")
	private String droppedItem;  // Name of the required drop (for display)

	@SerializedName("dropped_item_id")
	private List<Integer> droppedItemIds;  // Item IDs that count as the required drop

	@SerializedName("quantity")
	private Integer droppedItemQuantity;  // Required quantity of the dropped item

	// Varbit constraints (e.g., prohibit cannon use during timed tasks)
	@SerializedName("prohibited_active_varbits")
	private List<VarbitConstraint> prohibitedActiveVarbits;

	// net.runelite.api.Quest enum constant name for QUEST_CHECK tasks, e.g.
	// "DRAGON_SLAYER_II". No varp id or expected value is stored alongside it:
	// Quest.getState() resolves the per-quest varp and its finished threshold
	// internally, so the constant name is the whole configuration.
	@SerializedName("quest")
	private String quest;

	// Direct varbit/varp ID for VARBIT_CHECK and VARP_CHECK tasks
	@SerializedName("varbit_id")
	private Integer varbitId;

	@SerializedName("varp_id")
	private Integer varpId;

	// Expected value for VARBIT_CHECK / VARP_CHECK exact-match tasks.
	// Moved into constraints from a top-level NuzlockeTask field so all
	// varbit-related schema lives in one place. NuzlockeTask still exposes
	// a deprecated top-level mirror for legacy JSON that hasn't been migrated.
	@SerializedName("varbit_boolean")
	private Integer varbitBoolean;

	// Bit position to test inside a bitmap varbit (e.g. ACTIVE_PRAYERS 4101).
	// Same provenance / fallback story as varbitBoolean above.
	@SerializedName("varbit_bit")
	private Integer varbitBit;

	/**
	 * Represents a varbit constraint that must be checked during task verification.
	 */
	@Data
	public static class VarbitConstraint
	{
		@SerializedName("varbit_id")
		private int varbitId;

		@SerializedName("must_be_value")
		private int mustBeValue;

		@SerializedName("fail_message")
		private String failMessage;
	}

	public boolean hasTimeLimit()
	{
		return timeInTicks != null && timeInTicks > 0;
	}

	public int getRequiredLevel()
	{
		return requiredLevel != null ? requiredLevel : 1;
	}

	public int getRequiredXp()
	{
		return requiredXp != null ? requiredXp : 0;
	}

	public boolean hasEquipmentConstraints()
	{
		return (noEquipment != null && noEquipment) ||
			(equipNothing != null && equipNothing) ||
			(requiredEquipmentIds != null && !requiredEquipmentIds.isEmpty()) ||
			(allowedEquipmentIds != null && !allowedEquipmentIds.isEmpty()) ||
			(forbiddenEquipmentIds != null && !forbiddenEquipmentIds.isEmpty()) ||
			(mustBeEmptySlots != null && !mustBeEmptySlots.isEmpty()) ||
			(equippableSlots != null && !equippableSlots.isEmpty());
	}

	public boolean isNoEquipment()
	{
		return noEquipment != null && noEquipment;
	}

	public boolean isEquipNothing()
	{
		return equipNothing != null && equipNothing;
	}

	public boolean hasDroppedItemConstraint()
	{
		return droppedItemIds != null && !droppedItemIds.isEmpty();
	}

	public int getDroppedItemQuantity()
	{
		return droppedItemQuantity != null ? droppedItemQuantity : 1;
	}

	public boolean hasVarbitConstraints()
	{
		return prohibitedActiveVarbits != null && !prohibitedActiveVarbits.isEmpty();
	}

	public List<VarbitConstraint> getProhibitedActiveVarbits()
	{
		return prohibitedActiveVarbits;
	}

	/**
	 * Custom deserializer that handles flexible JSON formats.
	 * Also handles the case where "constraints" is an empty array [] instead of an object.
	 */
	public static class TaskConstraintsDeserializer implements JsonDeserializer<TaskConstraints>
	{
		@Override
		public TaskConstraints deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
			throws JsonParseException
		{
			// Handle case where constraints is an array instead of object
			if (json.isJsonArray())
			{
				JsonArray arr = json.getAsJsonArray();
				if (arr.size() == 0)
				{
					// Empty array - return empty constraints
					return new TaskConstraints();
				}
				// Non-empty array - deserialize first element if it's an object
				JsonElement first = arr.get(0);
				if (first.isJsonObject())
				{
					// Recursively deserialize the first object
					return deserialize(first, typeOfT, context);
				}
				return new TaskConstraints();
			}

			TaskConstraints constraints = new TaskConstraints();
			JsonObject obj = json.getAsJsonObject();

			// Handle integer fields that might be arrays
			constraints.setTimeInTicks(readFlexibleInt(obj, "time_in_ticks"));
			constraints.setRequiredLevel(readFlexibleInt(obj, "required_level"));
			constraints.setRequiredXp(readFlexibleInt(obj, "required_xp"));
			constraints.setRequiredRegion(readFlexibleInt(obj, "required_region"));
			constraints.setMinCombatLevel(readFlexibleInt(obj, "min_combat_level"));
			constraints.setMaxCombatLevel(readFlexibleInt(obj, "max_combat_level"));

			// Handle string field
			if (obj.has("required_skill"))
			{
				JsonElement el = obj.get("required_skill");
				if (el.isJsonArray() && el.getAsJsonArray().size() > 0)
				{
					constraints.setRequiredSkill(el.getAsJsonArray().get(0).getAsString());
				}
				else if (el.isJsonPrimitive())
				{
					constraints.setRequiredSkill(el.getAsString());
				}
			}

			// Handle allowed_regions array
			if (obj.has("allowed_regions"))
			{
				JsonArray arr = obj.getAsJsonArray("allowed_regions");
				List<Integer> regions = new ArrayList<>();
				for (JsonElement e : arr)
				{
					regions.add(e.getAsInt());
				}
				constraints.setAllowedRegions(regions);
			}

			// Handle boolean fields
			constraints.setNoPrayer(readFlexibleBoolean(obj, "no_prayer"));
			constraints.setNoFood(readFlexibleBoolean(obj, "no_food"));
			constraints.setNoEquipment(readFlexibleBoolean(obj, "no_equipment"));
			constraints.setEquipNothing(readFlexibleBoolean(obj, "equip_nothing"));

			// Handle equipment ID arrays
			constraints.setRequiredEquipmentIds(readIntArray(obj, "required_equipment_ids"));
			constraints.setAllowedEquipmentIds(readIntArray(obj, "allowed_equipment_ids"));
			constraints.setForbiddenEquipmentIds(readIntArray(obj, "forbidden_equipment_ids"));

			// Handle slot-based equipment constraints
			// Support both integer arrays and string slot name arrays
			constraints.setMustBeEmptySlots(readSlotArray(obj, "must_be_empty", "must_be_empty_slots"));
			constraints.setEquippableSlots(readSlotArray(obj, "equippable_slots", "equippable_slot_names"));

			// Handle dropped item constraints
			if (obj.has("dropped_item"))
			{
				JsonElement el = obj.get("dropped_item");
				if (el.isJsonPrimitive())
				{
					constraints.setDroppedItem(el.getAsString());
				}
			}
			constraints.setDroppedItemIds(readIntArray(obj, "dropped_item_id"));
			constraints.setDroppedItemQuantity(readFlexibleInt(obj, "quantity"));

			// Quest enum constant name for QUEST_CHECK tasks. This deserializer is
			// a whitelist — a field not read here is silently dropped, so the
			// read must exist or constraints.quest is always null at runtime.
			if (obj.has("quest") && obj.get("quest").isJsonPrimitive())
			{
				constraints.setQuest(obj.get("quest").getAsString());
			}

			// Handle direct varbit_id and varp_id for VARBIT_CHECK/VARP_CHECK tasks
			constraints.setVarbitId(readFlexibleInt(obj, "varbit_id"));
			constraints.setVarpId(readFlexibleInt(obj, "varp_id"));
			// Expected value + optional bit position for VARBIT_CHECK tasks.
			constraints.setVarbitBoolean(readFlexibleInt(obj, "varbit_boolean"));
			constraints.setVarbitBit(readFlexibleInt(obj, "varbit_bit"));

			// Handle prohibited_active_varbits constraints
			if (obj.has("prohibited_active_varbits"))
			{
				JsonElement varbitEl = obj.get("prohibited_active_varbits");
				if (varbitEl.isJsonArray())
				{
					List<VarbitConstraint> varbitConstraints = new ArrayList<>();
					for (JsonElement el : varbitEl.getAsJsonArray())
					{
						if (el.isJsonObject())
						{
							JsonObject vObj = el.getAsJsonObject();
							VarbitConstraint vc = new VarbitConstraint();
							if (vObj.has("varbit_id"))
							{
								vc.setVarbitId(vObj.get("varbit_id").getAsInt());
							}
							if (vObj.has("must_be_value"))
							{
								vc.setMustBeValue(vObj.get("must_be_value").getAsInt());
							}
							if (vObj.has("fail_message"))
							{
								vc.setFailMessage(vObj.get("fail_message").getAsString());
							}
							varbitConstraints.add(vc);
						}
					}
					constraints.setProhibitedActiveVarbits(varbitConstraints);
				}
			}

			return constraints;
		}

		private Integer readFlexibleInt(JsonObject obj, String field)
		{
			if (!obj.has(field)) return null;

			JsonElement el = obj.get(field);
			if (el.isJsonArray())
			{
				JsonArray arr = el.getAsJsonArray();
				if (arr.size() > 0)
				{
					return arr.get(0).getAsInt();
				}
				return null;
			}
			else if (el.isJsonPrimitive())
			{
				return el.getAsInt();
			}
			return null;
		}

		private Boolean readFlexibleBoolean(JsonObject obj, String field)
		{
			if (!obj.has(field)) return null;

			JsonElement el = obj.get(field);
			if (el.isJsonArray())
			{
				JsonArray arr = el.getAsJsonArray();
				if (arr.size() > 0)
				{
					return arr.get(0).getAsBoolean();
				}
				return null;
			}
			else if (el.isJsonPrimitive())
			{
				return el.getAsBoolean();
			}
			return null;
		}

		private List<Integer> readIntArray(JsonObject obj, String field)
		{
			if (!obj.has(field)) return null;

			List<Integer> result = new ArrayList<>();
			JsonElement el = obj.get(field);

			if (el.isJsonArray())
			{
				for (JsonElement e : el.getAsJsonArray())
				{
					if (e.isJsonPrimitive())
					{
						result.add(e.getAsInt());
					}
				}
			}
			else if (el.isJsonPrimitive())
			{
				// Single value instead of array
				result.add(el.getAsInt());
			}

			return result.isEmpty() ? null : result;
		}

		/**
		 * Read slot array that can be either integer indices or string slot names.
		 * Checks intField first (for integer indices or string names), then stringField as fallback.
		 */
		private List<Integer> readSlotArray(JsonObject obj, String intField, String stringField)
		{
			// First try reading from the primary field (can be integers OR strings)
			if (obj.has(intField))
			{
				List<Integer> result = readFlexibleSlotArray(obj.get(intField));
				if (result != null && !result.isEmpty())
				{
					return result;
				}
			}

			// Try reading from the secondary/fallback field
			if (obj.has(stringField))
			{
				List<Integer> result = readFlexibleSlotArray(obj.get(stringField));
				if (result != null && !result.isEmpty())
				{
					return result;
				}
			}

			return null;
		}

		/**
		 * Read a slot array that can contain either integer indices or string slot names.
		 */
		private List<Integer> readFlexibleSlotArray(JsonElement el)
		{
			List<Integer> result = new ArrayList<>();

			if (el.isJsonArray())
			{
				for (JsonElement e : el.getAsJsonArray())
				{
					if (e.isJsonPrimitive())
					{
						JsonPrimitive prim = e.getAsJsonPrimitive();
						if (prim.isNumber())
						{
							// Integer slot index
							result.add(prim.getAsInt());
						}
						else if (prim.isString())
						{
							// String slot name
							String slotName = prim.getAsString().toUpperCase();
							Integer slotIndex = slotNameToIndex(slotName);
							if (slotIndex != null)
							{
								result.add(slotIndex);
							}
						}
					}
				}
			}
			else if (el.isJsonPrimitive())
			{
				JsonPrimitive prim = el.getAsJsonPrimitive();
				if (prim.isNumber())
				{
					result.add(prim.getAsInt());
				}
				else if (prim.isString())
				{
					String slotName = prim.getAsString().toUpperCase();
					Integer slotIndex = slotNameToIndex(slotName);
					if (slotIndex != null)
					{
						result.add(slotIndex);
					}
				}
			}

			return result.isEmpty() ? null : result;
		}

		/**
		 * Convert slot name to slot index.
		 * Supports: HEAD, CAPE, AMULET, WEAPON, BODY, SHIELD, LEGS, GLOVES, BOOTS, RING, AMMO
		 */
		private Integer slotNameToIndex(String slotName)
		{
			switch (slotName)
			{
				case "HEAD": return 0;
				case "CAPE": return 1;
				case "AMULET": return 2;
				case "WEAPON": return 3;
				case "BODY": return 4;
				case "SHIELD": return 5;
				case "LEGS": return 7;
				case "GLOVES": return 9;
				case "BOOTS": return 10;
				case "RING": return 12;
				case "AMMO": return 13;
				default: return null;
			}
		}
	}
}
