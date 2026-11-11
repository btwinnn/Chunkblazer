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
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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

// Shared: the task window reads which chunk is hovered on the world map.
@Singleton
public class ChunkBlazerWorldMapOverlay extends Overlay
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
	private final CompanionPrompt addOns;
	private final ConfigManager configManager;

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
		ChatboxPanelManager chatboxPanelManager, ClientThread clientThread, CompanionPrompt addOns, ConfigManager configManager)
	{
		this.addOns = addOns;
		this.configManager = configManager;
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
	public int getHoveredRegionId()
	{
		return hoveredRegionId;
	}

	/**
	 * Point out a chunk on the world map: jumps there now if the map is open, or as soon
	 * as it's opened, and outlines it for a few seconds.
	 */
	public void focusRegion(int regionId)
	{
		focusRegion = regionId;
		focusShownAt = 0;
		focusPending = true;
	}

	public void drawTooltip(Graphics2D graphics)
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
		// ChunkBlazer UI draws the hover tooltip on its top layer; without it, draw it here.
		if (!addOns.uiOn())
		{
			drawTooltip(graphics);
		}
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

	/** An unlocked paid chunk (not free, charter or a dungeon) touches this one north, south, east or west. */
	private boolean nextToPaidChunk(Set<String> unlocked, int regionId)
	{
		for (int next : new int[]{regionId + 1, regionId - 1, regionId + 256, regionId - 256})
		{
			if (unlocked.contains(String.valueOf(next)) && !plugin.isFreeUnlockableRegion(next)
				&& !plugin.isCharterRegion(next) && !plugin.isFreeRegion(next))
			{
				return true;
			}
		}
		return false;
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

		MapView view = new MapView(worldMapRect, worldMap.getWorldMapZoom(), worldMap.getWorldMapPosition());

		// The tutorial shows the whole map locked, bar its practice chunks: Lumbridge and the
		// free chunks only open up once you've picked a mode.
		boolean preview = plugin.isTutorialPreview();
		Set<String> unlockedRegions = preview ? Collections.emptySet() : plugin.unlockedRegionIdsView();
		Set<Integer> neighborRegions = preview ? Collections.emptySet() : plugin.getNeighborRegionIds();
		int currentRegionId = plugin.getCurrentRegionId();

		// The chunk under the mouse (the last one whose square holds it, as drawn).
		Point mousePos = client.getMouseCanvasPosition();
		hoveredRegionId = -1;
		isHoveredUnlockable = false;
		if (mousePos != null && worldMapRect.contains(mousePos.getX(), mousePos.getY()))
		{
			for (int x = view.xRegionMin; x < view.xRegionMax; x += REGION_SIZE)
			{
				for (int y = view.yRegionMin; y < view.yRegionMax; y += REGION_SIZE)
				{
					int xPos = view.x(x);
					int yPos = view.y(y);
					if (mousePos.getX() >= xPos && mousePos.getX() < xPos + view.size
						&& mousePos.getY() >= yPos && mousePos.getY() < yPos + view.size)
					{
						hoveredRegionId = ((x >> 6) << 8) | (y >> 6);
					}
				}
			}
		}
		if (hoveredRegionId > 0)
		{
			int id = hoveredRegionId;
			boolean unlocked = unlockedRegions.contains(String.valueOf(id))
				|| !preview && plugin.isFreeRegion(id) || plugin.isPracticeUnlocked(id);
			isHoveredUnlockable = !preview && !unlocked
				&& (neighborRegions.contains(id) || plugin.isCharterRegion(id) || plugin.isFreeUnlockableRegion(id));
		}

		// Drawn once into an image and copied while nothing it shows has changed; drawn
		// straight onto the screen while the map is being dragged or zoomed.
		long now = System.currentTimeMillis();
		Object key = java.util.Arrays.asList(worldMapRect, view.position.getX(), view.position.getY(), view.zoom,
			hoveredRegionId, currentRegionId, preview, plugin.getLastUnlockChangeMs(), unlockedRegions.size(),
			neighborRegions.hashCode(), config.showChunkGridLines(), config.showChunkCostLabels());
		boolean changed = !key.equals(mapKey);
		if (changed && now - mapChangedAt < MAP_LIVE_MS)
		{
			// Still moving: draw it live, and keep the picture for when it settles.
			mapChangedAt = now;
			mapKey = key;
			mapImageGood = false;
			drawChunks(graphics, view, preview, unlockedRegions, neighborRegions, currentRegionId);
		}
		else
		{
			if (changed)
			{
				mapChangedAt = now;
				mapKey = key;
			}
			if (changed || !mapImageGood || now - mapDrawnAt >= MAP_REDRAW_MS || mapImage == null
				|| mapImage.getWidth() != worldMapRect.width || mapImage.getHeight() != worldMapRect.height)
			{
				if (mapImage == null || mapImage.getWidth() != worldMapRect.width || mapImage.getHeight() != worldMapRect.height)
				{
					// Premultiplied: the cheapest kind to copy onto the screen every frame.
					mapImage = new BufferedImage(Math.max(1, worldMapRect.width), Math.max(1, worldMapRect.height),
						BufferedImage.TYPE_INT_ARGB_PRE);
				}
				Graphics2D g = mapImage.createGraphics();
				g.setComposite(java.awt.AlphaComposite.Clear);
				g.fillRect(0, 0, mapImage.getWidth(), mapImage.getHeight());
				g.setComposite(java.awt.AlphaComposite.SrcOver);
				g.setRenderingHints(graphics.getRenderingHints());
				g.translate(-worldMapRect.x, -worldMapRect.y);
				drawChunks(g, view, preview, unlockedRegions, neighborRegions, currentRegionId);
				g.dispose();
				mapDrawnAt = now;
				mapImageGood = true;
			}
			graphics.drawImage(mapImage, worldMapRect.x, worldMapRect.y, null);
		}

		// Hovering an unlockable neighbour: show the keybind+click tooltip. The
		// actual unlock is handled in ChunkBlazerPlugin.unlockFromWorldMap when
		// the map-unlock key is held during the click (Region Locker model) —
		// world-map right-click menu entries don't render reliably. The tooltip follows the
		// mouse as it is when drawn.
		if (isHoveredUnlockable && hoveredRegionId > 0)
		{
			int id = hoveredRegionId;
			tooltip = g -> drawHoverTooltip(g, client.getMouseCanvasPosition(), id);
		}
		// Hovering a chunk you own: the same key + click opens its tasks (ChunkBlazer UI's TaskWindow).
		else if (!preview && hoveredRegionId > 0 && plugin.isRegionUnlocked(hoveredRegionId)
			&& !plugin.isFreeRegion(hoveredRegionId)
			&& !plugin.getRegionName(hoveredRegionId).startsWith("Unknown Region"))
		{
			int id = hoveredRegionId;
			tooltip = g -> drawUnlockedTooltip(g, client.getMouseCanvasPosition(), id);
		}
		return null;
	}

	// The chunk tints, borders and labels as last drawn (see renderChunks).
	private static final long MAP_LIVE_MS = 150;
	private static final long MAP_REDRAW_MS = 500;
	private BufferedImage mapImage;
	private Object mapKey;
	private long mapChangedAt;
	private long mapDrawnAt;
	private boolean mapImageGood;

	/** Where the map's chunks are on screen this frame. */
	private static final class MapView
	{
		final Rectangle rect;
		final float zoom;
		final Point position;
		final int widthInTiles;
		final int yTileMin;
		final int xRegionMin;
		final int xRegionMax;
		final int yRegionMin;
		final int yRegionMax;
		final int size;

		MapView(Rectangle rect, float zoom, Point position)
		{
			this.rect = rect;
			this.zoom = zoom;
			this.position = position;
			widthInTiles = (int) Math.ceil(rect.getWidth() / zoom);
			int heightInTiles = (int) Math.ceil(rect.getHeight() / zoom);
			yTileMin = position.getY() - heightInTiles / 2;
			xRegionMin = (position.getX() - widthInTiles / 2) & REGION_TRUNCATE;
			xRegionMax = ((position.getX() + widthInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
			yRegionMin = yTileMin & REGION_TRUNCATE;
			yRegionMax = ((position.getY() + heightInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
			size = (int) Math.ceil(REGION_SIZE * zoom);
		}

		int x(int tileX)
		{
			int xTileOffset = tileX + widthInTiles / 2 - position.getX();
			return ((int) (xTileOffset * zoom)) + (int) rect.getX();
		}

		int y(int tileY)
		{
			int yTileOffset = -(yTileMin - tileY);
			return (rect.height - (int) (yTileOffset * zoom)) + (int) rect.getY() - size;
		}
	}

	// What each chunk is (its type, tint and whether it's open), worked out once per change in
	// what's unlocked rather than for every chunk on every frame. Indexed by region id.
	private static final int REGIONS = 1 << 16;
	private final byte[] chunkType = new byte[REGIONS];
	private final byte[] chunkFill = new byte[REGIONS];
	private final boolean[] chunkUnlocked = new boolean[REGIONS];
	private final boolean[] chunkOpen = new boolean[REGIONS];
	private Object chunksKey;
	private static final ChunkUnlockType[] TYPES = ChunkUnlockType.values();
	// One line per chunk edge (they used to be drawn twice, once by each side).
	private static final Color GRID_LINE = new Color(255, 255, 255, 110);

	/** Forgets every chunk's state when what's unlocked has changed. */
	private void checkChunks(boolean preview, Set<String> unlockedRegions, Set<Integer> neighborRegions)
	{
		// Also every couple of seconds, for anything that changes without an unlock (the
		// tutorial's practice chunks, costs).
		Object key = java.util.Arrays.asList(preview, plugin.getLastUnlockChangeMs(), unlockedRegions.size(),
			neighborRegions.hashCode(), System.currentTimeMillis() / 2000);
		if (!key.equals(chunksKey))
		{
			chunksKey = key;
			java.util.Arrays.fill(chunkType, (byte) 0);
		}
	}

	/** A chunk's type (worked out the first time it's needed), or LOCKED off the map. */
	private ChunkUnlockType chunk(int regionId, boolean preview, Set<String> unlockedRegions, Set<Integer> neighborRegions)
	{
		if (regionId < 0 || regionId >= REGIONS)
		{
			return ChunkUnlockType.LOCKED;
		}
		if (chunkType[regionId] == 0)
		{
			// Free dungeon / off-map regions (regionY outside the surface band) are always
			// accessible, so they show as unlocked, not locked.
			boolean unlocked = unlockedRegions.contains(String.valueOf(regionId))
				|| !preview && plugin.isFreeRegion(regionId) || plugin.isPracticeUnlocked(regionId);
			ChunkUnlockType type = preview ? (unlocked ? ChunkUnlockType.UNLOCKED : ChunkUnlockType.LOCKED)
				: ChunkUnlockType.of(plugin, regionId, unlocked, neighborRegions.contains(regionId));
			// A free chunk has no tint of its own: it's greyed like a locked one until a paid
			// chunk next to it is unlocked (another free chunk doesn't count), then clear like an
			// unlocked one. Either way it says FREE until you walk in.
			ChunkUnlockType fill = type;
			if (fill == ChunkUnlockType.FREE)
			{
				fill = nextToPaidChunk(unlockedRegions, regionId) ? ChunkUnlockType.UNLOCKED : ChunkUnlockType.LOCKED;
			}
			chunkUnlocked[regionId] = unlocked;
			chunkOpen[regionId] = isOpen(unlockedRegions, regionId);
			chunkFill[regionId] = (byte) fill.ordinal();
			chunkType[regionId] = (byte) (type.ordinal() + 1);
		}
		return TYPES[chunkType[regionId] - 1];
	}

	private boolean unlockedAt(int regionId)
	{
		return regionId >= 0 && regionId < REGIONS && chunkUnlocked[regionId];
	}

	private boolean openAt(int regionId)
	{
		return regionId >= 0 && regionId < REGIONS && chunkOpen[regionId];
	}

	/**
	 * The line between two chunks shows unless both sides are unlocked (with "Lines Between
	 * Unlocked Chunks" off), so your unlocked area reads as one piece of map.
	 */
	private boolean edge(boolean gridLines, int a, int b)
	{
		return gridLines || !(unlockedAt(a) && openAt(b)) || !(unlockedAt(b) && openAt(a));
	}

	/** The chunk tints, borders, cost labels and region id, for {@code view}. */
	private void drawChunks(Graphics2D graphics, MapView view, boolean preview, Set<String> unlockedRegions,
		Set<Integer> neighborRegions, int currentRegionId)
	{
		boolean gridLines = config.showChunkGridLines();
		boolean costLabels = config.showChunkCostLabels();
		int size = view.size;
		int columns = Math.max(0, (view.xRegionMax - view.xRegionMin) / REGION_SIZE);
		int rows = Math.max(0, (view.yRegionMax - view.yRegionMin) / REGION_SIZE);
		if (columns == 0 || rows == 0)
		{
			return;
		}
		checkChunks(preview, unlockedRegions, neighborRegions);
		int rx0 = view.xRegionMin >> 6;
		int ry0 = view.yRegionMin >> 6;
		// The chunks one past each side too, for the lines along the view's edges.
		for (int i = -1; i <= columns; i++)
		{
			for (int j = -1; j <= rows; j++)
			{
				chunk(((rx0 + i) << 8) | (ry0 + j), preview, unlockedRegions, neighborRegions);
			}
		}

		// The tints: one pixel per chunk, stretched over the map in a single draw (north up,
		// so the picture's top row is the northernmost chunks).
		Color[] fills = new Color[TYPES.length];
		for (ChunkUnlockType t : TYPES)
		{
			fills[t.ordinal()] = fillFor(t);
		}
		BufferedImage tints = new BufferedImage(columns, rows, BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < columns; i++)
		{
			for (int j = 0; j < rows; j++)
			{
				int regionId = ((rx0 + i) << 8) | (ry0 + j);
				Color fill = fills[chunkFill[regionId]];
				int argb = fill.getRGB();
				if (regionId == hoveredRegionId && fill.getAlpha() > HOVER_ALPHA)
				{
					argb = (HOVER_ALPHA << 24) | (argb & 0xFFFFFF);
				}
				tints.setRGB(i, rows - 1 - j, argb);
			}
		}
		int left = view.x(view.xRegionMin);
		int right = view.x(view.xRegionMax);
		int top = view.y(view.yRegionMax) + size;
		int bottom = view.y(view.yRegionMin) + size;
		Object interpolation = graphics.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
		graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
			java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		graphics.drawImage(tints, left, top, right, bottom, 0, 0, columns, rows, null);
		if (interpolation != null)
		{
			graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, interpolation);
		}

		// The lines between chunks: each straight run drawn as one line.
		graphics.setColor(GRID_LINE);
		for (int i = 0; i <= columns; i++)
		{
			int x = view.x(view.xRegionMin + i * REGION_SIZE);
			int runEnd = Integer.MIN_VALUE;
			int runStart = 0;
			for (int j = 0; j < rows; j++)
			{
				int east = ((rx0 + i) << 8) | (ry0 + j);
				int y = view.y(view.yRegionMin + j * REGION_SIZE);
				if (edge(gridLines, east - 256, east))
				{
					if (runEnd == Integer.MIN_VALUE)
					{
						runEnd = y + size;
					}
					runStart = y;
				}
				else if (runEnd != Integer.MIN_VALUE)
				{
					graphics.drawLine(x, runStart, x, runEnd);
					runEnd = Integer.MIN_VALUE;
				}
			}
			if (runEnd != Integer.MIN_VALUE)
			{
				graphics.drawLine(x, runStart, x, runEnd);
			}
		}
		for (int j = 0; j <= rows; j++)
		{
			int y = view.y(view.yRegionMin + j * REGION_SIZE) + size;
			int runStart = Integer.MIN_VALUE;
			int runEnd = 0;
			for (int i = 0; i < columns; i++)
			{
				int north = ((rx0 + i) << 8) | (ry0 + j);
				int x = view.x(view.xRegionMin + i * REGION_SIZE);
				if (edge(gridLines, north - 1, north))
				{
					if (runStart == Integer.MIN_VALUE)
					{
						runStart = x;
					}
					runEnd = x + size;
				}
				else if (runStart != Integer.MIN_VALUE)
				{
					graphics.drawLine(runStart, y, runEnd, y);
					runStart = Integer.MIN_VALUE;
				}
			}
			if (runStart != Integer.MIN_VALUE)
			{
				graphics.drawLine(runStart, y, runEnd, y);
			}
		}

		// What it costs, written in the middle of every unlockable chunk (zoomed in far enough).
		if (costLabels && size >= MIN_LABEL_CHUNK_PIXELS)
		{
			for (int i = 0; i < columns; i++)
			{
				for (int j = 0; j < rows; j++)
				{
					int regionId = ((rx0 + i) << 8) | (ry0 + j);
					ChunkUnlockType type = TYPES[chunkType[regionId] - 1];
					if (type.isUnlockable())
					{
						drawCostLabel(graphics, view.x(view.xRegionMin + i * REGION_SIZE),
							view.y(view.yRegionMin + j * REGION_SIZE), size,
							ChunkUnlockType.costLabel(plugin, regionId, type), type.color);
					}
				}
			}
		}

		Font regionFont = FontManager.getRunescapeBoldFont().deriveFont(14f);
		graphics.setFont(regionFont);
		int hx = (hoveredRegionId >> 8) - rx0;
		int hy = (hoveredRegionId & 0xFF) - ry0;
		if (hoveredRegionId > 0 && hx >= 0 && hx < columns && hy >= 0 && hy < rows)
		{
			ChunkUnlockType type = TYPES[chunkType[hoveredRegionId] - 1];
			int xPos = view.x(view.xRegionMin + hx * REGION_SIZE);
			int yPos = view.y(view.yRegionMin + hy * REGION_SIZE);
			// Hover emphasis on unlockable chunks (outline only, no fill).
			if (type.isUnlockable())
			{
				graphics.setColor(CHUNK_BORDER);
				graphics.drawRect(xPos + 1, yPos + 1, size - 2, size - 2);
			}
			// Region ID in the top-left corner, only on the chunk under the mouse, so the
			// zoomed-out map isn't covered in numbers.
			String idText = String.valueOf(hoveredRegionId);
			int textX = xPos + 4;
			int textY = yPos + 16;
			if (textX > view.rect.getX() && textY > view.rect.getY())
			{
				graphics.setColor(Color.BLACK);
				graphics.drawString(idText, textX + 1, textY + 1);
				// Same colour family as the chunk's tint, solid so it stays readable.
				graphics.setColor(type.color);
				graphics.drawString(idText, textX, textY);
			}
		}

		// Current chunk: a double cyan outline, drawn after every other line so nothing covers it.
		int cx = (currentRegionId >> 8) - rx0;
		int cy = (currentRegionId & 0xFF) - ry0;
		if (currentRegionId > 0 && cx >= 0 && cx < columns && cy >= 0 && cy < rows)
		{
			int xPos = view.x(view.xRegionMin + cx * REGION_SIZE);
			int yPos = view.y(view.yRegionMin + cy * REGION_SIZE);
			graphics.setColor(CURRENT_BORDER);
			graphics.drawRect(xPos, yPos, size, size);
			graphics.drawRect(xPos + 1, yPos + 1, size - 2, size - 2);
		}

		// Region ID in the top-left corner of the world map: the hovered chunk's, else yours.
		drawRegionIdDisplay(graphics, view.rect, hoveredRegionId > 0 ? hoveredRegionId : currentRegionId);
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

	/** The chunk's name for a tooltip, without its "(region id)" number. */
	private String tooltipName(int regionId)
	{
		return plugin.getRegionName(regionId).replaceAll("\\s*\\(\\d+\\)$", "").trim();
	}

	private void drawHoverTooltip(Graphics2D graphics, Point mousePos, int regionId)
	{
		if (mousePos == null)
		{
			return;
		}

		String regionName = tooltipName(regionId);
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
		if (plugin.isTutorialPreview())
		{
			return plugin.isPracticeUnlocked(regionId);
		}
		return unlockedRegions.contains(String.valueOf(regionId)) || plugin.isFreeRegion(regionId);
	}

	/** Tooltip for an unlocked chunk: its name and how to open its tasks. */
	private void drawUnlockedTooltip(Graphics2D graphics, Point mousePos, int regionId)
	{
		if (mousePos == null)
		{
			return;
		}
		String line1 = tooltipName(regionId);
		String line2 = "Unlocked";
		// ChunkBlazer UI's keys legend on the map says it already (unless it's been closed).
		boolean keysShown = addOns.uiOn() && !"false".equals(configManager.getConfiguration("chunkblazerui", "showMapKeys"));
		String line3 = keysShown ? "" : "Hold " + config.worldMapTasksKey() + " + click to view tasks";
		int lines = keysShown ? 2 : 3;

		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = graphics.getFontMetrics();
		int padding = 6;
		int lineHeight = fm.getHeight();
		int width = Math.max(fm.stringWidth(line1), Math.max(fm.stringWidth(line2), fm.stringWidth(line3))) + padding * 2;
		int height = lineHeight * lines + padding * 2;
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
	public static Color fillFor(ChunkBlazerConfig config, ChunkUnlockType type)
	{
		switch (type)
		{
			case LOCKED:
				return config.worldMapLockedColor();
			case PAID:
				return config.worldMapPaidColor();
			case CHARTER:
				return config.worldMapCharterColor();
			case BOSS:
				return config.worldMapBossColor();
			default:
				return type.fill;
		}
	}
}
