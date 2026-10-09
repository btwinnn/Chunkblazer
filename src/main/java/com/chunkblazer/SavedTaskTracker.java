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
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * World of Warcraft-style tracker for saved (starred) tasks. Sits as a small bar at
 * the bottom of the game view (left of the inventory by default; Alt + drag to move).
 * Clicking the bar opens the list, which grows upwards (scroll it with the mouse
 * wheel when there are more than fit). Each task shows its chunk's name; the order
 * is nearest first, by how
 * many chunks away their chunk is, then tasks with no place (quests, level-ups) by
 * how far along they are. Clicking a task tracks it in the task box (or stops
 * tracking it, if it's the tracked one); Shift + click removes it from the saved
 * list. Archived tasks are left out, like the Saved
 * tab. Registered by TaskBrowserOverlay.
 */
@Singleton
public class SavedTaskTracker extends Overlay
{
	private static final String CONFIG_GROUP = "chunkblazer";
	private static final String SAVED_KEY = "savedTasks";
	private static final String SURFACE_KEY = "lastSurfaceRegion";

	private static final int WIDTH = 210;
	private static final int BAR = 22;
	private static final int ROW = 30;
	private static final int MAX_ROWS = 8;
	private static final int PAD = 6;
	private static final long REFRESH_MS = 1000;

	private static final Color BACKGROUND = new Color(38, 33, 27, 230);
	private static final Color BAR_BACKGROUND = new Color(48, 42, 34, 240);
	private static final Color BORDER = new Color(110, 95, 65);
	private static final Color TITLE = new Color(255, 152, 31);
	private static final Color SUBTEXT = new Color(170, 160, 140);
	private static final Color ROW_HOVER = new Color(255, 255, 255, 28);
	private static final Color TRACKED_FILL = new Color(255, 140, 0, 45);
	private static final Color NO_LEVEL = new Color(255, 90, 90);

	/** One saved task, with how far away it is (-1 when it has no place) and its chunk's name. */
	private static final class Item
	{
		final NuzlockeTask task;
		final int distance;
		final String chunk;

		Item(NuzlockeTask task, int distance, String chunk)
		{
			this.task = task;
			this.distance = distance;
			this.chunk = chunk;
		}
	}

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final ConfigManager configManager;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final TaskArchive archive;

	private volatile boolean expanded;
	private volatile Runnable hoveredAction;
	// The task row under the mouse, for Shift + click (unsave).
	private volatile String hoveredTaskId;
	private volatile boolean mouseOverList;
	private volatile int scroll;
	private List<Item> items = new ArrayList<>();
	private long itemsBuiltAt;
	private int itemsBuiltRegion = -1;
	// The last overworld region the player stood in. Underground areas and instances
	// sit far away on the region grid, so distances are measured from here instead.
	// Saved per account, so logging back in inside a cave still knows where it is.
	private int lastSurfaceRegion = -1;
	private String surfaceForAccount;

	private final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			Runnable action = hoveredAction;
			// Alt + drag moves the overlay; leave those clicks to RuneLite.
			if (action == null || event.isAltDown() || event.getButton() != MouseEvent.BUTTON1)
			{
				return event;
			}
			String taskId = hoveredTaskId;
			if (event.isShiftDown() && taskId != null)
			{
				unsave(taskId);
			}
			else
			{
				action.run();
			}
			event.consume();
			return event;
		}
	};

	private final MouseWheelListener wheel = event ->
	{
		if (expanded && mouseOverList)
		{
			scroll += event.getWheelRotation() * ROW;
			event.consume();
		}
		return event;
	};

	@Inject
	public SavedTaskTracker(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config,
		ConfigManager configManager, OverlayManager overlayManager, MouseManager mouseManager, TaskArchive archive)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.archive = archive;

		setPosition(OverlayPosition.BOTTOM_RIGHT);
		setPriority(OverlayPriority.LOW);
	}

	public void startUp()
	{
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouse);
		mouseManager.registerMouseWheelListener(wheel);
	}

	public void shutDown()
	{
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(mouse);
		mouseManager.unregisterMouseWheelListener(wheel);
		hoveredAction = null;
	}

	// --- Which tasks, in what order -----------------------------------------

	/** Take a task off the saved list (the same list as the task window's Saved tab). */
	private void unsave(String taskId)
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		String raw = configManager.getRSProfileConfiguration(CONFIG_GROUP, SAVED_KEY);
		if (raw == null)
		{
			return;
		}
		List<String> kept = new ArrayList<>();
		for (String id : raw.split(","))
		{
			String trimmed = id.trim();
			if (!trimmed.isEmpty() && !trimmed.equals(taskId))
			{
				kept.add(trimmed);
			}
		}
		configManager.setRSProfileConfiguration(CONFIG_GROUP, SAVED_KEY, String.join(",", kept));
		itemsBuiltAt = 0;
	}

	private List<Item> currentItems()
	{
		long now = System.currentTimeMillis();
		int current = plugin.getCurrentRegionId();
		loadSurfaceRegion();
		if (current > 0 && isSurface(current) && current != lastSurfaceRegion)
		{
			lastSurfaceRegion = current;
			if (configManager.getRSProfileKey() != null)
			{
				configManager.setRSProfileConfiguration(CONFIG_GROUP, SURFACE_KEY, current);
			}
		}
		// Underground: measure from the last place on the surface, so a cave under a
		// chunk doesn't read as miles away. Teleporting onto the surface updates it
		// straight away. With no surface place known at all, show no distances
		// rather than wrong ones.
		int here;
		if (current > 0 && isSurface(current))
		{
			here = current;
		}
		else
		{
			here = lastSurfaceRegion;
		}
		if (now - itemsBuiltAt < REFRESH_MS && here == itemsBuiltRegion)
		{
			return items;
		}
		itemsBuiltAt = now;
		itemsBuiltRegion = here;

		Set<String> saved = plugin.savedTaskIds();
		Set<String> archived = archive.ids();
		Set<String> completed = plugin.getCompletedTaskIdSet();
		Map<String, NuzlockeTask> candidates = new HashMap<>();
		for (NuzlockeTask task : plugin.getActiveTasks())
		{
			if (task != null && task.getTaskId() != null && !task.isCompleted())
			{
				candidates.putIfAbsent(task.getTaskId(), task);
			}
		}
		for (NuzlockeTask task : plugin.getVisibleGlobalTasks())
		{
			if (task != null && task.getTaskId() != null && !completed.contains(task.getTaskId()))
			{
				candidates.putIfAbsent(task.getTaskId(), task);
			}
		}

		List<Item> list = new ArrayList<>();
		for (NuzlockeTask task : candidates.values())
		{
			String id = task.getTaskId();
			if (!saved.contains(id) || archived.contains(id))
			{
				continue;
			}
			int distance = -1;
			if (!plugin.isGlobalTask(id) && here > 0 && isSurface(here))
			{
				int region = plugin.findRegionForTask(id);
				if (region > 0)
				{
					distance = chunksBetween(here, region);
				}
			}
			list.add(new Item(task, distance, chunkName(task)));
		}

		// Nearest first; tasks with no place after, furthest along first.
		list.sort(Comparator
			.comparing((Item i) -> i.distance < 0)
			.thenComparingInt(i -> i.distance < 0 ? 0 : i.distance)
			.thenComparing(Comparator.comparingDouble((Item i) -> fraction(i.task)).reversed())
			.thenComparing(i -> i.task.getName() == null ? "" : i.task.getName()));
		items = list;
		return items;
	}

	/** "Lumbridge (12850)" -> "Lumbridge"; "" for tasks with no chunk (quests, level-ups). */
	private String chunkName(NuzlockeTask task)
	{
		if (plugin.isGlobalTask(task.getTaskId()))
		{
			return "";
		}
		String name = plugin.getTaskRegionName(task);
		return name == null ? "" : name.replaceAll("\\s*\\(\\d+\\)$", "").trim();
	}

	/** Read this account's saved surface region, once per account (switching accounts re-reads it). */
	private void loadSurfaceRegion()
	{
		String account = configManager.getRSProfileKey();
		if (account == null || account.equals(surfaceForAccount))
		{
			return;
		}
		surfaceForAccount = account;
		lastSurfaceRegion = -1;
		String stored = configManager.getRSProfileConfiguration(CONFIG_GROUP, SURFACE_KEY);
		if (stored != null)
		{
			try
			{
				lastSurfaceRegion = Integer.parseInt(stored.trim());
			}
			catch (NumberFormatException ignored)
			{
				// Bad value: wait until the player is next on the surface.
			}
		}
	}

	// The overworld occupies region rows 39 to 64; dungeons and other off-map areas are
	// stored outside that band (the same rule the plugin uses for free dungeon regions).
	private static final int SURFACE_MIN_REGION_Y = 39;
	private static final int SURFACE_MAX_REGION_Y = 64;

	private static boolean isSurface(int regionId)
	{
		int regionY = regionId & 0xFF;
		return regionY >= SURFACE_MIN_REGION_Y && regionY <= SURFACE_MAX_REGION_Y;
	}

	/** Chunks between two regions on the region grid (diagonal steps count as one). */
	private static int chunksBetween(int regionA, int regionB)
	{
		int dx = Math.abs((regionA >> 8) - (regionB >> 8));
		int dy = Math.abs((regionA & 0xFF) - (regionB & 0xFF));
		return Math.max(dx, dy);
	}

	private static double fraction(NuzlockeTask task)
	{
		int target = Math.max(1, task.getTargetQuantity());
		return Math.min(1.0, task.getCurrentProgress() / (double) target);
	}

	// --- Drawing ------------------------------------------------------------

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showSavedTaskTracker() || client.getGameState() != GameState.LOGGED_IN)
		{
			hoveredAction = null;
			return null;
		}

		List<Item> list = currentItems();
		// The list is at most MAX_ROWS tall; anything beyond that scrolls.
		int visibleRows = expanded ? Math.max(1, Math.min(MAX_ROWS, list.size())) : 0;
		int listHeight = expanded ? visibleRows * ROW + PAD : 0;
		int height = listHeight + BAR;
		int contentHeight = list.size() * ROW;
		int viewHeight = visibleRows * ROW;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewHeight)));

		// Mouse position relative to this overlay (it's drawn at its own top-left).
		Rectangle bounds = getBounds();
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		int mx = mouse == null || bounds == null ? -1 : mouse.getX() - bounds.x;
		int my = mouse == null || bounds == null ? -1 : mouse.getY() - bounds.y;
		Runnable hovered = null;
		String hoveredId = null;
		boolean shiftHeld = client.isKeyPressed(KeyCode.KC_SHIFT);

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Font bold = FontManager.getRunescapeBoldFont();
		Font small = FontManager.getRunescapeSmallFont();

		// The list, above the bar.
		Rectangle view = new Rectangle(1, PAD / 2, WIDTH - 2, viewHeight);
		mouseOverList = expanded && view.contains(mx, my);
		if (expanded)
		{
			graphics.setColor(BACKGROUND);
			graphics.fillRect(0, 0, WIDTH, listHeight);
			graphics.setColor(BORDER);
			graphics.drawRect(0, 0, WIDTH - 1, listHeight);

			if (list.isEmpty())
			{
				graphics.setFont(small);
				graphics.setColor(SUBTEXT);
				Draw.centered(graphics, "Star tasks in the task window", new Rectangle(0, PAD / 2, WIDTH, ROW));
			}

			boolean scrollable = contentHeight > viewHeight;
			int rowWidth = scrollable ? view.width - 6 : view.width;
			NuzlockeTask tracked = plugin.getSelectedTask();
			Shape oldClip = graphics.getClip();
			graphics.clip(view);
			for (int i = 0; i < list.size(); i++)
			{
				int rowY = view.y + i * ROW - scroll;
				if (rowY + ROW < view.y || rowY > view.y + view.height)
				{
					continue;
				}
				Item item = list.get(i);
				Rectangle row = new Rectangle(view.x, rowY, rowWidth, ROW);
				boolean isTracked = tracked != null && item.task.getTaskId().equals(tracked.getTaskId());
				boolean hover = mouseOverList && row.contains(mx, my);
				if (hover)
				{
					hoveredId = item.task.getTaskId();
					hovered = () ->
					{
						if (isTracked)
						{
							plugin.clearSelectedTask();
						}
						else
						{
							plugin.selectTaskFromGame(item.task);
						}
					};
				}
				drawRow(graphics, item, row, isTracked, hover, hover && shiftHeld, bold, small);
			}
			graphics.setClip(oldClip);

			if (scrollable)
			{
				int thumbHeight = Math.max(16, viewHeight * viewHeight / contentHeight);
				int thumbY = view.y + (viewHeight - thumbHeight) * scroll / Math.max(1, contentHeight - viewHeight);
				graphics.setColor(BAR_BACKGROUND);
				graphics.fillRect(view.x + view.width - 5, view.y, 4, viewHeight);
				graphics.setColor(BORDER);
				graphics.fillRect(view.x + view.width - 5, thumbY, 4, thumbHeight);
			}
		}

		// The bar: click to open or close the list.
		Rectangle bar = new Rectangle(0, listHeight, WIDTH, BAR);
		boolean barHover = bar.contains(mx, my);
		if (barHover)
		{
			hovered = () ->
			{
				expanded = !expanded;
				scroll = 0;
			};
		}
		graphics.setColor(barHover ? new Color(70, 60, 45, 240) : BAR_BACKGROUND);
		graphics.fillRect(bar.x, bar.y, bar.width, bar.height);
		graphics.setColor(BORDER);
		graphics.drawRect(bar.x, bar.y, bar.width - 1, bar.height - 1);
		graphics.setFont(bold);
		FontMetrics fm = graphics.getFontMetrics();
		graphics.setColor(TITLE);
		graphics.drawString("Saved tasks (" + list.size() + ")", bar.x + PAD, Draw.textY(fm, bar.y, BAR));
		drawChevron(graphics, bar.x + bar.width - 12, bar.y + BAR / 2, expanded, barHover ? Color.WHITE : SUBTEXT);

		hoveredAction = hovered;
		hoveredTaskId = hoveredId;
		return new Dimension(WIDTH, height);
	}

	private void drawRow(Graphics2D graphics, Item item, Rectangle row, boolean isTracked, boolean hover,
		boolean removing, Font bold, Font small)
	{
		if (removing)
		{
			// Shift held over a task: show that a click will remove it.
			graphics.setColor(new Color(255, 70, 70, 50));
			graphics.fillRect(row.x, row.y, row.width, row.height);
			graphics.setFont(small);
			FontMetrics rm = graphics.getFontMetrics();
			graphics.setColor(NO_LEVEL);
			graphics.drawString("Remove", row.x + row.width - PAD - rm.stringWidth("Remove"), row.y + 12);
		}
		else if (isTracked)
		{
			graphics.setColor(TRACKED_FILL);
			graphics.fillRect(row.x, row.y, row.width, row.height);
			graphics.setColor(TITLE);
			graphics.fillRect(row.x, row.y, 3, row.height);
		}
		else if (hover)
		{
			graphics.setColor(ROW_HOVER);
			graphics.fillRect(row.x, row.y, row.width, row.height);
		}

		NuzlockeTask task = item.task;
		int textX = row.x + PAD + 2;
		int rightEdge = row.x + row.width - PAD;

		graphics.setFont(small);
		FontMetrics fm = graphics.getFontMetrics();
		boolean canDo = plugin.meetsLevelRequirement(task);
		graphics.setColor(canDo ? Color.WHITE : NO_LEVEL);
		String name = task.getName() == null ? task.getTaskId() : task.getName();
		// Leave room for the "Remove" hint while Shift is held over this row.
		int nameRight = removing ? rightEdge - fm.stringWidth("Remove") - 6 : rightEdge;
		graphics.drawString(Draw.fit(fm, name, nameRight - textX), textX, row.y + 12);

		// The chunk it's in (green when you're standing in it); quests and level-ups
		// have no chunk, so they show their category instead.
		String where = item.chunk.isEmpty() ? NuzlockeTask.displayCategory(task.getCategory()) : item.chunk;

		int target = Math.max(1, task.getTargetQuantity());
		int progressWidth = 0;
		if (target > 1)
		{
			String progress = Math.min(task.getCurrentProgress(), target) + "/" + target;
			progressWidth = fm.stringWidth(progress) + 6;
			graphics.setColor(SUBTEXT);
			graphics.drawString(progress, rightEdge - fm.stringWidth(progress), row.y + 25);
		}
		graphics.setColor(item.distance == 0 ? new Color(120, 220, 120) : SUBTEXT);
		graphics.drawString(Draw.fit(fm, where, rightEdge - progressWidth - textX), textX, row.y + 25);
	}

	/** Points up when closed (the list opens upwards), down when open. */
	private static void drawChevron(Graphics2D graphics, int cx, int cy, boolean open, Color color)
	{
		graphics.setColor(color);
		Polygon arrow = open
			? new Polygon(new int[]{cx - 4, cx + 4, cx}, new int[]{cy - 2, cy - 2, cy + 3}, 3)
			: new Polygon(new int[]{cx - 4, cx + 4, cx}, new int[]{cy + 2, cy + 2, cy - 3}, 3);
		graphics.fillPolygon(arrow);
	}
}
