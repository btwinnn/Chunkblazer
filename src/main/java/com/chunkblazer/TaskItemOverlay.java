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
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Outlines items that have tasks, wherever they show up: inventory, bank and shops.
 * That's gear an active equip task needs (from anywhere: chests, quests, minigames,
 * shops) and tools that stand in for inventory skills (knife: fletching, tinderbox:
 * firemaking... see TaskTargetExtras.matchesItem). The item's right-click Tasks menu
 * lists them. Switched on and off from the task window's cogwheel; registered
 * by TaskBrowserOverlay.
 */
@Singleton
public class TaskItemOverlay extends WidgetItemOverlay
{
	// Shop window and the inventory shown beside it.
	private static final int SHOP_INTERFACE = 300;
	private static final int SHOP_INVENTORY_INTERFACE = 301;
	private static final long REFRESH_MS = 1000;

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final TaskArchive archive;
	private final ItemManager itemManager;

	// Outline images follow each item's own shape; they're costly to make, so they're
	// kept per item and colour.
	private final Map<String, BufferedImage> outlines = new HashMap<>();

	// Item id -> equip tasks that need it, and the active tasks considered. Rebuilt about
	// once a second; per-item results are cached in between.
	private Map<Integer, List<NuzlockeTask>> needed = Collections.emptyMap();
	private List<NuzlockeTask> source = Collections.emptyList();
	private final Map<Integer, List<NuzlockeTask>> perItem = new HashMap<>();
	private long builtAt;

	@Inject
	public TaskItemOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config, TaskArchive archive,
		ItemManager itemManager)
	{
		this.itemManager = itemManager;
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.archive = archive;
		showOnInventory();
		showOnBank();
		showOnInterfaces(SHOP_INTERFACE, SHOP_INVENTORY_INTERFACE);
	}

	/** Equip tasks needing this item (empty if none). */
	List<NuzlockeTask> tasksNeeding(int itemId)
	{
		refresh();
		return needed.getOrDefault(itemId, Collections.emptyList());
	}

	/**
	 * Every task listed on this item: tools and bones (by the item's name) plus equip
	 * gear (by exact item id). Used for the outline, the tooltip and the right-click menu.
	 */
	synchronized List<NuzlockeTask> tasksFor(int itemId)
	{
		refresh();
		List<NuzlockeTask> cached = perItem.get(itemId);
		if (cached != null)
		{
			return cached;
		}
		List<NuzlockeTask> result = new ArrayList<>();
		net.runelite.api.ItemComposition item = client.getItemDefinition(itemId);
		String itemName = item == null || item.getName() == null ? "" : item.getName().toLowerCase().trim();
		if (!itemName.isEmpty() && !"null".equals(itemName))
		{
			for (NuzlockeTask task : source)
			{
				if (TaskTargetExtras.matchesItem(task, itemName))
				{
					result.add(task);
				}
			}
		}
		for (NuzlockeTask task : needed.getOrDefault(itemId, Collections.emptyList()))
		{
			if (!result.contains(task))
			{
				result.add(task);
			}
		}
		perItem.put(itemId, result);
		return result;
	}

	private synchronized void refresh()
	{
		long now = System.currentTimeMillis();
		if (now - builtAt < REFRESH_MS)
		{
			return;
		}
		builtAt = now;
		perItem.clear();

		List<NuzlockeTask> tasks = new ArrayList<>();
		Set<String> archived = archive.ids();
		for (NuzlockeTask task : plugin.getActiveTasks())
		{
			if (task != null && !task.isCompleted() && !archived.contains(task.getTaskId()))
			{
				tasks.add(task);
			}
		}
		source = tasks;

		Map<Integer, List<NuzlockeTask>> map = new HashMap<>();
		for (NuzlockeTask task : tasks)
		{
			if (!"EQUIP".equalsIgnoreCase(task.getCompletionType()) || task.getRequiredItems() == null)
			{
				continue;
			}
			for (RequiredItem item : task.getRequiredItems())
			{
				if (item == null || item.getItemIds() == null)
				{
					continue;
				}
				for (Integer id : item.getItemIds())
				{
					if (id != null)
					{
						map.computeIfAbsent(id, k -> new ArrayList<>()).add(task);
					}
				}
			}
		}
		needed = map;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.highlightEquipItems())
		{
			return;
		}
		// Same rules as NPC and object outlines (the cogwheel's Outlines setting; see OutlineMode.color).
		Color color = config.taskOutlineMode().color(tasksFor(itemId), plugin, config.taskHighlightColor(),
			config.taskHighlightUnavailableColor());
		if (color == null)
		{
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}

		// An outline around the item's own shape, so nothing covers the item itself.
		BufferedImage outline = outlineFor(itemId, color);
		if (outline != null)
		{
			graphics.drawImage(outline, bounds.x, bounds.y, null);
		}

	}

	private BufferedImage outlineFor(int itemId, Color color)
	{
		String key = itemId + ":" + color.getRGB();
		BufferedImage image = outlines.get(key);
		if (image == null)
		{
			if (outlines.size() > 500)
			{
				outlines.clear();
			}
			image = itemManager.getItemOutline(itemId, 1, color);
			if (image != null)
			{
				outlines.put(key, image);
			}
		}
		return image;
	}

}
