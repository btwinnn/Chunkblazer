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
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * How every ChunkBlazer window, box, button and bar is drawn: the game's own interfaces,
 * after the Leagues task screens. The stone, frames, bevels and icons are images in
 * {@code ui/} (drawn stretched round the edges, nine-slice style), so this is mostly
 * where to put them. The overlays lay themselves out and say what goes where.
 */
final class Skin
{
	private static final BufferedImage FRAME = image("frame");
	private static final BufferedImage RAISED = image("raised");
	private static final BufferedImage SUNK = image("sunk");
	static final BufferedImage CROSS = image("cross");
	static final BufferedImage CROSS_HOVER = image("cross_hover");
	private static final Paint STONE = tile("stone");
	private static final Paint STONE_DARK = tile("stone_dark");
	private static final Color FIELD = new Color(30, 26, 21);
	static final Color FILL = new Color(66, 160, 70);

	private Skin()
	{
	}

	static BufferedImage image(String name)
	{
		return ImageUtil.loadImageResource(Skin.class, "ui/" + name + ".png");
	}

	private static Paint tile(String name)
	{
		return new TexturePaint(image(name), new Rectangle(0, 0, 64, 64));
	}

	static Font bold()
	{
		return FontManager.getRunescapeBoldFont();
	}

	static Font regular()
	{
		return FontManager.getRunescapeFont();
	}

