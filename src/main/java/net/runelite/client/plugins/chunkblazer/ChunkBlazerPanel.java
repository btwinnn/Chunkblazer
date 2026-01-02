package net.runelite.client.plugins.chunkblazer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
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
    private JPanel modeSelectionPanel;
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
    private String completedTasksSearchText = "";
    private String selectedCategory = "All";
    private String selectedRegion = "All";
    private JPanel completedTasksFilterPanel;
    private JLabel completedCollapsedLabel;
    private boolean isRefreshingFilters = false; // Prevent event loops during filter refresh

    // Active Tasks Components
    private JPanel selectedTaskPanel;
    private JTextField activeTasksSearchField;
    private JComboBox<String> activeTasksCategoryCombo;
    private JComboBox<String> activeTasksRegionCombo;
    private String activeTasksSearchText = "";
    private String activeTasksSelectedCategory = "All";
    private String activeTasksSelectedRegion = "All";
    private boolean isRefreshingActiveFilters = false;
    private NuzlockeTask selectedTask = null;

    public ChunkBlazerPanel()
    {
        super(false);
    }

    private static final int PANEL_WIDTH = 225; // Standard RuneLite panel width
    private static final int CONTENT_WIDTH = PANEL_WIDTH - 24; // Width for content inside panels (accounting for borders/padding)

    public void init(ChunkBlazerPlugin plugin)
    {
        this.plugin = plugin;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setPreferredSize(new Dimension(PANEL_WIDTH, 600));
        setMaximumSize(new Dimension(PANEL_WIDTH, Integer.MAX_VALUE));

        // Wrap main panel in a scroll pane to prevent overflow
        JPanel mainContent = createMainPanel();
        mainContent.setPreferredSize(new Dimension(PANEL_WIDTH - 10, mainContent.getPreferredSize().height));
        mainContent.setMaximumSize(new Dimension(PANEL_WIDTH - 10, Integer.MAX_VALUE));

        JScrollPane mainScrollPane = new JScrollPane(mainContent);
        mainScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        mainScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        mainScrollPane.setBorder(null);
        mainScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        mainScrollPane.setPreferredSize(new Dimension(PANEL_WIDTH, 600));

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
        mainPanel.add(Box.createVerticalStrut(8));

        // Stats Section (fixed size - don't use setupSectionPanel)
        statsPanel = createStatsSection();
        statsPanel.setAlignmentX(LEFT_ALIGNMENT);
        mainPanel.add(statsPanel);
        mainPanel.add(Box.createVerticalStrut(8));

        // Mode Selection Section (hidden when locked)
        modeSelectionPanel = createModeSelectionSection();
        setupSectionPanel(modeSelectionPanel);
        mainPanel.add(modeSelectionPanel);
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
        sectionTitle.setForeground(new Color(150, 150, 255));
        headerRow.add(sectionTitle, BorderLayout.WEST);

        completedTasksToggle = new JToggleButton("\u25BC");
        completedTasksToggle.setFont(new Font("Arial", Font.PLAIN, 10));
        completedTasksToggle.setPreferredSize(new Dimension(30, 20));
        completedTasksToggle.setMaximumSize(new Dimension(30, 20));
        completedTasksToggle.setToolTipText("Expand/collapse completed tasks with search");
        completedTasksToggle.addActionListener(e -> {
            completedTasksExpanded = completedTasksToggle.isSelected();
            completedTasksToggle.setText(completedTasksExpanded ? "\u25B2" : "\u25BC");
            updateCompletedTasksVisibility();
        });
        headerRow.add(completedTasksToggle, BorderLayout.EAST);

        panel.add(headerRow);
        panel.add(Box.createVerticalStrut(5));

        // Filter panel (visible when expanded)
        completedTasksFilterPanel = new JPanel();
        completedTasksFilterPanel.setLayout(new BoxLayout(completedTasksFilterPanel, BoxLayout.Y_AXIS));
        completedTasksFilterPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        completedTasksFilterPanel.setAlignmentX(LEFT_ALIGNMENT);
        completedTasksFilterPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 80));
        completedTasksFilterPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 80));
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
            public void insertUpdate(DocumentEvent e) { onCompletedTasksFilterChanged(); }
            @Override
            public void removeUpdate(DocumentEvent e) { onCompletedTasksFilterChanged(); }
            @Override
            public void changedUpdate(DocumentEvent e) { onCompletedTasksFilterChanged(); }
        });
        searchRow.add(completedTasksSearchField, BorderLayout.CENTER);

        completedTasksFilterPanel.add(searchRow);
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
        categoryFilterCombo.addActionListener(e -> {
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

        JLabel regLabel = new JLabel("Region:");
        regLabel.setFont(FontManager.getRunescapeSmallFont());
        regLabel.setForeground(Color.LIGHT_GRAY);
        regionPanel.add(regLabel, BorderLayout.NORTH);

        regionFilterCombo = new JComboBox<>(new String[]{"All"});
        regionFilterCombo.setFont(FontManager.getRunescapeSmallFont());
        regionFilterCombo.addActionListener(e -> {
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
        completedCollapsedLabel = new JLabel("Click \u25BC to view completed tasks");
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
        }
        finally
        {
            isRefreshingFilters = false;
        }
    }

    private static final int HEADER_HEIGHT = 38; // Fixed height for header section
    private static final int STATS_HEIGHT = 36; // Fixed height for stats section

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
            BorderFactory.createLineBorder(new Color(255, 215, 0)), // Gold border
            new EmptyBorder(2, 3, 2, 3)
        ));
        // Fixed size - never changes
        statsPanel.setPreferredSize(new Dimension(PANEL_WIDTH - 10, STATS_HEIGHT));
        statsPanel.setMinimumSize(new Dimension(PANEL_WIDTH - 10, STATS_HEIGHT));
        statsPanel.setMaximumSize(new Dimension(PANEL_WIDTH - 10, STATS_HEIGHT));

        // Points - use "Pts" for shorter label
        JPanel pointsPanel = createStatBox("Pts", "0");
        totalPointsLabel = (JLabel) ((JPanel) pointsPanel.getComponent(0)).getComponent(1);
        statsPanel.add(pointsPanel);

        // Chunks Unlocked - use "Chk" for shorter label
        JPanel chunksPanel = createStatBox("Chk", "1");
        chunksUnlockedLabel = (JLabel) ((JPanel) chunksPanel.getComponent(0)).getComponent(1);
        statsPanel.add(chunksPanel);

        // Tasks Done - use "Tsk" for shorter label
        JPanel tasksPanel = createStatBox("Tsk", "0");
        tasksCompletedLabel = (JLabel) ((JPanel) tasksPanel.getComponent(0)).getComponent(1);
        statsPanel.add(tasksPanel);

        return statsPanel;
    }

    private JPanel createStatBox(String label, String value)
    {
        JPanel box = new JPanel();
        box.setLayout(new BorderLayout());
        box.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JPanel innerPanel = new JPanel();
        innerPanel.setLayout(new BoxLayout(innerPanel, BoxLayout.Y_AXIS));
        innerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JLabel labelText = new JLabel(label);
        labelText.setFont(new Font("Arial", Font.PLAIN, 9));
        labelText.setForeground(Color.LIGHT_GRAY);
        labelText.setAlignmentX(CENTER_ALIGNMENT);

        JLabel valueText = new JLabel(value);
        valueText.setFont(FontManager.getRunescapeBoldFont().deriveFont(12f));
        valueText.setForeground(new Color(255, 215, 0)); // Gold color
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
            BorderFactory.createLineBorder(new Color(255, 215, 0), 2), // Gold border
            new EmptyBorder(6, 6, 6, 6)
        ));
        selectedTaskPanel.setAlignmentX(LEFT_ALIGNMENT);
        selectedTaskPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, 100));
        selectedTaskPanel.setMaximumSize(new Dimension(CONTENT_WIDTH, 100));

        JLabel selectedTitle = new JLabel("\u2605 SELECTED TASK \u2605"); // Star symbols
        selectedTitle.setFont(FontManager.getRunescapeBoldFont());
        selectedTitle.setForeground(new Color(255, 215, 0)); // Gold color
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

        JLabel sectionTitle = new JLabel("Active Tasks");
        sectionTitle.setFont(FontManager.getRunescapeBoldFont());
        sectionTitle.setForeground(new Color(100, 255, 100));
        headerRow.add(sectionTitle, BorderLayout.WEST);

        activeTasksToggle = new JToggleButton("\u25B2"); // Up arrow (expanded)
        activeTasksToggle.setSelected(true); // Start expanded
        activeTasksToggle.setFont(new Font("Arial", Font.PLAIN, 10));
        activeTasksToggle.setPreferredSize(new Dimension(30, 20));
        activeTasksToggle.setMaximumSize(new Dimension(30, 20));
        activeTasksToggle.setToolTipText("Collapse/expand active tasks");
        activeTasksToggle.addActionListener(e -> {
            activeTasksExpanded = activeTasksToggle.isSelected();
            activeTasksToggle.setText(activeTasksExpanded ? "\u25B2" : "\u25BC");
            updateActiveTasksVisibility();
        });
        headerRow.add(activeTasksToggle, BorderLayout.EAST);

        taskPanel.add(headerRow);
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
            public void insertUpdate(DocumentEvent e) { onActiveTasksFilterChanged(); }
            @Override
            public void removeUpdate(DocumentEvent e) { onActiveTasksFilterChanged(); }
            @Override
            public void changedUpdate(DocumentEvent e) { onActiveTasksFilterChanged(); }
        });
        searchRow.add(activeTasksSearchField, BorderLayout.CENTER);

        activeTasksFilterPanel.add(searchRow);
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
        activeTasksCategoryCombo.addActionListener(e -> {
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

        JLabel regLabel = new JLabel("Region:");
        regLabel.setFont(FontManager.getRunescapeSmallFont());
        regLabel.setForeground(Color.LIGHT_GRAY);
        regionPanel.add(regLabel, BorderLayout.NORTH);

        activeTasksRegionCombo = new JComboBox<>(new String[]{"All"});
        activeTasksRegionCombo.setFont(FontManager.getRunescapeSmallFont());
        activeTasksRegionCombo.addActionListener(e -> {
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
        activeTasksCollapsedLabel = new JLabel("Click \u25BC to view tasks");
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

        JLabel selectedTitle = new JLabel("\u2605 SELECTED TASK \u2605");
        selectedTitle.setFont(FontManager.getRunescapeBoldFont());
        selectedTitle.setForeground(new Color(255, 215, 0));
        selectedTitle.setAlignmentX(LEFT_ALIGNMENT);
        selectedTaskPanel.add(selectedTitle);
        selectedTaskPanel.add(Box.createVerticalStrut(4));

        // Task name
        JLabel nameLabel = new JLabel("<html>" + selectedTask.getName() + "</html>");
        nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
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

        final int BAR_WIDTH = 100;
        JPanel progressBar = new JPanel(new BorderLayout());
        progressBar.setBackground(new Color(30, 30, 30));
        progressBar.setBorder(BorderFactory.createLineBorder(new Color(255, 215, 0)));
        progressBar.setPreferredSize(new Dimension(BAR_WIDTH + 2, 12));

        int fillWidth = Math.round(BAR_WIDTH * pct);
        JPanel progressFill = new JPanel();
        progressFill.setBackground(new Color(255, 215, 0)); // Gold fill
        progressFill.setPreferredSize(new Dimension(fillWidth, 10));
        progressBar.add(progressFill, BorderLayout.WEST);

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

    private JLabel createDetailLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(Color.LIGHT_GRAY);
        return label;
    }

    private JLabel createDetailValue(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(Color.WHITE);
        return label;
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

        devControlsToggle = new JToggleButton("\u25BC"); // Down arrow (collapsed)
        devControlsToggle.setFont(new Font("Arial", Font.PLAIN, 10));
        devControlsToggle.setPreferredSize(new Dimension(30, 20));
        devControlsToggle.setMaximumSize(new Dimension(30, 20));
        devControlsToggle.setToolTipText("Expand/collapse dev controls");
        devControlsToggle.addActionListener(e -> {
            devControlsExpanded = devControlsToggle.isSelected();
            devControlsToggle.setText(devControlsExpanded ? "\u25B2" : "\u25BC");
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
        add10PointsButton.addActionListener(e -> {
            plugin.devAddPoints(10);
            updateStats();
        });
        pointsPanel.add(add10PointsButton);

        JButton add100PointsButton = new JButton("+100 pts");
        add100PointsButton.setFont(FontManager.getRunescapeSmallFont());
        add100PointsButton.setToolTipText("Add 100 points");
        add100PointsButton.addActionListener(e -> {
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
            "- Clear current active task\n\n" +
            "Points and unlocked chunks will be kept.",
            "Reset Task Progress",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );

        if (confirm == JOptionPane.YES_OPTION)
        {
            plugin.devResetTasks();
            updatePanel();
            JOptionPane.showMessageDialog(this, "Task progress reset!", "Reset Complete", JOptionPane.INFORMATION_MESSAGE);
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

        JLabel sectionTitle = new JLabel("Region Tasks");
        sectionTitle.setFont(FontManager.getRunescapeBoldFont());
        sectionTitle.setForeground(Color.WHITE);
        headerRow.add(sectionTitle, BorderLayout.WEST);

        taskListToggle = new JToggleButton("\u25BC"); // Down arrow
        taskListToggle.setFont(new Font("Arial", Font.PLAIN, 10));
        taskListToggle.setPreferredSize(new Dimension(30, 20));
        taskListToggle.setMaximumSize(new Dimension(30, 20));
        taskListToggle.setToolTipText("Expand/collapse task list");
        taskListToggle.addActionListener(e -> {
            taskListExpanded = taskListToggle.isSelected();
            taskListToggle.setText(taskListExpanded ? "\u25B2" : "\u25BC"); // Up/Down arrow
            updateTaskListVisibility();
        });
        headerRow.add(taskListToggle, BorderLayout.EAST);

        listPanel.add(headerRow);
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
            public void insertUpdate(DocumentEvent e) { onFilterChanged(); }
            @Override
            public void removeUpdate(DocumentEvent e) { onFilterChanged(); }
            @Override
            public void changedUpdate(DocumentEvent e) { onFilterChanged(); }
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
        JLabel collapsedLabel = new JLabel("Click \u25BC to view tasks");
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
        log.info("Dev: Complete task requested");
        plugin.devCompleteActiveTask();
        updateTaskDisplay();
    }

    private void onRerollTask()
    {
        log.info("Dev: Reroll task requested");
        plugin.rerollTask();
        updateTaskDisplay();
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
        sb.append("=== CHUNKBLAZER DEBUG INFO ===\n");
        sb.append("Generated: ").append(new java.util.Date()).append("\n\n");

        // Module status
        sb.append("=== MODULE STATUS ===\n");
        sb.append("Check logs at: C:\\Users\\bao\\.runelite\\logs\\client.log\n");
        sb.append("Look for: '>>>' prefixed lines for combat tracking\n");
        sb.append("Look for: 'NpcKillModule HEARTBEAT' every ~60 seconds\n\n");

        // Current region
        sb.append("Current Region: ").append(plugin.getCurrentRegionId()).append("\n\n");

        // Active tasks with combat task highlighting
        sb.append("=== ACTIVE TASKS (").append(plugin.getActiveTasks().size()).append(") ===\n\n");

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

            sb.append(isCombat ? "[COMBAT] " : "");
            sb.append("Task: ").append(task.getName()).append("\n");
            sb.append("  ID: ").append(task.getTaskId()).append("\n");
            sb.append("  Category: ").append(category).append("\n");
            sb.append("  CompletionType: ").append(type).append("\n");
            sb.append("  Progress: ").append(task.getCurrentProgress()).append("/").append(task.getTargetQuantity()).append("\n");

            TargetNpc targetNpc = task.getTargetNpc();
            if (targetNpc != null)
            {
                sb.append("  Target NPC Name: ").append(targetNpc.getName()).append("\n");
                sb.append("  Target NPC IDs: ").append(targetNpc.getNpcIds()).append("\n");
                if (isCombat)
                {
                    sb.append("  >>> If kills aren't tracking, verify in-game NPC ID matches these IDs!\n");
                }
            }
            else
            {
                sb.append("  Target NPC: NONE - this task won't track NPC kills!\n");
            }
            sb.append("\n");
        }

        sb.append("Total combat tasks: ").append(combatTasks).append("\n");
        if (combatTasks == 0)
        {
            sb.append(">>> NO COMBAT TASKS! Kill tracking will not work without combat tasks.\n");
        }
        sb.append("\n");

        // Completed tasks
        sb.append("=== COMPLETED TASKS ===\n");
        List<NuzlockeTask> completed = plugin.getCompletedTasks();
        sb.append("Total completed: ").append(completed.size()).append("\n");
        for (NuzlockeTask task : completed)
        {
            sb.append("  - ").append(task.getName()).append(" (").append(task.getBasePoints()).append(" pts)\n");
        }

        sb.append("\n=== TROUBLESHOOTING ===\n");
        sb.append("If kills aren't being detected:\n");
        sb.append("1. Check the log file for '>>> PLAYER ATTACKING' messages\n");
        sb.append("2. Check for '>>> NPC died' messages when NPC dies\n");
        sb.append("3. Verify the killed NPC's ID matches the Target NPC IDs above\n");
        sb.append("4. Look for 'NpcKillModule HEARTBEAT' to confirm events are working\n");

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
        SwingUtilities.invokeLater(() -> {
            updateModeDisplay();
            updateRegionDisplay();
            updateStats();
            updateTaskDisplay();
            updateCompletedTasks();
            updateTaskList();
        });
    }

    public void updateStats()
    {
        int points = plugin.getTotalPoints();
        int chunks = plugin.getUnlockedRegionIds().size();
        int tasks = plugin.getCompletedTaskCount();

        totalPointsLabel.setText(String.valueOf(points));
        chunksUnlockedLabel.setText(String.valueOf(chunks));
        tasksCompletedLabel.setText(String.valueOf(tasks));
    }

    public void updateModeDisplay()
    {
        boolean isLocked = plugin.isModeLocked();
        modeSelectionPanel.setVisible(!isLocked);

        if (isLocked)
        {
            GameMode mode = plugin.getGameMode();
            modeLabel.setText(" | " + mode.getName());
            modeLabel.setForeground(mode == GameMode.NUZLOCKE ?
                new Color(255, 100, 100) : new Color(100, 200, 100));
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

    public void updateTaskDisplay()
    {
        updateActiveTasksDisplay();
        // Also update selected task if it exists
        if (selectedTask != null)
        {
            updateSelectedTaskDisplay();
        }
    }

    public void updateActiveTasksDisplay()
    {
        // Refresh filter dropdowns
        refreshActiveTasksFilters();

        // Only rebuild the scrollable content, not the whole panel
        activeTasksContentPanel.removeAll();

        List<NuzlockeTask> allTasks = plugin.getActiveTasks();

        // Cache filter values
        final String filterText = activeTasksSearchText != null ? activeTasksSearchText : "";
        final String filterCategory = activeTasksSelectedCategory != null ? activeTasksSelectedCategory : "All";
        final String filterRegion = activeTasksSelectedRegion != null ? activeTasksSelectedRegion : "All";

        // Filter tasks based on search text, category, and region
        List<NuzlockeTask> filteredTasks = allTasks.stream()
            .filter(task -> {
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
                // Region filter
                if (!"All".equals(filterRegion))
                {
                    String taskRegion = plugin.getTaskRegionName(task);
                    if (taskRegion == null || !filterRegion.equals(taskRegion))
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
            for (NuzlockeTask task : filteredTasks)
            {
                activeTasksContentPanel.add(createActiveTaskItem(task));
                activeTasksContentPanel.add(Box.createVerticalStrut(5));
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
    }

    private void updateActiveTasksSectionTitle(int totalCount, int filteredCount)
    {
        // Find the "Active Tasks" label in currentTaskPanel and update it
        for (java.awt.Component comp : currentTaskPanel.getComponents())
        {
            if (comp instanceof JLabel)
            {
                JLabel label = (JLabel) comp;
                String text = label.getText();
                if (text != null && text.startsWith("Active Tasks"))
                {
                    if (totalCount == filteredCount)
                    {
                        label.setText("Active Tasks (" + totalCount + ")");
                    }
                    else
                    {
                        label.setText("Active Tasks (" + filteredCount + "/" + totalCount + ")");
                    }
                    break;
                }
            }
        }
    }

    private JPanel createActiveTaskItem(NuzlockeTask task)
    {
        // Check if this task is currently selected
        boolean isSelected = selectedTask != null &&
            task.getTaskId() != null &&
            task.getTaskId().equals(selectedTask.getTaskId());

        JPanel itemPanel = new JPanel();
        itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));

        // Different colors for selected vs unselected
        Color bgColor = isSelected ? new Color(60, 70, 50) : new Color(40, 50, 40);
        Color borderColor = isSelected ? new Color(255, 215, 0) : new Color(60, 80, 60);
        int borderWidth = isSelected ? 2 : 1;

        itemPanel.setBackground(bgColor);
        itemPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, borderWidth),
            new EmptyBorder(4, 5, 4, 5)
        ));
        itemPanel.setAlignmentX(LEFT_ALIGNMENT);
        // Allow dynamic height based on content
        itemPanel.setMaximumSize(new Dimension(CONTENT_WIDTH - 10, Integer.MAX_VALUE));

        // Make clickable with cursor change
        itemPanel.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        itemPanel.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                selectTask(task);
            }

            @Override
            public void mouseEntered(java.awt.event.MouseEvent e)
            {
                if (!isSelected)
                {
                    itemPanel.setBackground(new Color(50, 60, 50));
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e)
            {
                if (!isSelected)
                {
                    itemPanel.setBackground(bgColor);
                }
            }
        });

        // Selection indicator + Task name (with text wrapping)
        String taskName = task.getName();
        String prefix = isSelected ? "\u2605 " : ""; // Star for selected
        String wrappedName = "<html><body style='width: " + (CONTENT_WIDTH - 40) + "px'>" + prefix + taskName + "</body></html>";
        JLabel nameLabel = new JLabel(wrappedName);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(isSelected ? new Color(255, 215, 0) : new Color(150, 255, 150));
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
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

        // Progress bar row
        int progress = task.getCurrentProgress();
        int target = task.getTargetQuantity();
        float pct = target > 0 ? (float) progress / target : 0;
        pct = Math.min(pct, 1.0f); // Cap at 100%

        JPanel progressRow = new JPanel(new BorderLayout(4, 0));
        progressRow.setBackground(bgColor);
        progressRow.setAlignmentX(LEFT_ALIGNMENT);
        progressRow.setPreferredSize(new Dimension(CONTENT_WIDTH - 20, 16));
        progressRow.setMaximumSize(new Dimension(CONTENT_WIDTH - 20, 16));

        // Progress bar - use exact width calculation
        final int BAR_WIDTH = 78; // Inner width (excluding border)
        JPanel progressBar = new JPanel();
        progressBar.setLayout(new BorderLayout());
        progressBar.setBackground(new Color(30, 30, 30));
        progressBar.setBorder(BorderFactory.createLineBorder(isSelected ? new Color(255, 215, 0) : new Color(60, 60, 60)));
        progressBar.setPreferredSize(new Dimension(BAR_WIDTH + 2, 10)); // +2 for border

        // Calculate fill width with rounding for accuracy
        int fillWidth = Math.round(BAR_WIDTH * pct);
        JPanel progressFill = new JPanel();
        progressFill.setBackground(isSelected ? new Color(255, 215, 0) : new Color(80, 180, 80));
        progressFill.setPreferredSize(new Dimension(fillWidth, 8)); // 8 = 10 - 2 for border
        progressBar.add(progressFill, BorderLayout.WEST);

        progressRow.add(progressBar, BorderLayout.CENTER);

        JLabel progressText = new JLabel(progress + "/" + target);
        progressText.setFont(FontManager.getRunescapeSmallFont());
        progressText.setForeground(Color.WHITE);
        progressRow.add(progressText, BorderLayout.EAST);

        itemPanel.add(Box.createVerticalStrut(2));
        itemPanel.add(progressRow);

        return itemPanel;
    }

    public void updateCompletedTasks()
    {
        List<CompletedTaskInfo> completedTasks = plugin.getCompletedTasksWithInfo();
        int count = completedTasks.size();

        if (completedCollapsedLabel != null)
        {
            completedCollapsedLabel.setText(count > 0 ?
                "Click \u25BC to view " + count + " completed tasks" :
                "Click \u25BC to view completed tasks");
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
        completedTasksContentPanel.removeAll();

        List<CompletedTaskInfo> allTasks = plugin.getCompletedTasksWithInfo();

        // Cache filter values to avoid null issues during combo box updates
        final String filterCategory = selectedCategory != null ? selectedCategory : "All";
        final String filterRegion = selectedRegion != null ? selectedRegion : "All";
        final String filterText = completedTasksSearchText != null ? completedTasksSearchText : "";

        List<CompletedTaskInfo> filteredTasks = allTasks.stream()
            .filter(info -> {
                if (info == null) return false;

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
            summaryLabel.setForeground(new Color(255, 215, 0));
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
    }

    private JPanel createEnhancedCompletedTaskItem(CompletedTaskInfo info)
    {
        JPanel itemPanel = new JPanel();
        itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));
        itemPanel.setBackground(new Color(40, 40, 60));
        itemPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 100)),
            new EmptyBorder(4, 5, 4, 5)
        ));
        itemPanel.setAlignmentX(LEFT_ALIGNMENT);
        // Allow dynamic height based on content
        itemPanel.setMaximumSize(new Dimension(CONTENT_WIDTH - 10, Integer.MAX_VALUE));

        // Task name with checkmark (with text wrapping)
        String taskName = info.getName();
        String wrappedName = "<html><body style='width: " + (CONTENT_WIDTH - 45) + "px'>\u2713 " + taskName + "</body></html>";
        JLabel nameLabel = new JLabel(wrappedName);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(new Color(100, 200, 100));
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        itemPanel.add(nameLabel);

        // Info line: Category | Points
        String infoText = info.getCategory() + "  +" + info.getPoints() + " pts";
        JLabel infoLabel = new JLabel(infoText);
        infoLabel.setFont(FontManager.getRunescapeSmallFont());
        infoLabel.setForeground(Color.ORANGE);
        infoLabel.setAlignmentX(LEFT_ALIGNMENT);
        itemPanel.add(infoLabel);

        // Region on separate line (with text wrapping)
        String regionName = info.getRegionName();
        String wrappedRegion = "<html><body style='width: " + (CONTENT_WIDTH - 45) + "px'>" + (regionName != null ? regionName : "Unknown") + "</body></html>";
        JLabel regionLabel = new JLabel(wrappedRegion);
        regionLabel.setFont(FontManager.getRunescapeSmallFont());
        regionLabel.setForeground(Color.CYAN);
        regionLabel.setAlignmentX(LEFT_ALIGNMENT);
        itemPanel.add(regionLabel);

        return itemPanel;
    }


    public void showNoTasksMessage()
    {
        SwingUtilities.invokeLater(() -> {
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
                        String titleText = "Region Tasks";
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

        // Fixed height to show 5 items - scroll if more content exists
        if (taskListExpanded)
        {
            int height = MAX_TASK_LIST_HEIGHT;
            taskListScrollPane.setMinimumSize(new Dimension(CONTENT_WIDTH, height));
            taskListScrollPane.setPreferredSize(new Dimension(CONTENT_WIDTH, height));
            taskListScrollPane.setMaximumSize(new Dimension(CONTENT_WIDTH, height));
            taskListScrollPane.revalidate();
        }
    }

    private JPanel createTaskListItem(NuzlockeTask task)
    {
        JPanel itemPanel = new JPanel();
        itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));
        itemPanel.setBorder(new EmptyBorder(4, 5, 4, 5));
        // Allow dynamic height based on content
        itemPanel.setMaximumSize(new Dimension(CONTENT_WIDTH - 10, Integer.MAX_VALUE));
        itemPanel.setAlignmentX(LEFT_ALIGNMENT);

        // Determine task status
        boolean isAssigned = plugin.isTaskAssigned(task.getTaskId());
        boolean isActive = plugin.getActiveTask() != null &&
            task.getTaskId().equals(plugin.getActiveTask().getTaskId());

        // Set background based on status
        if (isActive)
        {
            itemPanel.setBackground(new Color(40, 60, 40)); // Green tint for active
        }
        else if (isAssigned)
        {
            itemPanel.setBackground(new Color(50, 50, 50)); // Darker for assigned/done
        }
        else
        {
            itemPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        }

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

        // Task name with text wrapping
        String displayName = task.getName();
        String wrappedName = "<html><body style='width: " + (CONTENT_WIDTH - 50) + "px'>" + displayName + "</body></html>";
        JLabel nameLabel = new JLabel(wrappedName);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(textColor);
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        itemPanel.add(nameLabel);

        // Task info line (status + points + level)
        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        infoPanel.setBackground(itemPanel.getBackground());
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
