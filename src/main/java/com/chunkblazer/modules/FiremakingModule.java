/*
 * Copyright (c) 2026, btwinnn
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

package com.chunkblazer.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredItem;

/**
 * Module for FIREMAKING completion type tasks ("burn N of item X").
 *
 * <p>A burn is a watched log consumed paired with a Firemaking XP gain, which covers both
 * tinderbox-on-log and bonfires.
 *
 * <ul>
 *   <li><b>Either order, short window.</b> StatChanged can dispatch ahead of
 *       ItemContainerChanged, and a bonfire batch can drain logs faster than XP drops
 *       arrive, so the pairing is symmetric across {@link #MATCH_WINDOW_TICKS}.</li>
 *   <li><b>Unpaired consumption must expire,</b> or logs banked, dropped or sold credit
 *       alongside the next real burn.</li>
 *   <li><b>Logs can be lit on the ground,</b> never passing through the inventory. The
 *       ground item's despawn stands in. See {@link #onItemDespawned}.</li>
 *   <li><b>One XP gain pays for one credit.</b> {@code lastFiremakingXpTick} is cleared
 *       once it has credited something.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class FiremakingModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "FIREMAKING";
	// How far apart (in ticks) the log leaving the inventory and the Firemaking
	// XP gain may land and still count as the same burn. Covers a bonfire batch
	// draining ahead of its XP drops; short enough that logs banked or dropped
	// minutes earlier cannot ride along on an unrelated burn. Matches
	// ConstructionModule/FarmingModule's window.
	private static final int MATCH_WINDOW_TICKS = 5;

	// How close a despawning ground log must be to count as one the player just
	// lit. The fire appears under or beside them; anything further is someone
	// else's item vanishing in the loaded scene.
	private static final int GROUND_LIGHT_MAX_DISTANCE = 2;

	@Inject
	private ItemManager itemManager;

	// Track task-specific data
	// Map: taskId -> (Map: itemId -> required quantity)
	private final Map<String, Map<Integer, Integer>> taskTargetItems = new ConcurrentHashMap<>();

	// Track previous inventory state for detecting consumed items
	private final Map<Integer, Integer> previousInventory = new ConcurrentHashMap<>();

	// Items we're currently watching for (union of all task requirements)
	private final Set<Integer> watchedItemIds = ConcurrentHashMap.newKeySet();

	// Track Firemaking XP for detecting burns
	private int previousFiremakingXp = -1;

	// Watched logs consumed but not yet paired with XP, aged out lazily by
	// expirePendingBurns(). A LIST, not a map by item id: merging would refresh an old
	// batch's timestamp, keeping banked logs live when a later log of that type goes.
	private final List<PendingBurn> pendingBurns = new CopyOnWriteArrayList<>();

	// Tick of the most recent Firemaking XP gain, or -1 if none this session.
	// Lets consumption that arrives AFTER its XP still complete the pair.
	private int lastFiremakingXpTick = -1;

	/** Where a burn candidate's log came from. */
	private enum BurnSource
	{
		/** The log left the player's inventory (tinderbox, bonfire add, or a drop). */
		INVENTORY,
		/** A log on the ground vanished next to the player as the fire caught. */
		GROUND
	}

	/** One watched-log burn candidate, stamped with the tick it happened on. */
	private static final class PendingBurn
	{
		private final int itemId;
		private final int quantity;
		private final int tick;
		private final BurnSource source;

		private PendingBurn(int itemId, int quantity, int tick, BurnSource source)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.tick = tick;
			this.source = source;
		}
	}


	@Inject
	public FiremakingModule()
	{
	}

	@Override
	public String getCompletionType()
	{
		return COMPLETION_TYPE;
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		previousInventory.clear();
		taskTargetItems.clear();
		watchedItemIds.clear();
		pendingBurns.clear();
		previousFiremakingXp = -1;
		lastFiremakingXpTick = -1;
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);


			// Parse required items (logs to burn)
			Map<Integer, Integer> targetItems = new HashMap<>();
			List<RequiredItem> requiredItems = task.getRequiredItems();

			if (requiredItems != null)
			{
				for (RequiredItem item : requiredItems)
				{
					List<Integer> itemIds = item.getItemIds();
					if (itemIds != null)
					{
						for (Integer itemId : itemIds)
						{
							targetItems.put(itemId, item.getRequiredQuantity());
							watchedItemIds.add(itemId);
						}
					}
				}
			}

			taskTargetItems.put(task.getTaskId(), targetItems);

			// Initialize tracking on client thread
			clientThread.invokeLater(() ->
			{
				initializeInventoryTracking();
				initializeXpTracking();
			});
		}
		catch (Exception e)
		{
			log.error("FiremakingModule failed to add a task", e);
		}
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		taskTargetItems.clear();
		watchedItemIds.clear();
		previousInventory.clear();
		pendingBurns.clear();
		lastFiremakingXpTick = -1;
	}

	@Override
	public void checkProgress()
	{
		for (NuzlockeTask task : activeTasks)
		{
			checkTaskProgress(task);
		}
	}

	private void initializeInventoryTracking()
	{
		previousInventory.clear();

		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					int canonicalId = itemManager.canonicalize(item.getId());
					previousInventory.merge(canonicalId, item.getQuantity(), Integer::sum);
				}
			}
		}
	}

	private void initializeXpTracking()
	{
		if (client.getLocalPlayer() != null)
		{
			previousFiremakingXp = client.getSkillExperience(Skill.FIREMAKING);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (activeTasks.isEmpty() || watchedItemIds.isEmpty())
		{
			return;
		}

		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}

		// Build current inventory state
		Map<Integer, Integer> currentInventory = new HashMap<>();
		ItemContainer container = event.getItemContainer();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					int canonicalId = itemManager.canonicalize(item.getId());
					currentInventory.merge(canonicalId, item.getQuantity(), Integer::sum);
				}
			}
		}

		// Check for consumed logs (decrease in quantity)
		int tick = getGameTick();
		boolean consumedAny = false;

		for (int watchedItemId : watchedItemIds)
		{
			int previousCount = previousInventory.getOrDefault(watchedItemId, 0);
			int currentCount = currentInventory.getOrDefault(watchedItemId, 0);

			if (currentCount < previousCount)
			{
				int consumed = previousCount - currentCount;
				consumedAny = true;

				// Hold the consumption until a Firemaking XP gain confirms it
				// was a burn rather than a bank/drop/sale.
				pendingBurns.add(new PendingBurn(watchedItemId, consumed, tick, BurnSource.INVENTORY));
			}
		}

		// Update previous state
		previousInventory.clear();
		previousInventory.putAll(currentInventory);

		// XP-FIRST ordering: the burn's XP already landed, so this consumption
		// is what completes the pair. Without this the credit was dropped
		// outright — the bug that made bonfire burns fail to register.
		if (consumedAny && lastFiremakingXpTick >= 0
			&& tick - lastFiremakingXpTick <= MATCH_WINDOW_TICKS)
		{
			creditPendingBurns();
		}
	}

	/**
	 * Lighting logs on the ground. The log never passes through the inventory (dropped by
	 * someone else, or your own drop when lighting outlasts {@link #MATCH_WINDOW_TICKS}),
	 * so consumption can't see the burn. The despawn lands on the SAME tick the fire
	 * catches.
	 */
	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (activeTasks.isEmpty() || watchedItemIds.isEmpty())
		{
			return;
		}

		int itemId = itemManager.canonicalize(event.getItem().getId());
		if (!watchedItemIds.contains(itemId))
		{
			return;
		}

		// Only logs vanishing at arm's length are ours to claim. Without this a
		// stranger's ground item despawning anywhere in the loaded scene could
		// pair with our own bonfire XP.
		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldLocation() == null
			|| event.getTile().getWorldLocation().distanceTo(local.getWorldLocation()) > GROUND_LIGHT_MAX_DISTANCE)
		{
			return;
		}

		int tick = getGameTick();
		expirePendingBurns();

		// If the log leaving our inventory is still live, this despawn is that
		// same log hitting the floor a moment ago and being lit now — one burn,
		// not two. Once that consumption has expired it can no longer credit
		// anything, so the despawn becomes the only signal and must stand in.
		for (PendingBurn pending : pendingBurns)
		{
			if (pending.itemId == itemId && pending.source == BurnSource.INVENTORY)
			{
				return;
			}
		}

		// Always one: lighting consumes a single log, and logs do not stack, so
		// a despawned stack quantity is never the number burned.
		pendingBurns.add(new PendingBurn(itemId, 1, tick, BurnSource.GROUND));

		if (lastFiremakingXpTick >= 0 && tick - lastFiremakingXpTick <= MATCH_WINDOW_TICKS)
		{
			creditPendingBurns();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.FIREMAKING)
		{
			return;
		}

		if (activeTasks.isEmpty())
		{
			return;
		}

		int currentXp = event.getXp();
		if (previousFiremakingXp < 0)
		{
			previousFiremakingXp = currentXp;
			return;
		}

		int xpGained = currentXp - previousFiremakingXp;
		previousFiremakingXp = currentXp;

		if (xpGained <= 0)
		{
			return;
		}

		lastFiremakingXpTick = getGameTick();

		// CONSUMPTION-FIRST ordering: the traditional tinderbox path, where the
		// log leaves the inventory before the XP lands. A bonfire batch that
		// drained ahead of its XP drops also credits here.
		creditPendingBurns();
	}

	/**
	 * Pair up any un-expired consumption with a Firemaking XP gain and credit it.
	 *
	 * <p>Called from BOTH event handlers, so whichever of the two arrives second
	 * completes the burn. Safe to call speculatively: it no-ops when there is
	 * nothing pending.
	 */
	private void creditPendingBurns()
	{
		expirePendingBurns();

		if (pendingBurns.isEmpty())
		{
			return;
		}

		Map<Integer, Integer> burned = new HashMap<>();
		for (PendingBurn pending : pendingBurns)
		{
			burned.merge(pending.itemId, pending.quantity, Integer::sum);
		}
		pendingBurns.clear();

		// Spend the XP gain that paid for this credit. Otherwise one XP drop credits
		// every later consumption in its window, and a dropped log double-counts: on the
		// drop, then on the ground despawn.
		lastFiremakingXpTick = -1;

		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			checkTaskProgressFromBurn(task, burned);
		}
	}

	/**
	 * Drop consumption that never paired with a Firemaking XP gain.
	 *
	 * <p>Logs banked, dropped, sold or used on something else all look identical
	 * to a burn at the inventory layer. Previously that consumption sat in the
	 * map indefinitely and was credited to the next genuine burn; expiring it
	 * keeps a task honest.
	 */
	private void expirePendingBurns()
	{
		int tick = getGameTick();
		pendingBurns.removeIf(pending -> tick - pending.tick > MATCH_WINDOW_TICKS);
	}

	private void checkTaskProgressFromBurn(NuzlockeTask task, Map<Integer, Integer> consumedLogs)
	{
		Map<Integer, Integer> targetItems = taskTargetItems.get(task.getTaskId());
		if (targetItems == null || targetItems.isEmpty())
		{
			return;
		}

		if (!hasLevelFor(task))
		{
			return;
		}

		int progressIncrement = 0;

		for (Map.Entry<Integer, Integer> consumed : consumedLogs.entrySet())
		{
			int itemId = consumed.getKey();
			int quantity = consumed.getValue();

			if (targetItems.containsKey(itemId))
			{
				progressIncrement += quantity;
			}
		}

		if (progressIncrement > 0)
		{
			int previousProgress = task.getCurrentProgress();
			int newProgress = previousProgress + progressIncrement;
			int required = task.getTargetQuantity();

			task.setCurrentProgress(newProgress);

			sendTaskProgress(task, null, newProgress, required);

			if (completionCallback != null)
			{
				completionCallback.onProgressUpdated(task, newProgress);
			}

			// Check for completion
			if (newProgress >= required && !task.isCompleted())
			{
				task.setCompleted(true);

				sendTaskSuccess(task, null);

				if (completionCallback != null)
				{
					completionCallback.onTaskCompleted(task, newProgress);
				}

				// Clean up
				taskTargetItems.remove(task.getTaskId());
				activeTasks.remove(task);
				rebuildWatchedItems();
			}
		}
	}

	/**
	 * The task's Firemaking level, boosts included (the game accepts boosts for
	 * lighting logs). A log dropped just before another one is lit leaves the
	 * inventory inside the same match window, so it was credited alongside the
	 * real burn — a willow dropped at level 26 counted as a willow burn.
	 */
	private boolean hasLevelFor(NuzlockeTask task)
	{
		return client.getBoostedSkillLevel(Skill.FIREMAKING) >= task.getLevelRequirement();
	}

	private void checkTaskProgress(NuzlockeTask task)
	{
		// For firemaking, progress is tracked via burns, not current inventory
		// This method is for consistency with other modules
	}

	private void rebuildWatchedItems()
	{
		watchedItemIds.clear();
		for (Map<Integer, Integer> items : taskTargetItems.values())
		{
			watchedItemIds.addAll(items.keySet());
		}
	}
}
