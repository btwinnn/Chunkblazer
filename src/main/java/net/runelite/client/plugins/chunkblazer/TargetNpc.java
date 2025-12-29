package net.runelite.client.plugins.chunkblazer;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@Data
@JsonAdapter(TargetNpc.TargetNpcDeserializer.class)
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

    /**
     * Custom deserializer that handles flexible JSON formats:
     * - "name" can be a string or single-element array
     * - "quantity" can be int, single-element array, or range array
     */
    public static class TargetNpcDeserializer implements JsonDeserializer<TargetNpc>
    {
        @Override
        public TargetNpc deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException
        {
            // Handle case where target_npc is an array instead of object
            if (json.isJsonArray())
            {
                JsonArray arr = json.getAsJsonArray();
                if (arr.size() == 0)
                {
                    return new TargetNpc();
                }
                // Deserialize first element
                JsonElement first = arr.get(0);
                if (first.isJsonObject())
                {
                    return deserialize(first, typeOfT, context);
                }
                return new TargetNpc();
            }

            TargetNpc npc = new TargetNpc();
            JsonObject obj = json.getAsJsonObject();

            // Handle "name" field - can be string or array
            if (obj.has("name"))
            {
                JsonElement nameElement = obj.get("name");
                if (nameElement.isJsonArray())
                {
                    JsonArray arr = nameElement.getAsJsonArray();
                    if (arr.size() > 0)
                    {
                        npc.setName(arr.get(0).getAsString());
                    }
                }
                else if (nameElement.isJsonPrimitive())
                {
                    npc.setName(nameElement.getAsString());
                }
            }

            // Handle "npc_ids" field - array of integers
            if (obj.has("npc_ids"))
            {
                JsonArray arr = obj.getAsJsonArray("npc_ids");
                List<Integer> ids = new ArrayList<>();
                for (JsonElement e : arr)
                {
                    ids.add(e.getAsInt());
                }
                npc.setNpcIds(ids);
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
                        npc.setQuantity(arr.get(0).getAsInt());
                    }
                    else if (arr.size() >= 2)
                    {
                        // Multiple elements - treat as quantity range
                        List<Integer> range = new ArrayList<>();
                        for (JsonElement e : arr)
                        {
                            range.add(e.getAsInt());
                        }
                        npc.setQuantityRange(range);
                    }
                }
                else if (qtyElement.isJsonPrimitive())
                {
                    npc.setQuantity(qtyElement.getAsInt());
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
                npc.setQuantityRange(range);
            }

            return npc;
        }
    }
}
