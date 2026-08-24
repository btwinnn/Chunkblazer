package com.chunkblazer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import com.chunkblazer.ui.WrappingTextLabel;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

@Slf4j
public class ChunkBlazerPanel extends PluginPanel
{
	private static final int TASK_ITEM_HEIGHT = 80; // Approx height of one task item (increased for text wrapping)
	private static final int VISIBLE_TASK_COUNT = 5; // Show 5 tasks at a time in scroll areas
	private static final int MAX_TASK_LIST_HEIGHT = TASK_ITEM_HEIGHT * VISIBLE_TASK_COUNT; // Show 5 items
	private static final int MAX_ACTIVE_TASKS_HEIGHT = TASK_ITEM_HEIGHT * 4; // Max height for active tasks (4 items)
	private static final int MAX_COMPLETED_TASKS_HEIGHT = TASK_ITEM_HEIGHT * VISIBLE_TASK_COUNT; // Show 5 items
	// Global task cards are two lines (wrapped name + "Quest +N pts"), so they
	// pack tighter than the 80px rolled-task rows but are not fixed height —
	// a long quest name wraps. Height matches the Completed Tasks section.
	private static final int MAX_GLOBAL_TASKS_HEIGHT = MAX_COMPLETED_TASKS_HEIGHT;
	// Unlocked chunks render as two-line cards like the task sections, so this
	// shows roughly 6 before scrolling. Without a cap the expanded list grew
	// with the unlock count (101 entries ran to thousands of px) and swamped the
	// side panel — every other section here is a fixed-height scroll area.
	private static final int MAX_UNLOCKED_CHUNKS_HEIGHT = TASK_ITEM_HEIGHT * 3;

	/**
	 * Display labels for the Global Tasks type filter, keyed by the raw
	 * NuzlockeTask.category in the JSON.
	 *
	 * The combo is populated from the categories actually present in the pool,
	 * so a new global task type shows up in the filter with no code change. This
	 * map only overrides the wording where the category and the label differ
	 * (category "Quest" reads better as "Quests" in a filter). Anything not
	 * listed falls back to the raw category, which is why this is not naive
	 * pluralisation — "Mystery" must not become "Mysterys".
	 */
	private static final Map<String, String> GLOBAL_TYPE_DISPLAY_NAMES = createGlobalTypeDisplayNames();

	private static Map<String, String> createGlobalTypeDisplayNames()
	{
		Map<String, String> names = new HashMap<>();
		names.put("Quest", "Quests");
		names.put("Progression", "Progression");
		names.put("Mystery", "Mystery Tasks");
		return names;
	}

	private static String globalTypeDisplayName(String category)
	{
		if (category == null || category.isEmpty())
		{
			return "Other";
		}
		return GLOBAL_TYPE_DISPLAY_NAMES.getOrDefault(category, category);
	}

	private ChunkBlazerPlugin plugin;

	// UI Components
	private JPanel regionUnlockPanel;
	private JPanel unlockedListPanel;
	private boolean unlockedListExpanded = false; // Unlocked Chunks list collapsed by default
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
	private JLabel bossTokensLabel;
	private JLabel chunksUnlockedLabel;
	private JLabel tasksCompletedLabel;
	private JLabel taskNameLabel;
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

	// --- Global Tasks (chunk-independent quest pool) ---
	// Deliberately has no area/chunk filter combos: these tasks belong to no
	// region, so getTaskRegionName/getTaskArea return null for all of them and
	// any region filter would hide the entire section.
	private JPanel globalTasksPanel;
	private JPanel globalTasksContentPanel;
	private JScrollPane globalTasksScrollPane;
	private JToggleButton globalTasksToggle;
	private boolean globalTasksExpanded = false;
	private JTextField globalTasksSearchField;
	private String globalTasksSearchText = "";
	private JPanel globalTasksFilterPanel;
	private JLabel globalTasksCollapsedLabel;
	private JLabel globalTasksSectionTitle;
	private JCheckBox globalTasksHideCompleted;
	private JComboBox<String> globalTasksTypeCombo;
	// Underlying NuzlockeTask.category to filter on, or "All". Stored rather than
	// read off the combo because the combo shows display names (see
	// GLOBAL_TYPE_DISPLAY_NAMES) which do not always equal the category.
	private String globalTasksSelectedType = "All";
	private boolean isRefreshingFilters = false; // Prevent event loops during filter refresh

	// Active Tasks Components
	private JPanel selectedTaskPanel;
	private JLabel activeTasksSectionTitle;
	private JTextField activeTasksSearchField;
	// Tier (points) filters. 0 = All; otherwise the base_points value to match.
	private JComboBox<String> activeTasksTierCombo;
	private int activeTasksSelectedTier = 0;
	// Boss-chunk task filter: when checked, show only tasks belonging to boss
	// chunks (raids / bosses unlocked with Boss Tokens).
	private JCheckBox activeTasksBossOnlyCheck;
	private boolean activeTasksBossOnly = false;
	private JComboBox<String> completedTasksTierCombo;
	private int completedTasksSelectedTier = 0;

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
		JPanel mainPanel = boxPanel(ColorScheme.DARK_GRAY_COLOR);
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

		// Global Tasks Section — chunk-independent quest pool, free for every
		// account. Sits above the region task list because it's always relevant,
		// regardless of which chunks the player owns.
		globalTasksPanel = createGlobalTasksSection();
		setupSectionPanel(globalTasksPanel);
		mainPanel.add(globalTasksPanel);
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

