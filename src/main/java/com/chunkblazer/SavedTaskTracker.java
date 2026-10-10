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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * The saved-tasks bubble: a round task-list button with a count of your saved (starred)
 * tasks. Clicking it opens the saved list over the side panel, like a quest's
 * requirements (TaskBrowserOverlay draws that). Alt + drag to move it; its size follows
 * the Window Scale setting. Registered by TaskBrowserOverlay.
 */
@Singleton
public class SavedTaskTracker extends Overlay
{
	private static final int SIZE = 32;
	private static final Color FILL = new Color(48, 42, 34, 235);
	private static final Color RIM = new Color(255, 152, 31);
	private static final Color PAPER = new Color(232, 214, 170);

	private final Client client;
	private final ChunkBlazerConfig config;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	// The task window, which draws the saved list (a Provider: the window also uses this bubble).
	private final Provider<TaskBrowserOverlay> browser;
	private volatile boolean hovered;

	private final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			// Alt + drag moves the bubble; leave those clicks to RuneLite.
			if (hovered && !event.isAltDown() && event.getButton() == MouseEvent.BUTTON1)
			{
				browser.get().toggleSavedPane();
				event.consume();
			}
			return event;
		}
	};

	@Inject
	public SavedTaskTracker(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config,
		OverlayManager overlayManager, MouseManager mouseManager, Provider<TaskBrowserOverlay> browser)
	{
		super(plugin);
		this.client = client;
		this.config = config;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.browser = browser;
		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setPriority(OverlayPriority.LOW);
	}

	public void startUp()
	{
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouse);
	}

	public void shutDown()
	{
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(mouse);
		hovered = false;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showSavedTaskTracker() || client.getGameState() != GameState.LOGGED_IN)
		{
			hovered = false;
			return null;
		}
		int d = Math.round(SIZE * config.widgetScale() / 100f);
		int top = d / 6;
		Rectangle bounds = getBounds();
		net.runelite.api.Point m = client.getMouseCanvasPosition();
		hovered = m != null && bounds != null && new Rectangle(bounds.x, bounds.y + top, d, d).contains(m.getX(), m.getY());

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(FILL);
		graphics.fillOval(0, top, d, d);
		graphics.setColor(hovered ? Color.WHITE : RIM);
		graphics.setStroke(new BasicStroke(Math.max(1.5f, d / 16f)));
		graphics.drawOval(0, top, d, d);

		// The icon: a little task sheet with three lines.
		int w = d * 9 / 20;
		int h = d * 11 / 20;
		int px = (d - w) / 2;
		int py = top + (d - h) / 2;
		graphics.setColor(PAPER);
		graphics.fillRoundRect(px, py, w, h, 3, 3);
		graphics.setColor(new Color(110, 80, 40));
		for (int i = 1; i <= 3; i++)
		{
			int ly = py + h * i / 4;
			graphics.drawLine(px + w / 5, ly, px + w * 4 / 5, ly);
		}

		// How many saved tasks are waiting.
		int count = browser.get().savedTasks().size();
		if (count > 0)
		{
			String text = String.valueOf(count);
			graphics.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics fm = graphics.getFontMetrics();
			int bw = Math.max(14, fm.stringWidth(text) + 6);
			graphics.setColor(RIM);
			graphics.fillRoundRect(d - bw + 4, 0, bw, 14, 14, 14);
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, d - bw + 4 + (bw - fm.stringWidth(text)) / 2, 11);
		}
		return new Dimension(d + 4, d + top);
	}
}
