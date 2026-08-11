package com.chunkblazer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;

/**
 * Renders chunk (region) borders on the 3D scene as continuous polylines,
 * one per visible region-side, instead of one disconnected segment per tile.
 *
 * <p>Why a separate class: the previous in-line implementation drew each
 * boundary tile's segment independently. Two problems with that:
 * <ol>
 *   <li>Vertex order in the polygon returned by
 *       {@link Perspective#getCanvasTilePoly} is <code>SW, SE, NE, NW</code>
 *       — not <code>SW, NW, NE, SE</code> — so picking "vertex 0 → vertex 1"
 *       for the west side actually drew the south side, producing
 *       perpendicular stripes that looked like tally marks.</li>
 *   <li>Even with the correct vertices, drawing each tile-segment with a
 *       fresh {@link Graphics2D#drawLine} call can leave visible joints
 *       across terrain elevation changes. Building a single {@link Path2D}
 *       per region-side and stroking it with round caps + joins gives a
 *       crisp continuous outline.</li>
 * </ol>
 *
 * <p>The chain-building logic ({@link #buildBorderChains}) is exposed
 * package-private and free of any RuneLite dependency so it can be unit
 * tested with a stubbed {@link TilePolygonProvider}.
 */
public class ChunkBorderRenderer
{
	// 64 tiles per region, both axes. Hard-coded for production; tests use a
	// smaller value via the static method overload below.
	static final int REGION_SIZE = 64;

