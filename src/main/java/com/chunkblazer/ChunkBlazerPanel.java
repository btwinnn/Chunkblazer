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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import com.chunkblazer.ui.WrappingTextLabel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;



public class ChunkBlazerPanel extends PluginPanel
{
	private static final int PANEL_WIDTH = 225; // Standard RuneLite panel width
	private static final int CONTENT_WIDTH = PANEL_WIDTH - 24; // Width for content inside panels (accounting for borders/padding)
	// ChunkBlazer theme accent, flame orange.
	private static final Color FLAME = new Color(255, 152, 0);
	private static final int TASK_TEXT_WRAP_WIDTH = CONTENT_WIDTH - 25;
	private static final Font SMALL = FontManager.getRunescapeSmallFont();
	private static final Font BOLD = FontManager.getRunescapeBoldFont();
	private static final String WRAP = "<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>";

	private ChunkBlazerPlugin plugin;

	private JPanel modeSelectionPanel;
	private JPanel overlayHint;
	// Search, filters and the task list, under the framed box (which keeps the hint and stats).
	private JPanel taskArea;
	private JPanel loggedOutPanel;
	// Data-sync header: the "Progress synced" notice (sync ON) or the first-run
	// "Enable Sync" prompt (sync OFF, the default), toggled in updatePanel.
	private JPanel dataNoticeRow;
	private JButton showKeyButton;
	private JButton resetAccountButton;
	private JButton historyButton;
	private JPanel syncPromptPanel;
	private WrappingTextLabel syncChoiceHint;
	private JButton playOfflineButton;
	private JLabel regionLabel;
	private JLabel modeLabel;
	private JRadioButton casualRadio;
	private JRadioButton nuzlockeRadio;

	// Verification banner, shown until the account is verified via the in-game chat handshake.
	private JPanel verificationPanel;
	// On Tutorial Island, in place of the account setup: that waits for the mainland.
	private JPanel tutorialPanel;
	private JLabel verificationCodeLabel;



	/** Give the panel its look (PanelStyle, or ChunkBlazer UI's theme); once its task list is in, and on a theme change. */
	public void applyTheme()
	{
		SwingUtilities.invokeLater(() ->
		{
			PanelStyle.apply(this);
			revalidate();
			repaint();
		});
	}

	@Override
	protected void paintComponent(java.awt.Graphics g)
	{
		java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
		if (!SidePaint.draw("background", g2, getWidth(), getHeight()))
		{
			g2.setColor(new Color(40, 40, 40));
			g2.fillRect(0, 0, getWidth(), getHeight());
		}
		g2.dispose();
	}

	public void init(ChunkBlazerPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel mainContent = createMainPanel();
		add(mainContent, BorderLayout.CENTER);
	}

