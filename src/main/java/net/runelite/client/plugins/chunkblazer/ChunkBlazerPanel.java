package net.runelite.client.plugins.chunkblazer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.chunkblazer.ui.WrappingTextLabel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

@Slf4j
public class ChunkBlazerPanel extends PluginPanel
{
	private static final int TASK_ITEM_HEIGHT = 80; // Approx height of one task item (increased for text wrapping)
	private static final int VISIBLE_TASK_COUNT = 5; // Show 5 tasks at a time in scroll areas
	private static final int MAX_TASK_LIST_HEIGHT = TASK_ITEM_HEIGHT * VISIBLE_TASK_COUNT; // Show 5 items
	private static final int MAX_ACTIVE_TASKS_HEIGHT = TASK_ITEM_HEIGHT * 4; // Max height for active tasks (4 items)
	private static final int MAX_COMPLETED_TASKS_HEIGHT = TASK_ITEM_HEIGHT * VISIBLE_TASK_COUNT; // Show 5 items

	private ChunkBlazerPlugin plugin;

	// UI Components
	private JPanel regionUnlockPanel;
	private JPanel unlockedListPanel;
	private boolean unlockedListExpanded = false; // Unlocked Chunks list collapsed by default
	private JPanel unlockableChunksPanel; // unused — list UI removed; dead code, delete in cleanup pass
	// Pins the top-right region-unlock prompt to a chunk clicked on the world map
	// (hold U + click), overriding the walk-into-chunk current-region behaviour
	// until the player confirms or cancels.
	private int mapUnlockRegionId = -1;
	private JPanel modeSelectionPanel;
	private JPanel lockedModePanel;
	private JLabel lockedModeValueLabel;
	private JPanel loggedOutPanel;
	private JPanel currentTaskPanel;
	private JPanel activeTasksContentPanel; // Inner panel for active tasks
	private JScrollPane activeTasksScrollPane;
	private JPanel completedTasksPanel;
	private JPanel completedTasksContentPanel; // Inner panel for completed tasks
	private JScrollPane completedTasksScrollPane;
	private JPanel devControlsPanel;
	private JPanel taskListPanel;
	private JPanel taskListContentPanel; // Inner panel for region tasks
	private JScrollPane taskListScrollPane;
	private JTextField taskFilterField;
	private JToggleButton taskListToggle;
	private boolean taskListExpanded = false;
	private String taskFilterText = "";
	private JPanel statsPanel;
	private JLabel regionLabel;
	private JLabel modeLabel;
	private JLabel totalPointsLabel;
	private JLabel chunksUnlockedLabel;
	private JLabel tasksCompletedLabel;
	private JLabel taskNameLabel;
	private JLabel taskCategoryLabel;
	private JLabel taskPointsLabel;
	private JLabel taskProgressLabel;
	private JRadioButton casualRadio;
	private JRadioButton nuzlockeRadio;

	// Verification banner — shown at the top of the panel until the account is
	// verified via the in-game chat handshake. Hidden otherwise.
	private JPanel verificationPanel;
	private JLabel verificationCodeLabel;

	// Completed Tasks Filter Components
	private JToggleButton completedTasksToggle;
	private boolean completedTasksExpanded = false;

	// Active Tasks Collapse
	private JToggleButton activeTasksToggle;
	private boolean activeTasksExpanded = true; // Start expanded
	private JPanel activeTasksFilterPanel;
	private JLabel activeTasksCollapsedLabel;

	// Dev Controls Collapse
	private JToggleButton devControlsToggle;
	private boolean devControlsExpanded = false;
	private JPanel devControlsContentPanel;
	private JTextField completedTasksSearchField;
	private JComboBox<String> categoryFilterCombo;
	private JComboBox<String> regionFilterCombo;
	private JComboBox<String> areaFilterCombo;
	private String completedTasksSearchText = "";
	private String selectedCategory = "All";
	private String selectedRegion = "All";
	private String selectedArea = "All";
	private JPanel completedTasksFilterPanel;
	private JLabel completedCollapsedLabel;
	private boolean isRefreshingFilters = false; // Prevent event loops during filter refresh

	// Active Tasks Components
	private JPanel selectedTaskPanel;
	private JLabel activeTasksSectionTitle;
	private JTextField activeTasksSearchField;
	private JComboBox<String> activeTasksCategoryCombo;
	private JComboBox<String> activeTasksRegionCombo;
	private JComboBox<String> activeTasksAreaCombo;
	private String activeTasksSearchText = "";
	private String activeTasksSelectedCategory = "All";
	private String activeTasksSelectedRegion = "All";
	private String activeTasksSelectedArea = "All";
	private boolean isRefreshingActiveFilters = false;
	private NuzlockeTask selectedTask = null;
	// Tracks the region ID the bottom task list was last rendered for. When the player
	// changes region (e.g. climbing a ladder) the list shows a different region's tasks,
	// so a saved viewport position is meaningless — we scroll to top instead.
	private int lastRenderedTaskListRegionId = Integer.MIN_VALUE;

	public ChunkBlazerPanel()
	{
		super(false);
	}

	private static final int PANEL_WIDTH = 225; // Standard RuneLite panel width
	private static final int CONTENT_WIDTH = PANEL_WIDTH - 24; // Width for content inside panels (accounting for borders/padding)
	// ChunkBlazer theme accent — flame orange. Primary border/title colour across
	// the panel (replaces the old mismatched gold). Green stays for tasks/success,
	// amber for verification, blue for dev, red for danger.
	private static final Color FLAME = new Color(255, 152, 0);

	// Max width passed to WrappingTextLabel as a hard cap for BoxLayout.
	// Subtracts the vertical scrollbar (~17px) and item-panel borders so the
	// component never tries to render wider than the actual visible area.
	private static final int TASK_TEXT_WRAP_WIDTH = CONTENT_WIDTH - 25;

	public void init(ChunkBlazerPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setPreferredSize(new Dimension(PANEL_WIDTH, 600));
		setMaximumSize(new Dimension(PANEL_WIDTH, Integer.MAX_VALUE));

		// Wrap main panel in a scroll pane to prevent overflow
		JPanel mainContent = createMainPanel();
		// Don't set fixed preferred height - let content determine size for proper scrolling
		mainContent.setMaximumSize(new Dimension(PANEL_WIDTH - 10, Integer.MAX_VALUE));

		JScrollPane mainScrollPane = new JScrollPane(mainContent);
		mainScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		mainScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		mainScrollPane.setBorder(null);
		mainScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainScrollPane.getVerticalScrollBar().setUnitIncrement(16);
		// Don't set fixed preferred size - let scroll pane expand based on content

		add(mainScrollPane, BorderLayout.CENTER);
	}

	private JPanel createMainPanel()
	{
		JPanel mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

		// Header Section (fixed size - don't use setupSectionPanel)
		JPanel header = createHeaderSection();
		header.setAlignmentX(LEFT_ALIGNMENT);
		mainPanel.add(header);
		mainPanel.add(Box.createVerticalStrut(4));
		// Always-visible data-sync notice (data disclosure in the UI itself).
		mainPanel.add(createDataNoticeRow());
		mainPanel.add(Box.createVerticalStrut(8));

		// Logged-out prompt — shown in place of the gameplay sections until the
		// player is in-game (most controls are account-specific and can't act
		// before login). Toggled by updateLoginGate().
		loggedOutPanel = createLoggedOutSection();
		setupSectionPanel(loggedOutPanel);
		loggedOutPanel.setVisible(false);
		mainPanel.add(loggedOutPanel);

		// Verification Banner — sits directly under the header so it's the first
		// thing an unverified player sees. Hidden by default; the plugin calls
		// showVerificationPrompt() once the server issues a code.
		verificationPanel = createVerificationSection();
		setupSectionPanel(verificationPanel);
		verificationPanel.setVisible(false);
		mainPanel.add(verificationPanel);
		mainPanel.add(Box.createVerticalStrut(8));

		// Stats Section (fixed size - don't use setupSectionPanel)
		statsPanel = createStatsSection();
		statsPanel.setAlignmentX(LEFT_ALIGNMENT);
		mainPanel.add(statsPanel);
		mainPanel.add(Box.createVerticalStrut(8));

		// Region Unlock Section — visible only when the player is standing in a
		// locked region. Hidden otherwise so it doesn't waste vertical space.
		regionUnlockPanel = createRegionUnlockSection();
		setupSectionPanel(regionUnlockPanel);
		mainPanel.add(regionUnlockPanel);
		mainPanel.add(Box.createVerticalStrut(8));

		// Mode Selection Section (hidden once the mode is locked)
		modeSelectionPanel = createModeSelectionSection();
		setupSectionPanel(modeSelectionPanel);
		mainPanel.add(modeSelectionPanel);

		// Locked-mode card — shown in place of the selector once the mode is locked.
		lockedModePanel = createLockedModeSection();
		setupSectionPanel(lockedModePanel);
		lockedModePanel.setVisible(false);
		mainPanel.add(lockedModePanel);

		mainPanel.add(Box.createVerticalStrut(8));

		// Current Task Section
		currentTaskPanel = createCurrentTaskSection();
		setupSectionPanel(currentTaskPanel);
		mainPanel.add(currentTaskPanel);
		mainPanel.add(Box.createVerticalStrut(8));

		// Completed Tasks Section
		completedTasksPanel = createCompletedTasksSection();
		setupSectionPanel(completedTasksPanel);
		mainPanel.add(completedTasksPanel);
		mainPanel.add(Box.createVerticalStrut(8));

		// Task List Section (Region Tasks)
		taskListPanel = createTaskListSection();
		setupSectionPanel(taskListPanel);
		mainPanel.add(taskListPanel);
		mainPanel.add(Box.createVerticalStrut(8));

		// Unlocked Chunks — read-only list, tucked below the active tasks so it's
		// out of the way (it can get long).
		unlockedListPanel = createUnlockedListSection();
		setupSectionPanel(unlockedListPanel);
		mainPanel.add(unlockedListPanel);
		mainPanel.add(Box.createVerticalStrut(8));

		// Dev/Test Controls Section (at the bottom, collapsible)
		devControlsPanel = createDevControlsSection();
		setupSectionPanel(devControlsPanel);
		mainPanel.add(devControlsPanel);

		// Add vertical glue at the bottom to push content up and prevent shrinking
		mainPanel.add(Box.createVerticalGlue());

		return mainPanel;
	}

	/**
	 * Configure a section panel to fill width in BoxLayout.
	 * Lock horizontal width but allow vertical expansion based on content.
	 */
	private void setupSectionPanel(JPanel panel)
	{
		panel.setAlignmentX(LEFT_ALIGNMENT);
		// Fixed width, but let height be determined by content (don't set preferredSize height)
		panel.setMaximumSize(new Dimension(PANEL_WIDTH - 10, Integer.MAX_VALUE));
		panel.setMinimumSize(new Dimension(PANEL_WIDTH - 10, 0));
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
	 * Small triangle icon for collapse toggles — drawn rather than using a unicode
	 * arrow glyph (which renders as a tofu box / "X" in the button font). Points
	 * down when collapsed ("expand"), up when expanded ("collapse"). The raised
	 * (out) vs pressed (in) bevel comes from the JToggleButton's selected state.
	 */
	private static javax.swing.Icon arrowIcon(boolean down)
	{
		return new javax.swing.Icon()
		{
			@Override
			public int getIconWidth()
			{
				return 9;
			}

			@Override
			public int getIconHeight()
			{
				return 9;
			}

			@Override
			public void paintIcon(java.awt.Component c, Graphics g, int x, int y)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(Color.WHITE);
				if (down)
				{
					g2.fillPolygon(new int[]{x, x + 8, x + 4}, new int[]{y + 2, y + 2, y + 7}, 3);
				}
				else
				{
					g2.fillPolygon(new int[]{x, x + 8, x + 4}, new int[]{y + 7, y + 7, y + 2}, 3);
				}
				g2.dispose();
			}
		};
	}

