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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

@Slf4j
public class ChunkBlazerPanel extends PluginPanel
{
    private final ChunkBlazerPlugin plugin;

    // UI Components
    private JPanel modeSelectionPanel;
    private JPanel currentTaskPanel;
    private JPanel devControlsPanel;
    private JPanel taskListPanel;
    private JLabel regionLabel;
    private JLabel modeLabel;
    private JLabel taskNameLabel;
    private JLabel taskCategoryLabel;
    private JLabel taskPointsLabel;
    private JLabel taskProgressLabel;
    private JRadioButton casualRadio;
    private JRadioButton nuzlockeRadio;

    @Inject
    public ChunkBlazerPanel(ChunkBlazerPlugin plugin)
    {
        super(false);
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

        // Mode Selection Section (hidden when locked)
        modeSelectionPanel = createModeSelectionSection();
        mainPanel.add(modeSelectionPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Current Task Section
        currentTaskPanel = createCurrentTaskSection();
        mainPanel.add(currentTaskPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Dev/Test Controls Section
        devControlsPanel = createDevControlsSection();
        mainPanel.add(devControlsPanel);
        mainPanel.add(Box.createVerticalStrut(10));

        // Task List Section
        taskListPanel = createTaskListSection();
        mainPanel.add(taskListPanel);

        return mainPanel;
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
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(10, 10, 10, 10)
        ));

        // Section title
        JLabel sectionTitle = new JLabel("Current Task");
        sectionTitle.setFont(FontManager.getRunescapeBoldFont());
        sectionTitle.setForeground(Color.WHITE);
        sectionTitle.setAlignmentX(LEFT_ALIGNMENT);
        taskPanel.add(sectionTitle);
        taskPanel.add(Box.createVerticalStrut(8));

        // Task name
        taskNameLabel = new JLabel("No active task");
        taskNameLabel.setFont(FontManager.getRunescapeFont().deriveFont(14f));
        taskNameLabel.setForeground(new Color(100, 200, 100));
        taskNameLabel.setAlignmentX(LEFT_ALIGNMENT);
        taskPanel.add(taskNameLabel);
        taskPanel.add(Box.createVerticalStrut(5));

        // Task details panel
        JPanel detailsPanel = new JPanel(new GridLayout(0, 2, 5, 2));
        detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        detailsPanel.setAlignmentX(LEFT_ALIGNMENT);

        detailsPanel.add(createDetailLabel("Category:"));
        taskCategoryLabel = createDetailValue("--");
        detailsPanel.add(taskCategoryLabel);

        detailsPanel.add(createDetailLabel("Points:"));
        taskPointsLabel = createDetailValue("--");
        detailsPanel.add(taskPointsLabel);

        detailsPanel.add(createDetailLabel("Progress:"));
        taskProgressLabel = createDetailValue("--");
        detailsPanel.add(taskProgressLabel);

        taskPanel.add(detailsPanel);

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

        // Buttons panel
        JPanel buttonsPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        buttonsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        buttonsPanel.setAlignmentX(LEFT_ALIGNMENT);
        buttonsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JButton completeButton = new JButton("Complete Task");
        completeButton.addActionListener(e -> onCompleteTask());
        buttonsPanel.add(completeButton);

        JButton rerollButton = new JButton("Reroll Task");
        rerollButton.addActionListener(e -> onRerollTask());
        buttonsPanel.add(rerollButton);

        controlsPanel.add(buttonsPanel);

        return controlsPanel;
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

        // Section title
        JLabel sectionTitle = new JLabel("Available Tasks");
        sectionTitle.setFont(FontManager.getRunescapeBoldFont());
        sectionTitle.setForeground(Color.WHITE);
        sectionTitle.setAlignmentX(LEFT_ALIGNMENT);
        listPanel.add(sectionTitle);
        listPanel.add(Box.createVerticalStrut(8));

        // Placeholder for task list - will be populated dynamically
        JLabel placeholderLabel = new JLabel("Loading tasks...");
        placeholderLabel.setFont(FontManager.getRunescapeSmallFont());
        placeholderLabel.setForeground(Color.GRAY);
        placeholderLabel.setAlignmentX(LEFT_ALIGNMENT);
        listPanel.add(placeholderLabel);

        return listPanel;
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

    // --- Update Methods ---

    public void updatePanel()
    {
        SwingUtilities.invokeLater(() -> {
            updateModeDisplay();
            updateRegionDisplay();
            updateTaskDisplay();
            updateTaskList();
        });
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
        NuzlockeTask task = plugin.getActiveTask();

        if (task != null)
        {
            taskNameLabel.setText(task.getName());
            taskCategoryLabel.setText(task.getCategory());
            taskPointsLabel.setText(String.valueOf(task.getBasePoints()));
            taskProgressLabel.setText(task.getProgressText());
        }
        else
        {
            taskNameLabel.setText("No active task");
            taskCategoryLabel.setText("--");
            taskPointsLabel.setText("--");
            taskProgressLabel.setText("--");
        }
    }

    public void updateTaskList()
    {
        // Clear existing task list content (except title)
        while (taskListPanel.getComponentCount() > 2)
        {
            taskListPanel.remove(2);
        }

        List<NuzlockeTask> tasks = plugin.getCurrentRegionTasks();

        if (tasks == null || tasks.isEmpty())
        {
            JLabel noTasksLabel = new JLabel("No tasks available");
            noTasksLabel.setFont(FontManager.getRunescapeSmallFont());
            noTasksLabel.setForeground(Color.GRAY);
            noTasksLabel.setAlignmentX(LEFT_ALIGNMENT);
            taskListPanel.add(noTasksLabel);
        }
        else
        {
            for (NuzlockeTask task : tasks)
            {
                taskListPanel.add(createTaskListItem(task));
                taskListPanel.add(Box.createVerticalStrut(3));
            }
        }

        taskListPanel.revalidate();
        taskListPanel.repaint();
    }

    private JPanel createTaskListItem(NuzlockeTask task)
    {
        JPanel itemPanel = new JPanel(new BorderLayout(5, 0));
        itemPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        itemPanel.setBorder(new EmptyBorder(3, 5, 3, 5));
        itemPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        itemPanel.setAlignmentX(LEFT_ALIGNMENT);

        // Task name
        JLabel nameLabel = new JLabel(task.getName());
        nameLabel.setFont(FontManager.getRunescapeSmallFont());
        nameLabel.setForeground(task.isLocked() ? Color.GRAY : Color.WHITE);
        itemPanel.add(nameLabel, BorderLayout.CENTER);

        // Task info (points + level)
        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        if (task.getLevelRequirement() > 1)
        {
            JLabel levelLabel = new JLabel("Lv" + task.getLevelRequirement());
            levelLabel.setFont(FontManager.getRunescapeSmallFont());
            levelLabel.setForeground(Color.ORANGE);
            infoPanel.add(levelLabel);
        }

        JLabel pointsLabel = new JLabel(task.getBasePoints() + "pt");
        pointsLabel.setFont(FontManager.getRunescapeSmallFont());
        pointsLabel.setForeground(new Color(100, 200, 100));
        infoPanel.add(pointsLabel);

        itemPanel.add(infoPanel, BorderLayout.EAST);

        return itemPanel;
    }
}
