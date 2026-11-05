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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Pokemon-style area sign: on entering a different chunk, a banner with its name and
 * unlock status or cost slides down from the top of the game view, holds, then slides
 * back. Its own always-on-top overlay so nothing covers it; registered by
 * TaskBrowserOverlay.
 *
 * In a locked chunk the banner stays until you unlock it or walk out, even into a chunk
 * with no banner of its own. An unlocked chunk among the last few you were in gets no
 * repeat banner, so laps across chunk borders stay quiet. A dungeon counts as the land
 * above it, unless you come out in a different chunk.
 */
@Singleton
public class ChunkNameBanner extends Overlay
{
	private static final long SLIDE_MS = 350;
	// Quicker exit when another banner is waiting to come in.
	private static final long SWAP_SLIDE_MS = 220;
	private static final long HOLD_MS = 2500;
	private static final long UNLOCKED_HOLD_MS = 2000;
	private static final long FLASH_MS = 700;
	private static final int TOP_MARGIN = 12;
	private static final int PAD_X = 18;
	private static final int PAD_Y = 8;
	// How many recent places stay quiet: enough for a course or loop over 3-4 chunks.
	private static final int RECENT_PLACES = 4;
	// Dungeons sit 6400 tiles (100 regions) north of the land above them.
	private static final int UNDERGROUND_REGION_Y = 100;


	private enum Phase
	{
		HIDDEN, ENTER, HOLD, LEAVE
	}

	/** What one banner says, and whether it waits for the chunk to be unlocked. */
	private static final class Banner
	{
		final String title;
		final String subtitle;
		final Color color;
		final int regionId;
		final boolean sticky;

		Banner(String title, String subtitle, Color color, int regionId, boolean sticky)
		{
			this.title = title;
			this.subtitle = subtitle;
			this.color = color;
			this.regionId = regionId;
			this.sticky = sticky;
		}
	}

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;

