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
import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.IconTextField;

/**
 * The side panel's own look (Swing): everything as it was built, in RuneLite's greys. With
 * ChunkBlazer UI on, its theme styles the panel instead (see SidePaint); this puts it back.
 *
 * <p>Each component's colours, border and opacity as built are kept on it the first time
 * ({@link #ORIGINAL}), so it can be switched between looks any number of times.
 */
final class PanelStyle
{
	static final Color FIELD = new Color(30, 26, 21);
	static final Color ORANGE = new Color(255, 152, 31);
	static final Color YELLOW = new Color(255, 255, 0);
	private static final Color ACCENT = new Color(255, 152, 0);
	private static final Color BUTTON = new Color(55, 55, 55);
	/** Client property: {background, foreground, border, opaque} as built. ChunkBlazer UI reads it too. */
	static final String ORIGINAL = "chunkblazer.panelStyle.original";

	private PanelStyle()
	{
	}

	/** Give {@code root} and everything in it ChunkBlazer UI's theme, or the panel's own look. */
	static void apply(Container root)
	{
		walk(root, false);
		if (SidePaint.draw("restyle", root))
		{
			return;
		}
		walk(root, true);
		// The scroll pane round the panel shows below it.
		if (root.getParent() instanceof JViewport)
		{
			root.getParent().setBackground(new Color(40, 40, 40));
		}
	}

	/** Remember each component as built (the first time), and with {@code restore} put it back. */
	private static void walk(Container root, boolean restore)
	{
		if (root instanceof JComponent)
		{
			JComponent c = (JComponent) root;
			if (!(c.getClientProperty(ORIGINAL) instanceof Object[]))
			{
				c.putClientProperty(ORIGINAL, new Object[]{c.getBackground(), c.getForeground(), c.getBorder(), c.isOpaque()});
			}
			if (restore)
			{
				restore(c, (Object[]) c.getClientProperty(ORIGINAL));
			}
		}
		// RuneLite's search box colours its own text field.
		if (root instanceof IconTextField && restore)
		{
			return;
		}
		for (Component child : root.getComponents())
		{
			if (child instanceof Container)
			{
				walk((Container) child, restore);
			}
		}
	}

	/** Everything back as it was built (RuneLite's greys), the boxes this panel adds in grey. */
	private static void restore(JComponent c, Object[] o)
	{
		Color background = (Color) o[0];
		Color foreground = (Color) o[1];
		if (c instanceof IconTextField)
		{
			// As RuneLite's own panels have it (built without a colour, it would show white).
			IconTextField field = (IconTextField) c;
			field.setBorder((Border) o[2]);
			field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			field.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
			restoreField(field);
			return;
		}
		c.setBorder((Border) o[2]);
		c.setOpaque((Boolean) o[3]);
		c.setBackground(FIELD.equals(background) ? ColorScheme.DARKER_GRAY_COLOR : background);
		c.setForeground(ORANGE.equals(foreground) ? ACCENT : foreground);
		if (c instanceof JComboBox)
		{
			// Built without colours, a combo would be the look-and-feel's light grey.
			((JComboBox<?>) c).setUI(new BasicComboBoxUI()
			{
				@Override
				protected JButton createArrowButton()
				{
					return new BasicArrowButton(BasicArrowButton.SOUTH, BUTTON, Color.BLACK, ACCENT, BUTTON.brighter());
				}
			});
			c.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			c.setForeground(Color.WHITE);
			c.setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		}
		else if (c instanceof JButton && ((JButton) c).isContentAreaFilled())
		{
			// The same for buttons: RuneLite's dark grey with a thin grey edge, like its own panels.
			JButton b = (JButton) c;
			b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			b.setForeground(foreground != null && foreground.getRed() > foreground.getGreen() + 30
				? new Color(255, 90, 70) : ColorScheme.LIGHT_GRAY_COLOR);
			b.setBorder(new CompoundBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR), new EmptyBorder(3, 8, 3, 8)));
			b.setFocusPainted(false);
		}
	}

	/**
	 * The search box's insides as RuneLite has them: its text field sits inside another panel,
	 * so this goes all the way down. White text and caret, or the typing can't be seen.
	 */
	private static void restoreField(Container box)
	{
		for (Component inner : box.getComponents())
		{
			if (inner instanceof JTextField)
			{
				JTextField text = (JTextField) inner;
				Object o = text.getClientProperty(ORIGINAL);
				if (o instanceof Object[])
				{
					text.setBorder((Border) ((Object[]) o)[2]);
				}
				text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				text.setForeground(Color.WHITE);
				text.setCaretColor(Color.WHITE);
				text.setSelectedTextColor(Color.WHITE);
			}
			else if (inner instanceof Container)
			{
				if (inner instanceof JPanel)
				{
					inner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
				restoreField((Container) inner);
			}
		}
	}
}
