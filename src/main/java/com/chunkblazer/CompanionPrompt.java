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
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.LinkBrowser;

/**
 * The tutorial's first step, and the only part of it in ChunkBlazer itself: a box saying
 * whether ChunkBlazer UI and Region Locker GPU are installed and on. A plugin that's off can be
 * turned on from here; one that isn't installed opens its Plugin Hub page. It hides while
 * both are on, and goes for good on Skip. Nothing else in ChunkBlazer depends on it.
 *
 * <p>While it shows, "companionCheckPending" is true, so ChunkBlazer UI's tutorial waits.
 */
@Slf4j
@Singleton
class CompanionPrompt extends Overlay
{
	private static final String GROUP = "chunkblazer";
	// Set by Skip only: with both plugins on it just stays hidden, so it comes back if one's
	// turned off or removed later.
	private static final String DONE = "companionCheckSkipped";
	private static final String PENDING = "companionCheckPending";
	private static final String HUB = "https://runelite.net/plugin-hub/show/";
	// Tutorial Island's progress once the Gielinor Guide is done with you (the door opens).
	private static final int GUIDE_DONE = 10;

	/**
	 * One of the plugins it checks: its name as installed, what it adds, its Plugin Hub page,
	 * and another plugin from the same download that has to be on with it (or none).
	 */
	private static final String[][] COMPANIONS = {
		{"ChunkBlazer UI", "Task window, task cards, themes and the tutorial", "chunkblazer-ui", null},
		{"Region Locker GPU", "Greys out the chunks you haven't unlocked", "region-locker", "Region Locker"},
	};

	// Old School's colours (as in ChunkBlazer UI) and the stone frame and bevels from ui/.
	private static final Color TITLE = new Color(255, 152, 31);
	private static final Color TEXT = new Color(255, 176, 64);
	private static final Color LABEL = new Color(170, 150, 112);
	private static final Color ON = new Color(80, 220, 80);
	private static final Color RED = new Color(255, 90, 70);
	private static final Color GROOVE_DARK = new Color(36, 30, 24);
	private static final Color GROOVE_LIGHT = new Color(112, 99, 79);
	private static final java.awt.image.BufferedImage FRAME = Skin.image("frame");
	private static final java.awt.image.BufferedImage RAISED = Skin.image("raised");
	private static final java.awt.image.BufferedImage SUNK = Skin.image("sunk");
	private static final int WIDTH = 340;
	private static final int LINE = 12;
	private static final int PAD = 12;

	private final Client client;
	private final ConfigManager configManager;
	private final PluginManager pluginManager;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final EventBus eventBus;

	private volatile boolean showing;
	private volatile Rectangle box;
	// Clickable areas from the last frame, and what each does.
	private final List<Rectangle> spots = new ArrayList<>();
	private final List<Runnable> actions = new ArrayList<>();

