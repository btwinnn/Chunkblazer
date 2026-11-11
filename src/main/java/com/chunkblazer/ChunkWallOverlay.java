/*
 * Copyright (c) 2026, Vani-Lab
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
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * A see-through wall on every border between an unlocked and a locked chunk. Only
 * border edges are drawn, not every tile: per-tile shading was too slow in Java2D.
 *
 * Unlocking a chunk opens its wall as a wave running outwards from the player. If the
 * unlock dealt reveal cards, the wall waits until every card has been flipped and
 * cleared off the screen.
 */
@Singleton
public class ChunkWallOverlay extends Overlay
{
	private static final int REGION_MASK = 63;
	private static final int LOCAL_TILE = Perspective.LOCAL_TILE_SIZE;
	// Wall height in local units (128 per tile): about three tiles tall.
	private static final int WALL_HEIGHT = 3 * LOCAL_TILE;
	// Only build walls within this many tiles of the player.
	private static final int DRAW_DISTANCE = 40;

	// Opening wave: how fast it travels along the wall, how long each section takes to
	// surge up and to drop away, and how much taller it gets at the top of the surge.
	private static final double WAVE_MS_PER_TILE = 28;
	private static final long SURGE_MS = 220;
	private static final long DROP_MS = 380;
	private static final double SURGE_HEIGHT = 0.6;
	// Long enough for the wave to cross a whole chunk (about 90 tiles from a corner).
	private static final long WAVE_LIFETIME_MS = (long) (90 * WAVE_MS_PER_TILE) + SURGE_MS + DROP_MS;

	// Flames: how much the top ripples normally, and how close (tiles) the player has to be
	// for the wall to flare up, and how much taller it gets when they're right next to it.
	private static final double FLAME = 0.12;
	private static final double FLARE_TILES = 6;
	private static final double FLARE_HEIGHT = 0.45;

	/**
	 * One chunk's wall opening: which chunk, when it started (-1 while it's waiting for
	 * the reveal cards to be flipped), and where the player stood when it started.
	 */
	private static final class Opening
	{
		final int regionId;
		long startedAt = -1;
		int originX;
		int originY;

		Opening(int regionId)
		{
			this.regionId = regionId;
		}

		boolean waiting()
		{
			return startedAt < 0;
		}
	}

	// Unlocked or not, for the chunks seen last frame, to notice the moment one unlocks.
	private final Map<Integer, Boolean> lastSeen = new HashMap<>();
	private final List<Opening> openings = new ArrayList<>();
	// This frame's time, scene origin and player position (world tiles), for the flames.
	private long now;
	private int baseX;
	private int baseY;
	private double playerX;
	private double playerY;

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final TaskCardOverlay cards;

