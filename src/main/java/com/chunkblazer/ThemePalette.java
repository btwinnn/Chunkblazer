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
import java.awt.image.BufferedImage;

/**
 * The colours and materials of one {@link Theme}: what Skin paints windows, boxes, buttons
 * and bars with, and the text colours the overlays use.
 * Old School's materials are the stone images in {@code ui/}; the other themes' are
 * small tiles. Plain AWT only. ChunkBlazer itself only knows the
 * ChunkBlazer (original) palette; ChunkBlazer UI draws its own themes.
 */
public final class ThemePalette
{
	public final Theme theme;

	// Windows and boxes: the material (a tile, or flat colour when null) and its darker header.
	public Color base;
	public Color baseDark;
	public BufferedImage tile;
	public BufferedImage tileDark;
	/** Corner radius of windows and boxes (0: square). */
	public int radius;
	/** The frame line, and its width in pixels. */
	public Color frameOuter;
	public int frameWidth = 1;
	public boolean glow;

	// Buttons, bevels, grooves and fields.
	public Color button;
	public Color buttonActive;
	public Color edge;
	public Color bevelLight;
	public Color bevelDark;
	public Color dividerDark;
	public Color dividerLight;
	public Color field;
	public Color barFill;

	// Text and highlights the overlays use.
	public Color title;
	public Color subtext;
	public Color label;
	public Color dark;
	public Color tracked;
	public Color sidebar;
	public Color itemSlot;
	public Color rowAlt;

	public ThemePalette(Theme theme)
	{
		this.theme = theme;
	}

	/** btwinnn's original: flat dark brown, thin bronze lines, soft corners, orange. */
	public static ThemePalette original()
	{
		ThemePalette p = new ThemePalette(Theme.ORIGINAL);
			// btwinnn's original: flat dark brown, thin bronze lines, soft corners, orange.
			p.base = new Color(38, 33, 27, 242);
			p.baseDark = new Color(28, 24, 20, 250);
			p.radius = 7;
			p.frameOuter = new Color(110, 95, 65);
			p.button = new Color(48, 42, 34);
			p.buttonActive = new Color(70, 60, 45);
			p.edge = new Color(110, 95, 65);
			p.dividerDark = new Color(20, 18, 15);
			p.dividerLight = new Color(70, 60, 45);
			p.field = new Color(20, 18, 15);
			p.barFill = new Color(255, 140, 0);
			p.title = new Color(255, 152, 31);
			p.subtext = new Color(170, 160, 140);
			p.label = new Color(150, 140, 120);
			p.dark = new Color(20, 18, 15);
			p.tracked = new Color(255, 140, 0, 45);
			p.sidebar = new Color(0, 0, 0, 40);
			p.itemSlot = new Color(70, 60, 45);
			p.rowAlt = new Color(255, 255, 255, 10);
		return p;
	}
}
