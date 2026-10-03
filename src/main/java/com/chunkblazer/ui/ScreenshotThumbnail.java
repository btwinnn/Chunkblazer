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

package com.chunkblazer.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

/**
 * A completed task's screenshot thumbnail that only holds pixels while it's
 * near the visible part of the list. The panel calls {@link #load()} and
 * {@link #unload()} as the card scrolls in and out of range, so the memory
 * used is set by how many cards fit on screen, not by how many tasks have a
 * screenshot.
 *
 * <p>The label keeps a fixed size whether loaded or not, so filling in an
 * image never shifts the cards below it (which would move the scroll position
 * and trigger more loads).
 */
public class ScreenshotThumbnail extends JLabel
{
	/** Fetches a thumbnail scaled to fit width x height, calling back on the EDT. */
	@FunctionalInterface
	public interface Loader
	{
		void load(File file, int width, int height, Consumer<BufferedImage> callback);
	}

	private static final String PLACEHOLDER = "Screenshot";

	private final File file;
	private final int thumbWidth;
	private final int thumbHeight;
	private final Loader loader;
	private boolean wanted;

	public ScreenshotThumbnail(File file, int width, Loader loader, Font font)
	{
		super(PLACEHOLDER, SwingConstants.CENTER);
		this.file = file;
		this.thumbWidth = width;
		// 16:9 box; screenshots with the client frame letterbox inside it.
		this.thumbHeight = width * 9 / 16;
		this.loader = loader;

		Dimension size = new Dimension(thumbWidth + 2, thumbHeight + 2);
		setPreferredSize(size);
		setMinimumSize(size);
		setMaximumSize(size);
		setFont(font);
		setForeground(Color.GRAY);
		setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60)));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setToolTipText("Open screenshot");
	}

	public File getFile()
	{
		return file;
	}

	public void load()
	{
		if (wanted)
		{
			return;
		}
		wanted = true;
		loader.load(file, thumbWidth, thumbHeight, image ->
		{
			// Scrolled away (or the card was rebuilt) before the read finished.
			if (wanted)
			{
				setText(null);
				setIcon(new ImageIcon(image));
			}
		});
	}

	public void unload()
	{
		if (!wanted)
		{
			return;
		}
		wanted = false;
		setIcon(null);
		setText(PLACEHOLDER);
	}
}
