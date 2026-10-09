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
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import com.chunkblazer.ui.WrappingTextLabel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;



@Slf4j
public class ChunkBlazerPanel extends PluginPanel
{
	private static final int PANEL_WIDTH = 225; // Standard RuneLite panel width
	private static final int CONTENT_WIDTH = PANEL_WIDTH - 24; // Width for content inside panels (accounting for borders/padding)
	// ChunkBlazer theme accent, flame orange.
	private static final Color FLAME = new Color(255, 152, 0);
	private static final int TASK_TEXT_WRAP_WIDTH = CONTENT_WIDTH - 25;

	private ChunkBlazerPlugin plugin;

	private JPanel modeSelectionPanel;
	private JPanel overlayHint;
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
	private JLabel verificationCodeLabel;

	// Long user-facing text lives in panel.properties (UTF-8) to keep the source small.
	private static final Properties TEXT = new Properties();

	static
	{
		try (Reader reader = new InputStreamReader(ChunkBlazerPanel.class.getResourceAsStream("panel.properties"), StandardCharsets.UTF_8))
		{
			TEXT.load(reader);
		}
		catch (Exception e)
		{
			log.warn("Failed to load panel text", e);
		}
	}

	static String t(String key)
	{
		return TEXT.getProperty(key, key);
	}

