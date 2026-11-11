/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
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

		Widget minimapWidget = client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			minimapWidget = client.getWidget(InterfaceID.Toplevel.MINIMAP);
		}
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			minimapWidget = client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
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

		Set<Integer> neighborRegions = plugin.getNeighborRegionIds();

		// Draw the chunk tints and border grid on the minimap. Clipped to the minimap
		// widget so nothing spills over the rest of the UI.
		drawMinimapBorders(graphics, minimapBounds, neighborRegions);

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
	 * Draw region boundaries with the world map's look: owned chunks stay plain, others
	 * get a faint tint, and no lines run between two unlocked chunks. Points beyond the
	 * minimap radius come back null from {@link Perspective#localToMinimap} and are
	 * skipped.
	 */
	private void drawMinimapBorders(Graphics2D graphics, Rectangle minimapBounds, Set<Integer> neighborRegions)
	{
		final int sceneSize = Constants.SCENE_SIZE;
		final int baseX = client.getBaseX();
		final int baseY = client.getBaseY();
		Ellipse2D ellipse = new Ellipse2D.Float();
		ellipse.setFrame(minimapBounds);
		final Shape prevClip = graphics.getClip();
		final Stroke prevStroke = graphics.getStroke();
		graphics.setClip(ellipse);
		graphics.setStroke(BORDER_STROKE);

		Map<Integer, Boolean> open = new HashMap<>();

		// Tints first, so the lines sit on top. Skipped in instances, where the scene
		// is a copy of somewhere else and its region ids don't mean real chunks.
		if (!client.isInInstancedRegion())
		{
			fillChunks(graphics, baseX, baseY, sceneSize, open, neighborRegions);
		}

		for (int sx = 0; sx <= sceneSize; sx++)
		{
			for (int sy = 0; sy <= sceneSize; sy++)
			{
				final int worldX = baseX + sx;
				final int worldY = baseY + sy;

				// West edge of tile (sx,sy) is a region boundary when its world-x is a
				// multiple of 64. The edge runs from grid corner (sx,sy) to (sx,sy+1).
				// Skipped between two unlocked chunks; green on the edge of the
				// unlocked area, grey between two chunks you don't own.
				if ((worldX & REGION_MASK) == 0 && sy < sceneSize)
				{
					boolean here = isOpen(open, regionAt(worldX, worldY));
					boolean west = isOpen(open, regionAt(worldX - 1, worldY));
					if (!(here && west))
					{
						drawEdge(graphics, sx, sy, sx, sy + 1, here || west);
					}
				}
				// South edge of tile (sx,sy): world-y a multiple of 64. Corner (sx,sy)→(sx+1,sy).
				if ((worldY & REGION_MASK) == 0 && sx < sceneSize)
				{
					boolean here = isOpen(open, regionAt(worldX, worldY));
					boolean south = isOpen(open, regionAt(worldX, worldY - 1));
					if (!(here && south))
					{
						drawEdge(graphics, sx, sy, sx + 1, sy, here || south);
					}
				}
			}
		}

		graphics.setStroke(prevStroke);
		graphics.setClip(prevClip);
	}

	/** Tint every chunk in the loaded scene that you don't own, clipped to the scene. */
	private void fillChunks(Graphics2D graphics, int baseX, int baseY, int sceneSize,
		Map<Integer, Boolean> open, Set<Integer> neighborRegions)
	{
		int firstRegionX = baseX >> 6;
		int lastRegionX = (baseX + sceneSize - 1) >> 6;
		int firstRegionY = baseY >> 6;
		int lastRegionY = (baseY + sceneSize - 1) >> 6;

		for (int rx = firstRegionX; rx <= lastRegionX; rx++)
		{
			for (int ry = firstRegionY; ry <= lastRegionY; ry++)
			{
				int regionId = (rx << 8) | ry;
				boolean unlocked = isOpen(open, regionId);
				ChunkUnlockType type = ChunkUnlockType.of(plugin, regionId, unlocked, neighborRegions.contains(regionId));
				if (type.fill.getAlpha() == 0)
				{
					continue;
				}

				// The part of this chunk inside the loaded scene, in scene tiles.
				int x1 = Math.max(0, (rx << 6) - baseX);
				int y1 = Math.max(0, (ry << 6) - baseY);
				int x2 = Math.min(sceneSize, (rx << 6) + REGION_SIZE - baseX);
				int y2 = Math.min(sceneSize, (ry << 6) + REGION_SIZE - baseY);
				if (x2 <= x1 || y2 <= y1)
				{
					continue;
				}

				Point a = toMinimap(x1, y1);
				Point b = toMinimap(x2, y1);
				Point c = toMinimap(x2, y2);
				Point d = toMinimap(x1, y2);
				if (a == null || b == null || c == null || d == null)
				{
					continue;
				}
				graphics.setColor(type.fill);
				graphics.fillPolygon(new Polygon(
					new int[]{a.getX(), b.getX(), c.getX(), d.getX()},
					new int[]{a.getY(), b.getY(), c.getY(), d.getY()}, 4));
			}
		}
	}

	/** Minimap position of a scene grid corner; reaches the whole scene, not just the visible circle. */
	private Point toMinimap(int sceneTileX, int sceneTileY)
	{
		LocalPoint point = new LocalPoint(sceneTileX * LOCAL_TILE_SIZE, sceneTileY * LOCAL_TILE_SIZE);
		return Perspective.localToMinimap(client, point, Integer.MAX_VALUE);
	}

	private static int regionAt(int worldX, int worldY)
	{
		return ((worldX >> 6) << 8) | (worldY >> 6);
	}

	private boolean isOpen(Map<Integer, Boolean> cache, int regionId)
	{
		return cache.computeIfAbsent(regionId, plugin::isRegionUnlocked);
	}

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
