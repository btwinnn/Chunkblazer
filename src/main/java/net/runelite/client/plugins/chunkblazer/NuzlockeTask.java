package net.runelite.client.plugins.chunkblazer;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.util.List;

@Data
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
}
