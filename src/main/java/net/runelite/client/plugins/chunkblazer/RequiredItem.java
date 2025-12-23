package net.runelite.client.plugins.chunkblazer;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.util.List;

@Data
public class RequiredItem
{
    private String item;

    @SerializedName("item_ids")
    private List<Integer> itemIds;

    private String action;
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
            // Return a random value in range for assignment
            int min = quantityRange.get(0);
            int max = quantityRange.get(1);
            return min + (int) (Math.random() * (max - min + 1));
        }
        return 1;
    }

    /**
     * Check if the given item ID matches this required item.
     */
    public boolean matchesItemId(int itemId)
    {
        return itemIds != null && itemIds.contains(itemId);
    }

    /**
     * Get the first item ID (for display purposes).
     */
    public int getFirstItemId()
    {
        return itemIds != null && !itemIds.isEmpty() ? itemIds.get(0) : -1;
    }

    /**
     * Get all item IDs.
     */
    public List<Integer> getItemIds()
    {
        return itemIds;
    }
}
