package com.chunkblazer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Overlay for the minimap: draws chunk (region) borders and enables right-click
 * to unlock neighbouring chunks. Border drawing uses {@link Perspective#localToMinimap}
 * so the grid rotates and scales with the minimap; the hover/right-click logic below
 * uses a coarser approximation that only needs to know which neighbour is under the cursor.
 */
@Slf4j
public class ChunkBlazerMinimapOverlay extends Overlay
{
	private static final int REGION_SIZE = 64; // 64 tiles per region
	private static final int REGION_MASK = REGION_SIZE - 1;
	private static final int LOCAL_TILE_SIZE = Perspective.LOCAL_TILE_SIZE; // 128 local units per tile
	// localToMinimap clips points beyond this many local units from the player; a region
	// spans 64*128 = 8192, so a generous radius keeps nearby region lines from vanishing.
	private static final int MINIMAP_DRAW_DISTANCE = 8192;
	private static final Color UNLOCKED_BORDER = new Color(80, 220, 120, 200);
	private static final Color LOCKED_BORDER = new Color(120, 120, 120, 200);
	private static final Stroke BORDER_STROKE = new BasicStroke(1f);

	private final Client client;
	private final ClientThread clientThread;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final ChatboxPanelManager chatboxPanelManager;

	// Track hovered region for click detection
	private int hoveredRegionId = -1;
	private Rectangle lastMinimapBounds = null;

	@Inject
	public ChunkBlazerMinimapOverlay(Client client, ClientThread clientThread, ChunkBlazerPlugin plugin,
		ChunkBlazerConfig config, ChatboxPanelManager chatboxPanelManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.plugin = plugin;
		this.config = config;
		this.chatboxPanelManager = chatboxPanelManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showMinimapChunks())
		{
			return null;
		}

		Widget minimapWidget = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA);
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			minimapWidget = client.getWidget(ComponentID.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
		}
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			minimapWidget = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_MINIMAP_DRAW_AREA);
		}
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			return null;
		}

		Rectangle minimapBounds = minimapWidget.getBounds();
		if (minimapBounds == null)
		{
			return null;
		}
		lastMinimapBounds = minimapBounds;

		// Get player position
		WorldPoint playerLocation = client.getLocalPlayer().getWorldLocation();
		if (playerLocation == null)
		{
			return null;
		}

		// Draw the chunk-border grid on the minimap. Clipped to the minimap widget so
		// lines don't spill over the rest of the UI.
		drawMinimapBorders(graphics, minimapBounds);

		Set<Integer> neighborRegions = plugin.getNeighborRegionIds();

		// Get mouse position for hover detection
		Point mousePos = client.getMouseCanvasPosition();
		hoveredRegionId = -1;

		// Calculate the center of the minimap
		int centerX = (int) (minimapBounds.getCenterX());
		int centerY = (int) (minimapBounds.getCenterY());

		// Minimap scale (approximate)
		double scale = 4.0; // pixels per tile (approximate, varies with zoom)

		// Calculate visible region range (roughly 3x3 regions around player)
		int playerRegionX = playerLocation.getX() >> 6;
		int playerRegionY = playerLocation.getY() >> 6;

		// Only track hover for right-click menu - no drawing
		for (int rx = playerRegionX - 1; rx <= playerRegionX + 1; rx++)
		{
			for (int ry = playerRegionY - 1; ry <= playerRegionY + 1; ry++)
			{
				int regionId = (rx << 8) | ry;

				// Only care about unlockable regions (neighbours + charter ports)
				if (!neighborRegions.contains(regionId) && !plugin.isCharterRegion(regionId) && !plugin.isFreeUnlockableRegion(regionId))
				{
					continue;
				}

				// Calculate region corners in world coordinates
				int regionBaseX = rx << 6;
				int regionBaseY = ry << 6;

				// Convert to minimap coordinates (relative to player)
				int dx = regionBaseX - playerLocation.getX();
				int dy = regionBaseY - playerLocation.getY();

				// Minimap has Y inverted
				int minimapX = centerX + (int)(dx * scale);
				int minimapY = centerY - (int)(dy * scale);
				int regionSize = (int)(REGION_SIZE * scale);

				// Create region rectangle on minimap
				Rectangle regionRect = new Rectangle(minimapX, minimapY - regionSize, regionSize, regionSize);

				// Check if region is within minimap bounds
				if (!minimapBounds.intersects(regionRect))
				{
					continue;
				}

				// Check hover - only set if it's an unlockable neighbor
				if (mousePos != null && regionRect.contains(mousePos.getX(), mousePos.getY()))
				{
					if (!plugin.isRegionUnlocked(regionId))
					{
						hoveredRegionId = regionId;
					}
				}
			}
		}

		return null;
	}

	/**
	 * Draw the region-boundary grid onto the minimap. For every tile edge in the loaded
	 * scene that sits on a region boundary, convert both endpoints to minimap coordinates
	 * via {@link Perspective#localToMinimap} (which rotates/scales with the minimap) and
	 * stroke a segment. Points beyond the minimap radius come back null and are skipped,
	 * which naturally clips the grid to what's visible.
	 */
	private void drawMinimapBorders(Graphics2D graphics, Rectangle minimapBounds)
	{
		final int sceneSize = Constants.SCENE_SIZE;
		final int baseX = client.getBaseX();
		final int baseY = client.getBaseY();

		final Shape prevClip = graphics.getClip();
		final Stroke prevStroke = graphics.getStroke();
		graphics.setClip(minimapBounds);
		graphics.setStroke(BORDER_STROKE);

		for (int sx = 0; sx <= sceneSize; sx++)
		{
			for (int sy = 0; sy <= sceneSize; sy++)
			{
				final int worldX = baseX + sx;
				final int worldY = baseY + sy;

				// West edge of tile (sx,sy) is a region boundary when its world-x is a
				// multiple of 64. The edge runs from grid corner (sx,sy) to (sx,sy+1).
				if ((worldX & REGION_MASK) == 0 && sy < sceneSize)
				{
					int regionId = ((worldX >> 6) << 8) | (worldY >> 6);
					drawEdge(graphics, sx, sy, sx, sy + 1, plugin.isRegionUnlocked(regionId));
				}
				// South edge of tile (sx,sy): world-y a multiple of 64. Corner (sx,sy)→(sx+1,sy).
				if ((worldY & REGION_MASK) == 0 && sx < sceneSize)
				{
					int regionId = ((worldX >> 6) << 8) | (worldY >> 6);
					drawEdge(graphics, sx, sy, sx + 1, sy, plugin.isRegionUnlocked(regionId));
				}
			}
		}

		graphics.setStroke(prevStroke);
		graphics.setClip(prevClip);
	}

	/**
	 * Stroke one region-boundary segment between two scene grid corners. A grid corner at
	 * scene index (cx,cy) is at local coordinate (cx * 128, cy * 128) — the SW corner of
	 * tile (cx,cy). Skips the segment if either endpoint is off the minimap.
	 */
	private void drawEdge(Graphics2D graphics, int cx1, int cy1, int cx2, int cy2, boolean unlocked)
	{
		LocalPoint a = new LocalPoint(cx1 * LOCAL_TILE_SIZE, cy1 * LOCAL_TILE_SIZE);
		LocalPoint b = new LocalPoint(cx2 * LOCAL_TILE_SIZE, cy2 * LOCAL_TILE_SIZE);
		Point ma = Perspective.localToMinimap(client, a, MINIMAP_DRAW_DISTANCE);
		Point mb = Perspective.localToMinimap(client, b, MINIMAP_DRAW_DISTANCE);
		if (ma == null || mb == null)
		{
			return;
		}
		graphics.setColor(unlocked ? UNLOCKED_BORDER : LOCKED_BORDER);
		graphics.drawLine(ma.getX(), ma.getY(), mb.getX(), mb.getY());
	}

	public int getHoveredRegionId()
	{
		return hoveredRegionId;
	}
}
