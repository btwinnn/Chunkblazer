package com.chunkblazer;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that TargetNpc / RequiredItem cache the rolled random quantity so
 * repeated calls return the same value within a session. The original bug:
 * every call to getRequiredQuantity() generated a fresh Math.random() result,
 * so the panel and the chatbox disagreed on the same task's target, and chunk
 * unlocks / logins re-rolled the target Y.
 */
class RollCacheTest
{
	@Test
	void targetNpcWithFixedQuantityIsStable()
	{
		TargetNpc npc = new TargetNpc();
		npc.setQuantity(5);

		assertEquals(5, npc.getRequiredQuantity());
		assertEquals(5, npc.getRequiredQuantity());
	}

	@Test
	void targetNpcWithRangeRollsOnceAndCaches()
	{
		TargetNpc npc = new TargetNpc();
		npc.setQuantityRange(Arrays.asList(10, 50));

		int first = npc.getRequiredQuantity();
		assertTrue(first >= 10 && first <= 50, "rolled value out of range: " + first);

		// Hammer it — every subsequent call must return the same number.
		for (int i = 0; i < 100; i++)
		{
			assertEquals(first, npc.getRequiredQuantity());
		}
	}

	@Test
	void targetNpcSetRolledQuantityPinsValue()
	{
		TargetNpc npc = new TargetNpc();
		npc.setQuantityRange(Arrays.asList(10, 50));

		// Inject a persisted value, e.g. restored from saved config.
		npc.setRolledQuantity(37);

		assertEquals(37, npc.getRequiredQuantity());
		assertEquals(37, npc.getRequiredQuantity());
	}

	@Test
	void targetNpcSetRolledQuantityOverridesPriorRoll()
	{
		TargetNpc npc = new TargetNpc();
		npc.setQuantityRange(Arrays.asList(10, 50));

		int rolled = npc.getRequiredQuantity();
		// Even if we already rolled, an explicit set must win — this is what
		// initializeTask does to align the in-memory roll with the saved
		// target after a fresh roll has already happened earlier.
		npc.setRolledQuantity(99);
		assertEquals(99, npc.getRequiredQuantity());
		assertEquals(99, npc.getRequiredQuantity());
		assertTrue(rolled != 99 || true); // sanity, no-op assert
	}

	@Test
	void requiredItemWithFixedQuantityIsStable()
	{
		RequiredItem item = new RequiredItem();
		item.setQuantity(3);

		assertEquals(3, item.getRequiredQuantity());
		assertEquals(3, item.getRequiredQuantity());
	}

	@Test
	void requiredItemWithRangeRollsOnceAndCaches()
	{
		RequiredItem item = new RequiredItem();
		item.setQuantityRange(Arrays.asList(20, 100));

		int first = item.getRequiredQuantity();
		assertTrue(first >= 20 && first <= 100, "rolled value out of range: " + first);

		for (int i = 0; i < 100; i++)
		{
			assertEquals(first, item.getRequiredQuantity());
		}
	}

	@Test
	void requiredItemSetRolledQuantityPinsValue()
	{
		RequiredItem item = new RequiredItem();
		item.setQuantityRange(Arrays.asList(20, 100));

		item.setRolledQuantity(42);

		assertEquals(42, item.getRequiredQuantity());
		assertEquals(42, item.getRequiredQuantity());
	}

	@Test
	void independentInstancesRollIndependently()
	{
		// Two separate task instances should not share rolled values; each
		// caches on its own object.
		Set<Integer> rolls = new HashSet<>();
		for (int i = 0; i < 50; i++)
		{
			TargetNpc npc = new TargetNpc();
			npc.setQuantityRange(Arrays.asList(1, 1000));
			rolls.add(npc.getRequiredQuantity());
		}
		// With a 1-1000 range across 50 trials, getting >1 distinct value
		// is overwhelmingly likely. If we always got the same number, the
		// cache leaked across instances.
		assertTrue(rolls.size() > 1, "cache appears to be shared across instances");
	}

	// --- RequiredObject (parallel to RequiredItem / TargetNpc) ----------------------------------

	@Test
	void requiredObjectWithFixedQuantityIsStable()
	{
		RequiredObject ro = new RequiredObject();
		ro.setQuantity(7);
		assertEquals(7, ro.getRequiredQuantity());
		assertEquals(7, ro.getRequiredQuantity());
	}