	static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}


	/** Text the game's way: crisp, with a hard black drop shadow. */
	static void text(Graphics2D g, String s, int x, int y)
	{
		if (s == null || s.isEmpty())
		{
			return;
		}
		Color color = g.getColor();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(new Color(0, 0, 0, color.getAlpha()));
		g.drawString(s, x + 1, y + 1);
		g.setColor(color);
		g.drawString(s, x, y);
	}

	static void text(Graphics2D g, String s, int x, int y, Color color)
	{
		g.setColor(color);
		text(g, s, x, y);
	}

	/** Text centred on {@code cx}, baseline {@code y}. */
	static void centred(Graphics2D g, String s, int cx, int y, Color color)
	{
		g.setColor(color);
		text(g, s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
	}

	static Paint stone(boolean dark)
	{
		return dark ? STONE_DARK : STONE;
	}

	/** Draws an image's border round {@code r}: corners as they are, edges stretched (its middle is left alone). */
	static void nine(Graphics2D g, BufferedImage img, Rectangle r, int e)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int[] dx = {r.x, r.x + e, r.x + r.width - e, r.x + r.width};
		int[] dy = {r.y, r.y + e, r.y + r.height - e, r.y + r.height};
		int[] sx = {0, e, w - e, w};
		int[] sy = {0, e, h - e, h};
		for (int i = 0; i < 3; i++)
		{
			for (int j = 0; j < 3; j++)
			{
				if (i != 1 || j != 1)
				{
					g.drawImage(img, dx[i], dy[j], dx[i + 1], dy[j + 1], sx[i], sy[j], sx[i + 1], sy[j + 1], null);
				}
			}
		}
	}

	/** A stone bevel: raised (light top-left) or sunk, edged in black, like every button and box in the game. */
	static void bevel(Graphics2D g, Rectangle r, boolean raised)
	{
		nine(g, raised ? RAISED : SUNK, r, 2);
	}

	/** A smaller box (trackers, the quest pane); {@code accent} tints its top edge, or null. */
	static void panel(Graphics2D g, Rectangle r, int header, Color accent)
	{
		stoneFrame(g, r, header);
		if (accent != null)
		{
			g.setColor(accent);
			g.fillRect(r.x + 4, r.y + 4, r.width - 8, 2);
		}
	}

	/** Stone inside a black-edged steel band with studs at the corners, a darker header band of {@code header} pixels. */
	static void stoneFrame(Graphics2D g, Rectangle r, int header)
	{
		Paint old = g.getPaint();
		g.setColor(new Color(0, 0, 0, 110));
		g.fillRect(r.x + 3, r.y + 3, r.width, r.height);
		g.setPaint(STONE);
		g.fillRect(r.x, r.y, r.width, r.height);
		if (header > 0)
		{
			g.setPaint(STONE_DARK);
			g.fillRect(r.x + 3, r.y + 3, r.width - 6, header - 3);
			divider(g, r.x + 3, r.y + header, r.x + r.width - 4);
		}
		nine(g, FRAME, r, 6);
		g.setPaint(old);
	}

	/** A horizontal groove. */
	static void divider(Graphics2D g, int x1, int y, int x2)
	{
		g.setColor(new Color(36, 30, 24));
		g.drawLine(x1, y, x2, y);
		g.setColor(new Color(112, 99, 79));
		g.drawLine(x1, y + 1, x2, y + 1);
	}

	/** A stone button's face (the caller draws its label). {@code active}: pressed or chosen. */
	static void button(Graphics2D g, Rectangle r, boolean hover, boolean active)
	{
		g.setPaint(STONE);
		g.fillRect(r.x, r.y, r.width, r.height);
		g.setColor(active ? new Color(150, 40, 20, 110) : new Color(255, 255, 255, hover ? 28 : 0));
		g.fillRect(r.x, r.y, r.width, r.height);
		bevel(g, r, !active);
	}

	/**
	 * A progress bar filled to {@code fraction}: a dark groove with a soft green fill (or
	 * {@code color} when {@code tinted}, for a countdown). {@code glow} (0 to 1) rims it in yellow.
	 */
	static void bar(Graphics2D g, int x, int y, int width, int height, float fraction, Color color, float glow, boolean tinted)
	{
		int fill = Math.round(width * Math.max(0, Math.min(1, fraction)));
		g.setColor(Color.BLACK);
		g.fillRect(x - 1, y - 1, width + 2, height + 2);
		g.setColor(new Color(40, 34, 27));
		g.fillRect(x, y, width, height);
		g.setColor(tinted ? color : FILL);
		g.fillRect(x, y, fill, height);
		g.setColor(new Color(255, 255, 255, 60));
		g.fillRect(x, y, fill, Math.max(1, height / 4));
		if (glow > 0)
		{
			g.setColor(new Color(255, 255, 0, Math.round(200 * glow)));
			g.drawRect(x - 1, y - 1, width + 1, height + 1);
		}
	}

	/** A scrollbar down the right of {@code area}: a sunk track and a raised thumb. */
	static void scrollbar(Graphics2D g, Rectangle area, int thumbY, int thumbHeight)
	{
		Rectangle track = new Rectangle(area.x + area.width - 8, area.y + 1, 7, area.height - 2);
		g.setColor(FIELD);
		g.fillRect(track.x, track.y, track.width, track.height);
		Rectangle thumb = new Rectangle(track.x, Math.max(track.y, thumbY), track.width, thumbHeight);
		button(g, thumb, false, false);
	}

	/** A hint by the mouse: the game's yellow examine box. */
	static void tooltip(Graphics2D g, Rectangle box, String text)
	{
		g.setColor(new Color(255, 255, 160));
		g.fillRect(box.x, box.y, box.width, box.height);
		g.setColor(Color.BLACK);
		g.drawRect(box.x, box.y, box.width, box.height);
		g.drawString(text, box.x + 5, box.y + box.height - 4);
	}

	/** The game's close button: a stone square with a gold cross. */
	static void close(Graphics2D g, Rectangle r, boolean hover)
	{
		button(g, r, hover, false);
		g.drawImage(hover ? CROSS_HOVER : CROSS, r.x + (r.width - 11) / 2, r.y + (r.height - 11) / 2, null);
	}


	/** Shorten text with "..." so it fits the width. */
	static String fit(FontMetrics fm, String text, int width)
	{
		if (text == null || width <= 0)
		{
			return "";
		}
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end) + "...") > width)
		{
			end--;
		}
		return text.substring(0, end) + "...";
	}
}