	/**
	 * The side panel is account setup only: sync, verification, game mode and the sync
	 * key. Tasks live in the in-game task window (the Pts orb), and the full task history
	 * on the player's chunkblazer.com profile.
	 */
	private JPanel createMainPanel()
	{
		JPanel mainPanel = boxPanel(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

		JPanel header = createHeaderSection();
		header.setAlignmentX(LEFT_ALIGNMENT);
		mainPanel.add(header);
		mainPanel.add(Box.createVerticalStrut(4));
		tutorialPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		tutorialPanel.setAlignmentX(LEFT_ALIGNMENT);
		frame(tutorialPanel, FLAME, 5, 6);
		tutorialPanel.add(note("ChunkBlazer tutorial", FLAME));
		tutorialPanel.add(note("You're on Tutorial Island: follow the tutorial in game, with practice chunks "
			+ "and tasks worth 0 points.", Color.WHITE));
		tutorialPanel.add(note("Server Sync and your game mode are set up once you reach Lumbridge, "
			+ "where you can type in chat.", Color.LIGHT_GRAY));
		tutorialPanel.setVisible(false);
		mainPanel.add(tutorialPanel);
		dataNoticeRow = createDataNoticeRow();
		mainPanel.add(dataNoticeRow);
		syncPromptPanel = createSyncPromptSection();
		syncPromptPanel.setAlignmentX(LEFT_ALIGNMENT);
		mainPanel.add(syncPromptPanel);
		boolean syncOn = plugin != null && plugin.isServerSyncEnabled();
		dataNoticeRow.setVisible(syncOn);
		syncPromptPanel.setVisible(!syncOn);
		mainPanel.add(Box.createVerticalStrut(8));

		loggedOutPanel = createLoggedOutSection();
		loggedOutPanel.setVisible(false);
		mainPanel.add(loggedOutPanel);

		verificationPanel = createVerificationSection();
		verificationPanel.setVisible(false);
		mainPanel.add(verificationPanel);
		mainPanel.add(Box.createVerticalStrut(8));

		modeSelectionPanel = createModeSelectionSection();
		mainPanel.add(modeSelectionPanel);
		// Points, chunks and tasks all live in the in-game task window now.
		overlayHint = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		overlayHint.setAlignmentX(LEFT_ALIGNMENT);
		frame(overlayHint, FLAME, 5, 6);
		overlayHint.add(new WrappingTextLabel("Click a task to track it, right-click to star it, "
			+ "click a quest for its requirements, click a heading to fold it.", SMALL, FLAME, CONTENT_WIDTH - 16));
		overlayHint.setVisible(false);
		mainPanel.add(overlayHint);
		taskArea = boxPanel(ColorScheme.DARK_GRAY_COLOR);
		taskArea.setAlignmentX(LEFT_ALIGNMENT);
		taskArea.setVisible(false);
		mainPanel.add(taskArea);
		mainPanel.add(Box.createVerticalStrut(8));

		historyButton = linkButton("View my task history", new Color(140, 140, 140),
			"Every task you've completed, newest first, on chunkblazer.com");
		historyButton.addActionListener(e -> openLink("https://chunkblazer.com/player.html?rsn="
			+ java.net.URLEncoder.encode(String.valueOf(plugin.getPlayerName()), java.nio.charset.StandardCharsets.UTF_8)));
		mainPanel.add(historyButton);
		showKeyButton = linkButton("Show my sync key", new Color(140, 140, 140),
			"Reveal your account sync key to move this account to another computer");
		showKeyButton.addActionListener(e -> showSyncKeyBackup());
		mainPanel.add(showKeyButton);
		// Repair link for a contaminated account: clears only THIS account's local data
		// so it restores fresh from the server.
		resetAccountButton = linkButton("Reset this account's sync data", new Color(170, 110, 110),
			"Wipe this account's local ChunkBlazer data and restore it fresh from the server. "
			+ "Your server progress is not touched.");
		resetAccountButton.addActionListener(e -> confirmResetAccountData());
		mainPanel.add(resetAccountButton);
		mainPanel.add(Box.createVerticalStrut(6));

		JPanel socials = styledPanel(new FlowLayout(FlowLayout.LEFT, 6, 0), ColorScheme.DARK_GRAY_COLOR);
		socials.setAlignmentX(LEFT_ALIGNMENT);
		socials.add(iconLink("discord_icon.png", "Join the ChunkBlazer Discord", "https://discord.com/invite/2AmVDYBBE4"));
		socials.add(iconLink("patreon_icon.png", "Support ChunkBlazer on Patreon", "https://www.patreon.com/cw/Crukken"));
		mainPanel.add(socials);

		mainPanel.add(Box.createVerticalGlue());
		return mainPanel;
	}

	/** A borderless icon button that opens a link. */
	private JButton iconLink(String icon, String tooltip, String url)
	{
		JButton button = linkButton("", Color.WHITE, tooltip);
		button.setIcon(new ImageIcon(ImageUtil.loadImageResource(ChunkBlazerPanel.class, icon)));
		button.setBorder(new EmptyBorder(0, 0, 0, 0));
		button.addActionListener(e -> openLink(url));
		return button;
	}

	/** Run {@code action} when the label is clicked (with a hand cursor). */
	private static void onClick(JLabel label, Runnable action)
	{
		label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		label.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				action.run();
			}
		});
	}

	/** A section's frame: a line in {@code edge} with the padding inside it. */
	private static void frame(JComponent c, Color edge, int v, int h)
	{
		c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(edge), new EmptyBorder(v, h, v, h)));
	}

	/** Wrapped small text for a section. */
	private static WrappingTextLabel note(String text, Color color)
	{
		WrappingTextLabel label = new WrappingTextLabel(text, SMALL, color, TASK_TEXT_WRAP_WIDTH);
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	/** A subtle text-only link button. */
	private JButton linkButton(String text, Color color, String tooltip)
	{
		JButton button = new JButton(text);
		button.setFont(SMALL);
		button.setForeground(color);
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setFocusPainted(false);
		button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setToolTipText(tooltip);
		return button;
	}

	/**
	 * Thin flame-orange divider drawn under a section title — the consistent accent
	 * across every section header. Full content width, 2px tall.
	 */
	private JPanel sectionDivider()
	{
		JPanel d = new JPanel();
		d.setBackground(FLAME);
		d.setAlignmentX(LEFT_ALIGNMENT);
		Dimension sz = new Dimension(CONTENT_WIDTH, 2);
		d.setPreferredSize(sz);
		d.setMinimumSize(sz);
		d.setMaximumSize(sz);
		return d;
	}

	/**
	 * Create a JLabel with the given font and colour. Does NOT set alignment or
	 * add it anywhere — for labels added with a layout constraint, stored in a
	 * field, or needing extra config before they go into their parent.
	 */
	private JLabel styledLabel(String text, Font font, Color fg)
	{
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(fg);
		return label;
	}

	/**
	 * Create a left-aligned JLabel (font + colour + LEFT_ALIGNMENT), add it to
	 * {@code parent}, and return it — the common BoxLayout section-label pattern.
	 */
	private JLabel addLabel(JPanel parent, String text, Font font, Color fg)
	{
		JLabel label = styledLabel(text, font, fg);
		label.setAlignmentX(LEFT_ALIGNMENT);
		parent.add(label);
		return label;
	}

	/**
	 * Create a JPanel with the given layout manager and background colour.
	 */
	private JPanel styledPanel(java.awt.LayoutManager layout, Color bg)
	{
		JPanel panel = new JPanel(layout);
		panel.setBackground(bg);
		return panel;
	}

	/**
	 * Create a vertical (Y_AXIS BoxLayout) JPanel with the given background.
	 */
	private JPanel boxPanel(Color bg)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(bg);
		return panel;
	}


	/**
	 * Build the "Verify Your Account" banner. The big code label is filled in
	 * at runtime by {@link #showVerificationPrompt(String)}.
	 */
	private JPanel createVerificationSection()
	{
		JPanel panel = boxPanel(new Color(60, 45, 18)); // dark amber
		panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(230, 170, 50), 2), new EmptyBorder(8, 8, 8, 8)));

		addLabel(panel, "Verify Your Account", BOLD, new Color(255, 190, 60));
		panel.add(Box.createVerticalStrut(5));

		WrappingTextLabel body = new WrappingTextLabel(
			"Type this code in public chat and hit Enter to verify your ChunkBlazer account:",
			SMALL,
			Color.WHITE,
			CONTENT_WIDTH - 4);
		panel.add(body);
		panel.add(Box.createVerticalStrut(6));

		verificationCodeLabel = addLabel(panel, " ",
			BOLD.deriveFont(24f), new Color(120, 230, 120));

		return panel;
	}

	/**
	 * Show the verification banner with the given code. Safe to call from any
	 * thread — API callbacks run off the EDT.
	 */
	public void showVerificationPrompt(String code)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (verificationPanel == null)
			{
				return;
			}
			verificationCodeLabel.setText(code);
			verificationPanel.setVisible(true);
			revalidate();
			repaint();
		});
	}

	/**
	 * Hide the verification banner once the account is verified. Thread-safe.
	 */
	public void hideVerificationPrompt()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (verificationPanel == null)
			{
				return;
			}
			verificationPanel.setVisible(false);
			revalidate();
			repaint();
		});
	}

	private void openLink(String url)
	{
		// RuneLite's LinkBrowser is the Hub-blessed way to open a url — it handles
		// the platform differences and avoids java.awt.Desktop (which the Plugin
		// Hub reviewer flags).
		LinkBrowser.browse(url);
	}

	/**
	 * Compact, always-visible data notice under the header: tells players their
	 * progress is synced to chunkblazer.com and links to the full data-use
	 * explanation. This is the in-plugin half of the data disclosure (the config
	 * "Enable Server Sync" description carries the other half).
	 */
	private JPanel createDataNoticeRow()
	{
		JPanel row = styledPanel(new FlowLayout(FlowLayout.CENTER, 3, 0), ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(PANEL_WIDTH, 16));

		row.add(styledLabel("Progress synced to", SMALL, ColorScheme.LIGHT_GRAY_COLOR));

		JLabel site = styledLabel("chunkblazer.com", SMALL, new Color(255, 152, 0));
		site.setToolTipText("Track your account progress at chunkblazer.com");
		onClick(site, () -> openLink("https://chunkblazer.com"));
		row.add(site);

		// ASCII "(?)" — Runescape font lacks a circled-i glyph
		JLabel info = styledLabel("(?)", SMALL, ColorScheme.LIGHT_GRAY_COLOR);
		info.setToolTipText("How your data is used");
		onClick(info, () -> showDataUseDialog());
		row.add(info);

		return row;
	}

	/**
	 * Show the account's sync key on request, in a selectable field the player copies themselves
	 * (no plugin clipboard access). Reads it from the authoritative per-account store, so it is
	 * reliable even right after a settings Reset (which clears the masked settings field but not
	 * the stored key). For moving an account to another computer.
	 */
	private void showSyncKeyBackup()
	{
		String key = plugin.getSyncRecoveryKey();
		if (key == null || key.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"No sync key yet. Turn on Server Sync and log in once, and your key is created automatically.",
				"Sync key", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		// Gate the reveal behind an explicit confirmation. The key is a full account
		// credential and RuneLite is often streamed or screen-shared, so we never put
		// it on screen until the player says so.
		int confirm = JOptionPane.showConfirmDialog(this,
			"This will reveal your account's Sync Key on screen. Anyone who can see your "
				+ "screen, including a stream or screen share, will be able to read it. "
				+ "Are you sure you want to show it?",
			"Reveal sync key?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}
		JTextField keyField = new JTextField(key);
		keyField.setEditable(false);
		keyField.setCaretPosition(0);
		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.add(new JLabel("<html><body style='width:260px'>Select the key below and copy it "
			+ "(Ctrl+C), then save it somewhere safe like a password manager. To sync this account "
			+ "on another computer, "
			+ "paste it into the \"Sync recovery key\" setting there.<br><br><b>Anyone with this key "
			+ "can access your account. Do not share it.</b></body></html>"), BorderLayout.NORTH);
		content.add(keyField, BorderLayout.CENTER);
		JOptionPane.showMessageDialog(this, content, "Your account sync key", JOptionPane.WARNING_MESSAGE);
	}

	/**
	 * Confirm-gated repair for a contaminated account. Clears only this account's local
	 * ChunkBlazer data (never the server record) so it restores fresh from the server on the
	 * next fresh start. Used when an account is stuck on the wrong mode or showing another
	 * account's progress after the cross-account key leak. A RuneLite profile switch does not
	 * help, because the per-account data lives in the shared RSProfile store.
	 */
	private void confirmResetAccountData()
	{
		String blocked = plugin.resetBlockedReason(System.currentTimeMillis());
		if (blocked != null)
		{
			JOptionPane.showMessageDialog(this, "<html><body style='width:270px'>" + blocked + "</body></html>",
				"Can't reset right now", JOptionPane.WARNING_MESSAGE);
			return;
		}
		int confirm = JOptionPane.showConfirmDialog(this,
			"<html><body style='width:270px'>This erases this account's ChunkBlazer progress on THIS "
				+ "computer (mode, tasks, points, chunks) and reloads it from the server the next time "
				+ "you log in. Your server progress is not touched.<br><br>Use this only if this account "
				+ "is showing the wrong mode or another account's progress. After it finishes, restart "
				+ "RuneLite and log back in.<br><br>Continue?</body></html>",
			"Reset this account's local data?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}
		plugin.resetAccountLocalData();
		JOptionPane.showMessageDialog(this,
			"Local data cleared. Restart RuneLite and log back in to restore this account from the "
				+ "server. If sync does not come back on its own, paste this account's key into the "
				+ "\"Sync recovery key\" setting.",
			"Done", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * First-run prompt shown while server sync is OFF (the default). Spells out what
	 * turning it on gains — cross-device saves, leaderboards, player discovery, and
	 * Competitive eligibility — with a one-click Enable button and the same data-use
	 * link as the synced notice. Hidden once sync is on (the compact notice takes
	 * over). See ChunkBlazerConfig#apiEnabled.
	 */
	private JPanel createSyncPromptSection()
	{
		JPanel panel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		frame(panel, FLAME, 6, 6);
		panel.setAlignmentX(LEFT_ALIGNMENT);

		addLabel(panel, "Server sync is off", BOLD, FLAME);
		panel.add(Box.createVerticalStrut(3));

		WrappingTextLabel body = note("Turn on sync to save your progress across devices, show on the leaderboard, see other players, and play Competitive mode. Nothing is sent until you enable it. Your current progress will sync with the server when you do.", ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(body);

		panel.add(Box.createVerticalStrut(3));
		WrappingTextLabel comp = new WrappingTextLabel(
			"Competitive mode requires a new ironman account (Group included) with combat level 9 or lower and no skill above level 3.",
			SMALL, new Color(255, 190, 60), TASK_TEXT_WRAP_WIDTH);
		comp.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(comp);

		// Shown until the player picks: a new device for an existing account must not
		// deal Lumbridge before it knows whether the server already has a roll.
		panel.add(Box.createVerticalStrut(3));
		WrappingTextLabel hint = note("Your starting tasks are dealt once you choose Enable Sync or Play offline.", ColorScheme.LIGHT_GRAY_COLOR);
		syncChoiceHint = hint;
		panel.add(hint);

		panel.add(Box.createVerticalStrut(6));
		JButton enable = new JButton("Enable Sync");
		enable.setAlignmentX(LEFT_ALIGNMENT);
		enable.setFocusPainted(false);
		enable.setToolTipText("Sync your progress to chunkblazer.com");
		// Same confirmation RuneLite shows for the config toggle's warning.
		enable.addActionListener(e ->
		{
			int ok = JOptionPane.showConfirmDialog(this, ChunkBlazerConfig.SERVER_SYNC_WARNING,
				"Enable Server Sync", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (ok == JOptionPane.OK_OPTION)
			{
				plugin.enableServerSync();
			}
		});
		panel.add(enable);

		panel.add(Box.createVerticalStrut(4));
		playOfflineButton = new JButton("Play offline");
		playOfflineButton.setAlignmentX(LEFT_ALIGNMENT);
		playOfflineButton.setFocusPainted(false);
		playOfflineButton.setToolTipText("Keep progress on this computer only. You can enable sync later.");
		playOfflineButton.addActionListener(e -> plugin.choosePlayOffline());
		panel.add(playOfflineButton);

		panel.add(Box.createVerticalStrut(4));
		JLabel info = styledLabel("Read how your data is used", SMALL, ColorScheme.LIGHT_GRAY_COLOR);
		info.setAlignmentX(LEFT_ALIGNMENT);
		onClick(info, () -> showDataUseDialog());
		panel.add(info);

		return panel;
	}

	/**
	 * Full in-plugin data-use disclosure: what is collected, where it goes, and
	 * how to opt out. Mirrors PRIVACY.md. Reached from the notice row's info icon.
	 */
	private void showDataUseDialog()
	{
		String msg =
			"ChunkBlazer is a server-backed game mode. To save your progress\n"
			+ "and rank you on the leaderboards, the plugin sends data to\n"
			+ "ChunkBlazer's servers.\n"
			+ "\n"
			+ "WHAT IS SENT (only while \"Enable Server Sync\" is on):\n"
			+ "  • Your RuneScape name\n"
			+ "  • Your IP address\n"
			+ "  • Your current world and map region\n"
			+ "  • Progress events: NPC kills, XP/skill changes, items\n"
			+ "    obtained or equipped, and task completions\n"
			+ "  • If you're a Hardcore Ironman and lose that status: where\n"
			+ "    it happened and what killed you (shown on chunkblazer.com)\n"
			+ "\n"
			+ "WHAT IT IS USED FOR:\n"
			+ "  • Saving your unlocked chunks, tasks, points and game mode\n"
			+ "  • Server-side verification of completions (anti-cheat)\n"
			+ "  • Leaderboards and seeing other ChunkBlazer players online\n"
			+ "\n"
			+ "WHERE IT GOES:\n"
			+ "  • Over HTTPS to api.chunkblazer.com. Not shared with any\n"
			+ "    third parties.\n"
			+ "\n"
			+ "Track your account progress at chunkblazer.com.";

		int choice = JOptionPane.showOptionDialog(
			this,
			msg,
			"How ChunkBlazer uses your data",
			JOptionPane.DEFAULT_OPTION,
			JOptionPane.INFORMATION_MESSAGE,
			null,
			new Object[]{"Open chunkblazer.com", "Close"},
			"Close");
		if (choice == 0)
		{
			openLink("https://chunkblazer.com");
		}
	}

	// The header box's top row: the title, and at its right whatever setHeaderTool puts there.
	private JPanel titleRow;

	/** Put a small control (the task list's card details gear) at the top right of the header box. */
	public void setHeaderTool(JComponent tool)
	{
		SwingUtilities.invokeLater(() ->
		{
			titleRow.add(tool, BorderLayout.EAST);
			titleRow.revalidate();
			titleRow.repaint();
		});
	}

	private JPanel createHeaderSection()
	{
		JPanel headerPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		frame(headerPanel, ColorScheme.MEDIUM_GRAY_COLOR, 3, 6);
		// Marked for ChunkBlazer UI's themes (Varlamore draws it as a centred banner).
		headerPanel.putClientProperty("chunkblazer.banner", true);

		titleRow = styledPanel(new BorderLayout(3, 0), ColorScheme.DARKER_GRAY_COLOR);
		titleRow.setAlignmentX(CENTER_ALIGNMENT);
		titleRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 20));

		// Orange title
		JLabel title = styledLabel("ChunkBlazer", BOLD, new Color(255, 152, 0));
		title.putClientProperty("chunkblazer.centred", true);
		titleRow.add(title, BorderLayout.CENTER);


		headerPanel.add(titleRow);

		// Region and mode on same line
		JPanel infoRow = styledPanel(new FlowLayout(FlowLayout.LEFT, 0, 0), ColorScheme.DARKER_GRAY_COLOR);
		infoRow.putClientProperty("chunkblazer.centred", true);
		infoRow.setAlignmentX(CENTER_ALIGNMENT);

		regionLabel = styledLabel("Unknown (0)", SMALL, Color.WHITE);

		modeLabel = styledLabel(" | Mode: --", SMALL, new Color(0, 200, 200));

		infoRow.add(regionLabel);
		infoRow.add(modeLabel);
		headerPanel.add(infoRow);

		return headerPanel;
	}



	private JPanel createModeSelectionSection()
	{
		JPanel modePanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		frame(modePanel, ColorScheme.MEDIUM_GRAY_COLOR, 10, 10);

		// Section title
		addLabel(modePanel, "Select Game Mode", BOLD, Color.WHITE);
		modePanel.add(Box.createVerticalStrut(3));
		modePanel.add(sectionDivider());
		modePanel.add(Box.createVerticalStrut(5));

		// Verify-first prompt: verification (the chat-code handshake) proves account
		// ownership and is required for Competitive, so lead with it here in the same
		// amber as the "Verify Your Account" banner it points back to.
		addLabel(modePanel, WRAP
			+ "Verify your account, then choose your game mode. Type the code from the verify "
			+ "banner in public chat first."
			+ "</td></tr></table></html>", SMALL, new Color(255, 190, 60));
		modePanel.add(Box.createVerticalStrut(8));

		// Warning text
		addLabel(modePanel, "<html><i>This choice is permanent for this account!</i></html>",
			SMALL, Color.YELLOW);
		modePanel.add(Box.createVerticalStrut(10));

		// Radio buttons
		ButtonGroup modeGroup = new ButtonGroup();

		casualRadio = new JRadioButton("Casual Mode");
		casualRadio.setToolTipText("Start anywhere. Play on any account. Featured on the casual leaderboard.");
		casualRadio.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		casualRadio.setForeground(Color.WHITE);
		casualRadio.setSelected(true);
		casualRadio.setAlignmentX(LEFT_ALIGNMENT);
		modeGroup.add(casualRadio);
		modePanel.add(casualRadio);

		// Fixed-width table keeps the blurb inside CONTENT_WIDTH; Swing's CSS
		// subset ignores width on div/body/p, so a table is the reliable wrap.
		addLabel(modePanel, WRAP
			+ "Start anywhere. Play on any account. Featured on the casual leaderboard."
			+ "</td></tr></table></html>", SMALL, Color.LIGHT_GRAY);
		modePanel.add(Box.createVerticalStrut(5));

		nuzlockeRadio = new JRadioButton("Competitive");
		nuzlockeRadio.setToolTipText("Featured on the main page of the leaderboard and website. You must start on a new ironman account, Group included (combat level 9 or lower).");
		nuzlockeRadio.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nuzlockeRadio.setForeground(Color.WHITE);
		nuzlockeRadio.setAlignmentX(LEFT_ALIGNMENT);
		modeGroup.add(nuzlockeRadio);
		modePanel.add(nuzlockeRadio);

		addLabel(modePanel, WRAP
			+ "Featured on the main page of the leaderboard and website. You must start on a new ironman account, Group included (combat level 9 or lower)."
			+ "</td></tr></table></html>", SMALL, Color.LIGHT_GRAY);
		modePanel.add(Box.createVerticalStrut(10));

		// Confirm button
		JButton confirmButton = new JButton("Confirm Mode");
		confirmButton.setAlignmentX(LEFT_ALIGNMENT);
		confirmButton.addActionListener(e -> onConfirmMode());
		modePanel.add(confirmButton);

		return modePanel;
	}


	/**
	 * Prompt shown while the player is logged out, in place of the gameplay
	 * sections (which are account-specific and can't do anything pre-login).
	 */
	private JPanel createLoggedOutSection()
	{
		JPanel panel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		frame(panel, ColorScheme.MEDIUM_GRAY_COLOR, 15, 10);

		addLabel(panel, "Not logged in", BOLD, Color.WHITE);
		panel.add(Box.createVerticalStrut(5));

		addLabel(panel, "<html>Log into Old School RuneScape to start playing ChunkBlazer.</html>",
			SMALL, Color.LIGHT_GRAY);

		return panel;
	}

	// --- Action Handlers ---

	private void onConfirmMode()
	{
		GameMode selectedMode = casualRadio.isSelected() ? GameMode.CASUAL : GameMode.NUZLOCKE;

		int confirm = JOptionPane.showConfirmDialog(
			this,
			"Are you sure you want to select " + selectedMode.getName() + " mode?\n\n" +
			"This choice is PERMANENT for this account!",
			"Confirm Game Mode",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);

		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}

		// Competitive is verified server-side (fresh-account eligibility + the chat-code
		// handshake), so it can't be locked with sync off. Instead of failing with a
		// chat message, offer to turn Server Sync on right here — Yes enables it and
		// continues the lock once connected; No leaves sync off and cancels.
		if (selectedMode == GameMode.NUZLOCKE && !plugin.isServerSyncEnabled())
		{
			int enable = JOptionPane.showConfirmDialog(
				this,
				"Competitive mode needs a connection to the ChunkBlazer server to verify\n"
				+ "your RuneScape account.\n\n"
				+ "Do you want to enable Server Sync?",
				"Enable Server Sync?",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE
			);
			if (enable == JOptionPane.YES_OPTION)
			{
				plugin.enableServerSyncAndLockCompetitive();
			}
			updateModeDisplay();
			return;
		}

		plugin.lockGameMode(selectedMode);
		updateModeDisplay();
	}

	public void updatePanel()
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean loggedIn = plugin.isLoggedIn();
			loggedOutPanel.setVisible(!loggedIn);

			boolean syncOn = plugin.isServerSyncEnabled();
			// Not on Tutorial Island: it's set up on reaching Lumbridge, and here it pushed the
			// tutorial's practice tasks down out of sight.
			syncPromptPanel.setVisible(!syncOn && !plugin.isOnTutorialIsland());
			boolean choosing = !syncOn && !plugin.isPlayOffline();
			syncChoiceHint.setVisible(choosing);
			playOfflineButton.setVisible(choosing);
			dataNoticeRow.setVisible(syncOn);
			showKeyButton.setVisible(syncOn);
			resetAccountButton.setVisible(syncOn);
			historyButton.setVisible(syncOn && loggedIn);

			if (!loggedIn)
			{
				modeSelectionPanel.setVisible(false);
				overlayHint.setVisible(false);
				taskArea.setVisible(false);
				revalidate();
				repaint();
				return;
			}
			updateModeDisplay();
			updateRegionDisplay();
		});
	}


	/** The task list (TaskSidePanel), shown under the hint once a mode is picked. */
	/** Add to the framed box under the hint (the points, chunks, tasks and tokens). */
	public void addTaskStats(JComponent stats)
	{
		SwingUtilities.invokeLater(() ->
		{
			stats.setAlignmentX(LEFT_ALIGNMENT);
			overlayHint.add(stats);
			revalidate();
		});
	}

	/** Add below the framed box, the panel's full width (search, filters, the task list). */
	public void addTaskList(JComponent list)
	{
		SwingUtilities.invokeLater(() ->
		{
			list.setAlignmentX(LEFT_ALIGNMENT);
			taskArea.add(list);
			revalidate();
		});
	}

	public void updateModeDisplay()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateModeDisplay);
			return;
		}
		boolean isLocked = plugin.isModeLocked();
		// On Tutorial Island the tutorial runs the show: its practice tasks, and the mode
		// is picked once you reach the mainland.
		boolean island = plugin.isOnTutorialIsland();
		modeSelectionPanel.setVisible(!isLocked && !island);
		tutorialPanel.setVisible(island);
		if (island)
		{
			// Sync, verification and the account links all need the mainland's chat box.
			for (JComponent c : new JComponent[]{syncPromptPanel, dataNoticeRow, verificationPanel, historyButton,
				showKeyButton, resetAccountButton})
			{
				c.setVisible(false);
			}
		}
		overlayHint.setVisible(isLocked || island || plugin.isTutorialPreview());
		taskArea.setVisible(overlayHint.isVisible());
		// Until a mode is picked the panel is only the mode picker, so no chunk either.
		regionLabel.setVisible(isLocked);

		if (isLocked)
		{
			GameMode mode = plugin.getGameMode();
			Color modeColor = mode == GameMode.NUZLOCKE ?
				new Color(255, 100, 100) : new Color(100, 200, 100);
			modeLabel.setText(" | " + mode.getName());
			modeLabel.setForeground(modeColor);
		}
		else if (island)
		{
			modeLabel.setText("Tutorial: pick a mode in Lumbridge");
			modeLabel.setForeground(Color.ORANGE);
		}
		else
		{
			modeLabel.setText("Mode: Not Set");
			modeLabel.setForeground(Color.YELLOW);
		}

		revalidate();
		repaint();
	}

	public void updateRegionDisplay()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateRegionDisplay);
			return;
		}
		int regionId = plugin.getCurrentRegionId();
		String regionName = plugin.getCurrentRegionName();

		if (regionId > 0)
		{
			// Show full region name - no truncation
			String displayName = regionName != null ? regionName : "Unknown";
			regionLabel.setText(displayName + " (" + regionId + ")");
		}
		else
		{
			regionLabel.setText("Unknown (0)");
		}
	}
}
