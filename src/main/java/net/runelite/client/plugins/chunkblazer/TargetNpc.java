package net.runelite.client.plugins.chunkblazer;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.util.List;

@Data
public class TargetNpc
{
    private String name;

    @SerializedName("npc_ids")
    private List<Integer> npcIds;

    private Integer quantity;

    @SerializedName("quantity_range")
    private List<Integer> quantityRange;

    public int getRequiredQuantity()
    {
        if (quantity != null)
        {
            return quantity;
        }
        if (quantityRange != null && quantityRange.size() >= 2)
        {
            int min = quantityRange.get(0);
            int max = quantityRange.get(1);
            return min + (int) (Math.random() * (max - min + 1));
        }
        return 1;
    }

    /**
     * Check if the given NPC ID matches this target.
     */
    public boolean matchesNpcId(int npcId)
    {
        return npcIds != null && npcIds.contains(npcId);
    }

    /**
     * Get the first NPC ID (for display purposes).
     */
    public int getFirstNpcId()
    {
        return npcIds != null && !npcIds.isEmpty() ? npcIds.get(0) : -1;
    }
}
