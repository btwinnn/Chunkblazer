package net.runelite.client.plugins.chunkblazer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Draws chunk (region) boundaries on the 3D game scene. Each region is 64×64
 * tiles; borders are drawn as line segments along the edge tiles, and locked
 * regions get a translucent grey fill (à la Region Locker).
 *
 * Walks the loaded scene tile-by-tile rather than projecting whole-region
 * polygons because adjacent regions' centers are usually outside the loaded
 * scene, which makes Perspective.getCanvasTileAreaPoly return null. Walking
 * the scene means every tile that's actually visible gets drawn, regardless of
 * which region it belongs to.
 */
@Slf4j
public class ChunkBlazerSceneOverlay extends Overlay
{
	private static final int REGION_SIZE = 64;
	private static final int REGION_MASK = REGION_SIZE - 1;

	private static final Color UNLOCKED_BORDER = new Color(80, 220, 120, 200);
	private static final Color LOCKED_BORDER = new Color(60, 60, 60, 220);
	// Translucent grey wash for locked chunks. Alpha ~128 = roughly 50% — easy
	// to see what's underneath but obviously "off".
	private static final Color LOCKED_FILL = new Color(40, 40, 40, 120);
	private static final Stroke BORDER_STROKE = new BasicStroke(1.5f);

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;

	@Inject
	public ChunkBlazerSceneOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.LOW);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showSceneChunks())
		{
			return null;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}

		Scene scene = client.getScene();
		if (scene == null)
		{
			return null;
		}

		final int plane = client.getPlane();
		final int baseX = client.getBaseX();
		final int baseY = client.getBaseY();
		final int sceneSize = Constants.SCENE_SIZE; // 104

		Stroke prev = graphics.getStroke();
		graphics.setStroke(BORDER_STROKE);

		for (int sx = 0; sx < sceneSize; sx++)
		{
			for (int sy = 0; sy < sceneSize; sy++)
			{
				int worldX = baseX + sx;
				int worldY = baseY + sy;
				int regionId = ((worldX >> 6) << 8) | (worldY >> 6);

				// Cell within its parent region (0..63 on each axis).
				int rx = worldX & REGION_MASK;
				int ry = worldY & REGION_MASK;
				boolean onWestEdge = rx == 0;
				boolean onEastEdge = rx == REGION_MASK;
				boolean onSouthEdge = ry == 0;
				boolean onNorthEdge = ry == REGION_MASK;
				boolean onAnyEdge = onWestEdge || onEastEdge || onSouthEdge || onNorthEdge;

				boolean unlocked = plugin.isRegionUnlocked(regionId);

				// Skip non-edge tiles in unlocked regions — nothing to draw.
				if (!onAnyEdge && unlocked)
				{
					continue;
				}

				LocalPoint lp = LocalPoint.fromScene(sx, sy, client.getTopLevelWorldView());
				if (lp == null)
				{
					continue;
				}
				Polygon tilePoly = Perspective.getCanvasTilePoly(client, lp);
				if (tilePoly == null)
				{
					continue;
				}

				if (!unlocked)
				{
					graphics.setColor(LOCKED_FILL);
					graphics.fillPolygon(tilePoly);
				}

				if (onAnyEdge)
				{
					graphics.setColor(unlocked ? UNLOCKED_BORDER : LOCKED_BORDER);
					// Only draw the SIDE(S) of this tile that sit on a region
					// boundary, not the whole tile outline. Otherwise every edge
					// tile shows a four-sided box and the chunk perimeter looks
					// like a fuzzy band instead of a clean line.
					drawRegionEdges(graphics, tilePoly, onWestEdge, onEastEdge, onSouthEdge, onNorthEdge);
				}
			}
		}

		graphics.setStroke(prev);
		return null;
	}

	/**
	 * The tile polygon vertices come from getCanvasTileAreaPoly in this order:
	 * SW, NW, NE, SE. We draw only the sides that sit on a region boundary.
	 */
	private static void drawRegionEdges(Graphics2D g, Polygon p,
		boolean west, boolean east, boolean south, boolean north)
	{
		int[] x = p.xpoints;
		int[] y = p.ypoints;
		if (p.npoints < 4)
		{
			return;
		}
		// Vertex layout: 0=SW, 1=NW, 2=NE, 3=SE
		if (west)  g.drawLine(x[0], y[0], x[1], y[1]); // SW -> NW
		if (north) g.drawLine(x[1], y[1], x[2], y[2]); // NW -> NE
		if (east)  g.drawLine(x[2], y[2], x[3], y[3]); // NE -> SE
		if (south) g.drawLine(x[3], y[3], x[0], y[0]); // SE -> SW
	}
}