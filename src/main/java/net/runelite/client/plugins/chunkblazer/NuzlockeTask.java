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
     */
    public static class NuzlockeTaskDeserializer implements JsonDeserializer<NuzlockeTask>
    {
        @Override
        public NuzlockeTask deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException
        {
            NuzlockeTask task = new NuzlockeTask();
            JsonObject obj = json.getAsJsonObject();

            // Simple string fields
            if (obj.has("name")) task.setName(obj.get("name").getAsString());
            if (obj.has("taskID")) task.setTaskId(obj.get("taskID").getAsString());
            if (obj.has("category")) task.setCategory(obj.get("category").getAsString());
            if (obj.has("completion_type")) task.setCompletionType(obj.get("completion_type").getAsString());

            // Integer fields
            if (obj.has("base_points")) task.setBasePoints(obj.get("base_points").getAsInt());
            if (obj.has("assignment_weight")) task.setAssignmentWeight(obj.get("assignment_weight").getAsInt());
            if (obj.has("level")) task.setLevel(obj.get("level").getAsInt());

            // Boolean field
            if (obj.has("is_unlocked")) task.setIsUnlocked(obj.get("is_unlocked").getAsBoolean());

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

            // Handle constraints - delegate to its deserializer
            if (obj.has("constraints"))
            {
                task.setConstraints(context.deserialize(obj.get("constraints"), TaskConstraints.class));
            }

            return task;
        }
    }
}
