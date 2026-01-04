package net.runelite.client.plugins.chunkblazer;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom deserializer that handles both single integers and arrays of integers.
 * This allows JSON like "region_id": 12595 or "region_id": [12850, 12950]
 */
public class IntOrArrayDeserializer implements JsonDeserializer<List<Integer>>
{
	@Override
	public List<Integer> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
		throws JsonParseException
	{
		List<Integer> result = new ArrayList<>();

		if (json.isJsonArray())
		{
			for (JsonElement element : json.getAsJsonArray())
			{
				result.add(element.getAsInt());
			}
		}
		else if (json.isJsonPrimitive())
		{
			result.add(json.getAsInt());
		}

		return result;
	}
}
