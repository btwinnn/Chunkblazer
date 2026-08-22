package com.chunkblazer;

import java.awt.Point;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ChunkBorderRenderer#buildBorderChains} — the geometry
 * grouping logic that turns scene tiles into continuous boundary
 * polylines. Stubs the tile-corner provider so no RuneLite Client is
 * needed.
 */
class ChunkBorderRendererTest
{
	/**
	 * Synthetic provider that maps tile (sx, sy) to a 10×10 canvas square
	 * at (sx*10, sy*10). Optional <code>hidden</code> set marks tiles as
	 * invisible (provider returns null) — used to simulate scene clipping.
	 *
	 * <p>Vertex layout matches what {@link ChunkBorderRenderer} expects:
	 * <code>[SW, SE, NE, NW]</code>.
	 */
	private static final class GridProvider implements ChunkBorderRenderer.TilePolygonProvider
	{
		private final Set<String> hidden;

		GridProvider(String... hiddenKeys)
		{
			this.hidden = new HashSet<>(Arrays.asList(hiddenKeys));
		}

		@Override
		public int[] cornersAt(int sx, int sy)
		{
			if (hidden.contains(sx + "," + sy))
			{
				return null;
			}
			int x0 = sx * 10;
			int y0 = sy * 10;
			return new int[] {
				x0,      y0,        // SW
				x0 + 10, y0,        // SE
				x0 + 10, y0 + 10,   // NE
				x0,      y0 + 10    // NW
			};
		}
	}

	private static ChunkBorderRenderer.BorderChain findChain(
		List<ChunkBorderRenderer.BorderChain> chains,
		int regionId,
		ChunkBorderRenderer.Side side)
	{
		return chains.stream()
			.filter(c -> c.regionId == regionId && c.side == side)
			.findFirst()
			.orElse(null);
	}

	@Test
	void westEdgeOfRegionFormsContinuousChain()
	{
		// 4×4 scene divided into a single 4-tile region. The west edge of
		// that region runs through tiles (sx=0, sy=0..3) — expect ONE
		// continuous chain visiting SW₀, SW₁, SW₂, SW₃, NW₃.
		final int regionSize = 4;
		final int sceneSize = 4;
		IntPredicate locked = id -> false;

		List<ChunkBorderRenderer.BorderChain> chains = ChunkBorderRenderer.buildBorderChains(
			sceneSize, 0, 0, locked, new GridProvider(), regionSize);

		ChunkBorderRenderer.BorderChain west = findChain(
			chains, /*regionId=*/0, ChunkBorderRenderer.Side.WEST);
		assertNotNull(west, "expected a WEST-side chain for region 0");

		// 4 tiles' SW points + 1 closing NW point = 5 points.
		assertEquals(5, west.points.size());
		assertEquals(new Point(0, 0), west.points.get(0));
		assertEquals(new Point(0, 10), west.points.get(1));
		assertEquals(new Point(0, 20), west.points.get(2));
		assertEquals(new Point(0, 30), west.points.get(3));
		assertEquals(new Point(0, 40), west.points.get(4));
	}

	@Test
	void invisibleTileSplitsChainIntoTwo()
	{
		// Same 4-tile region, but the middle tile (sx=0, sy=2) is hidden
		// — so the west edge should split into two chains: tiles 0..1 and
		// tile 3 (degenerate single-tile chain still gets the closing
		// vertex).
		final int regionSize = 4;
		final int sceneSize = 4;
		IntPredicate locked = id -> false;

		List<ChunkBorderRenderer.BorderChain> chains = ChunkBorderRenderer.buildBorderChains(
			sceneSize, 0, 0, locked, new GridProvider("0,2"), regionSize);

		List<ChunkBorderRenderer.BorderChain> westChains = chains.stream()
			.filter(c -> c.side == ChunkBorderRenderer.Side.WEST && c.regionId == 0)
			.collect(java.util.stream.Collectors.toList());
		assertEquals(2, westChains.size(), "hidden tile should split the chain in two");

		// First sub-chain: tiles sy=0,1 → SW₀, SW₁, NW₁ = (0,0),(0,10),(0,20)
		assertEquals(3, westChains.get(0).points.size());
		assertEquals(new Point(0, 0), westChains.get(0).points.get(0));
		assertEquals(new Point(0, 20), westChains.get(0).points.get(2));

		// Second sub-chain: tile sy=3 alone → SW₃, NW₃ = (0,30),(0,40)
		assertEquals(2, westChains.get(1).points.size());
		assertEquals(new Point(0, 30), westChains.get(1).points.get(0));
		assertEquals(new Point(0, 40), westChains.get(1).points.get(1));
	}