	private static final Color UNLOCKED_BORDER = new Color(80, 220, 120, 200);
	private static final Color LOCKED_BORDER = new Color(60, 60, 60, 220);
	private static final Stroke BORDER_STROKE = new BasicStroke(
		1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	// NOTE: the per-tile translucent grey fill for locked chunks was removed
	// because rendering ~10k fillPolygon() calls per frame in Java2D tanked
	// framerate (Mike's friend hit ~15 FPS). The right place for that effect
	// is the GPU pipeline (cf. Region Locker plugin's GPU wrapper). Until
	// that's wired up, locked chunks are distinguished by border colour only.

	/**
	 * Identifies which side of a region a polyline traces. The vertex picks
	 * for that side are baked into the chain-building algorithm.
	 */
	enum Side
	{
		WEST, EAST, NORTH, SOUTH
	}

	/**
	 * One continuous run of points along a region's side. Production code
	 * draws this as a {@link Path2D}; tests inspect {@link #points} directly.
	 */
	static class BorderChain
	{
		final int regionId;
		final boolean unlocked;
		final Side side;
		final List<Point> points;

		BorderChain(int regionId, boolean unlocked, Side side, List<Point> points)
		{
			this.regionId = regionId;
			this.unlocked = unlocked;
			this.side = side;
			this.points = points;
		}
	}

	/**
	 * Provides the four canvas corners of a scene tile in vertex order
	 * <code>[SW, SE, NE, NW]</code>: <code>{x_sw, y_sw, x_se, y_se,
	 * x_ne, y_ne, x_nw, y_nw}</code>. Returns <code>null</code> if the tile
	 * isn't visible (out of scene, occluded, off-camera). Pure functional
	 * interface so tests can stub it without a RuneLite Client.
	 */
	@FunctionalInterface
	interface TilePolygonProvider
	{
		int[] cornersAt(int sx, int sy);
	}

	/**
	 * Render chunk borders + locked-region wash onto the supplied graphics.
	 * Called from {@link ChunkBlazerSceneOverlay#render}.
	 */
	public void render(Graphics2D graphics, Client client, IntPredicate isRegionUnlocked)
	{
		final int sceneSize = Constants.SCENE_SIZE;
		final int baseX = client.getBaseX();
		final int baseY = client.getBaseY();

		TilePolygonProvider provider = (sx, sy) ->
		{
			LocalPoint lp = LocalPoint.fromScene(sx, sy, client.getTopLevelWorldView());
			if (lp == null)
			{
				return null;
			}
			Polygon p = Perspective.getCanvasTilePoly(client, lp);
			if (p == null || p.npoints < 4)
			{
				return null;
			}
			// Vertex layout from Perspective.getCanvasTileAreaPoly:
			//   p1 = (-x,-y) = SW → index 0
			//   p2 = (+x,-y) = SE → index 1
			//   p3 = (+x,+y) = NE → index 2
			//   p4 = (-x,+y) = NW → index 3
			// (Perspective.java's local variable names "se"/"nw" are
			// swapped relative to OSRS world-axis convention; the actual
			// addPoint order is SW, SE, NE, NW.)
			return new int[] {
				p.xpoints[0], p.ypoints[0], // SW
				p.xpoints[1], p.ypoints[1], // SE
				p.xpoints[2], p.ypoints[2], // NE
				p.xpoints[3], p.ypoints[3]  // NW
			};
		};

		Stroke prev = graphics.getStroke();
		graphics.setStroke(BORDER_STROKE);

		// Build chained polylines and stroke each as one Path2D. (No per-tile
		// fill for locked chunks — see note on the constants above.)
		List<BorderChain> chains = buildBorderChains(
			sceneSize, baseX, baseY, isRegionUnlocked, provider, REGION_SIZE);
		for (BorderChain chain : chains)
		{
			if (chain.points.size() < 2)
			{
				continue;
			}
			graphics.setColor(chain.unlocked ? UNLOCKED_BORDER : LOCKED_BORDER);
			Path2D.Double path = new Path2D.Double();
			Point first = chain.points.get(0);
			path.moveTo(first.x, first.y);
			for (int i = 1; i < chain.points.size(); i++)
			{
				Point p = chain.points.get(i);
				path.lineTo(p.x, p.y);
			}
			graphics.draw(path);
		}

		graphics.setStroke(prev);
	}

	/**
	 * Walk each region-side and group its visible boundary tiles into
	 * continuous polylines. The result is at most 4 chains per visible
	 * region (W/E/N/S), but a side may be split into multiple chains if a
	 * tile in the middle is invisible (off-scene / occluded).
	 *
	 * <p>For a chain along the WEST side of a region, going north:
	 * for each tile in the run we emit its <em>start</em> vertex, then
	 * after the last tile we emit its <em>end</em> vertex. Adjacent tiles
	 * share corners exactly, so the start of tile N+1 equals the end of
	 * tile N — meaning the chain visits every shared corner once and
	 * traces the boundary cleanly. Same idea reflected per side:
	 * <table>
	 *   <tr><th>Side</th><th>Iterate axis</th><th>Start vertex</th><th>End vertex</th></tr>
	 *   <tr><td>WEST</td>  <td>sy ↑</td><td>SW</td><td>NW</td></tr>
	 *   <tr><td>EAST</td>  <td>sy ↑</td><td>SE</td><td>NE</td></tr>
	 *   <tr><td>SOUTH</td> <td>sx ↑</td><td>SW</td><td>SE</td></tr>
	 *   <tr><td>NORTH</td> <td>sx ↑</td><td>NW</td><td>NE</td></tr>
	 * </table>
	 *
	 * <p>Pure function — no Client / no graphics — so it's testable in
	 * isolation.
	 */
	static List<BorderChain> buildBorderChains(int sceneSize, int baseX, int baseY,
		IntPredicate isRegionUnlocked, TilePolygonProvider provider, int regionSize)
	{
		int regionMask = regionSize - 1;
		// log2(regionSize). regionId = (chunkX << 8) | chunkY, where chunkX
		// = worldX / regionSize. We bit-shift by this amount instead of
		// dividing so the test can use a small regionSize (e.g. 4) without
		// the production constant 64 leaking in.
		int regionShift = Integer.numberOfTrailingZeros(regionSize);
		List<BorderChain> out = new ArrayList<>();

		// Vertical edges: walk along sy for each sx that lies on a region boundary.
		for (int sx = 0; sx < sceneSize; sx++)
		{
			int rx = (baseX + sx) & regionMask;
			if (rx == 0)
			{
				walkAxis(out, sceneSize, baseX, baseY, sx, /*horizontal=*/false,
					isRegionUnlocked, provider, regionShift, Side.WEST);
			}
			if (rx == regionMask)
			{
				walkAxis(out, sceneSize, baseX, baseY, sx, /*horizontal=*/false,
					isRegionUnlocked, provider, regionShift, Side.EAST);
			}
		}
		// Horizontal edges: walk along sx for each sy that lies on a region boundary.
		for (int sy = 0; sy < sceneSize; sy++)
		{
			int ry = (baseY + sy) & regionMask;
			if (ry == 0)
			{
				walkAxis(out, sceneSize, baseX, baseY, sy, /*horizontal=*/true,
					isRegionUnlocked, provider, regionShift, Side.SOUTH);
			}
			if (ry == regionMask)
			{
				walkAxis(out, sceneSize, baseX, baseY, sy, /*horizontal=*/true,
					isRegionUnlocked, provider, regionShift, Side.NORTH);
			}
		}
		return out;
	}

	/**
	 * Walks one row or column of the scene along the supplied axis,
	 * emitting a {@link BorderChain} every time the run is interrupted by
	 * a region-id change or by an invisible tile.
	 */
	private static void walkAxis(List<BorderChain> out, int sceneSize, int baseX, int baseY,
		int fixed, boolean horizontal, IntPredicate isRegionUnlocked,
		TilePolygonProvider provider, int regionShift, Side side)
	{
		int currentRegionId = -1;
		boolean currentUnlocked = false;
		List<Point> currentPoints = null;

		// Iterate one past the end so the loop body can flush an open chain
		// when the iteration finishes.
		final int limit = sceneSize + 1;
		for (int i = 0; i < limit; i++)
		{
			int sx = horizontal ? i : fixed;
			int sy = horizontal ? fixed : i;
			boolean inBounds = i < sceneSize;

			int[] corners = inBounds ? provider.cornersAt(sx, sy) : null;
			int regionId = -1;
			if (inBounds && corners != null)
			{
				regionId = ((baseX + sx) >> regionShift) << 8 | ((baseY + sy) >> regionShift);
			}

			boolean isContinuationOfChain = corners != null
				&& currentPoints != null
				&& regionId == currentRegionId;

			if (!isContinuationOfChain)
			{
				// Flush any open chain. If the previous tile contributed an
				// "end" vertex, append it now to close the chain cleanly.
				if (currentPoints != null && currentPoints.size() >= 1)
				{
					int[] prevCorners = lastCorners(provider, side, sx, sy, horizontal);
					if (prevCorners != null)
					{
						currentPoints.add(endVertex(prevCorners, side));
					}
					if (currentPoints.size() >= 2)
					{
						out.add(new BorderChain(currentRegionId, currentUnlocked, side, currentPoints));
					}
					currentPoints = null;
					currentRegionId = -1;
				}

				// Open a new chain if the new tile is visible.
				if (corners != null)
				{
					currentRegionId = regionId;
					currentUnlocked = isRegionUnlocked.test(regionId);
					currentPoints = new ArrayList<>();
				}
			}

			if (corners != null)
			{
				currentPoints.add(startVertex(corners, side));
			}
		}
	}

	/**
	 * Return the corners of the tile that came BEFORE position (sx, sy) along
	 * the iteration axis. We need this when flushing an open chain because
	 * the chain's "end vertex" is on the prior tile, not on the current
	 * (boundary or invisible) one.
	 */
	private static int[] lastCorners(TilePolygonProvider provider, Side side,
		int sx, int sy, boolean horizontal)
	{
		int prevSx = horizontal ? sx - 1 : sx;
		int prevSy = horizontal ? sy : sy - 1;
		if (prevSx < 0 || prevSy < 0)
		{
			return null;
		}
		return provider.cornersAt(prevSx, prevSy);
	}

	/** Vertex layout: 0=SWx 1=SWy, 2=SEx 3=SEy, 4=NEx 5=NEy, 6=NWx 7=NWy. */
	private static Point startVertex(int[] c, Side side)
	{
		switch (side)
		{
			case WEST:  return new Point(c[0], c[1]); // SW
			case EAST:  return new Point(c[2], c[3]); // SE
			case SOUTH: return new Point(c[0], c[1]); // SW
			case NORTH: return new Point(c[6], c[7]); // NW
			default: throw new IllegalArgumentException("unknown side " + side);
		}
	}

	private static Point endVertex(int[] c, Side side)
	{
		switch (side)
		{
			case WEST:  return new Point(c[6], c[7]); // NW
			case EAST:  return new Point(c[4], c[5]); // NE
			case SOUTH: return new Point(c[2], c[3]); // SE
			case NORTH: return new Point(c[4], c[5]); // NE
			default: throw new IllegalArgumentException("unknown side " + side);
		}
	}
}