	@Test
	void requiredObjectWithRangeRollsOnceAndCaches()
	{
		RequiredObject ro = new RequiredObject();
		ro.setQuantityRange(Arrays.asList(1, 20));

		int first = ro.getRequiredQuantity();
		assertTrue(first >= 1 && first <= 20, "rolled value out of range: " + first);

		for (int i = 0; i < 100; i++)
		{
			assertEquals(first, ro.getRequiredQuantity());
		}
	}

	@Test
	void requiredObjectSetRolledQuantityPinsValue()
	{
		RequiredObject ro = new RequiredObject();
		ro.setQuantityRange(Arrays.asList(1, 20));

		ro.setRolledQuantity(13);

		assertEquals(13, ro.getRequiredQuantity());
		assertEquals(13, ro.getRequiredQuantity());
	}

	@Test
	void requiredObjectRollSpreadsAcrossInstances()
	{
		// Same independence guarantee as the TargetNpc case — every freshly-
		// constructed RequiredObject must roll on its own; the cache cannot
		// leak across instances.
		Set<Integer> rolls = new HashSet<>();
		for (int i = 0; i < 50; i++)
		{
			RequiredObject ro = new RequiredObject();
			ro.setQuantityRange(Arrays.asList(1, 1000));
			rolls.add(ro.getRequiredQuantity());
		}
		assertTrue(rolls.size() > 1, "cache appears to be shared across instances");
	}

	// --- Deserializer round-trip tests ----------------------------------------------------------
	// Verify that JSON like "quantity": [1, 20] actually lands in quantityRange
	// (so the roll happens), and "quantity": 5 or [5] lands in quantity (so
	// nothing rolls and the value is fixed). The end-to-end story is:
	//   JSON "[min, max]" -> deserializer -> quantityRange -> getRequiredQuantity rolls.

	private static final Gson GSON = new Gson();

	@Test
	void requiredItemDeserializer_RangeArrayParsesToQuantityRange()
	{
		String json = "{\"item\": \"Cowhide\", \"item_ids\": [1739], \"quantity\": [1, 36]}";
		RequiredItem item = GSON.fromJson(json, RequiredItem.class);

		assertNull(item.getQuantity(), "fixed quantity must be null when JSON gives a range");
		assertNotNull(item.getQuantityRange(), "quantityRange must be populated from [min, max]");
		assertEquals(Arrays.asList(1, 36), item.getQuantityRange());

		int rolled = item.getRequiredQuantity();
		assertTrue(rolled >= 1 && rolled <= 36, "rolled out of range: " + rolled);
	}

	@Test
	void requiredItemDeserializer_SingleElementArrayParsesToFixedQuantity()
	{
		String json = "{\"item\": \"Cowhide\", \"item_ids\": [1739], \"quantity\": [5]}";
		RequiredItem item = GSON.fromJson(json, RequiredItem.class);

		assertEquals(Integer.valueOf(5), item.getQuantity(), "single-element array must be fixed quantity");
		assertNull(item.getQuantityRange(), "quantityRange must NOT be populated for [n]");
		assertEquals(5, item.getRequiredQuantity());
		assertEquals(5, item.getRequiredQuantity()); // stable
	}

	@Test
	void requiredItemDeserializer_IntParsesToFixedQuantity()
	{
		String json = "{\"item\": \"Cowhide\", \"item_ids\": [1739], \"quantity\": 5}";
		RequiredItem item = GSON.fromJson(json, RequiredItem.class);

		assertEquals(Integer.valueOf(5), item.getQuantity());
		assertNull(item.getQuantityRange());
	}

	@Test
	void requiredObjectDeserializer_RangeArrayParsesToQuantityRange()
	{
		String json = "{\"object\": [\"Falador Rooftop Edge\"], \"object_id\": [14925], \"quantity\": [1, 20]}";
		RequiredObject ro = GSON.fromJson(json, RequiredObject.class);

		assertNull(ro.getQuantity());
		assertNotNull(ro.getQuantityRange());
		assertEquals(Arrays.asList(1, 20), ro.getQuantityRange());

		int rolled = ro.getRequiredQuantity();
		assertTrue(rolled >= 1 && rolled <= 20, "rolled out of range: " + rolled);
	}

	@Test
	void requiredObjectDeserializer_SingleElementArrayParsesToFixedQuantity()
	{
		String json = "{\"object\": [\"Seed Stall\"], \"object_id\": [7053], \"quantity\": [1]}";
		RequiredObject ro = GSON.fromJson(json, RequiredObject.class);

		assertEquals(Integer.valueOf(1), ro.getQuantity());
		assertNull(ro.getQuantityRange());
	}

