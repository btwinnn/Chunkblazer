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
