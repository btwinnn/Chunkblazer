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
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Task cards without ChunkBlazer UI: freshly rolled tasks wait face down in btwinnn's original
 * art, in the middle of the view. Click the card to turn it over (that's when the task is
 * yours), click again for the next one; Send 5 / Send All take a pile in at once. No dealing,
 * flipping or flying: ChunkBlazer UI's TaskCardOverlay does all that.
 */
@Slf4j
@Singleton
public class SimpleCardOverlay extends Overlay implements TaskCardShow
{
	private static final int CARD_WIDTH = 260;
	// Height over width, as the original card art (356 x 200).
	private static final double ASPECT = 200.0 / 356.0;
	private static final int DECK_OFFSET = 7;
	private static final int DECK_MAX_VISIBLE = 5;
	private static final Color PROMPT = new Color(235, 235, 235, 225);

	private final Client client;
	private final ChunkBlazerConfig config;
	private final ChunkBlazerPlugin plugin;

	// Pending task ids, top first, and the one turned face up (null while face down).
	private volatile List<String> pile = new ArrayList<>();
	private volatile String faceUp;
	private String lastPendingRaw;
	// Where the card and buttons were drawn last frame, for clicks (mouse thread).
	private volatile Rectangle cardBounds;
	private volatile Rectangle fiveBounds;
	private volatile Rectangle allBounds;
	// A click to act on, handed to the client thread: 1 the card, 5 Send 5, -1 Send All.
	private volatile int request;

	// The art at the current card size.
	private int artWidth;
	private final BufferedImage[] backs = new BufferedImage[TaskCardTier.values().length];
	private String frontFor;
	private BufferedImage front;

	@Inject
	private SimpleCardOverlay(Client client, ChunkBlazerConfig config, ChunkBlazerPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public boolean isActive()
	{
		return config.showTaskCards() && !pile.isEmpty();
	}

	@Override
	public boolean onClick(int x, int y)
	{
		if (!isActive())
		{
			return false;
		}
		Rectangle all = allBounds;
		Rectangle five = fiveBounds;
		Rectangle card = cardBounds;
		if (all != null && all.contains(x, y))
		{
			request = -1;
			return true;
		}
		if (five != null && five.contains(x, y))
		{
			request = 5;
			return true;
		}
		if (card != null && card.contains(x, y))
		{
			request = 1;
			return true;
		}
		return false;
	}

	@Override
	public void clear()
	{
		pile = new ArrayList<>();
		faceUp = null;
		lastPendingRaw = null;
		request = 0;
		cardBounds = null;
		fiveBounds = null;
		allBounds = null;
	}

	@Override
	public void onSweepStarted(boolean test, long durationMs)
	{
	}

	@Override
	public void onSweepFinished()
	{
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTaskCards())
		{
			// Turned off: don't strand pending tasks behind cards that will never show.
			for (String taskId : plugin.getUnrevealedTaskIds())
			{
				plugin.revealTaskCard(taskId);
			}
			clear();
			return null;
		}
		sync();
		act();
		List<String> cards = pile;
		Rectangle viewport = viewport();
		if (cards.isEmpty() || viewport == null)
		{
			cardBounds = null;
			fiveBounds = null;
			allBounds = null;
			return null;
		}
		int width = Math.max(48, Math.min(CARD_WIDTH, viewport.width - 80));
		int height = (int) Math.round(width * ASPECT);
		Rectangle rest = new Rectangle(viewport.x + (viewport.width - width) / 2, viewport.y + (viewport.height - height) / 2,
			width, height);
		if (width != artWidth)
		{
			artWidth = width;
			java.util.Arrays.fill(backs, null);
			front = null;
		}
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		// The rest of the pile, face down behind the top card.
		int behind = Math.min(cards.size() - 1, DECK_MAX_VISIBLE);
		for (int k = behind; k >= 1; k--)
		{
			graphics.drawImage(back(tierOf(cards.get(k)), width, height), rest.x - k * DECK_OFFSET, rest.y - k * DECK_OFFSET, null);
		}
		String top = cards.get(0);
		boolean up = top.equals(faceUp);
		graphics.drawImage(up ? front(top, width, height) : back(tierOf(top), width, height), rest.x, rest.y, null);
		cardBounds = rest;
		drawPrompt(graphics, viewport, rest, up, cards.size());
		return null;
	}

