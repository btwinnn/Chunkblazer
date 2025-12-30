package net.runelite.client.plugins.chunkblazer;

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

    @SerializedName("solo_only")
    private Boolean soloOnly;

    public boolean hasTimeLimit()
    {
        return timeInTicks != null && timeInTicks > 0;
    }

    public double getTimeInSeconds()
    {
        if (timeInTicks == null)
        {
            return 0;
        }
        // 1 game tick = 0.6 seconds
        return timeInTicks * 0.6;
    }

    public int getRequiredLevel()
    {
        return requiredLevel != null ? requiredLevel : 1;
    }

    public int getRequiredXp()
    {
        return requiredXp != null ? requiredXp : 0;
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
            constraints.setSoloOnly(readFlexibleBoolean(obj, "solo_only"));

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
    }
}
