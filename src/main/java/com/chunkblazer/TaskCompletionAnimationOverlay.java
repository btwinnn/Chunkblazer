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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.ImageUtil;

/**
 * Animated overlay for task completion, an eighth of the way down the screen.
 *
 * 0-1s: the bar opens out from its centre. 1-2.5s: the Task Complete box rises out of it
 * and the points box drops below it (the bar goes). 2.5-6.5s: hold. 6.5-9s: all of that
 * in reverse.
 */
@Singleton
public class TaskCompletionAnimationOverlay extends Overlay
{
	private static final long BAR = 1000;
	private static final long OPEN = 2500;
	private static final long HOLD_END = 6500;
	public static final long TOTAL = 9000;
	private static final Font NAME_FONT = new Font("Verdana", Font.BOLD, 11);
	private static final Font VALUE_FONT = new Font("Verdana", Font.PLAIN, 10);
	private static final Color SHADOW = new Color(0, 0, 0, 180);

	private static final BufferedImage BAR_IMAGE = load("1_Anim_Starting_Bar");
	private static final BufferedImage POINTS_BOX = load("Base_Anim_Points_Box");
	private static final BufferedImage TITLE_BOX = load("Base_Anim_TaskComplete_Box");

	private final ChunkBlazerConfig config;
	private final Client client;

	private long startTime = -1;
	private String taskName = "";
	private String regionName = "";
	private int pointsAwarded;

	@Inject
	public TaskCompletionAnimationOverlay(ChunkBlazerConfig config, Client client)
	{
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	private static BufferedImage load(String name)
	{
		return ImageUtil.loadImageResource(TaskCompletionAnimationOverlay.class, "Task_Complete_Anim/" + name + ".png");
	}

	public void showTaskCompletion(NuzlockeTask task, int points, String region)
	{
		if (task == null)
		{
			return;
		}
		taskName = task.getName();
		pointsAwarded = points > 0 ? points : task.getBasePoints();
		regionName = region == null ? "Unknown" : region.replaceAll("\\s*\\(.*", "").trim();
		startTime = System.currentTimeMillis();
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (startTime == -1 || !config.showTaskCompletionPopup())
		{
			return null;
		}
		long t = System.currentTimeMillis() - startTime;
		if (t > TOTAL)
		{
			startTime = -1;
			return null;
		}
		// The last part plays the opening backwards.
		if (t >= HOLD_END)
		{
			t = Math.max(0, (long) (OPEN * (1 - (t - HOLD_END) / (float) (TOTAL - HOLD_END))));
		}
		Skin.smooth(g);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		// Over the game view: in the fixed-size client that's the game window near its top
		// (the title box rises above this line), not the middle of the whole client.
		int cx = client.getViewportXOffset() + client.getViewportWidth() / 2;
		int y = client.isResized() ? client.getCanvasHeight() / 8 : client.getViewportYOffset() + TITLE_BOX.getHeight() + 6;
		Shape clip = g.getClip();
		if (t < BAR)
		{
			// The bar opens from the middle out.
			int full = BAR_IMAGE.getWidth();
			int w = (int) (full * ease(t / (float) BAR));
			int x = cx - full / 2;
			g.clip(new Rectangle(x + (full - w) / 2, y, w, BAR_IMAGE.getHeight()));
			g.drawImage(BAR_IMAGE, x, y, null);
		}
		else
		{
			// The title box rises above the line and the points box drops below it.
			float p = ease(Math.min(1, (t - BAR) / (float) (OPEN - BAR)));
			int up = (int) (TITLE_BOX.getHeight() * p);
			// (A few pixels' slack above, as the image has a soft edge.)
			g.clip(new Rectangle(0, y - up - (p < 0.98f ? 5 : TITLE_BOX.getHeight()), client.getCanvasWidth(), up + TITLE_BOX.getHeight()));
			g.drawImage(TITLE_BOX, cx - TITLE_BOX.getWidth() / 2, y - TITLE_BOX.getHeight(), null);
			g.setClip(clip);
			int x = cx - POINTS_BOX.getWidth() / 2;
			g.clip(new Rectangle(x, y, POINTS_BOX.getWidth(), (int) (POINTS_BOX.getHeight() * p)));
			g.drawImage(POINTS_BOX, x, y, null);
			if (p > 0.6f)
			{
				g.setFont(NAME_FONT);
				FontMetrics fm = g.getFontMetrics();
				String name = Skin.fit(fm, taskName, POINTS_BOX.getWidth() - 30);
				text(g, name, cx - fm.stringWidth(name) / 2, y + 25);
				g.setFont(VALUE_FONT);
				text(g, regionName, x + 175, y + 58);
				text(g, String.valueOf(pointsAwarded), x + 175, y + 78);
			}
		}
		g.setClip(clip);
		return null;
	}

	private static void text(Graphics2D g, String s, int x, int y)
	{
		g.setColor(SHADOW);
		g.drawString(s, x + 1, y + 1);
		g.setColor(Color.WHITE);
		g.drawString(s, x, y);
	}

	private static float ease(float t)
	{
		return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
	}
}
