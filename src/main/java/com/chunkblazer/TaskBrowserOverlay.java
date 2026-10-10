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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.FlatTextField;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.LinkBrowser;

/**
 * Leagues-style task window, opened from the Points orb (ChunkBlazerOrbWidget).
 *
 * Tabs: Active, Saved (starred), Archived (book button) and, after new tasks arrive,
 * New. Search matches name, description, category and chunk; "Current chunk" follows
 * you. The tasks key + click on an unlocked chunk on the world map opens its tasks.
 * Filter: a skill, a tier, task types (any ticked type) and conditions (all ticked
 * conditions); the eye button hides a type or condition. Sort only changes the order.
 * Settings live in RuneLite's plugin config. Drag the title to move the window.
 *
 * Hover is worked out while drawing, from the game's own mouse position, so clicks
 * line up in stretched and resized modes.
 */
@Singleton
public class TaskBrowserOverlay extends Overlay
{
	private static final String CONFIG_GROUP = "chunkblazer";
	private static final String SAVED_KEY = "savedTasks";
	private static final String SEEN_KEY = "seenTasks";

	// New-task alert: ticks to wait after login, how long the pointer shows, pulse speed.
	private static final int SETTLE_TICKS = 10;
	private static final long HINT_MS = 6000;
	private static final long HINT_FADE_MS = 1500;
	private static final long PULSE_MS = 1200;

	private static final int MAX_WIDTH = 460;
	private static final int MAX_HEIGHT = 400;
	private static final int HEADER = 30;
	private static final int TABS = 26;
	private static final int ROW = 38;
	private static final int PAD = 8;
	private static final int ONE_OFF_BLANK = 100;
	private static final int MENU_ROW = 20;
	private static final int SKILL_CELL = 30;
	private static final int SKILL_COLUMNS = 6;
	private static final long ROW_REFRESH_MS = 1000;
	private static final int DETAIL_LINE = 13;
	private static final int SECTION_TEXT = 11;
	private static final int HIDE_BUTTON = 20;
	private static final int QUEST_CHECKS_PER_TICK = 15;
	private static final int MAX_SEARCH = 40;
	// Arrow directions for arrow().
	private static final int UP = 0;
	private static final int DOWN = 1;
	private static final int RIGHT = 2;

	private static final Color BACKGROUND = new Color(38, 33, 27, 242);
	private static final Color MENU_BACKGROUND = new Color(28, 24, 20, 250);
	private static final Color BORDER = new Color(110, 95, 65);
	private static final Color TITLE = new Color(255, 152, 31);
	private static final Color TAB_ON = new Color(70, 60, 45);
	private static final Color TAB_OFF = new Color(48, 42, 34);
	private static final Color ROW_ALT = new Color(255, 255, 255, 10);
	private static final Color ROW_HOVER = new Color(255, 255, 255, 28);
	private static final Color SUBTEXT = new Color(170, 160, 140);
	private static final Color POINTS = new Color(255, 200, 100);
	private static final Color NO_LEVEL = new Color(255, 90, 90);
	private static final Color STAR_ON = new Color(255, 215, 0);
	private static final Color STAR_OFF = new Color(120, 110, 90);
	private static final Color DARK = new Color(20, 18, 15);
	private static final Color BAR_FILL = new Color(255, 140, 0);
	private static final Color DETAIL = new Color(200, 195, 180);
	private static final Color TRACKED_FILL = new Color(255, 140, 0, 45);
	private static final Color BOOK_ON = new Color(190, 140, 90);

	enum Tab
	{
		ACTIVE, SAVED, ARCHIVED, NEW
	}

	enum SortField
	{
		POINTS("Points", false),
		PROGRESS("Progress", false),
		LEVEL("Level needed", true),
		CHUNK("Chunk", true),
		NAME("Name", true);

		final String label;
		final boolean ascendingByDefault;

		SortField(String label, boolean ascendingByDefault)
		{
			this.label = label;
			this.ascendingByDefault = ascendingByDefault;
		}
	}

	/**
	 * Tickable filters. Types are what a task is, so ticking several shows any of them
	 * (Combat + Talk to); conditions narrow whatever is shown, so all ticked ones must
	 * hold (Quests + Can do = ready quests). Hiding either removes those tasks.
	 */
	enum Filter
	{
		COMBAT("Kills & combat", "Combat", false),
		OBTAIN("Obtain items", "Obtain", false),
		EQUIP("Equip items", "Equip", false),
		TALK("Talk to", "Talk to", false),
		ACHIEVEMENTS("Raids & combat achievements", "Raids & CAs", false),
		QUESTS("Quests (global)", "Quests", false),
		PROGRESSION("Level ups (global)", "Level ups", false),
		CAN_DO("Can do now", "Can do", true),
		OFFLINE("Works offline (mobile)", "Offline", true),
		BOSS("Boss chunks", "Bosses", true);

		final String label;
		final String shortLabel;
		final boolean condition;

		Filter(String label, String shortLabel, boolean condition)
		{
			this.label = label;
			this.shortLabel = shortLabel;
			this.condition = condition;
		}
	}

	enum Menu
	{
		NONE, SORT, FILTER, SKILLS, TIERS
	}

	/** A list line: a task, or the header of one skill's level-up tasks (group set). */
	private static final class Entry
	{
		final NuzlockeTask task;
		final String group;
		final List<NuzlockeTask> members;
		final boolean child;

		Entry(NuzlockeTask task, String group, List<NuzlockeTask> members, boolean child)
		{
			this.task = task;
			this.group = group;
			this.members = members;
			this.child = child;
		}
	}

	/** A clickable area drawn this frame. Checked in order, so earlier wins. */
	private static final class Hit
	{
		final Shape area;
		final Runnable action;

		Hit(Shape area, Runnable action)
		{
			this.area = area;
			this.action = action;
		}
	}

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ConfigManager configManager;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final SkillIconManager skillIcons;
	private final KeyManager keyManager;
	private final EventBus eventBus;
	private final ChunkBlazerConfig config;
	private final ChunkBlazerWorldMapOverlay worldMap;
	private final TaskArchive archive;
	private final ChatboxPanelManager chatboxPanelManager;
	private final ClientThread clientThread;
	private final SavedTaskTracker savedTracker;
	private final TaskItemOverlay itemOverlay;
	private final ChunkNameBanner banner;
	private final WorldMapLegendOverlay legend;
	private final QuestRequirements quests;

	private volatile boolean open;
	private volatile String search = "";
	private volatile boolean searchFocused;
	private volatile boolean currentChunkOnly;
	// A chunk picked on the world map; while set, only its tasks are listed.
	private volatile String pinnedChunk;
	private volatile boolean tasksKeyHeld;
	private int lastRegionSeen = -1;
	private final Set<String> expanded = ConcurrentHashMap.newKeySet();
	private final Set<String> expandedGroups = ConcurrentHashMap.newKeySet();
	private volatile Tab tab = Tab.ACTIVE;
	private volatile SortField sortField = SortField.POINTS;
	private volatile boolean ascending = SortField.POINTS.ascendingByDefault;
	// Filters. The sets are replaced, never changed in place, so a list build sees one state.
	private volatile Set<Filter> filterOn = Collections.emptySet();
	private volatile Set<Filter> filterHidden = Collections.emptySet();
	private volatile Skill filterSkill;
	private volatile TaskCardTier filterTier;
	private volatile Menu menu = Menu.NONE;
	private volatile Rectangle menuBox;
	private volatile int scroll;
	// A quest link was clicked: scroll to and briefly outline this task.
	// The quest whose requirements are shown over the side panel (null when closed).
	private volatile NuzlockeTask questPane;
	// Stands in for the saved-tasks list in the pane, so Back and closing work the same.
	private static final NuzlockeTask SAVED_PANE = new NuzlockeTask();
	private long questPaneAt;
	// Quests opened from inside the pane, newest first, for its Back link.
	private final Deque<NuzlockeTask> questBack = new ConcurrentLinkedDeque<>();

	// New-task alert: unseen active tasks, the snapshot the New tab shows, the Points orb.
	private final Set<String> newIds = ConcurrentHashMap.newKeySet();
	private volatile Set<String> shownNew = Collections.emptySet();
	private volatile Rectangle taskButton;
	private volatile long newArrivedAt;
	private int ticksLoggedIn;

	private final List<Hit> hits = new ArrayList<>();
	private final List<Hit> menuHits = new ArrayList<>();
	private volatile Runnable hoveredAction;
	private volatile boolean mouseInWindow;

	// Dragging the window by its title. Drag events are swallowed, so movement is read
	// from the events, not the game's mouse position.
	private volatile boolean titleHovered;
	private volatile boolean dragging;
	private volatile boolean moved;
	private volatile int pressX;
	private volatile int pressY;
	private volatile int startX;
	private volatile int startY;
	private volatile int windowX;
	private volatile int windowY;
	private volatile int customX;
	private volatile int customY;

	private List<Entry> rows = new ArrayList<>();
	private final Map<String, String> chunkNames = new HashMap<>();
	private final Map<String, List<String>> requirements = new HashMap<>();
	private long rowsBuiltAt;

	// The real mouse spot while over the quest pane; the game is told it's off-screen.
	private volatile java.awt.Point paneMouse = new java.awt.Point(-1, -1);

