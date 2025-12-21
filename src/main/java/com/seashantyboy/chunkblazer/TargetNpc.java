package com.seashantyboy.chunkblazer;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.util.List;

@Data
public class TargetNpc
{
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
}
