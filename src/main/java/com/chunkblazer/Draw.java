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
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** Text and box drawing shared by the overlays. */
final class Draw
{
	private Draw()
	{
	}

	/** Baseline that vertically centres a line of text in a band starting at y, h tall. */
	static int textY(FontMetrics fm, int y, int h)
	{
		return y + (h + fm.getAscent()) / 2 - 2;
	}

	/** Text centred in the area, in the current colour. */
	static void centered(Graphics2D g, String text, Rectangle area)
	{
		FontMetrics fm = g.getFontMetrics();
		g.drawString(text, area.x + (area.width - fm.stringWidth(text)) / 2, textY(fm, area.y, area.height));
	}

	/** A one-pixel drop shadow (down and right) under the text. Leaves the colour set to colour. */
	static void shadow(Graphics2D g, String text, int x, int y, Color shadow, Color colour)
	{
		g.setColor(shadow);
		g.drawString(text, x + 1, y + 1);
		g.setColor(colour);
		g.drawString(text, x, y);
	}

	/** A filled box with a border drawn on its full width and height. Leaves the colour set to border. */
	static void box(Graphics2D g, int x, int y, int w, int h, Color fill, Color border)
	{
		g.setColor(fill);
		g.fillRect(x, y, w, h);
		g.setColor(border);
		g.drawRect(x, y, w, h);
	}

	/** Shorten text with "..." so it fits the width ("..." alone if nothing else fits). */
	static String fit(FontMetrics fm, String text, int width)
	{
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

	/** Greedy word wrap: split text into lines that fit the width, breaking between words. */
	static List<String> wrap(FontMetrics fm, String text, int width)
	{
		List<String> lines = new ArrayList<>();
		if (text == null)
		{
			return lines;
		}
		String line = "";
		for (String word : text.split(" "))
		{
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (fm.stringWidth(candidate) > width && !line.isEmpty())
			{
				lines.add(line);
				line = word;
			}
			else
			{
				line = candidate;
			}
		}
		if (!line.isEmpty())
		{
			lines.add(line);
		}
		return lines;
	}
}
