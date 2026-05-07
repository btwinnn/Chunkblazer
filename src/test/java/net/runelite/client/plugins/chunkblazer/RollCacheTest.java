package net.runelite.client.plugins.chunkblazer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
