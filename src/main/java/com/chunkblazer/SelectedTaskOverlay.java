/*
 * Copyright (c) 2026, Vani-Lab
 * Copyright (c) 2026, NetworkAccessViolation
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

import com.chunkblazer.modules.NPCKillModule;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.BasicStroke;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * On-screen box showing the task the player selected in the side panel, with a
 * progress bar that updates live. It re-reads the task every frame, so any
 * progress change made by the task modules shows up immediately.
 * Alt + drag to move it; click the X (or right-click, then Deselect) to stop tracking.
 * Click the book in the top-left corner to archive the task (see TaskArchive), which
 * also stops tracking it.
 *
 * Timed kill tasks ("Kill a Frog in 21 seconds") show a live countdown instead of a
 * progress bar, starting on your first hit (the same tick the kill tracker starts
 * timing): green, then yellow, orange and red, and "Too slow" once time runs out.
 * Multi-kill timed tasks also show "Kills x/y". Single-action tasks, including
 * 1-tick tasks, get no bar at all.
 *
 * Three looks (the Task Box setting): Vani's full box, Vani's compact strip, and Net's
 * simple box, designed by NetworkAccessViolation (it was ChunkBlazerTaskOverlay). They
 * all track the same way: right-click Deselect, the Tasks menu on targets, Alt + drag.
 */
@Singleton
public class SelectedTaskOverlay extends OverlayPanel
{
	private static final Color BAR_BACKGROUND = new Color(60, 60, 60, 200);
	private static final Color REQUIREMENT_TEXT = new Color(200, 200, 200);
	private static final Color MISSING_LEVEL = new Color(255, 90, 90);
	private static final int CLOSE_SIZE = 9;
	private static final int CLOSE_MARGIN = 5;
	private static final Color CLOSE_IDLE = new Color(200, 200, 200);
	private static final Color CLOSE_HOVER = new Color(255, 90, 90);
	private static final BufferedImage BOOK = Skin.image("book_box");
	private static final BufferedImage BOOK_HOVER = Skin.image("book_box_hover");
	private static final Color NET_ORANGE = new Color(255, 152, 0);

	// Countdown colours, from plenty of time left to out of time.
	private static final Color TIMER_OK = new Color(90, 200, 90);
	private static final Color TIMER_WARN = new Color(230, 200, 60);
	private static final Color TIMER_LATE = new Color(255, 140, 0);
	private static final Color TIMER_FAIL = new Color(220, 50, 50);
	private static final int TICK_MS = 600;

	// Where the X was last drawn, relative to the overlay's top-left. Null while hidden.
	private volatile Rectangle closeButton;
	private volatile boolean closeHovered;
	// Same for the archive book in the opposite corner, plus the task it would archive.
	private volatile Rectangle archiveButton;
	private volatile boolean archiveHovered;
	// Beside the book: switch between the compact strip and the detailed box.
	private volatile Rectangle modeButton;
	private volatile boolean modeHovered;
	private final ConfigManager configManager;
	private volatile String shownTaskId;

	// The game only advances in 0.6s ticks; remembering when the current tick began
	// lets the countdown move smoothly between them instead of jumping.
	private int lastTick = -1;
	private long lastTickAt;

	// Compact layout: how far it has opened into the full box (0 shut, 1 open), when that
	// was last moved on, and the two sizes it moves between.
	private float expand;
	private long expandAt;
	private Dimension compactSize = new Dimension(120, 17);
	// Where the compact strip was on screen just before it started to open.
	private Rectangle stripBounds;
	private Dimension fullSize = new Dimension(190, 60);

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final TaskArchive archive;
	private final NPCKillModule killModule;

