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
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The chunk unlock price curve: 1.0.9's prices up to the knee, a gentler climb to 17 after. */
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
		assertEquals(11, ChunkBlazerPlugin.curveUnlockCost(168), "Ben's next chunk, same as 1.0.9");
		assertEquals(12, ChunkBlazerPlugin.curveUnlockCost(192), "halfway, the knee");
		assertEquals(15, ChunkBlazerPlugin.curveUnlockCost(288), "75% of the map");
		assertEquals(17, ChunkBlazerPlugin.curveUnlockCost(CHUNKS), "the last chunk");
	}

	/** Up to the knee every price is exactly 1.0.9's, so current players never feel the change. */
	@Test
	void earlyGameUnchanged()
	{
		for (int r = 1; r <= ChunkBlazerPlugin.CURVE_KNEE; r++)
		{
			assertEquals(Math.max(1, Math.round(1 + 0.059 * r)), ChunkBlazerPlugin.curveUnlockCost(r), "rank " + r);
		}
		assertTrue(ChunkBlazerPlugin.curveUnlockCost(300) < Math.round(1 + 0.059 * 300), "late game is cheaper than 1.0.9");
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
	 * All paid chunks cost 80-92% of what the game deals: every chunk's 5-task unlock deal
	 * (averaged over seeded runs), plus the global pools. A hard late game, but
	 * finishable. The server checks the same band.
	 */
	@Test
	void mapCostInBand() throws Exception
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
		NuzlockeChunk start = null;
		List<NuzlockeChunk> dealing = new ArrayList<>();
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
			if (isStart)
			{
				start = c;
				continue;
			}
			dealing.add(c);
			if (!c.isCharter() && c.getUnlockCostValue() != 0)
			{
				paid++;
			}
		}
		Map<String, Integer> global = new HashMap<>();
		for (String file : new String[]{"Quest_Tasks.json", "Progression_Tasks.json", "Diary_Tasks.json"})
		{
			for (NuzlockeChunk group : chunksIn(file))
			{
				addLive(global, group.getTasks());
			}
		}
		double points = 0;
		for (int p : global.values())
		{
			points += p;
		}
		// Own every chunk, in a random order after the start chunk, as the plugin deals it.
		int runs = 50;
		for (int run = 0; run < runs; run++)
		{
			Random rnd = new Random(run);
			List<NuzlockeChunk> order = new ArrayList<>(dealing);
			Collections.shuffle(order, rnd);
			order.add(0, start);
			Set<String> taken = new HashSet<>();
			for (NuzlockeChunk c : order)
			{
				points += (double) deal(c, taken, rnd) / runs;
			}
		}
		int cost = ChunkBlazerPlugin.curveLedgerTotal(paid);
		double pct = 100.0 * cost / points;
		assertTrue(pct >= 80.0 && pct <= 92.0, "owning all " + paid + " paid chunks costs " + cost + ", "
			+ Math.round(pct) + "% of the " + Math.round(points) + " points the game deals (want 80-92%)");
	}

	/** The unlock roll: TASKS_PER_CHUNK live tasks by weight, never one already taken. Returns their points. */
	private static int deal(NuzlockeChunk chunk, Set<String> taken, Random rnd)
	{
		List<NuzlockeTask> avail = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (NuzlockeTask t : chunk.getTasks())
		{
			if (t.getTaskId() != null && !t.isLocked() && !taken.contains(t.getTaskId()) && ids.add(t.getTaskId()))
			{
				avail.add(t);
			}
		}
		int points = 0;
		for (int n = 0; n < ChunkBlazerPlugin.TASKS_PER_CHUNK && !avail.isEmpty(); n++)
		{
			int total = 0;
			for (NuzlockeTask t : avail)
			{
				total += Math.max(t.getAssignmentWeight(), 1);
			}
			int x = rnd.nextInt(total);
			for (NuzlockeTask t : avail)
			{
				x -= Math.max(t.getAssignmentWeight(), 1);
				if (x < 0)
				{
					points += t.getBasePoints();
					taken.add(t.getTaskId());
					avail.remove(t);
					break;
				}
			}
		}
		return points;
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
