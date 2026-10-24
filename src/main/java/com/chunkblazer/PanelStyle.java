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
import java.awt.Graphics;
import java.awt.Insets;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.IconTextField;

/**
 * The side panel's look for the current Theme (Swing). Old School: stone behind everything,
 * framed sections as dark stone in a bevelled black frame, stone buttons, sunken fields and
 * the game's orange for grey text. The other themes the same way in their own colours.
 * ChunkBlazer (btwinnn's original) puts back the panel exactly as it was built: RuneLite's greys.
 *
 * <p>Each component's own colours and border are remembered the first time, so the panel can
 * be switched between themes any number of times.
 */
final class PanelStyle
{
	private static final Color OS_FIELD = new Color(30, 26, 21);
	private static final Color OS_ORANGE = new Color(255, 152, 31);
	static Color DARK_STONE = new Color(46, 40, 31);
	static Color FIELD = OS_FIELD;
	static Color ORANGE = OS_ORANGE;
	static final Color YELLOW = new Color(255, 255, 0);
	private static final String ORIGINAL = "chunkblazer.panelStyle.original";

	private PanelStyle()
	{
	}

	/** A bevelled edge in the theme's colours: dark outside, light top-left, dark bottom-right. */
	static final class StoneBorder extends AbstractBorder
	{
		private final Insets padding;

		StoneBorder(Insets padding)
		{
			this.padding = padding;
		}

		@Override
		public void paintBorder(Component c, Graphics g, int x, int y, int width, int height)
		{
			ThemePalette p = Skin.palette();
			g.setColor(p.theme == Theme.OLD_SCHOOL ? Color.BLACK : p.panelBorder);
			g.drawRect(x, y, width - 1, height - 1);
			Skin.bevel((java.awt.Graphics2D) g, new java.awt.Rectangle(x + 1, y + 1, width - 2, height - 2), true);
		}

		@Override
		public Insets getBorderInsets(Component c, Insets insets)
		{
			insets.set(3 + padding.top, 3 + padding.left, 3 + padding.bottom, 3 + padding.right);
			return insets;
		}
	}

	/** What a component looked like as built, before any theme. */
	private static final class Original
	{
		final Color background;
		final Color foreground;
		final Border border;
		final boolean opaque;

		Original(JComponent c)
		{
			background = c.getBackground();
			foreground = c.getForeground();
			border = c.getBorder();
			opaque = c.isOpaque();
		}
	}

	/** Restyle {@code root} and everything in it for the current theme. */
	static void apply(Container root)
	{
		ThemePalette p = Skin.palette();
		FIELD = p.theme == Theme.OLD_SCHOOL ? OS_FIELD : p.panelField;
		ORANGE = p.panelAccent;
		DARK_STONE = p.panelSection;
		walk(root, p);
		// The scroll pane round the panel shows below it.
		if (root.getParent() instanceof JViewport)
		{
			root.getParent().setBackground(p.panelBg);
		}
	}

	private static void walk(Container root, ThemePalette p)
	{
		if (root instanceof JComponent)
		{
			style((JComponent) root, p);
		}
		// RuneLite's search box colours its own text field; in ChunkBlazer, leave its insides to it.
		if (root instanceof IconTextField && p.theme == Theme.ORIGINAL)
		{
			return;
		}
		for (Component child : root.getComponents())
		{
			if (child instanceof Container)
			{
				walk((Container) child, p);
			}
		}
	}

	private static Original original(JComponent c)
	{
		Object o = c.getClientProperty(ORIGINAL);
		if (!(o instanceof Original))
		{
			o = new Original(c);
			c.putClientProperty(ORIGINAL, o);
		}
		return (Original) o;
	}