	@Inject
	public SelectedTaskOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config, TaskArchive archive,
		NPCKillModule killModule, ConfigManager configManager)
	{
		super(plugin);
		this.configManager = configManager;
		this.killModule = killModule;
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.archive = archive;

		setPosition(OverlayPosition.TOP_LEFT);

		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Deselect", "ChunkBlazer task",
				e -> plugin.clearSelectedTask());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		TaskTrackerStyle style = config.taskTrackerStyle();
		if (style == TaskTrackerStyle.OFF || client.getGameState() != GameState.LOGGED_IN)
		{
			hideButtons();
			return null;
		}

		NuzlockeTask task = plugin.getSelectedTask();
		if (task == null)
		{
			hideButtons();
			return null;
		}

		if (style == TaskTrackerStyle.NET)
		{
			hideButtons();
			return drawNet(graphics, task);
		}
		// Vani's looks: a wider box, and a few pixels between rows so wrapped text never
		// touches the progress bar.
		panelComponent.setPreferredSize(new Dimension(190, 0));
		panelComponent.setGap(new Point(0, 3));
		// Inside the stone frame's edges, with more room at the foot (left, top, right, bottom).
		panelComponent.setBorder(new Rectangle(5, 5, 5, 8));
		// The stone frame is painted behind the panel (at last frame's size).
		panelComponent.setBackgroundColor(new Color(0, 0, 0, 0));
		return drawVani(graphics, task, style);
	}

	/** Vani's full box (and the Compact strip). */
	private Dimension drawVani(Graphics2D graphics, NuzlockeTask task, TaskTrackerStyle style)
	{
		Color tier = TaskCardTier.fromTask(task).getAccent();
		// Compact: one small line, out of the way. Hovering it opens the full box (what to do,
		// requirements, missing levels); timed tasks keep the full box for their countdown.
		net.runelite.api.Point at = client.getMouseCanvasPosition();
		Rectangle shown = getBounds();
		// While it opens, the box changes size (a long name is wider as a strip than as the box,
		// and wraps to more lines in it), so it can slide out from under the mouse, shut, slide
		// back and open again: a jitter. Where the strip was counts as hovered too, so it stays
		// open for as long as the mouse is on either.
		if (expand == 0 && shown != null)
		{
			stripBounds = new Rectangle(shown);
		}
		boolean hovered = at != null && ((shown != null && shown.contains(at.getX(), at.getY()))
			|| (expand > 0 && stripBounds != null && stripBounds.contains(at.getX(), at.getY())));
		// Opens quickly under the mouse (0.12s), closes slower once it leaves (0.4s).
		long now = System.currentTimeMillis();
		long step = Math.min(100, now - expandAt);
		expandAt = now;
		expand = hovered ? Math.min(1, expand + step / 120f) : Math.max(0, expand - step / 400f);
		boolean compact = style == TaskTrackerStyle.COMPACT && timeLimitTicks(task) == 0;
		if (compact && expand == 0)
		{
			hideButtons();
			compactSize = drawCompact(graphics, task, tier);
			return compactSize;
		}
		// Part way open: show the full box cut to a size between the two.
		float e = expand * (2 - expand);
		int w = Math.round(compactSize.width + (fullSize.width - compactSize.width) * e);
		int h = Math.round(compactSize.height + (fullSize.height - compactSize.height) * e);
		java.awt.Shape oldClip = graphics.getClip();
		if (compact && expand < 1)
		{
			graphics.clip(new Rectangle(0, 0, w, h));
		}
		Skin.panel(graphics, new Rectangle(0, 0, fullSize.width, fullSize.height), 0, tier);

		// The heading is the task's category, in its tier colour (which is its points). Its level
		// only shows below, and only when you don't have it.
		panelComponent.getChildren().add(TitleComponent.builder()
				.text(NuzlockeTask.displayCategory(task.getCategory()))
				.color(tier)
				.build());

		centred(graphics, task.getName(), new Color(255, 152, 31));

		// What the task actually asks for, when the name alone doesn't say. A timed task's
		// limit isn't repeated as text when the countdown bar below shows it.
		for (String requirement : requirementLines(task, timeLimitTicks(task) == 0))
		{
			centred(graphics, requirement, REQUIREMENT_TEXT);
		}
		// Every level you're missing, e.g. "20 Defence, 20 Ranged".
		String missing = TaskTargetExtras.missingRequirement(client, task);
		if (missing != null)
		{
			centred(graphics, missing, MISSING_LEVEL);
		}
		else if (!plugin.meetsLevelRequirement(task))
		{
			centred(graphics, task.getLevelRequirement() + " " + NuzlockeTask.displayCategory(task.getCategory()), MISSING_LEVEL);
		}

		int progress = task.getCurrentProgress();
		int target = Math.max(1, task.getTargetQuantity());

		int limitTicks = timeLimitTicks(task);
		if (limitTicks > 0)
		{
			// Timed kill task: "Kills x/y" for multi-kill ones, then the countdown.
			if (target > 1)
			{
				panelComponent.getChildren().add(LineComponent.builder()
						.left("Kills")
						.right(Math.min(progress, target) + "/" + target)
						.leftColor(REQUIREMENT_TEXT)
						.rightColor(Color.WHITE)
						.build());
			}
			panelComponent.getChildren().add(countdownBar(task, limitTicks));
		}
		// One-off tasks (a target of 1, 1-tick tasks included) get no bar: "0/1" says nothing useful.
		else if (target > 1)
		{
			ProgressBarComponent bar = new ProgressBarComponent();
			bar.setMinimum(0);
			bar.setMaximum(target);
			bar.setValue(Math.min(progress, target));
			// A soft green in a dark groove.
			bar.setForegroundColor(Skin.FILL);
			bar.setBackgroundColor(new Color(40, 34, 27));
			bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.FULL); // shows "3/10"
			panelComponent.getChildren().add(bar);
		}

		Dimension size = super.render(graphics);
		if (size == null)
		{
			graphics.setClip(oldClip);
			hideButtons();
			return null;
		}

		// Close X in the top-right corner and the archive book in the top-left, drawn
		// over the panel just painted.
		// (Just below the frame's top edge.)
		Rectangle box = new Rectangle(size.width - CLOSE_SIZE - CLOSE_MARGIN - 1, CLOSE_MARGIN + 3, CLOSE_SIZE, CLOSE_SIZE);
		Rectangle book = new Rectangle(CLOSE_MARGIN + 1, CLOSE_MARGIN + 2, CLOSE_SIZE, CLOSE_SIZE + 2);

		// Hover test using the game's own mouse position, which is in the same
		// coordinate space the overlay is drawn in (works in stretched/resized
		// modes too). The click handler just asks "is the X hovered right now?".
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		Rectangle bounds = getBounds();
		closeHovered = isHovered(mouse, bounds, box);
		archiveHovered = isHovered(mouse, bounds, book);
		Rectangle mode = new Rectangle(book.x + book.width + 5, CLOSE_MARGIN + 3, CLOSE_SIZE, CLOSE_SIZE);
		modeHovered = isHovered(mouse, bounds, mode);

		graphics.setColor(closeHovered ? CLOSE_HOVER : CLOSE_IDLE);
		graphics.setStroke(new BasicStroke(2));
		graphics.drawLine(box.x, box.y, box.x + box.width, box.y + box.height);
		graphics.drawLine(box.x, box.y + box.height, box.x + box.width, box.y);
		graphics.drawImage(archiveHovered ? BOOK_HOVER : BOOK, book.x - 1, book.y - 1, null);
		// A bar ("shrink to the strip") on the detailed box, an outlined box ("keep it open") on the compact one.
		graphics.setColor(modeHovered ? CLOSE_HOVER : CLOSE_IDLE);
		if (style == TaskTrackerStyle.COMPACT)
		{
			graphics.drawRect(mode.x, mode.y, mode.width - 1, mode.height - 1);
		}
		else
		{
			graphics.fillRect(mode.x, mode.y + mode.height - 3, mode.width, 2);
		}
		modeButton = mode;

		closeButton = box;
		archiveButton = book;
		shownTaskId = task.getTaskId();
		fullSize = size;
		if (compact && expand < 1)
		{
			graphics.setClip(oldClip);
			return new Dimension(w, h);
		}
		return size;
	}

	/**
	 * Compact layout: a small rounded strip with the tier colour at the left, the name
	 * (red if you can't do it yet), its count on the right and a thin bar along the
	 * bottom. Right-click it to deselect, as with the full box.
	 */
	private Dimension drawCompact(Graphics2D graphics, NuzlockeTask task, Color tier)
	{
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		int target = Math.max(1, task.getTargetQuantity());
		int done = Math.min(task.getCurrentProgress(), target);
		String count = target > 1 ? done + "/" + target : "";
		// The whole name, however long: the strip grows to fit it.
		String name = task.getName() == null ? "" : task.getName();
		int width = 15 + fm.stringWidth(name) + (count.isEmpty() ? 0 : 8 + fm.stringWidth(count));
		int height = 20;
		Skin.panel(graphics, new Rectangle(0, 0, width + 4, height), 0, null);
		graphics.setColor(tier);
		graphics.fillRect(5, 5, 3, height - 10);
		if (target > 1)
		{
			graphics.setColor(Skin.FILL);
			graphics.fillRect(9, height - 6, (width - 15) * done / target, 2);
		}
		boolean ready = TaskTargetExtras.canDo(client, plugin, task);
		graphics.setColor(ready ? Color.WHITE : MISSING_LEVEL);
		Skin.text(graphics, name, 9, 14);
		graphics.setColor(new Color(255, 255, 0));
		Skin.text(graphics, count, width - 6 - fm.stringWidth(count), 14);
		return new Dimension(width, height);
	}

	/** A line of the box, centred, wrapped onto as many lines as it needs to fit. */
	private void centred(Graphics2D graphics, String text, Color color)
	{
		int width = panelComponent.getPreferredSize().width - 12;
		for (String line : TaskBrowserOverlay.wrap(graphics.getFontMetrics(), text, width))
		{
			panelComponent.getChildren().add(TitleComponent.builder().text(line).color(color).build());
		}
	}

	/**
	 * Net's simple box (by NetworkAccessViolation), drawn as he made it: "Selected Task",
	 * the name, the description, category, points and level, the chunk and area, then an
	 * orange progress bar. RuneLite's standard panel size and background.
	 */
	private Dimension drawNet(Graphics2D graphics, NuzlockeTask task)
	{
		panelComponent.setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH, 0));
		panelComponent.setGap(new Point(5, 1));
		panelComponent.setBackgroundColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
		panelComponent.setBorder(new Rectangle(4, 4, 4, 4));
		panelComponent.getChildren().add(TitleComponent.builder().text("Selected Task").color(NET_ORANGE).build());
		panelComponent.getChildren().add(LineComponent.builder().left(task.getName()).build());
		// An empty title, for a little padding.
		panelComponent.getChildren().add(TitleComponent.builder().text("").build());
		String description = task.getDescription();
		if (description != null && !description.trim().isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder().left(description).leftColor(REQUIREMENT_TEXT).build());
		}
		String info = NuzlockeTask.displayCategory(task.getCategory()) + " | " + task.getBasePoints() + " pts"
			+ (task.getLevelRequirement() > 1 ? " | L" + task.getLevelRequirement() : "");
		panelComponent.getChildren().add(LineComponent.builder().left(info).leftColor(new Color(255, 200, 100)).build());
		String region = plugin.getTaskRegionName(task);
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Chunk: " + (region != null && !region.isEmpty() ? region : "unknown"))
			.leftColor(new Color(140, 200, 230))
			.build());
		String area = plugin.getTaskArea(task);
		if (area != null && !area.isEmpty())
		{
			panelComponent.getChildren().add(LineComponent.builder().left("Area: " + area).leftColor(new Color(180, 180, 220)).build());
		}
		ProgressBarComponent bar = new ProgressBarComponent();
		bar.setBackgroundColor(new Color(61, 56, 49));
		bar.setForegroundColor(NET_ORANGE);
		bar.setValue(task.getCurrentProgress());
		bar.setMaximum(task.getTargetQuantity());
		bar.setRightLabel(String.format("%d/%d", task.getCurrentProgress(), task.getTargetQuantity()));
		bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
		panelComponent.getChildren().add(bar);
		return super.render(graphics);
	}

	/**
	 * The task's time limit in ticks, if it's a timed kill task that gets a countdown;
	 * 0 otherwise. 1-tick tasks ("in the first hit") are single actions, so they get none.
	 */
	private static int timeLimitTicks(NuzlockeTask task)
	{
		TaskConstraints c = task.getConstraints();
		if (c == null || c.getTimeInTicks() == null || c.getTimeInTicks() <= 1 || task.getTargetNpc() == null)
		{
			return 0;
		}
		String type = task.getCompletionType();
		return "NPC_KILL".equalsIgnoreCase(type) || "SLAYER".equalsIgnoreCase(type) ? c.getTimeInTicks() : 0;
	}

	/**
	 * Countdown bar for a timed kill: full and waiting until the first hit, then
	 * draining smoothly between game ticks, changing colour as time runs low.
	 */
	private ProgressBarComponent countdownBar(NuzlockeTask task, int limitTicks)
	{
		int tick = client.getTickCount();
		long now = System.currentTimeMillis();
		if (tick != lastTick)
		{
			lastTick = tick;
			lastTickAt = now;
		}

		long limitMs = (long) limitTicks * TICK_MS;
		int startTick = killModule.getActiveFightStartTick(task.getTargetNpc());
		long remaining;
		String label;
		if (startTick < 0)
		{
			remaining = limitMs;
			label = formatSeconds(limitMs) + " (starts on first hit)";
		}
		else
		{
			long elapsed = (long) (tick - startTick) * TICK_MS + Math.min(TICK_MS, now - lastTickAt);
			remaining = Math.max(0, limitMs - elapsed);
			label = remaining > 0 ? formatSeconds(remaining) : "Too slow";
		}

		double fraction = limitMs > 0 ? remaining / (double) limitMs : 0;
		Color color;
		if (remaining <= 0)
		{
			color = TIMER_FAIL;
		}
		else if (fraction > 0.5)
		{
			color = TIMER_OK;
		}
		else if (fraction > 0.25)
		{
			color = TIMER_WARN;
		}
		else
		{
			color = TIMER_LATE;
		}

		ProgressBarComponent bar = new ProgressBarComponent();
		bar.setMinimum(0);
		bar.setMaximum(limitMs);
		bar.setValue(remaining);
		bar.setForegroundColor(color);
		bar.setBackgroundColor(BAR_BACKGROUND);
		bar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
		bar.setCenterLabel(label);
		return bar;
	}

	private static String formatSeconds(long ms)
	{
		return String.format("%.1fs", ms / 1000.0);
	}

	/** Mouse over a corner button, with a few pixels of slack around it. */
	private static boolean isHovered(net.runelite.api.Point mouse, Rectangle bounds, Rectangle button)
	{
		return mouse != null && bounds != null
				&& new Rectangle(bounds.x + button.x - 4, bounds.y + button.y - 4, button.width + 8, button.height + 8)
				.contains(mouse.getX(), mouse.getY());
	}

	private void hideButtons()
	{
		closeButton = null;
		closeHovered = false;
		archiveButton = null;
		archiveHovered = false;
		modeButton = null;
		modeHovered = false;
		shownTaskId = null;
	}

	/** True while the mouse is over the close X (updated every frame in render). */
	boolean isCloseHovered()
	{
		return closeButton != null && closeHovered;
	}

	/** True while the mouse is over the archive book (updated every frame in render). */
	boolean isArchiveHovered()
	{
		return archiveButton != null && archiveHovered;
	}

	/** True while the mouse is over the compact/detailed switch. */
	boolean isModeHovered()
	{
		return modeButton != null && modeHovered;
	}

	/** Switch the box between the compact strip and the detailed box. */
	void toggleMode()
	{
		configManager.setConfiguration("chunkblazer", "taskTrackerStyle",
			config.taskTrackerStyle() == TaskTrackerStyle.COMPACT ? TaskTrackerStyle.VANI : TaskTrackerStyle.COMPACT);
	}

	/** Archive the task this box is showing and stop tracking it. */
	void archiveShownTask()
	{
		String taskId = shownTaskId;
		if (taskId != null)
		{
			archive.archive(taskId);
			plugin.clearSelectedTask();
		}
	}

	/**
	 * Plain-English requirements for the task. Uses the task's description when it
	 * has one (challenges and combat achievements, e.g. "Defeat Scurrius with a Green
	 * d'hide body, chaps, vambraces, and Maple shortbow equipped."); otherwise spells
	 * out the common restrictions from its constraints.
	 */
	static List<String> requirementLines(NuzlockeTask task)
	{
		return requirementLines(task, true);
	}

	/** The same, with or without the time limit ("Within 0:18"). */
	private static List<String> requirementLines(NuzlockeTask task, boolean withTime)
	{
		List<String> lines = new ArrayList<>();
		String description = task.getDescription();
		if (description != null && !description.trim().isEmpty()
				&& !simplify(description).equals(simplify(task.getName())))
		{
			lines.add(description.trim());
			return lines;
		}

		TaskConstraints c = task.getConstraints();
		if (c == null)
		{
			return lines;
		}
		// One tick ("Within 0:01", a first-hit kill) says nothing useful.
		if (withTime && c.getTimeInTicks() != null && c.getTimeInTicks() > 1)
		{
			int seconds = (int) Math.round(c.getTimeInTicks() * 0.6);
			lines.add(String.format("Within %d:%02d", seconds / 60, seconds % 60));
		}
		if (Boolean.TRUE.equals(c.getNoPrayer()))
		{
			lines.add("No prayer");
		}
		if (Boolean.TRUE.equals(c.getNoFood()))
		{
			lines.add("No food");
		}
		if (Boolean.TRUE.equals(c.getNoEquipment()) || Boolean.TRUE.equals(c.getEquipNothing()))
		{
			lines.add("Nothing equipped");
		}
		if (c.getMaxCombatLevel() != null)
		{
			lines.add("Combat level " + c.getMaxCombatLevel() + " or lower");
		}
		if (c.getMinCombatLevel() != null)
		{
			lines.add("Combat level " + c.getMinCombatLevel() + " or higher");
		}
		return lines;
	}

	private static String simplify(String text)
	{
		return text == null ? "" : text.toLowerCase().replaceAll("[^a-z0-9]", "");
	}
}