	/** The pending set from the plugin: new cards go under the pile, revealed ones drop off. */
	private void sync()
	{
		String raw = plugin.getUnrevealedTasksRaw();
		raw = raw == null ? "" : raw;
		if (raw.equals(lastPendingRaw))
		{
			return;
		}
		lastPendingRaw = raw;
		List<String> cards = new ArrayList<>();
		for (String taskId : plugin.getUnrevealedTaskIds())
		{
			if (plugin.getTaskById(taskId) == null)
			{
				// No task behind it: it could never be revealed by clicking, so drop it.
				log.warn("Unknown unrevealed task '{}' dropped", taskId);
				plugin.revealTaskCard(taskId);
				continue;
			}
			cards.add(taskId);
		}
		// A face-up card has already been revealed (so it's left the pending set): keep it on top.
		String up = faceUp;
		if (up != null && !cards.contains(up))
		{
			if (pile.contains(up))
			{
				cards.add(0, up);
			}
			else
			{
				faceUp = null;
			}
		}
		pile = cards;
	}

	/** The click from the mouse thread: turn the card, send it on, or send a few at once. Client thread. */
	private void act()
	{
		int req = request;
		request = 0;
		List<String> cards = new ArrayList<>(pile);
		if (req == 0 || cards.isEmpty())
		{
			return;
		}
		if (req == 1)
		{
			String top = cards.get(0);
			if (top.equals(faceUp))
			{
				// Face up: off to the task list, next card.
				faceUp = null;
				cards.remove(0);
			}
			else
			{
				faceUp = top;
				plugin.revealTaskCard(top);
			}
			pile = cards;
			return;
		}
		int n = req < 0 ? cards.size() : Math.min(req, cards.size());
		for (int i = 0; i < n; i++)
		{
			String taskId = cards.get(i);
			if (!taskId.equals(faceUp))
			{
				plugin.revealTaskCard(taskId);
			}
		}
		faceUp = null;
		pile = new ArrayList<>(cards.subList(n, cards.size()));
		lastPendingRaw = null;
	}

	private TaskCardTier tierOf(String taskId)
	{
		NuzlockeTask task = plugin.getTaskById(taskId);
		return task == null ? TaskCardTier.values()[0] : TaskCardTier.fromTask(task);
	}

	private BufferedImage back(TaskCardTier tier, int w, int h)
	{
		if (backs[tier.ordinal()] == null)
		{
			backs[tier.ordinal()] = art(tier, false, null, w, h);
		}
		return backs[tier.ordinal()];
	}

	private BufferedImage front(String taskId, int w, int h)
	{
		if (front == null || !taskId.equals(frontFor))
		{
			NuzlockeTask task = plugin.getTaskById(taskId);
			front = art(tierOf(taskId), true, task == null ? taskId : task.getName(), w, h);
			frontFor = taskId;
		}
		return front;
	}

	private static BufferedImage art(TaskCardTier tier, boolean face, String name, int w, int h)
	{
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		Skin.smooth(g);
		CardArt.paint(g, new Rectangle(0, 0, w, h), tier, face, name);
		g.dispose();
		return img;
	}

	private void drawPrompt(Graphics2D graphics, Rectangle viewport, Rectangle rest, boolean up, int total)
	{
		String text = up
			? (total > 1 ? "Task added. Click to send it to your list (" + (total - 1) + " left)" : "Task added. Click to send it to your list")
			: (total > 1 ? "Click to reveal. " + total + " cards to open" : "Click the card to reveal your task");
		graphics.setFont(Skin.bold());
		FontMetrics fm = graphics.getFontMetrics();
		int cx = viewport.x + viewport.width / 2;
		Skin.text(graphics, text, cx - fm.stringWidth(text) / 2, rest.y - 14, new Color(255, 255, 0));
		Rectangle five = null;
		Rectangle all = null;
		if (total > 1)
		{
			int by = rest.y + rest.height + 10;
			if (total > 5)
			{
				five = button(graphics, "Send 5 To Task List", cx, by);
				all = button(graphics, "Send All To Task List (" + total + ")", cx, by + five.height + 6);
			}
			else
			{
				all = button(graphics, "Send All To Task List (" + total + ")", cx, by);
			}
		}
		fiveBounds = five;
		allBounds = all;
	}

	private static Rectangle button(Graphics2D graphics, String text, int centreX, int top)
	{
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		Rectangle bounds = new Rectangle(centreX - (fm.stringWidth(text) + 20) / 2, top, fm.stringWidth(text) + 20, fm.getHeight() + 8);
		Skin.button(graphics, bounds, false, false);
		Skin.text(graphics, text, centreX - fm.stringWidth(text) / 2, top + 4 + fm.getAscent(), PROMPT);
		return bounds;
	}

	private Rectangle viewport()
	{
		int w = client.getViewportWidth();
		int h = client.getViewportHeight();
		return w <= 0 || h <= 0 ? null : new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(), w, h);
	}
}