	private static void style(JComponent c, ThemePalette p)
	{
		Original o = original(c);
		if (p.theme == Theme.ORIGINAL)
		{
			restore(c, o, p);
			return;
		}
		if (c instanceof IconTextField)
		{
			c.setBackground(p.panelField);
			((IconTextField) c).setHoverBackgroundColor(p.panelField.brighter());
		}
		else if (c instanceof JPanel)
		{
			panel((JPanel) c, o, p);
		}
		else if (c instanceof JComboBox || c instanceof JTextField)
		{
			if (c instanceof JComboBox)
			{
				comboArrow((JComboBox<?>) c, p);
			}
			// A plain dark edge, like the game's boxes.
			Color edge = p.theme == Theme.OLD_SCHOOL ? Color.BLACK : p.panelBorder;
			c.setBorder(c instanceof JTextField ? new CompoundBorder(new LineBorder(edge), new EmptyBorder(2, 4, 2, 4))
				: new LineBorder(edge));
			c.setBackground(p.panelField);
			c.setForeground(c instanceof JTextField ? Color.WHITE : p.panelAccent);
			if (c instanceof JTextField)
			{
				((JTextField) c).setCaretColor(Color.WHITE);
			}
		}
		else if (c instanceof JRadioButton || c instanceof JList)
		{
			c.setOpaque(false);
			c.setForeground(Color.WHITE);
		}
		else if (c instanceof JButton)
		{
			JButton b = (JButton) c;
			Color fg = o.foreground;
			if (b.isContentAreaFilled())
			{
				b.setBackground(p.panelButton);
				b.setBorder(new StoneBorder(new Insets(3, 8, 3, 8)));
				b.setFocusPainted(false);
			}
			// Text links stay red when they warn; everything else takes the accent.
			b.setForeground(fg != null && fg.getRed() > fg.getGreen() + 30 ? new Color(255, 90, 70) : p.panelAccent);
		}
		else if (c instanceof JLabel || c instanceof JTextArea)
		{
			c.setForeground(text(o.foreground, p));
			if (o.opaque && OS_FIELD.equals(o.background))
			{
				c.setBackground(p.panelField);
			}
		}
	}

	/** ChunkBlazer: everything back as it was built (RuneLite's greys), the boxes this window adds in grey. */
	private static void restore(JComponent c, Original o, ThemePalette p)
	{
		if (c instanceof IconTextField)
		{
			// As RuneLite's own panels have it (built without a colour, it would show white).
			IconTextField field = (IconTextField) c;
			field.setBorder(o.border);
			field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			field.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
			restoreField(field);
			return;
		}
		c.setBorder(o.border);
		c.setOpaque(o.opaque);
		c.setBackground(OS_FIELD.equals(o.background) ? p.panelField : o.background);
		c.setForeground(OS_ORANGE.equals(o.foreground) ? p.panelAccent : o.foreground);
		if (c instanceof JComboBox)
		{
			// Built without colours, a combo would be the look-and-feel's light grey.
			comboArrow((JComboBox<?>) c, p);
			c.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			c.setForeground(Color.WHITE);
			c.setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		}
		else if (c instanceof JButton && ((JButton) c).isContentAreaFilled())
		{
			// The same for buttons: RuneLite's dark grey with a thin grey edge, like its own panels.
			JButton b = (JButton) c;
			b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			b.setForeground(o.foreground != null && o.foreground.getRed() > o.foreground.getGreen() + 30
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
				Object orig = text.getClientProperty(ORIGINAL);
				if (orig instanceof Original)
				{
					text.setBorder(((Original) orig).border);
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

	/** The plain combo, with a themed arrow button, drops the look-and-feel's blue ring. */
	private static void comboArrow(JComboBox<?> box, ThemePalette p)
	{
		box.setUI(new BasicComboBoxUI()
		{
			@Override
			protected JButton createArrowButton()
			{
				return new BasicArrowButton(BasicArrowButton.SOUTH, p.panelButton, Color.BLACK, p.panelAccent,
					p.panelButton.brighter());
			}
		});
	}

	/** Plain backing panels show the material; framed sections become its darker shade in a bevelled frame. */
	private static void panel(JPanel c, Original o, ThemePalette p)
	{
		Color bg = o.background;
		Border border = o.border;
		Border outer = border instanceof CompoundBorder ? ((CompoundBorder) border).getOutsideBorder() : border;
		Border inner = border instanceof CompoundBorder ? ((CompoundBorder) border).getInsideBorder() : null;
		if (outer instanceof LineBorder)
		{
			c.setBorder(new StoneBorder(inner instanceof EmptyBorder ? ((EmptyBorder) inner).getBorderInsets() : new Insets(4, 4, 4, 4)));
			c.setBackground(p.panelSection);
			c.setOpaque(true);
		}
		else if (bg == null || bg.equals(ColorScheme.DARK_GRAY_COLOR) || bg.getAlpha() < 255)
		{
			c.setOpaque(false);
		}
		else if (bg.equals(ColorScheme.DARKER_GRAY_COLOR))
		{
			c.setBackground(p.panelSection);
		}
		else if (OS_FIELD.equals(bg))
		{
			c.setBackground(p.panelField);
		}
	}

	/** Grey text becomes the theme's accent (the game's orange in Old School); colours that mean something stay. */
	private static Color text(Color c, ThemePalette p)
	{
		if (c == null || OS_ORANGE.equals(c))
		{
			return p.panelAccent;
		}
		int spread = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue())) - Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
		return spread < 24 && c.getRed() < 235 && c.getRed() > 90 ? p.panelAccent : c;
	}
}