	@Test
	void targetNpcDeserializer_RangeArrayParsesToQuantityRange()
	{
		String json = "{\"npc\": [\"Man\"], \"npc_ids\": [3106], \"quantity\": [1, 28]}";
		TargetNpc npc = GSON.fromJson(json, TargetNpc.class);

		assertNull(npc.getQuantity());
		assertNotNull(npc.getQuantityRange());
		assertEquals(Arrays.asList(1, 28), npc.getQuantityRange());

		int rolled = npc.getRequiredQuantity();
		assertTrue(rolled >= 1 && rolled <= 28, "rolled out of range: " + rolled);
	}

	@Test
	void targetNpcDeserializer_IntParsesToFixedQuantity()
	{
		String json = "{\"npc\": [\"Man\"], \"npc_ids\": [3106], \"quantity\": 1}";
		TargetNpc npc = GSON.fromJson(json, TargetNpc.class);

		assertEquals(Integer.valueOf(1), npc.getQuantity());
		assertNull(npc.getQuantityRange());
	}

	// --- End-to-end: deserialize a real-shape rooftop task and roll the quantity ---------------

	@Test
	void requiredObject_EndToEnd_RooftopTaskRollsInRange()
	{
		// Same shape as Asgarnia_Tasks.json's "complete_falador_roof":
		// "required_object": { "object": [...], "object_id": [...], "quantity": [1, 20] }
		String json = "{"
			+ "\"object\": [\"Falador Rooftop Edge\"],"
			+ "\"object_id\": [14925],"
			+ "\"quantity\": [1, 20]"
			+ "}";
		RequiredObject ro = GSON.fromJson(json, RequiredObject.class);

		// First call rolls and caches; later calls must return the same value
		// (matches how initializeTask + module addActiveTask both call this).
		int target = ro.getRequiredQuantity();
		assertTrue(target >= 1 && target <= 20);
		for (int i = 0; i < 25; i++)
		{
			assertEquals(target, ro.getRequiredQuantity(), "subsequent calls must hit the cache");
		}
	}

	@Test
	void nuzlockeTask_AcceptsRequiredFinishedObjectAlias()
	{
		// CONSTRUCTION tasks in the canonical Tasks_JSON tree use the field name
		// `required_finished_object` instead of `required_object`. The data shape
		// is identical — only the name changed. The deserializer must populate
		// the same requiredObjects list either way, otherwise the precise build-
		// confirmation check in ConstructionModule silently fails to fire for
		// every renamed construction task (≈133 of them).
		String json = "{"
			+ "\"name\": \"Build a Medium STASH\","
			+ "\"taskID\": \"build_medium_stash\","
			+ "\"completion_type\": \"CONSTRUCTION\","
			+ "\"required_finished_object\": ["
			+ "  { \"object\": \"Medium STASH Unit (inconspicuous bush)\", \"object_id\": [29003] }"
			+ "]"
			+ "}";
		NuzlockeTask task = GSON.fromJson(json, NuzlockeTask.class);

		assertNotNull(task.getRequiredObjects(), "required_finished_object must populate requiredObjects");
		assertEquals(1, task.getRequiredObjects().size());
		RequiredObject ro = task.getRequiredObjects().get(0);
		assertEquals(Arrays.asList(29003), ro.getObjectIds());
		assertTrue(task.isHasRequiredObject(), "hasRequiredObject flag must be set");
	}

	@Test
	void nuzlockeTask_RequiredObjectAndFinishedObjectBehaveIdentically()
	{
		// Same data shape, two field-name aliases — both should produce the same
		// parsed task. This locks in the alias contract so a future refactor
		// can't silently break one path.
		String shared = "\"completion_type\":\"CONSTRUCTION\","
			+ "\"name\":\"t\",\"taskID\":\"t\","
			+ "_FIELD_:[{\"object\":\"X\",\"object_id\":[42]}]";
		String jsonOld = "{" + shared.replace("_FIELD_", "\"required_object\"") + "}";
		String jsonNew = "{" + shared.replace("_FIELD_", "\"required_finished_object\"") + "}";

		NuzlockeTask oldTask = GSON.fromJson(jsonOld, NuzlockeTask.class);
		NuzlockeTask newTask = GSON.fromJson(jsonNew, NuzlockeTask.class);

		assertEquals(oldTask.getRequiredObjects().get(0).getObjectIds(),
			newTask.getRequiredObjects().get(0).getObjectIds());
		assertEquals(oldTask.isHasRequiredObject(), newTask.isHasRequiredObject());
	}
}