	private final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			Rectangle b = box;
			if (!showing || b == null || !b.contains(event.getPoint()))
			{
				return event;
			}
			synchronized (spots)
			{
				for (int i = 0; i < spots.size(); i++)
				{
					if (spots.get(i).contains(event.getPoint()))
					{
						Runnable action = actions.get(i);
						SwingUtilities.invokeLater(action);
						break;
					}
				}
			}
			event.consume();
			return event;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			return consumeInBox(event);
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent event)
		{
			return consumeInBox(event);
		}
	};

	@Inject
	CompanionPrompt(Client client, ConfigManager configManager, PluginManager pluginManager, OverlayManager overlayManager,
		MouseManager mouseManager, EventBus eventBus)
	{
		this.client = client;
		this.configManager = configManager;
		this.pluginManager = pluginManager;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.eventBus = eventBus;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGHEST);
	}

	void start()
	{
		// A box left up by a crash or force close saved "pending" as true, and nothing would
		// clear it if the box isn't needed now (ChunkBlazer UI's tutorial would wait forever).
		showing = false;
		configManager.setConfiguration(GROUP, PENDING, false);
		found.clear();
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouse);
		eventBus.register(this);
	}

	void stop()
	{
		eventBus.unregister(this);
		mouseManager.unregisterMouseListener(mouse);
		overlayManager.remove(this);
		setShowing(false);
	}

	private MouseEvent consumeInBox(MouseEvent event)
	{
		Rectangle b = box;
		if (showing && b != null && b.contains(event.getPoint()))
		{
			event.consume();
		}
		return event;
	}

	// Whether ChunkBlazer UI or Region Locker GPU was on at the last look (for the ground borders' Auto).
	private volatile boolean anyOn;

	// Plugins looked up by name, looked up again only when RuneLite's plugin list changes.
	private final Map<String, Optional<Plugin>> found = new ConcurrentHashMap<>();
	// Per add-on that's installed but off: the plugins its Turn on would switch off, or null.
	private volatile String[] turnsOff = new String[COMPANIONS.length];

	/** Whether ChunkBlazer UI or Region Locker GPU is on (as of the last game tick). */
	boolean anyOn()
	{
		return anyOn;
	}

	// Whether ChunkBlazer UI was on at the last look.
	private volatile boolean uiOn;

	/** Whether ChunkBlazer UI is on (as of the last game tick). */
	boolean uiOn()
	{
		return uiOn;
	}

	/** Whether ChunkBlazer GPU is on right now (it draws the chunk walls into the scene itself). */
	boolean gpuOn()
	{
		Plugin gpu = find("ChunkBlazer GPU");
		return gpu != null && pluginManager.isPluginActive(gpu);
	}

	/** A plugin was installed, removed or updated: look the add-ons up again. */
	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		found.clear();
	}

	@Subscribe
	public void onExternalPluginsChanged(ExternalPluginsChanged event)
	{
		found.clear();
	}

	// Opened on purpose (the Points orb without ChunkBlazer UI): shows even after a Skip.
	private volatile boolean asked;

	/** Show the box now, as the Points orb does without ChunkBlazer UI. Any thread. */
	void open()
	{
		asked = true;
		setShowing(true);
	}

	/**
	 * Each tick in game: show it unless both plugins are on, or (unless asked for) it's been
	 * skipped or the Gielinor Guide hasn't finished with you yet.
	 */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		boolean any = false;
		boolean allOn = true;
		for (String[] companion : COMPANIONS)
		{
			boolean on = isOn(companion);
			any |= on;
			allOn &= on;
		}
		anyOn = any;
		uiOn = isOn(COMPANIONS[0]);
		boolean wanted = asked || !Boolean.parseBoolean(configManager.getConfiguration(GROUP, DONE))
			&& client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TUTORIAL) >= GUIDE_DONE;
		if (allOn)
		{
			asked = false;
		}
		setShowing(!allOn && wanted && client.getGameState() == GameState.LOGGED_IN);
		if (showing)
		{
			String[] off = new String[COMPANIONS.length];
			for (int i = 0; i < COMPANIONS.length; i++)
			{
				Plugin plugin = find(COMPANIONS[i][0]);
				off[i] = plugin == null || pluginManager.isPluginActive(plugin) ? null : activeConflicts(plugin);
			}
			turnsOff = off;
		}
	}

	/** The running plugins that turning {@code plugin} on would switch off ("GPU, 117 HD"), or null. */
	private String activeConflicts(Plugin plugin)
	{
		StringBuilder names = new StringBuilder();
		for (Plugin conflict : pluginManager.conflictsForPlugin(plugin))
		{
			if (pluginManager.isPluginActive(conflict))
			{
				names.append(names.length() == 0 ? "" : ", ").append(conflict.getName());
			}
		}
		return names.length() == 0 ? null : names.toString();
	}

	/** Whether the box is up (ChunkBlazer's own Lumbridge prompts wait for it). */
	boolean isShowing()
	{
		return showing;
	}

	private void setShowing(boolean show)
	{
		if (show != showing)
		{
			showing = show;
			configManager.setConfiguration(GROUP, PENDING, show);
		}
	}

	/** Whether a companion is on, with the plugin it needs from the same download. */
	private boolean isOn(String[] companion)
	{
		Plugin plugin = find(companion[0]);
		Plugin needs = companion[3] == null ? null : find(companion[3]);
		return plugin != null && pluginManager.isPluginActive(plugin)
			&& (companion[3] == null || needs != null && pluginManager.isPluginActive(needs));
	}

	/** Whether a plugin is installed, on or not (for the Region Locker sync). */
	boolean isInstalled(String name)
	{
		return find(name) != null;
	}

	/** An installed plugin by its name, or null. */
	private Plugin find(String name)
	{
		return found.computeIfAbsent(name, n ->
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
				if (descriptor != null && n.equals(descriptor.name()))
				{
					return Optional.of(plugin);
				}
			}
			return Optional.empty();
		}).orElse(null);
	}

	/** Turns a plugin on, as its switch in RuneLite's plugin list would (anything it conflicts with goes off first). */
	private void turnOn(Plugin plugin)
	{
		try
		{
			for (Plugin conflict : pluginManager.conflictsForPlugin(plugin))
			{
				pluginManager.setPluginEnabled(conflict, false);
				pluginManager.stopPlugin(conflict);
			}
			pluginManager.setPluginEnabled(plugin, true);
			pluginManager.startPlugin(plugin);
		}
		catch (PluginInstantiationException e)
		{
			log.warn("Could not turn on {}", plugin.getName(), e);
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!showing)
		{
			box = null;
			return null;
		}
		List<Rectangle> frameSpots = new ArrayList<>();
		List<Runnable> frameActions = new ArrayList<>();
		int header = 26;
		// Descriptions wrap beside the button, so each row is as tall as its text.
		FontMetrics small = graphics.getFontMetrics(FontManager.getRunescapeSmallFont());
		int descWidth = WIDTH - 16 - 88 - 16;
		List<List<String>> descs = new ArrayList<>();
		int rowsHeight = 0;
		String[] off = turnsOff;
		for (int i = 0; i < COMPANIONS.length; i++)
		{
			// Turn on switches off what it conflicts with, as RuneLite's own toggle does: say so first.
			String note = off[i] != null ? "Turning it on turns off " + off[i] : COMPANIONS[i][1];
			List<String> lines = TaskLists.wrap(small, note, descWidth);
			descs.add(lines);
			rowsHeight += rowHeight(lines);
		}
		int height = header + 40 + rowsHeight + 20 + 34;
		int x = client.getViewportXOffset() + (client.getViewportWidth() - WIDTH) / 2;
		int y = client.getViewportYOffset() + 30;
		Rectangle b = new Rectangle(x, y, WIDTH, height);
		frame(graphics, b, header);

		graphics.setFont(FontManager.getRunescapeBoldFont());
		Skin.centred(graphics, "Before you start", b.x + WIDTH / 2, b.y + 18, TITLE);
		graphics.setFont(FontManager.getRunescapeFont());
		Skin.text(graphics, "ChunkBlazer is best with its two add-on plugins.", b.x + PAD, b.y + header + 18, TEXT);
		Skin.text(graphics, "Turn them on or install them here, then carry on.", b.x + PAD, b.y + header + 33, TEXT);

		net.runelite.api.Point m = client.getMouseCanvasPosition();
		int rowY = b.y + header + 42;
		for (int i = 0; i < COMPANIONS.length; i++)
		{
			String[] companion = COMPANIONS[i];
			List<String> lines = descs.get(i);
			int rowHeight = rowHeight(lines);
			Plugin plugin = find(companion[0]);
			boolean active = isOn(companion);
			// Each add-on on a sunk stone strip, like a row in the task window.
			Rectangle row = new Rectangle(b.x + 8, rowY, WIDTH - 16, rowHeight - 6);
			graphics.setColor(new Color(0, 0, 0, 60));
			graphics.fillRect(row.x, row.y, row.width, row.height);
			Skin.nine(graphics, SUNK, row, 2);
			Rectangle button = new Rectangle(row.x + row.width - 88, row.y + (row.height - 22) / 2, 80, 22);
			graphics.setFont(FontManager.getRunescapeBoldFont());
			Skin.text(graphics, companion[0], row.x + 8, row.y + 16, Color.WHITE);
			int nameEnd = row.x + 8 + graphics.getFontMetrics().stringWidth(companion[0]);
			graphics.setFont(FontManager.getRunescapeSmallFont());
			for (int l = 0; l < lines.size(); l++)
			{
				Skin.text(graphics, lines.get(l), row.x + 8, row.y + 30 + l * LINE, off[i] != null && !active ? TEXT : LABEL);
			}
			// How it is, beside its name.
			String state = active ? "On" : plugin != null ? "Installed, off" : "Not installed";
			Skin.text(graphics, state, nameEnd + 6, row.y + 16, active ? ON : RED);
			if (!active)
			{
				String label = plugin != null ? "Turn on" : "Plugin Hub";
				Runnable action = plugin != null ? () ->
				{
					// What it needs from the same download first (Region Locker for its GPU).
					Plugin needs = companion[3] == null ? null : find(companion[3]);
					if (needs != null && !pluginManager.isPluginActive(needs))
					{
						turnOn(needs);
					}
					turnOn(plugin);
				} : () -> LinkBrowser.browse(HUB + companion[2]);
				button(graphics, button, label, m);
				frameSpots.add(button);
				frameActions.add(action);
			}
			rowY += rowHeight;
		}

		graphics.setFont(FontManager.getRunescapeSmallFont());
		Skin.text(graphics, "Or search \"chunkblazer\" or \"region locker\" in the Plugin Hub.", b.x + PAD, rowY + 8, LABEL);
		Rectangle skip = new Rectangle(b.x + (WIDTH - 100) / 2, b.y + height - 30, 100, 22);
		button(graphics, skip, "Skip", m);
		frameSpots.add(skip);
		frameActions.add(() ->
		{
			asked = false;
			setShowing(false);
			configManager.setConfiguration(GROUP, DONE, true);
		});

		synchronized (spots)
		{
			spots.clear();
			spots.addAll(frameSpots);
			actions.clear();
			actions.addAll(frameActions);
		}
		box = b;
		return null;
	}

	/**
	 * The box the way ChunkBlazer UI draws its Old School boxes: a drop shadow, stone, a darker
	 * header band with a groove under it, the steel frame round it all and an orange line on top.
	 */
	private static int rowHeight(List<String> lines)
	{
		return 44 + Math.max(0, lines.size() - 1) * LINE;
	}

	private static void frame(Graphics2D graphics, Rectangle b, int header)
	{
		java.awt.Paint old = graphics.getPaint();
		graphics.setColor(new Color(0, 0, 0, 110));
		graphics.fillRect(b.x + 3, b.y + 3, b.width, b.height);
		graphics.setPaint(Skin.stone(false));
		graphics.fillRect(b.x, b.y, b.width, b.height);
		graphics.setPaint(Skin.stone(true));
		graphics.fillRect(b.x + 3, b.y + 3, b.width - 6, header - 3);
		graphics.setPaint(old);
		graphics.setColor(GROOVE_DARK);
		graphics.drawLine(b.x + 3, b.y + header, b.x + b.width - 4, b.y + header);
		graphics.setColor(GROOVE_LIGHT);
		graphics.drawLine(b.x + 3, b.y + header + 1, b.x + b.width - 4, b.y + header + 1);
		Skin.nine(graphics, FRAME, b, 6);
		graphics.setColor(TITLE);
		graphics.fillRect(b.x + 4, b.y + 4, b.width - 8, 2);
	}

	/** A stone button: raised, sunk under the mouse, its label orange (white when hovered). */
	private static void button(Graphics2D graphics, Rectangle area, String label, net.runelite.api.Point mouse)
	{
		boolean hover = mouse != null && area.contains(mouse.getX(), mouse.getY());
		java.awt.Paint old = graphics.getPaint();
		graphics.setPaint(Skin.stone(!hover));
		graphics.fillRect(area.x, area.y, area.width, area.height);
		graphics.setPaint(old);
		Skin.nine(graphics, hover ? SUNK : RAISED, area, 2);
		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = graphics.getFontMetrics();
		Skin.text(graphics, label, area.x + (area.width - fm.stringWidth(label)) / 2, area.y + 15, hover ? Color.WHITE : TITLE);
	}
}