	public ChunkBlazerPanel()
	{
		super(true);
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
		overlayHint.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(FLAME),
			new EmptyBorder(5, 6, 5, 6)));
		overlayHint.add(new WrappingTextLabel(t("pts"),
			FontManager.getRunescapeSmallFont(), FLAME, CONTENT_WIDTH - 16));
		overlayHint.setVisible(false);
		mainPanel.add(overlayHint);
		mainPanel.add(Box.createVerticalStrut(8));

		historyButton = linkButton("View my task history", new Color(140, 140, 140), t("historyTip"));
		historyButton.addActionListener(e -> openLink("https://chunkblazer.com/player.html?rsn="
			+ java.net.URLEncoder.encode(String.valueOf(plugin.getPlayerName()), java.nio.charset.StandardCharsets.UTF_8)));
		mainPanel.add(historyButton);
		showKeyButton = linkButton("Show my sync key", new Color(140, 140, 140), t("keyTip"));
		showKeyButton.addActionListener(e -> showSyncKeyBackup());
		mainPanel.add(showKeyButton);
		// Repair link for a contaminated account: clears only THIS account's local data
		// so it restores fresh from the server.
		resetAccountButton = linkButton("Reset this account's sync data", new Color(170, 110, 110), t("resetTip"));
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

	/** A subtle text-only link button. */
	private JButton linkButton(String text, Color color, String tooltip)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
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
	 * Create a standard action button: bold RuneScape font, white text on the
	 * given background, no focus ring. Caller wires the listener and adds it.
	 */
	private JButton actionButton(String text, Color bg)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeBoldFont());
		button.setBackground(bg);
		button.setForeground(Color.WHITE);
		button.setFocusPainted(false);
		return button;
	}

	/**
	 * Build the "Verify Your Account" banner. The big code label is filled in
	 * at runtime by {@link #showVerificationPrompt(String)}.
	 */
	private JPanel createVerificationSection()
	{
		JPanel panel = boxPanel(new Color(60, 45, 18)); // dark amber
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(230, 170, 50), 2),
			new EmptyBorder(8, 8, 8, 8)
		));

		addLabel(panel, "Verify Your Account", FontManager.getRunescapeBoldFont(), new Color(255, 190, 60));
		panel.add(Box.createVerticalStrut(5));

		WrappingTextLabel body = new WrappingTextLabel(
			t("verifyBody"),
			FontManager.getRunescapeSmallFont(),
			Color.WHITE,
			CONTENT_WIDTH - 4);
		panel.add(body);
		panel.add(Box.createVerticalStrut(6));

		verificationCodeLabel = addLabel(panel, " ",
			FontManager.getRunescapeBoldFont().deriveFont(24f), new Color(120, 230, 120));

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

		row.add(styledLabel("Progress synced to", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		JLabel site = styledLabel("chunkblazer.com", FontManager.getRunescapeSmallFont(), new Color(255, 152, 0));
		site.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		site.setToolTipText(t("siteTip"));
		site.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				openLink("https://chunkblazer.com");
			}
		});
		row.add(site);

		// ASCII "(?)" — Runescape font lacks a circled-i glyph
		JLabel info = styledLabel("(?)", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR);
		info.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		info.setToolTipText("How your data is used");
		info.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				showDataUseDialog();
			}
		});
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
			JOptionPane.showMessageDialog(this, t("noKey"), "Sync key", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		// Gate the reveal behind an explicit confirmation. The key is a full account
		// credential and RuneLite is often streamed or screen-shared, so we never put
		// it on screen until the player says so.
		int confirm = JOptionPane.showConfirmDialog(this, t("reveal"),
			"Reveal sync key?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}
		JTextField keyField = new JTextField(key);
		keyField.setEditable(false);
		keyField.setCaretPosition(0);
		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.add(new JLabel(t("keyHelp")), BorderLayout.NORTH);
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
		int confirm = JOptionPane.showConfirmDialog(this, t("resetConfirm"),
			"Reset this account's local data?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}
		plugin.resetAccountLocalData();
		JOptionPane.showMessageDialog(this, t("resetDone"), "Done", JOptionPane.INFORMATION_MESSAGE);
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
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(FLAME),
			new EmptyBorder(6, 6, 6, 6)));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		addLabel(panel, "Server sync is off", FontManager.getRunescapeBoldFont(), FLAME);
		panel.add(Box.createVerticalStrut(3));

		WrappingTextLabel body = new WrappingTextLabel(t("syncBody"),
			FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR, TASK_TEXT_WRAP_WIDTH);
		body.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(body);

		panel.add(Box.createVerticalStrut(3));
		WrappingTextLabel comp = new WrappingTextLabel(t("compReq"),
			FontManager.getRunescapeSmallFont(), new Color(255, 190, 60), TASK_TEXT_WRAP_WIDTH);
		comp.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(comp);

		// Shown until the player picks: a new device for an existing account must not
		// deal Lumbridge before it knows whether the server already has a roll.
		panel.add(Box.createVerticalStrut(3));
		WrappingTextLabel hint = new WrappingTextLabel(t("syncHint"),
			FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR, TASK_TEXT_WRAP_WIDTH);
		hint.setAlignmentX(LEFT_ALIGNMENT);
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
		playOfflineButton.setToolTipText(t("offlineTip"));
		playOfflineButton.addActionListener(e -> plugin.choosePlayOffline());
		panel.add(playOfflineButton);

		panel.add(Box.createVerticalStrut(4));
		JLabel info = styledLabel("Read how your data is used", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR);
		info.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		info.setAlignmentX(LEFT_ALIGNMENT);
		info.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				showDataUseDialog();
			}
		});
		panel.add(info);

		return panel;
	}

	/**
	 * Full in-plugin data-use disclosure: what is collected, where it goes, and
	 * how to opt out. Mirrors PRIVACY.md. Reached from the notice row's info icon.
	 */
	private void showDataUseDialog()
	{
		int choice = JOptionPane.showOptionDialog(
			this,
			t("dataUse"),
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

	private JPanel createHeaderSection()
	{
		JPanel headerPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 6, 3, 6)
		));

		JPanel titleRow = styledPanel(new BorderLayout(3, 0), ColorScheme.DARKER_GRAY_COLOR);
		titleRow.setAlignmentX(CENTER_ALIGNMENT);
		titleRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 20));

		// Orange title
		titleRow.add(styledLabel("ChunkBlazer", FontManager.getRunescapeBoldFont(), new Color(255, 152, 0)), BorderLayout.WEST);


		headerPanel.add(titleRow);

		// Region and mode on same line
		JPanel infoRow = styledPanel(new FlowLayout(FlowLayout.LEFT, 0, 0), ColorScheme.DARKER_GRAY_COLOR);
		infoRow.setAlignmentX(CENTER_ALIGNMENT);

		regionLabel = styledLabel("Unknown (0)", FontManager.getRunescapeSmallFont(), Color.WHITE);

		modeLabel = styledLabel(" | Mode: --", FontManager.getRunescapeSmallFont(), new Color(0, 200, 200));

		infoRow.add(regionLabel);
		infoRow.add(modeLabel);
		headerPanel.add(infoRow);

		return headerPanel;
	}



	private JPanel createModeSelectionSection()
	{
		JPanel modePanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		modePanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(10, 10, 10, 10)
		));

		// Section title
		addLabel(modePanel, "Select Game Mode", FontManager.getRunescapeBoldFont(), Color.WHITE);
		modePanel.add(Box.createVerticalStrut(3));
		modePanel.add(sectionDivider());
		modePanel.add(Box.createVerticalStrut(5));

		// Verify-first prompt: verification (the chat-code handshake) proves account
		// ownership and is required for Competitive, so lead with it here in the same
		// amber as the "Verify Your Account" banner it points back to.
		addLabel(modePanel, t("verifyFirst"), FontManager.getRunescapeSmallFont(), new Color(255, 190, 60));
		modePanel.add(Box.createVerticalStrut(8));

		// Warning text
		addLabel(modePanel, t("permanent"), FontManager.getRunescapeSmallFont(), Color.YELLOW);
		modePanel.add(Box.createVerticalStrut(10));

		// Radio buttons
		ButtonGroup modeGroup = new ButtonGroup();

		casualRadio = new JRadioButton("Casual Mode");
		casualRadio.setToolTipText(t("casual"));
		casualRadio.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		casualRadio.setForeground(Color.WHITE);
		casualRadio.setSelected(true);
		casualRadio.setAlignmentX(LEFT_ALIGNMENT);
		modeGroup.add(casualRadio);
		modePanel.add(casualRadio);

		// Fixed-width table keeps the blurb inside CONTENT_WIDTH; Swing's CSS
		// subset ignores width on div/body/p, so a table is the reliable wrap.
		addLabel(modePanel, t("casualBlurb"), FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);
		modePanel.add(Box.createVerticalStrut(5));

		nuzlockeRadio = new JRadioButton("Competitive");
		nuzlockeRadio.setToolTipText(t("competitive"));
		nuzlockeRadio.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nuzlockeRadio.setForeground(Color.WHITE);
		nuzlockeRadio.setAlignmentX(LEFT_ALIGNMENT);
		modeGroup.add(nuzlockeRadio);
		modePanel.add(nuzlockeRadio);

		addLabel(modePanel, t("competitiveBlurb"), FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);
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
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(15, 10, 15, 10)
		));

		addLabel(panel, "Not logged in", FontManager.getRunescapeBoldFont(), Color.WHITE);
		panel.add(Box.createVerticalStrut(5));

		addLabel(panel, t("loggedOut"), FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);

		return panel;
	}

	// --- Action Handlers ---

	private void onConfirmMode()
	{
		GameMode selectedMode = casualRadio.isSelected() ? GameMode.CASUAL : GameMode.NUZLOCKE;

		int confirm = JOptionPane.showConfirmDialog(
			this,
			String.format(t("confirmMode"), selectedMode.getName()),
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
				t("needSync"),
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
			syncPromptPanel.setVisible(!syncOn);
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
				revalidate();
				repaint();
				return;
			}
			updateModeDisplay();
			updateRegionDisplay();
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
		modeSelectionPanel.setVisible(!isLocked);
		overlayHint.setVisible(isLocked);

		if (isLocked)
		{
			GameMode mode = plugin.getGameMode();
			Color modeColor = mode == GameMode.NUZLOCKE ?
				new Color(255, 100, 100) : new Color(100, 200, 100);
			modeLabel.setText(" | " + mode.getName());
			modeLabel.setForeground(modeColor);
		}
		else
		{
			modeLabel.setText(" | Not Set");
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