	private final MouseAdapter mouse = new MouseAdapter()
	{
		// Over the quest pane, the game sees the mouse leave, so nothing under it
		// (inventory items, prayers...) shows its hover text or tooltips.
		@Override
		public MouseEvent mouseMoved(MouseEvent event)
		{
			if (open || questPane == null)
			{
				return event;
			}
			paneMouse = event.getPoint();
			return mouseInWindow ? new MouseEvent(event.getComponent(), event.getID(), event.getWhen(),
				event.getModifiersEx(), -1, -1, 0, false) : event;
		}

		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (!open && questPane == null || !mouseInWindow)
			{
				return event;
			}
			if (event.getButton() == MouseEvent.BUTTON1 && titleHovered)
			{
				pressX = event.getX();
				pressY = event.getY();
				startX = windowX;
				startY = windowY;
				dragging = true;
			}
			else if (event.getButton() == MouseEvent.BUTTON1 && hoveredAction != null)
			{
				hoveredAction.run();
			}
			// Clicks inside the window never reach the game.
			event.consume();
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			if (dragging)
			{
				customX = startX + event.getX() - pressX;
				customY = startY + event.getY() - pressY;
				moved = true;
				event.consume();
			}
			return event;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			if (dragging)
			{
				dragging = false;
				event.consume();
			}
			return event;
		}
	};

	private final MouseWheelListener wheel = event ->
	{
		if ((open || questPane != null) && mouseInWindow && menu == Menu.NONE)
		{
			scroll += event.getWheelRotation() * ROW;
			event.consume();
		}
		return event;
	};

	// Esc closes the window; any key closes the quest pane. Search typing goes through a chatbox
	// input (startSearch), so key-remapping plugins don't eat the letters.
	private final KeyListener keys = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent event)
		{
		}

		@Override
		public void keyPressed(KeyEvent event)
		{
			if (config.worldMapTasksKey().matches(event))
			{
				tasksKeyHeld = true;
			}
			// Any key (a tab hotkey, Esc...) closes the quest pane, so it never blocks a prayer switch.
			// Shift, Ctrl, Alt and the arrow keys (camera) don't: Shift + click unsaves in the saved list.
			int code = event.getKeyCode();
			if (code != KeyEvent.VK_SHIFT && code != KeyEvent.VK_CONTROL && code != KeyEvent.VK_ALT
				&& (code < KeyEvent.VK_LEFT || code > KeyEvent.VK_DOWN))
			{
				questPane = null;
			}
			if (open && !searchFocused && event.getKeyCode() == KeyEvent.VK_ESCAPE)
			{
				close();
				event.consume();
			}
		}

		@Override
		public void keyReleased(KeyEvent event)
		{
			if (config.worldMapTasksKey().matches(event))
			{
				tasksKeyHeld = false;
			}
		}
	};

	@Inject
	public TaskBrowserOverlay(Client client, ChunkBlazerPlugin plugin, ConfigManager configManager,
		OverlayManager overlayManager, MouseManager mouseManager, SkillIconManager skillIcons, KeyManager keyManager,
		EventBus eventBus, ChunkBlazerConfig config, ChunkBlazerWorldMapOverlay worldMap, TaskArchive archive,
		ChatboxPanelManager chatboxPanelManager, ClientThread clientThread, SavedTaskTracker savedTracker,
		TaskItemOverlay itemOverlay, ChunkNameBanner banner, WorldMapLegendOverlay legend,
		QuestRequirements quests)
	{
		this.client = client;
		this.plugin = plugin;
		this.configManager = configManager;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.skillIcons = skillIcons;
		this.keyManager = keyManager;
		this.eventBus = eventBus;
		this.config = config;
		this.worldMap = worldMap;
		this.archive = archive;
		this.chatboxPanelManager = chatboxPanelManager;
		this.clientThread = clientThread;
		this.savedTracker = savedTracker;
		this.itemOverlay = itemOverlay;
		this.banner = banner;
		this.legend = legend;
		this.quests = quests;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	/** Registers this window and the other UI overlays (item outlines, banner, map legend). */
	public void startUp()
	{
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouse);
		mouseManager.registerMouseWheelListener(wheel);
		keyManager.registerKeyListener(keys);
		eventBus.register(this);
		quests.load();
		savedTracker.startUp();
		overlayManager.add(itemOverlay);
		overlayManager.add(banner);
		overlayManager.add(legend);
	}

	public void shutDown()
	{
		open = false;
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(mouse);
		mouseManager.unregisterMouseWheelListener(wheel);
		keyManager.unregisterKeyListener(keys);
		eventBus.unregister(this);
		savedTracker.shutDown();
		overlayManager.remove(itemOverlay);
		overlayManager.remove(banner);
		overlayManager.remove(legend);
	}

	/** Open or close the window (the Points orb's "Tasks" option). */
	public void toggle()
	{
		if (open)
		{
			close();
			return;
		}
		open = true;
		questPane = null;
		pinnedChunk = null;
		menu = Menu.NONE;
		stopSearch();
		refresh();
		if (!newIds.isEmpty())
		{
			shownNew = Collections.unmodifiableSet(new LinkedHashSet<>(newIds));
			tab = Tab.NEW;
		}
	}

	private void close()
	{
		open = false;
		menu = Menu.NONE;
		stopSearch();
		pinnedChunk = null;
		finishNew();
	}

	/** Leaving the New tab: its tasks are now seen. */
	private void finishNew()
	{
		if (tab != Tab.NEW)
		{
			return;
		}
		markSeen(shownNew);
		newIds.removeAll(shownNew);
		shownNew = Collections.emptySet();
		tab = Tab.ACTIVE;
		rowsBuiltAt = 0;
	}

	/** Where the Points orb is on screen (null if hidden), for the new-task alert. */
	public void setTaskButtonBounds(Rectangle bounds)
	{
		taskButton = bounds;
	}

	/**
	 * Tasks key + click on an unlocked chunk on the world map: show that chunk's tasks.
	 * Any other click out in the game world (walk, attack, chop...) closes the window,
	 * like the game's own interfaces do. Clicks on interfaces, like the orb, don't.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (open && !tasksKeyHeld && event.getMenuAction() != MenuAction.CANCEL
			&& event.getMenuEntry().getWidget() == null)
		{
			close();
		}
		if (!tasksKeyHeld || client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER) == null)
		{
			return;
		}
		int regionId = worldMap.getHoveredRegionId();
		if (regionId <= 0 || !plugin.isRegionUnlocked(regionId) || plugin.isFreeRegion(regionId))
		{
			return;
		}
		String name = chunkNameFor(regionId);
		if (name.isEmpty())
		{
			return;
		}
		event.consume();
		finishNew();
		pinnedChunk = name;
		currentChunkOnly = false;
		tab = Tab.ACTIVE;
		clearFilters();
		search = "";
		menu = Menu.NONE;
		open = true;
		refresh();
	}

	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (!event.isFocused())
		{
			tasksKeyHeld = false;
		}
	}

	// --- New tasks -----------------------------------------------------------

	/**
	 * Find active chunk tasks not seen yet. With nothing stored (first run on an account),
	 * everything present counts as seen, so there's no flood of "new" tasks.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || configManager.getRSProfileKey() == null)
		{
			return;
		}
		// Every tick from login, so the side panel fills as soon as the tasks arrive.
		updateSideList();
		if (++ticksLoggedIn < SETTLE_TICKS)
		{
			return;
		}
		if (open || questPane != null)
		{
			quests.refreshStates(QUEST_CHECKS_PER_TICK);
		}
		Set<String> active = new LinkedHashSet<>();
		for (NuzlockeTask task : pool(false))
		{
			active.add(task.getTaskId());
		}
		Set<String> seen = idSet(SEEN_KEY);
		if (seen == null)
		{
			markSeen(active);
			return;
		}
		boolean arrived = false;
		for (String id : active)
		{
			arrived |= !seen.contains(id) && newIds.add(id);
		}
		newIds.retainAll(active);
		if (arrived)
		{
			newArrivedAt = System.currentTimeMillis();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			ticksLoggedIn = 0;
			newIds.clear();
			quests.reset();
		}
	}

	// --- Side panel list --------------------------------------------------------

	// The side panel's task list, styled like the original panel: stats, the selected task,
	// then Saved, Chunk, Active and Global sections of rounded tier cards. Click a heading
	// to fold it. It uses the window's task data, so none of that logic is repeated here.
	private static final int SIDE_MAX = 25;
	private static final Color FLAME = new Color(255, 140, 0);
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
	private volatile int sideHover = -1;
	private volatile float sideSize = 16f;
	private final IconTextField sideSearch = new IconTextField();
	private final JComboBox<String> sideSkill = new JComboBox<>();
	private final JComboBox<String> sideTier = new JComboBox<>();
	private final JComboBox<String> sideSort = new JComboBox<>();
	// Side panel filters: like the window's, but each box cycles off, on (shown) and hidden.
	private final Set<Filter> sideOn = ConcurrentHashMap.newKeySet();
	private final Set<Filter> sideHidden = ConcurrentHashMap.newKeySet();
	private final JLabel sideStats = new JLabel();

	/** Once a tick: add the list to the side panel, and refresh it while it's on screen. */
	private void updateSideList()
	{
		ChunkBlazerPanel panel = plugin.getPanel();
		if (!sideAdded && panel != null)
		{
			sideAdded = true;
			sideFolded.addAll(Arrays.asList("Active Tasks", "Global Tasks"));
			sideList.setCellRenderer((list, item, index, selected, focus) -> sideRow(item, index == sideHover));
			java.awt.event.MouseAdapter sideMouse = new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseMoved(MouseEvent e)
				{
					sideHover = sideList.locationToIndex(e.getPoint());
					sideList.repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					sideHover = -1;
					sideList.repaint();
				}

				// On press, not click: a click is lost if the mouse moves at all in between.
				@Override
				public void mousePressed(MouseEvent e)
				{
					int i = sideList.locationToIndex(e.getPoint());
					Object item = i < 0 ? null : sideList.getModel().getElementAt(i);
					if (item instanceof String)
					{
						String section = ((String) item).replaceAll(" \\(.*", "");
						if (!sideFolded.remove(section))
						{
							sideFolded.add(section);
						}
					}
					else if (item instanceof NuzlockeTask && SwingUtilities.isRightMouseButton(e))
					{
						toggleSaved(((NuzlockeTask) item).getTaskId());
					}
					else if (item instanceof NuzlockeTask && QuestRequirements.isQuestTask((NuzlockeTask) item))
					{
						// Quests aren't tracked: their requirements open over the side panel instead.
						openQuestPane((NuzlockeTask) item);
					}
					else if (item instanceof NuzlockeTask)
					{
						plugin.selectTaskFromGame((NuzlockeTask) item);
					}
					// Redraw now rather than on the next game tick (up to 0.6s away).
					sideKey = "";
					clientThread.invoke(TaskBrowserOverlay.this::updateSideList);
				}
			};
			sideList.addMouseListener(sideMouse);
			sideList.addMouseMotionListener(sideMouse);
			sideList.setBackground(new Color(40, 40, 40));
			// Search: filters every section and opens them all while there's text.
			sideSearch.setIcon(IconTextField.Icon.SEARCH);
			sideSearch.setBackground(new Color(62, 62, 62));
			sideSearch.setHoverBackgroundColor(new Color(75, 75, 75));
			for (java.awt.Component c : sideSearch.getComponents())
			{
				if (c instanceof FlatTextField)
				{
					// IconTextField has no text colour setter, so set it on the field inside.
					((FlatTextField) c).getTextField().setForeground(Color.WHITE);
					((FlatTextField) c).getTextField().setCaretColor(Color.WHITE);
					((FlatTextField) c).getTextField().setFont(FontManager.getRunescapeFont());
				}
			}
			sideSearch.setPreferredSize(new Dimension(sideSearch.getPreferredSize().width, 34));
			JPanel search = new JPanel(new BorderLayout());
			search.setOpaque(false);
			search.setBorder(new EmptyBorder(8, 0, 8, 0));
			search.add(sideSearch);
			Runnable research = () ->
			{
				sideKey = "";
				clientThread.invoke(this::updateSideList);
			};
			sideSearch.addClearListener(research);
			sideSearch.addKeyListener(new java.awt.event.KeyAdapter()
			{
				@Override
				public void keyReleased(KeyEvent e)
				{
					research.run();
				}
			});
			// A- / A+ text size, remembered per account.
			String size = configManager.getRSProfileConfiguration(CONFIG_GROUP, "sideTextSize");
			sideSize = size == null ? 16f : Float.parseFloat(size);
			// Everything A-/A+ resizes besides the list itself.
			List<JComponent> scaled = new ArrayList<>(Arrays.asList(sideSkill, sideTier, sideSort));
			Runnable rescale = () -> scaled.forEach(c -> c.setFont(FontManager.getRunescapeSmallFont().deriveFont(sideSize)));
			JPanel sizes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
			sizes.setOpaque(false);
			for (int step : new int[]{-2, 2})
			{
				JButton button = new JButton(step < 0 ? "A-" : "A+");
				button.addActionListener(e ->
				{
					sideSize = Math.max(12, Math.min(24, sideSize + step));
					configManager.setRSProfileConfiguration(CONFIG_GROUP, "sideTextSize", sideSize);
					rescale.run();
					research.run();
				});
				sizes.add(button);
			}
			// Skill and Tier dropdowns, then the filter boxes (any ticked type, every ticked
			// condition, nothing crossed out), two per row. A click cycles off, ✓ on, ✗ hidden.
			sideSkill.addItem("Skills");
			Arrays.stream(Skill.values()).filter(k -> k != Skill.OVERALL).forEach(k -> sideSkill.addItem(k.getName()));
			sideTier.addItem("Tiers");
			Arrays.stream(TaskCardTier.values()).forEach(t -> sideTier.addItem(t.getDisplayName()));
			JPanel filters = new JPanel(new GridLayout(0, 2, 4, 4));
			filters.setOpaque(false);
			filters.setVisible(false);
			// The filters fold under one button, shut to start, which counts what's set.
			JButton fold = new JButton();
			scaled.add(fold);
			Runnable foldText = () -> fold.setText((filters.isVisible() ? "\u25BC Filters" : "\u25B6 Filters")
				+ " (" + (sideOn.size() + sideHidden.size() + (sideSkill.getSelectedIndex() > 0 ? 1 : 0)
				+ (sideTier.getSelectedIndex() > 0 ? 1 : 0)) + ")");
			fold.addActionListener(e ->
			{
				filters.setVisible(!filters.isVisible());
				foldText.run();
				panel.revalidate();
			});
			filters.add(sideSkill);
			filters.add(sideTier);
			// Each sort field twice: its usual direction first, so "Points" high to low leads.
			for (SortField field : SortField.values())
			{
				for (boolean asc : new boolean[]{field.ascendingByDefault, !field.ascendingByDefault})
				{
					sideSort.addItem("Sort: " + field.label + (asc ? " \u25B2" : " \u25BC"));
				}
			}
			for (JComboBox<String> box : Arrays.asList(sideSkill, sideTier, sideSort))
			{
				box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				box.setForeground(Color.WHITE);
				box.addActionListener(e ->
				{
					foldText.run();
					research.run();
				});
			}
			for (Filter f : Filter.values())
			{
				JButton box = new JButton(f.shortLabel);
				scaled.add(box);
				box.addActionListener(e ->
				{
					boolean on = sideOn.remove(f);
					boolean hidden = sideHidden.remove(f);
					if (!on && !hidden)
					{
						sideOn.add(f);
					}
					else if (on)
					{
						sideHidden.add(f);
					}
					box.setText((sideOn.contains(f) ? "\u2713 " : sideHidden.contains(f) ? "\u2717 " : "") + f.shortLabel);
					box.setForeground(sideOn.contains(f) ? new Color(120, 220, 120)
						: sideHidden.contains(f) ? new Color(255, 110, 110) : Color.WHITE);
					foldText.run();
					research.run();
				});
				filters.add(box);
			}
			rescale.run();
			foldText.run();
			panel.addTaskList(sideStats);
			for (JComponent c : Arrays.asList(sizes, search, fold, filters, sideSort))
			{
				c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
				panel.addTaskList(c);
			}
			panel.addTaskList(sideList);
		}
		if (!sideList.isShowing())
		{
			return;
		}
		Set<String> saved = savedIds();
		Set<String> archived = archive.ids();
		String here = currentChunkName();
		List<NuzlockeTask> chunk = new ArrayList<>();
		List<NuzlockeTask> globals = new ArrayList<>();
		String query = sideSearch.getText().trim().toLowerCase();
		int skill = sideSkill.getSelectedIndex();
		int tier = sideTier.getSelectedIndex();
		Predicate<NuzlockeTask> test = filterTest(sideOn, sideHidden,
			skill < 1 ? null : Arrays.stream(Skill.values()).filter(k -> k != Skill.OVERALL).toArray(Skill[]::new)[skill - 1],
			tier < 1 ? null : TaskCardTier.values()[tier - 1]);
		for (NuzlockeTask task : pool(true))
		{
			chunkNames.computeIfAbsent(task.getTaskId(), k -> chunkName(task));
			if (!archived.contains(task.getTaskId()) && (query.isEmpty() || matchesSearch(task, query))
				&& test.test(task))
			{
				(plugin.isGlobalTask(task.getTaskId()) ? globals : chunk).add(task);
			}
		}
		SortField field = SortField.values()[sideSort.getSelectedIndex() / 2];
		Comparator<NuzlockeTask> order = sorter(field, field.ascendingByDefault == (sideSort.getSelectedIndex() % 2 == 0));
		chunk.sort(order);
		globals.sort(order);
		NuzlockeTask tracked = plugin.getSelectedTask();
		List<Object> items = new ArrayList<>();
		addSection(items, "Selected Task", tracked == null ? Collections.emptyList() : Collections.singletonList(tracked));
		List<NuzlockeTask> savedTasks = new ArrayList<>(chunk);
		savedTasks.addAll(globals);
		savedTasks.removeIf(t -> !saved.contains(t.getTaskId()));
		addSection(items, "Saved", savedTasks);
		List<NuzlockeTask> inChunk = new ArrayList<>(chunk);
		inChunk.removeIf(t -> !here.equals(chunkNames.get(t.getTaskId())));
		addSection(items, "Chunk Tasks", inChunk);
		addSection(items, "Active Tasks", chunk);
		addSection(items, "Global Tasks", globals);

		// The stat boxes from the original panel's top row.
		String stats = "<html><table width=190 cellpadding=1><tr>" + stat(plugin.getTotalPoints(), "Points")
			+ stat(paidChunks(), "Chunks") + stat(plugin.getCompletedTaskIdSet().size(), "Tasks")
			+ stat(plugin.getBossTokens(), "Tokens") + "</tr></table></html>";
		// Only rebuild when something changed, so the panel doesn't jump while scrolling.
		StringBuilder key = new StringBuilder(stats + query + skill + tier + sideSort.getSelectedIndex() + sideOn + sideHidden + saved);
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
			sideList.setModel(model);
			sideStats.setText(stats);
			// The panel only grows to fit if it's told the list changed size.
			panel.revalidate();
			panel.repaint();
		});
	}

	/** Chunks paid for with points or a boss token; free, charter and starting chunks don't count. */
	private int paidChunks()
	{
		return plugin.countPayableUnlockedChunks() + (int) plugin.getUnlockedRegionIds().stream()
			.filter(id -> id.trim().matches("\\d+") && plugin.isBossRegion(Integer.parseInt(id.trim()))).count();
	}

	private static String stat(int value, String label)
	{
		return "<td align=center><font color='#ffffff' size=4>" + value + "</font><br><font color='#a0a0a0'>"
			+ label + "</font></td>";
	}

	/** Searching opens every section, so results never hide in a folded one. Filters don't. */
	private boolean sideFiltering()
	{
		return !sideSearch.getText().trim().isEmpty();
	}

	/** A header with its count, then (unless folded) up to SIDE_MAX tasks and a "+N more" line. */
	private void addSection(List<Object> items, String title, List<NuzlockeTask> tasks)
	{
		items.add(title + " (" + tasks.size() + ")");
		if (sideFolded.contains(title) && !sideFiltering())
		{
			return;
		}
		items.addAll(tasks.subList(0, Math.min(SIDE_MAX, tasks.size())));
		if (tasks.size() > SIDE_MAX || tasks.isEmpty())
		{
			items.add(tasks.size() - SIDE_MAX);
		}
	}

	/** A section heading, a "+N more" line, or a rounded tier card like the original panel's. */
	private JLabel sideRow(Object item, boolean hover)
	{
		Font small = FontManager.getRunescapeSmallFont().deriveFont(sideSize);
		if (item instanceof Integer)
		{
			int extra = (Integer) item;
			JLabel more = new JLabel(extra > 0 ? "+" + extra + " more in the task window" : "Nothing here");
			more.setFont(small);
			more.setForeground(Color.GRAY);
			more.setBorder(new EmptyBorder(2, 8, 6, 4));
			return more;
		}
		if (item instanceof String)
		{
			// "Active Tasks 12" in the original section colours, a fold arrow, a divider under it.
			String title = (String) item;
			String name = title.replaceAll(" \\(.*", "");
			boolean folded = sideFolded.contains(name) && !sideFiltering();
			JLabel header = new JLabel("<html>" + (folded ? "&#9654; " : "&#9660; ") + name
				+ " <font color='#8c8c8c'>" + title.substring(name.length()).replaceAll("[ ()]", "") + "</font></html>");
			header.setFont(FontManager.getRunescapeBoldFont().deriveFont(sideSize));
			header.setForeground(name.startsWith("Selected") ? FLAME : name.startsWith("Active") ? new Color(100, 255, 100)
				: name.startsWith("Chunk") ? Color.WHITE : new Color(255, 200, 80));
			header.setBorder(BorderFactory.createCompoundBorder(new EmptyBorder(8, 0, 4, 0),
				BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(70, 70, 70)),
					new EmptyBorder(2, 2, 3, 2))));
			return header;
		}
		NuzlockeTask task = (NuzlockeTask) item;
		NuzlockeTask tracked = plugin.getSelectedTask();
		boolean selected = tracked != null && task.getTaskId().equals(tracked.getTaskId());
		boolean global = plugin.isGlobalTask(task.getTaskId());
		int target = task.getTargetQuantity();
		int progress = Math.min(task.getCurrentProgress(), target);
		Color tier = TaskCardTier.fromTask(task).getAccent();
		String description = task.getDescription() == null ? "" : task.getDescription().trim();
		// Name; category, points and level; chunk; description. Then a progress bar.
		JLabel card = new JLabel("<html><table cellpadding=0 cellspacing=0 width=165><tr><td>"
			+ "<span style='font-size:" + Math.round(sideSize + 2) + "pt'><font color='#96ff96'>"
			+ (saved(task) ? "&#9733; " : "") + (task.getName() == null ? task.getTaskId() : task.getName())
			+ "</font></span><br><font color='#ffc800'>" + NuzlockeTask.displayCategory(task.getCategory())
			+ (task.getLevelRequirement() > 1 ? "  Lvl " + task.getLevelRequirement() : "")
			+ "</font><br><font color='#8cc8e6'>" + (global ? "Global" : chunkNames.getOrDefault(task.getTaskId(), ""))
			+ "</font>" + (description.isEmpty() ? "" : "<br><font color='#b9b9b9'>" + description + "</font>")
			+ "</td></tr></table></html>")
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight() - 3;
				java.awt.geom.RoundRectangle2D box = new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, 12, 12);
				float shade = selected ? 0.66f : hover ? 0.58f : 0.48f;
				g2.setColor(new Color((int) (tier.getRed() * shade), (int) (tier.getGreen() * shade), (int) (tier.getBlue() * shade)));
				g2.fill(box);
				Shape clip = g2.getClip();
				g2.clip(box);
				g2.setColor(FLAME);
				g2.fillRect(0, 0, 5, h);
				g2.setClip(clip);
				g2.setColor(selected ? FLAME : tier.darker());
				g2.setStroke(new BasicStroke(selected ? 1.6f : 1f));
				g2.draw(box);
				if (!global)
				{
					// Progress bar with "x/y" on its right, like the original active-task cards.
					String count = progress + "/" + target;
					int textWidth = g2.getFontMetrics().stringWidth(count);
					int barWidth = w - 30 - textWidth;
					g2.setColor(new Color(60, 60, 60));
					g2.fillRect(12, h - 15, barWidth, 8);
					g2.setColor(new Color(80, 180, 80));
					g2.fillRect(12, h - 15, (int) (barWidth * (progress / (double) Math.max(1, target))), 8);
					g2.setColor(Color.WHITE);
					g2.drawString(count, w - 12 - textWidth, h - 7);
				}
				g2.dispose();
				super.paintComponent(g);
			}
		};
		card.setFont(small);
		card.setBorder(new EmptyBorder(5, 12, global ? 9 : 25, 6));
		return card;
	}

	private boolean saved(NuzlockeTask task)
	{
		return savedIds().contains(task.getTaskId());
	}

	/** Saved tasks still to do, in the order they were starred. */
	List<NuzlockeTask> savedTasks()
	{
		Map<String, NuzlockeTask> byId = new HashMap<>();
		pool(true).forEach(t -> byId.put(t.getTaskId(), t));
		Set<String> archived = archive.ids();
		List<NuzlockeTask> list = new ArrayList<>();
		for (String id : savedIds())
		{
			if (byId.containsKey(id) && !archived.contains(id))
			{
				list.add(byId.get(id));
			}
		}
		return list;
	}

	/** The saved bubble: open the saved list over the side panel, or close it. */
	void toggleSavedPane()
	{
		if (questPane == SAVED_PANE)
		{
			questPane = null;
		}
		else
		{
			openQuestPane(SAVED_PANE);
		}
	}

	/** A comma-separated id set stored per account, or null if never stored. */
	private Set<String> idSet(String key)
	{
		String raw = configManager.getRSProfileConfiguration(CONFIG_GROUP, key);
		if (raw == null)
		{
			return null;
		}
		Set<String> ids = new LinkedHashSet<>();
		for (String id : raw.split(","))
		{
			if (!id.trim().isEmpty())
			{
				ids.add(id.trim());
			}
		}
		return ids;
	}

	private void storeIds(String key, Set<String> ids)
	{
		if (configManager.getRSProfileKey() != null)
		{
			configManager.setRSProfileConfiguration(CONFIG_GROUP, key, String.join(",", ids));
		}
	}

	private void markSeen(Set<String> ids)
	{
		Set<String> seen = idSet(SEEN_KEY);
		Set<String> updated = seen == null ? new LinkedHashSet<>() : seen;
		updated.addAll(ids);
		storeIds(SEEN_KEY, updated);
	}

	private Set<String> savedIds()
	{
		Set<String> ids = idSet(SAVED_KEY);
		return ids == null ? new LinkedHashSet<>() : ids;
	}

	private void toggleSaved(String taskId)
	{
		Set<String> ids = savedIds();
		if (!ids.remove(taskId))
		{
			ids.add(taskId);
		}
		storeIds(SAVED_KEY, ids);
		rowsBuiltAt = 0;
	}

	/** Archive or restore a task. Archiving the tracked task also stops tracking it. */
	private void toggleArchived(NuzlockeTask task)
	{
		boolean archiving = !archive.isArchived(task);
		archive.toggle(task.getTaskId());
		NuzlockeTask tracked = plugin.getSelectedTask();
		if (archiving && tracked != null && task.getTaskId().equals(tracked.getTaskId()))
		{
			plugin.clearSelectedTask();
		}
		rowsBuiltAt = 0;
	}

	// --- Search ---------------------------------------------------------------

	/** Search typed in a chatbox input, like the bank search; the list filters as you type. */
	private void startSearch()
	{
		if (searchFocused)
		{
			return;
		}
		searchFocused = true;
		String current = search;
		clientThread.invoke(() -> chatboxPanelManager.openTextInput("Search tasks")
			.value(current)
			.onChanged(text ->
			{
				search = text.length() > MAX_SEARCH ? text.substring(0, MAX_SEARCH) : text;
				refresh();
			})
			.onDone((Consumer<String>) text -> searchFocused = false)
			.onClose(() -> searchFocused = false)
			.build());
	}

	private void stopSearch()
	{
		if (searchFocused)
		{
			searchFocused = false;
			clientThread.invoke(() -> chatboxPanelManager.close());
		}
	}

	private void refresh()
	{
		scroll = 0;
		rowsBuiltAt = 0;
	}

	// --- Which tasks, in what order -----------------------------------------

	/** Unfinished chunk tasks, plus unfinished Global tasks (quests, level-ups) if asked. */
	private List<NuzlockeTask> pool(boolean includeGlobal)
	{
		List<NuzlockeTask> pool = new ArrayList<>();
		for (NuzlockeTask task : plugin.getActiveTasks())
		{
			if (task != null && task.getTaskId() != null && !task.isCompleted())
			{
				pool.add(task);
			}
		}
		if (includeGlobal)
		{
			Set<String> completed = plugin.getCompletedTaskIdSet();
			for (NuzlockeTask task : plugin.getVisibleGlobalTasks())
			{
				if (task != null && task.getTaskId() != null && !completed.contains(task.getTaskId()))
				{
					pool.add(task);
				}
			}
		}
		return pool;
	}

	private int count(boolean includeGlobal, Predicate<String> test)
	{
		int count = 0;
		for (NuzlockeTask task : pool(includeGlobal))
		{
			count += test.test(task.getTaskId()) ? 1 : 0;
		}
		return count;
	}

	/**
	 * Skill and tier must match, at least one ticked type (if any), every ticked
	 * condition, and no hidden filter.
	 */
	private Predicate<NuzlockeTask> filterTest(Set<Filter> on, Set<Filter> hidden, Skill skill, TaskCardTier tier)
	{
		boolean anyType = on.stream().anyMatch(f -> !f.condition);
		return task -> (skill == null || isSkillTask(task, skill))
			&& (tier == null || TaskCardTier.fromTask(task) == tier)
			&& on.stream().allMatch(f -> !f.condition || matches(f, task))
			&& (!anyType || on.stream().anyMatch(f -> !f.condition && matches(f, task)))
			&& hidden.stream().noneMatch(f -> matches(f, task));
	}

	private boolean matches(Filter f, NuzlockeTask task)
	{
		switch (f)
		{
			case COMBAT:
				return typeIs(task, "NPC_KILL", "SLAYER")
					|| (task.getCategory() != null && task.getCategory().toLowerCase().contains("combat"));
			case OBTAIN:
				return typeIs(task, "OBTAIN");
			case EQUIP:
				return typeIs(task, "EQUIP");
			case TALK:
				return typeIs(task, "NPC_DIALOGUE");
			case ACHIEVEMENTS:
				return typeIs(task, "COMBAT_ACHIEVEMENT", "RAID_CHALLENGE");
			case QUESTS:
				return typeIs(task, "QUEST_CHECK");
			case PROGRESSION:
				return typeIs(task, "SKILL_THRESHOLD");
			case CAN_DO:
				return canDo(task);
			case OFFLINE:
				return isOfflineTrackable(task);
			default:
				return plugin.isBossTask(task);
		}
	}

	/**
	 * True if a task done away from RuneLite (on mobile) still completes at the next
	 * RuneLite login: it's checked against saved game state. Quests (QuestCheckModule
	 * sweep), real levels (ProgressionModule), combat achievement varps, persistent unlock
	 * varbits, items held in inventory/bank/worn (ObtainModule; the bank once opened) and
	 * worn equipment (EquipModule). Kills, skilling XP drops, dialogue and the active-prayer
	 * varbit (it resets) need RuneLite watching.
	 */
	static boolean isOfflineTrackable(NuzlockeTask task)
	{
		return typeIs(task, "QUEST_CHECK", "SKILL_THRESHOLD", "COMBAT_ACHIEVEMENT", "EQUIP", "OBTAIN")
			|| (typeIs(task, "VARBIT_CHECK", "VARP_CHECK") && "unlock".equalsIgnoreCase(task.getCategory()));
	}

	private boolean anyFilter()
	{
		return filterSkill != null || filterTier != null || !filterOn.isEmpty() || !filterHidden.isEmpty();
	}

	private void clearFilters()
	{
		filterOn = Collections.emptySet();
		filterHidden = Collections.emptySet();
		filterSkill = null;
		filterTier = null;
	}

	/** Tick or untick a filter (show) or its eye (hide). One undoes the other. */
	private void toggleFilter(Filter f, boolean hide)
	{
		Set<Filter> on = EnumSet.noneOf(Filter.class);
		on.addAll(filterOn);
		Set<Filter> hidden = EnumSet.noneOf(Filter.class);
		hidden.addAll(filterHidden);
		Set<Filter> mine = hide ? hidden : on;
		if (!mine.remove(f))
		{
			mine.add(f);
			(hide ? on : hidden).remove(f);
		}
		filterOn = Collections.unmodifiableSet(on);
		filterHidden = Collections.unmodifiableSet(hidden);
		refresh();
	}

	/** "Mining + Tier 5 + Combat + no Bosses": every active filter. */
	private String filterLabel()
	{
		List<String> parts = new ArrayList<>();
		if (filterSkill != null)
		{
			parts.add(filterSkill.getName());
		}
		if (filterTier != null)
		{
			parts.add(filterTier.getDisplayName());
		}
		filterOn.forEach(f -> parts.add(f.shortLabel));
		filterHidden.forEach(f -> parts.add("no " + f.shortLabel));
		return parts.isEmpty() ? "All" : String.join(" + ", parts);
	}

	private static boolean typeIs(NuzlockeTask task, String... types)
	{
		String type = task.getCompletionType();
		return type != null && Arrays.stream(types).anyMatch(type::equalsIgnoreCase);
	}

	/** The task trains or needs this skill (exact match: Runecrafting isn't Crafting). */
	private static boolean isSkillTask(NuzlockeTask task, Skill skill)
	{
		if (TaskTargetExtras.categorySkill(task.getCategory()) == skill
			|| TaskTargetExtras.requirements(task).containsKey(skill))
		{
			return true;
		}
		String type = task.getCompletionType();
		if (type != null && type.toLowerCase().startsWith(skill.getName().toLowerCase()))
		{
			return true;
		}
		TaskConstraints c = task.getConstraints();
		return c != null && skill.name().equalsIgnoreCase(c.getRequiredSkill());
	}

	private Comparator<NuzlockeTask> sorter(SortField by, boolean asc)
	{
		Comparator<NuzlockeTask> byName = Comparator.comparing(t -> t.getName() == null ? "" : t.getName());
		Comparator<NuzlockeTask> main;
		switch (by)
		{
			case PROGRESS:
				main = Comparator.comparingDouble(TaskBrowserOverlay::fraction);
				break;
			case LEVEL:
				main = Comparator.comparingInt(NuzlockeTask::getLevelRequirement);
				break;
			case CHUNK:
				main = Comparator.comparing((NuzlockeTask t) -> chunkNames.getOrDefault(t.getTaskId(), ""));
				break;
			case NAME:
				main = byName;
				break;
			default:
				main = Comparator.comparingInt(NuzlockeTask::getBasePoints);
				break;
		}
		return (asc ? main : main.reversed()).thenComparing(byName);
	}

	/** The list for the current tab, search, chunk and filters; rebuilt at most once a second. */
	private List<Entry> currentRows()
	{
		long now = System.currentTimeMillis();
		if (now - rowsBuiltAt < ROW_REFRESH_MS)
		{
			return rows;
		}
		rowsBuiltAt = now;

		String query = search.trim().toLowerCase();
		Set<String> saved = savedIds();
		Set<String> archived = archive.ids();
		Set<String> shown = shownNew;
		Set<Filter> on = filterOn;
		Predicate<NuzlockeTask> test = filterTest(filterOn, filterHidden, filterSkill, filterTier);
		String here = pinnedChunk != null ? pinnedChunk : currentChunkOnly ? currentChunkName() : null;
		// Global tasks (quests, level-ups) join the Active list when asked for, or when searching.
		boolean global = tab == Tab.SAVED || tab == Tab.ARCHIVED || (tab == Tab.ACTIVE && (filterSkill != null
			|| !query.isEmpty() || on.contains(Filter.QUESTS) || on.contains(Filter.PROGRESSION)
			|| on.contains(Filter.OFFLINE)));

		List<NuzlockeTask> list = new ArrayList<>();
		for (NuzlockeTask task : pool(global))
		{
			String id = task.getTaskId();
			chunkNames.computeIfAbsent(id, k -> chunkName(task));
			boolean keep;
			if (tab == Tab.NEW)
			{
				// Just the newly arrived tasks: no filter, archive or chunk limits.
				keep = shown.contains(id);
			}
			else
			{
				keep = (tab == Tab.ARCHIVED) == archived.contains(id)
					&& (tab != Tab.SAVED || saved.contains(id))
					&& (tab != Tab.ACTIVE || test.test(task))
					&& (here == null || here.equals(chunkNames.get(id)));
			}
			if (keep && (query.isEmpty() || matchesSearch(task, query)))
			{
				list.add(task);
			}
		}
		list.sort(sorter(sortField, ascending));
		rows = group(list);
		return rows;
	}

	/** The skill a level-up task belongs to (e.g. "MINING"), or null for any other task. */
	private static String levelUpSkill(NuzlockeTask task)
	{
		TaskConstraints c = task.getConstraints();
		return typeIs(task, "SKILL_THRESHOLD") && c != null && c.getRequiredSkill() != null
			? c.getRequiredSkill().toUpperCase() : null;
	}

	private static int requiredLevel(NuzlockeTask task)
	{
		return task.getConstraints() == null ? 0 : task.getConstraints().getRequiredLevel();
	}

	/** Fold each skill's level-ups into one header where its first rung sorted; rungs under it. */
	private List<Entry> group(List<NuzlockeTask> sorted)
	{
		Map<String, List<NuzlockeTask>> bySkill = new LinkedHashMap<>();
		for (NuzlockeTask task : sorted)
		{
			String skill = levelUpSkill(task);
			if (skill != null)
			{
				bySkill.computeIfAbsent(skill, k -> new ArrayList<>()).add(task);
			}
		}
		bySkill.values().forEach(m -> m.sort(Comparator.comparingInt(TaskBrowserOverlay::requiredLevel)));

		List<Entry> entries = new ArrayList<>();
		Set<String> placed = new HashSet<>();
		for (NuzlockeTask task : sorted)
		{
			String skill = levelUpSkill(task);
			if (skill == null)
			{
				entries.add(new Entry(task, null, null, false));
			}
			else if (placed.add(skill))
			{
				List<NuzlockeTask> members = bySkill.get(skill);
				entries.add(new Entry(null, skill, members, false));
				if (expandedGroups.contains(skill))
				{
					members.forEach(m -> entries.add(new Entry(m, null, null, true)));
				}
			}
		}
		return entries;
	}

	private String currentChunkName()
	{
		return chunkNameFor(plugin.getCurrentRegionId());
	}

	/** Chunk name without its "(region id)" suffix; "" if it isn't a known chunk. */
	private String chunkNameFor(int regionId)
	{
		String name = plugin.getRegionName(regionId);
		return name == null || name.startsWith("Unknown Region") ? "" : stripRegionId(name);
	}

	private String chunkName(NuzlockeTask task)
	{
		if (plugin.isGlobalTask(task.getTaskId()))
		{
			return "Global";
		}
		String name = plugin.getTaskRegionName(task);
		return name == null ? "" : stripRegionId(name);
	}

	private static String stripRegionId(String name)
	{
		return name.replaceAll("\\s*\\(\\d+\\)$", "").trim();
	}

	private boolean matchesSearch(NuzlockeTask task, String query)
	{
		for (String text : new String[]{task.getName(), task.getDescription(),
			NuzlockeTask.displayCategory(task.getCategory()), chunkNames.get(task.getTaskId())})
		{
			if (text != null && text.toLowerCase().contains(query))
			{
				return true;
			}
		}
		return false;
	}

	private List<String> requirementsFor(NuzlockeTask task)
	{
		return requirements.computeIfAbsent(task.getTaskId(), id -> SelectedTaskOverlay.requirementLines(task));
	}

	/** Everything under an expanded task: its requirements, then a quest's requirement sections. */
	private List<QuestRequirements.Line> detailLines(NuzlockeTask task)
	{
		List<QuestRequirements.Line> lines = new ArrayList<>();
		requirementsFor(task).forEach(line -> lines.add(new QuestRequirements.Line(line, DETAIL)));
		lines.addAll(quests.lines(task, this::chunkNameFor));
		return lines;
	}

	private static double fraction(NuzlockeTask task)
	{
		return Math.min(1.0, task.getCurrentProgress() / (double) Math.max(1, task.getTargetQuantity()));
	}

	/** Level checks, real requirements (TaskTargetExtras) and, for quests, their requirements. */
	private boolean canDo(NuzlockeTask task)
	{
		return plugin.meetsLevelRequirement(task) && TaskTargetExtras.missingRequirement(client, task) == null
			&& quests.isReady(task);
	}

	/** "(20 Defence, 20 Ranged)", "(Lvl 40)" or "(Not ready)" for a task you can't do yet. */
	private String levelNote(NuzlockeTask task)
	{
		String missing = TaskTargetExtras.missingRequirement(client, task);
		if (missing != null)
		{
			return "(" + missing + ")";
		}
		return plugin.meetsLevelRequirement(task) ? "(Not ready)" : "(Lvl " + task.getLevelRequirement() + ")";
	}

	// --- Drawing --------------------------------------------------------------

	@Override
	public Dimension render(Graphics2D graphics)
	{
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		if (!open && questPane != null && loggedIn)
		{
			drawQuestPane(graphics);
			return null;
		}
		if (!open || !loggedIn)
		{
			hoveredAction = null;
			mouseInWindow = false;
			if (!open && loggedIn && !newIds.isEmpty())
			{
				drawNewTaskAlert(graphics);
			}
			return null;
		}

		net.runelite.api.Point point = client.getMouseCanvasPosition();
		int mx = point == null ? -1 : point.getX();
		int my = point == null ? -1 : point.getY();
		hits.clear();
		menuHits.clear();
		menuBox = null;

		// Walked into another chunk: rebuild now if the list follows you.
		int region = plugin.getCurrentRegionId();
		if (region != lastRegionSeen)
		{
			lastRegionSeen = region;
			if (currentChunkOnly)
			{
				refresh();
			}
		}

		// Window Scale setting: everything below is laid out at 100% and drawn scaled
		// from the window's corner, with the mouse scaled back to match.
		float scale = config.widgetScale() / 100f;
		int width = Math.min(MAX_WIDTH, (int) ((client.getViewportWidth() - 20) / scale));
		int height = Math.min(MAX_HEIGHT, (int) ((client.getViewportHeight() - 20) / scale));
		int x = client.getViewportXOffset() + (client.getViewportWidth() - (int) (width * scale)) / 2;
		int y = client.getViewportYOffset() + (client.getViewportHeight() - (int) (height * scale)) / 2;
		if (moved)
		{
			x = Math.max(0, Math.min(customX, client.getCanvasWidth() - (int) (width * scale) - 1));
			y = Math.max(0, Math.min(customY, client.getCanvasHeight() - (int) (height * scale) - 1));
		}
		windowX = x;
		windowY = y;
		graphics.translate(x, y);
		graphics.scale(scale, scale);
		graphics.translate(-x, -y);
		mx = mx < 0 ? mx : x + (int) ((mx - x) / scale);
		my = my < 0 ? my : y + (int) ((my - y) / scale);
		Rectangle window = new Rectangle(x, y, width, height);
		boolean menuOpen = menu != Menu.NONE;
		// The mouse as the window's own controls see it: nowhere while a dropdown is open.
		int wx = menuOpen ? -1 : mx;

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		fill(graphics, window, BACKGROUND);
		graphics.setColor(BORDER);
		graphics.drawRect(x, y, width, height);

		Font bold = FontManager.getRunescapeBoldFont();
		Font regular = FontManager.getRunescapeFont();
		Font small = FontManager.getRunescapeSmallFont();

		// Header: title (drag handle), search, current-chunk toggle, close.
		graphics.setFont(bold);
		int titleWidth = graphics.getFontMetrics().stringWidth("ChunkBlazer Tasks");
		titleHovered = new Rectangle(x, y, PAD + titleWidth + 8, HEADER).contains(wx, my);
		graphics.setColor(titleHovered || dragging ? Color.WHITE : TITLE);
		graphics.drawString("ChunkBlazer Tasks", x + PAD, y + 20);
		Rectangle close = new Rectangle(x + width - 24, y + 7, 16, 16);
		hits.add(new Hit(close, this::close));
		cross(graphics, close.x + 8, close.y + 8, 5, close.contains(wx, my) ? NO_LEVEL : SUBTEXT);
		graphics.setFont(small);
		FontMetrics sm = graphics.getFontMetrics();
		String chunkLabel = pinnedChunk != null ? fit(sm, pinnedChunk, 120) : "Current chunk";
		int toggleWidth = sm.stringWidth(chunkLabel) + 18;
		Rectangle chunkToggle = new Rectangle(close.x - 8 - toggleWidth, y + 6, toggleWidth, 19);
		int searchX = x + PAD + titleWidth + 12;
		drawSearch(graphics, new Rectangle(searchX, y + 6, chunkToggle.x - 8 - searchX, 19), wx, my);
		drawChunkToggle(graphics, chunkToggle, chunkLabel, wx, my);

		Rectangle body = new Rectangle(x + 1, y + HEADER, width - 2, height - HEADER - 1);
		// Tabs, then Filter and Sort buttons.
		int barY = body.y;
		int buttonHeight = TABS - 4;
		int tabWidth = 80;
		int tabsWidth = tabWidth * 3 + 8;
		if (tab == Tab.NEW)
		{
			drawTab(graphics, new Rectangle(x + PAD, barY, tabsWidth, buttonHeight),
				"New tasks (" + shownNew.size() + ")", Tab.NEW, wx, my);
		}
		else
		{
			Set<String> archived = archive.ids();
			Set<String> saved = savedIds();
			int[] counts = {count(false, id -> !archived.contains(id)),
				count(true, id -> saved.contains(id) && !archived.contains(id)), count(true, archived::contains)};
			String[] names = {"Active", "Saved", "Archived"};
			for (int i = 0; i < 3; i++)
			{
				drawTab(graphics, new Rectangle(x + PAD + (tabWidth + 4) * i, barY, tabWidth, buttonHeight),
					names[i] + " (" + counts[i] + ")", Tab.values()[i], wx, my);
			}
		}

		int buttonWidth = Math.max(70, (width - PAD * 2 - tabsWidth - 8) / 2);
		Rectangle sortButton = new Rectangle(x + width - PAD - buttonWidth, barY, buttonWidth, buttonHeight);
		Rectangle filterButton = new Rectangle(sortButton.x - 4 - buttonWidth, barY, buttonWidth, buttonHeight);
		// Saved and Archived show all their tasks, so the filter does nothing there.
		boolean filtering = tab == Tab.ACTIVE && anyFilter();
		drawButton(graphics, filterButton, "Filter: " + (tab != Tab.ACTIVE ? "off" : filterLabel()),
			menu == Menu.FILTER || menu == Menu.SKILLS || menu == Menu.TIERS || filtering, wx, my,
			tab != Tab.ACTIVE ? null : () -> menu = menu == Menu.NONE ? Menu.FILTER : Menu.NONE);
		if (filtering)
		{
			// Outlined in orange with an x that clears, so it's clear why tasks are missing.
			graphics.setColor(TITLE);
			graphics.drawRect(filterButton.x, filterButton.y, filterButton.width - 1, filterButton.height - 1);
			Rectangle clear = new Rectangle(filterButton.x + filterButton.width - 16, filterButton.y + 2, 14,
				filterButton.height - 4);
			hits.add(0, new Hit(clear, () ->
			{
				clearFilters();
				refresh();
			}));
			cross(graphics, clear.x + 7, clear.y + clear.height / 2, 3, clear.contains(wx, my) ? NO_LEVEL : SUBTEXT);
		}
		drawButton(graphics, sortButton, "Sort: " + sortField.label, menu == Menu.SORT, wx, my,
			() -> menu = menu == Menu.NONE ? Menu.SORT : Menu.NONE);
		arrow(graphics, sortButton.x + sortButton.width - 10, sortButton.y + sortButton.height / 2,
			ascending ? UP : DOWN, SUBTEXT);

		drawList(graphics, new Rectangle(x + 1, barY + TABS, width - 2, height - HEADER - TABS - 1), wx, my,
			regular, small);

		// Dropdowns go on top, and their clicks win.
		graphics.setFont(small);
		if (menu == Menu.SORT)
		{
			drawSortMenu(graphics, sortButton, mx, my);
		}
		else if (menu == Menu.FILTER)
		{
			drawFilterMenu(graphics, filterButton, mx, my);
		}
		else if (menu == Menu.SKILLS)
		{
			drawSkillMenu(graphics, filterButton, mx, my);
		}
		else if (menu == Menu.TIERS)
		{
			drawTierMenu(graphics, filterButton, mx, my);
		}
		finishHover(window, mx, my);
		return null;
	}

	/** The task list with expanded details, scrolling and the empty-list message. */
	private void drawList(Graphics2D graphics, Rectangle list, int mx, int my, Font regular, Font small)
	{
		List<Entry> entries = currentRows();
		FontMetrics sm = graphics.getFontMetrics(small);
		int detailWidth = list.width - 54;
		List<List<QuestRequirements.Line>> details = new ArrayList<>();
		int contentHeight = 0;
		for (Entry entry : entries)
		{
			List<QuestRequirements.Line> wrapped = entry.task != null && expanded.contains(entry.task.getTaskId())
				? wrapDetail(sm, entry.task, detailWidth) : new ArrayList<>();
			details.add(wrapped);
			contentHeight += ROW + detailHeight(wrapped);
		}
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - list.height)));

		Set<String> saved = savedIds();
		Shape oldClip = graphics.getClip();
		graphics.clip(list);
		if (entries.isEmpty())
		{
			graphics.setFont(regular);
			graphics.setColor(SUBTEXT);
			drawCentered(graphics, emptyMessage(), list);
		}
		int rowY = list.y - scroll;
		for (int i = 0; i < entries.size(); i++)
		{
			Entry entry = entries.get(i);
			int rowHeight = ROW + detailHeight(details.get(i));
			if (rowY + rowHeight >= list.y && rowY <= list.y + list.height)
			{
				int indent = entry.child ? 16 : 0;
				Rectangle area = new Rectangle(list.x + indent, rowY, list.width - 6 - indent, rowHeight);
				boolean hover = list.contains(mx, my) && area.contains(mx, my);
				fill(graphics, area, hover ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : null);
				if (entry.task == null)
				{
					drawGroupRow(graphics, entry, area, list, mx, my, regular, small);
				}
				else
				{
					drawRow(graphics, entry.task, area, details.get(i), saved, list, mx, my, regular, small);
				}
			}
			rowY += rowHeight;
		}
		graphics.setClip(oldClip);
		drawScrollbar(graphics, list, contentHeight);
	}

	private String emptyMessage()
	{
		if (pinnedChunk != null)
		{
			return "No tasks left in " + pinnedChunk + ".";
		}
		if (currentChunkOnly)
		{
			String here = currentChunkName();
			return here.isEmpty() ? "You're not in a chunk with tasks." : "No tasks here in " + here + ".";
		}
		if (search.isEmpty() && tab != Tab.ACTIVE)
		{
			return tab == Tab.NEW ? "No new tasks left to show."
				: tab == Tab.ARCHIVED ? "Click a task's book icon to archive it here."
				: "Star tasks in the Active tab to save them here.";
		}
		return "No tasks match.";
	}

	/** Work out what a click would do. With a menu open, clicking outside it closes it. */
	private void finishHover(Rectangle window, int mx, int my)
	{
		Rectangle box = menuBox;
		boolean inMenu = box != null && box.contains(mx, my);
		mouseInWindow = window.contains(mx, my) || inMenu;
		Runnable hovered = firstHit(menuHits, mx, my);
		if (hovered == null && menu != Menu.NONE)
		{
			hovered = inMenu ? () ->
			{
			} : () -> menu = Menu.NONE;
		}
		hoveredAction = hovered != null ? hovered : firstHit(hits, mx, my);
	}

	private static Runnable firstHit(List<Hit> list, int mx, int my)
	{
		for (Hit hit : list)
		{
			if (hit.area.contains(mx, my))
			{
				return hit.action;
			}
		}
		return null;
	}

	/**
	 * While the window is closed with new tasks waiting: a pulsing glow around the Points
	 * orb, and for a few seconds after they arrive a "New tasks!" pointer that fades out.
	 */
	private void drawNewTaskAlert(Graphics2D graphics)
	{
		Rectangle b = taskButton;
		if (b == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		double pulse = 0.5 + 0.5 * Math.sin(now * 2 * Math.PI / PULSE_MS);
		Composite previousComposite = graphics.getComposite();
		Stroke previousStroke = graphics.getStroke();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		graphics.setColor(new Color(255, 152, 31, (int) (35 * pulse)));
		graphics.fillRoundRect(b.x - 3, b.y - 3, b.width + 6, b.height + 6, 14, 14);
		graphics.setColor(new Color(255, 152, 31, (int) (90 + 150 * pulse)));
		graphics.setStroke(new BasicStroke(2.5f));
		graphics.drawRoundRect(b.x - 3, b.y - 3, b.width + 6, b.height + 6, 14, 14);

		long age = now - newArrivedAt;
		if (age < HINT_MS)
		{
			float alpha = age < HINT_MS - HINT_FADE_MS ? 1f : (HINT_MS - age) / (float) HINT_FADE_MS;
			graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
			int count = newIds.size();
			String text = count == 1 ? "1 new task!" : count + " new tasks!";
			graphics.setFont(FontManager.getRunescapeBoldFont());
			FontMetrics fm = graphics.getFontMetrics();

			// Arrow pointing at the orb, gently bobbing towards it.
			int tipX = b.x - 6 + (int) Math.round(3 * Math.sin(now / 150.0));
			int cy = b.y + b.height / 2;
			graphics.setStroke(new BasicStroke(1f));
			graphics.setColor(TITLE);
			graphics.fillPolygon(new Polygon(new int[]{tipX, tipX - 11, tipX - 11}, new int[]{cy, cy - 7, cy + 7}, 3));
			graphics.fillRect(tipX - 19, cy - 2, 9, 5);

			int boxWidth = fm.stringWidth(text) + 12;
			int boxHeight = fm.getHeight() + 4;
			int boxX = tipX - 19 - boxWidth;
			int boxY = cy - boxHeight / 2;
			graphics.setColor(MENU_BACKGROUND);
			graphics.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);
			graphics.setColor(TITLE);
			graphics.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 8, 8);
			graphics.setColor(Color.WHITE);
			graphics.drawString(text, boxX + 6, boxY + (boxHeight + fm.getAscent()) / 2 - 2);
		}
		graphics.setComposite(previousComposite);
		graphics.setStroke(previousStroke);
	}

	// --- Header controls -------------------------------------------------------

	/** "Current chunk" checkbox; or, showing a chunk picked on the map, its name with an x. */
	private void drawChunkToggle(Graphics2D graphics, Rectangle area, String label, int mx, int my)
	{
		boolean hover = area.contains(mx, my);
		hits.add(0, new Hit(area, () ->
		{
			if (pinnedChunk != null)
			{
				pinnedChunk = null;
			}
			else
			{
				currentChunkOnly = !currentChunkOnly;
			}
			refresh();
		}));
		FontMetrics fm = graphics.getFontMetrics();
		int textY = area.y + (area.height + fm.getAscent()) / 2 - 1;
		if (pinnedChunk != null)
		{
			graphics.setColor(TITLE);
			graphics.drawString(label, area.x + 2, textY);
			cross(graphics, area.x + area.width - 9, area.y + area.height / 2, 3, hover ? NO_LEVEL : SUBTEXT);
			return;
		}
		checkbox(graphics, area.x + 2, area.y + (area.height - 10) / 2, currentChunkOnly, hover);
		graphics.setColor(currentChunkOnly || hover ? Color.WHITE : SUBTEXT);
		graphics.drawString(label, area.x + 17, textY);
	}

	private void drawSearch(Graphics2D graphics, Rectangle box, int mx, int my)
	{
		hits.add(0, new Hit(box, this::startSearch));
		Rectangle clear = new Rectangle(box.x + box.width - 16, box.y + 2, 14, box.height - 4);
		if (!search.isEmpty())
		{
			hits.add(0, new Hit(clear, () ->
			{
				search = "";
				stopSearch();
				refresh();
			}));
		}
		fill(graphics, box, DARK);
		graphics.setColor(searchFocused || box.contains(mx, my) ? TITLE : BORDER);
		graphics.drawRect(box.x, box.y, box.width, box.height);

		FontMetrics fm = graphics.getFontMetrics();
		int textY = box.y + (box.height + fm.getAscent()) / 2 - 1;
		if (search.isEmpty() && !searchFocused)
		{
			graphics.setColor(STAR_OFF);
			graphics.drawString("Search tasks...", box.x + 5, textY);
			return;
		}
		String shown = search;
		while (!shown.isEmpty() && fm.stringWidth(shown) > box.width - 26)
		{
			shown = shown.substring(1);
		}
		graphics.setColor(Color.WHITE);
		graphics.drawString(shown, box.x + 5, textY);
		if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0)
		{
			int caretX = box.x + 6 + fm.stringWidth(shown);
			graphics.drawLine(caretX, box.y + 4, caretX, box.y + box.height - 4);
		}
		if (!search.isEmpty())
		{
			cross(graphics, clear.x + 7, clear.y + clear.height / 2, 3, clear.contains(mx, my) ? NO_LEVEL : SUBTEXT);
		}
	}

	private void drawTab(Graphics2D graphics, Rectangle area, String label, Tab which, int mx, int my)
	{
		hits.add(new Hit(area, () ->
		{
			tab = which;
			refresh();
		}));
		fill(graphics, area, tab == which ? TAB_ON : TAB_OFF);
		if (tab == which)
		{
			fill(graphics, new Rectangle(area.x, area.y + area.height - 2, area.width, 2), TITLE);
		}
		graphics.setColor(tab == which || area.contains(mx, my) ? Color.WHITE : SUBTEXT);
		drawCentered(graphics, label, area);
	}

	/** A button; a null action makes it inert. */
	private void drawButton(Graphics2D graphics, Rectangle area, String label, boolean active, int mx, int my,
		Runnable action)
	{
		if (action != null)
		{
			hits.add(new Hit(area, action));
		}
		fill(graphics, area, active || area.contains(mx, my) ? TAB_ON : TAB_OFF);
		graphics.setColor(active ? Color.WHITE : SUBTEXT);
		FontMetrics fm = graphics.getFontMetrics();
		graphics.drawString(fit(fm, label, area.width - 18), area.x + 6, area.y + (area.height + fm.getAscent()) / 2 - 2);
	}

	// --- Dropdowns -------------------------------------------------------------

	/** Draws a dropdown's box, which then swallows clicks between its buttons. */
	private Rectangle openMenu(Graphics2D graphics, Rectangle button, int boxWidth, int boxHeight)
	{
		Rectangle box = new Rectangle(button.x + button.width - boxWidth, button.y + button.height + 2, boxWidth, boxHeight);
		fill(graphics, box, MENU_BACKGROUND);
		graphics.setColor(BORDER);
		graphics.drawRect(box.x, box.y, box.width, box.height);
		menuBox = box;
		return box;
	}

	/** Sort dropdown: each field with up and down arrows; clicking the name uses its usual direction. */
	private void drawSortMenu(Graphics2D graphics, Rectangle button, int mx, int my)
	{
		SortField[] fields = SortField.values();
		Rectangle box = openMenu(graphics, button, button.width, fields.length * MENU_ROW + 4);
		for (int i = 0; i < fields.length; i++)
		{
			SortField field = fields[i];
			Rectangle row = new Rectangle(box.x + 2, box.y + 2 + i * MENU_ROW, box.width - 4, MENU_ROW);
			Rectangle down = new Rectangle(row.x + row.width - 20, row.y, 18, MENU_ROW);
			Rectangle up = new Rectangle(down.x - 20, row.y, 18, MENU_ROW);
			menuHits.add(new Hit(up, () -> chooseSort(field, true)));
			menuHits.add(new Hit(down, () -> chooseSort(field, false)));
			menuHits.add(new Hit(row, () -> chooseSort(field, field.ascendingByDefault)));
			boolean selected = field == sortField;
			menuRow(graphics, row, field.label, selected, mx, my);
			arrow(graphics, up.x + 9, up.y + MENU_ROW / 2, UP,
				selected && ascending ? TITLE : up.contains(mx, my) ? Color.WHITE : STAR_OFF);
			arrow(graphics, down.x + 9, down.y + MENU_ROW / 2, DOWN,
				selected && !ascending ? TITLE : down.contains(mx, my) ? Color.WHITE : STAR_OFF);
		}
	}

	private void chooseSort(SortField field, boolean asc)
	{
		sortField = field;
		ascending = asc;
		menu = Menu.NONE;
		refresh();
	}

	/**
	 * Filter dropdown: Skill and Tier pickers, task types, then conditions. Each row's
	 * box shows, its eye hides. It stays open while you pick; click outside to close.
	 */
	private void drawFilterMenu(Graphics2D graphics, Rectangle button, int mx, int my)
	{
		Filter[] filters = Filter.values();
		Rectangle box = openMenu(graphics, button, Math.max(button.width, 200), (filters.length + 5) * MENU_ROW + 10);
		int rowY = box.y + 2;
		drawPickerRow(graphics, new Rectangle(box.x + 2, rowY, box.width - 4, MENU_ROW), "Skill",
			filterSkill == null ? null : filterSkill.getName(), mx, my, Menu.SKILLS);
		rowY += MENU_ROW;
		drawPickerRow(graphics, new Rectangle(box.x + 2, rowY, box.width - 4, MENU_ROW), "Tier",
			filterTier == null ? null : filterTier.getDisplayName(), mx, my, Menu.TIERS);
		rowY += MENU_ROW;

		Set<Filter> on = filterOn;
		Set<Filter> hidden = filterHidden;
		for (Filter f : filters)
		{
			if (f.ordinal() == 0 || f.condition != filters[f.ordinal() - 1].condition)
			{
				rowY = menuHeading(graphics, box, rowY, f.condition ? "Only if (all ticked)" : "Type (any ticked)");
			}
			Rectangle row = new Rectangle(box.x + 2, rowY, box.width - 4 - HIDE_BUTTON, MENU_ROW);
			Rectangle eye = new Rectangle(row.x + row.width, rowY, HIDE_BUTTON, MENU_ROW);
			menuHits.add(new Hit(eye, () -> toggleFilter(f, true)));
			menuHits.add(new Hit(row, () -> toggleFilter(f, false)));
			boolean isHidden = hidden.contains(f);
			drawCheckRow(graphics, row, f.label, on.contains(f), isHidden ? STAR_OFF : null, mx, my, true);
			fill(graphics, eye, eye.contains(mx, my) ? ROW_HOVER : null);
			drawHiddenEye(graphics, eye.x + eye.width / 2, rowY + MENU_ROW / 2,
				isHidden ? NO_LEVEL : eye.contains(mx, my) ? Color.WHITE : STAR_OFF);
			rowY += MENU_ROW;
		}

		rowY = menuHeading(graphics, box, rowY, null);
		Rectangle clear = new Rectangle(box.x + 2, rowY, box.width - 4, MENU_ROW);
		boolean any = anyFilter();
		if (any)
		{
			menuHits.add(new Hit(clear, () ->
			{
				clearFilters();
				refresh();
			}));
		}
		drawCheckRow(graphics, clear, "Clear all filters", false, any ? null : STAR_OFF, any ? mx : -1, my, false);
	}

	/** A divider line with an optional small heading; returns where the next row starts. */
	private int menuHeading(Graphics2D graphics, Rectangle box, int rowY, String text)
	{
		graphics.setColor(BORDER);
		graphics.drawLine(box.x + 6, rowY + 2, box.x + box.width - 6, rowY + 2);
		if (text == null)
		{
			return rowY + 5;
		}
		graphics.setColor(STAR_OFF);
		graphics.drawString(text, box.x + 8, rowY + MENU_ROW - 4);
		return rowY + MENU_ROW;
	}

	/** "Skill        Mining  >": opens a picker; the value is orange while it's filtering. */
	private void drawPickerRow(Graphics2D graphics, Rectangle row, String label, String value, int mx, int my,
		Menu picker)
	{
		menuHits.add(new Hit(row, () -> menu = picker));
		menuRow(graphics, row, label, false, mx, my);
		FontMetrics fm = graphics.getFontMetrics();
		String shown = value == null ? "Any" : value;
		graphics.setColor(value != null ? TITLE : SUBTEXT);
		graphics.drawString(shown, row.x + row.width - 20 - fm.stringWidth(shown), row.y + (MENU_ROW + fm.getAscent()) / 2 - 2);
		arrow(graphics, row.x + row.width - 10, row.y + MENU_ROW / 2, RIGHT, SUBTEXT);
	}

	/** Skill icon grid. Picking the current skill clears it; either way, back to the filter menu. */
	private void drawSkillMenu(Graphics2D graphics, Rectangle button, int mx, int my)
	{
		List<Skill> skills = new ArrayList<>(Arrays.asList(Skill.values()));
		skills.remove(Skill.OVERALL);
		int rowsNeeded = (skills.size() + SKILL_COLUMNS - 1) / SKILL_COLUMNS;
		Rectangle box = openMenu(graphics, button, SKILL_COLUMNS * SKILL_CELL + 8, rowsNeeded * SKILL_CELL + 26);
		String caption = filterSkill == null ? "Pick a skill" : "Click " + filterSkill.getName() + " again to clear";
		for (int i = 0; i < skills.size(); i++)
		{
			Skill skill = skills.get(i);
			Rectangle cell = new Rectangle(box.x + 4 + (i % SKILL_COLUMNS) * SKILL_CELL,
				box.y + 4 + (i / SKILL_COLUMNS) * SKILL_CELL, SKILL_CELL, SKILL_CELL);
			menuHits.add(new Hit(cell, () ->
			{
				filterSkill = skill == filterSkill ? null : skill;
				menu = Menu.FILTER;
				refresh();
			}));
			boolean hover = cell.contains(mx, my);
			fill(graphics, cell, hover ? ROW_HOVER : skill == filterSkill ? TAB_ON : null);
			caption = hover ? skill.getName() : caption;
			BufferedImage icon = skillIcons.getSkillImage(skill, true);
			if (icon != null)
			{
				graphics.drawImage(icon, cell.x + (cell.width - icon.getWidth()) / 2,
					cell.y + (cell.height - icon.getHeight()) / 2, null);
			}
		}
		graphics.setColor(SUBTEXT);
		drawCentered(graphics, caption, new Rectangle(box.x, box.y + box.height - 20, box.width, 18));
	}

	/** Tier picker, Easy to Master with card colours. Picking the current tier clears it. */
	private void drawTierMenu(Graphics2D graphics, Rectangle button, int mx, int my)
	{
		TaskCardTier[] tiers = TaskCardTier.values();
		Rectangle box = openMenu(graphics, button, Math.max(button.width, 140), tiers.length * MENU_ROW + 4);
		for (int i = 0; i < tiers.length; i++)
		{
			TaskCardTier tier = tiers[i];
			Rectangle row = new Rectangle(box.x + 2, box.y + 2 + i * MENU_ROW, box.width - 4, MENU_ROW);
			menuHits.add(new Hit(row, () ->
			{
				filterTier = tier == filterTier ? null : tier;
				menu = Menu.FILTER;
				refresh();
			}));
			fill(graphics, row, row.contains(mx, my) ? ROW_HOVER : tier == filterTier ? TAB_ON : null);
			fill(graphics, new Rectangle(row.x + 6, row.y + (MENU_ROW - 10) / 2, 10, 10), tier.getAccent());
			int points = tier.ordinal() + 1;
			graphics.setColor(tier == filterTier ? Color.WHITE : SUBTEXT);
			graphics.drawString(tier.getDisplayName() + " (" + points + (points == 1 ? " pt)" : " pts)"),
				row.x + 22, row.y + (MENU_ROW + graphics.getFontMetrics().getAscent()) / 2 - 2);
		}
	}

	/** A dropdown row: hover fill and its label (white when selected or hovered). */
	private void menuRow(Graphics2D graphics, Rectangle row, String label, boolean selected, int mx, int my)
	{
		drawCheckRow(graphics, row, label, selected, null, mx, my, false);
	}

	/**
	 * A row with a hover fill, its label ({@code labelColor} overrides the usual white or
	 * grey) and, if {@code box}, a checkbox on the right showing {@code on}.
	 */
	private void drawCheckRow(Graphics2D graphics, Rectangle row, String label, boolean on, Color labelColor,
		int mx, int my, boolean box)
	{
		boolean hover = row.contains(mx, my);
		fill(graphics, row, hover ? ROW_HOVER : null);
		FontMetrics fm = graphics.getFontMetrics();
		graphics.setColor(labelColor != null ? labelColor : on || hover ? Color.WHITE : SUBTEXT);
		graphics.drawString(label, row.x + 6, row.y + (row.height + fm.getAscent()) / 2 - 2);
		if (box)
		{
			checkbox(graphics, row.x + row.width - 18, row.y + (row.height - 10) / 2, on, hover);
		}
	}

	// --- List rows ---------------------------------------------------------------

	/** Header for one skill's level-up tasks; click to show or hide its rungs. */
	private void drawGroupRow(Graphics2D graphics, Entry entry, Rectangle row, Rectangle list, int mx, int my,
		Font regular, Font small)
	{
		boolean hover = list.contains(mx, my) && row.contains(mx, my);
		if (list.contains(mx, my))
		{
			hits.add(new Hit(row.intersection(list), () ->
			{
				if (!expandedGroups.remove(entry.group))
				{
					expandedGroups.add(entry.group);
				}
				rowsBuiltAt = 0;
			}));
		}
		arrow(graphics, row.x + 9, row.y + ROW / 2, expandedGroups.contains(entry.group) ? DOWN : RIGHT,
			hover ? Color.WHITE : SUBTEXT);
		try
		{
			BufferedImage icon = skillIcons.getSkillImage(Skill.valueOf(entry.group), true);
			if (icon != null)
			{
				graphics.drawImage(icon, row.x + 18 + (18 - icon.getWidth()) / 2, row.y + (ROW - icon.getHeight()) / 2, null);
			}
		}
		catch (IllegalArgumentException ignored)
		{
			// Not a skill we know: no icon.
		}
		int points = entry.members.stream().mapToInt(NuzlockeTask::getBasePoints).sum();
		int left = entry.members.size();
		String name = entry.group.charAt(0) + entry.group.substring(1).toLowerCase() + " levels";
		String info = left + (left == 1 ? " level-up left" : " level-ups left") + " - next: level "
			+ requiredLevel(entry.members.get(0));
		drawRowText(graphics, row, row.x + 42, name, Color.WHITE, points + " pts", info, regular, small);
	}

	/** Line 1: name (left) and points (right). Line 2: the info text. */
	private void drawRowText(Graphics2D graphics, Rectangle row, int textX, String name, Color nameColor, String points,
		String info, Font regular, Font small)
	{
		int rightEdge = row.x + row.width - 8;
		graphics.setFont(regular);
		FontMetrics fm = graphics.getFontMetrics();
		int pointsWidth = fm.stringWidth(points);
		graphics.setColor(POINTS);
		graphics.drawString(points, rightEdge - pointsWidth, row.y + 16);
		graphics.setColor(nameColor);
		graphics.drawString(fit(fm, name, rightEdge - pointsWidth - 10 - textX), textX, row.y + 16);
		graphics.setFont(small);
		if (info != null)
		{
			graphics.setColor(SUBTEXT);
			graphics.drawString(info, textX, row.y + 30);
		}
	}

	private void drawRow(Graphics2D graphics, NuzlockeTask task, Rectangle row, List<QuestRequirements.Line> detail,
		Set<String> saved, Rectangle list, int mx, int my, Font regular, Font small)
	{
		boolean inList = list.contains(mx, my);
		String taskId = task.getTaskId();
		NuzlockeTask tracked = plugin.getSelectedTask();
		boolean isTracked = tracked != null && taskId.equals(tracked.getTaskId());
		if (isTracked)
		{
			// The task shown in the task box: warm fill, orange edge bar and outline.
			fill(graphics, row, TRACKED_FILL);
			fill(graphics, new Rectangle(row.x, row.y, 3, row.height), TITLE);
			graphics.drawRect(row.x, row.y, row.width - 1, row.height - 1);
		}

		// Expand arrow (a quest's opens its pane), star and archive book; added first so they win the click.
		int iconY = row.y + (ROW - 18) / 2;
		Rectangle arrowArea = new Rectangle(row.x + 2, iconY, 14, 18);
		Rectangle star = new Rectangle(row.x + 18, iconY, 18, 18);
		Rectangle book = new Rectangle(row.x + 38, iconY, 14, 18);
		boolean expandable = !requirementsFor(task).isEmpty() || QuestRequirements.isQuestTask(task);
		if (inList)
		{
			if (expandable)
			{
				hits.add(new Hit(arrowArea.intersection(list), () ->
				{
					if (QuestRequirements.isQuestTask(task))
					{
						openQuestPane(task);
					}
					else if (!expanded.remove(taskId))
					{
						expanded.add(taskId);
					}
				}));
			}
			hits.add(new Hit(star.intersection(list), () -> toggleSaved(taskId)));
			hits.add(new Hit(book.intersection(list), () -> toggleArchived(task)));
		}
		int textX = book.x + book.width + 6;
		drawDetail(graphics, detail, textX, row.y + ROW - 1, list, mx, my, small);
		if (inList)
		{
			// A quest opens in the pane; clicking the tracked task stops tracking it; any other row tracks it.
			hits.add(new Hit(row.intersection(list), () ->
			{
				if (QuestRequirements.isQuestTask(task))
				{
					openQuestPane(task);
				}
				else if (isTracked)
				{
					plugin.clearSelectedTask();
				}
				else
				{
					plugin.selectTaskFromGame(task);
				}
			}));
		}
		if (expandable)
		{
			arrow(graphics, arrowArea.x + 7, arrowArea.y + 9, expanded.contains(taskId) ? DOWN : RIGHT,
				inList && arrowArea.contains(mx, my) ? Color.WHITE : SUBTEXT);
		}
		boolean isSaved = saved.contains(taskId);
		drawStar(graphics, star.x + 9, star.y + 9, 8, isSaved || (inList && star.contains(mx, my)) ? STAR_ON : STAR_OFF,
			isSaved);
		boolean isArchived = archive.isArchived(task);
		TaskArchive.drawBook(graphics, book.x + 2, book.y + 3, 10, 12,
			isArchived || (inList && book.contains(mx, my)) ? BOOK_ON : STAR_OFF, isArchived);

		int rightEdge = row.x + row.width - 8;
		boolean canDo = canDo(task);
		String name = (task.getName() == null ? taskId : task.getName()) + (canDo ? "" : " " + levelNote(task));
		drawRowText(graphics, row, textX, name, canDo ? Color.WHITE : NO_LEVEL,
			task.getBasePoints() + (task.getBasePoints() == 1 ? " pt" : " pts"), null, regular, small);

		// Line 2: category and chunk, then progress. One-off tasks (target 1) leave the bar's
		// space blank, since a "0/1" bar says nothing.
		FontMetrics sm = graphics.getFontMetrics();
		int target = Math.max(1, task.getTargetQuantity());
		String chunk = chunkNames.getOrDefault(taskId, "");
		String info = NuzlockeTask.displayCategory(task.getCategory()) + (chunk.isEmpty() ? "" : " - " + chunk);
		int progressStart = rightEdge - ONE_OFF_BLANK;
		if (target > 1)
		{
			String progress = Math.min(task.getCurrentProgress(), target) + "/" + target;
			int progressWidth = sm.stringWidth(progress);
			graphics.setColor(SUBTEXT);
			graphics.drawString(progress, rightEdge - progressWidth, row.y + 30);
			progressStart = rightEdge - progressWidth - 76;
			fill(graphics, new Rectangle(progressStart, row.y + 24, 70, 6), DARK);
			fill(graphics, new Rectangle(progressStart, row.y + 24, (int) Math.round(70 * fraction(task)), 6), BAR_FILL);
		}
		graphics.setColor(SUBTEXT);
		graphics.drawString(fit(sm, info, progressStart - 10 - textX), textX, row.y + 30);
	}

	/**
	 * Detail lines from (x, y). Clickable ones get a hit: a section header opens/closes, a
	 * chunk shows on the map, a quest opens in the pane.
	 */
	private void drawDetail(Graphics2D graphics, List<QuestRequirements.Line> detail, int x, int y, Rectangle clip,
		int mx, int my, Font small)
	{
		graphics.setFont(small);
		FontMetrics sm = graphics.getFontMetrics();
		boolean in = clip.contains(mx, my);
		for (int i = 0; i < detail.size(); i++)
		{
			QuestRequirements.Line line = detail.get(i);
			Rectangle area = detailLineArea(sm, detail, x, y, i);
			if (line.clickable() && in)
			{
				hits.add(new Hit(area.intersection(clip), line.region > 0 ? () -> showChunkOnMap(line.region, line.text.trim())
					: line.quest != null ? () -> showQuest(line.quest, line.text.replace(" (checking)", ""))
					: () -> quests.toggleSection(line.toggle)));
			}
			int lineY = area.y + 1 + sm.getAscent() - 2;
			boolean hover = line.clickable() && in && area.contains(mx, my);
			if (line.header)
			{
				arrow(graphics, x + 3, lineY - sm.getAscent() / 2, line.open ? DOWN : RIGHT, hover ? Color.WHITE : SUBTEXT);
			}
			int lineX = x + lineIndent(line);
			graphics.setColor(line.color);
			graphics.drawString(line.text, lineX, lineY);
			if (hover)
			{
				graphics.drawLine(lineX, lineY + 2, lineX + sm.stringWidth(line.text), lineY + 2);
			}
		}
	}

	/** A task's detail lines, wrapped to {@code width}. */
	private List<QuestRequirements.Line> wrapDetail(FontMetrics sm, NuzlockeTask task, int width)
	{
		List<QuestRequirements.Line> wrapped = new ArrayList<>();
		for (QuestRequirements.Line line : detailLines(task))
		{
			boolean first = true;
			for (String part : wrap(sm, line.text, width - lineIndent(line)))
			{
				wrapped.add(line.withText(part, first));
				first = false;
			}
		}
		return wrapped;
	}

	/** Where detail line {@code index} is: from x to the end of its text. */
	private static Rectangle detailLineArea(FontMetrics fm, List<QuestRequirements.Line> detail, int x, int top, int index)
	{
		int y = top;

		for (int i = 0; i <= index; i++)
		{
			y += detail.get(i).gap + (i < index ? DETAIL_LINE : 0);
		}
		QuestRequirements.Line line = detail.get(index);
		return new Rectangle(x, y, lineIndent(line) + fm.stringWidth(line.text), DETAIL_LINE);
	}

	private static int lineIndent(QuestRequirements.Line line)
	{
		return line.header ? SECTION_TEXT : line.indent;
	}

	private static int detailHeight(List<QuestRequirements.Line> lines)
	{
		return lines.isEmpty() ? 0 : 4 + lines.stream().mapToInt(l -> DETAIL_LINE + l.gap).sum();
	}

	/** A prerequisite quest was clicked: open it in the pane, or say in chat that it isn't a task right now. */
	private void showQuest(String questName, String display)
	{
		NuzlockeTask found = pool(true).stream()
			.filter(t -> QuestRequirements.isQuestTask(t) && t.getConstraints() != null
				&& questName.equals(t.getConstraints().getQuest()))
			.findFirst().orElse(null);
		if (found == null)
		{
			chat(display + " isn't in your task list (it's done, or not a ChunkBlazer quest).");
			return;
		}
		openQuestPane(found);
	}

	/**
	 * Show a quest's requirements over the side panel (inventory, prayer...), not over its
	 * tab buttons, and close the window so the map can be seen. It has its own close
	 * button, and switching tab or pressing any key closes it too.
	 */
	void openQuestPane(NuzlockeTask task)
	{
		close();
		if (questPane == null)
		{
			questBack.clear();
		}
		else if (questPane != task)
		{
			questBack.push(questPane);
		}
		questPane = task;
		questPaneAt = System.currentTimeMillis();
		scroll = 0;
	}

	private void drawQuestPane(Graphics2D graphics)
	{
		NuzlockeTask task = questPane;
		Rectangle pane = sidePanel();
		int mx = paneMouse.x;
		int my = paneMouse.y;
		hits.clear();
		menuHits.clear();
		menuBox = null;
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		fill(graphics, pane, BACKGROUND);
		graphics.setColor(BORDER);
		graphics.drawRect(pane.x, pane.y, pane.width - 1, pane.height - 1);
		Rectangle close = new Rectangle(pane.x + pane.width - 20, pane.y + 6, 14, 14);
		hits.add(new Hit(close, () -> questPane = null));
		cross(graphics, close.x + 7, close.y + 7, 5, close.contains(mx, my) ? NO_LEVEL : SUBTEXT);
		// The quest's OSRS Wiki page, beside the close button.
		boolean savedList = task == SAVED_PANE;
		List<NuzlockeTask> saved = savedList ? savedTasks() : null;
		graphics.setFont(FontManager.getRunescapeSmallFont());
		Rectangle wiki = new Rectangle(close.x - 28, close.y, 24, 14);
		if (!savedList)
		{
			// The wiki's search jumps straight to the page when the name matches, and
			// lists the closest pages when it doesn't (the Recipe for Disaster parts).
			String quest = task.getName().replaceFirst("^Complete ", "").replace("&", "%26").replace(' ', '+');
			hits.add(new Hit(wiki, () -> LinkBrowser.browse("https://oldschool.runescape.wiki/?title=Special:Search&go=Go&search=" + quest)));
			graphics.setColor(wiki.contains(mx, my) ? Color.WHITE : SUBTEXT);
			graphics.drawString("Wiki", wiki.x + 2, wiki.y + 11);
		}
		// Back to the quest this one was opened from.
		Rectangle back = new Rectangle(wiki.x - 30, close.y, 28, 14);
		if (!questBack.isEmpty())
		{
			hits.add(new Hit(back, () ->
			{
				questPane = questBack.pop();
				questPaneAt = System.currentTimeMillis();
				scroll = 0;
			}));
			graphics.setColor(back.contains(mx, my) ? Color.WHITE : SUBTEXT);
			graphics.drawString("Back", back.x + 2, back.y + 11);
		}
		// A quick fade and slide in, so moving to a required quest reads as a new page.
		float in = Math.min(1f, (System.currentTimeMillis() - questPaneAt) / 200f);
		Composite oldComposite = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, in));
		graphics.translate((int) (16 * (1 - in) * (1 - in)), 0);
		// The whole name, wrapped, without the "Complete" every quest task starts with.
		graphics.setFont(FontManager.getRunescapeBoldFont());
		graphics.setColor(TITLE);
		List<String> title = wrap(graphics.getFontMetrics(), savedList ? "Saved tasks (" + saved.size() + ")"
			: task.getName().replaceFirst("^Complete ", ""), pane.width - (questBack.isEmpty() ? 56 : 86));
		for (int i = 0; i < title.size(); i++)
		{
			graphics.drawString(title.get(i), pane.x + 6, pane.y + 18 + i * 15);
		}

		Font small = FontManager.getRunescapeSmallFont();
		int top = pane.y + 9 + title.size() * 15;
		Rectangle body = new Rectangle(pane.x + 1, top, pane.width - 2, pane.y + pane.height - 1 - top);
		Shape oldClip = graphics.getClip();
		graphics.clip(body);
		if (savedList)
		{
			drawSavedList(graphics, saved, body, mx, my, small);
		}
		else
		{
			List<QuestRequirements.Line> lines = wrapDetail(graphics.getFontMetrics(small), task, pane.width - 12);
			scroll = Math.max(0, Math.min(scroll, detailHeight(lines) - body.height));
			drawDetail(graphics, lines, pane.x + 6, body.y - scroll, body, mx, my, small);
		}
		graphics.setClip(oldClip);
		graphics.translate(-(int) (16 * (1 - in) * (1 - in)), 0);
		graphics.setComposite(oldComposite);
		finishHover(pane, mx, my);
	}

	/**
	 * The saved list in the pane: name (red if your level's too low), then its chunk or
	 * category and progress. Click to track or untrack (a quest opens its requirements),
	 * Shift + click to unsave.
	 */
	private void drawSavedList(Graphics2D graphics, List<NuzlockeTask> saved, Rectangle body, int mx, int my, Font small)
	{
		graphics.setFont(small);
		FontMetrics fm = graphics.getFontMetrics();
		if (saved.isEmpty())
		{
			graphics.setColor(SUBTEXT);
			graphics.drawString("Star tasks in the task window", body.x + 5, body.y + 14);
			return;
		}
		scroll = Math.max(0, Math.min(scroll, saved.size() * 30 - body.height));
		NuzlockeTask tracked = plugin.getSelectedTask();
		boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		for (int i = 0; i < saved.size(); i++)
		{
			NuzlockeTask task = saved.get(i);
			Rectangle row = new Rectangle(body.x, body.y + i * 30 - scroll, body.width, 30);
			boolean hover = row.contains(mx, my) && body.contains(mx, my);
			boolean isTracked = tracked != null && task.getTaskId().equals(tracked.getTaskId());
			if (hover || isTracked)
			{
				fill(graphics, row, hover && shift ? new Color(255, 70, 70, 50) : hover ? ROW_HOVER : TRACKED_FILL);
			}
			if (body.contains(mx, my))
			{
				hits.add(new Hit(row, () ->
				{
					if (client.isKeyPressed(KeyCode.KC_SHIFT))
					{
						toggleSaved(task.getTaskId());
					}
					else if (QuestRequirements.isQuestTask(task))
					{
						openQuestPane(task);
					}
					else if (isTracked)
					{
						plugin.clearSelectedTask();
					}
					else
					{
						plugin.selectTaskFromGame(task);
					}
				}));
			}
			int target = Math.max(1, task.getTargetQuantity());
			String progress = target > 1 ? Math.min(task.getCurrentProgress(), target) + "/" + target : "";
			String where = plugin.isGlobalTask(task.getTaskId()) ? NuzlockeTask.displayCategory(task.getCategory())
				: chunkNames.computeIfAbsent(task.getTaskId(), k -> chunkName(task));
			graphics.setColor(hover && shift ? NO_LEVEL : plugin.meetsLevelRequirement(task) ? Color.WHITE : NO_LEVEL);
			graphics.drawString(fit(fm, hover && shift ? "Remove " + task.getName() : task.getName(), row.width - 12),
				row.x + 5, row.y + 12);
			graphics.setColor(SUBTEXT);
			graphics.drawString(fit(fm, where, row.width - 16 - fm.stringWidth(progress)), row.x + 5, row.y + 25);
			graphics.drawString(progress, row.x + row.width - 5 - fm.stringWidth(progress), row.y + 25);
		}
	}

	/** The side panel's area in whichever layout is on; a spot by the right edge if it's closed. */
	private Rectangle sidePanel()
	{
		for (int id : new int[]{InterfaceID.Toplevel.SIDE_PANELS, InterfaceID.ToplevelOsrsStretch.SIDE_PANELS,
			InterfaceID.ToplevelPreEoc.SIDE_PANELS, InterfaceID.ToplevelOsm.SIDE_PANELS})
		{
			Widget panel = client.getWidget(id);
			if (panel != null && !panel.isHidden())
			{
				return panel.getBounds();
			}
		}
		return new Rectangle(client.getCanvasWidth() - 214, client.getCanvasHeight() - 340, 204, 275);
	}

	/** Switching side tab (prayer, inventory...) closes the quest pane. */
	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() == VarClientID.TOPLEVEL_PANEL)
		{
			questPane = null;
		}
	}

	/** Jump the world map to a chunk and outline it; if the map is closed, say to open it. */
	private void showChunkOnMap(int region, String name)
	{
		worldMap.focusRegion(region);
		clientThread.invoke(() ->
		{
			if (!worldMap.isMapOpen())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Open the world map to see " + name + ".", null);
			}
		});
	}

	private void chat(String message)
	{
		clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
	}

	// --- Small drawing helpers ------------------------------------------------

	/** Fills the area; a null colour draws nothing. */
	private static void fill(Graphics2D graphics, Rectangle area, Color color)
	{
		if (color != null)
		{
			graphics.setColor(color);
			graphics.fillRect(area.x, area.y, area.width, area.height);
		}
	}

	private static void checkbox(Graphics2D graphics, int x, int y, boolean on, boolean hover)
	{
		fill(graphics, new Rectangle(x, y, 10, 10), DARK);
		graphics.setColor(on || hover ? TITLE : BORDER);
		graphics.drawRect(x, y, 10, 10);
		if (on)
		{
			graphics.fillRect(x + 3, y + 3, 5, 5);
		}
	}

	private static void cross(Graphics2D graphics, int cx, int cy, int r, Color color)
	{
		graphics.setColor(color);
		graphics.drawLine(cx - r, cy - r, cx + r, cy + r);
		graphics.drawLine(cx - r, cy + r, cx + r, cy - r);
	}

	/** A small triangle centred on (cx, cy), pointing UP, DOWN or RIGHT. */
	private static void arrow(Graphics2D graphics, int cx, int cy, int direction, Color color)
	{
		graphics.setColor(color);
		graphics.fillPolygon(direction == RIGHT
			? new Polygon(new int[]{cx - 2, cx - 2, cx + 3}, new int[]{cy - 4, cy + 4, cy}, 3)
			: direction == UP
			? new Polygon(new int[]{cx - 4, cx + 4, cx}, new int[]{cy + 2, cy + 2, cy - 3}, 3)
			: new Polygon(new int[]{cx - 4, cx + 4, cx}, new int[]{cy - 2, cy - 2, cy + 3}, 3));
	}

	private void drawScrollbar(Graphics2D graphics, Rectangle area, int contentHeight)
	{
		if (contentHeight <= area.height)
		{
			return;
		}
		int thumbHeight = Math.max(20, area.height * area.height / contentHeight);
		int thumbY = area.y + (area.height - thumbHeight) * scroll / Math.max(1, contentHeight - area.height);
		fill(graphics, new Rectangle(area.x + area.width - 5, area.y, 4, area.height), TAB_OFF);
		fill(graphics, new Rectangle(area.x + area.width - 5, thumbY, 4, thumbHeight), BORDER);
	}

	/** The "hide this" button: an eye with a line through it. */
	private static void drawHiddenEye(Graphics2D graphics, int cx, int cy, Color color)
	{
		Stroke previous = graphics.getStroke();
		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(1.4f));
		graphics.drawArc(cx - 6, cy - 4, 12, 9, 0, 180);
		graphics.drawArc(cx - 6, cy - 5, 12, 9, 180, 180);
		graphics.fillOval(cx - 2, cy - 2, 4, 4);
		graphics.drawLine(cx - 6, cy + 5, cx + 6, cy - 5);
		graphics.setStroke(previous);
	}

	private static void drawStar(Graphics2D graphics, int cx, int cy, int radius, Color color, boolean filled)
	{
		int[] xs = new int[10];
		int[] ys = new int[10];
		for (int i = 0; i < 10; i++)
		{
			double r = i % 2 == 0 ? radius : radius * 0.45;
			double angle = Math.PI / 2 + i * Math.PI / 5;
			xs[i] = cx + (int) Math.round(r * Math.cos(angle));
			ys[i] = cy - (int) Math.round(r * Math.sin(angle));
		}
		graphics.setColor(color);
		if (filled)
		{
			graphics.fillPolygon(xs, ys, 10);
		}
		else
		{
			graphics.drawPolygon(xs, ys, 10);
		}
	}

	private static void drawCentered(Graphics2D graphics, String text, Rectangle area)
	{
		FontMetrics fm = graphics.getFontMetrics();
		graphics.drawString(text, area.x + (area.width - fm.stringWidth(text)) / 2, area.y + (area.height + fm.getAscent()) / 2 - 2);
	}

	/** Shorten text with "..." so it fits the width. */
	private static String fit(FontMetrics fm, String text, int width)
	{
		if (width <= 0)
		{
			return "";
		}
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end) + "...") > width)
		{
			end--;
		}
		return text.substring(0, end) + "...";
	}

	/** Split text into lines that fit the width, breaking between words. */
	private static List<String> wrap(FontMetrics fm, String text, int width)
	{
		List<String> lines = new ArrayList<>();
		String line = "";
		for (String word : text.split(" "))
		{
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (fm.stringWidth(candidate) > width && !line.isEmpty())
			{
				lines.add(line);
				line = word;
			}
			else
			{
				line = candidate;
			}
		}
		if (!line.isEmpty())
		{
			lines.add(line);
		}
		return lines;
	}
}
