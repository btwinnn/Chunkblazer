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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

@Slf4j
// Shared: the task window reads which chunk is hovered on the world map.
@Singleton
class ChunkBlazerWorldMapOverlay extends Overlay
{
	private static final int REGION_SIZE = 64; // 64 tiles per region
	private static final int REGION_TRUNCATE = ~((1 << 6) - 1);

	// Colors
	private static final Color LOCKED_BORDER = new Color(255, 0, 0, 120);
	// The chunk you're standing in gets its own outline colour.
	private static final Color CURRENT_BORDER = new Color(0, 200, 255, 255);
	// One outline colour for every chunk, so neighbouring edges never clash; the
	// chunk's type is shown by its fill instead (see ChunkUnlockType).
	private static final Color CHUNK_BORDER = new Color(255, 255, 255, 70);
	// Tint strength on the chunk under the mouse, so you can see the map inside it.
	private static final int HOVER_ALPHA = 55;
	// Below this many pixels per chunk the cost text won't fit, so it's skipped.
	private static final int MIN_LABEL_CHUNK_PIXELS = 48;

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final ChatboxPanelManager chatboxPanelManager;
	private final ClientThread clientThread;

	private int hoveredRegionId = -1;
	private boolean isHoveredUnlockable = false;

	// A chunk to point out (clicked in a quest's requirement list): the map jumps to it
	// once it's open, then outlines it with a pulse for FOCUS_MS.
	private static final long FOCUS_MS = 6000;
	private static final Color FOCUS_COLOR = new Color(255, 200, 60);
	private volatile int focusRegion = -1;
	private volatile boolean focusPending;
	private volatile long focusShownAt;
	// The hover tooltip, drawn by WorldMapLegendOverlay on the always-on-top layer so map icons can't cover it.
	private volatile Consumer<Graphics2D> tooltip;