	@Test
	void adjacentRegionsProduceSeparateChains()
	{
		// 8×4 scene split into two regions side-by-side: region 0 (sx=0..3)
		// and region 256 (sx=4..7). Their SHARED interior boundary becomes
		// the east side of region 0 AND the west side of region 256 — both
		// drawn, independent chains.
		final int regionSize = 4;
		final int sceneSize = 8;
		final int sceneSizeY = 4;
		IntPredicate locked = id -> false;

		List<ChunkBorderRenderer.BorderChain> chains = ChunkBorderRenderer.buildBorderChains(
			sceneSizeY * 2, 0, 0, locked, new GridProvider(), regionSize);

		// Region 0's east side: tiles sx=3, sy=0..3, vertex pair (SE, NE).
		ChunkBorderRenderer.BorderChain eastOfRegion0 = findChain(
			chains, /*regionId=*/0, ChunkBorderRenderer.Side.EAST);
		assertNotNull(eastOfRegion0);
		assertEquals(new Point(40, 0), eastOfRegion0.points.get(0));
		assertEquals(new Point(40, 40), eastOfRegion0.points.get(eastOfRegion0.points.size() - 1));

		// Region (1<<8)|0 = 256: its west side: tiles sx=4, sy=0..3, vertex pair (SW, NW).
		ChunkBorderRenderer.BorderChain westOfRegion256 = findChain(
			chains, /*regionId=*/256, ChunkBorderRenderer.Side.WEST);
		assertNotNull(westOfRegion256);
		assertEquals(new Point(40, 0), westOfRegion256.points.get(0));
		assertEquals(new Point(40, 40), westOfRegion256.points.get(westOfRegion256.points.size() - 1));

		// They overlap geometrically (the shared boundary), but they're
		// distinct BorderChain objects — confirms the algorithm doesn't
		// dedupe across regions, so a locked vs unlocked neighbor would
		// each get their own colored stroke.
		assertTrue(eastOfRegion0 != westOfRegion256);
	}

	@Test
	void lockedFlagReflectsTheCallback()
	{
		// Region 0 unlocked, region 256 locked. The chain for each should
		// reflect this in its 'unlocked' flag — that's what drives the
		// border colour in production.
		final int regionSize = 4;
		final int sceneSize = 8;
		IntPredicate isUnlocked = id -> id == 0;

		List<ChunkBorderRenderer.BorderChain> chains = ChunkBorderRenderer.buildBorderChains(
			sceneSize, 0, 0, isUnlocked, new GridProvider(), regionSize);

		ChunkBorderRenderer.BorderChain westOfRegion0 = findChain(
			chains, /*regionId=*/0, ChunkBorderRenderer.Side.WEST);
		ChunkBorderRenderer.BorderChain westOfRegion256 = findChain(
			chains, /*regionId=*/256, ChunkBorderRenderer.Side.WEST);

		assertNotNull(westOfRegion0);
		assertNotNull(westOfRegion256);
		assertTrue(westOfRegion0.unlocked, "region 0 should report as unlocked");
		assertTrue(!westOfRegion256.unlocked, "region 256 should report as locked");
	}

	@Test
	void northSideUsesNwAndNeVertices()
	{
		// Sanity-check the north side picks NW->NE vertices, not SW->SE.
		// Region 0 north edge: tiles sy=3, sx=0..3 — chain visits NW for
		// each then NE of the last.
		final int regionSize = 4;
		final int sceneSize = 4;
		IntPredicate locked = id -> false;

		List<ChunkBorderRenderer.BorderChain> chains = ChunkBorderRenderer.buildBorderChains(
			sceneSize, 0, 0, locked, new GridProvider(), regionSize);

		ChunkBorderRenderer.BorderChain north = findChain(
			chains, /*regionId=*/0, ChunkBorderRenderer.Side.NORTH);
		assertNotNull(north);

		// 4 tiles' NW corners + 1 closing NE = 5 points along y=40.
		assertEquals(5, north.points.size());
		for (Point p : north.points)
		{
			assertEquals(40, p.y, "north edge should run along y=40 (top of region)");
		}
		assertEquals(new Point(0, 40), north.points.get(0));
		assertEquals(new Point(40, 40), north.points.get(4));
	}
}
