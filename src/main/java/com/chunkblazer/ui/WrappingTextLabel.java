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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.JTextArea;

/**
 * Word-wrapping read-only label for the ChunkBlazer side panel.
 *
 * <p>Why this exists: the side panel originally used
 * {@code <html><body style='width:Npx; word-wrap:break-word'>...</body></html>}
 * inside JLabels. Swing's HTML renderer fixes the JLabel's preferred height
 * based on the requested CSS width, but BoxLayout often ends up giving the
 * label less actual width than the CSS hinted (vertical scrollbar, parent
 * insets, body default margins). The HTML then wraps onto more lines than
 * the precomputed preferred height covers, and the extra lines get clipped
 * silently — sometimes whole words like "in 10" vanish from the middle of
 * a name. Reducing the CSS width only changes which line gets eaten.
 *
 * <p>{@code JTextArea} with {@code setLineWrap(true) + setWrapStyleWord(true)}
 * wraps at the layout-time width and reports its true wrapped height back to
 * BoxLayout, so the visible component always matches what's actually drawn.
 *
 * <p><b>Maintenance rule:</b> if side-panel text wrapping needs adjustment,
 * change it here. Do not reintroduce {@code JLabel + <html>} for task names.
 */
public final class WrappingTextLabel extends JTextArea
{
	/**
	 * @param text the text to display
	 * @param font font to render with
	 * @param foreground text color
	 * @param maxWidth hard cap on width — BoxLayout will not stretch the
	 *                 component beyond this even if siblings are narrower.
	 *                 Required, otherwise BoxLayout will let a long single
	 *                 line make the component wider than the panel.
	 */
	public WrappingTextLabel(String text, Font font, Color foreground, int maxWidth)
	{
		super(text);
		setFont(font);
		setForeground(foreground);
		setLineWrap(true);
		setWrapStyleWord(true);
		setEditable(false);
		setFocusable(false);
		setOpaque(false);
		setBorder(null);
		setHighlighter(null);
		setAlignmentX(Component.LEFT_ALIGNMENT);
		setMaximumSize(new Dimension(maxWidth, Integer.MAX_VALUE));
	}
}