	/**
	 * A beveled "close" button icon: a raised dark square (light top/left edge, dark
	 * bottom/right edge) with an X cut into it — drawn instead of the ✕ font glyph, which
	 * renders as tofu in the RuneScape UI font. {@code xColor} lets callers brighten the X
	 * on hover.
	 */
	private static javax.swing.Icon xIcon(Color xColor)
	{
		return new javax.swing.Icon()
		{
			@Override
			public int getIconWidth()
			{
				return 16;
			}

			@Override
			public int getIconHeight()
			{
				return 16;
			}

			@Override
			public void paintIcon(java.awt.Component c, Graphics g, int x, int y)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				int s = 16;
				// Square body.
				g2.setColor(new Color(43, 43, 43));
				g2.fillRect(x, y, s - 1, s - 1);
				// Raised bevel: light top/left, dark bottom/right.
				g2.setColor(new Color(96, 96, 96));
				g2.drawLine(x, y, x + s - 2, y);
				g2.drawLine(x, y, x, y + s - 2);
				g2.setColor(new Color(16, 16, 16));
				g2.drawLine(x, y + s - 2, x + s - 2, y + s - 2);
				g2.drawLine(x + s - 2, y, x + s - 2, y + s - 2);
				// X.
				g2.setColor(xColor);
				g2.setStroke(new java.awt.BasicStroke(1.8f,
					java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
				g2.drawLine(x + 5, y + 5, x + 10, y + 10);
				g2.drawLine(x + 10, y + 5, x + 5, y + 10);
				g2.dispose();
			}
		};
	}

	/** Point a collapse toggle's arrow: down when collapsed, up when expanded. */
	private void setToggleArrow(JToggleButton btn, boolean expanded)
	{
		btn.setText(null);
		btn.setIcon(arrowIcon(!expanded));
	}

	/**
	 * Build the "Verify Your Account" banner. The big code label is filled in
	 * at runtime by {@link #showVerificationPrompt(String)}.
	 */
	private JPanel createVerificationSection()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(new Color(60, 45, 18)); // dark amber
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(230, 170, 50), 2),
			new EmptyBorder(8, 8, 8, 8)
		));

		JLabel title = new JLabel("Verify Your Account");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(new Color(255, 190, 60));
		title.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(title);
		panel.add(Box.createVerticalStrut(5));

		WrappingTextLabel body = new WrappingTextLabel(
			"Type this code in public chat and hit Enter to verify your ChunkBlazer account:",
			FontManager.getRunescapeSmallFont(),
			Color.WHITE,
			CONTENT_WIDTH - 4);
		panel.add(body);
		panel.add(Box.createVerticalStrut(6));

		verificationCodeLabel = new JLabel(" ");
		verificationCodeLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(24f));
		verificationCodeLabel.setForeground(new Color(120, 230, 120));
		verificationCodeLabel.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(verificationCodeLabel);

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
		try
		{
			java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
		}
		catch (Exception e)
		{
			log.error("Failed to open link: {}", url, e);
			JOptionPane.showMessageDialog(this,
				"Failed to open link:\n" + url + "\n\n" + e.getMessage(),
				"Error",
				JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Compact, always-visible data notice under the header: tells players their
	 * progress is synced to chunkblazer.com and links to the full data-use
	 * explanation. This is the in-plugin half of the data disclosure (the config
	 * "Enable Server Verification" description carries the other half).
	 */
	private JPanel createDataNoticeRow()
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(PANEL_WIDTH, 16));

		JLabel note = new JLabel("Progress synced to");
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(note);

		JLabel site = new JLabel("chunkblazer.com");
		site.setFont(FontManager.getRunescapeSmallFont());
		site.setForeground(new Color(255, 152, 0));
		site.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		site.setToolTipText("Track your account progress at chunkblazer.com");
		site.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				openLink("https://chunkblazer.com");
			}
		});
		row.add(site);

		JLabel info = new JLabel("(?)"); // ASCII — Runescape font lacks a circled-i glyph
		info.setFont(FontManager.getRunescapeSmallFont());
		info.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
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
			+ "WHAT IS SENT (only while \"Enable Server Verification\" is on):\n"
			+ "  • Your RuneScape name\n"
			+ "  • Your current world and map region\n"
			+ "  • Progress events: NPC kills, XP/skill changes, items\n"
			+ "    obtained or equipped, and task completions\n"
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
			"ChunkBlazer — How your data is used",
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

	private JPanel createCompletedTasksSection()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(100, 100, 180)),
			new EmptyBorder(6, 6, 6, 6)
		));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		// Header row with toggle button
		JPanel headerRow = new JPanel(new BorderLayout(5, 0));
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		JLabel sectionTitle = new JLabel("Completed Tasks");
		sectionTitle.setFont(FontManager.getRunescapeBoldFont());
		sectionTitle.setForeground(new Color(100, 255, 100));
		headerRow.add(sectionTitle, BorderLayout.WEST);

		completedTasksToggle = new JToggleButton();
		setToggleArrow(completedTasksToggle, completedTasksExpanded);
		completedTasksToggle.setFont(new Font("Arial", Font.PLAIN, 10));
		completedTasksToggle.setPreferredSize(new Dimension(30, 20));
		completedTasksToggle.setMaximumSize(new Dimension(30, 20));
		completedTasksToggle.setToolTipText("Expand/collapse completed tasks with search");
		completedTasksToggle.addActionListener(e ->
		{
			completedTasksExpanded = completedTasksToggle.isSelected();
			setToggleArrow(completedTasksToggle, completedTasksExpanded);
			updateCompletedTasksVisibility();
		});
		headerRow.add(completedTasksToggle, BorderLayout.EAST);

		panel.add(headerRow);
		panel.add(sectionDivider());
		panel.add(Box.createVerticalStrut(5));

		// Filter panel (visible when expanded)
		completedTasksFilterPanel = new JPanel();
		completedTasksFilterPanel.setLayout(new BoxLayout(completedTasksFilterPanel, BoxLayout.Y_AXIS));
		completedTasksFilterPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		completedTasksFilterPanel.setAlignmentX(LEFT_ALIGNMENT);
		completedTasksFilterPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 135));
		completedTasksFilterPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 135));
		completedTasksFilterPanel.setVisible(false);

		// Search text field
		JPanel searchRow = new JPanel(new BorderLayout(5, 0));
		searchRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchRow.setAlignmentX(LEFT_ALIGNMENT);
		searchRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		searchRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		JLabel searchLabel = new JLabel("Search:");
		searchLabel.setFont(FontManager.getRunescapeSmallFont());
		searchLabel.setForeground(Color.LIGHT_GRAY);
		searchRow.add(searchLabel, BorderLayout.WEST);

		completedTasksSearchField = new JTextField();
		completedTasksSearchField.setToolTipText("Search tasks by name");
		completedTasksSearchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				onCompletedTasksFilterChanged();
			}
			@Override
			public void removeUpdate(DocumentEvent e)
			{
				onCompletedTasksFilterChanged();
			}
			@Override
			public void changedUpdate(DocumentEvent e)
			{
				onCompletedTasksFilterChanged();
			}
		});
		searchRow.add(completedTasksSearchField, BorderLayout.CENTER);

		completedTasksFilterPanel.add(searchRow);
		completedTasksFilterPanel.add(Box.createVerticalStrut(5));

		// Area filter — broadest cut (Misthalin / Asgarnia / Zeah / ...).
		JPanel areaRow = new JPanel(new BorderLayout(2, 0));
		areaRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		areaRow.setAlignmentX(LEFT_ALIGNMENT);
		areaRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 45));
		areaRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 45));

		JLabel areaLabel = new JLabel("Area:");
		areaLabel.setFont(FontManager.getRunescapeSmallFont());
		areaLabel.setForeground(Color.LIGHT_GRAY);
		areaRow.add(areaLabel, BorderLayout.NORTH);

		areaFilterCombo = new JComboBox<>(new String[]{"All"});
		areaFilterCombo.setFont(FontManager.getRunescapeSmallFont());
		areaFilterCombo.addActionListener(e ->
		{
			if (!isRefreshingFilters)
			{
				selectedArea = (String) areaFilterCombo.getSelectedItem();
				if (selectedArea == null) selectedArea = "All";
				updateCompletedTasksContent();
			}
		});
		areaRow.add(areaFilterCombo, BorderLayout.CENTER);

		completedTasksFilterPanel.add(areaRow);
		completedTasksFilterPanel.add(Box.createVerticalStrut(5));

		// Category and Region filter row
		JPanel filterRow = new JPanel(new GridLayout(1, 2, 5, 0));
		filterRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		filterRow.setAlignmentX(LEFT_ALIGNMENT);
		filterRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 50));
		filterRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 50));

		// Category filter
		JPanel categoryPanel = new JPanel(new BorderLayout(2, 0));
		categoryPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel catLabel = new JLabel("Category:");
		catLabel.setFont(FontManager.getRunescapeSmallFont());
		catLabel.setForeground(Color.LIGHT_GRAY);
		categoryPanel.add(catLabel, BorderLayout.NORTH);

		categoryFilterCombo = new JComboBox<>(new String[]{"All"});
		categoryFilterCombo.setFont(FontManager.getRunescapeSmallFont());
		categoryFilterCombo.addActionListener(e ->
		{
			if (!isRefreshingFilters)
			{
				selectedCategory = (String) categoryFilterCombo.getSelectedItem();
				if (selectedCategory == null) selectedCategory = "All";
				updateCompletedTasksContent();
			}
		});
		categoryPanel.add(categoryFilterCombo, BorderLayout.CENTER);

		filterRow.add(categoryPanel);

		// Region filter
		JPanel regionPanel = new JPanel(new BorderLayout(2, 0));
		regionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel regLabel = new JLabel("Chunk:");
		regLabel.setFont(FontManager.getRunescapeSmallFont());
		regLabel.setForeground(Color.LIGHT_GRAY);
		regionPanel.add(regLabel, BorderLayout.NORTH);

		regionFilterCombo = new JComboBox<>(new String[]{"All"});
		regionFilterCombo.setFont(FontManager.getRunescapeSmallFont());
		regionFilterCombo.addActionListener(e ->
		{
			if (!isRefreshingFilters)
			{
				selectedRegion = (String) regionFilterCombo.getSelectedItem();
				if (selectedRegion == null) selectedRegion = "All";
				updateCompletedTasksContent();
			}
		});
		regionPanel.add(regionFilterCombo, BorderLayout.CENTER);

		filterRow.add(regionPanel);

		completedTasksFilterPanel.add(filterRow);
		completedTasksFilterPanel.add(Box.createVerticalStrut(5));

		panel.add(completedTasksFilterPanel);

		// Scrollable content panel
		completedTasksContentPanel = new JPanel();
		completedTasksContentPanel.setLayout(new BoxLayout(completedTasksContentPanel, BoxLayout.Y_AXIS));
		completedTasksContentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		completedTasksContentPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

		completedTasksScrollPane = new JScrollPane(completedTasksContentPanel);
		completedTasksScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		completedTasksScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		completedTasksScrollPane.setBorder(null);
		completedTasksScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		completedTasksScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		completedTasksScrollPane.setAlignmentX(LEFT_ALIGNMENT);
		// Initially hidden - size will be set when expanded
		completedTasksScrollPane.setVisible(false);

		panel.add(completedTasksScrollPane);

		// Collapsed summary label
		completedCollapsedLabel = new JLabel("Click to view completed tasks");
		completedCollapsedLabel.setFont(FontManager.getRunescapeSmallFont());
		completedCollapsedLabel.setForeground(Color.GRAY);
		completedCollapsedLabel.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(completedCollapsedLabel);

		return panel;
	}


	private void updateCompletedTasksVisibility()
	{
		completedTasksFilterPanel.setVisible(completedTasksExpanded);
		completedTasksScrollPane.setVisible(completedTasksExpanded);
		completedCollapsedLabel.setVisible(!completedTasksExpanded);

		if (completedTasksExpanded)
		{
			refreshCompletedTasksFilters();
			updateCompletedTasksContent();

			// Set size to show 5 items (each ~65px with spacing)
			int height = MAX_COMPLETED_TASKS_HEIGHT;
			completedTasksScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
			completedTasksScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
			completedTasksScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
		}
		else
		{
			// Reset to collapsed size
			completedTasksScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, 0));
			completedTasksScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, 0));
			completedTasksScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, 0));
		}

		// Revalidate the entire panel hierarchy
		completedTasksPanel.revalidate();
		completedTasksPanel.repaint();

		// Also revalidate parent to fix layout
		if (completedTasksPanel.getParent() != null)
		{
			completedTasksPanel.getParent().revalidate();
			completedTasksPanel.getParent().repaint();
		}
	}

	private void onCompletedTasksFilterChanged()
	{
		completedTasksSearchText = completedTasksSearchField.getText().toLowerCase().trim();
		updateCompletedTasksContent();
	}

	private void refreshCompletedTasksFilters()
	{
		isRefreshingFilters = true; // Prevent event loops
		try
		{
			Set<String> categories = plugin.getAllCategories();
			String currentCategory = selectedCategory;
			categoryFilterCombo.removeAllItems();
			categoryFilterCombo.addItem("All");
			for (String cat : categories)
			{
				categoryFilterCombo.addItem(cat);
			}
			if (currentCategory != null && categories.contains(currentCategory))
			{
				categoryFilterCombo.setSelectedItem(currentCategory);
			}
			else
			{
				categoryFilterCombo.setSelectedItem("All");
				selectedCategory = "All";
			}

			Set<String> regions = plugin.getCompletedTaskRegions();
			String currentRegion = selectedRegion;
			regionFilterCombo.removeAllItems();
			regionFilterCombo.addItem("All");
			for (String reg : regions)
			{
				regionFilterCombo.addItem(reg);
			}
			if (currentRegion != null && regions.contains(currentRegion))
			{
				regionFilterCombo.setSelectedItem(currentRegion);
			}
			else
			{
				regionFilterCombo.setSelectedItem("All");
				selectedRegion = "All";
			}

			Set<String> areas = plugin.getCompletedTaskAreas();
			String currentArea = selectedArea;
			areaFilterCombo.removeAllItems();
			areaFilterCombo.addItem("All");
			for (String a : areas)
			{
				areaFilterCombo.addItem(a);
			}
			if (currentArea != null && areas.contains(currentArea))
			{
				areaFilterCombo.setSelectedItem(currentArea);
			}
			else
			{
				areaFilterCombo.setSelectedItem("All");
				selectedArea = "All";
			}
		}
		finally
		{
			isRefreshingFilters = false;
		}
	}

	private static final int HEADER_HEIGHT = 38; // Fixed height for header section
	private static final int STATS_HEIGHT = 42; // Fixed height for stats section

	private JPanel createHeaderSection()
	{
		JPanel headerPanel = new JPanel();
		headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 6, 3, 6)
		));
		// Fixed size - never changes
		headerPanel.setPreferredSize(new Dimension(PANEL_WIDTH - 10, HEADER_HEIGHT));
		headerPanel.setMinimumSize(new Dimension(PANEL_WIDTH - 10, HEADER_HEIGHT));
		headerPanel.setMaximumSize(new Dimension(PANEL_WIDTH - 10, HEADER_HEIGHT));

		// Title row with Discord button
		JPanel titleRow = new JPanel(new BorderLayout(3, 0));
		titleRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titleRow.setAlignmentX(CENTER_ALIGNMENT);
		titleRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 20));

		JLabel titleLabel = new JLabel("ChunkBlazer");
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(new Color(255, 152, 0)); // Orange color
		titleRow.add(titleLabel, BorderLayout.WEST);

		// Discord button with icon character
		JButton discordButton = new JButton("\uD83D\uDCAC Discord"); // Speech bubble icon
		discordButton.setFont(FontManager.getRunescapeSmallFont());
		discordButton.setForeground(new Color(88, 101, 242)); // Discord blurple
		discordButton.setPreferredSize(new Dimension(70, 18));
		discordButton.setMargin(new Insets(0, 2, 0, 2));
		discordButton.setToolTipText("Join the ChunkBlazer Discord");
		discordButton.addActionListener(e -> openLink("https://discord.gg/D8DYP45DV8"));
		titleRow.add(discordButton, BorderLayout.EAST);

		headerPanel.add(titleRow);

		// Region and mode on same line
		JPanel infoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		infoRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		infoRow.setAlignmentX(CENTER_ALIGNMENT);

		regionLabel = new JLabel("Unknown (0)");
		regionLabel.setFont(FontManager.getRunescapeSmallFont());
		regionLabel.setForeground(Color.WHITE);

		modeLabel = new JLabel(" | Mode: --");
		modeLabel.setFont(FontManager.getRunescapeSmallFont());
		modeLabel.setForeground(new Color(0, 200, 200));

		infoRow.add(regionLabel);
		infoRow.add(modeLabel);
		headerPanel.add(infoRow);

		return headerPanel;
	}

	private JPanel createStatsSection()
	{
		JPanel statsPanel = new JPanel();
		statsPanel.setLayout(new GridLayout(1, 3, 2, 0));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(FLAME), // Gold border
			new EmptyBorder(2, 3, 2, 3)
		));
		// Fixed size - never changes
		statsPanel.setPreferredSize(new Dimension(PANEL_WIDTH - 10, STATS_HEIGHT));
		statsPanel.setMinimumSize(new Dimension(PANEL_WIDTH - 10, STATS_HEIGHT));
		statsPanel.setMaximumSize(new Dimension(PANEL_WIDTH - 10, STATS_HEIGHT));

		JPanel pointsPanel = createStatBox("Points", "0");
		totalPointsLabel = (JLabel) ((JPanel) pointsPanel.getComponent(0)).getComponent(1);
		statsPanel.add(pointsPanel);

		JPanel chunksPanel = createStatBox("Chunks", "1");
		chunksUnlockedLabel = (JLabel) ((JPanel) chunksPanel.getComponent(0)).getComponent(1);
		statsPanel.add(chunksPanel);

		JPanel tasksPanel = createStatBox("Tasks", "0");
		tasksCompletedLabel = (JLabel) ((JPanel) tasksPanel.getComponent(0)).getComponent(1);
		statsPanel.add(tasksPanel);

		return statsPanel;
	}

	/**
	 * Creates the region-unlock section: a small panel that appears in the side
	 * panel when the player is standing in a locked region, showing the region
	 * name, the unlock cost, the player's current points, and a button to spend
	 * the points. Empty container — populated and shown/hidden by
	 * {@link #updateRegionUnlockSection()}.
	 */
	private JPanel createRegionUnlockSection()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(FLAME), // gold — matches stats border
			new EmptyBorder(6, 6, 6, 6)
		));
		panel.setVisible(false); // shown by updateRegionUnlockSection() only when relevant
		return panel;
	}

	/**
	 * Empty container for the read-only "Unlocked Chunks" list; populated by
	 * {@link #updateUnlockedListSection()}.
	 */
	private JPanel createUnlockedListSection()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 6, 6, 6)
		));
		panel.setVisible(false);
		return panel;
	}

	/**
	 * Refresh the read-only "Unlocked Chunks" list from the plugin's unlocked set.
	 * Display only — not editable (the old editable config field was a free-unlock
	 * cheat). Safe from any thread.
	 */
	public void updateUnlockedListSection()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateUnlockedListSection);
			return;
		}
		if (unlockedListPanel == null)
		{
			return;
		}
		unlockedListPanel.removeAll();

		java.util.List<String> names = plugin.getUnlockedChunkDisplayNames();
		if (!plugin.isLoggedIn())
		{
			unlockedListPanel.setVisible(false);
			if (unlockedListPanel.getParent() != null)
			{
				unlockedListPanel.getParent().revalidate();
				unlockedListPanel.getParent().repaint();
			}
			return;
		}

		// Collapsible header: title + count on the left, a toggle on the right.
		// Collapsed by default so a long unlock list doesn't dominate the panel —
		// the rows only render when expanded.
		JPanel headerRow = new JPanel(new BorderLayout(4, 0));
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 20));

		JLabel title = new JLabel("Unlocked Chunks (" + names.size() + ")");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		headerRow.add(title, BorderLayout.WEST);

		JToggleButton toggle = new JToggleButton();
		setToggleArrow(toggle, unlockedListExpanded);
		toggle.setFont(new Font("Arial", Font.PLAIN, 10));
		toggle.setPreferredSize(new Dimension(30, 18));
		toggle.setMaximumSize(new Dimension(30, 18));
		toggle.setSelected(unlockedListExpanded);
		toggle.setToolTipText("Show/hide your unlocked chunks");
		toggle.addActionListener(e ->
		{
			unlockedListExpanded = toggle.isSelected();
			updateUnlockedListSection();
		});
		headerRow.add(toggle, BorderLayout.EAST);
		unlockedListPanel.add(headerRow);

		if (unlockedListExpanded)
		{
			unlockedListPanel.add(Box.createVerticalStrut(3));
			unlockedListPanel.add(sectionDivider());
			unlockedListPanel.add(Box.createVerticalStrut(5));
			if (names.isEmpty())
			{
				JLabel empty = new JLabel("No chunks unlocked yet.");
				empty.setFont(FontManager.getRunescapeSmallFont());
				empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.setAlignmentX(LEFT_ALIGNMENT);
				unlockedListPanel.add(empty);
			}
			else
			{
				// Compact wrapping "chips" instead of one chunk per row, so a long unlock
				// list stays tight and modern-looking as it grows.
				JPanel chipWrap = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
				chipWrap.setOpaque(false);
				chipWrap.setAlignmentX(LEFT_ALIGNMENT);
				chipWrap.setMaximumSize(new Dimension(CONTENT_WIDTH, Integer.MAX_VALUE));
				for (String n : names)
				{
					chipWrap.add(makeChunkChip(n));
				}
				unlockedListPanel.add(chipWrap);
			}
		}

		unlockedListPanel.setVisible(true);
		unlockedListPanel.revalidate();
		unlockedListPanel.repaint();
		if (unlockedListPanel.getParent() != null)
		{
			unlockedListPanel.getParent().revalidate();
			unlockedListPanel.getParent().repaint();
		}
	}

	/**
	 * Empty container for the "Unlockable Chunks" list; populated and shown/hidden
	 * by {@link #updateUnlockableChunksSection()}.
	 */
	private JPanel createUnlockableChunksSection()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(FLAME),
			new EmptyBorder(6, 6, 6, 6)
		));
		panel.setVisible(false);
		return panel;
	}

	/**
	 * Lists every neighbouring chunk the player can unlock right now, each with an
	 * inline Unlock button. Mirrors the world-map / minimap unlock but in the side
	 * panel, so it's reachable without the map. Safe from any thread.
	 */
	public void updateUnlockableChunksSection()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateUnlockableChunksSection);
			return;
		}
		if (unlockableChunksPanel == null)
		{
			return;
		}

		java.util.List<Integer> neighbors = new java.util.ArrayList<>(plugin.getNeighborRegionIds());
		neighbors.removeIf(r -> plugin.isRegionUnlocked(r));
		java.util.Collections.sort(neighbors);

		unlockableChunksPanel.removeAll();

		if (!plugin.isLoggedIn() || neighbors.isEmpty())
		{
			unlockableChunksPanel.setVisible(false);
			if (unlockableChunksPanel.getParent() != null)
			{
				unlockableChunksPanel.getParent().revalidate();
				unlockableChunksPanel.getParent().repaint();
			}
			return;
		}

		int points = plugin.getTotalPoints();

		JLabel title = new JLabel("Unlockable Chunks");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(FLAME);
		title.setAlignmentX(LEFT_ALIGNMENT);
		unlockableChunksPanel.add(title);
		unlockableChunksPanel.add(Box.createVerticalStrut(4));

		for (int regionId : neighbors)
		{
			String name = plugin.getRegionName(regionId);
			int cost = plugin.getRegionUnlockCost(regionId);
			boolean canAfford = points >= cost;

			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.setAlignmentX(LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(CONTENT_WIDTH, 24));

			JLabel nameLabel = new JLabel(name);
			nameLabel.setFont(FontManager.getRunescapeSmallFont());
			nameLabel.setForeground(Color.WHITE);
			nameLabel.setToolTipText(name + " (" + regionId + ")");
			row.add(nameLabel, BorderLayout.CENTER);

			final int finalRegion = regionId;
			final String finalName = name;
			final int finalCost = cost;
			JButton btn = new JButton(cost + " pts");
			btn.setFont(FontManager.getRunescapeSmallFont());
			btn.setFocusPainted(false);
			btn.setMargin(new Insets(0, 4, 0, 4));
			if (canAfford)
			{
				btn.setBackground(new Color(50, 110, 60));
				btn.setForeground(Color.WHITE);
				btn.addActionListener(e -> showListUnlockConfirm(row, finalRegion, finalName, finalCost));
			}
			else
			{
				btn.setEnabled(false);
				btn.setToolTipText("Need " + (cost - points) + " more pts");
			}
			row.add(btn, BorderLayout.EAST);

			unlockableChunksPanel.add(row);
			unlockableChunksPanel.add(Box.createVerticalStrut(3));
		}

		unlockableChunksPanel.setVisible(true);
		unlockableChunksPanel.revalidate();
		unlockableChunksPanel.repaint();
		if (unlockableChunksPanel.getParent() != null)
		{
			unlockableChunksPanel.getParent().revalidate();
			unlockableChunksPanel.getParent().repaint();
		}
	}

	/**
	 * Inline "Spend N? Yes / No" confirm for an Unlockable Chunks row.
	 */
	private void showListUnlockConfirm(JPanel row, int regionId, String regionName, int cost)
	{
		row.removeAll();

		JLabel prompt = new JLabel("Spend " + cost + "?");
		prompt.setFont(FontManager.getRunescapeSmallFont());
		prompt.setForeground(Color.WHITE);
		row.add(prompt, BorderLayout.WEST);

		JPanel choices = new JPanel(new GridLayout(1, 2, 4, 0));
		choices.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton yes = new JButton("Yes");
		yes.setFont(FontManager.getRunescapeSmallFont());
		yes.setBackground(new Color(50, 110, 60));
		yes.setForeground(Color.WHITE);
		yes.setFocusPainted(false);
		yes.addActionListener(e ->
		{
			plugin.closeChatboxPrompt();
			plugin.unlockRegion(regionId);
			log.info("Player unlocked region {} ({}) via panel list for {} pts", regionId, regionName, cost);
			updateUnlockableChunksSection();
			updateStats();
			updateRegionUnlockSection();
		});

		JButton no = new JButton("No");
		no.setFont(FontManager.getRunescapeSmallFont());
		no.setBackground(new Color(110, 50, 50));
		no.setForeground(Color.WHITE);
		no.setFocusPainted(false);
		no.addActionListener(e -> updateUnlockableChunksSection());

		choices.add(yes);
		choices.add(no);
		row.add(choices, BorderLayout.EAST);

		row.revalidate();
		row.repaint();
	}

	/**
	 * Pin the top-right unlock prompt to a specific chunk (from a world-map
	 * U+click) and refresh, so it shows "Unlock X for Y points? Yes / No" there —
	 * the same prompt the walk-into-chunk flow uses. Safe from any thread.
	 */
	public void promptUnlockForRegion(int regionId)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> promptUnlockForRegion(regionId));
			return;
		}
		mapUnlockRegionId = regionId;
		updateRegionUnlockSection();
	}

	/**
	 * Refresh the region-unlock section based on the player's current region and
	 * point total. Hides itself when the player is in an unlocked region (or
	 * the current region is unknown / undefined). Safe to call from any thread —
	 * marshals to the EDT.
	 */
	public void updateRegionUnlockSection()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateRegionUnlockSection);
			return;
		}
		if (regionUnlockPanel == null)
		{
			return;
		}

		// A world-map U+click pins this top-right prompt to the clicked chunk via
		// mapUnlockRegionId; otherwise it follows the region the player stands in.
		int regionId = mapUnlockRegionId > 0 ? mapUnlockRegionId : plugin.getCurrentRegionId();
		// A region is only purchasable if it's a neighbor of an already-unlocked
		// chunk. The plugin enforces this in unlockRegion() too as a backstop —
		// here we hide the whole prompt for non-neighbors so we don't leak a
		// "Cost: N pts" line for chunks the player can't actually buy
		// (e.g. an Unknown region the player teleported into).
		boolean isNeighbor = regionId > 0 && plugin.getNeighborRegionIds().contains(regionId);
		if (regionId <= 0 || plugin.isRegionUnlocked(regionId) || !isNeighbor)
		{
			// Clicked target resolved (now unlocked / no longer valid) — drop the
			// pin so the prompt reverts to the walk-into-chunk current region.
			mapUnlockRegionId = -1;
			if (regionUnlockPanel.isVisible())
			{
				regionUnlockPanel.setVisible(false);
				regionUnlockPanel.getParent().revalidate();
				regionUnlockPanel.getParent().repaint();
			}
			return;
		}

		String regionName = plugin.getRegionName(regionId);
		int cost = plugin.getRegionUnlockCost(regionId);
		int points = plugin.getTotalPoints();
		boolean canAfford = points >= cost;

		regionUnlockPanel.removeAll();

		JLabel title = new JLabel("🔒 Locked Region");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		regionUnlockPanel.add(title);
		regionUnlockPanel.add(Box.createVerticalStrut(3));
		regionUnlockPanel.add(sectionDivider());
		regionUnlockPanel.add(Box.createVerticalStrut(5));

		WrappingTextLabel nameLabel = new WrappingTextLabel(
			regionName + " (" + regionId + ")",
			FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD),
			Color.WHITE,
			TASK_TEXT_WRAP_WIDTH);
		regionUnlockPanel.add(nameLabel);
		regionUnlockPanel.add(Box.createVerticalStrut(4));

		JLabel costLine = new JLabel("Cost: " + cost + " pts | You have: " + points);
		costLine.setFont(FontManager.getRunescapeSmallFont());
		costLine.setForeground(canAfford ? new Color(150, 255, 150) : new Color(255, 130, 130));
		costLine.setAlignmentX(LEFT_ALIGNMENT);
		regionUnlockPanel.add(costLine);
		regionUnlockPanel.add(Box.createVerticalStrut(6));

		// Two-state button: shows the cost, then on click swaps to "Confirm? Yes/No".
		// Inline confirmation avoids a popup dialog and keeps everything in the panel.
		final int finalRegionId = regionId;
		final String finalRegionName = regionName;
		final int finalCost = cost;
		JPanel buttonRow = new JPanel(new BorderLayout(4, 0));
		buttonRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonRow.setAlignmentX(LEFT_ALIGNMENT);
		buttonRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 28));

		if (!canAfford)
		{
			JButton disabled = new JButton("Need " + (cost - points) + " more pts");
			disabled.setEnabled(false);
			disabled.setFont(FontManager.getRunescapeSmallFont());
			disabled.setFocusPainted(false);
			buttonRow.add(disabled, BorderLayout.CENTER);
		}
		else
		{
			JButton unlockBtn = new JButton("Unlock for " + cost + " pts");
			unlockBtn.setFont(FontManager.getRunescapeBoldFont());
			unlockBtn.setBackground(new Color(50, 110, 60));
			unlockBtn.setForeground(Color.WHITE);
			unlockBtn.setFocusPainted(false);
			unlockBtn.addActionListener(e -> showUnlockConfirm(buttonRow, finalRegionId, finalRegionName, finalCost));
			buttonRow.add(unlockBtn, BorderLayout.CENTER);
		}
		regionUnlockPanel.add(buttonRow);

		regionUnlockPanel.setVisible(true);
		regionUnlockPanel.revalidate();
		regionUnlockPanel.repaint();
		regionUnlockPanel.getParent().revalidate();
		regionUnlockPanel.getParent().repaint();
	}

	/**
	 * Replace the unlock button with an inline "Confirm? Yes / No" pair.
	 * Yes spends the points and unlocks; No reverts to the cost button.
	 */
	private void showUnlockConfirm(JPanel buttonRow, int regionId, String regionName, int cost)
	{
		buttonRow.removeAll();

		JLabel prompt = new JLabel("Spend " + cost + "?");
		prompt.setFont(FontManager.getRunescapeSmallFont());
		prompt.setForeground(Color.WHITE);
		buttonRow.add(prompt, BorderLayout.WEST);

		JPanel choices = new JPanel(new GridLayout(1, 2, 4, 0));
		choices.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton yes = new JButton("Yes");
		yes.setFont(FontManager.getRunescapeBoldFont());
		yes.setBackground(new Color(50, 110, 60));
		yes.setForeground(Color.WHITE);
		yes.setFocusPainted(false);
		yes.addActionListener(e ->
		{
			// Close any open chatbox unlock-prompt for this region first. If we
			// don't, the prompt sits there waiting for a click, and a stray Yes
			// would fire unlockRegion a second time. The plugin also has an
			// idempotency guard inside unlockRegion as a backstop.
			plugin.closeChatboxPrompt();
			plugin.unlockRegion(regionId);
			log.info("Player unlocked region {} ({}) via panel for {} pts", regionId, regionName, cost);
			// Clear any world-map click pin; the region is unlocked now.
			mapUnlockRegionId = -1;
			// updateRegionUnlockSection will be called by updateRegionDisplay /
			// updateStats and hide the section now that the region is unlocked.
			updateRegionUnlockSection();
			updateStats();
		});

		JButton no = new JButton("No");
		no.setFont(FontManager.getRunescapeBoldFont());
		no.setBackground(new Color(110, 50, 50));
		no.setForeground(Color.WHITE);
		no.setFocusPainted(false);
		no.addActionListener(e ->
		{
			// Cancel drops the world-map click pin too.
			mapUnlockRegionId = -1;
			updateRegionUnlockSection();
		});

		choices.add(yes);
		choices.add(no);
		buttonRow.add(choices, BorderLayout.EAST);

		buttonRow.revalidate();
		buttonRow.repaint();
	}

	private JPanel createStatBox(String label, String value)
	{
		JPanel box = new JPanel();
		box.setLayout(new BorderLayout());
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JPanel innerPanel = new JPanel();
		innerPanel.setLayout(new BoxLayout(innerPanel, BoxLayout.Y_AXIS));
		innerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel labelText = new JLabel(label.toUpperCase());
		labelText.setFont(new Font("Arial", Font.PLAIN, 9));
		labelText.setForeground(new Color(150, 150, 150)); // muted grey
		labelText.setAlignmentX(CENTER_ALIGNMENT);

		JLabel valueText = new JLabel(value);
		valueText.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));
		valueText.setForeground(FLAME); // gold
		valueText.setAlignmentX(CENTER_ALIGNMENT);

		innerPanel.add(labelText);
		innerPanel.add(valueText);
		box.add(innerPanel, BorderLayout.CENTER);

		return box;
	}

	private JPanel createModeSelectionSection()
	{
		JPanel modePanel = new JPanel();
		modePanel.setLayout(new BoxLayout(modePanel, BoxLayout.Y_AXIS));
		modePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		modePanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(10, 10, 10, 10)
		));

		// Section title
		JLabel sectionTitle = new JLabel("Select Game Mode");
		sectionTitle.setFont(FontManager.getRunescapeBoldFont());
		sectionTitle.setForeground(Color.WHITE);
		sectionTitle.setAlignmentX(LEFT_ALIGNMENT);
		modePanel.add(sectionTitle);
		modePanel.add(Box.createVerticalStrut(3));
		modePanel.add(sectionDivider());
		modePanel.add(Box.createVerticalStrut(5));

		// Warning text
		JLabel warningLabel = new JLabel("<html><i>This choice is permanent for this account!</i></html>");
		warningLabel.setFont(FontManager.getRunescapeSmallFont());
		warningLabel.setForeground(Color.YELLOW);
		warningLabel.setAlignmentX(LEFT_ALIGNMENT);
		modePanel.add(warningLabel);
		modePanel.add(Box.createVerticalStrut(10));

		// Radio buttons
		ButtonGroup modeGroup = new ButtonGroup();

		casualRadio = new JRadioButton("Casual Mode");
		casualRadio.setToolTipText("Any account, no leaderboard tracking");
		casualRadio.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		casualRadio.setForeground(Color.WHITE);
		casualRadio.setSelected(true);
		casualRadio.setAlignmentX(LEFT_ALIGNMENT);
		modeGroup.add(casualRadio);
		modePanel.add(casualRadio);

		JLabel casualDesc = new JLabel("   Any account, no leaderboard");
		casualDesc.setFont(FontManager.getRunescapeSmallFont());
		casualDesc.setForeground(Color.LIGHT_GRAY);
		casualDesc.setAlignmentX(LEFT_ALIGNMENT);
		modePanel.add(casualDesc);
		modePanel.add(Box.createVerticalStrut(5));

		nuzlockeRadio = new JRadioButton("Full Nuzlocke");
		nuzlockeRadio.setToolTipText("Level 3 start, Lumbridge, leaderboard eligible");
		nuzlockeRadio.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nuzlockeRadio.setForeground(Color.WHITE);
		nuzlockeRadio.setAlignmentX(LEFT_ALIGNMENT);
		modeGroup.add(nuzlockeRadio);
		modePanel.add(nuzlockeRadio);

		JLabel nuzlockeDesc = new JLabel("   Level 3 start, leaderboard eligible");
		nuzlockeDesc.setFont(FontManager.getRunescapeSmallFont());
		nuzlockeDesc.setForeground(Color.LIGHT_GRAY);
		nuzlockeDesc.setAlignmentX(LEFT_ALIGNMENT);
		modePanel.add(nuzlockeDesc);
		modePanel.add(Box.createVerticalStrut(10));

		// Confirm button
		JButton confirmButton = new JButton("Confirm Mode");
		confirmButton.setAlignmentX(LEFT_ALIGNMENT);
		confirmButton.addActionListener(e -> onConfirmMode());
		modePanel.add(confirmButton);

		return modePanel;
	}

	/**
	 * The read-only card shown once a game mode is locked, replacing the
	 * selector. The mode name + colour are filled in by updateModeDisplay().
	 */
	private JPanel createLockedModeSection()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(10, 10, 10, 10)
		));

		JLabel title = new JLabel("Game Mode");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(title);
		panel.add(Box.createVerticalStrut(5));

		lockedModeValueLabel = new JLabel("Casual");
		lockedModeValueLabel.setFont(FontManager.getRunescapeBoldFont());
		lockedModeValueLabel.setForeground(new Color(100, 200, 100));
		lockedModeValueLabel.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(lockedModeValueLabel);

		JLabel lockedNote = new JLabel("Locked for this account");
		lockedNote.setFont(FontManager.getRunescapeSmallFont());
		lockedNote.setForeground(Color.LIGHT_GRAY);
		lockedNote.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(lockedNote);

		return panel;
	}

	/**
	 * Prompt shown while the player is logged out, in place of the gameplay
	 * sections (which are account-specific and can't do anything pre-login).
	 */
	private JPanel createLoggedOutSection()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(15, 10, 15, 10)
		));

		JLabel title = new JLabel("Not logged in");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(title);
		panel.add(Box.createVerticalStrut(5));

		JLabel msg = new JLabel("<html>Log into Old School RuneScape to start playing ChunkBlazer.</html>");
		msg.setFont(FontManager.getRunescapeSmallFont());
		msg.setForeground(Color.LIGHT_GRAY);
		msg.setAlignmentX(LEFT_ALIGNMENT);
		panel.add(msg);

		return panel;
	}

	private JPanel createCurrentTaskSection()
	{
		JPanel taskPanel = new JPanel();
		taskPanel.setLayout(new BoxLayout(taskPanel, BoxLayout.Y_AXIS));
		taskPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		taskPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(100, 180, 100)),
			new EmptyBorder(6, 6, 6, 6)
		));
		taskPanel.setAlignmentX(LEFT_ALIGNMENT);

		// === SELECTED TASK HIGHLIGHT BOX ===
		selectedTaskPanel = new JPanel();
		selectedTaskPanel.setLayout(new BoxLayout(selectedTaskPanel, BoxLayout.Y_AXIS));
		selectedTaskPanel.setBackground(new Color(60, 80, 60));
		selectedTaskPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(FLAME, 2), // Gold border
			new EmptyBorder(6, 6, 6, 6)
		));
		selectedTaskPanel.setAlignmentX(LEFT_ALIGNMENT);
		selectedTaskPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 135));
		selectedTaskPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 135));

		JLabel selectedTitle = new JLabel("\u2605 SELECTED TASK \u2605"); // Star symbols
		selectedTitle.setFont(FontManager.getRunescapeBoldFont());
		selectedTitle.setForeground(FLAME); // Gold color
		selectedTitle.setAlignmentX(LEFT_ALIGNMENT);
		selectedTaskPanel.add(selectedTitle);

		JLabel selectedTaskName = new JLabel("Click a task below to select");
		selectedTaskName.setFont(FontManager.getRunescapeSmallFont());
		selectedTaskName.setForeground(Color.LIGHT_GRAY);
		selectedTaskName.setAlignmentX(LEFT_ALIGNMENT);
		selectedTaskName.setName("selectedTaskName");
		selectedTaskPanel.add(selectedTaskName);

		selectedTaskPanel.setVisible(false); // Hidden until a task is selected
		taskPanel.add(selectedTaskPanel);
		taskPanel.add(Box.createVerticalStrut(4));

		// === HEADER ROW WITH TOGGLE ===
		JPanel headerRow = new JPanel(new BorderLayout(5, 0));
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 22));
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 22));

		activeTasksSectionTitle = new JLabel("Active Tasks");
		activeTasksSectionTitle.setFont(FontManager.getRunescapeBoldFont());
		activeTasksSectionTitle.setForeground(new Color(100, 255, 100));
		headerRow.add(activeTasksSectionTitle, BorderLayout.WEST);

		activeTasksToggle = new JToggleButton();
		activeTasksToggle.setSelected(true); // Start expanded
		setToggleArrow(activeTasksToggle, true);
		activeTasksToggle.setFont(new Font("Arial", Font.PLAIN, 10));
		activeTasksToggle.setPreferredSize(new Dimension(30, 20));
		activeTasksToggle.setMaximumSize(new Dimension(30, 20));
		activeTasksToggle.setToolTipText("Collapse/expand active tasks");
		activeTasksToggle.addActionListener(e ->
		{
			activeTasksExpanded = activeTasksToggle.isSelected();
			setToggleArrow(activeTasksToggle, activeTasksExpanded);
			updateActiveTasksVisibility();
		});
		headerRow.add(activeTasksToggle, BorderLayout.EAST);

		taskPanel.add(headerRow);
		taskPanel.add(sectionDivider());
		taskPanel.add(Box.createVerticalStrut(4));

		// === FILTER PANEL (collapsible) ===
		activeTasksFilterPanel = new JPanel();
		activeTasksFilterPanel.setLayout(new BoxLayout(activeTasksFilterPanel, BoxLayout.Y_AXIS));
		activeTasksFilterPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activeTasksFilterPanel.setAlignmentX(LEFT_ALIGNMENT);

		// Search field
		JPanel searchRow = new JPanel(new BorderLayout(5, 0));
		searchRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchRow.setAlignmentX(LEFT_ALIGNMENT);
		searchRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 22));
		searchRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 22));

		JLabel searchLabel = new JLabel("Search:");
		searchLabel.setFont(FontManager.getRunescapeSmallFont());
		searchLabel.setForeground(Color.LIGHT_GRAY);
		searchRow.add(searchLabel, BorderLayout.WEST);

		activeTasksSearchField = new JTextField();
		activeTasksSearchField.setToolTipText("Search tasks by name");
		activeTasksSearchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				onActiveTasksFilterChanged();
			}
			@Override
			public void removeUpdate(DocumentEvent e)
			{
				onActiveTasksFilterChanged();
			}
			@Override
			public void changedUpdate(DocumentEvent e)
			{
				onActiveTasksFilterChanged();
			}
		});
		searchRow.add(activeTasksSearchField, BorderLayout.CENTER);

		activeTasksFilterPanel.add(searchRow);
		activeTasksFilterPanel.add(Box.createVerticalStrut(4));

		// Area filter (broadest cut — Misthalin / Asgarnia / Zeah / ...).
		// On its own row above Category+Region so the dropdown has room for full area names.
		JPanel areaRow = new JPanel(new BorderLayout(2, 0));
		areaRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		areaRow.setAlignmentX(LEFT_ALIGNMENT);
		areaRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 40));
		areaRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 40));

		JLabel areaLabel = new JLabel("Area:");
		areaLabel.setFont(FontManager.getRunescapeSmallFont());
		areaLabel.setForeground(Color.LIGHT_GRAY);
		areaRow.add(areaLabel, BorderLayout.NORTH);

		activeTasksAreaCombo = new JComboBox<>(new String[]{"All"});
		activeTasksAreaCombo.setFont(FontManager.getRunescapeSmallFont());
		activeTasksAreaCombo.addActionListener(e ->
		{
			if (!isRefreshingActiveFilters)
			{
				activeTasksSelectedArea = (String) activeTasksAreaCombo.getSelectedItem();
				if (activeTasksSelectedArea == null) activeTasksSelectedArea = "All";
				updateActiveTasksDisplay();
			}
		});
		areaRow.add(activeTasksAreaCombo, BorderLayout.CENTER);

		activeTasksFilterPanel.add(areaRow);
		activeTasksFilterPanel.add(Box.createVerticalStrut(4));

		// Category and Region filters
		JPanel filterRow = new JPanel(new GridLayout(1, 2, 4, 0));
		filterRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		filterRow.setAlignmentX(LEFT_ALIGNMENT);
		filterRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 40));
		filterRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 40));

		// Category filter
		JPanel categoryPanel = new JPanel(new BorderLayout(2, 0));
		categoryPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel catLabel = new JLabel("Category:");
		catLabel.setFont(FontManager.getRunescapeSmallFont());
		catLabel.setForeground(Color.LIGHT_GRAY);
		categoryPanel.add(catLabel, BorderLayout.NORTH);

		activeTasksCategoryCombo = new JComboBox<>(new String[]{"All"});
		activeTasksCategoryCombo.setFont(FontManager.getRunescapeSmallFont());
		activeTasksCategoryCombo.addActionListener(e ->
		{
			if (!isRefreshingActiveFilters)
			{
				activeTasksSelectedCategory = (String) activeTasksCategoryCombo.getSelectedItem();
				if (activeTasksSelectedCategory == null) activeTasksSelectedCategory = "All";
				updateActiveTasksDisplay();
			}
		});
		categoryPanel.add(activeTasksCategoryCombo, BorderLayout.CENTER);

		filterRow.add(categoryPanel);

		// Region filter
		JPanel regionPanel = new JPanel(new BorderLayout(2, 0));
		regionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel regLabel = new JLabel("Chunk:");
		regLabel.setFont(FontManager.getRunescapeSmallFont());
		regLabel.setForeground(Color.LIGHT_GRAY);
		regionPanel.add(regLabel, BorderLayout.NORTH);

		activeTasksRegionCombo = new JComboBox<>(new String[]{"All"});
		activeTasksRegionCombo.setFont(FontManager.getRunescapeSmallFont());
		activeTasksRegionCombo.addActionListener(e ->
		{
			if (!isRefreshingActiveFilters)
			{
				activeTasksSelectedRegion = (String) activeTasksRegionCombo.getSelectedItem();
				if (activeTasksSelectedRegion == null) activeTasksSelectedRegion = "All";
				updateActiveTasksDisplay();
			}
		});
		regionPanel.add(activeTasksRegionCombo, BorderLayout.CENTER);

		filterRow.add(regionPanel);

		activeTasksFilterPanel.add(filterRow);
		activeTasksFilterPanel.add(Box.createVerticalStrut(4));

		taskPanel.add(activeTasksFilterPanel);

		// === SCROLLABLE TASK LIST ===
		activeTasksContentPanel = new JPanel();
		activeTasksContentPanel.setLayout(new BoxLayout(activeTasksContentPanel, BoxLayout.Y_AXIS));
		activeTasksContentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		activeTasksScrollPane = new JScrollPane(activeTasksContentPanel);
		activeTasksScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		activeTasksScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		activeTasksScrollPane.setBorder(null);
		activeTasksScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activeTasksScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activeTasksScrollPane.setAlignmentX(LEFT_ALIGNMENT);

		// Placeholder
		taskNameLabel = new JLabel("Loading tasks...");
		taskNameLabel.setFont(FontManager.getRunescapeSmallFont());
		taskNameLabel.setForeground(Color.LIGHT_GRAY);
		taskNameLabel.setAlignmentX(LEFT_ALIGNMENT);
		activeTasksContentPanel.add(taskNameLabel);

		taskPanel.add(activeTasksScrollPane);

		// === COLLAPSED LABEL ===
		activeTasksCollapsedLabel = new JLabel("Click to view tasks");
		activeTasksCollapsedLabel.setFont(FontManager.getRunescapeSmallFont());
		activeTasksCollapsedLabel.setForeground(Color.GRAY);
		activeTasksCollapsedLabel.setAlignmentX(LEFT_ALIGNMENT);
		activeTasksCollapsedLabel.setVisible(false); // Hidden when expanded
		taskPanel.add(activeTasksCollapsedLabel);

		// Hidden labels for backward compatibility
		taskCategoryLabel = new JLabel("");
		taskPointsLabel = new JLabel("");
		taskProgressLabel = new JLabel("");

		return taskPanel;
	}

	private void updateActiveTasksVisibility()
	{
		activeTasksFilterPanel.setVisible(activeTasksExpanded);
		activeTasksScrollPane.setVisible(activeTasksExpanded);
		activeTasksCollapsedLabel.setVisible(!activeTasksExpanded);

		if (activeTasksExpanded)
		{
			refreshActiveTasksFilters();
			updateActiveTasksDisplay();

			// Set size to show 5 items
			int height = MAX_ACTIVE_TASKS_HEIGHT;
			activeTasksScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
			activeTasksScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
			activeTasksScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
		}
		else
		{
			// Collapsed
			activeTasksScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, 0));
			activeTasksScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, 0));
			activeTasksScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, 0));
		}

		// Revalidate hierarchy
		currentTaskPanel.revalidate();
		currentTaskPanel.repaint();

		if (currentTaskPanel.getParent() != null)
		{
			currentTaskPanel.getParent().revalidate();
			currentTaskPanel.getParent().repaint();
		}
	}

	private void onActiveTasksFilterChanged()
	{
		activeTasksSearchText = activeTasksSearchField.getText().toLowerCase().trim();
		updateActiveTasksDisplay();
	}

	/**
	 * Refresh the category and region filter dropdowns for active tasks.
	 */
	private void refreshActiveTasksFilters()
	{
		isRefreshingActiveFilters = true;
		try
		{
			// Populate categories from all active tasks
			Set<String> categories = plugin.getActiveTasks().stream()
				.map(NuzlockeTask::getCategory)
				.filter(c -> c != null && !c.isEmpty())
				.collect(Collectors.toCollection(java.util.TreeSet::new));

			String currentCategory = activeTasksSelectedCategory;
			activeTasksCategoryCombo.removeAllItems();
			activeTasksCategoryCombo.addItem("All");
			for (String cat : categories)
			{
				activeTasksCategoryCombo.addItem(cat);
			}
			if (currentCategory != null && categories.contains(currentCategory))
			{
				activeTasksCategoryCombo.setSelectedItem(currentCategory);
			}
			else
			{
				activeTasksCategoryCombo.setSelectedItem("All");
				activeTasksSelectedCategory = "All";
			}

			// Populate regions from active tasks
			Set<String> regions = plugin.getActiveTaskRegions();
			String currentRegion = activeTasksSelectedRegion;
			activeTasksRegionCombo.removeAllItems();
			activeTasksRegionCombo.addItem("All");
			for (String reg : regions)
			{
				activeTasksRegionCombo.addItem(reg);
			}
			if (currentRegion != null && regions.contains(currentRegion))
			{
				activeTasksRegionCombo.setSelectedItem(currentRegion);
			}
			else
			{
				activeTasksRegionCombo.setSelectedItem("All");
				activeTasksSelectedRegion = "All";
			}

			// Populate areas from active tasks
			Set<String> areas = plugin.getActiveTaskAreas();
			String currentArea = activeTasksSelectedArea;
			activeTasksAreaCombo.removeAllItems();
			activeTasksAreaCombo.addItem("All");
			for (String a : areas)
			{
				activeTasksAreaCombo.addItem(a);
			}
			if (currentArea != null && areas.contains(currentArea))
			{
				activeTasksAreaCombo.setSelectedItem(currentArea);
			}
			else
			{
				activeTasksAreaCombo.setSelectedItem("All");
				activeTasksSelectedArea = "All";
			}
		}
		finally
		{
			isRefreshingActiveFilters = false;
		}
	}

	/**
	 * Select a task and highlight it in the Selected Task box.
	 */
	public void selectTask(NuzlockeTask task)
	{
		this.selectedTask = task;
		updateSelectedTaskDisplay();
		updateActiveTasksDisplay(); // Refresh to show selection highlight
	}

	/**
	 * Clear the selected task if it matches the given task (e.g., when task is completed).
	 */
	public void clearSelectedTaskIfMatch(NuzlockeTask task)
	{
		if (selectedTask != null && task != null &&
			selectedTask.getTaskId() != null &&
			selectedTask.getTaskId().equals(task.getTaskId()))
		{
			selectedTask = null;
			updateSelectedTaskDisplay();
			// Also refresh the active task list — otherwise a row that was just completed
			// keeps its gold "selected" border/star until something else triggers a rebuild.
			updateActiveTasksDisplay();
		}
	}

	/**
	 * Clear the selected task unconditionally.
	 */
	public void clearSelectedTask()
	{
		selectedTask = null;
		updateSelectedTaskDisplay();
	}

	private void updateSelectedTaskDisplay()
	{
		if (selectedTask == null)
		{
			selectedTaskPanel.setVisible(false);
			// Force the parent layout to recompute so the gap left by the now-hidden panel
			// closes immediately instead of leaving a ghost slot until the next interaction.
			java.awt.Container parent = selectedTaskPanel.getParent();
			if (parent != null)
			{
				parent.revalidate();
				parent.repaint();
			}
			return;
		}

		selectedTaskPanel.setVisible(true);

		// Update the task name label
		for (java.awt.Component comp : selectedTaskPanel.getComponents())
		{
			if (comp instanceof JLabel && "selectedTaskName".equals(comp.getName()))
			{
				JLabel nameLabel = (JLabel) comp;
				nameLabel.setText("<html><b>" + selectedTask.getName() + "</b></html>");
				nameLabel.setForeground(Color.WHITE);
			}
		}

		// Rebuild the selected task panel with full details
		selectedTaskPanel.removeAll();

		// Header row: title on the left, \u00d7 dismiss on the right.
		JPanel headerRow = new JPanel(new BorderLayout(5, 0));
		headerRow.setBackground(selectedTaskPanel.getBackground());
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 22));

		JLabel selectedTitle = new JLabel("\u2605 SELECTED TASK \u2605");
		selectedTitle.setFont(FontManager.getRunescapeBoldFont());
		selectedTitle.setForeground(FLAME);
		headerRow.add(selectedTitle, BorderLayout.WEST);

		// Beveled close button (painted xIcon); the X brightens to red on hover.
		final Color dismissIdleColor = new Color(180, 180, 180);
		final Color dismissHoverColor = new Color(255, 120, 120);
		JButton dismissButton = new JButton(xIcon(dismissIdleColor));
		dismissButton.setContentAreaFilled(false);
		dismissButton.setBorderPainted(false);
		dismissButton.setFocusPainted(false);
		dismissButton.setOpaque(false);
		dismissButton.setMargin(new Insets(0, 0, 0, 0));
		dismissButton.setPreferredSize(new Dimension(18, 18));
		dismissButton.setMaximumSize(new Dimension(18, 18));
		dismissButton.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		dismissButton.setToolTipText("Deselect this task");
		dismissButton.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				dismissButton.setIcon(xIcon(dismissHoverColor));
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				dismissButton.setIcon(xIcon(dismissIdleColor));
			}
		});
		dismissButton.addActionListener(e ->
		{
			clearSelectedTask();
			updateActiveTasksDisplay();
		});
		headerRow.add(dismissButton, BorderLayout.EAST);

		selectedTaskPanel.add(headerRow);
		selectedTaskPanel.add(Box.createVerticalStrut(4));

		// Task name (wrapped via WrappingTextLabel).
		WrappingTextLabel nameLabel = new WrappingTextLabel(
			selectedTask.getName(),
			FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD),
			Color.WHITE,
			TASK_TEXT_WRAP_WIDTH);
		selectedTaskPanel.add(nameLabel);

		// Category and points
		String info = selectedTask.getCategory() + " | " + selectedTask.getBasePoints() + " pts";
		if (selectedTask.getLevelRequirement() > 1)
		{
			info += " | L" + selectedTask.getLevelRequirement();
		}
		JLabel infoLabel = new JLabel(info);
		infoLabel.setFont(FontManager.getRunescapeSmallFont());
		infoLabel.setForeground(new Color(255, 200, 100));
		infoLabel.setAlignmentX(LEFT_ALIGNMENT);
		selectedTaskPanel.add(infoLabel);

		// Region + Area rows — gives the player full context for the highlighted task.
		// getTaskRegionName already includes the numeric ID in "ChunkName (id)" form.
		String selRegionName = plugin.getTaskRegionName(selectedTask);
		String regionRow = (selRegionName != null && !selRegionName.isEmpty())
			? "Chunk: " + selRegionName
			: "Chunk: unknown";
		JLabel selRegionLabel = new JLabel(regionRow);
		selRegionLabel.setFont(FontManager.getRunescapeSmallFont());
		selRegionLabel.setForeground(new Color(140, 200, 230));
		selRegionLabel.setAlignmentX(LEFT_ALIGNMENT);
		selectedTaskPanel.add(selRegionLabel);

		String selArea = plugin.getTaskArea(selectedTask);
		if (selArea != null && !selArea.isEmpty())
		{
			JLabel selAreaLabel = new JLabel("Area: " + selArea);
			selAreaLabel.setFont(FontManager.getRunescapeSmallFont());
			selAreaLabel.setForeground(new Color(180, 180, 220));
			selAreaLabel.setAlignmentX(LEFT_ALIGNMENT);
			selectedTaskPanel.add(selAreaLabel);
		}

		// Progress
		int progress = selectedTask.getCurrentProgress();
		int target = selectedTask.getTargetQuantity();
		float pct = target > 0 ? (float) progress / target : 0;
		pct = Math.min(pct, 1.0f);

		JPanel progressRow = new JPanel(new BorderLayout(4, 0));
		progressRow.setBackground(new Color(60, 80, 60));
		progressRow.setAlignmentX(LEFT_ALIGNMENT);
		progressRow.setPreferredSize(new Dimension(CONTENT_WIDTH - 30, 14));
		progressRow.setMaximumSize(new Dimension(CONTENT_WIDTH - 30, 14));

		// Paints fill against its actual width — see createPercentageProgressBar
		// for the long story on why we don't use BorderLayout.WEST + a fixed-size
		// child for this anymore.
		JPanel progressBar = createPercentageProgressBar(
			pct,
			FLAME,                // gold fill
			FLAME,                // gold border
			12);
		progressRow.add(progressBar, BorderLayout.CENTER);

		JLabel progressText = new JLabel(progress + "/" + target);
		progressText.setFont(FontManager.getRunescapeSmallFont());
		progressText.setForeground(Color.WHITE);
		progressRow.add(progressText, BorderLayout.EAST);

		selectedTaskPanel.add(Box.createVerticalStrut(4));
		selectedTaskPanel.add(progressRow);

		selectedTaskPanel.revalidate();
		selectedTaskPanel.repaint();
	}

	private JPanel createDevControlsSection()
	{
		JPanel controlsPanel = new JPanel();
		controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
		controlsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		controlsPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(100, 100, 150)),
			new EmptyBorder(6, 6, 6, 6)
		));

		// Header row with toggle button
		JPanel headerRow = new JPanel(new BorderLayout(5, 0));
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		JLabel sectionTitle = new JLabel("Dev Controls");
		sectionTitle.setFont(FontManager.getRunescapeBoldFont());
		sectionTitle.setForeground(new Color(150, 150, 255));
		headerRow.add(sectionTitle, BorderLayout.WEST);

		devControlsToggle = new JToggleButton();
		setToggleArrow(devControlsToggle, devControlsExpanded);
		devControlsToggle.setFont(new Font("Arial", Font.PLAIN, 10));
		devControlsToggle.setPreferredSize(new Dimension(30, 20));
		devControlsToggle.setMaximumSize(new Dimension(30, 20));
		devControlsToggle.setToolTipText("Expand/collapse dev controls");
		devControlsToggle.addActionListener(e ->
		{
			devControlsExpanded = devControlsToggle.isSelected();
			setToggleArrow(devControlsToggle, devControlsExpanded);
			updateDevControlsVisibility();
		});
		headerRow.add(devControlsToggle, BorderLayout.EAST);

		controlsPanel.add(headerRow);
		controlsPanel.add(Box.createVerticalStrut(5));

		// Collapsible content panel
		devControlsContentPanel = new JPanel();
		devControlsContentPanel.setLayout(new BoxLayout(devControlsContentPanel, BoxLayout.Y_AXIS));
		devControlsContentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		devControlsContentPanel.setAlignmentX(LEFT_ALIGNMENT);
		devControlsContentPanel.setVisible(false); // Hidden by default

		// Task buttons row
		JPanel taskButtonsPanel = new JPanel(new GridLayout(1, 2, 4, 0));
		taskButtonsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		taskButtonsPanel.setAlignmentX(LEFT_ALIGNMENT);
		taskButtonsPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 26));
		taskButtonsPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 26));

		JButton completeButton = new JButton("Complete");
		completeButton.setFont(FontManager.getRunescapeSmallFont());
		completeButton.setToolTipText("Complete current task");
		completeButton.addActionListener(e -> onCompleteTask());
		taskButtonsPanel.add(completeButton);

		JButton rerollButton = new JButton("Reroll");
		rerollButton.setFont(FontManager.getRunescapeSmallFont());
		rerollButton.setToolTipText("Reroll current task");
		rerollButton.addActionListener(e -> onRerollTask());
		taskButtonsPanel.add(rerollButton);

		devControlsContentPanel.add(taskButtonsPanel);
		devControlsContentPanel.add(Box.createVerticalStrut(4));

		// Points button row
		JPanel pointsPanel = new JPanel(new GridLayout(1, 2, 4, 0));
		pointsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		pointsPanel.setAlignmentX(LEFT_ALIGNMENT);
		pointsPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 26));
		pointsPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 26));

		JButton add10PointsButton = new JButton("+10 pts");
		add10PointsButton.setFont(FontManager.getRunescapeSmallFont());
		add10PointsButton.setToolTipText("Add 10 points");
		add10PointsButton.addActionListener(e ->
		{
			plugin.devAddPoints(10);
			updateStats();
		});
		pointsPanel.add(add10PointsButton);

		JButton add100PointsButton = new JButton("+100 pts");
		add100PointsButton.setFont(FontManager.getRunescapeSmallFont());
		add100PointsButton.setToolTipText("Add 100 points");
		add100PointsButton.addActionListener(e ->
		{
			plugin.devAddPoints(100);
			updateStats();
		});
		pointsPanel.add(add100PointsButton);

		devControlsContentPanel.add(pointsPanel);
		devControlsContentPanel.add(Box.createVerticalStrut(4));

		// Reset buttons row
		JPanel resetPanel = new JPanel(new GridLayout(1, 2, 4, 0));
		resetPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		resetPanel.setAlignmentX(LEFT_ALIGNMENT);
		resetPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 26));
		resetPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 26));

		JButton resetTasksButton = new JButton("Rst Tasks");
		resetTasksButton.setFont(FontManager.getRunescapeSmallFont());
		resetTasksButton.setForeground(new Color(255, 100, 100));
		resetTasksButton.setToolTipText("Reset task progress");
		resetTasksButton.addActionListener(e -> onResetTasks());
		resetPanel.add(resetTasksButton);

		JButton resetAllButton = new JButton("Rst All");
		resetAllButton.setFont(FontManager.getRunescapeSmallFont());
		resetAllButton.setForeground(new Color(255, 50, 50));
		resetAllButton.setToolTipText("Reset everything");
		resetAllButton.addActionListener(e -> onResetAll());
		resetPanel.add(resetAllButton);

		devControlsContentPanel.add(resetPanel);
		devControlsContentPanel.add(Box.createVerticalStrut(4));

		// Chunk button row — force-unlock the current region with no adjacency check
		// or point cost. unlockRegionFree() also rolls tasks for the region so the dev
		// can immediately test task assignment on the newly-unlocked chunk.
		JPanel chunkPanel = new JPanel(new GridLayout(1, 1, 4, 0));
		chunkPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		chunkPanel.setAlignmentX(LEFT_ALIGNMENT);
		chunkPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 26));
		chunkPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 26));

		JButton forceUnlockButton = new JButton("Force Unlock Chunk");
		forceUnlockButton.setFont(FontManager.getRunescapeSmallFont());
		forceUnlockButton.setForeground(new Color(255, 200, 100));
		forceUnlockButton.setToolTipText("Unlock the chunk you're standing on with no adjacency check or point cost. Rolls tasks for the region.");
		forceUnlockButton.addActionListener(e ->
		{
			int regionId = plugin.getCurrentRegionId();
			if (regionId <= 0)
			{
				log.info("Dev: Force Unlock pressed but current region is unknown (id={})", regionId);
				return;
			}
			log.info("Dev: Force-unlocking region {} (no adjacency check)", regionId);
			plugin.unlockRegionFree(regionId);
			updateStats();
			updateRegionDisplay();
		});
		chunkPanel.add(forceUnlockButton);

		devControlsContentPanel.add(chunkPanel);
		devControlsContentPanel.add(Box.createVerticalStrut(4));

		// Dev unlock-by-ID — replaces the old editable "Unlocked Regions" config
		// field. Type one or more region IDs (comma-separated) and click Unlock to
		// force-unlock them (no cost / no adjacency) for testing.
		JPanel unlockByIdPanel = new JPanel(new BorderLayout(4, 0));
		unlockByIdPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		unlockByIdPanel.setAlignmentX(LEFT_ALIGNMENT);
		unlockByIdPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 26));
		unlockByIdPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 26));

		JTextField unlockIdField = new JTextField();
		unlockIdField.setFont(FontManager.getRunescapeSmallFont());
		unlockIdField.setToolTipText("Region ID(s), comma-separated, e.g. 12594,12595");
		unlockByIdPanel.add(unlockIdField, BorderLayout.CENTER);

		JButton unlockIdButton = new JButton("Unlock");
		unlockIdButton.setFont(FontManager.getRunescapeSmallFont());
		unlockIdButton.setForeground(new Color(255, 200, 100));
		unlockIdButton.setToolTipText("Force-unlock the entered region ID(s)");
		unlockIdButton.addActionListener(e ->
		{
			plugin.devUnlockRegions(unlockIdField.getText());
			unlockIdField.setText("");
			updateStats();
			updateRegionDisplay();
		});
		unlockByIdPanel.add(unlockIdButton, BorderLayout.EAST);

		devControlsContentPanel.add(unlockByIdPanel);
		devControlsContentPanel.add(Box.createVerticalStrut(4));

		// Debug button row
		JPanel debugPanel = new JPanel(new GridLayout(1, 2, 4, 0));
		debugPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		debugPanel.setAlignmentX(LEFT_ALIGNMENT);
		debugPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 26));
		debugPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 26));

		JButton debugButton = new JButton("Debug");
		debugButton.setFont(FontManager.getRunescapeSmallFont());
		debugButton.setForeground(new Color(100, 150, 255));
		debugButton.setToolTipText("Show debug info");
		debugButton.addActionListener(e -> onShowDebugInfo());
		debugPanel.add(debugButton);

		JButton logButton = new JButton("Logs");
		logButton.setFont(FontManager.getRunescapeSmallFont());
		logButton.setForeground(new Color(100, 150, 255));
		logButton.setToolTipText("Open log file");
		logButton.addActionListener(e -> openLogFile());
		debugPanel.add(logButton);

		devControlsContentPanel.add(debugPanel);
		devControlsContentPanel.add(Box.createVerticalStrut(4));

		// Varbit/VarPlayer dump buttons — each appends a labelled snapshot to
		// C:\Chunkblazer\VarBit_VarPlayer.txt with a timestamped header. Used
		// to reverse-engineer which varbit/varplayer holds a given prayer/spell
		// state when authoring new VARBIT_CHECK tasks. Two buttons share a row.
		JPanel varDumpPanel = new JPanel(new GridLayout(1, 2, 4, 0));
		varDumpPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		varDumpPanel.setAlignmentX(LEFT_ALIGNMENT);
		varDumpPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 26));
		varDumpPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 26));

		JButton prayerDumpButton = new JButton("Prayer Vars");
		prayerDumpButton.setFont(FontManager.getRunescapeSmallFont());
		prayerDumpButton.setForeground(new Color(100, 220, 200));
		prayerDumpButton.setToolTipText("Append current PRAYER-related VarBit/VarPlayer values to C:\\Chunkblazer\\VarBit_VarPlayer.txt");
		prayerDumpButton.addActionListener(e ->
		{
			log.info("Dev: Prayer Vars button pressed — dumping prayer vars");
			plugin.dumpPrayerVars();
		});
		varDumpPanel.add(prayerDumpButton);

		JButton magicDumpButton = new JButton("Magic Vars");
		magicDumpButton.setFont(FontManager.getRunescapeSmallFont());
		magicDumpButton.setForeground(new Color(180, 140, 240));
		magicDumpButton.setToolTipText("Append current SPELL/MAGIC/AUTOCAST/SPELLBOOK VarBit/VarPlayer values to C:\\Chunkblazer\\VarBit_VarPlayer.txt (excludes PRAYER_*)");
		magicDumpButton.addActionListener(e ->
		{
			log.info("Dev: Magic Vars button pressed — dumping magic vars");
			plugin.dumpMagicVars();
		});
		varDumpPanel.add(magicDumpButton);

		devControlsContentPanel.add(varDumpPanel);

		controlsPanel.add(devControlsContentPanel);

		return controlsPanel;
	}

	private void updateDevControlsVisibility()
	{
		devControlsContentPanel.setVisible(devControlsExpanded);

		// Revalidate the panel hierarchy
		devControlsPanel.revalidate();
		devControlsPanel.repaint();

		if (devControlsPanel.getParent() != null)
		{
			devControlsPanel.getParent().revalidate();
			devControlsPanel.getParent().repaint();
		}
	}

	private void onResetTasks()
	{
		int confirm = JOptionPane.showConfirmDialog(
			this,
			"Reset all task progress?\n\n" +
			"This will:\n" +
			"- Clear all rolled tasks (re-roll on next assign)\n" +
			"- Clear assigned task history\n" +
			"- Clear current active task\n" +
			"- Clear completed tasks list\n\n" +
			"Points and unlocked chunks will be kept.",
			"Reset Task Progress",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);

		if (confirm == JOptionPane.YES_OPTION)
		{
			log.info(">>> Dev: Reset Tasks confirmed, calling devResetTasks()...");
			try
			{
				plugin.devResetTasks();
				log.info(">>> Dev: devResetTasks() completed");

				// Clear selected task
				selectedTask = null;
				updateSelectedTaskDisplay();

				// Force refresh all sections
				updatePanel();

				// Explicitly clear and refresh completed tasks content
				completedTasksContentPanel.removeAll();
				updateCompletedTasksContent();
				completedTasksContentPanel.revalidate();
				completedTasksContentPanel.repaint();

				log.info(">>> Dev: Reset complete, all panels refreshed");
				JOptionPane.showMessageDialog(this, "Task progress reset!", "Reset Complete", JOptionPane.INFORMATION_MESSAGE);
			}
			catch (Exception e)
			{
				log.error(">>> Dev: Reset Tasks FAILED with exception: ", e);
				JOptionPane.showMessageDialog(this, "Reset failed! Check logs.", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	private void onResetAll()
	{
		int confirm = JOptionPane.showConfirmDialog(
			this,
			"RESET EVERYTHING?\n\n" +
			"This will:\n" +
			"- Reset all task progress\n" +
			"- Reset points to 0\n" +
			"- Reset unlocked chunks to starting area\n" +
			"- Unlock game mode selection\n\n" +
			"This cannot be undone!",
			"Full Reset",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);

		if (confirm == JOptionPane.YES_OPTION)
		{
			plugin.devResetAll();
			updatePanel();
			JOptionPane.showMessageDialog(this, "Full reset complete!", "Reset Complete", JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private JPanel createTaskListSection()
	{
		JPanel listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		listPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 6, 6, 6)
		));
		listPanel.setAlignmentX(LEFT_ALIGNMENT);

		// Header row with toggle button
		JPanel headerRow = new JPanel(new BorderLayout(5, 0));
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		JLabel sectionTitle = new JLabel("Chunk Tasks");
		sectionTitle.setFont(FontManager.getRunescapeBoldFont());
		sectionTitle.setForeground(Color.WHITE);
		headerRow.add(sectionTitle, BorderLayout.WEST);

		taskListToggle = new JToggleButton();
		setToggleArrow(taskListToggle, taskListExpanded);
		taskListToggle.setFont(new Font("Arial", Font.PLAIN, 10));
		taskListToggle.setPreferredSize(new Dimension(30, 20));
		taskListToggle.setMaximumSize(new Dimension(30, 20));
		taskListToggle.setToolTipText("Expand/collapse task list");
		taskListToggle.addActionListener(e ->
		{
			taskListExpanded = taskListToggle.isSelected();
			setToggleArrow(taskListToggle, taskListExpanded);
			updateTaskListVisibility();
		});
		headerRow.add(taskListToggle, BorderLayout.EAST);

		listPanel.add(headerRow);
		listPanel.add(sectionDivider());
		listPanel.add(Box.createVerticalStrut(5));

		// Filter text field (visible when expanded)
		taskFilterField = new JTextField();
		taskFilterField.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		taskFilterField.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));
		taskFilterField.setAlignmentX(LEFT_ALIGNMENT);
		taskFilterField.setToolTipText("Filter tasks by name");
		taskFilterField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				onFilterChanged();
			}
			@Override
			public void removeUpdate(DocumentEvent e)
			{
				onFilterChanged();
			}
			@Override
			public void changedUpdate(DocumentEvent e)
			{
				onFilterChanged();
			}
		});
		taskFilterField.setVisible(false);
		listPanel.add(taskFilterField);
		listPanel.add(Box.createVerticalStrut(3));

		// Scrollable task list content
		taskListContentPanel = new JPanel();
		taskListContentPanel.setLayout(new BoxLayout(taskListContentPanel, BoxLayout.Y_AXIS));
		taskListContentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		taskListContentPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

		taskListScrollPane = new JScrollPane(taskListContentPanel);
		taskListScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		taskListScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		taskListScrollPane.setBorder(null);
		taskListScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		taskListScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		taskListScrollPane.setAlignmentX(LEFT_ALIGNMENT);
		// Initially hidden - size will be set when expanded
		taskListScrollPane.setVisible(false);

		listPanel.add(taskListScrollPane);

		// Collapsed summary (visible when collapsed)
		JLabel collapsedLabel = new JLabel("Click to view tasks");
		collapsedLabel.setFont(FontManager.getRunescapeSmallFont());
		collapsedLabel.setForeground(Color.GRAY);
		collapsedLabel.setAlignmentX(LEFT_ALIGNMENT);
		listPanel.add(collapsedLabel);

		return listPanel;
	}

	private void onFilterChanged()
	{
		taskFilterText = taskFilterField.getText().toLowerCase().trim();
		updateTaskListContent();
	}

	private void updateTaskListVisibility()
	{
		taskFilterField.setVisible(taskListExpanded);
		taskListScrollPane.setVisible(taskListExpanded);

		// Find and toggle the collapsed label
		for (java.awt.Component comp : taskListPanel.getComponents())
		{
			if (comp instanceof JLabel && ((JLabel) comp).getText().contains("Click"))
			{
				comp.setVisible(!taskListExpanded);
			}
		}

		if (taskListExpanded)
		{
			updateTaskListContent();

			// Fixed height to show 5 items - scroll if more content exists
			int height = MAX_TASK_LIST_HEIGHT;
			taskListScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
			taskListScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
			taskListScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
		}
		else
		{
			// Reset to collapsed size
			taskListScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, 0));
			taskListScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, 0));
			taskListScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, 0));
		}

		// Revalidate the entire panel hierarchy
		taskListPanel.revalidate();
		taskListPanel.repaint();

		// Also revalidate parent to fix layout
		if (taskListPanel.getParent() != null)
		{
			taskListPanel.getParent().revalidate();
			taskListPanel.getParent().repaint();
		}
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

		if (confirm == JOptionPane.YES_OPTION)
		{
			plugin.lockGameMode(selectedMode);
			updateModeDisplay();
		}
	}

	private void onCompleteTask()
	{
		if (selectedTask != null)
		{
			log.info("Dev: Completing selected task: {}", selectedTask.getName());
			plugin.devCompleteSpecificTask(selectedTask);
			// Clear selection after completing
			selectedTask = null;
			updateSelectedTaskDisplay();
		}
		else
		{
			log.info("Dev: No task selected, completing first active task");
			plugin.devCompleteActiveTask();
		}
		updateTaskDisplay();
	}

	private void onRerollTask()
	{
		log.info(">>> Dev: Reroll task button CLICKED");
		try
		{
			plugin.rerollTask();
			log.info(">>> Dev: rerollTask() completed, updating display...");

			// Force update on EDT
			SwingUtilities.invokeLater(() ->
			{
				updateTaskDisplay();
				updateTaskList();
				updateActiveTasksDisplay();
				revalidate();
				repaint();
				log.info(">>> Dev: Reroll UI refresh complete");
			});
		}
		catch (Exception e)
		{
			log.error(">>> Dev: Reroll FAILED with exception: ", e);
		}
	}

	private void openLogFile()
	{
		try
		{
			java.io.File logFile = new java.io.File(System.getProperty("user.home") + "/.runelite/logs/client.log");
			if (!logFile.exists())
			{
				JOptionPane.showMessageDialog(this,
					"Log file not found at:\n" + logFile.getAbsolutePath(),
					"Log File Not Found",
					JOptionPane.WARNING_MESSAGE);
				return;
			}

			// Try Notepad++ first (common install locations)
			String[] notepadPlusPlusPaths = {
				"C:\\Program Files\\Notepad++\\notepad++.exe",
				"C:\\Program Files (x86)\\Notepad++\\notepad++.exe"
			};

			for (String path : notepadPlusPlusPaths)
			{
				java.io.File npp = new java.io.File(path);
				if (npp.exists())
				{
					// Open with Notepad++ and jump to end of file
					Runtime.getRuntime().exec(new String[]{path, "-n999999", logFile.getAbsolutePath()});
					return;
				}
			}

			// Fallback to default application
			java.awt.Desktop.getDesktop().open(logFile);
		}
		catch (Exception e)
		{
			log.error("Failed to open log file", e);
			JOptionPane.showMessageDialog(this,
				"Failed to open log file:\n" + e.getMessage() + "\n\n" +
				"Log file location:\n" + System.getProperty("user.home") + "/.runelite/logs/client.log",
				"Error",
				JOptionPane.ERROR_MESSAGE);
		}
	}

	private void onShowDebugInfo()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("=== CHUNKBLAZER DEBUG INFO ===\nGenerated: ")
			.append(new java.util.Date())
			.append("\n\n=== MODULE STATUS ===\nCheck logs at: C:\\Users\\bao\\.runelite\\logs\\client.log\nLook for: '>>>' prefixed lines for combat tracking\nLook for: 'NpcKillModule HEARTBEAT' every ~60 seconds\n\nCurrent Region: ")
			.append(plugin.getCurrentRegionId())
			.append("\n\n=== ACTIVE TASKS (").append(plugin.getActiveTasks().size()).append(") ===\n\n");

		List<NuzlockeTask> tasks = plugin.getActiveTasks();
		int combatTasks = 0;

		for (NuzlockeTask task : tasks)
		{
			String type = task.getCompletionType();
			String category = task.getCategory();
			boolean isCombat = "NPC_KILL".equalsIgnoreCase(type)
			|| "COMBAT".equalsIgnoreCase(type)
			|| "combat".equalsIgnoreCase(category);

			if (isCombat) combatTasks++;

			sb.append(isCombat ? "[COMBAT] " : "")
				.append("Task: ").append(task.getName()).append('\n')
				.append("  ID: ").append(task.getTaskId()).append('\n')
				.append("  Category: ").append(category).append('\n')
				.append("  CompletionType: ").append(type).append('\n')
				.append("  Progress: ").append(task.getCurrentProgress()).append('/').append(task.getTargetQuantity()).append('\n');

			TargetNpc targetNpc = task.getTargetNpc();
			if (targetNpc != null)
			{
				sb.append("  Target NPC Name: ").append(targetNpc.getName()).append('\n')
					.append("  Target NPC IDs: ").append(targetNpc.getNpcIds()).append('\n');
				if (isCombat)
				{
					sb.append("  >>> If kills aren't tracking, verify in-game NPC ID matches these IDs!\n");
				}
			}
			else
			{
				sb.append("  Target NPC: NONE - this task won't track NPC kills!\n");
			}
			sb.append('\n');
		}

		sb.append("Total combat tasks: ").append(combatTasks).append('\n');
		if (combatTasks == 0)
		{
			sb.append(">>> NO COMBAT TASKS! Kill tracking will not work without combat tasks.\n");
		}
		sb.append("\n=== COMPLETED TASKS ===\n");
		List<NuzlockeTask> completed = plugin.getCompletedTasks();
		sb.append("Total completed: ").append(completed.size()).append('\n');
		for (NuzlockeTask task : completed)
		{
			sb.append("  - ").append(task.getName()).append(" (").append(task.getBasePoints()).append(" pts)\n");
		}

		sb.append("\n=== TROUBLESHOOTING ===\nIf kills aren't being detected:\n1. Check the log file for '>>> PLAYER ATTACKING' messages\n2. Check for '>>> NPC died' messages when NPC dies\n3. Verify the killed NPC's ID matches the Target NPC IDs above\n4. Look for 'NpcKillModule HEARTBEAT' to confirm events are working\n");

		// Create scrollable text area
		javax.swing.JTextArea textArea = new javax.swing.JTextArea(sb.toString());
		textArea.setEditable(false);
		textArea.setFont(FontManager.getRunescapeSmallFont());
		textArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		textArea.setForeground(Color.WHITE);
		textArea.setCaretPosition(0);
		textArea.setWrapStyleWord(true);
		textArea.setLineWrap(true);

		JScrollPane scrollPane = new JScrollPane(textArea);
		scrollPane.setPreferredSize(new Dimension(450, 500));
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

		JOptionPane.showMessageDialog(
			this,
			scrollPane,
			"ChunkBlazer Debug Info",
			JOptionPane.INFORMATION_MESSAGE
		);
	}

	// --- Update Methods ---

	public void updatePanel()
	{
		SwingUtilities.invokeLater(() ->
		{
			boolean loggedIn = plugin.isLoggedIn();
			loggedOutPanel.setVisible(!loggedIn);

			// Always-on gameplay sections simply follow login state.
			statsPanel.setVisible(loggedIn);
			completedTasksPanel.setVisible(loggedIn);
			taskListPanel.setVisible(loggedIn);

			if (!loggedIn)
			{
				// Conditionally-shown sections: hide outright while logged out so
				// nothing interactive is reachable before there's an account.
				regionUnlockPanel.setVisible(false);
				unlockedListPanel.setVisible(false);
				modeSelectionPanel.setVisible(false);
				lockedModePanel.setVisible(false);
				currentTaskPanel.setVisible(false);
				revalidate();
				repaint();
				return;
			}

			// The Active Tasks section (currentTaskPanel) is hidden in the
			// logged-out branch above and nowhere re-shown, so it stayed blank on
			// login until a plugin toggle rebuilt the panel. Re-show it here.
			currentTaskPanel.setVisible(true);

			updateModeDisplay();
			updateRegionDisplay();
			updateStats();
			updateTaskDisplay();
			updateCompletedTasks();
			updateTaskList();
			updateUnlockedListSection();
		});
	}

	public void updateStats()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateStats);
			return;
		}
		int points = plugin.getTotalPoints();
		int chunks = plugin.getUnlockedRegionIds().size();
		int tasks = plugin.getCompletedTaskCount();

		totalPointsLabel.setText(String.valueOf(points));
		chunksUnlockedLabel.setText(String.valueOf(chunks));
		tasksCompletedLabel.setText(String.valueOf(tasks));

		// A points change can flip the unlock button between
		// "Need N more pts" and "Unlock for N pts" — refresh it.
		updateRegionUnlockSection();
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
		lockedModePanel.setVisible(isLocked);

		if (isLocked)
		{
			GameMode mode = plugin.getGameMode();
			Color modeColor = mode == GameMode.NUZLOCKE ?
				new Color(255, 100, 100) : new Color(100, 200, 100);
			modeLabel.setText(" | " + mode.getName());
			modeLabel.setForeground(modeColor);
			lockedModeValueLabel.setText(mode.getName());
			lockedModeValueLabel.setForeground(modeColor);
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

		// Crossing into a new region is the moment the unlock prompt should
		// appear (or disappear, if the new region is already unlocked).
		updateRegionUnlockSection();
	}

	public void updateTaskDisplay()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateTaskDisplay);
			return;
		}
		updateActiveTasksDisplay();
		// Also update selected task if it exists
		if (selectedTask != null)
		{
			updateSelectedTaskDisplay();
		}
	}

	public void updateActiveTasksDisplay()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateActiveTasksDisplay);
			return;
		}
		log.debug(">>> updateActiveTasksDisplay() CALLED");
		log.debug(">>> Stack trace: {}", Thread.currentThread().getStackTrace()[2]);

		// Capture scroll position so the rebuild below doesn't snap the user back to the top
		// when a chunk is unlocked or a task completes.
		final java.awt.Point savedActiveViewPos = activeTasksScrollPane.getViewport().getViewPosition();

		// Refresh filter dropdowns
		refreshActiveTasksFilters();

		// Only rebuild the scrollable content, not the whole panel
		activeTasksContentPanel.removeAll();

		List<NuzlockeTask> allTasks = plugin.getActiveTasks();

		// Debug: check for duplicates
		java.util.Set<String> taskIds = new java.util.HashSet<>();
		for (NuzlockeTask t : allTasks)
		{
			if (taskIds.contains(t.getTaskId()))
			{
				log.warn(">>> DUPLICATE TASK DETECTED: {} ({})", t.getName(), t.getTaskId());
			}
			taskIds.add(t.getTaskId());
		}
		log.debug(">>> allTasks size: {}, unique taskIds: {}", allTasks.size(), taskIds.size());

		// Cache filter values
		final String filterText = activeTasksSearchText != null ? activeTasksSearchText : "";
		final String filterCategory = activeTasksSelectedCategory != null ? activeTasksSelectedCategory : "All";
		final String filterRegion = activeTasksSelectedRegion != null ? activeTasksSelectedRegion : "All";
		final String filterArea = activeTasksSelectedArea != null ? activeTasksSelectedArea : "All";

		// Filter tasks based on search text, category, region, and area
		List<NuzlockeTask> filteredTasks = allTasks.stream()
			.filter(task ->
			{
				// Search text filter
				if (!filterText.isEmpty())
				{
					String name = task.getName() != null ? task.getName().toLowerCase() : "";
					String category = task.getCategory() != null ? task.getCategory().toLowerCase() : "";
					if (!name.contains(filterText) && !category.contains(filterText))
					{
						return false;
					}
				}
				// Category filter
				if (!"All".equals(filterCategory))
				{
					String taskCategory = task.getCategory() != null ? task.getCategory() : "";
					if (!filterCategory.equals(taskCategory))
					{
						return false;
					}
				}
				// Region filter (chunk-level)
				if (!"All".equals(filterRegion))
				{
					String taskRegion = plugin.getTaskRegionName(task);
					if (taskRegion == null || !filterRegion.equals(taskRegion))
					{
						return false;
					}
				}
				// Area filter (overarching: Misthalin / Asgarnia / ...)
				if (!"All".equals(filterArea))
				{
					String taskArea = plugin.getTaskArea(task);
					if (taskArea == null || !filterArea.equals(taskArea))
					{
						return false;
					}
				}
				return true;
			})
			.collect(Collectors.toList());

		if (filteredTasks.isEmpty())
		{
			String message = allTasks.isEmpty() ? "No active tasks" : "No tasks match filter";
			JLabel noTaskLabel = new JLabel(message);
			noTaskLabel.setFont(FontManager.getRunescapeSmallFont());
			noTaskLabel.setForeground(Color.GRAY);
			noTaskLabel.setAlignmentX(LEFT_ALIGNMENT);
			activeTasksContentPanel.add(noTaskLabel);
		}
		else
		{
			int taskNumber = 1;
			for (NuzlockeTask task : filteredTasks)
			{
				activeTasksContentPanel.add(createActiveTaskItem(task, taskNumber));
				activeTasksContentPanel.add(Box.createVerticalStrut(5));
				taskNumber++;
			}
		}

		// Update the section title to show count
		updateActiveTasksSectionTitle(allTasks.size(), filteredTasks.size());

		log.debug(">>> updateActiveTasksDisplay() DONE - added {} items to panel, component count: {}",
			filteredTasks.size(), activeTasksContentPanel.getComponentCount());

		activeTasksContentPanel.revalidate();
		activeTasksContentPanel.repaint();

		// Fixed height for scroll area - content scrolls if it exceeds this
		if (activeTasksExpanded)
		{
			int height = MAX_ACTIVE_TASKS_HEIGHT;
			activeTasksScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
			activeTasksScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
			activeTasksScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
			activeTasksScrollPane.revalidate();
		}

		// Revalidate the SECTION and its parent too — not just the inner content.
		// On the data-refresh path (updateTaskDisplay -> here), if the section was
		// previously laid out at 0 height (e.g. an earlier empty render before the
		// tasks loaded), the parent BoxLayout caches that height and won't re-expand
		// to reveal the freshly added items — so the green "Active Tasks" area looks
		// empty/absent even though the log shows N items were added. The collapse
		// toggle (updateActiveTasksVisibility) already does this, which is why
		// toggling it makes the tasks appear; the refresh path must do it too.
		currentTaskPanel.revalidate();
		currentTaskPanel.repaint();
		if (currentTaskPanel.getParent() != null)
		{
			currentTaskPanel.getParent().revalidate();
			currentTaskPanel.getParent().repaint();
		}

		// Restore the viewport AFTER the layout pass — Swing resets it to (0,0) during revalidate.
		SwingUtilities.invokeLater(() -> activeTasksScrollPane.getViewport().setViewPosition(savedActiveViewPos));
	}

	private void updateActiveTasksSectionTitle(int totalCount, int filteredCount)
	{
		if (activeTasksSectionTitle == null)
		{
			return;
		}

		if (totalCount == filteredCount)
		{
			activeTasksSectionTitle.setText("Active Tasks (" + totalCount + ")");
		}
		else
		{
			activeTasksSectionTitle.setText("Active Tasks (" + filteredCount + "/" + totalCount + ")");
		}
	}

	/**
	 * A rounded ChunkBlazer "card" panel: navy fill, flame-orange left accent bar and a
	 * subtle border, painted (not a fixed image) so it scales to the row's height. Shared
	 * by the completed- and chunk-task rows so every task box has one consistent look.
	 * (The active-task card paints its own variant because it also needs hover/selection.)
	 */
	private JPanel createCardPanel(Color fill, Color border)
	{
		JPanel card = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				java.awt.geom.RoundRectangle2D box =
					new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, 12, 12);
				g2.setColor(fill);
				g2.fill(box);
				java.awt.Shape oldClip = g2.getClip();
				g2.clip(box);
				g2.setColor(FLAME);
				g2.fillRect(0, 0, 5, h);
				g2.setClip(oldClip);
				g2.setColor(border);
				g2.setStroke(new java.awt.BasicStroke(1f));
				g2.draw(box);
				g2.dispose();
			}

			// Cap height to content so BoxLayout can't stretch the card to fill leftover
			// viewport space — that stretch is what left the big gap below short task names.
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(CONTENT_WIDTH - 10, getPreferredSize().height);
			}
		};
		card.setOpaque(false);
		return card;
	}

	/**
	 * A compact rounded "pill" for one unlocked chunk. The region-id parenthetical is
	 * dropped from the visible label to keep chips tight; the full name (with id) is the
	 * tooltip. Laid out by {@link WrapLayout} so chips flow and wrap instead of stacking
	 * one-per-row.
	 */
	private JLabel makeChunkChip(String fullText)
	{
		String label = fullText.replaceAll("\\s*\\([^)]*\\)\\s*$", "");
		JLabel chip = new JLabel(label)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(new Color(40, 52, 76));
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
				g2.setColor(new Color(64, 80, 108));
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		chip.setOpaque(false);
		chip.setForeground(new Color(210, 220, 235));
		chip.setFont(FontManager.getRunescapeSmallFont());
		chip.setBorder(new EmptyBorder(2, 7, 2, 7));
		chip.setToolTipText(fullText);
		return chip;
	}

	/**
	 * FlowLayout that actually wraps to multiple rows and reports the correct preferred
	 * height for the available width — plain FlowLayout always claims a single row, which
	 * breaks inside a BoxLayout/scroll pane. Standard well-known WrapLayout.
	 */
	private static class WrapLayout extends FlowLayout
	{
		WrapLayout(int align, int hgap, int vgap)
		{
			super(align, hgap, vgap);
		}

		@Override
		public Dimension preferredLayoutSize(java.awt.Container target)
		{
			return layoutSize(target, true);
		}

		@Override
		public Dimension minimumLayoutSize(java.awt.Container target)
		{
			Dimension minimum = layoutSize(target, false);
			minimum.width -= (getHgap() + 1);
			return minimum;
		}

		private Dimension layoutSize(java.awt.Container target, boolean preferred)
		{
			synchronized (target.getTreeLock())
			{
				java.awt.Container container = target;
				while (container.getSize().width == 0 && container.getParent() != null)
				{
					container = container.getParent();
				}
				int targetWidth = container.getSize().width;
				if (targetWidth == 0)
				{
					targetWidth = Integer.MAX_VALUE;
				}
				int hgap = getHgap();
				int vgap = getVgap();
				java.awt.Insets insets = target.getInsets();
				int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
				int maxWidth = targetWidth - horizontalInsetsAndGap;
				Dimension dim = new Dimension(0, 0);
				int rowWidth = 0;
				int rowHeight = 0;
				int nmembers = target.getComponentCount();
				for (int i = 0; i < nmembers; i++)
				{
					java.awt.Component m = target.getComponent(i);
					if (m.isVisible())
					{
						Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
						if (rowWidth + d.width > maxWidth)
						{
							addRow(dim, rowWidth, rowHeight);
							rowWidth = 0;
							rowHeight = 0;
						}
						if (rowWidth != 0)
						{
							rowWidth += hgap;
						}
						rowWidth += d.width;
						rowHeight = Math.max(rowHeight, d.height);
					}
				}
				addRow(dim, rowWidth, rowHeight);
				dim.width += horizontalInsetsAndGap;
				dim.height += insets.top + insets.bottom + vgap * 2;
				java.awt.Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
				if (scrollPane != null && target.isValid())
				{
					dim.width -= (hgap + 1);
				}
				return dim;
			}
		}

		private void addRow(Dimension dim, int rowWidth, int rowHeight)
		{
			dim.width = Math.max(dim.width, rowWidth);
			if (dim.height > 0)
			{
				dim.height += getVgap();
			}
			dim.height += rowHeight;
		}
	}

	private JPanel createActiveTaskItem(NuzlockeTask task, int taskNumber)
	{
		// Check if this task is currently selected
		boolean isSelected = selectedTask != null &&
			task.getTaskId() != null &&
			task.getTaskId().equals(selectedTask.getTaskId());

		// Task card: a dark rounded box with a flame-orange left accent bar, painted
		// (not a fixed image) so it scales to each row's height. Hover lightens the
		// fill; selected warms it + flame border.
		final boolean[] hovered = {false};
		JPanel itemPanel = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				int arc = 12;
				java.awt.geom.RoundRectangle2D box =
					new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, arc, arc);
				// ChunkBlazer navy backdrop (matches the News & Updates header) so the
				// card pops off the near-black panel; brightens on hover / selection.
				g2.setColor(isSelected ? new Color(44, 56, 82)
					: (hovered[0] ? new Color(40, 52, 76) : new Color(30, 40, 60)));
				g2.fill(box);
				// Flame-orange left accent bar, clipped to the rounded shape.
				java.awt.Shape oldClip = g2.getClip();
				g2.clip(box);
				g2.setColor(FLAME);
				g2.fillRect(0, 0, 5, h);
				g2.setClip(oldClip);
				g2.setColor(isSelected ? FLAME : new Color(60, 74, 100));
				g2.setStroke(new java.awt.BasicStroke(isSelected ? 1.6f : 1f));
				g2.draw(box);
				g2.dispose();
			}
		};
		itemPanel.setOpaque(false);
		itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));
		// Left inset (12) clears the orange bar; the rest is breathing room.
		itemPanel.setBorder(new EmptyBorder(5, 12, 6, 6));
		itemPanel.setAlignmentX(LEFT_ALIGNMENT);
		itemPanel.setMaximumSize(new Dimension(CONTENT_WIDTH - 10, Integer.MAX_VALUE));

		// Click + hover behaviour. Attached recursively at the bottom of this method so the
		// entire row is clickable, not just bare itemPanel space — child labels and the
		// progress bar were eating clicks before because Swing routes events to the deepest
		// component and they had no listener of their own.
		final java.awt.event.MouseAdapter rowAdapter = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				// Toggle: clicking the already-selected row clears the selection.
				if (isSelected)
				{
					clearSelectedTask();
					updateActiveTasksDisplay();
				}
				else
				{
					selectTask(task);
				}
			}

			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				if (!isSelected)
				{
					hovered[0] = true;
					itemPanel.repaint();
				}
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				if (isSelected)
				{
					return;
				}
				// Only revert if the mouse genuinely left the row, not just moved to a child
				// component within the row. Without this check, hovering a label inside the
				// row makes the highlight flicker.
				java.awt.Point local = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), itemPanel);
				if (!itemPanel.contains(local))
				{
					hovered[0] = false;
					itemPanel.repaint();
				}
			}
		};

		// Task number + Selection indicator + Task name (wrapped via WrappingTextLabel).
		String taskName = task.getName();
		String numberPrefix = taskNumber + ". ";
		String selectionPrefix = isSelected ? "\u2605 " : ""; // Star for selected
		WrappingTextLabel nameLabel = new WrappingTextLabel(
			numberPrefix + selectionPrefix + taskName,
			FontManager.getRunescapeSmallFont(),
			isSelected ? FLAME : new Color(150, 255, 150),
			TASK_TEXT_WRAP_WIDTH);
		itemPanel.add(nameLabel);

		// Info line: Category | Points | Level (compact single line)
		StringBuilder infoText = new StringBuilder();
		infoText.append(task.getCategory());
		infoText.append("  ").append(task.getBasePoints()).append("pt");
		if (task.getLevelRequirement() > 1)
		{
			infoText.append("  L").append(task.getLevelRequirement());
		}
		JLabel infoLabel = new JLabel(infoText.toString());
		infoLabel.setFont(FontManager.getRunescapeSmallFont());
		infoLabel.setForeground(isSelected ? new Color(255, 200, 100) : Color.ORANGE);
		infoLabel.setAlignmentX(LEFT_ALIGNMENT);
		itemPanel.add(infoLabel);

		// Region subtitle: friendly name + numeric region ID, so a glance tells the
		// player which chunk rolled the task. getTaskRegionName already returns the
		// composed "ChunkName (regionId)" string — don't append the ID again.
		String activeRegionName = plugin.getTaskRegionName(task);
		String regionText = (activeRegionName != null && !activeRegionName.isEmpty())
			? "Chunk: " + activeRegionName
			: "Chunk: unknown";
		JLabel activeRegionLabel = new JLabel(regionText);
		activeRegionLabel.setFont(FontManager.getRunescapeSmallFont());
		activeRegionLabel.setForeground(new Color(140, 200, 230));
		activeRegionLabel.setAlignmentX(LEFT_ALIGNMENT);
		itemPanel.add(activeRegionLabel);

		// Progress bar row
		int progress = task.getCurrentProgress();
		int target = task.getTargetQuantity();
		float pct = target > 0 ? (float) progress / target : 0;
		pct = Math.min(pct, 1.0f); // Cap at 100%

		JPanel progressRow = new JPanel(new BorderLayout(4, 0));
		progressRow.setOpaque(false);
		progressRow.setAlignmentX(LEFT_ALIGNMENT);
		progressRow.setPreferredSize(new Dimension(CONTENT_WIDTH - 20, 16));
		progressRow.setMaximumSize(new Dimension(CONTENT_WIDTH - 20, 16));

		// Progress bar paints its fill against its actual rendered width — so the
		// bar always visually matches `pct`, even when the parent layout stretches
		// it wider than the preferred 80 px.
		JPanel progressBar = createPercentageProgressBar(
			pct,
			isSelected ? FLAME : new Color(80, 180, 80),
			isSelected ? FLAME : new Color(60, 60, 60),
			10);
		progressRow.add(progressBar, BorderLayout.CENTER);

		JLabel progressText = new JLabel(progress + "/" + target);
		progressText.setFont(FontManager.getRunescapeSmallFont());
		progressText.setForeground(Color.WHITE);
		progressRow.add(progressText, BorderLayout.EAST);

		itemPanel.add(Box.createVerticalStrut(2));
		itemPanel.add(progressRow);

		// Attach the click/hover listener and HAND cursor to itemPanel and every descendant.
		// Must run AFTER all children are added so nothing gets missed.
		attachRowMouseHandling(itemPanel, rowAdapter, new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

		return itemPanel;
	}

	/**
	 * Return a progress-bar JPanel that paints its fill against its OWN rendered
	 * width every paint, instead of relying on a hardcoded inner-width constant
	 * and a fixed-size child JPanel.
	 *
	 * <p>Why this exists: the previous implementation added a {@code progressFill}
	 * child via {@code BorderLayout.WEST} inside a bar that lived at the parent
	 * row's {@code BorderLayout.CENTER}. CENTER stretches its child to fill all
	 * leftover horizontal space, so the bar's actual width was much larger
	 * (~140 px) than the hardcoded BAR_WIDTH (78) the fill was sized against —
	 * meaning a 97% complete task only painted ~54% of the visible bar.
	 *
	 * <p>By overriding {@code paintComponent} we always paint {@code pct} of the
	 * actual {@code getWidth()}, regardless of how layout sizes the bar.
	 */
	private static JPanel createPercentageProgressBar(float pct, Color fillColor, Color borderColor, int height)
	{
		final float clamped = Math.max(0f, Math.min(1f, pct));
		JPanel bar = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				int innerWidth = getWidth() - 2;   // minus 1px border on each side
				int innerHeight = getHeight() - 2;
				if (innerWidth <= 0 || innerHeight <= 0)
				{
					return;
				}
				int fillWidth = Math.round(innerWidth * clamped);
				if (fillWidth > 0)
				{
					g.setColor(fillColor);
					g.fillRect(1, 1, fillWidth, innerHeight);
				}
			}
		};
		bar.setBackground(new Color(30, 30, 30));
		bar.setBorder(BorderFactory.createLineBorder(borderColor));
		// Preferred size is a hint — actual width is set by the parent layout,
		// and the paint code adapts to whatever final width is assigned.
		bar.setPreferredSize(new Dimension(80, height));
		return bar;
	}

	/**
	 * Walk a container tree and attach the same MouseListener + cursor to every component.
	 * Swing only delivers a click to the deepest component under the cursor; without this,
	 * clicks on child labels/panels inside a "clickable row" silently do nothing.
	 */
	private static void attachRowMouseHandling(java.awt.Container container, java.awt.event.MouseListener listener, java.awt.Cursor cursor)
	{
		container.setCursor(cursor);
		container.addMouseListener(listener);
		for (java.awt.Component child : container.getComponents())
		{
			if (child instanceof java.awt.Container)
			{
				attachRowMouseHandling((java.awt.Container) child, listener, cursor);
			}
			else
			{
				child.setCursor(cursor);
				child.addMouseListener(listener);
			}
		}
	}

	public void updateCompletedTasks()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateCompletedTasks);
			return;
		}
		List<CompletedTaskInfo> completedTasks = plugin.getCompletedTasksWithInfo();
		int count = completedTasks.size();

		if (completedCollapsedLabel != null)
		{
			completedCollapsedLabel.setText(count > 0 ?
				"Click to view " + count + " completed tasks" :
				"Click to view completed tasks");
		}

		if (completedTasksPanel.getComponentCount() > 0)
		{
			java.awt.Component first = completedTasksPanel.getComponent(0);
			if (first instanceof JPanel)
			{
				JPanel headerRow = (JPanel) first;
				for (java.awt.Component comp : headerRow.getComponents())
				{
					if (comp instanceof JLabel)
					{
						((JLabel) comp).setText("Completed Tasks (" + count + ")");
						break;
					}
				}
			}
		}

		if (completedTasksExpanded)
		{
			refreshCompletedTasksFilters();
			updateCompletedTasksContent();
		}
	}

	private void updateCompletedTasksContent()
	{
		// Capture viewport so a rebuild (e.g. on completion) doesn't snap the user back to top.
		final java.awt.Point savedCompletedViewPos = completedTasksScrollPane.getViewport().getViewPosition();

		completedTasksContentPanel.removeAll();

		List<CompletedTaskInfo> allTasks = plugin.getCompletedTasksWithInfo();

		// Cache filter values to avoid null issues during combo box updates
		final String filterCategory = selectedCategory != null ? selectedCategory : "All";
		final String filterRegion = selectedRegion != null ? selectedRegion : "All";
		final String filterArea = selectedArea != null ? selectedArea : "All";
		final String filterText = completedTasksSearchText != null ? completedTasksSearchText : "";

		List<CompletedTaskInfo> filteredTasks = allTasks.stream()
			.filter(info ->
			{
				if (info == null)
				{
					return false;
				}

				if (!filterText.isEmpty())
				{
					String name = info.getName() != null ? info.getName().toLowerCase() : "";
					if (!name.contains(filterText))
					{
						return false;
					}
				}
				if (!"All".equals(filterCategory))
				{
					String cat = info.getCategory() != null ? info.getCategory() : "";
					if (!filterCategory.equals(cat))
					{
						return false;
					}
				}
				if (!"All".equals(filterRegion))
				{
					String reg = info.getRegionName() != null ? info.getRegionName() : "";
					if (!filterRegion.equals(reg))
					{
						return false;
					}
				}
				if (!"All".equals(filterArea))
				{
					String area = plugin.getAreaForRegionId(info.getRegionId());
					if (area == null || !filterArea.equals(area))
					{
						return false;
					}
				}
				return true;
			})
			.collect(Collectors.toList());

		if (filteredTasks.isEmpty())
		{
			String message = allTasks.isEmpty() ? "No tasks completed yet" : "No tasks match filters";
			JLabel placeholder = new JLabel(message);
			placeholder.setFont(FontManager.getRunescapeSmallFont());
			placeholder.setForeground(Color.GRAY);
			placeholder.setAlignmentX(LEFT_ALIGNMENT);
			completedTasksContentPanel.add(placeholder);
		}
		else
		{
			int totalPoints = filteredTasks.stream().mapToInt(CompletedTaskInfo::getPoints).sum();
			JLabel summaryLabel = new JLabel("Showing " + filteredTasks.size() + " tasks (" + totalPoints + " pts)");
			summaryLabel.setFont(FontManager.getRunescapeSmallFont());
			summaryLabel.setForeground(FLAME);
			summaryLabel.setAlignmentX(LEFT_ALIGNMENT);
			completedTasksContentPanel.add(summaryLabel);
			completedTasksContentPanel.add(Box.createVerticalStrut(5));

			for (CompletedTaskInfo info : filteredTasks)
			{
				completedTasksContentPanel.add(createEnhancedCompletedTaskItem(info));
				completedTasksContentPanel.add(Box.createVerticalStrut(4));
			}
		}

		completedTasksContentPanel.revalidate();
		completedTasksContentPanel.repaint();

		// Set fixed scroll pane height to show 5 items (scrollable if more)
		if (completedTasksExpanded)
		{
			// Force layout calculation
			completedTasksContentPanel.doLayout();

			// Use fixed height to show 5 items - scroll if more content exists
			int height = MAX_COMPLETED_TASKS_HEIGHT;
			completedTasksScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
			completedTasksScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
			completedTasksScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
			completedTasksScrollPane.revalidate();
			completedTasksScrollPane.repaint();
		}

		// Restore viewport AFTER the layout pass; revalidate resets it to (0,0) otherwise.
		SwingUtilities.invokeLater(() -> completedTasksScrollPane.getViewport().setViewPosition(savedCompletedViewPos));
	}

	private JPanel createEnhancedCompletedTaskItem(CompletedTaskInfo info)
	{
		// Shared navy card look; dimmed slightly since these are already done.
		JPanel itemPanel = createCardPanel(new Color(28, 36, 54), new Color(54, 66, 92));
		itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));
		// Left inset (12) clears the orange accent bar.
		itemPanel.setBorder(new EmptyBorder(5, 12, 6, 6));
		itemPanel.setAlignmentX(LEFT_ALIGNMENT);
		// Allow dynamic height based on content
		itemPanel.setMaximumSize(new Dimension(CONTENT_WIDTH - 10, Integer.MAX_VALUE));

		// Task name with checkmark (wrapped via WrappingTextLabel).
		String taskName = info.getName();
		WrappingTextLabel nameLabel = new WrappingTextLabel(
			"\u2713 " + taskName,
			FontManager.getRunescapeSmallFont(),
			new Color(100, 200, 100),
			TASK_TEXT_WRAP_WIDTH);
		itemPanel.add(nameLabel);

		// Info line: Category | Points
		String infoText = info.getCategory() + "  +" + info.getPoints() + " pts";
		JLabel infoLabel = new JLabel(infoText);
		infoLabel.setFont(FontManager.getRunescapeSmallFont());
		infoLabel.setForeground(Color.ORANGE);
		infoLabel.setAlignmentX(LEFT_ALIGNMENT);
		itemPanel.add(infoLabel);

		// Region line. info.getRegionName() is already the composed
		// "ChunkName (regionId)" string from getRegionName(int) — display as-is.
		String regionName = info.getRegionName();
		WrappingTextLabel regionLabel = new WrappingTextLabel(
			regionName != null && !regionName.isEmpty() ? regionName : "Unknown",
			FontManager.getRunescapeSmallFont(),
			Color.CYAN,
			TASK_TEXT_WRAP_WIDTH);
		itemPanel.add(regionLabel);

		return itemPanel;
	}


	public void showNoTasksMessage()
	{
		SwingUtilities.invokeLater(() ->
		{
			JOptionPane.showMessageDialog(
				this,
				"All available tasks have been completed!\n\n" +
				"Unlock more chunks to get new tasks.",
				"No Tasks Available",
				JOptionPane.INFORMATION_MESSAGE
			);
		});
	}

	public void updateTaskList()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateTaskList);
			return;
		}
		// Update the header with task count
		List<NuzlockeTask> tasks = plugin.getCurrentRegionTasks();
		int availableCount = 0;
		int totalCount = tasks != null ? tasks.size() : 0;
		if (tasks != null)
		{
			for (NuzlockeTask task : tasks)
			{
				if (!plugin.isTaskAssigned(task.getTaskId()))
				{
					availableCount++;
				}
			}
		}

		// Update header title in the first component (headerRow)
		if (taskListPanel.getComponentCount() > 0)
		{
			java.awt.Component first = taskListPanel.getComponent(0);
			if (first instanceof JPanel)
			{
				JPanel headerRow = (JPanel) first;
				for (java.awt.Component comp : headerRow.getComponents())
				{
					if (comp instanceof JLabel)
					{
						String titleText = "Chunk Tasks";
						if (totalCount > 0)
						{
							titleText += " (" + availableCount + "/" + totalCount + ")";
						}
						((JLabel) comp).setText(titleText);
						break;
					}
				}
			}
		}

		// Update content if expanded
		if (taskListExpanded)
		{
			updateTaskListContent();
		}
	}

	private void updateTaskListContent()
	{
		// Only preserve viewport when staying within the same region. On a region change
		// the list shows a totally different set of tasks, so any saved Y just clamps
		// into a meaningless "specific spot" (the ladder-click bug).
		final int currentRegionId = plugin.getCurrentRegionId();
		final boolean sameRegion = currentRegionId == lastRenderedTaskListRegionId;
		final java.awt.Point savedTaskListViewPos = sameRegion
			? taskListScrollPane.getViewport().getViewPosition()
			: new java.awt.Point(0, 0);
		lastRenderedTaskListRegionId = currentRegionId;

		taskListContentPanel.removeAll();

		List<NuzlockeTask> tasks = plugin.getCurrentRegionTasks();

		if (tasks == null || tasks.isEmpty())
		{
			JLabel noTasksLabel = new JLabel("No tasks rolled for this region");
			noTasksLabel.setFont(FontManager.getRunescapeSmallFont());
			noTasksLabel.setForeground(Color.GRAY);
			noTasksLabel.setAlignmentX(LEFT_ALIGNMENT);
			taskListContentPanel.add(noTasksLabel);
		}
		else
		{
			int shown = 0;
			for (NuzlockeTask task : tasks)
			{
				// Apply filter
				if (!taskFilterText.isEmpty())
				{
					String name = task.getName().toLowerCase();
					String category = task.getCategory() != null ? task.getCategory().toLowerCase() : "";
					if (!name.contains(taskFilterText) && !category.contains(taskFilterText))
					{
						continue;
					}
				}

				taskListContentPanel.add(createTaskListItem(task));
				taskListContentPanel.add(Box.createVerticalStrut(3));
				shown++;
			}

			if (shown == 0 && !taskFilterText.isEmpty())
			{
				JLabel noMatchLabel = new JLabel("No tasks match filter");
				noMatchLabel.setFont(FontManager.getRunescapeSmallFont());
				noMatchLabel.setForeground(Color.GRAY);
				noMatchLabel.setAlignmentX(LEFT_ALIGNMENT);
				taskListContentPanel.add(noMatchLabel);
			}
		}

		taskListContentPanel.revalidate();
		taskListContentPanel.repaint();

		// Size the viewport to the actual (now height-capped) cards, capped at
		// MAX_TASK_LIST_HEIGHT — shrinks the section when there are few small tasks
		// instead of always reserving the full 5-item height, then scrolls beyond the cap.
		if (taskListExpanded)
		{
			int contentH = taskListContentPanel.getPreferredSize().height + 4;
			int height = Math.max(50, Math.min(contentH, MAX_TASK_LIST_HEIGHT));
			taskListScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
			taskListScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
			taskListScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
			taskListScrollPane.revalidate();
		}

		// Restore viewport AFTER the layout pass; revalidate resets it to (0,0) otherwise.
		SwingUtilities.invokeLater(() -> taskListScrollPane.getViewport().setViewPosition(savedTaskListViewPos));
	}

	private JPanel createTaskListItem(NuzlockeTask task)
	{
		// Determine task status
		boolean isAssigned = plugin.isTaskAssigned(task.getTaskId());
		boolean isActive = plugin.getActiveTask() != null &&
			task.getTaskId().equals(plugin.getActiveTask().getTaskId());

		// Shared navy card; brighter + flame border when active, dimmed when already done.
		Color cardFill;
		Color cardBorder;
		if (isActive)
		{
			cardFill = new Color(44, 56, 82);
			cardBorder = FLAME;
		}
		else if (isAssigned)
		{
			cardFill = new Color(26, 32, 46);
			cardBorder = new Color(50, 60, 80);
		}
		else
		{
			cardFill = new Color(30, 40, 60);
			cardBorder = new Color(60, 74, 100);
		}
		JPanel itemPanel = createCardPanel(cardFill, cardBorder);
		itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));
		// Left inset (12) clears the orange accent bar.
		itemPanel.setBorder(new EmptyBorder(5, 12, 6, 6));
		// Allow dynamic height based on content
		itemPanel.setMaximumSize(new Dimension(CONTENT_WIDTH - 10, Integer.MAX_VALUE));
		itemPanel.setAlignmentX(LEFT_ALIGNMENT);

		// Determine text color based on status
		Color textColor;
		if (isActive)
		{
			textColor = new Color(100, 255, 100); // Bright green for active
		}
		else if (isAssigned)
		{
			textColor = Color.GRAY; // Gray for already assigned
		}
		else if (task.isLocked())
		{
			textColor = Color.DARK_GRAY;
		}
		else
		{
			textColor = Color.WHITE; // Available
		}

		// Task name (wrapped via WrappingTextLabel).
		String displayName = task.getName();
		WrappingTextLabel nameLabel = new WrappingTextLabel(
			displayName,
			FontManager.getRunescapeSmallFont(),
			textColor,
			TASK_TEXT_WRAP_WIDTH);
		itemPanel.add(nameLabel);

		// Task info line (status + points + level)
		JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		infoPanel.setOpaque(false);
		infoPanel.setAlignmentX(LEFT_ALIGNMENT);

		// Status indicator
		if (isActive)
		{
			JLabel statusLabel = new JLabel("ACTIVE");
			statusLabel.setFont(FontManager.getRunescapeSmallFont());
			statusLabel.setForeground(new Color(100, 255, 100));
			infoPanel.add(statusLabel);
		}
		else if (isAssigned)
		{
			JLabel statusLabel = new JLabel("DONE");
			statusLabel.setFont(FontManager.getRunescapeSmallFont());
			statusLabel.setForeground(Color.GRAY);
			infoPanel.add(statusLabel);
		}

		if (task.getLevelRequirement() > 1)
		{
			JLabel levelLabel = new JLabel("Lv" + task.getLevelRequirement());
			levelLabel.setFont(FontManager.getRunescapeSmallFont());
			levelLabel.setForeground(Color.ORANGE);
			infoPanel.add(levelLabel);
		}

		JLabel pointsLabel = new JLabel(task.getBasePoints() + "pt");
		pointsLabel.setFont(FontManager.getRunescapeSmallFont());
		pointsLabel.setForeground(isAssigned ? Color.GRAY : new Color(100, 200, 100));
		infoPanel.add(pointsLabel);

		itemPanel.add(infoPanel);

		return itemPanel;
	}
}