	private int lastRegionId = -1;
	private String lastName;
	// The last few unlocked places you were in (dungeons count as the land above them),
	// oldest first.
	private final Map<Integer, Boolean> recent = new LinkedHashMap<Integer, Boolean>(8, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest)
		{
			return size() > RECENT_PLACES;
		}
	};

	// The banner on screen and where it is in its animation.
	private Phase phase = Phase.HIDDEN;
	private long phaseStart;
	private long holdMs;
	private long leaveMs;
	private double leaveFrom;
	private long flashAt = -1;
	private String title;
	private String subtitle;
	private Color subtitleColor;
	private int bannerRegion;
	private boolean sticky;

	// The next banner, shown once the current one has slid away.
	private Banner pending;

	@Inject
	public ChunkNameBanner(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	/** Called every frame; watches for a chunk change and draws the banner while it's showing. */
	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showChunkNamePopups())
		{
			phase = Phase.HIDDEN;
			pending = null;
			return null;
		}
		checkForNewChunk();
		long now = System.currentTimeMillis();
		advance(now);
		if (phase == Phase.HIDDEN)
		{
			return null;
		}
		double flash = flashAt < 0 ? 0 : Math.max(0, 1 - (now - flashAt) / (double) FLASH_MS);
		draw(graphics, shownAmount(now), flash);
		return null;
	}

	/** Move the animation along: finish sliding in, wait, react to an unlock, slide out. */
	private void advance(long now)
	{
		long t = now - phaseStart;
		switch (phase)
		{
			case ENTER:
				if (t >= SLIDE_MS)
				{
					phase = Phase.HOLD;
					phaseStart = now;
				}
				break;
			case HOLD:
				if (sticky)
				{
					// Waiting in a locked chunk: the moment it's unlocked, say so, then leave.
					if (plugin.isRegionUnlocked(bannerRegion))
					{
						subtitle = "Unlocked!";
						subtitleColor = ChunkUnlockType.UNLOCKED.color;
						sticky = false;
						holdMs = UNLOCKED_HOLD_MS;
						flashAt = now;
						phaseStart = now;
					}
				}
				else if (t >= holdMs)
				{
					leave(now, SLIDE_MS);
				}
				break;
			case LEAVE:
				if (t >= leaveMs)
				{
					phase = Phase.HIDDEN;
					if (pending != null)
					{
						Banner next = pending;
						pending = null;
						show(next, now);
					}
				}
				break;
			default:
				break;
		}
	}

	/** 0 = hidden above the screen, 1 = fully shown. Eases in and out. */
	private double shownAmount(long now)
	{
		double t = now - phaseStart;
		switch (phase)
		{
			case ENTER:
				return ease(Math.min(1, t / SLIDE_MS));
			case LEAVE:
				// Leaves from wherever it was, even part-way through sliding in.
				return leaveFrom * (1 - ease(Math.min(1, t / leaveMs)));
			case HOLD:
				return 1;
			default:
				return 0;
		}
	}

	private void show(Banner banner, long now)
	{
		title = banner.title;
		subtitle = banner.subtitle;
		subtitleColor = banner.color;
		bannerRegion = banner.regionId;
		sticky = banner.sticky;
		holdMs = HOLD_MS;
		flashAt = -1;
		phase = Phase.ENTER;
		phaseStart = now;
	}

	private void leave(long now, long duration)
	{
		leaveFrom = shownAmount(now);
		leaveMs = duration;
		phase = Phase.LEAVE;
		phaseStart = now;
	}

	/** Show a banner now, or after the current one has slid away. */
	private void queue(Banner banner)
	{
		long now = System.currentTimeMillis();
		if (phase == Phase.HIDDEN)
		{
			show(banner, now);
			return;
		}
		pending = banner;
		if (phase != Phase.LEAVE)
		{
			leave(now, SWAP_SLIDE_MS);
		}
	}

	private void checkForNewChunk()
	{
		Player local = client.getLocalPlayer();
		if (local == null || client.isInInstancedRegion())
		{
			return;
		}
		WorldPoint location = local.getWorldLocation();
		if (location == null)
		{
			return;
		}
		int regionId = location.getRegionID();
		if (regionId == lastRegionId)
		{
			return;
		}
		boolean firstLook = lastRegionId == -1;
		lastRegionId = regionId;

		String name = chunkName(regionId);
		// Left the locked chunk the sign is waiting on (declined it, walked back out):
		// let it go, even if where you are now doesn't get a banner of its own.
		if (sticky && (phase == Phase.ENTER || phase == Phase.HOLD) && (name == null || !name.equals(title)))
		{
			sticky = false;
			leave(System.currentTimeMillis(), SLIDE_MS);
		}
		// Nothing for unnamed areas, or when moving between two regions of the same
		// chunk (a surface and its dungeon share a name).
		if (name == null || name.equals(lastName))
		{
			lastName = name;
			return;
		}
		lastName = name;

		boolean unlocked = plugin.isRegionUnlocked(regionId);
		// On login, only a locked chunk gets a sign (it's the one asking to be unlocked).
		// After that, an unlocked chunk only gets one if you haven't been there recently.
		// Locked places aren't remembered, so a chunk you've since unlocked gets one.
		int place = (regionId & 0xFF) >= UNDERGROUND_REGION_Y ? regionId - UNDERGROUND_REGION_Y : regionId;
		if (unlocked && (recent.put(place, true) != null || firstLook))
		{
			return;
		}
		boolean neighbor = !unlocked && plugin.getNeighborRegionIds().contains(regionId);
		ChunkUnlockType type = ChunkUnlockType.of(plugin, regionId, unlocked, neighbor);
		// Only a chunk you could unlock right now waits on screen. One you were moved into
		// (a random event's prison, a teleport) just says it's locked and goes.
		queue(new Banner(name, statusText(type, ChunkUnlockType.costLabel(plugin, regionId, type)),
			type.color, regionId, type.isUnlockable()));
	}

	/** "Lumbridge (12850)" -> "Lumbridge"; null for regions without a chunk name. */
	private String chunkName(int regionId)
	{
		String full = plugin.getRegionName(regionId);
		if (full == null || full.startsWith("Unknown Region"))
		{
			return null;
		}
		return full.replaceAll("\\s*\\(\\d+\\)$", "").trim();
	}

	private static String statusText(ChunkUnlockType type, String cost)
	{
		switch (type)
		{
			case UNLOCKED:
				return "Unlocked";
			case LOCKED:
				return "Locked";
			case FREE:
			case CHARTER:
				return "Free to unlock";
			case BOSS:
				return "Unlock with 1 Boss Token";
			default:
				return "Unlock for " + cost;
		}
	}

	private static double ease(double t)
	{
		return 1 - Math.pow(1 - t, 3);
	}

	/** {@code flash} (0 to 1) brightens the border for a moment when the chunk is unlocked. */
	private void draw(Graphics2D graphics, double shown, double flash)
	{
		drawSkinned(graphics, shown, flash);
	}

	/** Old School: a stone sign with the name in the game's bold font, the status under it. */
	private void drawSkinned(Graphics2D graphics, double shown, double flash)
	{
		Font titleFont = Skin.bold();
		Font subtitleFont = FontManager.getRunescapeSmallFont();
		FontMetrics tm = graphics.getFontMetrics(titleFont);
		FontMetrics sm = graphics.getFontMetrics(subtitleFont);
		int width = Math.max(tm.stringWidth(title), sm.stringWidth(subtitle)) + PAD_X * 2;
		int height = tm.getAscent() + sm.getHeight() + PAD_Y * 2;
		int viewX = client.getViewportXOffset();
		int viewWidth = client.getViewportWidth();
		int x = viewX + (viewWidth - width) / 2;
		int shownY = client.getViewportYOffset() + TOP_MARGIN;
		int y = (int) Math.round(shownY - (height + TOP_MARGIN) * (1 - shown));

		Composite previousComposite = graphics.getComposite();
		Object previousAntialias = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, shown))));
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		Skin.stoneFrame(graphics, new Rectangle(x, y, width, height), 0);
		if (flash > 0)
		{
			graphics.setColor(new Color(255, 255, 0, (int) (220 * flash)));
			graphics.drawRect(x - 2, y - 2, width + 3, height + 3);
		}
		Color status = flash > 0 ? blend(subtitleColor, ChunkUnlockType.UNLOCKED.color, flash) : subtitleColor;
		int titleY = y + PAD_Y + tm.getAscent() - 3;
		graphics.setFont(titleFont);
		Skin.centred(graphics, title, x + width / 2, titleY, new Color(255, 152, 31));
		graphics.setFont(subtitleFont);
		Skin.centred(graphics, subtitle, x + width / 2, titleY + sm.getAscent() + 3, status);
		graphics.setComposite(previousComposite);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialias);
	}

	private static Color blend(Color from, Color to, double amount)
	{
		double k = Math.max(0, Math.min(1, amount));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * k),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * k),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * k));
	}
}