	// --- Swing factory helpers -----------------------------------------------
	// Small builders that collapse the repeated "new component, set font, set
	// colour, add" boilerplate. Pure convenience — they set exactly the same
	// properties the inline code used to, in the same order.

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
		JPanel panel = boxPanel(new Color(60, 45, 18)); // dark amber
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(230, 170, 50), 2),
			new EmptyBorder(8, 8, 8, 8)
		));

		addLabel(panel, "Verify Your Account", FontManager.getRunescapeBoldFont(), new Color(255, 190, 60));
		panel.add(Box.createVerticalStrut(5));

		WrappingTextLabel body = new WrappingTextLabel(
			"Type this code in public chat and hit Enter to verify your ChunkBlazer account:",
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
	 * "Enable Server Verification" description carries the other half).
	 */
	private JPanel createDataNoticeRow()
	{
		JPanel row = styledPanel(new FlowLayout(FlowLayout.CENTER, 3, 0), ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(PANEL_WIDTH, 16));

		row.add(styledLabel("Progress synced to", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR));

		JLabel site = styledLabel("chunkblazer.com", FontManager.getRunescapeSmallFont(), new Color(255, 152, 0));
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
		JPanel panel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(100, 100, 180)),
			new EmptyBorder(6, 6, 6, 6)
		));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		// Header row with toggle button
		JPanel headerRow = styledPanel(new BorderLayout(5, 0), ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		headerRow.add(styledLabel("Completed Tasks", FontManager.getRunescapeBoldFont(), new Color(100, 255, 100)), BorderLayout.WEST);

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
		completedTasksFilterPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		completedTasksFilterPanel.setAlignmentX(LEFT_ALIGNMENT);
		completedTasksFilterPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 185));
		completedTasksFilterPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 185));
		completedTasksFilterPanel.setVisible(false);

		// Search text field
		JPanel searchRow = styledPanel(new BorderLayout(5, 0), ColorScheme.DARKER_GRAY_COLOR);
		searchRow.setAlignmentX(LEFT_ALIGNMENT);
		searchRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		searchRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		searchRow.add(styledLabel("Search:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.WEST);

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
		JPanel areaRow = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);
		areaRow.setAlignmentX(LEFT_ALIGNMENT);
		areaRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 45));
		areaRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 45));

		areaRow.add(styledLabel("Area:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

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
		JPanel filterRow = styledPanel(new GridLayout(1, 2, 5, 0), ColorScheme.DARKER_GRAY_COLOR);
		filterRow.setAlignmentX(LEFT_ALIGNMENT);
		filterRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 50));
		filterRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 50));

		// Category filter
		JPanel categoryPanel = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);

		categoryPanel.add(styledLabel("Category:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

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
		JPanel regionPanel = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);

		regionPanel.add(styledLabel("Chunk:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

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

		// Tier (points) filter — mirrors the card colours.
		JPanel cTierRow = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);
		cTierRow.setAlignmentX(LEFT_ALIGNMENT);
		cTierRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 45));
		cTierRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 45));

		cTierRow.add(styledLabel("Tier:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

		completedTasksTierCombo = new JComboBox<>(TIER_NAMES);
		completedTasksTierCombo.setFont(FontManager.getRunescapeSmallFont());
		completedTasksTierCombo.setToolTipText("Filter completed tasks by point value");
		completedTasksTierCombo.addActionListener(e ->
		{
			if (!isRefreshingFilters)
			{
				completedTasksSelectedTier = tierPointsForLabel(completedTasksTierCombo.getSelectedItem());
				updateCompletedTasksContent();
			}
		});
		cTierRow.add(completedTasksTierCombo, BorderLayout.CENTER);

		completedTasksFilterPanel.add(cTierRow);
		completedTasksFilterPanel.add(Box.createVerticalStrut(5));

		panel.add(completedTasksFilterPanel);

		// Scrollable content panel
		completedTasksContentPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
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
		completedCollapsedLabel = addLabel(panel, "Click to view completed tasks", FontManager.getRunescapeSmallFont(), Color.GRAY);

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

	// ------------------------------------------------------------------
	// Global Tasks — the chunk-independent quest pool. Every account has all
	// of these from load; there is no rolling, no unlock and no region gate.
	// ------------------------------------------------------------------

	private JPanel createGlobalTasksSection()
	{
		JPanel panel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(190, 150, 60)),
			new EmptyBorder(6, 6, 6, 6)
		));
		panel.setAlignmentX(LEFT_ALIGNMENT);

		JPanel headerRow = styledPanel(new BorderLayout(5, 0), ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		// Held as a field rather than found by walking the header's children —
		// the index-hunting pattern used elsewhere breaks the moment the header
		// layout changes.
		globalTasksSectionTitle = styledLabel("Global Tasks", FontManager.getRunescapeBoldFont(), new Color(255, 200, 80));
		headerRow.add(globalTasksSectionTitle, BorderLayout.WEST);

		globalTasksToggle = new JToggleButton();
		setToggleArrow(globalTasksToggle, globalTasksExpanded);
		globalTasksToggle.setFont(new Font("Arial", Font.PLAIN, 10));
		globalTasksToggle.setPreferredSize(new Dimension(30, 20));
		globalTasksToggle.setMaximumSize(new Dimension(30, 20));
		globalTasksToggle.setToolTipText("Expand/collapse global tasks");
		globalTasksToggle.addActionListener(e ->
		{
			globalTasksExpanded = globalTasksToggle.isSelected();
			setToggleArrow(globalTasksToggle, globalTasksExpanded);
			updateGlobalTasksVisibility();
		});
		headerRow.add(globalTasksToggle, BorderLayout.EAST);

		panel.add(headerRow);
		panel.add(sectionDivider());
		panel.add(Box.createVerticalStrut(5));

		globalTasksFilterPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		globalTasksFilterPanel.setAlignmentX(LEFT_ALIGNMENT);
		globalTasksFilterPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 105));
		globalTasksFilterPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 105));
		globalTasksFilterPanel.setVisible(false);

		JPanel searchRow = styledPanel(new BorderLayout(5, 0), ColorScheme.DARKER_GRAY_COLOR);
		searchRow.setAlignmentX(LEFT_ALIGNMENT);
		searchRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		searchRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		searchRow.add(styledLabel("Search:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.WEST);

		globalTasksSearchField = new JTextField();
		globalTasksSearchField.setToolTipText("Search global tasks by name");
		globalTasksSearchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				onGlobalTasksFilterChanged();
			}
			@Override
			public void removeUpdate(DocumentEvent e)
			{
				onGlobalTasksFilterChanged();
			}
			@Override
			public void changedUpdate(DocumentEvent e)
			{
				onGlobalTasksFilterChanged();
			}
		});
		searchRow.add(globalTasksSearchField, BorderLayout.CENTER);

		globalTasksFilterPanel.add(searchRow);
		globalTasksFilterPanel.add(Box.createVerticalStrut(5));

		// Type filter — Quests today; Progression and Mystery later. Populated
		// from the categories present in the pool, so new types need no code.
		JPanel typeRow = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);
		typeRow.setAlignmentX(LEFT_ALIGNMENT);
		typeRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 45));
		typeRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 45));

		typeRow.add(styledLabel("Type:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

		globalTasksTypeCombo = new JComboBox<>(new String[]{"All"});
		globalTasksTypeCombo.setFont(FontManager.getRunescapeSmallFont());
		globalTasksTypeCombo.setToolTipText("Filter global tasks by type");
		globalTasksTypeCombo.addActionListener(e ->
		{
			if (isRefreshingFilters)
			{
				return;
			}
			globalTasksSelectedType = selectedGlobalTypeCategory();
			updateGlobalTasksContent();
		});
		typeRow.add(globalTasksTypeCombo, BorderLayout.CENTER);

		globalTasksFilterPanel.add(typeRow);
		globalTasksFilterPanel.add(Box.createVerticalStrut(3));

		globalTasksHideCompleted = new JCheckBox("Hide completed");
		globalTasksHideCompleted.setFont(FontManager.getRunescapeSmallFont());
		globalTasksHideCompleted.setForeground(Color.LIGHT_GRAY);
		globalTasksHideCompleted.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		globalTasksHideCompleted.setAlignmentX(LEFT_ALIGNMENT);
		globalTasksHideCompleted.addActionListener(e -> updateGlobalTasksContent());
		globalTasksFilterPanel.add(globalTasksHideCompleted);

		panel.add(globalTasksFilterPanel);

		globalTasksContentPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		globalTasksContentPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

		globalTasksScrollPane = new JScrollPane(globalTasksContentPanel);
		globalTasksScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		globalTasksScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		globalTasksScrollPane.setBorder(null);
		globalTasksScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		globalTasksScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		globalTasksScrollPane.setAlignmentX(LEFT_ALIGNMENT);
		globalTasksScrollPane.setVisible(false);
		// Rows are compact single-line labels, so scroll a sensible amount per notch.
		globalTasksScrollPane.getVerticalScrollBar().setUnitIncrement(16);

		panel.add(globalTasksScrollPane);

		globalTasksCollapsedLabel = addLabel(panel, "Click to view global tasks", FontManager.getRunescapeSmallFont(), Color.GRAY);

		return panel;
	}

	private void updateGlobalTasksVisibility()
	{
		globalTasksFilterPanel.setVisible(globalTasksExpanded);
		globalTasksScrollPane.setVisible(globalTasksExpanded);
		globalTasksCollapsedLabel.setVisible(!globalTasksExpanded);

		if (globalTasksExpanded)
		{
			refreshGlobalTasksTypes();
			updateGlobalTasksContent();

			int height = MAX_GLOBAL_TASKS_HEIGHT;
			globalTasksScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
			globalTasksScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
			globalTasksScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
		}
		else
		{
			globalTasksScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, 0));
			globalTasksScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, 0));
			globalTasksScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, 0));
		}

		globalTasksPanel.revalidate();
		globalTasksPanel.repaint();

		if (globalTasksPanel.getParent() != null)
		{
			globalTasksPanel.getParent().revalidate();
			globalTasksPanel.getParent().repaint();
		}
	}

	private void onGlobalTasksFilterChanged()
	{
		globalTasksSearchText = globalTasksSearchField.getText().toLowerCase().trim();
		updateGlobalTasksContent();
	}

	/**
	 * Map the combo's current display label back to the underlying task
	 * category, since the two differ for some types ("Quests" -> "Quest").
	 */
	private String selectedGlobalTypeCategory()
	{
		Object selected = globalTasksTypeCombo.getSelectedItem();
		if (selected == null || "All".equals(selected))
		{
			return "All";
		}

		String label = selected.toString();
		for (NuzlockeTask task : plugin.getVisibleGlobalTasks())
		{
			if (label.equals(globalTypeDisplayName(task.getCategory())))
			{
				return task.getCategory() != null ? task.getCategory() : "All";
			}
		}
		return "All";
	}

	/**
	 * Rebuild the type combo from the categories actually present in the pool.
	 * Preserves the current selection where possible so a refresh doesn't reset
	 * the player's filter.
	 */
	private void refreshGlobalTasksTypes()
	{
		if (globalTasksTypeCombo == null || plugin == null)
		{
			return;
		}

		Set<String> categories = new TreeSet<>();
		for (NuzlockeTask task : plugin.getVisibleGlobalTasks())
		{
			if (task.getCategory() != null && !task.getCategory().isEmpty())
			{
				categories.add(task.getCategory());
			}
		}

		isRefreshingFilters = true;
		try
		{
			String previous = globalTasksSelectedType;

			globalTasksTypeCombo.removeAllItems();
			globalTasksTypeCombo.addItem("All");
			for (String category : categories)
			{
				globalTasksTypeCombo.addItem(globalTypeDisplayName(category));
			}

			if (previous != null && !"All".equals(previous) && categories.contains(previous))
			{
				globalTasksTypeCombo.setSelectedItem(globalTypeDisplayName(previous));
			}
			else
			{
				globalTasksTypeCombo.setSelectedItem("All");
				globalTasksSelectedType = "All";
			}
		}
		finally
		{
			isRefreshingFilters = false;
		}
	}

	/**
	 * Refresh the Global Tasks header/summary, and rebuild the list only when the
	 * section is expanded.
	 *
	 * The lazy gate matters more here than in the other sections: this pool is
	 * ~200 tasks, and every rebuild tears down and reconstructs a component per
	 * row. This method is called from updatePanel() only — deliberately NOT from
	 * updateTaskDisplay(), which fires on every progress event.
	 */
	public void updateGlobalTasks()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::updateGlobalTasks);
			return;
		}

		if (globalTasksPanel == null || plugin == null)
		{
			return;
		}

		List<NuzlockeTask> tasks = plugin.getVisibleGlobalTasks();
		Set<String> completedIds = plugin.getCompletedTaskIdSet();

		int total = tasks.size();
		int done = 0;
		for (NuzlockeTask t : tasks)
		{
			if (t.isCompleted() || completedIds.contains(t.getTaskId()))
			{
				done++;
			}
		}

		if (globalTasksSectionTitle != null)
		{
			globalTasksSectionTitle.setText("Global Tasks (" + done + "/" + total + ")");
		}

		if (globalTasksCollapsedLabel != null)
		{
			globalTasksCollapsedLabel.setText(total > 0
				? "Click to view " + total + " global tasks"
				: "No global tasks loaded");
		}

		if (globalTasksExpanded)
		{
			refreshGlobalTasksTypes();
			updateGlobalTasksContent();
		}
	}

	private void updateGlobalTasksContent()
	{
		if (globalTasksContentPanel == null || plugin == null)
		{
			return;
		}

		// Preserve scroll position so a rebuild doesn't snap a player who is
		// halfway down a 200-row list back to the top.
		final java.awt.Point savedViewPos = globalTasksScrollPane.getViewport().getViewPosition();

		globalTasksContentPanel.removeAll();

		Set<String> completedIds = plugin.getCompletedTaskIdSet();
		boolean hideCompleted = globalTasksHideCompleted != null && globalTasksHideCompleted.isSelected();
		final String filterType = globalTasksSelectedType != null ? globalTasksSelectedType : "All";

		List<NuzlockeTask> matching = new ArrayList<>();
		int matchingPoints = 0;

		for (NuzlockeTask task : plugin.getVisibleGlobalTasks())
		{
			boolean done = task.isCompleted() || completedIds.contains(task.getTaskId());

			if (hideCompleted && done)
			{
				continue;
			}

			if (!"All".equals(filterType))
			{
				String category = task.getCategory() != null ? task.getCategory() : "";
				if (!filterType.equals(category))
				{
					continue;
				}
			}

			if (!globalTasksSearchText.isEmpty())
			{
				String name = task.getName() == null ? "" : task.getName().toLowerCase();
				if (!name.contains(globalTasksSearchText))
				{
					continue;
				}
			}

			matching.add(task);
			matchingPoints += task.getBasePoints();
		}

		int shown = matching.size();

		if (shown > 0)
		{
			// Mirrors the Completed Tasks summary line, and makes it obvious the
			// list is filtered rather than empty/broken.
			addLabel(globalTasksContentPanel, "Showing " + shown + " tasks (" + matchingPoints + " pts)",
				FontManager.getRunescapeSmallFont(), FLAME);
			globalTasksContentPanel.add(Box.createVerticalStrut(5));
		}

		for (NuzlockeTask task : matching)
		{
			boolean done = task.isCompleted() || completedIds.contains(task.getTaskId());
			globalTasksContentPanel.add(createGlobalTaskRow(task, done));
			globalTasksContentPanel.add(Box.createVerticalStrut(4));
		}

		if (shown == 0)
		{
			addLabel(globalTasksContentPanel,
				plugin.getVisibleGlobalTasks().isEmpty() ? "No global tasks loaded" : "No tasks match the filter",
				FontManager.getRunescapeSmallFont(), Color.GRAY);
		}

		globalTasksContentPanel.revalidate();
		globalTasksContentPanel.repaint();

		SwingUtilities.invokeLater(() ->
			globalTasksScrollPane.getViewport().setViewPosition(savedViewPos));
	}

	/**
	 * One card per global task, matching the Completed Tasks card style.
	 *
	 * The first version put the name and the points on one BorderLayout row
	 * (name CENTER, points EAST). Quest names are long, and a plain JLabel
	 * reports its full text width as its preferred size, so the row grew wider
	 * than the viewport and — with HORIZONTAL_SCROLLBAR_NEVER — the points label
	 * was pushed off the right edge and clipped ("5p"). WrappingTextLabel is
	 * bounded to TASK_TEXT_WRAP_WIDTH and wraps instead of growing, and the
	 * points now sit on their own line, so nothing can be cut off.
	 *
	 * Still deliberately lighter than createActiveTaskItem, which adds a
	 * progress bar and recursive mouse handlers per row — fine for 4 rolled
	 * tasks, wasteful for ~200. Quest tasks are pass/fail, so there is no
	 * progress to draw.
	 */
	private JPanel createGlobalTaskRow(NuzlockeTask task, boolean done)
	{
		// Tier colour by points, settled back once complete.
		int pts = task.getBasePoints();
		JPanel card = createCardPanel(
			done ? dim(tierFill(pts), 0.30f) : tierFill(pts),
			done ? dim(tierBorder(pts), 0.25f) : tierBorder(pts));
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		// Left inset (12) clears the orange accent bar.
		card.setBorder(new EmptyBorder(5, 12, 6, 6));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(CONTENT_WIDTH - 10, Integer.MAX_VALUE));

		WrappingTextLabel nameLabel = new WrappingTextLabel(
			(done ? "✓ " : "") + task.getName(),
			FontManager.getRunescapeSmallFont(),
			done ? new Color(100, 200, 100) : Color.WHITE,
			TASK_TEXT_WRAP_WIDTH);
		card.add(nameLabel);

		// Info line mirrors the completed cards: "Quest  +2 pts".
		String category = task.getCategory() != null ? NuzlockeTask.displayCategory(task.getCategory()) : "Global";
		addLabel(card, category + "  +" + task.getBasePoints() + " pts",
			FontManager.getRunescapeSmallFont(), done ? new Color(170, 130, 60) : Color.ORANGE);

		return card;
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
		JPanel headerPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 6, 3, 6)
		));
		// Fixed size - never changes
		headerPanel.setPreferredSize(new Dimension(PANEL_WIDTH - 10, HEADER_HEIGHT));
		headerPanel.setMinimumSize(new Dimension(PANEL_WIDTH - 10, HEADER_HEIGHT));
		headerPanel.setMaximumSize(new Dimension(PANEL_WIDTH - 10, HEADER_HEIGHT));

		// Title row with Discord button
		JPanel titleRow = styledPanel(new BorderLayout(3, 0), ColorScheme.DARKER_GRAY_COLOR);
		titleRow.setAlignmentX(CENTER_ALIGNMENT);
		titleRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 20));

		// Orange title
		titleRow.add(styledLabel("ChunkBlazer", FontManager.getRunescapeBoldFont(), new Color(255, 152, 0)), BorderLayout.WEST);

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
		JPanel infoRow = styledPanel(new FlowLayout(FlowLayout.LEFT, 0, 0), ColorScheme.DARKER_GRAY_COLOR);
		infoRow.setAlignmentX(CENTER_ALIGNMENT);

		regionLabel = styledLabel("Unknown (0)", FontManager.getRunescapeSmallFont(), Color.WHITE);

		modeLabel = styledLabel(" | Mode: --", FontManager.getRunescapeSmallFont(), new Color(0, 200, 200));

		infoRow.add(regionLabel);
		infoRow.add(modeLabel);
		headerPanel.add(infoRow);

		return headerPanel;
	}

	private JPanel createStatsSection()
	{
		JPanel statsPanel = styledPanel(new GridLayout(1, 4, 2, 0), ColorScheme.DARKER_GRAY_COLOR);
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

		// Boss Tokens — secondary currency, shown last (far right) for a cleaner layout.
		JPanel tokensPanel = createStatBox("Tokens", "2");
		bossTokensLabel = (JLabel) ((JPanel) tokensPanel.getComponent(0)).getComponent(1);
		statsPanel.add(tokensPanel);

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
		JPanel panel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
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
		JPanel panel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
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
		JPanel headerRow = styledPanel(new BorderLayout(4, 0), ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 20));

		headerRow.add(styledLabel("Unlocked Chunks (" + names.size() + ")",
			FontManager.getRunescapeBoldFont(), Color.WHITE), BorderLayout.WEST);

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
				addLabel(unlockedListPanel, "No chunks unlocked yet.",
					FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR);
			}
			else
			{
				// One card per chunk, same shape as the Global Tasks / Completed
				// Tasks cards so the whole panel reads as one system.
				JPanel chunkList = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
				chunkList.setAlignmentX(LEFT_ALIGNMENT);
				for (String n : names)
				{
					chunkList.add(createUnlockedChunkRow(n));
					chunkList.add(Box.createVerticalStrut(4));
				}

				// Fixed-height scroll area, matching the task sections.
				JScrollPane chunkScroll = new JScrollPane(chunkList);
				chunkScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
				chunkScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
				chunkScroll.setBorder(null);
				chunkScroll.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				chunkScroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
				chunkScroll.setAlignmentX(LEFT_ALIGNMENT);
				chunkScroll.getVerticalScrollBar().setUnitIncrement(16);

				int height = Math.min(MAX_UNLOCKED_CHUNKS_HEIGHT,
					Math.max(40, chunkList.getPreferredSize().height + 8));
				chunkScroll.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
				chunkScroll.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
				chunkScroll.setMaximumSize(new Dimension(CONTENT_WIDTH, height));

				unlockedListPanel.add(chunkScroll);
			}
		}
		else if (!names.isEmpty())
		{
			// Collapsed hint, matching the other collapsible sections.
			addLabel(unlockedListPanel, "Click to view " + names.size() + " unlocked chunks",
				FontManager.getRunescapeSmallFont(), Color.GRAY);
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

		// Boss chunks are unlocked with a Boss Token, not points — render that UI
		// instead of the points cost/button below.
		if (plugin.isBossRegion(regionId))
		{
			renderBossUnlockSection(regionId, regionName);
			return;
		}

		int cost = plugin.getRegionUnlockCost(regionId);
		int points = plugin.getTotalPoints();
		boolean canAfford = points >= cost;

		regionUnlockPanel.removeAll();

		addLabel(regionUnlockPanel, "🔒 Locked Region", FontManager.getRunescapeBoldFont(), Color.WHITE);
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

		addLabel(regionUnlockPanel, "Cost: " + cost + " pts | You have: " + points,
			FontManager.getRunescapeSmallFont(), canAfford ? new Color(150, 255, 150) : new Color(255, 130, 130));
		regionUnlockPanel.add(Box.createVerticalStrut(6));

		// Two-state button: shows the cost, then on click swaps to "Confirm? Yes/No".
		// Inline confirmation avoids a popup dialog and keeps everything in the panel.
		final int finalRegionId = regionId;
		final String finalRegionName = regionName;
		final int finalCost = cost;
		JPanel buttonRow = styledPanel(new BorderLayout(4, 0), ColorScheme.DARKER_GRAY_COLOR);
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
			JButton unlockBtn = actionButton("Unlock for " + cost + " pts", new Color(50, 110, 60));
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
	 * Render the locked-region prompt for a BOSS chunk: costs one Boss Token (not
	 * points) and grants every task on unlock. Mirrors the points layout but reads
	 * the token balance and routes to {@code unlockBossRegion}.
	 */
	private void renderBossUnlockSection(int regionId, String regionName)
	{
		int tokens = plugin.getBossTokens();
		boolean canAfford = tokens > 0;

		regionUnlockPanel.removeAll();

		addLabel(regionUnlockPanel, "🔒 Boss Chunk", FontManager.getRunescapeBoldFont(), Color.WHITE);
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

		addLabel(regionUnlockPanel, "Cost: 1 Boss Token | You have: " + tokens,
			FontManager.getRunescapeSmallFont(), canAfford ? new Color(150, 255, 150) : new Color(255, 130, 130));
		regionUnlockPanel.add(Box.createVerticalStrut(4));

		addLabel(regionUnlockPanel, "Unlocking grants every task on this chunk.",
			FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);
		regionUnlockPanel.add(Box.createVerticalStrut(4));

		WrappingTextLabel tokenNote = new WrappingTextLabel(
			"This will cost 1 boss token. You will need to defeat this boss to gain another.",
			FontManager.getRunescapeSmallFont(),
			new Color(230, 200, 120),
			TASK_TEXT_WRAP_WIDTH);
		tokenNote.setAlignmentX(LEFT_ALIGNMENT);
		regionUnlockPanel.add(tokenNote);
		regionUnlockPanel.add(Box.createVerticalStrut(6));

		final int finalRegionId = regionId;
		JPanel buttonRow = styledPanel(new BorderLayout(4, 0), ColorScheme.DARKER_GRAY_COLOR);
		buttonRow.setAlignmentX(LEFT_ALIGNMENT);
		buttonRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 28));

		if (!canAfford)
		{
			JButton disabled = new JButton("Need a Boss Token");
			disabled.setEnabled(false);
			disabled.setFont(FontManager.getRunescapeSmallFont());
			disabled.setFocusPainted(false);
			buttonRow.add(disabled, BorderLayout.CENTER);
		}
		else
		{
			JButton unlockBtn = actionButton("Unlock for 1 Boss Token", new Color(50, 110, 60));
			unlockBtn.addActionListener(e ->
			{
				plugin.closeChatboxPrompt();
				plugin.unlockBossRegion(finalRegionId);
				mapUnlockRegionId = -1;
				updateRegionUnlockSection();
				updateStats();
			});
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

		buttonRow.add(styledLabel("Spend " + cost + "?", FontManager.getRunescapeSmallFont(), Color.WHITE), BorderLayout.WEST);

		JPanel choices = styledPanel(new GridLayout(1, 2, 4, 0), ColorScheme.DARKER_GRAY_COLOR);

		JButton yes = actionButton("Yes", new Color(50, 110, 60));
		yes.addActionListener(e ->
		{
			// Close any open chatbox unlock-prompt for this region first. If we
			// don't, the prompt sits there waiting for a click, and a stray Yes
			// would fire unlockRegion a second time. The plugin also has an
			// idempotency guard inside unlockRegion as a backstop.
			plugin.closeChatboxPrompt();
			plugin.unlockRegion(regionId);
			// Clear any world-map click pin; the region is unlocked now.
			mapUnlockRegionId = -1;
			// updateRegionUnlockSection will be called by updateRegionDisplay /
			// updateStats and hide the section now that the region is unlocked.
			updateRegionUnlockSection();
			updateStats();
		});

		JButton no = actionButton("No", new Color(110, 50, 50));
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
		JPanel box = styledPanel(new BorderLayout(), ColorScheme.DARKER_GRAY_COLOR);

		JPanel innerPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);

		// muted grey
		JLabel labelText = styledLabel(label.toUpperCase(), new Font("Arial", Font.PLAIN, 9), new Color(150, 150, 150));
		labelText.setAlignmentX(CENTER_ALIGNMENT);

		// gold
		JLabel valueText = styledLabel(value, FontManager.getRunescapeBoldFont().deriveFont(15f), FLAME);
		valueText.setAlignmentX(CENTER_ALIGNMENT);

		innerPanel.add(labelText);
		innerPanel.add(valueText);
		box.add(innerPanel, BorderLayout.CENTER);

		return box;
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

		// Warning text
		addLabel(modePanel, "<html><i>This choice is permanent for this account!</i></html>",
			FontManager.getRunescapeSmallFont(), Color.YELLOW);
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
		addLabel(modePanel, "<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>"
			+ "Start anywhere. Play on any account. Featured on the casual leaderboard."
			+ "</td></tr></table></html>", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);
		modePanel.add(Box.createVerticalStrut(5));

		nuzlockeRadio = new JRadioButton("Competitive");
		nuzlockeRadio.setToolTipText("Featured on the main page of the leaderboard and website. You must start on a fresh level 3 account.");
		nuzlockeRadio.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		nuzlockeRadio.setForeground(Color.WHITE);
		nuzlockeRadio.setAlignmentX(LEFT_ALIGNMENT);
		modeGroup.add(nuzlockeRadio);
		modePanel.add(nuzlockeRadio);

		addLabel(modePanel, "<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>"
			+ "Featured on the main page of the leaderboard and website. You must start on a fresh level 3 account."
			+ "</td></tr></table></html>", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);
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
		JPanel panel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(10, 10, 10, 10)
		));

		addLabel(panel, "Game Mode", FontManager.getRunescapeBoldFont(), Color.WHITE);
		panel.add(Box.createVerticalStrut(5));

		lockedModeValueLabel = addLabel(panel, "Casual", FontManager.getRunescapeBoldFont(), new Color(100, 200, 100));

		addLabel(panel, "Locked for this account", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);

		return panel;
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

		addLabel(panel, "<html>Log into Old School RuneScape to start playing ChunkBlazer.</html>",
			FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);

		return panel;
	}

	private JPanel createCurrentTaskSection()
	{
		JPanel taskPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		taskPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(100, 180, 100)),
			new EmptyBorder(6, 6, 6, 6)
		));
		taskPanel.setAlignmentX(LEFT_ALIGNMENT);

		// === SELECTED TASK HIGHLIGHT BOX ===
		selectedTaskPanel = boxPanel(new Color(60, 80, 60));
		selectedTaskPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(FLAME, 2), // Gold border
			new EmptyBorder(6, 6, 6, 6)
		));
		selectedTaskPanel.setAlignmentX(LEFT_ALIGNMENT);
		selectedTaskPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 135));
		selectedTaskPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 135));

		// Star symbols; gold
		addLabel(selectedTaskPanel, "\u2605 SELECTED TASK \u2605", FontManager.getRunescapeBoldFont(), FLAME);

		JLabel selectedTaskName = styledLabel("Click a task below to select", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);
		selectedTaskName.setAlignmentX(LEFT_ALIGNMENT);
		selectedTaskName.setName("selectedTaskName");
		selectedTaskPanel.add(selectedTaskName);

		selectedTaskPanel.setVisible(false); // Hidden until a task is selected
		taskPanel.add(selectedTaskPanel);
		taskPanel.add(Box.createVerticalStrut(4));

		// === HEADER ROW WITH TOGGLE ===
		JPanel headerRow = styledPanel(new BorderLayout(5, 0), ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 22));
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 22));

		activeTasksSectionTitle = styledLabel("Active Tasks", FontManager.getRunescapeBoldFont(), new Color(100, 255, 100));
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
		activeTasksFilterPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		activeTasksFilterPanel.setAlignmentX(LEFT_ALIGNMENT);

		// Search field
		JPanel searchRow = styledPanel(new BorderLayout(5, 0), ColorScheme.DARKER_GRAY_COLOR);
		searchRow.setAlignmentX(LEFT_ALIGNMENT);
		searchRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 22));
		searchRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 22));

		searchRow.add(styledLabel("Search:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.WEST);

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
		JPanel areaRow = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);
		areaRow.setAlignmentX(LEFT_ALIGNMENT);
		areaRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 40));
		areaRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 40));

		areaRow.add(styledLabel("Area:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

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
		JPanel filterRow = styledPanel(new GridLayout(1, 2, 4, 0), ColorScheme.DARKER_GRAY_COLOR);
		filterRow.setAlignmentX(LEFT_ALIGNMENT);
		filterRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 40));
		filterRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 40));

		// Category filter
		JPanel categoryPanel = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);

		categoryPanel.add(styledLabel("Category:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

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
		JPanel regionPanel = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);

		regionPanel.add(styledLabel("Chunk:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

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

		// Tier (points) filter — mirrors the card colours.
		JPanel tierRow = styledPanel(new BorderLayout(2, 0), ColorScheme.DARKER_GRAY_COLOR);
		tierRow.setAlignmentX(LEFT_ALIGNMENT);
		tierRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 40));
		tierRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 40));

		tierRow.add(styledLabel("Tier:", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY), BorderLayout.NORTH);

		activeTasksTierCombo = new JComboBox<>(TIER_NAMES);
		activeTasksTierCombo.setFont(FontManager.getRunescapeSmallFont());
		activeTasksTierCombo.setToolTipText("Filter active tasks by point value");
		activeTasksTierCombo.addActionListener(e ->
		{
			if (!isRefreshingFilters)
			{
				activeTasksSelectedTier = tierPointsForLabel(activeTasksTierCombo.getSelectedItem());
				updateActiveTasksDisplay();
			}
		});
		tierRow.add(activeTasksTierCombo, BorderLayout.CENTER);

		activeTasksFilterPanel.add(tierRow);
		activeTasksFilterPanel.add(Box.createVerticalStrut(4));

		// Boss-chunk filter — show only tasks from boss chunks (raids / bosses).
		activeTasksBossOnlyCheck = new JCheckBox("Boss chunk tasks only");
		activeTasksBossOnlyCheck.setFont(FontManager.getRunescapeSmallFont());
		activeTasksBossOnlyCheck.setForeground(Color.LIGHT_GRAY);
		activeTasksBossOnlyCheck.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activeTasksBossOnlyCheck.setToolTipText("Show only tasks from boss chunks (raids / bosses)");
		activeTasksBossOnlyCheck.setAlignmentX(LEFT_ALIGNMENT);
		activeTasksBossOnlyCheck.addActionListener(e ->
		{
			if (!isRefreshingFilters)
			{
				activeTasksBossOnly = activeTasksBossOnlyCheck.isSelected();
				updateActiveTasksDisplay();
			}
		});
		activeTasksFilterPanel.add(activeTasksBossOnlyCheck);
		activeTasksFilterPanel.add(Box.createVerticalStrut(4));

		taskPanel.add(activeTasksFilterPanel);

		// === SCROLLABLE TASK LIST ===
		activeTasksContentPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);

		activeTasksScrollPane = new JScrollPane(activeTasksContentPanel);
		activeTasksScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		activeTasksScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		activeTasksScrollPane.setBorder(null);
		activeTasksScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activeTasksScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activeTasksScrollPane.setAlignmentX(LEFT_ALIGNMENT);

		// Placeholder
		taskNameLabel = addLabel(activeTasksContentPanel, "Loading tasks...", FontManager.getRunescapeSmallFont(), Color.LIGHT_GRAY);

		taskPanel.add(activeTasksScrollPane);

		// === COLLAPSED LABEL ===
		activeTasksCollapsedLabel = styledLabel("Click to view tasks", FontManager.getRunescapeSmallFont(), Color.GRAY);
		activeTasksCollapsedLabel.setAlignmentX(LEFT_ALIGNMENT);
		activeTasksCollapsedLabel.setVisible(false); // Hidden when expanded
		taskPanel.add(activeTasksCollapsedLabel);

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
			// Populate categories from all active tasks. Fold "_Set" pools
			// (Herblore_Set, Obtain_Set) onto the base skill so they list once.
			Set<String> categories = plugin.getActiveTasks().stream()
				.map(NuzlockeTask::getCategory)
				.filter(c -> c != null && !c.isEmpty())
				.map(NuzlockeTask::displayCategory)
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
		JPanel headerRow = styledPanel(new BorderLayout(5, 0), selectedTaskPanel.getBackground());
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 22));

		headerRow.add(styledLabel("\u2605 SELECTED TASK \u2605", FontManager.getRunescapeBoldFont(), FLAME), BorderLayout.WEST);

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

		// Description ("how to complete this"), wrapped, right under the title.
		String selDesc = selectedTask.getDescription();
		if (selDesc != null && !selDesc.trim().isEmpty())
		{
			WrappingTextLabel selDescLabel = new WrappingTextLabel(
				selDesc.trim(),
				FontManager.getRunescapeSmallFont(),
				new Color(200, 200, 200),
				TASK_TEXT_WRAP_WIDTH);
			selDescLabel.setAlignmentX(LEFT_ALIGNMENT);
			selectedTaskPanel.add(selDescLabel);
		}

		// Category and points
		String info = NuzlockeTask.displayCategory(selectedTask.getCategory()) + " | " + selectedTask.getBasePoints() + " pts";
		if (selectedTask.getLevelRequirement() > 1)
		{
			info += " | L" + selectedTask.getLevelRequirement();
		}
		addLabel(selectedTaskPanel, info, FontManager.getRunescapeSmallFont(), new Color(255, 200, 100));

		// Region + Area rows — gives the player full context for the highlighted task.
		// getTaskRegionName already includes the numeric ID in "ChunkName (id)" form.
		String selRegionName = plugin.getTaskRegionName(selectedTask);
		String regionRow = (selRegionName != null && !selRegionName.isEmpty())
			? "Chunk: " + selRegionName
			: "Chunk: unknown";
		addLabel(selectedTaskPanel, regionRow, FontManager.getRunescapeSmallFont(), new Color(140, 200, 230));

		String selArea = plugin.getTaskArea(selectedTask);
		if (selArea != null && !selArea.isEmpty())
		{
			addLabel(selectedTaskPanel, "Area: " + selArea, FontManager.getRunescapeSmallFont(), new Color(180, 180, 220));
		}

		// Progress
		int progress = selectedTask.getCurrentProgress();
		int target = selectedTask.getTargetQuantity();
		float pct = target > 0 ? (float) progress / target : 0;
		pct = Math.min(pct, 1.0f);

		JPanel progressRow = styledPanel(new BorderLayout(4, 0), new Color(60, 80, 60));
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

		progressRow.add(styledLabel(progress + "/" + target, FontManager.getRunescapeSmallFont(), Color.WHITE), BorderLayout.EAST);

		selectedTaskPanel.add(Box.createVerticalStrut(4));
		selectedTaskPanel.add(progressRow);

		selectedTaskPanel.revalidate();
		selectedTaskPanel.repaint();
	}

	private JPanel createTaskListSection()
	{
		JPanel listPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
		listPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 6, 6, 6)
		));
		listPanel.setAlignmentX(LEFT_ALIGNMENT);

		// Header row with toggle button
		JPanel headerRow = styledPanel(new BorderLayout(5, 0), ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setAlignmentX(LEFT_ALIGNMENT);
		headerRow.setPreferredSize(new Dimension(CONTENT_WIDTH, 25));
		headerRow.setMaximumSize(new Dimension(CONTENT_WIDTH, 25));

		headerRow.add(styledLabel("Chunk Tasks", FontManager.getRunescapeBoldFont(), Color.WHITE), BorderLayout.WEST);

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
		taskListContentPanel = boxPanel(ColorScheme.DARKER_GRAY_COLOR);
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
		addLabel(listPanel, "Click to view tasks", FontManager.getRunescapeSmallFont(), Color.GRAY);

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
			// updateTaskListContent() sizes the scroll pane to the actual (height-capped)
			// cards, up to MAX_TASK_LIST_HEIGHT — don't re-set a fixed height here or it
			// re-introduces the big empty block below short task lists.
			updateTaskListContent();
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

	private void onRerollTask()
	{
		try
		{
			plugin.rerollTask();

			// Force update on EDT
			SwingUtilities.invokeLater(() ->
			{
				updateTaskDisplay();
				updateTaskList();
				updateActiveTasksDisplay();
				revalidate();
				repaint();
			});
		}
		catch (Exception e)
		{
			log.error(">>> Dev: Reroll FAILED with exception: ", e);
		}
	}

	private void openLogFile()
	{
		// Resolve under RuneLite's own dir (never a hardcoded user.home path) and
		// hand off to LinkBrowser.open — the Hub-compliant file opener. Avoids
		// both Runtime.exec (external process, banned) and java.awt.Desktop.
		java.io.File logFile = new java.io.File(RuneLite.RUNELITE_DIR, "logs/client.log");
		if (!logFile.exists())
		{
			JOptionPane.showMessageDialog(this,
				"Log file not found at:\n" + logFile.getAbsolutePath(),
				"Log File Not Found",
				JOptionPane.WARNING_MESSAGE);
			return;
		}
		LinkBrowser.open(logFile.getAbsolutePath());
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
			globalTasksPanel.setVisible(loggedIn);
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
			updateGlobalTasks();
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
		bossTokensLabel.setText(String.valueOf(plugin.getBossTokens()));
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

		// Capture scroll position so the rebuild below doesn't snap the user back to the top
		// when a chunk is unlocked or a task completes.
		final java.awt.Point savedActiveViewPos = activeTasksScrollPane.getViewport().getViewPosition();

		// Refresh filter dropdowns
		refreshActiveTasksFilters();

		// Only rebuild the scrollable content, not the whole panel
		activeTasksContentPanel.removeAll();

		List<NuzlockeTask> allTasks = plugin.getActiveTasks();

		// Cache filter values
		final String filterText = activeTasksSearchText != null ? activeTasksSearchText : "";
		final String filterCategory = activeTasksSelectedCategory != null ? activeTasksSelectedCategory : "All";
		final String filterRegion = activeTasksSelectedRegion != null ? activeTasksSelectedRegion : "All";
		final String filterArea = activeTasksSelectedArea != null ? activeTasksSelectedArea : "All";

		// Filter tasks based on search text, category, region, area, and tier
		final int filterTier = activeTasksSelectedTier;
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
					String taskCategory = NuzlockeTask.displayCategory(task.getCategory() != null ? task.getCategory() : "");
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
				// Tier filter — 0 means All.
				if (filterTier > 0 && task.getBasePoints() != filterTier)
				{
					return false;
				}
				// Boss-chunk-only filter.
				if (activeTasksBossOnly && !plugin.isBossTask(task))
				{
					return false;
				}
				return true;
			})
			.collect(Collectors.toList());

		if (filteredTasks.isEmpty())
		{
			String message = allTasks.isEmpty() ? "No active tasks" : "No tasks match filter";
			addLabel(activeTasksContentPanel, message, FontManager.getRunescapeSmallFont(), Color.GRAY);
		}
		else
		{
			// Summary line, matching Completed Tasks and Global Tasks. Points here
			// are what's STILL ON OFFER (base_points of tasks not yet done), not
			// points banked — these tasks are by definition incomplete.
			int availablePoints = filteredTasks.stream().mapToInt(NuzlockeTask::getBasePoints).sum();
			addLabel(activeTasksContentPanel,
				"Showing " + filteredTasks.size() + " tasks (" + availablePoints + " pts available)",
				FontManager.getRunescapeSmallFont(), FLAME);
			activeTasksContentPanel.add(Box.createVerticalStrut(5));

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
	/**
	 * Card backdrop by task VALUE, matching the in-game task banners:
	 * 1pt blue, 2pt green, 3pt purple, 4pt gold (Elite), 5pt red (Master).
	 *
	 * Colour carries the tier at a glance, so it has to be consistent across
	 * every task list in the panel — Active, Completed, Chunk and Global all
	 * call these rather than hardcoding their own navy.
	 *
	 * Values are muted rather than saturated: white body text sits on top of
	 * these, and the panel background is near-black, so a bright fill would
	 * both hurt contrast and fight the rest of the UI.
	 */
	/**
	 * Tier labels for the points filters. Index = base_points, so TIER_NAMES[3]
	 * is the 3pt tier. Order follows the OSRS combat-achievement convention
	 * (Easy < Medium < Hard < Elite < Master) and pairs with tierFill(): 1 blue,
	 * 2 green, 3 purple, 4 gold, 5 red.
	 */
	private static final String[] TIER_NAMES = {"All", "Easy", "Medium", "Hard", "Elite", "Master"};

	/** Combo label -> base_points, or 0 for "All"/unknown. */
	private static int tierPointsForLabel(Object label)
	{
		if (label == null)
		{
			return 0;
		}
		for (int i = 1; i < TIER_NAMES.length; i++)
		{
			if (TIER_NAMES[i].equals(label.toString()))
			{
				return i;
			}
		}
		return 0;
	}

	private static Color tierFill(int basePoints)
	{
		switch (basePoints)
		{
			case 1:  return new Color(52, 84, 104);   // blue   — Novice
			case 2:  return new Color(52, 92, 50);    // green  — Intermediate
			case 3:  return new Color(88, 56, 100);   // purple — Hard
			case 4:  return new Color(122, 96, 34);   // gold   — Elite
			case 5:  return new Color(112, 52, 50);   // red    — Master
			default: return new Color(30, 40, 60);    // unscored/unknown: old navy
		}
	}

	private static Color tierBorder(int basePoints)
	{
		switch (basePoints)
		{
			case 1:  return new Color(86, 128, 152);
			case 2:  return new Color(88, 136, 84);
			case 3:  return new Color(132, 92, 148);
			case 4:  return new Color(176, 142, 60);  // gold  — Elite
			case 5:  return new Color(160, 88, 84);   // red   — Master
			default: return new Color(60, 74, 100);
		}
	}

	/** Blend toward black — used to settle completed//already-assigned cards back. */
	private static Color dim(Color c, float amount)
	{
		float k = 1f - Math.max(0f, Math.min(1f, amount));
		return new Color(Math.round(c.getRed() * k), Math.round(c.getGreen() * k), Math.round(c.getBlue() * k));
	}

	/** Blend toward white — used for hover / selection. */
	private static Color lighten(Color c, float amount)
	{
		float k = Math.max(0f, Math.min(1f, amount));
		return new Color(
			Math.round(c.getRed()   + (255 - c.getRed())   * k),
			Math.round(c.getGreen() + (255 - c.getGreen()) * k),
			Math.round(c.getBlue()  + (255 - c.getBlue())  * k));
	}

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
	/**
	 * One unlocked-chunk card, mirroring the Global Tasks / Completed Tasks card
	 * shape so the side panel reads as one system.
	 *
	 * Green rather than the tasks' navy: an unlocked chunk is something you own
	 * outright, not something in progress, and the green keeps it from reading
	 * as another task list at a glance.
	 *
	 * Input is "Chunk Name (regionId)" from getUnlockedChunkDisplayNames(); the
	 * id is split onto the info line so the name can wrap on its own.
	 */
	private JPanel createUnlockedChunkRow(String fullText)
	{
		String name = fullText;
		String region = null;
		int paren = fullText.lastIndexOf(" (");
		if (paren > 0 && fullText.endsWith(")"))
		{
			name = fullText.substring(0, paren);
			region = fullText.substring(paren + 2, fullText.length() - 1);
		}

		JPanel card = createCardPanel(new Color(26, 46, 32), new Color(58, 96, 66));
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		// Left inset (12) clears the accent bar, same as the task cards.
		card.setBorder(new EmptyBorder(5, 12, 6, 6));
		card.setAlignmentX(LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(CONTENT_WIDTH - 10, Integer.MAX_VALUE));

		WrappingTextLabel nameLabel = new WrappingTextLabel(
			"✓ " + name,
			FontManager.getRunescapeSmallFont(),
			new Color(120, 215, 120),
			TASK_TEXT_WRAP_WIDTH);
		card.add(nameLabel);

		addLabel(card, region != null ? "Chunk " + region : "Unlocked",
			FontManager.getRunescapeSmallFont(), new Color(150, 190, 150));

		return card;
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
				Color tier = tierFill(task.getBasePoints());
				g2.setColor(isSelected ? lighten(tier, 0.22f)
					: (hovered[0] ? lighten(tier, 0.12f) : tier));
				g2.fill(box);
				// Flame-orange left accent bar, clipped to the rounded shape.
				java.awt.Shape oldClip = g2.getClip();
				g2.clip(box);
				g2.setColor(FLAME);
				g2.fillRect(0, 0, 5, h);
				g2.setClip(oldClip);
				g2.setColor(isSelected ? FLAME : tierBorder(task.getBasePoints()));
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
		infoText.append(NuzlockeTask.displayCategory(task.getCategory()));
		infoText.append("  ").append(task.getBasePoints()).append("pt");
		if (task.getLevelRequirement() > 1)
		{
			infoText.append("  L").append(task.getLevelRequirement());
		}
		addLabel(itemPanel, infoText.toString(),
			FontManager.getRunescapeSmallFont(), isSelected ? new Color(255, 200, 100) : Color.ORANGE);

		// Region subtitle: friendly name + numeric region ID, so a glance tells the
		// player which chunk rolled the task. getTaskRegionName already returns the
		// composed "ChunkName (regionId)" string — don't append the ID again.
		String activeRegionName = plugin.getTaskRegionName(task);
		String regionText = (activeRegionName != null && !activeRegionName.isEmpty())
			? "Chunk: " + activeRegionName
			: "Chunk: unknown";
		addLabel(itemPanel, regionText, FontManager.getRunescapeSmallFont(), new Color(140, 200, 230));

		// Optional "how to complete this" description, wrapped. Especially useful for
		// raid/boss tasks whose name alone doesn't spell out the requirement
		// (e.g. "Strapped Sands" -> "less than 10m in equipped gear").
		String activeDesc = task.getDescription();
		if (activeDesc != null && !activeDesc.trim().isEmpty())
		{
			WrappingTextLabel descLabel = new WrappingTextLabel(
				activeDesc.trim(),
				FontManager.getRunescapeSmallFont(),
				new Color(185, 185, 185),
				TASK_TEXT_WRAP_WIDTH);
			descLabel.setAlignmentX(LEFT_ALIGNMENT);
			itemPanel.add(Box.createVerticalStrut(2));
			itemPanel.add(descLabel);
		}

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

		progressRow.add(styledLabel(progress + "/" + target, FontManager.getRunescapeSmallFont(), Color.WHITE), BorderLayout.EAST);

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
		final int filterTier = completedTasksSelectedTier;

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
					String cat = NuzlockeTask.displayCategory(info.getCategory() != null ? info.getCategory() : "");
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
				if (filterTier > 0 && info.getPoints() != filterTier)
				{
					return false;
				}
				if (!"All".equals(filterArea))
				{
					// Global tasks have no chunk; getAreaForRegionId(-1) is null,
					// so they need the shared bucket lookup or every specific
					// area selection would silently hide them.
					String area = plugin.getAreaForCompletedTask(info.getTaskId(), info.getRegionId());
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
			addLabel(completedTasksContentPanel, message, FontManager.getRunescapeSmallFont(), Color.GRAY);
		}
		else
		{
			int totalPoints = filteredTasks.stream().mapToInt(CompletedTaskInfo::getPoints).sum();
			addLabel(completedTasksContentPanel, "Showing " + filteredTasks.size() + " tasks (" + totalPoints + " pts)",
				FontManager.getRunescapeSmallFont(), FLAME);
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
		// Tier colour, settled back a little because it's already done.
		JPanel itemPanel = createCardPanel(
			dim(tierFill(info.getPoints()), 0.30f),
			dim(tierBorder(info.getPoints()), 0.25f));
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

		// Description ("what this task was"), looked up from the catalog by id since
		// the completed-task record itself doesn't carry it. Skipped for tasks the
		// catalog no longer knows (getTaskById == null).
		NuzlockeTask completedDef = info.getTaskId() != null ? plugin.getTaskById(info.getTaskId()) : null;
		String completedDesc = completedDef != null ? completedDef.getDescription() : null;
		if (completedDesc != null && !completedDesc.trim().isEmpty())
		{
			WrappingTextLabel completedDescLabel = new WrappingTextLabel(
				completedDesc.trim(),
				FontManager.getRunescapeSmallFont(),
				new Color(170, 170, 170),
				TASK_TEXT_WRAP_WIDTH);
			completedDescLabel.setAlignmentX(LEFT_ALIGNMENT);
			itemPanel.add(completedDescLabel);
		}

		// Info line: Category | Points
		String infoText = NuzlockeTask.displayCategory(info.getCategory()) + "  +" + info.getPoints() + " pts";
		addLabel(itemPanel, infoText, FontManager.getRunescapeSmallFont(), Color.ORANGE);

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
			addLabel(taskListContentPanel, "No tasks rolled for this region", FontManager.getRunescapeSmallFont(), Color.GRAY);
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
				addLabel(taskListContentPanel, "No tasks match filter", FontManager.getRunescapeSmallFont(), Color.GRAY);
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
		// Tier colour carries the points; state is carried by brightness and, for
		// the active task, the flame border — so both read at once.
		int tierPts = task.getBasePoints();
		if (isActive)
		{
			cardFill = lighten(tierFill(tierPts), 0.12f);
			cardBorder = FLAME;
		}
		else if (isAssigned)
		{
			cardFill = dim(tierFill(tierPts), 0.40f);
			cardBorder = dim(tierBorder(tierPts), 0.35f);
		}
		else
		{
			cardFill = tierFill(tierPts);
			cardBorder = tierBorder(tierPts);
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
			infoPanel.add(styledLabel("ACTIVE", FontManager.getRunescapeSmallFont(), new Color(100, 255, 100)));
		}
		else if (isAssigned)
		{
			infoPanel.add(styledLabel("DONE", FontManager.getRunescapeSmallFont(), Color.GRAY));
		}

		if (task.getLevelRequirement() > 1)
		{
			infoPanel.add(styledLabel("Lv" + task.getLevelRequirement(), FontManager.getRunescapeSmallFont(), Color.ORANGE));
		}

		infoPanel.add(styledLabel(task.getBasePoints() + "pt",
			FontManager.getRunescapeSmallFont(), isAssigned ? Color.GRAY : new Color(100, 200, 100)));

		itemPanel.add(infoPanel);

		return itemPanel;
	}
}
