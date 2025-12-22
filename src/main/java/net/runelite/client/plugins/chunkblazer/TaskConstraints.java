package net.runelite.client.plugins.chunkblazer;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.util.List;

@Data
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
}