	@Inject
	public ChunkWallOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config,
		TaskCardOverlay cards)
	{
		this.cards = cards;
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
		if (!config.showChunkWalls() || client.isInInstancedRegion())
		{
			// Start fresh when walls come back, so nothing unlocked meanwhile replays.
			lastSeen.clear();
			openings.clear();
			return null;
		}
		Player local = client.getLocalPlayer();
		LocalPoint playerPos = local == null ? null : local.getLocalLocation();
		if (playerPos == null)
		{
			return null;
		}

		int plane = client.getPlane();
		baseX = client.getBaseX();
		baseY = client.getBaseY();
		playerX = baseX + playerPos.getX() / (double) LOCAL_TILE;
		playerY = baseY + playerPos.getY() / (double) LOCAL_TILE;
		int last = Constants.SCENE_SIZE - 1;
		int minX = Math.max(1, playerPos.getSceneX() - DRAW_DISTANCE);
		int maxX = Math.min(last, playerPos.getSceneX() + DRAW_DISTANCE);
		int minY = Math.max(1, playerPos.getSceneY() - DRAW_DISTANCE);
		int maxY = Math.min(last, playerPos.getSceneY() + DRAW_DISTANCE);

		Color base = config.chunkWallColor();
		Map<Integer, Boolean> unlocked = new HashMap<>();
		Paint previousPaint = graphics.getPaint();
		now = System.currentTimeMillis();
		noticeUnlocks(unlocked, baseX, baseY, local, now);

		for (int sx = minX; sx < maxX; sx++)
		{
			for (int sy = minY; sy < maxY; sy++)
			{
				int worldX = baseX + sx;
				int worldY = baseY + sy;

				// West edge of this tile is a chunk border: compare the chunks either side.
				if ((worldX & REGION_MASK) == 0)
				{
					drawEdge(graphics, plane, unlocked, now, base, sx, sy, sx, sy + 1,
						regionAt(worldX, worldY), regionAt(worldX - 1, worldY), worldX, worldY + 0.5);
				}
				// South edge of this tile is a chunk border.
				if ((worldY & REGION_MASK) == 0)
				{
					drawEdge(graphics, plane, unlocked, now, base, sx, sy, sx + 1, sy,
						regionAt(worldX, worldY), regionAt(worldX, worldY - 1), worldX + 0.5, worldY);
				}
			}
		}

		graphics.setPaint(previousPaint);
		return null;
	}

	private static int regionAt(int worldX, int worldY)
	{
		return ((worldX >> 6) << 8) | (worldY >> 6);
	}

	private boolean isUnlocked(Map<Integer, Boolean> cache, int regionId)
	{
		return cache.computeIfAbsent(regionId, plugin::isRegionUnlocked);
	}

	/**
	 * One border edge. Unlocked on one side only: a normal wall. Unlocked on both sides
	 * because one of them has just been unlocked: the wall that used to be there, in
	 * the middle of its opening wave. (worldX, worldY) is the edge's midpoint.
	 */
	private void drawEdge(Graphics2D graphics, int plane, Map<Integer, Boolean> unlocked, long now, Color base,
		int cx1, int cy1, int cx2, int cy2, int regionA, int regionB, double worldX, double worldY)
	{
		boolean openA = isUnlocked(unlocked, regionA);
		boolean openB = isUnlocked(unlocked, regionB);
		if (openA != openB)
		{
			drawWall(graphics, plane, cx1, cy1, cx2, cy2, base, 1.0, 0);
			return;
		}
		if (!openA || openings.isEmpty())
		{
			return;
		}
		for (Opening opening : openings)
		{
			if (opening.regionId != regionA && opening.regionId != regionB)
			{
				continue;
			}
			if (opening.waiting())
			{
				// Unlocked, but its cards are still face down: keep the wall up for now.
				drawWall(graphics, plane, cx1, cy1, cx2, cy2, base, 1.0, 0);
				return;
			}
			double distance = Math.hypot(worldX - opening.originX, worldY - opening.originY);
			double t = now - opening.startedAt - distance * WAVE_MS_PER_TILE;
			if (t < 0)
			{
				// The wave hasn't reached this part yet: still a normal wall.
				drawWall(graphics, plane, cx1, cy1, cx2, cy2, base, 1.0, 0);
			}
			else if (t < SURGE_MS)
			{
				double k = easeOut(t / SURGE_MS);
				drawWall(graphics, plane, cx1, cy1, cx2, cy2, base, 1 + SURGE_HEIGHT * k, k);
			}
			else if (t < SURGE_MS + DROP_MS)
			{
				double k = easeIn((t - SURGE_MS) / DROP_MS);
				drawWall(graphics, plane, cx1, cy1, cx2, cy2, base, (1 + SURGE_HEIGHT) * (1 - k), 1 - k);
			}
			return;
		}
	}

	/**
	 * Notice chunks that were locked last frame and are unlocked now, and start their
	 * opening wave from where the player is standing. Chunks just coming into view
	 * (a new area loading) aren't unlocks, so they don't animate.
	 */
	private void noticeUnlocks(Map<Integer, Boolean> unlocked, int baseX, int baseY, Player local, long now)
	{
		openings.removeIf(o -> !o.waiting() && now - o.startedAt > WAVE_LIFETIME_MS);

		// Waiting walls open once every reveal card has been flipped AND cleared off the
		// screen (a flipped card stays up until it's clicked away), from wherever the
		// player is standing at that moment.
		if (!openings.isEmpty() && plugin.getUnrevealedTaskIds().isEmpty() && !cards.isActive())
		{
			net.runelite.api.coords.WorldPoint at = local.getWorldLocation();
			for (Opening opening : openings)
			{
				if (opening.waiting())
				{
					opening.startedAt = now;
					opening.originX = at.getX();
					opening.originY = at.getY();
				}
			}
		}

		Map<Integer, Boolean> current = new HashMap<>();
		int scene = Constants.SCENE_SIZE;
		for (int rx = baseX >> 6; rx <= (baseX + scene - 1) >> 6; rx++)
		{
			for (int ry = baseY >> 6; ry <= (baseY + scene - 1) >> 6; ry++)
			{
				int regionId = (rx << 8) | ry;
				boolean open = isUnlocked(unlocked, regionId);
				current.put(regionId, open);
				if (open && Boolean.FALSE.equals(lastSeen.get(regionId)))
				{
					// Starts on a later frame: straight away if no cards were dealt,
					// otherwise once they've all been flipped.
					openings.add(new Opening(regionId));
				}
			}
		}
		lastSeen.clear();
		lastSeen.putAll(current);
	}

	private static double easeOut(double t)
	{
		return 1 - Math.pow(1 - Math.max(0, Math.min(1, t)), 3);
	}

	private static double easeIn(double t)
	{
		double c = Math.max(0, Math.min(1, t));
		return c * c;
	}

	/**
	 * One wall panel standing on the edge between two scene grid corners. {@code scale}
	 * is its height (1 = normal, more during an opening surge, 0 = gone); {@code glow}
	 * (0 to 1) brightens it towards white at the top of the surge. On top of that, each
	 * corner's height flickers like a flame, and the panel flares when the player is near.
	 */
	private void drawWall(Graphics2D graphics, int plane, int cx1, int cy1, int cx2, int cy2,
		Color wallColor, double scale, double glow)
	{
		if (scale <= 0)
		{
			return;
		}
		double midX = baseX + (cx1 + cx2) / 2.0;
		double midY = baseY + (cy1 + cy2) / 2.0;
		double near = Math.max(0, 1 - Math.hypot(midX - playerX, midY - playerY) / FLARE_TILES);
		double flare = near * near;
		double flicker = FLAME + FLAME * 2 * flare;
		int heightA = flameHeight(cx1, cy1, scale, flicker, flare);
		int heightB = flameHeight(cx2, cy2, scale, flicker, flare);
		glow = Math.max(glow, 0.1 * (1 + flame(midX, midY)) + 0.5 * flare);
		Color base = brighten(wallColor, glow * 0.6);
		Color clear = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0);
		Color line = new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(255, base.getAlpha() * 2));

		LocalPoint a = new LocalPoint(cx1 * LOCAL_TILE, cy1 * LOCAL_TILE);
		LocalPoint b = new LocalPoint(cx2 * LOCAL_TILE, cy2 * LOCAL_TILE);
		Point groundA = Perspective.localToCanvas(client, a, plane);
		Point groundB = Perspective.localToCanvas(client, b, plane);
		Point topA = Perspective.localToCanvas(client, a, plane, heightA);
		Point topB = Perspective.localToCanvas(client, b, plane, heightB);
		if (groundA == null || groundB == null || topA == null || topB == null)
		{
			return;
		}

		Polygon panel = new Polygon(
			new int[]{groundA.getX(), groundB.getX(), topB.getX(), topA.getX()},
			new int[]{groundA.getY(), groundB.getY(), topB.getY(), topA.getY()},
			4);

		// Solid at the ground, fading to clear at the top.
		float groundMidX = (groundA.getX() + groundB.getX()) / 2f;
		float groundMidY = (groundA.getY() + groundB.getY()) / 2f;
		float topMidX = (topA.getX() + topB.getX()) / 2f;
		float topMidY = (topA.getY() + topB.getY()) / 2f;
		graphics.setPaint(new GradientPaint(groundMidX, groundMidY, base, topMidX, topMidY, clear));
		graphics.fillPolygon(panel);

		// A stronger line along the ground so the exact border is clear.
		graphics.setPaint(line);
		graphics.drawLine(groundA.getX(), groundA.getY(), groundB.getX(), groundB.getY());
	}

	/** Height at one grid corner: its flame flicker, plus the flare when the player is close. */
	private int flameHeight(int cx, int cy, double scale, double flicker, double flare)
	{
		double k = 1 + flicker * flame(baseX + cx, baseY + cy) + FLARE_HEIGHT * flare;
		return (int) Math.round(WALL_HEIGHT * scale * k);
	}

	/** -1 to 1: two waves rolling along the wall at different speeds, so it reads as fire. */
	private double flame(double worldX, double worldY)
	{
		double along = worldX + worldY;
		return 0.6 * Math.sin(along * 0.7 + now / 260.0) + 0.4 * Math.sin(along * 1.9 - now / 170.0);
	}

	/** Blend towards white, keeping the colour's transparency (a little more opaque when bright). */
	private static Color brighten(Color c, double amount)
	{
		double k = Math.max(0, Math.min(1, amount));
		return new Color(
			(int) Math.round(c.getRed() + (255 - c.getRed()) * k),
			(int) Math.round(c.getGreen() + (255 - c.getGreen()) * k),
			(int) Math.round(c.getBlue() + (255 - c.getBlue()) * k),
			(int) Math.round(Math.min(255, c.getAlpha() * (1 + k))));
	}
}
