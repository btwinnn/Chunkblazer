package net.runelite.client.plugins.chunkblazer;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors {@link RequiredItem} but for GameObjects. Used by THIEVING tasks
 * that target stalls/chests (Seed Stall, Fortunato's Market Stall, etc.)
 * where the target is a GameObject rather than an NPC.
 */
@Data
@JsonAdapter(RequiredObject.RequiredObjectDeserializer.class)
public class RequiredObject
{
	private String object;

	@SerializedName("object_id")
	private List<Integer> objectIds;

	private Integer quantity;

	@SerializedName("quantity_range")
	private List<Integer> quantityRange;

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
			int min = quantityRange.get(0);
			int max = quantityRange.get(1);
			rolledQuantity = min + (int) (Math.random() * (max - min + 1));
			return rolledQuantity;
		}
		return 1;
	}

	public void setRolledQuantity(int qty)
	{
		this.rolledQuantity = qty;
	}

	public void clearRolledQuantity()
	{
		this.rolledQuantity = null;
	}

	public boolean matchesObjectId(int objectId)
	{
		return objectIds != null && objectIds.contains(objectId);
	}

	public int getFirstObjectId()
	{
		return objectIds != null && !objectIds.isEmpty() ? objectIds.get(0) : -1;
	}

	public List<Integer> getObjectIds()
	{
		return objectIds;
	}

	public static class RequiredObjectDeserializer implements JsonDeserializer<RequiredObject>
	{
		@Override
		public RequiredObject deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
			throws JsonParseException
		{
			RequiredObject ro = new RequiredObject();
			JsonObject obj = json.getAsJsonObject();

			if (obj.has("object"))
			{
				JsonElement el = obj.get("object");
				if (el.isJsonArray())
				{
					JsonArray arr = el.getAsJsonArray();
					if (arr.size() > 0)
					{
						ro.setObject(arr.get(0).getAsString());
					}
				}
				else if (el.isJsonPrimitive())
				{
					ro.setObject(el.getAsString());
				}
			}

			if (obj.has("object_id"))
			{
				JsonArray arr = obj.getAsJsonArray("object_id");
				List<Integer> ids = new ArrayList<>();
				for (JsonElement e : arr)
				{
					ids.add(e.getAsInt());
				}
				ro.setObjectIds(ids);
			}

			if (obj.has("quantity"))
			{
				JsonElement qtyEl = obj.get("quantity");
				if (qtyEl.isJsonArray())
				{
					JsonArray arr = qtyEl.getAsJsonArray();
					if (arr.size() == 1)
					{
						ro.setQuantity(arr.get(0).getAsInt());
					}
					else if (arr.size() >= 2)
					{
						List<Integer> range = new ArrayList<>();
						for (JsonElement e : arr)
						{
							range.add(e.getAsInt());
						}
						ro.setQuantityRange(range);
					}
				}
				else if (qtyEl.isJsonPrimitive())
				{
					ro.setQuantity(qtyEl.getAsInt());
				}
			}

			if (obj.has("quantity_range"))
			{
				JsonArray arr = obj.getAsJsonArray("quantity_range");
				List<Integer> range = new ArrayList<>();
				for (JsonElement e : arr)
				{
					range.add(e.getAsInt());
				}
				ro.setQuantityRange(range);
			}

			return ro;
		}
	}
}