	@Inject
	private ChunkBlazerWorldMapOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config,
		ChatboxPanelManager chatboxPanelManager, ClientThread clientThread)
	{
		setPosition(OverlayPosition.DYNAMIC);
		// Just above RuneLite's own map icons (its WorldMapOverlay is PRIORITY_HIGHEST, and on the
		// map a higher priority draws later), so locked chunks' tint covers the icons inside them.
		setPriority(PRIORITY_HIGHEST + 0.01f);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.chatboxPanelManager = chatboxPanelManager;
		this.clientThread = clientThread;
	}

	/**
	 * @return the region id under the cursor on the world map, or -1 if none.
	 * Read by {@code ChunkBlazerPlugin.unlockFromWorldMap} for keybind+click unlock.
	 */
	int getHoveredRegionId()
	{
		return hoveredRegionId;
	}

	/**
	 * Point out a chunk on the world map: jumps there now if the map is open, or as soon
	 * as it's opened, and outlines it for a few seconds.
	 */
	void focusRegion(int regionId)
	{
		focusRegion = regionId;
		focusShownAt = 0;
		focusPending = true;
	}

	/** True if the world map is open right now. */
	boolean isMapOpen()
	{
		return client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER) != null;
	}

	void drawTooltip(Graphics2D graphics)
	{
		Consumer<Graphics2D> shown = tooltip;
		if (shown != null)
		{
			shown.accept(graphics);
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		tooltip = null;
		// The focus outline goes on top of the chunk tints.
		Dimension size = renderChunks(graphics);
		renderFocus(graphics);
		return size;
	}

	/** Moves the map to the focused chunk and draws its pulsing outline. Independent of the chunk overlay setting. */
	private void renderFocus(Graphics2D graphics)
	{
		int region = focusRegion;
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		WorldMap worldMap = client.getWorldMap();
		if (region <= 0 || map == null || worldMap == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (focusPending)
		{
			focusPending = false;
			focusShownAt = now;
			worldMap.setWorldMapPositionTarget(new WorldPoint(((region >> 8) << 6) + 32, ((region & 0xFF) << 6) + 32, 0));
		}
		if (now - focusShownAt > FOCUS_MS)
		{
			focusRegion = -1;
			return;
		}

		Rectangle mapRect = map.getBounds();
		float pixelsPerTile = worldMap.getWorldMapZoom();
		Point centre = worldMap.getWorldMapPosition();
		int size = (int) Math.ceil(REGION_SIZE * pixelsPerTile);
		int x = mapRect.x + mapRect.width / 2 + (int) ((((region >> 8) << 6) - centre.getX()) * pixelsPerTile);
		int y = mapRect.y + mapRect.height / 2 - (int) ((((region & 0xFF) << 6) - centre.getY()) * pixelsPerTile) - size;

		// Pulse: the outline breathes in and out, one beat a second.
		double pulse = 0.5 + 0.5 * Math.sin((now - focusShownAt) / 1000.0 * Math.PI * 2);
		java.awt.Shape oldClip = graphics.getClip();
		Stroke oldStroke = graphics.getStroke();
		graphics.setClip(mapRect);
		graphics.setColor(new Color(FOCUS_COLOR.getRed(), FOCUS_COLOR.getGreen(), FOCUS_COLOR.getBlue(),
			(int) (60 * pulse)));
		graphics.fillRect(x, y, size, size);
		graphics.setColor(FOCUS_COLOR);
		graphics.setStroke(new BasicStroke(2f + 2f * (float) pulse));
		graphics.drawRect(x, y, size, size);
		graphics.setStroke(oldStroke);
		graphics.setClip(oldClip);
	}

	private Dimension renderChunks(Graphics2D graphics)
	{
		// Chunk borders render on three independent surfaces: the minimap
		// (showMinimapChunks), the 3D scene (showSceneChunks), and this world map
		// (showWorldMapChunks). Splitting the scene/world-map toggles lets a player
		// keep borders on the minimap/world map without the in-scene overlay.
		if (!config.showWorldMapChunks())
		{
			return null;
		}

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null)
		{
			return null;
		}

		WorldMap worldMap = client.getWorldMap();
		if (worldMap == null)
		{
			return null;
		}

		Rectangle worldMapRect = map.getBounds();
		graphics.setClip(worldMapRect);

		float pixelsPerTile = worldMap.getWorldMapZoom();
		int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
		int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);

		Point worldMapPosition = worldMap.getWorldMapPosition();

		// Calculate visible region bounds
		int yTileMin = worldMapPosition.getY() - heightInTiles / 2;
		int xRegionMin = (worldMapPosition.getX() - widthInTiles / 2) & REGION_TRUNCATE;
		int xRegionMax = ((worldMapPosition.getX() + widthInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
		int yRegionMin = (yTileMin & REGION_TRUNCATE);
		int yRegionMax = ((worldMapPosition.getY() + heightInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
		int regionPixelSize = (int) Math.ceil(REGION_SIZE * pixelsPerTile);

		Set<String> unlockedRegions = plugin.unlockedRegionIdsView();
		Set<Integer> neighborRegions = plugin.getNeighborRegionIds();
		int currentRegionId = plugin.getCurrentRegionId();

		// Get mouse position for hover detection
		Point mousePos = client.getMouseCanvasPosition();
		hoveredRegionId = -1;
		isHoveredUnlockable = false;

		// First pass: Draw locked chunk overlays (greyscale effect)
		for (int x = xRegionMin; x < xRegionMax; x += REGION_SIZE)
		{
			for (int y = yRegionMin; y < yRegionMax; y += REGION_SIZE)
			{
				int regionId = ((x >> 6) << 8) | (y >> 6);
				// Free dungeon / off-map regions (regionY outside the surface band)
				// are always accessible, so draw them as unlocked, not locked.
				boolean isUnlocked = unlockedRegions.contains(String.valueOf(regionId))
					|| plugin.isFreeRegion(regionId);
				boolean isNeighbor = neighborRegions.contains(regionId);
				boolean isCharter = plugin.isCharterRegion(regionId);
				boolean isFreeUnlockable = plugin.isFreeUnlockableRegion(regionId);

				int yTileOffset = -(yTileMin - y);
				int xTileOffset = x + widthInTiles / 2 - worldMapPosition.getX();

				int xPos = ((int) (xTileOffset * pixelsPerTile)) + (int) worldMapRect.getX();
				int yPos = (worldMapRect.height - (int) (yTileOffset * pixelsPerTile)) + (int) worldMapRect.getY();
				yPos -= regionPixelSize;

				Rectangle regionRect = new Rectangle(xPos, yPos, regionPixelSize, regionPixelSize);

				// Check if mouse is hovering over this region
				if (mousePos != null && worldMapRect.contains(mousePos.getX(), mousePos.getY()))
				{
					if (regionRect.contains(mousePos.getX(), mousePos.getY()))
					{
						hoveredRegionId = regionId;
						isHoveredUnlockable = (isNeighbor || isCharter || isFreeUnlockable) && !isUnlocked;
					}
				}

				// Each chunk gets a tint for its type: none for owned, gold/teal/blue/
				// purple for the ways it can be unlocked, dark for locked. The chunk
				// under the mouse is tinted more lightly, so you can see what's in it.
				Color fill = fillFor(ChunkUnlockType.of(plugin, regionId, isUnlocked, isNeighbor));
				if (regionId == hoveredRegionId && fill.getAlpha() > HOVER_ALPHA)
				{
					fill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), HOVER_ALPHA);
				}
				graphics.setColor(fill);
				graphics.fillRect(xPos, yPos, regionPixelSize, regionPixelSize);
			}
		}

		// Second pass: Draw borders and region IDs
		Font regionFont = FontManager.getRunescapeBoldFont().deriveFont(14f);
		graphics.setFont(regionFont);
		Rectangle currentChunkRect = null;

		for (int x = xRegionMin; x < xRegionMax; x += REGION_SIZE)
		{
			for (int y = yRegionMin; y < yRegionMax; y += REGION_SIZE)
			{
				int regionId = ((x >> 6) << 8) | (y >> 6);
				// Free dungeon / off-map regions (regionY outside the surface band)
				// are always accessible, so draw them as unlocked, not locked.
				boolean isUnlocked = unlockedRegions.contains(String.valueOf(regionId))
					|| plugin.isFreeRegion(regionId);
				boolean isNeighbor = neighborRegions.contains(regionId);
				boolean isCurrent = regionId == currentRegionId;

				int yTileOffset = -(yTileMin - y);
				int xTileOffset = x + widthInTiles / 2 - worldMapPosition.getX();

				int xPos = ((int) (xTileOffset * pixelsPerTile)) + (int) worldMapRect.getX();
				int yPos = (worldMapRect.height - (int) (yTileOffset * pixelsPerTile)) + (int) worldMapRect.getY();
				yPos -= regionPixelSize;

				ChunkUnlockType type = ChunkUnlockType.of(plugin, regionId, isUnlocked, isNeighbor);

				// Uniform outline round every chunk. With "Lines Between Unlocked Chunks"
				// off, edges between two unlocked chunks are skipped, so your whole
				// unlocked area reads as one connected piece of map. North is +1 in
				// region id, east is +256.
				graphics.setColor(CHUNK_BORDER);
				int right = xPos + regionPixelSize;
				int bottom = yPos + regionPixelSize;
				boolean gridLines = config.showChunkGridLines();
				if (gridLines || !(isUnlocked && isOpen(unlockedRegions, regionId + 1)))
				{
					graphics.drawLine(xPos, yPos, right, yPos);
				}
				if (gridLines || !(isUnlocked && isOpen(unlockedRegions, regionId - 1)))
				{
					graphics.drawLine(xPos, bottom, right, bottom);
				}
				if (gridLines || !(isUnlocked && isOpen(unlockedRegions, regionId - 256)))
				{
					graphics.drawLine(xPos, yPos, xPos, bottom);
				}
				if (gridLines || !(isUnlocked && isOpen(unlockedRegions, regionId + 256)))
				{
					graphics.drawLine(right, yPos, right, bottom);
				}

				// The chunk you're standing in is outlined last, on top of everything.
				if (isCurrent)
				{
					currentChunkRect = new Rectangle(xPos, yPos, regionPixelSize, regionPixelSize);
				}

				// Hover emphasis on unlockable chunks (outline only, no fill).
				if (type.isUnlockable() && regionId == hoveredRegionId)
				{
					graphics.drawRect(xPos + 1, yPos + 1, regionPixelSize - 2, regionPixelSize - 2);
				}

				// What it costs, written in the middle of every unlockable chunk.
				if (config.showChunkCostLabels() && type.isUnlockable() && regionPixelSize >= MIN_LABEL_CHUNK_PIXELS)
				{
					drawCostLabel(graphics, xPos, yPos, regionPixelSize,
						ChunkUnlockType.costLabel(plugin, regionId, type), type.color);
					graphics.setFont(regionFont);
				}

				// Region ID in the top-left corner, only on the chunk under the mouse, so the
				// zoomed-out map isn't covered in numbers.
				if (regionId == hoveredRegionId)
				{
					String idText = String.valueOf(regionId);
					int textX = xPos + 4;
					int textY = yPos + 16;

					// Make sure text position is within the map bounds
					if (textX > worldMapRect.getX() && textY > worldMapRect.getY())
					{
						// Simple drop shadow (single offset, bottom-right)
						graphics.setColor(Color.BLACK);
						graphics.drawString(idText, textX + 1, textY + 1);

						// Same colour family as the chunk's tint, solid so it stays readable.
						graphics.setColor(type.color);
						graphics.drawString(idText, textX, textY);
					}
				}
			}
		}

		// Current chunk: a double cyan outline, drawn after every other line so
		// nothing covers it.
		if (currentChunkRect != null)
		{
			graphics.setColor(CURRENT_BORDER);
			graphics.drawRect(currentChunkRect.x, currentChunkRect.y, currentChunkRect.width, currentChunkRect.height);
			graphics.drawRect(currentChunkRect.x + 1, currentChunkRect.y + 1,
				currentChunkRect.width - 2, currentChunkRect.height - 2);
		}

		// Hovering an unlockable neighbour: show the keybind+click tooltip. The
		// actual unlock is handled in ChunkBlazerPlugin.unlockFromWorldMap when
		// the map-unlock key is held during the click (Region Locker model) —
		// world-map right-click menu entries don't render reliably.
		if (isHoveredUnlockable && hoveredRegionId > 0)
		{
			int id = hoveredRegionId;
			tooltip = g -> drawHoverTooltip(g, mousePos, id);
		}
		// Hovering a chunk you own: the same key + click opens its tasks (TaskBrowserOverlay).
		else if (hoveredRegionId > 0 && plugin.isRegionUnlocked(hoveredRegionId)
			&& !plugin.isFreeRegion(hoveredRegionId)
			&& !plugin.getRegionName(hoveredRegionId).startsWith("Unknown Region"))
		{
			int id = hoveredRegionId;
			tooltip = g -> drawUnlockedTooltip(g, mousePos, id);
		}

		// Draw region ID in top-left corner of world map
		if (hoveredRegionId > 0)
		{
			drawRegionIdDisplay(graphics, worldMapRect, hoveredRegionId);
		}
		else
		{
			// Show current player region if not hovering
			drawRegionIdDisplay(graphics, worldMapRect, currentRegionId);
		}

		return null;
	}

	private void drawRegionIdDisplay(Graphics2D graphics, Rectangle worldMapRect, int regionId)
	{
		if (regionId <= 0)
		{
			return;
		}

		String regionName = plugin.getRegionName(regionId);
		String regionIdText = "Region: " + regionId;

		Font font = FontManager.getRunescapeSmallFont();
		graphics.setFont(font);
		FontMetrics fm = graphics.getFontMetrics();

		int padding = 4;
		int lineHeight = fm.getHeight();
		int maxWidth = Math.max(fm.stringWidth(regionName), fm.stringWidth(regionIdText));
		int boxWidth = maxWidth + padding * 2;
		int boxHeight = lineHeight * 2 + padding * 2;

		int boxX = (int) worldMapRect.getX() + 5;
		int boxY = (int) worldMapRect.getY() + 5;

		// Draw background
		graphics.setColor(new Color(0, 0, 0, 180));
		graphics.fillRect(boxX, boxY, boxWidth, boxHeight);

		// Draw border
		graphics.setColor(new Color(255, 215, 0, 200));
		graphics.drawRect(boxX, boxY, boxWidth, boxHeight);

		// Draw region name
		int textX = boxX + padding;
		int textY = boxY + padding + fm.getAscent();
		graphics.setColor(Color.WHITE);
		graphics.drawString(regionName, textX, textY);

		// Draw region ID
		textY += lineHeight;
		graphics.setColor(new Color(200, 200, 200));
		graphics.drawString(regionIdText, textX, textY);
	}

	private void drawHoverTooltip(Graphics2D graphics, Point mousePos, int regionId)
	{
		if (mousePos == null)
		{
			return;
		}

		String regionName = plugin.getRegionName(regionId);
		int unlockCost = plugin.getRegionUnlockCost(regionId);
		int playerPoints = plugin.getTotalPoints();
		boolean isBoss = plugin.isBossRegion(regionId);
		boolean canAfford = playerPoints >= unlockCost;

		if (isBoss)
		{
			canAfford = (plugin.getBossTokens() > 0);
		}

		// Build tooltip text
		String line1 = regionName;
		String line2, line3 = "";
		if (plugin.isCharterRegion(regionId))
		{
			line2 = "Cost: FREE (charter port)";
		}
		else if (plugin.isFreeUnlockableRegion(regionId) || (!isBoss && unlockCost == 0))
		{
			line2 = "Cost: FREE";
		}
		else if (isBoss)
		{
			line2 = "Cost: 1 Boss Token";
			line3 = "Need 1 more Boss Token";
		}
		else
		{
			line2 = "Cost: " + unlockCost + " pts";
			line3 = "Need " + (unlockCost - playerPoints) + " more pts";
		}

		if (canAfford)
		{
			line3 = "Hold " + config.worldMapUnlockKey() + " + click to unlock";
		}


		// Setup font
		Font font = FontManager.getRunescapeSmallFont();
		graphics.setFont(font);
		FontMetrics fm = graphics.getFontMetrics();

		// Calculate tooltip size
		int padding = 6;
		int lineHeight = fm.getHeight();
		int maxWidth = Math.max(fm.stringWidth(line1), Math.max(fm.stringWidth(line2), fm.stringWidth(line3)));
		int tooltipWidth = maxWidth + padding * 2;
		int tooltipHeight = lineHeight * 3 + padding * 2;

		// Position tooltip near mouse (offset to not cover cursor)
		int tooltipX = mousePos.getX() + 15;
		int tooltipY = mousePos.getY() - tooltipHeight - 5;

		// Draw background
		graphics.setColor(new Color(30, 30, 30, 230));
		graphics.fillRect(tooltipX, tooltipY, tooltipWidth, tooltipHeight);

		// Draw border
		ChunkUnlockType type = ChunkUnlockType.of(plugin, regionId, false, true);
		graphics.setColor(canAfford ? type.color : LOCKED_BORDER);
		graphics.drawRect(tooltipX, tooltipY, tooltipWidth, tooltipHeight);

		// Draw text
		int textX = tooltipX + padding;
		int textY = tooltipY + padding + fm.getAscent();

		graphics.setColor(Color.WHITE);
		graphics.drawString(line1, textX, textY);

		textY += lineHeight;
		graphics.setColor(new Color(255, 215, 0)); // Gold for cost
		graphics.drawString(line2, textX, textY);

		textY += lineHeight;
		graphics.setColor(canAfford ? new Color(100, 255, 100) : new Color(255, 100, 100));
		graphics.drawString(line3, textX, textY);
	}


	/** Unlocked, or an always-open area such as a dungeon (same test as the chunk fill). */
	private boolean isOpen(Set<String> unlockedRegions, int regionId)
	{
		return unlockedRegions.contains(String.valueOf(regionId)) || plugin.isFreeRegion(regionId);
	}

	/** Tooltip for an unlocked chunk: its name and how to open its tasks. */
	private void drawUnlockedTooltip(Graphics2D graphics, Point mousePos, int regionId)
	{
		if (mousePos == null)
		{
			return;
		}
		String line1 = plugin.getRegionName(regionId);
		String line2 = "Unlocked";
		String line3 = "Hold " + config.worldMapTasksKey() + " + click to view tasks";

		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = graphics.getFontMetrics();
		int padding = 6;
		int lineHeight = fm.getHeight();
		int width = Math.max(fm.stringWidth(line1), Math.max(fm.stringWidth(line2), fm.stringWidth(line3))) + padding * 2;
		int height = lineHeight * 3 + padding * 2;
		int x = mousePos.getX() + 15;
		int y = mousePos.getY() - height - 5;

		graphics.setColor(new Color(30, 30, 30, 230));
		graphics.fillRect(x, y, width, height);
		graphics.setColor(ChunkUnlockType.UNLOCKED.color);
		graphics.drawRect(x, y, width, height);

		int textX = x + padding;
		int textY = y + padding + fm.getAscent();
		graphics.setColor(Color.WHITE);
		graphics.drawString(line1, textX, textY);
		textY += lineHeight;
		graphics.setColor(ChunkUnlockType.UNLOCKED.color);
		graphics.drawString(line2, textX, textY);
		textY += lineHeight;
		graphics.setColor(new Color(255, 215, 0));
		graphics.drawString(line3, textX, textY);
	}

	/** Cost text centred in a chunk, on a dark backing so it reads on any map colour. */
	private void drawCostLabel(Graphics2D graphics, int xPos, int yPos, int size, String text, Color color)
	{
		if (text == null)
		{
			return;
		}
		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = graphics.getFontMetrics();
		int width = fm.stringWidth(text);
		int x = xPos + (size - width) / 2;
		int y = yPos + (size + fm.getAscent()) / 2 - 2;

		graphics.setColor(new Color(0, 0, 0, 170));
		graphics.fillRect(x - 3, y - fm.getAscent(), width + 6, fm.getHeight());
		graphics.setColor(color);
		graphics.drawString(text, x, y);
	}

	/** Key in the bottom-left of the world map explaining each chunk colour. */
	/** The tint for a chunk type: the player's colour from the World Map settings (unlocked stays clear). */
	private Color fillFor(ChunkUnlockType type)
	{
		return fillFor(config, type);
	}

	/** Shared with WorldMapLegendOverlay, so the legend always matches the map. */
	static Color fillFor(ChunkBlazerConfig config, ChunkUnlockType type)
	{
		switch (type)
		{
			case LOCKED:
				return config.worldMapLockedColor();
			case PAID:
				return config.worldMapPaidColor();
			case FREE:
				return config.worldMapFreeColor();
			case CHARTER:
				return config.worldMapCharterColor();
			case BOSS:
				return config.worldMapBossColor();
			default:
				return type.fill;
		}
	}
}
