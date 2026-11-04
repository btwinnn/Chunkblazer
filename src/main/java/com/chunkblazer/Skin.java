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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * How every ChunkBlazer window, box, button and bar is drawn: the game's own interfaces,
 * after the Leagues task screens. The stone, frames, bevels and icons are images in
 * {@code ui/} (drawn stretched round the edges, nine-slice style), so this is mostly
 * where to put them. The overlays lay themselves out and say what goes where.
 */
public final class Skin
{
	private static final BufferedImage RAISED = image("raised");
	private static final BufferedImage SUNK = image("sunk");
	private static final Paint OS_STONE = tile("stone");
	private static final Paint OS_STONE_DARK = tile("stone_dark");
	private static Paint STONE = OS_STONE;
	private static Paint STONE_DARK = OS_STONE_DARK;
	private static Color FIELD = new Color(30, 26, 21);
	/** Antialiasing on; returns what it was, for {@link #restore}. */
	public static Object smooth(Graphics2D g)
	{
		Object was = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return was;
	}

	/** Antialiasing back to {@code was}, from {@link #smooth}. */
	public static void restore(Graphics2D g, Object was)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, was == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : was);
	}

	public static Color FILL = new Color(66, 160, 70);
	private static volatile ThemePalette palette = ThemePalette.original();

	private Skin()
	{
	}

	public static BufferedImage image(String name)
	{
		return ImageUtil.loadImageResource(Skin.class, "ui/" + name + ".png");
	}

	private static Paint tile(String name)
	{
		return new TexturePaint(image(name), new Rectangle(0, 0, 64, 64));
	}

	// Told whenever the palette changes, so overlays can pick up its text colours.
	private static final List<Consumer<ThemePalette>> RETHEME = new CopyOnWriteArrayList<>();

	/**
	 * Switch every window, box, button and bar to {@code p} (ChunkBlazer's own when null), and
	 * the overlays' own text colours with them (they read them each frame). Any thread.
	 * ChunkBlazer UI's Themes makes the palettes for its themes.
	 */
	public static void setPalette(ThemePalette p)
	{
		if (p == null)
		{
			p = ThemePalette.original();
		}
		boolean os = p.theme == Theme.OLD_SCHOOL;
		STONE = os ? OS_STONE : p.tile != null ? new TexturePaint(p.tile, new Rectangle(0, 0, 64, 64)) : p.base;
		STONE_DARK = os ? OS_STONE_DARK : p.tileDark != null ? new TexturePaint(p.tileDark, new Rectangle(0, 0, 64, 64)) : p.baseDark;
		FIELD = p.field;
		FILL = p.barFill;
		palette = p;
		for (Consumer<ThemePalette> listener : RETHEME)
		{
			listener.accept(p);
		}
	}

	/** Call {@code listener} with the palette now and whenever it changes. */
	public static void onRetheme(Consumer<ThemePalette> listener)
	{
		RETHEME.add(listener);
		listener.accept(palette);
	}

	public static ThemePalette palette()
	{
		return palette;
	}

	public static Theme theme()
	{
		return palette.theme;
	}

	private static boolean oldSchool()
	{
		return palette.theme == Theme.OLD_SCHOOL;
	}

	public static Font bold()
	{
		return FontManager.getRunescapeBoldFont();
	}

	public static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	/** Text the game's way: crisp, with a hard black drop shadow. */
	public static void text(Graphics2D g, String s, int x, int y)
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

	public static void text(Graphics2D g, String s, int x, int y, Color color)
	{
		g.setColor(color);
		text(g, s, x, y);
	}

	/**
	 * A material tile lined up with {@code r}'s corner, so a window's texture moves with the
	 * window when it's dragged instead of staying put on the screen. Flat colours pass through.
	 */
	private static Paint at(Paint material, Rectangle r)
	{
		if (!(material instanceof TexturePaint))
		{
			return material;
		}
		TexturePaint t = (TexturePaint) material;
		Rectangle anchor = t.getAnchorRect().getBounds();
		return new TexturePaint(t.getImage(), new Rectangle(r.x, r.y, anchor.width, anchor.height));
	}

	/** Text centred on {@code cx}, baseline {@code y}. */
	public static void centred(Graphics2D g, String s, int cx, int y, Color color)
	{
		g.setColor(color);
		text(g, s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
	}

	public static Paint stone(boolean dark)
	{
		return dark ? STONE_DARK : STONE;
	}

	/** Draws an image's border round {@code r}: corners as they are, edges stretched (its middle is left alone). */
	public static void nine(Graphics2D g, BufferedImage img, Rectangle r, int e)
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
	public static void bevel(Graphics2D g, Rectangle r, boolean raised)
	{
		if (oldSchool())
		{
			nine(g, raised ? RAISED : SUNK, r, 2);
			return;
		}
		ThemePalette p = palette;
		if (p.bevelLight != null)
		{
			// Light and dark edges inside a dark outline, the other way round when sunk.
			g.setColor(raised ? p.bevelLight : p.bevelDark);
			g.drawLine(r.x + 1, r.y + 1, r.x + r.width - 2, r.y + 1);
			g.drawLine(r.x + 1, r.y + 1, r.x + 1, r.y + r.height - 2);
			g.setColor(raised ? p.bevelDark : p.bevelLight);
			g.drawLine(r.x + 1, r.y + r.height - 2, r.x + r.width - 2, r.y + r.height - 2);
			g.drawLine(r.x + r.width - 2, r.y + 1, r.x + r.width - 2, r.y + r.height - 2);
		}
		g.setColor(raised ? p.edge : p.dividerDark);
		if (p.radius > 0)
		{
			g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, Math.min(p.radius, r.height / 2), Math.min(p.radius, r.height / 2));
		}
		else
		{
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		}
	}

	/** A stone button's face (the caller draws its label). {@code active}: pressed or chosen. */
	public static void button(Graphics2D g, Rectangle r, boolean hover, boolean active)
	{
		if (oldSchool())
		{
			g.setPaint(at(STONE, r));
			g.fillRect(r.x, r.y, r.width, r.height);
			g.setColor(active ? new Color(150, 40, 20, 110) : new Color(255, 255, 255, hover ? 28 : 0));
			g.fillRect(r.x, r.y, r.width, r.height);
			bevel(g, r, !active);
			return;
		}
		ThemePalette p = palette;
		Object aa = smooth(g);
		int rad = Math.min(p.radius, r.height / 2) * 2;
		g.setColor(active ? p.buttonActive : p.button);
		g.fillRoundRect(r.x, r.y, r.width, r.height, rad, rad);
		if (hover)
		{
			g.setColor(new Color(255, 255, 255, 26));
			g.fillRoundRect(r.x, r.y, r.width, r.height, rad, rad);
		}
		restore(g, aa);
		bevel(g, r, !active);
		if (active && p.glow)
		{
			g.setColor(p.frameOuter);
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		}
	}

	/**
	 * A progress bar filled to {@code fraction}: a dark groove with a soft green fill (or
	 * {@code color} when {@code tinted}, for a countdown). {@code glow} (0 to 1) rims it in yellow.
	 */
	public static void bar(Graphics2D g, int x, int y, int width, int height, float fraction, Color color, float glow, boolean tinted)
	{
		int fill = Math.round(width * Math.max(0, Math.min(1, fraction)));
		g.setColor(Color.BLACK);
		g.fillRect(x - 1, y - 1, width + 2, height + 2);
		g.setColor(oldSchool() ? new Color(40, 34, 27) : palette.field);
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

	/** Shorten text with "..." so it fits the width. */
	public static String fit(FontMetrics fm, String text, int width)
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

	static
	{
		// ChunkBlazer's own look until ChunkBlazer UI (if installed) applies the chosen Theme.
		setPalette(ThemePalette.original());
	}
}
