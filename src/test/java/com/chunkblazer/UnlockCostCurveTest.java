package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The chunk unlock price curve: cost(r) = 1 + b*r, rounded, minimum 1. */
class UnlockCostCurveTest
{
	private static final int CHUNKS = 384;

	@Test
	void floorIsOne()
	{
		assertEquals(1, ChunkBlazerPlugin.curveUnlockCost(0), "rank below 1 clamps to 1");
		assertEquals(1, ChunkBlazerPlugin.curveUnlockCost(1), "the very first paid unlock costs 1 pt");
	}

	@Test
	void milestonesMatch()
	{
		assertEquals(3, ChunkBlazerPlugin.curveUnlockCost(38), "10% of the map");
		assertEquals(7, ChunkBlazerPlugin.curveUnlockCost(96), "25% of the map");
		assertEquals(12, ChunkBlazerPlugin.curveUnlockCost(192), "halfway");
		assertEquals(18, ChunkBlazerPlugin.curveUnlockCost(288), "75% of the map");
		assertEquals(24, ChunkBlazerPlugin.curveUnlockCost(CHUNKS), "the last chunk");
	}

	@Test
	void neverDecreases()
	{
		int prev = 0;
		for (int r = 1; r <= CHUNKS; r++)
		{
			int c = ChunkBlazerPlugin.curveUnlockCost(r);
			assertTrue(c >= prev, "cost must be non-decreasing in rank (r=" + r + ")");
			prev = c;
		}
		assertTrue(ChunkBlazerPlugin.curveUnlockCost(CHUNKS) > ChunkBlazerPlugin.curveUnlockCost(1),
			"the endgame must cost more than the opening");
	}

	@Test
	void ledgerSumsCosts()
	{
		int manual = 0;
		for (int k = 1; k <= 50; k++)
		{
			manual += ChunkBlazerPlugin.curveUnlockCost(k);
		}
		assertEquals(manual, ChunkBlazerPlugin.curveLedgerTotal(50),
			"curveLedgerTotal(n) must equal the sum of curveUnlockCost(1..n)");
	}

	/** No refund: the repair's legacy-curve ceiling must cover what the current curve charges. */
	@Test
	void legacyCoversCurrent()
	{
		for (int n = 0; n <= 500; n++)
		{
			assertTrue(ChunkBlazerPlugin.legacyCurveLedgerTotal(n) >= ChunkBlazerPlugin.curveLedgerTotal(n),
				"legacy ledger below the current one at n=" + n);
		}
	}

	/**
	 * All paid chunks cost about 80% of what refill can deal: each live non-boss task
	 * once, plus quests and progression. The server checks the same band (curveB).
	 */
	@Test
	void mapCostsEightyPercent() throws Exception
	{
		Set<Integer> free = new HashSet<>();
		for (NuzlockeChunk c : chunksIn("Free_Chunks.json"))
		{
			if (c.getRegionIds() != null)
			{
				free.addAll(c.getRegionIds());
			}
		}
		// Last file loaded wins, as in the plugin.
		Map<Integer, NuzlockeChunk> byRegion = new HashMap<>();
		List<NuzlockeChunk> all = new ArrayList<>();
		for (String file : chunkFiles())
		{
			for (NuzlockeChunk c : chunksIn(file))
			{
				all.add(c);
				for (Integer r : c.getRegionIds())
				{
					byRegion.put(r, c);
				}
			}
		}
		Set<NuzlockeChunk> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<String, Integer> pool = new HashMap<>();
		int paid = 0;
		for (NuzlockeChunk c : all)
		{
			boolean reachable = false;
			boolean isFree = false;
			boolean isStart = false;
			for (Integer r : c.getRegionIds())
			{
				reachable |= byRegion.get(r) == c;
				isFree |= free.contains(r);
				isStart |= r == 12850;
			}
			if (!reachable || c.isBoss() || isFree || !seen.add(c))
			{
				continue;
			}
			if (!isStart && !c.isCharter() && c.getUnlockCostValue() != 0)
			{
				paid++;
			}
			addLive(pool, c.getTasks());
		}
		for (String file : new String[]{"Quest_Tasks.json", "Progression_Tasks.json"})
		{
			for (NuzlockeChunk group : chunksIn(file))
			{
				addLive(pool, group.getTasks());
			}
		}
		int points = 0;
		for (int p : pool.values())
		{
			points += p;
		}
		int cost = ChunkBlazerPlugin.curveLedgerTotal(paid);
		double pct = 100.0 * cost / points;
		assertTrue(pct >= 75.0 && pct <= 85.0, "owning all " + paid + " paid chunks costs " + cost + ", "
			+ Math.round(pct) + "% of the " + points + " points refill can deal (want 75-85%)");
	}

	private static void addLive(Map<String, Integer> pool, List<NuzlockeTask> tasks)
	{
		if (tasks == null)
		{
			return;
		}
		for (NuzlockeTask t : tasks)
		{
			if (t.getTaskId() != null && !t.isLocked())
			{
				pool.putIfAbsent(t.getTaskId(), t.getBasePoints());
			}
		}
	}

	/** The plugin's chunk files, in its load order. */
	private static String[] chunkFiles() throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField("TASK_JSON_FILES");
		f.setAccessible(true);
		return (String[]) f.get(null);
	}

	private static List<NuzlockeChunk> chunksIn(String file) throws Exception
	{
		String json = ShippedSeed.fileContent(file);
		assertNotNull(json, file + " is missing from the shipped seed");
		Map<String, List<NuzlockeChunk>> data = new Gson().fromJson(json,
			new TypeToken<Map<String, List<NuzlockeChunk>>>()
			{
			}.getType());
		List<NuzlockeChunk> out = new ArrayList<>();
		for (List<NuzlockeChunk> groups : data.values())
		{
			if (groups != null)
			{
				out.addAll(groups);
			}
		}
		return out;
	}
}
