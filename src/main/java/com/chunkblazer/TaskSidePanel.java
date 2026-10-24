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

import static com.chunkblazer.TaskLists.*;
import static com.chunkblazer.TaskStyle.*;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.ListModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;

/**
 * The task list in ChunkBlazer's side panel: the stat boxes, search, filters and sort, then
 * the Selected, Saved, Chunk, Active and Global sections of task cards. Click a heading to
 * fold it; the gear at the top right of the panel's header box picks what each card shows.
 */
@Singleton
public class TaskSidePanel
{
	@Inject
	private TaskArchive archive;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChunkBlazerPlugin plugin;

	@Inject
	private SkillIconManager skillIcons;

	@Inject
	private TaskLists lists;
	@Inject
	private Provider<ChunkBlazerUi> ui;
	@Inject
	private EventBus eventBus;
	@Inject
	private QuestRequirements quests;

	// --- Side panel list --------------------------------------------------------

	private static final int QUEST_CHECKS_PER_TICK = 15;

	/** With the orbs (ChunkBlazerOrbWidget): quest data for "can do", and the list kept filled each tick. */
	public void startUp()
	{
		quests.load();
		eventBus.register(this);
	}

	public void shutDown()
	{
		eventBus.unregister(this);
	}

	/** Every tick from login, so the side panel fills as soon as the tasks arrive. */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || configManager.getRSProfileKey() == null)
		{
			return;
		}
		// Prerequisite quests' states, a few a tick, for the quests' "can do".
		quests.refreshStates(QUEST_CHECKS_PER_TICK);
		updateSideList();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			quests.reset();
		}
	}

	// The side panel's task list: stats, the selected task, then Saved, Chunk, Active and
	// Global sections of task cards. Click a heading to fold it. Which tasks and in what order
	// come from TaskLists, shared with ChunkBlazer UI's task window.
	private final JList<Object> sideList = new JList<Object>()
	{
		// Full panel width even when every section is folded (BoxLayout stops at preferred).
		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	};

	private final Set<String> sideFolded = ConcurrentHashMap.newKeySet();
	private String sideKey = "";
	private boolean sideAdded;
	// The pieces put in the side panel, built once; and the panel they're in. Turning the
	// plugin off and on makes a new side panel, so they're moved over to it.
	private JComponent sideStatsBox;
	private JComponent sideControlsBox;
	private JComponent sideGear;
	private ChunkBlazerPanel sideHost;
	private volatile int sideHover = -1;
	// Points, Chunks, Tasks and Tokens, each in its own black-edged box.
	private static final String[] STAT_NAMES = {"Points", "Chunks", "Tasks", "Tokens"};
	private final JLabel[] sideStats = new JLabel[STAT_NAMES.length];
	// Finished cards, by what they show, so a repaint doesn't lay out their HTML again.
	private final Map<String, JLabel> sideCards = new HashMap<>();
	// The saved ids the list was last built with (read once a build, not once a card).
	private volatile Set<String> sideSaved = Collections.emptySet();
	// The filters and sort live here; the window shows what's set. sideSync keeps them in step.
	// Skill filter: a button that opens the skills in the game's Skills tab layout, as icons.
	private final JButton sideSkill = smooth(new JButton());
	private final JPopupMenu sideSkillMenu = new JPopupMenu();
	private long sideSkillMenuClosed;
	private final Map<Skill, JButton> sideSkillButtons = new EnumMap<>(Skill.class);
	private final JComboBox<String> sideTier = new JComboBox<>();
	private final JComboBox<String> sideSort = new JComboBox<>();
	private final IconTextField sideSearch = new IconTextField();
	private final Map<Filter, JButton> sideBoxes = new EnumMap<>(Filter.class);
	private final JButton sideFold = new JButton();

	/** What a side panel task card shows, picked with the gear in the panel's header box. */
	private enum SideDetail
	{
		CATEGORY("Category", true),
		LEVEL("Level needed", true),
		CHUNK("Chunk", true),
		DESCRIPTION("Description", true),
		PROGRESS("Progress bar", true),
		POINTS("Points banner", true),
		DETAILS("Details (as when expanded in the task window)", false);

		final String label;
		final boolean byDefault;

		SideDetail(String label, boolean byDefault)
		{
			this.label = label;
			this.byDefault = byDefault;
		}
	}

	// The side panel cards' details (SideDetail), saved as "sideDetail".
	private volatile Set<SideDetail> sideDetail;
	private final JButton sideClear = new JButton("Clear");
	// The list's text size (A- / A+), remembered per account.
	// Text size step (A- / A+): 0 the small font, 1 the regular font, 2 regular half as big again, 3 doubled.
	private volatile int sideSize;
	private final JPanel sideFilterBox = new JPanel(new GridLayout(0, 2, 4, 4));
	private boolean sideSyncing;

	/** Once a tick: add the list to the side panel, and refresh it while it's on screen. */
	public void updateSideList()
	{
		ChunkBlazerPanel panel = plugin.getPanel();
		if (!sideAdded && panel != null)
		{
			sideAdded = true;
			String folded = configManager.getRSProfileConfiguration(CONFIG_GROUP, "sideFolded");
			sideFolded.addAll(Arrays.asList((folded == null ? "Active,Global" : folded).split(",")));
			lists.loadFilters();
			sideList.setCellRenderer((list, item, index, selected, focus) -> sideRow(item, index == sideHover));
			java.awt.event.MouseAdapter sideMouse = new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					hoverRow(sideList.locationToIndex(e.getPoint()));
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hoverRow(-1);
				}

				// On press, not click: a click is lost if the mouse moves at all in between.
				@Override
				public void mousePressed(MouseEvent e)
				{
					int i = sideList.locationToIndex(e.getPoint());
					Object item = i < 0 ? null : sideList.getModel().getElementAt(i);
					// Keep the clicked row under the mouse when the list rebuilds (see keepAnchor).
					sideAnchor = anchorAt(i);
					if (item instanceof String)
					{
						String section = ((String) item).replaceAll(" \\(.*", "");
						if (!sideFolded.remove(section))
						{
							sideFolded.add(section);
						}
						configManager.setRSProfileConfiguration(CONFIG_GROUP, "sideFolded", String.join(",", sideFolded));
					}
					else if (item instanceof NuzlockeTask && SwingUtilities.isRightMouseButton(e))
					{
						lists.toggleSaved(((NuzlockeTask) item).getTaskId());
					}
					else if (item instanceof NuzlockeTask)
					{
						lists.trackOrOpen((NuzlockeTask) item, t -> ui.get().openTask(t));
					}
					// Redraw now rather than on the next game tick (up to 0.6s away).
					sideKey = "";
					clientThread.invoke(TaskSidePanel.this::updateSideList);
				}
			};
			sideList.addMouseListener(sideMouse);
			sideList.addMouseMotionListener(sideMouse);
			sideList.setBackground(new Color(40, 40, 40));
			JPanel stats = new JPanel(new GridLayout(1, STAT_NAMES.length, 4, 0));
			stats.setOpaque(false);
			for (int i = 0; i < sideStats.length; i++)
			{
				// Plain labels, not HTML, so the game's pixel fonts stay their real size.
				JPanel box = new JPanel(new GridLayout(2, 1));
				box.setBackground(PanelStyle.FIELD);
				box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, Color.BLACK),
					new EmptyBorder(2, 0, 3, 0)));
				sideStats[i] = statLabel("0", FontManager.getRunescapeBoldFont(), PanelStyle.YELLOW);
				box.add(sideStats[i]);
				box.add(statLabel(STAT_NAMES[i], FontManager.getRunescapeSmallFont(), PanelStyle.ORANGE));
				stats.add(box);
			}
			stats.setMaximumSize(new Dimension(Integer.MAX_VALUE, stats.getPreferredSize().height));
			sideStatsBox = stats;
			sideControlsBox = sideControls();
			sideFold.addActionListener(e ->
			{
				sideFilterBox.setVisible(!sideFilterBox.isVisible());
				sideSync();
				ChunkBlazerPanel now = plugin.getPanel();
				if (now != null)
				{
					now.revalidate();
				}
			});
			sideGear = gearButton();
		}
		if (sideAdded && panel != null && panel != sideHost)
		{
			sideHost = panel;
			panel.addTaskStats(sideStatsBox);
			panel.addTaskList(sideControlsBox);
			panel.addTaskList(sideList);
			panel.setHeaderTool(sideGear);
			panel.applyTheme();
			sideKey = "";
		}
		if (!sideList.isShowing())
		{
			return;
		}
		Set<String> saved = lists.savedIds();
		Set<String> archived = archive.ids();
		String here = lists.currentChunkName();
		List<NuzlockeTask> chunk = new ArrayList<>();
		List<NuzlockeTask> globals = new ArrayList<>();
		String query = lists.search.trim().toLowerCase();
		// A search looks past the filters until the box is cleared.
		Predicate<NuzlockeTask> test = query.isEmpty() ? lists.filterTest(lists.filterOn, lists.filterHidden, lists.filterSkill, lists.filterTier)
			: t -> lists.matchesSearch(t, query);
		for (NuzlockeTask task : lists.pool(true))
		{
			lists.chunkNames.computeIfAbsent(task.getTaskId(), k -> lists.chunkName(task));
			if (!archived.contains(task.getTaskId()) && test.test(task))
			{
				(plugin.isGlobalTask(task.getTaskId()) ? globals : chunk).add(task);
			}
		}
		Comparator<NuzlockeTask> order = lists.sorter(lists.sortField, lists.ascending);
		chunk.sort(order);
		globals.sort(order);
		NuzlockeTask tracked = plugin.getSelectedTask();
		List<Object> items = new ArrayList<>();
		// Only while something is tracked, and unless it only shows on screen (ChunkBlazer UI's choice).
		if (tracked != null && ui.get().sidePanelShowsTracked())
		{
			addSection(items, "Selected", Collections.singletonList(tracked));
		}
		List<NuzlockeTask> savedTasks = new ArrayList<>(chunk);
		savedTasks.addAll(globals);
		savedTasks.removeIf(t -> !saved.contains(t.getTaskId()));
		addSection(items, "Saved", savedTasks);
		// Saved tasks are listed once, under Saved.
		chunk.removeIf(t -> saved.contains(t.getTaskId()));
		globals.removeIf(t -> saved.contains(t.getTaskId()));
		List<NuzlockeTask> inChunk = new ArrayList<>(chunk);
		inChunk.removeIf(t -> !here.equals(lists.chunkNames.get(t.getTaskId())));
		addSection(items, "Chunk", inChunk);
		addSection(items, "Active", chunk);
		addSection(items, "Global", globals);

		// The stat boxes from the original panel's top row.
		int[] numbers = {plugin.getTotalPoints(), lists.paidChunks(), plugin.getCompletedTaskIdSet().size(), plugin.getBossTokens()};
		String stats = Arrays.toString(numbers);
		// Only rebuild when something changed, so the panel doesn't jump while scrolling.
		StringBuilder key = new StringBuilder(stats + sideSize + lists.filterLabel() + lists.sortField + lists.ascending + saved + query);
		items.forEach(o -> key.append(o instanceof NuzlockeTask
			? ((NuzlockeTask) o).getTaskId() + ((NuzlockeTask) o).getCurrentProgress() : o));
		if (key.toString().equals(sideKey))
		{
			return;
		}
		sideKey = key.toString();
		SwingUtilities.invokeLater(() ->
		{
			// A whole new model swapped in at once: clearing the old one flashed an empty list.
			DefaultListModel<Object> model = new DefaultListModel<>();
			items.forEach(model::addElement);
			int height = sideList.getPreferredSize().height;
			// What to hold still: the row just clicked, else (when scrolled down) the top row in view.
			Anchor anchor = sideAnchor != null ? sideAnchor : topAnchor();
			sideAnchor = null;
			sideSaved = saved;
			sideList.setModel(model);
			for (int i = 0; i < numbers.length; i++)
			{
				sideStats[i].setText(String.valueOf(numbers[i]));
			}
			sideSync();
			// The panel is only laid out again when the list changed height.
			if (sideList.getPreferredSize().height != height)
			{
				panel.revalidate();
			}
			keepAnchor(anchor);
			sideList.repaint();
		});
	}

	/**
	 * The Skill and Tier dropdowns and a button per type or condition, two to a row and folded
	 * under one button. A click cycles a button off, on (\u2713) and hidden (\u2717). Sort sits below.
	 */
	private JPanel sideFilters()
	{
		sideFilterBox.setOpaque(false);
		sideFilterBox.setVisible(false);
		sideSkill.setFont(FontManager.getRunescapeSmallFont());
		sideSkill.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sideSkill.setForeground(Color.WHITE);
		sideSkill.setFocusPainted(false);
		// Clicking the button while the grid is open closes it: the press already hid the popup, so
		// don't reopen it straight away.
		sideSkillMenu.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
				sideSkillMenuClosed = System.currentTimeMillis();
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
			}
		});
		sideSkill.addActionListener(e ->
		{
			if (System.currentTimeMillis() - sideSkillMenuClosed > 250)
			{
				showSkillMenu();
			}
		});
		sideTier.addItem("Tiers");
		Arrays.stream(TaskCardTier.values()).forEach(t -> sideTier.addItem(t.getDisplayName()));
		// Each sort field twice: its usual direction first, so "Points" high to low leads.
		for (SortField field : SortField.values())
		{
			for (boolean asc : new boolean[]{field.ascendingByDefault, !field.ascendingByDefault})
			{
				sideSort.addItem("Sort: " + field.label + (asc ? " \u25B2" : " \u25BC"));
			}
		}
		sideFilterBox.add(sideSkill);
		sideFilterBox.add(sideTier);
		// Every option on screen at once (no scroll bar), the number on the right past a divider.
		sideTier.setMaximumRowCount(sideTier.getItemCount());
		sideSort.setMaximumRowCount(sideSort.getItemCount());
		widenPopup(sideTier, 130);
		widenPopup(sideSort, 140);
		sideTier.setRenderer((list, item, index, selected, focus) ->
		{
			int tier = sideTier.getSelectedIndex();
			int i = index >= 0 ? index : tier;
			if (i < 1)
			{
				return splitRow(index < 0 ? "Tiers" : "All tiers", null, Color.WHITE, selected && index >= 0);
			}
			TaskCardTier t = TaskCardTier.values()[i - 1];
			// The closed box is narrow: just the name there, the points only in the open list.
			return splitRow(t.getDisplayName(), index < 0 ? null : i + (i == 1 ? " pt" : " pts"), t.getAccent(), selected && index >= 0);
		});
		sideSort.setRenderer((list, item, index, selected, focus) ->
		{
			String text = String.valueOf(item);
			String name = text.replaceFirst("^Sort: ", "").replaceAll(" [\u25B2\u25BC]$", "");
			String arrow = text.endsWith("\u25B2") ? "\u25B2" : "\u25BC";
			// Closed: "Sort" until a sort is picked, then just the pick ("Chunk \u25B2").
			if (index < 0)
			{
				return splitRow(sideSort.getSelectedIndex() <= 0 ? "Sort" : name + " " + arrow, null, Color.WHITE, false);
			}
			return splitRow(name, arrow, Color.WHITE, selected, 18);
		});
		for (JComboBox<String> combo : Arrays.asList(sideTier, sideSort))
		{
			combo.setFont(FontManager.getRunescapeSmallFont());
			combo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			combo.setForeground(Color.WHITE);
			combo.addActionListener(e ->
			{
				if (sideSyncing)
				{
					return;
				}
				int tier = sideTier.getSelectedIndex();
				int sort = sideSort.getSelectedIndex();
				lists.filterTier = tier < 1 ? null : TaskCardTier.values()[tier - 1];
				lists.sortField = SortField.values()[sort / 2];
				lists.ascending = lists.sortField.ascendingByDefault == (sort % 2 == 0);
				lists.saveFilters();
				lists.refresh();
				sideChanged();
			});
		}
		for (Filter f : Filter.values())
		{
			JButton button = smooth(new JButton(f.shortLabel));
			button.setFont(FontManager.getRunescapeSmallFont());
			sideBoxes.put(f, button);
			button.addActionListener(e ->
			{
				lists.toggleFilter(f, lists.filterOn.contains(f) || lists.filterHidden.contains(f));
				sideChanged();
			});
			sideFilterBox.add(button);
		}
		sideFold.setFont(FontManager.getRunescapeSmallFont());
		return sideFilterBox;
	}

	/** Opens a dropdown's list at least this wide, so the number column isn't cut off by a narrow box. */
	private static void widenPopup(JComboBox<String> combo, int width)
	{
		combo.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
				Object popup = combo.getUI().getAccessibleChild(combo, 0);
				if (popup instanceof JPopupMenu && ((JPopupMenu) popup).getComponentCount() > 0)
				{
					JComponent scroll = (JComponent) ((JPopupMenu) popup).getComponent(0);
					Dimension size = new Dimension(Math.max(combo.getWidth(), width), scroll.getPreferredSize().height);
					scroll.setPreferredSize(size);
					scroll.setMaximumSize(size);
				}
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
			}
		});
	}

	/** A dropdown row: the name on the left, a fixed-width number (or arrow) on the right past a divider. */
	private static JComponent splitRow(String left, String right, Color color, boolean highlighted)
	{
		return splitRow(left, right, color, highlighted, 42);
	}

	/** splitRow with the right column this wide (an arrow needs far less room than "5 pts"). */
	private static JComponent splitRow(String left, String right, Color color, boolean highlighted, int rightWidth)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBorder(new EmptyBorder(3, 6, 3, 6));
		row.setBackground(highlighted ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		JLabel name = smooth(new JLabel(left));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(color);
		row.add(name, BorderLayout.CENTER);
		if (right != null)
		{
			boolean arrow = right.equals("\u25B2") || right.equals("\u25BC");
			// Arrows are drawn, not typed: the small font's arrow glyph is wider than its column and
			// came out as "...". Numbers stay text.
			JLabel number = arrow ? new JLabel(new ImageIcon(Skin.image(right.equals("\u25B2") ? "sort_up" : "sort_down")), SwingConstants.CENTER)
				: smooth(new JLabel(right, SwingConstants.RIGHT));
			number.setFont(FontManager.getRunescapeSmallFont());
			number.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			number.setBorder(new CompoundBorder(new MatteBorder(0, 1, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
				new EmptyBorder(0, arrow ? 1 : 6, 0, 0)));
			number.setPreferredSize(new Dimension(rightWidth, name.getPreferredSize().height));
			row.add(number, BorderLayout.EAST);
		}
		return row;
	}

	/** The skills as the game's Skills tab lays them out: three columns, read row by row. */
	public static final Skill[] SKILL_TAB = {
		Skill.ATTACK, Skill.HITPOINTS, Skill.MINING,
		Skill.STRENGTH, Skill.AGILITY, Skill.SMITHING,
		Skill.DEFENCE, Skill.HERBLORE, Skill.FISHING,
		Skill.RANGED, Skill.THIEVING, Skill.COOKING,
		Skill.PRAYER, Skill.CRAFTING, Skill.FIREMAKING,
		Skill.MAGIC, Skill.FLETCHING, Skill.WOODCUTTING,
		Skill.RUNECRAFT, Skill.SLAYER, Skill.FARMING,
		Skill.CONSTRUCTION, Skill.HUNTER, Skill.SAILING,
	};

	/**
	 * The skill filter's dropdown: each skill's icon and level, in the Skills tab's grid, with
	 * "All skills" where the tab shows the total level. Built on first open; the levels and the
	 * highlighted pick are refreshed each time it opens.
	 */
	private void showSkillMenu()
	{
		if (sideSkillButtons.isEmpty())
		{
			JPanel grid = new JPanel(new GridLayout(0, 3, 2, 2));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			for (Skill skill : SKILL_TAB)
			{
				JButton button = smooth(new JButton());
				BufferedImage icon = skillIcons.getSkillImage(skill, false);
				button.setIcon(icon == null ? null : new ImageIcon(icon));
				button.setToolTipText(skill.getName());
				button.setFont(FontManager.getRunescapeSmallFont());
				button.setHorizontalAlignment(SwingConstants.LEFT);
				button.setIconTextGap(6);
				button.setFocusPainted(false);
				button.setBorder(new EmptyBorder(3, 5, 3, 5));
				button.addActionListener(e -> pickSkill(skill));
				sideSkillButtons.put(skill, button);
				grid.add(button);
			}
			JButton all = smooth(new JButton("All skills"));
			all.setFont(FontManager.getRunescapeSmallFont());
			all.setBackground(ColorScheme.DARK_GRAY_COLOR);
			all.setForeground(Color.WHITE);
			all.setFocusPainted(false);
			all.addActionListener(e -> pickSkill(null));
			JPanel menu = new JPanel(new BorderLayout(0, 4));
			menu.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			menu.setBorder(new EmptyBorder(4, 4, 4, 4));
			menu.add(grid, BorderLayout.CENTER);
			menu.add(all, BorderLayout.SOUTH);
			sideSkillMenu.add(menu);
		}
		for (Map.Entry<Skill, JButton> e : sideSkillButtons.entrySet())
		{
			int level = client.getRealSkillLevel(e.getKey());
			boolean picked = e.getKey() == lists.filterSkill;
			JButton button = e.getValue();
			button.setText(level > 0 ? String.valueOf(level) : "");
			button.setBackground(picked ? ColorScheme.DARK_GRAY_HOVER_COLOR : ColorScheme.DARK_GRAY_COLOR);
			button.setForeground(picked ? ColorScheme.BRAND_ORANGE : Color.YELLOW);
		}
		sideSkillMenu.show(sideSkill, 0, sideSkill.getHeight());
	}

	private void pickSkill(Skill skill)
	{
		sideSkillMenu.setVisible(false);
		lists.filterSkill = skill;
		lists.saveFilters();
		lists.refresh();
		sideChanged();
	}

	/** Search, Filters (with Clear beside it), the filter buttons, then Sort with A- / A+ beside it, spaced out. */
	private JPanel sideControls()
	{
		// Search matches name, description, category and chunk, in the side panel and the window alike.
		sideSearch.setIcon(IconTextField.Icon.SEARCH);
		sideSearch.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sideSearch.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		sideSearch.setPreferredSize(new Dimension(0, 30));
		sideSearch.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		sideSearch.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				searched();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				searched();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				searched();
			}
		});
		sideClear.setFont(FontManager.getRunescapeSmallFont());
		sideClear.addActionListener(e ->
		{
			lists.clearFilters();
			lists.refresh();
			sideChanged();
		});
		JPanel sizes = new JPanel(new GridLayout(1, 2, 2, 0));
		sizes.setOpaque(false);
		// Four sizes now; the old three-step setting's biggest (2) is the new biggest (3).
		String size = configManager.getRSProfileConfiguration(CONFIG_GROUP, "sideTextSize");
		String old = configManager.getRSProfileConfiguration(CONFIG_GROUP, "sideTextStep");
		// Older builds saved other things under the old key (a font size, "16.0"), so anything
		// that isn't a step we know falls back to the smallest rather than breaking the panel.
		sideSize = size != null ? step(size, 3) : "2".equals(old) ? 3 : old == null ? 0 : step(old, 1);
		for (int step : new int[]{-1, 1})
		{
			JButton button = smooth(new JButton(step < 0 ? "A-" : "A+"));
			button.setFont(FontManager.getRunescapeSmallFont());
			button.addActionListener(e ->
			{
				sideSize = Math.max(0, Math.min(3, sideSize + step));
				configManager.setRSProfileConfiguration(CONFIG_GROUP, "sideTextSize", sideSize);
				sideChanged();
			});
			sizes.add(button);
		}
		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setOpaque(false);
		controls.setBorder(new EmptyBorder(6, 0, 8, 0));
		for (JComponent row : Arrays.asList(sideSearch, row(sideFold, sideClear), sideFilters(), row(sideSort, sizes)))
		{
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setBorder(new EmptyBorder(0, 0, 5, 0));
			controls.add(row);
		}
		return controls;
	}

	/** A saved text size step, 0 to {@code max}; 0 for anything else. */
	private static int step(String saved, int max)
	{
		try
		{
			int step = Integer.parseInt(saved.trim());
			return step >= 0 && step <= max ? step : 0;
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private void searched()
	{
		lists.search = sideSearch.getText();
		lists.refresh();
		sideChanged();
		ui.get().searchChanged(lists.search);
	}

	/** Show every task again: no search, filters or chunk limit. */
	public void showAll()
	{
		setSearch("");
		lists.clearFilters();
		lists.currentChunkOnly = false;
		lists.pinnedChunk = null;
		lists.refresh();
		sideChanged();
	}

	/** Typed in the window's search: search, and show it in the side panel's box too. */
	public void setSearch(String text)
	{
		lists.search = text;
		lists.refresh();
		SwingUtilities.invokeLater(() ->
		{
			if (!sideSearch.getText().equals(text))
			{
				sideSearch.setText(text);
			}
		});
	}

	/** {@code main} filling the width with {@code side} at its right. */
	public static JPanel row(JComponent main, JComponent side)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.add(main);
		row.add(side, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(main.getPreferredSize().height, side.getPreferredSize().height) + 5));
		return row;
	}

	/** Show the current filters and sort on the side panel's controls (the window's tier list sets them too). */
	private void sideSync()
	{
		sideSyncing = true;
		Skill skill = lists.filterSkill;
		sideSkill.setText(skill == null ? "Skills \u25BE" : skill.getName());
		BufferedImage icon = skill == null ? null : skillIcons.getSkillImage(skill, true);
		sideSkill.setIcon(icon == null ? null : new ImageIcon(icon));
		sideTier.setSelectedIndex(lists.filterTier == null ? 0 : lists.filterTier.ordinal() + 1);
		sideSort.setSelectedIndex(lists.sortField.ordinal() * 2 + (lists.ascending == lists.sortField.ascendingByDefault ? 0 : 1));
		sideBoxes.forEach((f, box) ->
		{
			box.setText((lists.filterOn.contains(f) ? "\u2713 " : lists.filterHidden.contains(f) ? "\u2717 " : "") + f.shortLabel);
			box.setForeground(lists.filterOn.contains(f) ? new Color(120, 220, 120)
				: lists.filterHidden.contains(f) ? new Color(255, 110, 110) : Color.WHITE);
		});
		sideClear.setEnabled(lists.anyFilter());
		sideFold.setText((sideFilterBox.isVisible() ? "\u25BC Filters" : "\u25B6 Filters") + " (" + (lists.filterOn.size()
			+ lists.filterHidden.size() + (lists.filterSkill != null ? 1 : 0) + (lists.filterTier != null ? 1 : 0)) + ")");
		sideSyncing = false;
	}

	/** A filter changed in the side panel: rebuild the list now rather than on the next game tick. */
	public void sideChanged()
	{
		sideKey = "";
		clientThread.invoke(this::updateSideList);
	}

	/**
	 * The RuneScape fonts are pixel fonts on a 16-pixel grid, so they're only crisp when their
	 * size on screen is 16 (or 32) pixels. Any other size, or Windows display scaling (150% on a
	 * 4K screen), squeezes the grid unevenly; drawn as plain pixels, rows and columns drop out
	 * or double up. Smoothed (greyscale) text stays whole at any size and is just as sharp on
	 * the grid. Greyscale, not the coloured ClearType edges Windows would pick.
	 */
	private static <T extends JComponent> T smooth(T component)
	{
		component.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		return component;
	}

	private static JLabel statLabel(String text, Font font, Color color)
	{
		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setFont(font);
		label.setForeground(color);
		return label;
	}

	/** A header with its count, then (unless folded) all its tasks. */
	private void addSection(List<Object> items, String title, List<NuzlockeTask> tasks)
	{
		// An empty section is left out entirely; its heading comes back once it has a task.
		if (tasks.isEmpty())
		{
			return;
		}
		items.add(title + " (" + tasks.size() + ")");
		if (sideFolded.contains(title))
		{
			return;
		}
		items.addAll(tasks);
	}

	/**
	 * A row the side list holds still on screen while it rebuilds: a task (by id) or a heading
	 * (by title), the section it was in, and how far down the visible area it was.
	 */
	private static final class Anchor
	{
		final String key;
		final String section;
		final int y;
		// Where its section's heading was, for when the row itself leaves that section.
		final int sectionY;

		Anchor(String key, String section, int y, int sectionY)
		{
			this.key = key;
			this.section = section;
			this.y = y;
			this.sectionY = sectionY;
		}
	}

	private volatile Anchor sideAnchor;

	private JViewport sideViewport()
	{
		return (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, sideList);
	}

	/** The row's key: a task's id, or a heading's title without its count. */
	private static String rowKey(Object item)
	{
		return item instanceof NuzlockeTask ? ((NuzlockeTask) item).getTaskId() : String.valueOf(item).replaceAll(" \\(.*", "");
	}

	/** The index of the section heading a row sits under, or -1. */
	private static int headingOf(ListModel<Object> model, int index)
	{
		for (int i = index; i >= 0; i--)
		{
			if (model.getElementAt(i) instanceof String)
			{
				return i;
			}
		}
		return -1;
	}

	private static String sectionOf(ListModel<Object> model, int index)
	{
		int heading = headingOf(model, index);
		return heading < 0 ? "" : rowKey(model.getElementAt(heading));
	}

	private Anchor anchorAt(int index)
	{
		JViewport view = sideViewport();
		Rectangle cell = index < 0 ? null : sideList.getCellBounds(index, index);
		if (view == null || cell == null)
		{
			return null;
		}
		ListModel<Object> model = sideList.getModel();
		int y = SwingUtilities.convertPoint(sideList, 0, cell.y, view).y;
		int heading = headingOf(model, index);
		Rectangle headingCell = heading < 0 ? cell : sideList.getCellBounds(heading, heading);
		int headingY = SwingUtilities.convertPoint(sideList, 0, headingCell.y, view).y;
		return new Anchor(rowKey(model.getElementAt(index)), sectionOf(model, index), y, headingY);
	}

	/** The first row in view, but only when scrolled down: at the top, new sections just appear. */
	private Anchor topAnchor()
	{
		JViewport view = sideViewport();
		if (view == null || view.getViewPosition().y == 0)
		{
			return null;
		}
		Point top = SwingUtilities.convertPoint(view, 0, 0, sideList);
		return anchorAt(sideList.locationToIndex(new Point(0, Math.max(0, top.y))));
	}

	/**
	 * After a rebuild, scroll so the anchored row is back where it was. If the row left its
	 * section (a task just saved moves to Saved), its old section's heading is held instead,
	 * so the view doesn't jump to wherever the row went.
	 */
	private void keepAnchor(Anchor anchor)
	{
		JViewport view = sideViewport();
		if (anchor == null || view == null)
		{
			return;
		}
		JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, view);
		if (scroll != null)
		{
			scroll.validate(); // lay out now, so the rows' new positions are final
		}
		ListModel<Object> model = sideList.getModel();
		int found = -1;
		int heading = -1;
		for (int i = 0; i < model.getSize() && found < 0; i++)
		{
			Object item = model.getElementAt(i);
			String key = rowKey(item);
			if (item instanceof String && key.equals(anchor.section))
			{
				heading = i;
			}
			if (key.equals(anchor.key) && sectionOf(model, i).equals(anchor.section))
			{
				found = i;
			}
		}
		int index = found >= 0 ? found : heading;
		Rectangle cell = index < 0 ? null : sideList.getCellBounds(index, index);
		if (cell == null)
		{
			return;
		}
		// The row back where it was; or, if it left its section, that section's heading back
		// where it was (the rows under it just close the gap).
		int was = found >= 0 ? anchor.y : anchor.sectionY;
		int now = SwingUtilities.convertPoint(sideList, 0, cell.y, view).y;
		Point position = view.getViewPosition();
		int max = Math.max(0, view.getView().getHeight() - view.getHeight());
		position.y = Math.max(0, Math.min(max, position.y + now - was));
		view.setViewPosition(position);
	}

	/** Move the hover highlight, repainting just the row it left and the one it's on. */
	public void hoverRow(int index)
	{
		int old = sideHover;
		sideHover = index;
		for (int i : new int[]{old, index})
		{
			Rectangle cell = i < 0 || i == old && i == index ? null : sideList.getCellBounds(i, i);
			if (cell != null)
			{
				sideList.repaint(cell);
			}
		}
	}

	/**
	 * A section heading (a stone bar) or a task card (stone, with its tier and points), made
	 * once for each thing it can show and reused after that.
	 */
	/**
	 * The side panel's card font at the A- / A+ step. The game fonts are pixel fonts drawn on
	 * a 16px grid, so the size lands on a whole multiple of 16 screen pixels, Windows display
	 * scaling included: any other size splits their pixels and the text looks choppy. The
	 * multiple is the one nearest the scaling (2x at 200%, so 4K text isn't tiny). Every step
	 * grows every line: the first two by font (small and regular text, regular and bold names),
	 * the last one multiple up. {@code kind}: 0 card text, 1 a task's name, 2 a heading.
	 */
	private Font sideFont(int kind)
	{
		GraphicsConfiguration screen = sideList.getGraphicsConfiguration();
		double scale = screen == null ? 1 : screen.getDefaultTransform().getScaleX();
		// Steps: 0 the small font, 1 the regular font, 2 half as big again, 3 doubled.
		double grid = Math.max(1, Math.round(scale)) + (sideSize == 3 ? 1 : sideSize == 2 ? 0.5 : 0);
		Font font = kind == 2 || kind == 1 && sideSize > 0 ? FontManager.getRunescapeBoldFont()
			: kind == 1 || sideSize > 0 ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont();
		return font.deriveFont((float) (16 * grid / scale));
	}

	private JLabel sideRow(Object item, boolean hover)
	{
		String key = item instanceof String ? item + "|" + sideFolded.contains(((String) item).replaceAll(" \\(.*", ""))
			: ((NuzlockeTask) item).getTaskId() + "|" + ((NuzlockeTask) item).getCurrentProgress() + "|"
				+ lists.isTracked((NuzlockeTask) item) + sideSaved.contains(((NuzlockeTask) item).getTaskId());
		Font small = sideFont(0);
		detail(SideDetail.CATEGORY);
		key += "|" + hover + "|" + small + sideSize + "|" + Skin.theme() + "|" + sideDetail;
		if (sideCards.size() > 2000)
		{
			sideCards.clear();
		}
		return sideCards.computeIfAbsent(key, k ->
		{
			return item instanceof String ? sideHeader((String) item, hover, small) : sideCard((NuzlockeTask) item, hover, small);
		});
	}

	/**
	 * Old School heading, like the panel's buttons: light raised stone in a black edge, the
	 * name in yellow, and its count in a dark box at the right.
	 */
	private JLabel sideHeader(String title, boolean hover, Font small)
	{
		String name = title.replaceAll(" \\(.*", "");
		String count = title.substring(name.length()).replaceAll("[ ()]", "");
		boolean folded = sideFolded.contains(name);
		if (Skin.theme() == Theme.ORIGINAL)
		{
			return originalHeader(name, count, folded);
		}
		JLabel header = new JLabel(name)
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				Rectangle bar = new Rectangle(0, 6, getWidth(), getHeight() - 8);
				g2.setPaint(Skin.stone(false));
				g2.fillRect(bar.x, bar.y, bar.width, bar.height);
				if (hover)
				{
					g2.setColor(new Color(255, 255, 255, 30));
					g2.fillRect(bar.x, bar.y, bar.width, bar.height);
				}
				g2.setColor(Color.BLACK);
				g2.drawRect(bar.x, bar.y, bar.width - 1, bar.height - 1);
				Skin.bevel(g2, new Rectangle(bar.x + 1, bar.y + 1, bar.width - 2, bar.height - 2), true);
				// Drawn, not a text arrow, as the game's fonts have no arrow glyphs.
				int cy = bar.y + bar.height / 2;
				g2.setColor(getForeground());
				g2.fillPolygon(folded ? new int[]{9, 9, 15} : new int[]{8, 16, 12}, folded ? new int[]{cy - 4, cy + 4, cy}
					: new int[]{cy - 2, cy - 2, cy + 3}, 3);
				g2.setFont(small);
				FontMetrics fm = g2.getFontMetrics();
				Rectangle chip = new Rectangle(bar.x + bar.width - fm.stringWidth(count) - 16, bar.y + 4,
					fm.stringWidth(count) + 10, bar.height - 8);
				g2.setColor(PanelStyle.FIELD);
				g2.fillRect(chip.x, chip.y, chip.width, chip.height);
				g2.setColor(Color.BLACK);
				g2.drawRect(chip.x, chip.y, chip.width - 1, chip.height - 1);
				g2.setColor(PanelStyle.ORANGE);
				g2.drawString(count, chip.x + 5, chip.y + (chip.height + fm.getAscent()) / 2 - 1);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		header.setFont(sideFont(2));
		header.setForeground(name.startsWith("Selected") ? Color.WHITE : PanelStyle.YELLOW);
		header.setBorder(new EmptyBorder(9, 22, 4, 4));
		return smooth(header);
	}

	/**
	 * Old School card: dark stone in a bevelled frame, the tier down the left, white name,
	 * yellow category, orange chunk, and a soft green bar in a dark groove.
	 */
	private JLabel sideCard(NuzlockeTask task, boolean hover, Font small)
	{
		boolean selected = lists.isTracked(task);
		boolean global = plugin.isGlobalTask(task.getTaskId());
		int target = task.getTargetQuantity();
		int progress = Math.min(task.getCurrentProgress(), target);
		Color tier = TaskCardTier.fromTask(task).getAccent();
		String description = task.getDescription() == null ? "" : TaskSources.tidy(task.getDescription().trim());
		boolean saved = sideSaved.contains(task.getTaskId());
		// Drawn line by line, not as HTML: HTML rounds the font to whole points, which undoes
		// sideFont's exact size (and the name kept one size whatever the step).
		// The ChunkBlazer theme keeps the original card colours: green name, gold category,
		// blue chunk, grey description, on a card filled with the tier's colour.
		boolean original = Skin.theme() == Theme.ORIGINAL;
		List<Object[]> lines = new ArrayList<>();
		sideLines(lines, task.getName() == null ? task.getTaskId() : task.getName(),
			original ? new Color(150, 255, 150) : Color.WHITE, sideFont(1));
		// The level is the category's skill's ("Farming  Lvl 30"); with Category off, the skill
		// still goes with it ("Farming Lvl 30"), or the number alone wouldn't say which skill.
		boolean showCategory = detail(SideDetail.CATEGORY);
		boolean showLevel = detail(SideDetail.LEVEL) && task.getLevelRequirement() > 1;
		String skillName = NuzlockeTask.displayCategory(task.getCategory());
		String category = (showCategory ? skillName : "")
			+ (showLevel ? (showCategory ? "  Lvl " : skillName + " Lvl ") + task.getLevelRequirement() : "");
		sideLines(lines, category, original ? new Color(255, 200, 0) : Color.YELLOW, small);
		if (!global && detail(SideDetail.CHUNK))
		{
			sideLines(lines, lists.chunkNames.getOrDefault(task.getTaskId(), ""),
				original ? new Color(140, 200, 230) : new Color(255, 152, 31), small);
		}
		if (detail(SideDetail.DESCRIPTION))
		{
			sideLines(lines, description, original ? new Color(185, 185, 185) : new Color(200, 180, 140), small);
		}
		if (detail(SideDetail.DETAILS))
		{
			// What the task window shows when the task is expanded: requirements, then where to get it.
			List<String> extra = new ArrayList<>(lists.requirementsFor(task));
			extra.removeIf(line -> line.equals(description));
			// The Source line is left out when it's just the chunk's name, shown above.
			if (ui.get().showTaskDetails())
			{
				extra.addAll(TaskSources.lines(task, !global && detail(SideDetail.CHUNK) ? lists.chunkNames.get(task.getTaskId()) : null));
			}
			for (String line : extra)
			{
				sideLines(lines, line, original ? new Color(215, 215, 215) : new Color(200, 195, 180), small);
			}
		}
		// One-off tasks (a target of 1) get no bar: "0/1" says nothing.
		boolean bar = !global && target > 1 && detail(SideDetail.PROGRESS);
		boolean points = detail(SideDetail.POINTS);
		JLabel card = new JLabel()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				int w = getWidth();
				int h = getHeight() - 3;
				if (original)
				{
					originalCard(g2, w, h, tier, selected, hover, saved, !bar, progress, target, getFont());
				}
				else
				{
					themedCard(g2, w, h, tier, selected, hover, saved, bar, progress, target, getFont());
				}
				if (points)
				{
					banner(g2, w - 20, 3, tier, task.getBasePoints());
				}
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				int y = 6;
				for (Object[] line : lines)
				{
					FontMetrics fm = g2.getFontMetrics((Font) line[2]);
					g2.setFont((Font) line[2]);
					g2.setColor((Color) line[1]);
					g2.drawString((String) line[0], 12, y + fm.getAscent());
					y += fm.getHeight();
				}
				g2.dispose();
			}
		};
		card.setFont(small);
		int height = 6 + (bar ? 25 : 9);
		for (Object[] line : lines)
		{
			height += card.getFontMetrics((Font) line[2]).getHeight();
		}
		card.setPreferredSize(new Dimension(168, height));
		return card;
	}

	/** Whether side panel cards show {@code d} (loaded from config the first time). */
	public boolean detail(SideDetail d)
	{
		Set<SideDetail> on = sideDetail;
		if (on == null)
		{
			on = EnumSet.noneOf(SideDetail.class);
			String saved = configManager.getConfiguration(CONFIG_GROUP, "sideDetail");
			for (SideDetail x : SideDetail.values())
			{
				if (saved == null ? x.byDefault : Arrays.asList(saved.split(",")).contains(x.name()))
				{
					on.add(x);
				}
			}
			sideDetail = on;
		}
		return on.contains(d);
	}

	/** The gear at the top right of the header box: opens the menu of what each card shows. */
	private JComponent gearButton()
	{
		JLabel button = new JLabel()
		{
			private boolean hover;

			{
				addMouseListener(new java.awt.event.MouseAdapter()
				{
					@Override
					public void mouseEntered(MouseEvent e)
					{
						hover = true;
						repaint();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						hover = false;
						repaint();
					}

					@Override
					public void mousePressed(MouseEvent e)
					{
						detailMenu(e.getComponent());
					}
				});
			}

			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				gear(g2, new Rectangle(2, (getHeight() - 14) / 2, 14, 14), hover ? PanelStyle.ORANGE : new Color(170, 170, 170));
				g2.dispose();
			}
		};
		button.setPreferredSize(new Dimension(18, 18));
		button.setToolTipText("Choose what each task card shows");
		button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		return button;
	}

	/**
	 * What each side panel card shows, as a list like the Sort dropdown's: click a row to
	 * turn it on or off. It stays open for as many as you like, and closes on a click outside.
	 */
	private void detailMenu(java.awt.Component at)
	{
		detail(SideDetail.CATEGORY);
		JPopupMenu menu = new JPopupMenu();
		menu.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		menu.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JPanel rows = new JPanel(new GridLayout(0, 1));
		rows.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JComponent title = splitRow("Show on each card", null, PanelStyle.ORANGE, false);
		rows.add(title);
		for (SideDetail d : SideDetail.values())
		{
			JPanel holder = new JPanel(new BorderLayout());
			holder.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			Runnable redraw = () ->
			{
				holder.removeAll();
				holder.add(detailRow(d, false));
				holder.revalidate();
				holder.repaint();
			};
			redraw.run();
			holder.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					holder.removeAll();
					holder.add(detailRow(d, true));
					holder.revalidate();
					holder.repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					redraw.run();
				}

				@Override
				public void mousePressed(MouseEvent e)
				{
					Set<SideDetail> on = EnumSet.noneOf(SideDetail.class);
					on.addAll(sideDetail);
					if (!on.remove(d))
					{
						on.add(d);
					}
					sideDetail = on;
					configManager.setConfiguration(CONFIG_GROUP, "sideDetail",
						on.stream().map(Enum::name).collect(Collectors.joining(",")));
					sideKey = "";
					sideChanged();
					holder.removeAll();
					holder.add(detailRow(d, true));
					holder.revalidate();
					holder.repaint();
				}
			});
			rows.add(holder);
		}
		menu.add(rows);
		menu.pack();
		// Opens down and to the left of the gear, inside the panel.
		menu.show(at, at.getWidth() - menu.getPreferredSize().width, at.getHeight() + 2);
	}

	/** One row of the card details menu: its name, then a tick box past the divider. */
	private JComponent detailRow(SideDetail d, boolean hover)
	{
		boolean on = sideDetail.contains(d);
		JComponent row = splitRow(d.label.replaceFirst(" \\(.*", ""), null, on ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR, hover);
		JLabel tick = new JLabel(new javax.swing.Icon()
		{
			@Override
			public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(ColorScheme.DARK_GRAY_COLOR);
				g2.fillRect(x, y, 10, 10);
				g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g2.drawRect(x, y, 9, 9);
				if (on)
				{
					g2.setColor(PanelStyle.ORANGE);
					g2.setStroke(new BasicStroke(2f));
					g2.drawPolyline(new int[]{x + 2, x + 4, x + 8}, new int[]{y + 5, y + 7, y + 2}, 3);
				}
				g2.dispose();
			}

			@Override
			public int getIconWidth()
			{
				return 10;
			}

			@Override
			public int getIconHeight()
			{
				return 10;
			}
		}, SwingConstants.CENTER);
		tick.setBorder(new CompoundBorder(new MatteBorder(0, 1, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR), new EmptyBorder(0, 1, 0, 0)));
		tick.setPreferredSize(new Dimension(22, 10));
		row.add(tick, BorderLayout.EAST);
		return row;
	}

	/** A small gear: a toothed ring with a hole. */
	private static void gear(Graphics2D g, Rectangle r, Color color)
	{
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		double cx = r.getCenterX();
		double cy = r.getCenterY();
		double outer = r.width / 2.0;
		java.awt.geom.Path2D teeth = new java.awt.geom.Path2D.Double();
		for (int i = 0; i < 16; i++)
		{
			double a = Math.PI * 2 * i / 16;
			double rr = i % 2 == 0 ? outer : outer * 0.72;
			double a2 = a + Math.PI * 2 / 16;
			if (i == 0)
			{
				teeth.moveTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
			}
			else
			{
				teeth.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
			}
			teeth.lineTo(cx + Math.cos(a2) * rr, cy + Math.sin(a2) * rr);
		}
		teeth.closePath();
		java.awt.geom.Area shape = new java.awt.geom.Area(teeth);
		shape.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(cx - outer * 0.32, cy - outer * 0.32,
			outer * 0.64, outer * 0.64)));
		g.setColor(new Color(0, 0, 0, 160));
		g.fill(java.awt.geom.AffineTransform.getTranslateInstance(1, 1).createTransformedShape(shape));
		g.setColor(color);
		g.fill(shape);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}

	/** Text wrapped to a card's width, each line with its colour and font, for sideCard to draw. */
	private void sideLines(List<Object[]> lines, String text, Color color, Font font)
	{
		if (!text.isEmpty())
		{
			for (String line : wrap(sideList.getFontMetrics(font), text, 150))
			{
				lines.add(new Object[]{line, color, font});
			}
		}
	}

	/** A side panel card's background in the other themes: stone, a tier stripe, the progress bar. */
	private static void themedCard(Graphics2D g2, int w, int h, Color tier, boolean selected, boolean hover,
		boolean saved, boolean bar, int progress, int target, Font font)
	{
		Rectangle box = new Rectangle(0, 0, w, h);
		g2.setPaint(Skin.stone(true));
		g2.fillRect(0, 0, w, h);
		if (Skin.palette().theme == Theme.OLD_SCHOOL)
		{
			// Old School's cards are near black, the stone only just showing through.
			g2.setColor(new Color(0, 0, 0, 108));
			g2.fillRect(0, 0, w, h);
		}
		// Saved: a warm gold wash over the whole card, easy to spot down the list.
		if (saved)
		{
			g2.setColor(new Color(255, 200, 40, 34));
			g2.fillRect(0, 0, w, h);
		}
		if (selected || hover)
		{
			g2.setColor(selected ? new Color(255, 152, 31, 40) : new Color(255, 255, 255, 22));
			g2.fillRect(0, 0, w, h);
		}
		Skin.bevel(g2, box, true);
		g2.setColor(tier);
		g2.fillRect(3, 3, 3, h - 6);
		if (selected)
		{
			g2.setColor(PanelStyle.ORANGE);
			g2.drawRect(0, 0, w - 1, h - 1);
		}
		if (bar)
		{
			String count = progress + "/" + target;
			g2.setFont(font);
			int textWidth = g2.getFontMetrics().stringWidth(count);
			int barWidth = w - 32 - textWidth;
			Skin.bar(g2, 13, h - 15, barWidth, 7, (float) (progress / (double) Math.max(1, target)), null, 0, false);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setColor(Color.BLACK);
			g2.drawString(count, w - 11 - textWidth, h - 6);
			g2.setColor(PanelStyle.YELLOW);
			g2.drawString(count, w - 12 - textWidth, h - 7);
		}
	}

	/**
	 * The ChunkBlazer theme's side panel card, as the plugin first drew it: a rounded card filled
	 * with the tier's colour (brighter when hovered or tracked), a flame strip down the left,
	 * and a plain green progress bar with "x/y" at its right.
	 */
	private static void originalCard(Graphics2D g2, int w, int h, Color tier, boolean selected, boolean hover,
		boolean saved, boolean global, int progress, int target, Font font)
	{
		Color flame = new Color(255, 152, 0);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		java.awt.geom.RoundRectangle2D box = new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, 12, 12);
		float shade = selected ? 0.66f : hover ? 0.58f : 0.48f;
		g2.setColor(new Color((int) (tier.getRed() * shade), (int) (tier.getGreen() * shade), (int) (tier.getBlue() * shade)));
		g2.fill(box);
		java.awt.Shape clip = g2.getClip();
		g2.clip(box);
		if (saved)
		{
			g2.setColor(new Color(255, 200, 40, 34));
			g2.fillRect(0, 0, w, h);
		}
		g2.setColor(flame);
		g2.fillRect(0, 0, 5, h);
		g2.setClip(clip);
		g2.setColor(selected ? flame : tier.darker());
		g2.setStroke(new BasicStroke(selected ? 1.6f : 1f));
		g2.draw(box);
		g2.setStroke(new BasicStroke(1f));
		if (!global)
		{
			String count = progress + "/" + target;
			g2.setFont(font);
			int textWidth = g2.getFontMetrics().stringWidth(count);
			int barWidth = w - 30 - textWidth;
			g2.setColor(new Color(60, 60, 60));
			g2.fillRect(12, h - 15, barWidth, 8);
			g2.setColor(new Color(80, 180, 80));
			g2.fillRect(12, h - 15, (int) (barWidth * (progress / (double) Math.max(1, target))), 8);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setColor(Color.WHITE);
			g2.drawString(count, w - 12 - textWidth, h - 7);
		}
	}

	/** The ChunkBlazer theme's section heading, as first drawn: coloured name, grey count, a line under it. */
	private JLabel originalHeader(String name, String count, boolean folded)
	{
		JLabel header = new JLabel(name + "  " + count)
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				int cy = 6 + (getHeight() - 8) / 2;
				g2.setColor(getForeground());
				g2.fillPolygon(folded ? new int[]{9, 9, 15} : new int[]{8, 16, 12}, folded ? new int[]{cy - 4, cy + 4, cy}
					: new int[]{cy - 2, cy - 2, cy + 3}, 3);
				g2.setColor(new Color(70, 70, 70));
				g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		header.setText("<html>" + name + " <font color='#8c8c8c'>" + count + "</font></html>");
		header.setFont(sideFont(2));
		header.setForeground(name.startsWith("Selected") ? new Color(255, 152, 0) : name.startsWith("Active") ? new Color(100, 255, 100)
			: name.startsWith("Chunk") ? Color.WHITE : new Color(255, 200, 80));
		header.setBorder(new EmptyBorder(9, 22, 5, 4));
		return smooth(header);
	}

	/**
	 * The task's points on a small badge in the tier's colour (side panel cards), shaped for
	 * the theme: a round coin for ChunkBlazer, a shield with a gold chief for Varlamore (its
	 * crest is a shield), and a hanging banner for Old School.
	 */
	private static void banner(Graphics2D g, int x, int y, Color tier, int points)
	{
		int w = 14;
		int h = 18;
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Theme theme = Skin.theme();
		double centreX;
		double centreY;
		if (theme == Theme.ORIGINAL)
		{
			int d = 16;
			int cx = x - 1;
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(tier);
			g.fillOval(cx, y, d, d);
			g.setColor(new Color(255, 255, 255, 70));
			g.fillArc(cx + 2, y + 1, d - 4, d - 6, 20, 140);
			g.setColor(Color.BLACK);
			g.drawOval(cx, y, d, d);
			centreX = cx + d / 2.0;
			centreY = y + d / 2.0;
		}
		else if (theme == Theme.ROYAL)
		{
			java.awt.geom.Path2D shield = new java.awt.geom.Path2D.Double();
			shield.moveTo(x, y);
			shield.lineTo(x + w, y);
			shield.lineTo(x + w, y + h * 0.5);
			shield.quadTo(x + w, y + h * 0.85, x + w / 2.0, y + h);
			shield.quadTo(x, y + h * 0.85, x, y + h * 0.5);
			shield.closePath();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(tier);
			g.fill(shield);
			// The gold chief across the top, as on Varlamore's crest.
			g.setColor(new Color(211, 174, 84));
			g.fillRect(x + 1, y + 1, w - 1, 3);
			g.setColor(new Color(60, 40, 20));
			g.draw(shield);
			centreX = x + w / 2.0;
			centreY = y + 4 + (h - 4) * 0.42;
		}
		else
		{
			Polygon flag = new Polygon(new int[]{x, x + w, x + w, x + w / 2, x}, new int[]{y, y, y + h, y + h - 5, y + h}, 5);
			g.setColor(tier);
			g.fillPolygon(flag);
			g.setColor(new Color(255, 255, 255, 60));
			g.fillRect(x + 1, y + 1, w - 2, 2);
			g.setColor(Color.BLACK);
			g.drawPolygon(flag);
			centreX = x + w / 2.0;
			centreY = y + (h - 5) / 2.0 + 1;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
		// Bold, and centred on the badge by the digits' own drawn outline, not the font's box.
		// Two-digit numbers too wide for the badge in bold drop to the small font.
		String text = String.valueOf(points);
		java.awt.Font font = FontManager.getRunescapeBoldFont();
		java.awt.geom.Rectangle2D ink = font.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
		if (ink.getWidth() > w - 3)
		{
			font = FontManager.getRunescapeSmallFont();
			ink = font.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
		}
		g.setFont(font);
		int tx = (int) Math.round(centreX - ink.getWidth() / 2 - ink.getX());
		int ty = (int) Math.round(centreY - ink.getHeight() / 2 - ink.getY());
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(Color.BLACK);
		g.drawString(text, tx + 1, ty + 1);
		g.setColor(Color.WHITE);
		g.drawString(text, tx, ty);
	}
}
