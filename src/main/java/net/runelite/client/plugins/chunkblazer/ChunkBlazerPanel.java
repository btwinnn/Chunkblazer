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
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
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
    private static final int MAX_TASK_LIST_HEIGHT = 150; // Max height for task list scroll area
    private static final int MAX_ACTIVE_TASKS_HEIGHT = 180; // Max height for active tasks
    private static final int MAX_COMPLETED_TASKS_HEIGHT = 120; // Max height for completed tasks

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

    public ChunkBlazerPanel()
    {
        super(false);
    }

    public void init(ChunkBlazerPlugin plugin)
    {
        this.plugin = plugin;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Build the panel
        add(createMainPanel(), BorderLayout.NORTH);
    }

    private JPanel createMainPanel()
    {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Header Section
        mainPanel.add(createHeaderSection());
        mainPanel.add(Box.createVerticalStrut(10));

        // Stats Section (points, chunks, tasks)
        statsPanel = createStatsSection();
        mainPanel.add(statsPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Mode Selection Section (hidden when locked)
        modeSelectionPanel = createModeSelectionSection();
        mainPanel.add(modeSelectionPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Current Task Section
        currentTaskPanel = createCurrentTaskSection();
        mainPanel.add(currentTaskPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Completed Tasks Section
        completedTasksPanel = createCompletedTasksSection();
        mainPanel.add(completedTasksPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Dev/Test Controls Section
        devControlsPanel = createDevControlsSection();
        mainPanel.add(devControlsPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Task List Section
        taskListPanel = createTaskListSection();
        mainPanel.add(taskListPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Social Links Section
        mainPanel.add(createSocialLinksSection());

        return mainPanel;
    }

    private JPanel createSocialLinksSection()
    {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 5));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setAlignmentX(LEFT_ALIGNMENT);

        // Discord button with icon
        JButton discordButton = new JButton("\uD83D\uDCAC Discord"); // Speech bubble emoji
        discordButton.setForeground(new Color(88, 101, 242)); // Discord blurple
        discordButton.setToolTipText("Join the Discord");
        discordButton.addActionListener(e -> openLink("https://discord.gg/D8DYP45DV8"));
        panel.add(discordButton);

        return panel;
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
            new EmptyBorder(10, 10, 10, 10)
        ));

        // Section title
        JLabel sectionTitle = new JLabel("Completed Tasks");
        sectionTitle.setFont(FontManager.getRunescapeBoldFont());
        sectionTitle.setForeground(new Color(150, 150, 255));
        sectionTitle.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(sectionTitle);
        panel.add(Box.createVerticalStrut(8));

        // Scrollable content panel for completed tasks
        completedTasksContentPanel = new JPanel();
        completedTasksContentPanel.setLayout(new BoxLayout(completedTasksContentPanel, BoxLayout.Y_AXIS));
        completedTasksContentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        completedTasksScrollPane = new JScrollPane(completedTasksContentPanel);
        completedTasksScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        completedTasksScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        completedTasksScrollPane.setBorder(null);
        completedTasksScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        completedTasksScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        completedTasksScrollPane.setAlignmentX(LEFT_ALIGNMENT);
        completedTasksScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, MAX_COMPLETED_TASKS_HEIGHT));
        completedTasksScrollPane.setPreferredSize(new Dimension(200, MAX_COMPLETED_TASKS_HEIGHT));

        // Placeholder
        JLabel placeholder = new JLabel("No tasks completed yet");
        placeholder.setFont(FontManager.getRunescapeSmallFont());
        placeholder.setForeground(Color.GRAY);
        placeholder.setAlignmentX(LEFT_ALIGNMENT);
        completedTasksContentPanel.add(placeholder);

        panel.add(completedTasksScrollPane);

        return panel;
    }

    private JPanel createHeaderSection()
    {
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new GridBagLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(10, 10, 10, 10)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // Title
        JLabel titleLabel = new JLabel("ChunkBlazer");
        titleLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
        titleLabel.setForeground(new Color(255, 152, 0)); // Orange color
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        headerPanel.add(titleLabel, gbc);

        // Region display
        regionLabel = new JLabel("Region: --");
        regionLabel.setFont(FontManager.getRunescapeSmallFont());
        regionLabel.setForeground(Color.WHITE);
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.insets = new Insets(5, 0, 0, 0);
        headerPanel.add(regionLabel, gbc);

        // Mode display
        modeLabel = new JLabel("Mode: --");
        modeLabel.setFont(FontManager.getRunescapeSmallFont());
        modeLabel.setForeground(Color.LIGHT_GRAY);
        modeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        gbc.gridx = 1;
        headerPanel.add(modeLabel, gbc);

        return headerPanel;
    }

    private JPanel createStatsSection()
    {
        JPanel statsPanel = new JPanel();
        statsPanel.setLayout(new GridLayout(1, 3, 5, 0));
        statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statsPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(255, 215, 0)), // Gold border
            new EmptyBorder(8, 8, 8, 8)
        ));

        // Points
        JPanel pointsPanel = createStatBox("Points", "0");
        totalPointsLabel = (JLabel) ((JPanel) pointsPanel.getComponent(0)).getComponent(1);
        statsPanel.add(pointsPanel);

        // Chunks Unlocked
        JPanel chunksPanel = createStatBox("Chunks", "1");
        chunksUnlockedLabel = (JLabel) ((JPanel) chunksPanel.getComponent(0)).getComponent(1);
        statsPanel.add(chunksPanel);

        // Tasks Done
        JPanel tasksPanel = createStatBox("Tasks", "0");
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
        labelText.setFont(FontManager.getRunescapeSmallFont());
        labelText.setForeground(Color.LIGHT_GRAY);
        labelText.setAlignmentX(CENTER_ALIGNMENT);

        JLabel valueText = new JLabel(value);
        valueText.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
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
            new EmptyBorder(10, 10, 10, 10)
        ));

        // Section title
        JLabel sectionTitle = new JLabel("Active Tasks");
        sectionTitle.setFont(FontManager.getRunescapeBoldFont().deriveFont(14f));
        sectionTitle.setForeground(new Color(100, 255, 100));
        sectionTitle.setAlignmentX(LEFT_ALIGNMENT);
        taskPanel.add(sectionTitle);
        taskPanel.add(Box.createVerticalStrut(8));

        // Scrollable content panel for active tasks
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
        activeTasksScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, MAX_ACTIVE_TASKS_HEIGHT));
        activeTasksScrollPane.setPreferredSize(new Dimension(200, MAX_ACTIVE_TASKS_HEIGHT));

        // Placeholder
        taskNameLabel = new JLabel("Loading tasks...");
        taskNameLabel.setFont(FontManager.getRunescapeSmallFont());
        taskNameLabel.setForeground(Color.LIGHT_GRAY);
        taskNameLabel.setAlignmentX(LEFT_ALIGNMENT);
        activeTasksContentPanel.add(taskNameLabel);

        taskPanel.add(activeTasksScrollPane);

        // Hidden labels for backward compatibility
        taskCategoryLabel = new JLabel("");
        taskPointsLabel = new JLabel("");
        taskProgressLabel = new JLabel("");

        return taskPanel;
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
            new EmptyBorder(10, 10, 10, 10)
        ));

        // Section title
        JLabel sectionTitle = new JLabel("Dev Controls");
        sectionTitle.setFont(FontManager.getRunescapeBoldFont());
        sectionTitle.setForeground(new Color(150, 150, 255));
        sectionTitle.setAlignmentX(LEFT_ALIGNMENT);
        controlsPanel.add(sectionTitle);
        controlsPanel.add(Box.createVerticalStrut(8));

        // Task buttons row
        JPanel taskButtonsPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        taskButtonsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        taskButtonsPanel.setAlignmentX(LEFT_ALIGNMENT);
        taskButtonsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JButton completeButton = new JButton("Complete Task");
        completeButton.addActionListener(e -> onCompleteTask());
        taskButtonsPanel.add(completeButton);

        JButton rerollButton = new JButton("Reroll Task");
        rerollButton.addActionListener(e -> onRerollTask());
        taskButtonsPanel.add(rerollButton);

        controlsPanel.add(taskButtonsPanel);
        controlsPanel.add(Box.createVerticalStrut(5));

        // Points button row
        JPanel pointsPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        pointsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        pointsPanel.setAlignmentX(LEFT_ALIGNMENT);
        pointsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JButton add10PointsButton = new JButton("+10 Points");
        add10PointsButton.addActionListener(e -> {
            plugin.devAddPoints(10);
            updateStats();
        });
        pointsPanel.add(add10PointsButton);

        JButton add100PointsButton = new JButton("+100 Points");
        add100PointsButton.addActionListener(e -> {
            plugin.devAddPoints(100);
            updateStats();
        });
        pointsPanel.add(add100PointsButton);

        controlsPanel.add(pointsPanel);
        controlsPanel.add(Box.createVerticalStrut(5));

        // Reset buttons row
        JPanel resetPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        resetPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        resetPanel.setAlignmentX(LEFT_ALIGNMENT);
        resetPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JButton resetTasksButton = new JButton("Reset Tasks");
        resetTasksButton.setForeground(new Color(255, 100, 100));
        resetTasksButton.addActionListener(e -> onResetTasks());
        resetPanel.add(resetTasksButton);

        JButton resetAllButton = new JButton("Reset All");
        resetAllButton.setForeground(new Color(255, 50, 50));
        resetAllButton.addActionListener(e -> onResetAll());
        resetPanel.add(resetAllButton);

        controlsPanel.add(resetPanel);
        controlsPanel.add(Box.createVerticalStrut(5));

        // Debug button row
        JPanel debugPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        debugPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        debugPanel.setAlignmentX(LEFT_ALIGNMENT);
        debugPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JButton debugButton = new JButton("Debug Info");
        debugButton.setForeground(new Color(100, 150, 255));
        debugButton.addActionListener(e -> onShowDebugInfo());
        debugPanel.add(debugButton);

        JButton logButton = new JButton("Open Logs");
        logButton.setForeground(new Color(100, 150, 255));
        logButton.addActionListener(e -> openLogFile());
        debugPanel.add(logButton);

        controlsPanel.add(debugPanel);

        return controlsPanel;
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
            new EmptyBorder(10, 10, 10, 10)
        ));

        // Header row with toggle button
        JPanel headerRow = new JPanel(new BorderLayout(5, 0));
        headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerRow.setAlignmentX(LEFT_ALIGNMENT);
        headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));

        JLabel sectionTitle = new JLabel("Region Tasks");
        sectionTitle.setFont(FontManager.getRunescapeBoldFont());
        sectionTitle.setForeground(Color.WHITE);
        headerRow.add(sectionTitle, BorderLayout.WEST);

        taskListToggle = new JToggleButton("\u25BC"); // Down arrow
        taskListToggle.setFont(new Font("Arial", Font.PLAIN, 10));
        taskListToggle.setPreferredSize(new Dimension(30, 20));
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
        taskFilterField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
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

        taskListScrollPane = new JScrollPane(taskListContentPanel);
        taskListScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        taskListScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        taskListScrollPane.setBorder(null);
        taskListScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        taskListScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        taskListScrollPane.setAlignmentX(LEFT_ALIGNMENT);
        taskListScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, MAX_TASK_LIST_HEIGHT));
        taskListScrollPane.setPreferredSize(new Dimension(200, MAX_TASK_LIST_HEIGHT));
        taskListScrollPane.setVisible(false); // Hidden by default

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
        }

        taskListPanel.revalidate();
        taskListPanel.repaint();
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
            modeLabel.setText("Mode: " + mode.getName());
            modeLabel.setForeground(mode == GameMode.NUZLOCKE ?
                new Color(255, 100, 100) : new Color(100, 200, 100));
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
        int regionId = plugin.getCurrentRegionId();
        String regionName = plugin.getCurrentRegionName();

        if (regionId > 0)
        {
            regionLabel.setText("Region: " + regionName + " (" + regionId + ")");
        }
        else
        {
            regionLabel.setText("Region: --");
        }
    }

    public void updateTaskDisplay()
    {
        // Only rebuild the scrollable content, not the whole panel
        activeTasksContentPanel.removeAll();

        List<NuzlockeTask> tasks = plugin.getActiveTasks();

        if (tasks.isEmpty())
        {
            JLabel noTaskLabel = new JLabel("No active tasks");
            noTaskLabel.setFont(FontManager.getRunescapeSmallFont());
            noTaskLabel.setForeground(Color.GRAY);
            noTaskLabel.setAlignmentX(LEFT_ALIGNMENT);
            activeTasksContentPanel.add(noTaskLabel);
        }
        else
        {
            for (NuzlockeTask task : tasks)
            {
                activeTasksContentPanel.add(createActiveTaskItem(task));
                activeTasksContentPanel.add(Box.createVerticalStrut(5));
            }
        }

        // Update the section title in parent panel
        if (currentTaskPanel.getComponentCount() > 0)
        {
            java.awt.Component first = currentTaskPanel.getComponent(0);
            if (first instanceof JLabel)
            {
                ((JLabel) first).setText("Active Tasks (" + tasks.size() + ")");
            }
        }

        activeTasksContentPanel.revalidate();
        activeTasksContentPanel.repaint();
    }

    private JPanel createActiveTaskItem(NuzlockeTask task)
    {
        JPanel itemPanel = new JPanel();
        itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));
        itemPanel.setBackground(new Color(40, 50, 40));
        itemPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 80, 60)),
            new EmptyBorder(4, 4, 4, 4)
        ));
        itemPanel.setAlignmentX(LEFT_ALIGNMENT);
        itemPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

        // Task name with HTML wrapping
        String taskName = task.getName();
        JLabel nameLabel = new JLabel("<html><body style='width: 180px'>" + taskName + "</body></html>");
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(new Color(150, 255, 150));
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        itemPanel.add(nameLabel);

        // Category and points row - more compact
        JPanel infoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        infoRow.setBackground(new Color(40, 50, 40));
        infoRow.setAlignmentX(LEFT_ALIGNMENT);

        JLabel catLabel = new JLabel(task.getCategory());
        catLabel.setFont(FontManager.getRunescapeSmallFont());
        catLabel.setForeground(Color.ORANGE);
        infoRow.add(catLabel);

        JLabel ptsLabel = new JLabel(task.getBasePoints() + "pt");
        ptsLabel.setFont(FontManager.getRunescapeSmallFont());
        ptsLabel.setForeground(new Color(255, 215, 0));
        infoRow.add(ptsLabel);

        if (task.getLevelRequirement() > 1)
        {
            JLabel lvlLabel = new JLabel("L" + task.getLevelRequirement());
            lvlLabel.setFont(FontManager.getRunescapeSmallFont());
            lvlLabel.setForeground(Color.CYAN);
            infoRow.add(lvlLabel);
        }

        itemPanel.add(infoRow);

        // Progress bar row
        JPanel progressRow = new JPanel(new BorderLayout(5, 0));
        progressRow.setBackground(new Color(40, 50, 40));
        progressRow.setAlignmentX(LEFT_ALIGNMENT);
        progressRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        // Progress bar
        int progress = task.getCurrentProgress();
        int target = task.getTargetQuantity();
        float pct = target > 0 ? (float) progress / target : 0;

        JPanel progressBar = new JPanel();
        progressBar.setLayout(new BorderLayout());
        progressBar.setBackground(new Color(30, 30, 30));
        progressBar.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60)));
        progressBar.setPreferredSize(new Dimension(120, 14));

        JPanel progressFill = new JPanel();
        progressFill.setBackground(new Color(80, 180, 80));
        progressFill.setPreferredSize(new Dimension((int)(120 * pct), 14));
        progressBar.add(progressFill, BorderLayout.WEST);

        progressRow.add(progressBar, BorderLayout.CENTER);

        JLabel progressText = new JLabel(progress + "/" + target);
        progressText.setFont(FontManager.getRunescapeSmallFont());
        progressText.setForeground(Color.WHITE);
        progressRow.add(progressText, BorderLayout.EAST);

        itemPanel.add(Box.createVerticalStrut(3));
        itemPanel.add(progressRow);

        return itemPanel;
    }

    public void updateCompletedTasks()
    {
        // Only rebuild the scrollable content
        completedTasksContentPanel.removeAll();

        List<NuzlockeTask> completedTasks = plugin.getCompletedTasks();

        // Update the section title in parent panel
        if (completedTasksPanel.getComponentCount() > 0)
        {
            java.awt.Component first = completedTasksPanel.getComponent(0);
            if (first instanceof JLabel)
            {
                ((JLabel) first).setText("Completed Tasks (" + completedTasks.size() + ")");
            }
        }

        if (completedTasks.isEmpty())
        {
            JLabel placeholder = new JLabel("No tasks completed yet");
            placeholder.setFont(FontManager.getRunescapeSmallFont());
            placeholder.setForeground(Color.GRAY);
            placeholder.setAlignmentX(LEFT_ALIGNMENT);
            completedTasksContentPanel.add(placeholder);
        }
        else
        {
            // Calculate total points earned
            int totalPoints = completedTasks.stream().mapToInt(NuzlockeTask::getBasePoints).sum();

            JLabel pointsLabel = new JLabel("Total earned: " + totalPoints + " pts");
            pointsLabel.setFont(FontManager.getRunescapeSmallFont());
            pointsLabel.setForeground(new Color(255, 215, 0));
            pointsLabel.setAlignmentX(LEFT_ALIGNMENT);
            completedTasksContentPanel.add(pointsLabel);
            completedTasksContentPanel.add(Box.createVerticalStrut(5));

            for (NuzlockeTask task : completedTasks)
            {
                completedTasksContentPanel.add(createCompletedTaskItem(task));
                completedTasksContentPanel.add(Box.createVerticalStrut(3));
            }
        }

        completedTasksContentPanel.revalidate();
        completedTasksContentPanel.repaint();
    }

    private JPanel createCompletedTaskItem(NuzlockeTask task)
    {
        JPanel itemPanel = new JPanel(new BorderLayout(5, 0));
        itemPanel.setBackground(new Color(40, 40, 60));
        itemPanel.setBorder(new EmptyBorder(4, 6, 4, 6));
        itemPanel.setAlignmentX(LEFT_ALIGNMENT);
        itemPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        // Task name with checkmark
        JLabel nameLabel = new JLabel("\u2713 " + task.getName());
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(new Color(100, 200, 100));
        itemPanel.add(nameLabel, BorderLayout.CENTER);

        // Points earned
        JLabel ptsLabel = new JLabel("+" + task.getBasePoints() + " pts");
        ptsLabel.setFont(FontManager.getRunescapeSmallFont());
        ptsLabel.setForeground(new Color(255, 215, 0));
        itemPanel.add(ptsLabel, BorderLayout.EAST);

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
    }

    private JPanel createTaskListItem(NuzlockeTask task)
    {
        JPanel itemPanel = new JPanel(new BorderLayout(5, 0));
        itemPanel.setBorder(new EmptyBorder(3, 5, 3, 5));
        itemPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
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

        // Task name with status indicator
        String displayName = task.getName();
        JLabel nameLabel = new JLabel(displayName);
        nameLabel.setFont(FontManager.getRunescapeSmallFont());

        // Color based on status
        if (isActive)
        {
            nameLabel.setForeground(new Color(100, 255, 100)); // Bright green for active
        }
        else if (isAssigned)
        {
            nameLabel.setForeground(Color.GRAY); // Gray for already assigned
        }
        else if (task.isLocked())
        {
            nameLabel.setForeground(Color.DARK_GRAY);
        }
        else
        {
            nameLabel.setForeground(Color.WHITE); // Available
        }

        itemPanel.add(nameLabel, BorderLayout.CENTER);

        // Task info (status + points + level)
        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        infoPanel.setBackground(itemPanel.getBackground());

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

        itemPanel.add(infoPanel, BorderLayout.EAST);

        return itemPanel;
    }
}
