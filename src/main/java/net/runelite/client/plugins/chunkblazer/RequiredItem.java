package net.runelite.client.plugins.chunkblazer;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@Data
@JsonAdapter(RequiredItem.RequiredItemDeserializer.class)
public class RequiredItem
{
	private String item;

	@SerializedName("item_ids")
	private List<Integer> itemIds;

	private String action;
	private Integer quantity;

	@SerializedName("quantity_range")
	private List<Integer> quantityRange;

	// Cached random roll for this session. See TargetNpc#rolledQuantity for the
	// full rationale — short version: every getRequiredQuantity() call would
	// otherwise generate a fresh Math.random() result, causing the panel and
	// the chatbox to display different totals for the same task.
	private transient Integer rolledQuantity;

	public int getRequiredQuantity()
	{
		if (quantity != null)
		{
			return quantity;
		}
		if (rolledQuantity != null)
		{
			return rolledQuantity;
		}
		if (quantityRange != null && quantityRange.size() >= 2)
		{
			// Return a random value in range for assignment
			int min = quantityRange.get(0);
			int max = quantityRange.get(1);
			rolledQuantity = min + (int) (Math.random() * (max - min + 1));
			return rolledQuantity;
		}
		return 1;
	}

	/**
	 * Pin the rolled quantity to a specific value. Used to restore a persisted
	 * target across sessions so the in-memory roll matches what was saved.
	 */
	public void setRolledQuantity(int qty)
	{
		this.rolledQuantity = qty;
	}

	/**
	 * Drop the cached roll so the next getRequiredQuantity() call rolls fresh.
	 * Called from initializeTask's no-saved-target branch — without it, an
	 * in-memory roll left over from an earlier load (e.g. a value pinned from
	 * a now-cleared config entry) gets reused instead of a real fresh roll,
	 * which made devResetAll a no-op for any task with stale state.
	 */
	public void clearRolledQuantity()
	{
		this.rolledQuantity = null;
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

	/**
	 * Custom deserializer that handles flexible JSON formats:
	 * - "item" can be either a string or a single-element array: "item": "X" or "item": ["X"]
	 * - "quantity" can be either an int or an array: "quantity": 1 or "quantity": [1] or "quantity": [1, 50]
	 * - If "quantity" is a 2+ element array, it's treated as a range
	 */
	public static class RequiredItemDeserializer implements JsonDeserializer<RequiredItem>
	{
		@Override
		public RequiredItem deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
			throws JsonParseException
		{
			RequiredItem item = new RequiredItem();
			JsonObject obj = json.getAsJsonObject();

			// Handle "item" field - can be string or array of strings
			if (obj.has("item"))
			{
				JsonElement itemElement = obj.get("item");
				if (itemElement.isJsonArray())
				{
					JsonArray arr = itemElement.getAsJsonArray();
					if (arr.size() > 0)
					{
						item.setItem(arr.get(0).getAsString());
					}
				}
				else if (itemElement.isJsonPrimitive())
				{
					item.setItem(itemElement.getAsString());
				}
			}

			// Handle "item_ids" field - array of integers
			if (obj.has("item_ids"))
			{
				JsonArray arr = obj.getAsJsonArray("item_ids");
				List<Integer> ids = new ArrayList<>();
				for (JsonElement e : arr)
				{
					ids.add(e.getAsInt());
				}
				item.setItemIds(ids);
			}

			// Handle "action" field
			if (obj.has("action"))
			{
				item.setAction(obj.get("action").getAsString());
			}

			// Handle "quantity" field - can be int, single-element array, or range array
			if (obj.has("quantity"))
			{
				JsonElement qtyElement = obj.get("quantity");
				if (qtyElement.isJsonArray())
				{
					JsonArray arr = qtyElement.getAsJsonArray();
					if (arr.size() == 1)
					{
						// Single element array - treat as quantity
						item.setQuantity(arr.get(0).getAsInt());
					}
					else if (arr.size() >= 2)
					{
						// Multiple elements - treat as quantity range
						List<Integer> range = new ArrayList<>();
						for (JsonElement e : arr)
						{
							range.add(e.getAsInt());
						}
						item.setQuantityRange(range);
					}
				}
				else if (qtyElement.isJsonPrimitive())
				{
					item.setQuantity(qtyElement.getAsInt());
				}
			}

			// Handle "quantity_range" field (if explicitly provided)
			if (obj.has("quantity_range"))
			{
				JsonArray arr = obj.getAsJsonArray("quantity_range");
				List<Integer> range = new ArrayList<>();
				for (JsonElement e : arr)
				{
					range.add(e.getAsInt());
				}
				item.setQuantityRange(range);
			}

			return item;
		}
	}
}
