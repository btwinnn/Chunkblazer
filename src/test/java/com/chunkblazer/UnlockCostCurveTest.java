package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The scaling chunk-unlock cost curve. cost(r) = 1 + b*r^gamma, rounded, minimum 1,
 * calibrated (2026-09) so owning the whole map (388 payable chunks) costs about 80%
 * of the 9,096-point pool. These lock the calibration in: a casual tweak to CURVE_B
 * or CURVE_GAMMA that moves the endgame off ~80% fails here rather than shipping.
 */
class UnlockCostCurveTest
{
	private static final int CHUNKS = 388;
	private static final int POOL = 9096;

	@Test
	void floorIsAlwaysAtLeastOne()
	{
		assertEquals(1, ChunkBlazerPlugin.curveUnlockCost(0), "rank below 1 clamps to 1");
		assertEquals(1, ChunkBlazerPlugin.curveUnlockCost(1), "the very first paid unlock costs 1 pt");
	}

	@Test
	void knownMilestonesMatchTheSimulator()
	{
		// The whole-number prices we agreed on in the planning simulator.
		assertEquals(5, ChunkBlazerPlugin.curveUnlockCost(39), "10% of the map");
		assertEquals(10, ChunkBlazerPlugin.curveUnlockCost(97), "25% of the map");
		assertEquals(19, ChunkBlazerPlugin.curveUnlockCost(194), "halfway");
		assertEquals(28, ChunkBlazerPlugin.curveUnlockCost(291), "75% of the map");
		assertEquals(36, ChunkBlazerPlugin.curveUnlockCost(CHUNKS), "the last chunk");
	}

	@Test
	void costNeverDecreasesAsYouUnlockMore()
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
	void owningTheWholeMapCostsAboutEightyPercentOfThePool()
	{
		int total = ChunkBlazerPlugin.curveLedgerTotal(CHUNKS);
		double pct = 100.0 * total / POOL;
		// Round-to-nearest keeps the drift tiny; guard a generous band so adding a
		// few chunks doesn't break the build, but a real miscalibration does.
		assertTrue(pct >= 78.0 && pct <= 82.0,
			"unlocking all " + CHUNKS + " chunks should cost ~80% of the pool, got "
				+ total + " pts (" + Math.round(pct) + "%)");
	}

	@Test
	void ledgerIsTheRunningSumOfPerChunkCosts()
	{
		int manual = 0;
		for (int k = 1; k <= 50; k++)
		{
			manual += ChunkBlazerPlugin.curveUnlockCost(k);
		}
		assertEquals(manual, ChunkBlazerPlugin.curveLedgerTotal(50),
			"curveLedgerTotal(n) must equal the sum of curveUnlockCost(1..n)");
	}
}
