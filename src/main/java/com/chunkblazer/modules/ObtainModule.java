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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;
import net.runelite.client.game.ItemManager;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredItem;

/**
 * Module for handling OBTAIN completion type tasks.
 * Tracks item acquisition in inventory - doesn't matter HOW the player gets the items,
 * just that they have them (bought from shop, monster drop, crafted, etc.).
 */
@Slf4j
@Singleton
public class ObtainModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "OBTAIN";

	// All completion types this module handles (production-based skilling + obtain)
	private static final Set<String> HANDLED_TYPES = Set.of(
		"OBTAIN",
		"COOKING",
		"CRAFTING",
		"SMITHING",
		"MINING",
		"WOODCUTTING",
		"FISHING",
		"FLETCHING",
		"HERBLORE",
		"RUNECRAFTING",
		"HUNTER"
	);
	@Inject
	private ItemManager itemManager;

	/**
	 * One conceptual requirement, from one {@code required_items} entry (e.g. "any
	 * elemental staff"). Variants ({@link #variantIds}) are OR'd toward the slot's
	 * quantity; slots are AND'd across the task.
	 */
	static final class Slot
	{
		final Set<Integer> variantIds;
		final int requiredQuantity;

		Slot(Set<Integer> variantIds, int requiredQuantity)
		{
			this.variantIds = variantIds;
			this.requiredQuantity = requiredQuantity;
		}
	}

	// Track task-specific data: per-task ordered list of slots.
	private final Map<String, List<Slot>> taskSlots = new ConcurrentHashMap<>();

	// Items we're currently watching for (union of all task requirements)
	// Use thread-safe set to avoid ConcurrentModificationException
	private final Set<Integer> watchedItemIds = ConcurrentHashMap.newKeySet();

	// Per-task per-item INVENTORY-only counts of watched items, for skilling tasks. A
	// rise on a matching XP drop is credited; changes without one (banking, dropping, GE
	// buys, pickups) just slide the snapshot. OBTAIN tasks (no skill) use the
	// inv+bank+equip count via checkTaskProgress instead.
	private final Map<String, Map<Integer, Integer>> inventoryHeldSnapshot = new ConcurrentHashMap<>();

	// Last observed XP per skill, used to detect XP gains in onStatChanged.
	// Seeded from initial StatChanged events; null for a skill we haven't seen yet.
	private final Map<Skill, Integer> previousXp = new ConcurrentHashMap<>();

	// Skills that gained XP this tick, so skilling credit doesn't depend on event order:
	// gathering skills fire XP then item, production skills (crafting, fletching) item
	// then XP. Cleared at the end of every tick in onGameTick.
	private final Set<Skill> skillsXpGainedThisTick = ConcurrentHashMap.newKeySet();

	// Cooking tasks already credited this tick (see the +1 cap in creditSkillingDelta).
	private final Set<String> cookedThisTick = ConcurrentHashMap.newKeySet();


	@Inject
	public ObtainModule()
	{
	}

	@Override
	public String getCompletionType()
	{
		return COMPLETION_TYPE;
	}

	@Override
	public boolean canHandle(NuzlockeTask task)
	{
		// Handle OBTAIN and all production-based skilling types
		// (COOKING, CRAFTING, SMITHING, MINING, WOODCUTTING, FISHING, FLETCHING, HERBLORE, RUNECRAFTING, HUNTER)
		String type = task.getCompletionType();
		String category = task.getCategory();

		return (type != null && HANDLED_TYPES.contains(type.toUpperCase())) ||
			(category != null && HANDLED_TYPES.contains(category.toUpperCase()));
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
		taskSlots.clear();
		watchedItemIds.clear();
		inventoryHeldSnapshot.clear();
		previousXp.clear();
		cookedThisTick.clear();
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);


			// Parse required items from task into one Slot per RequiredItem.
			// Within a Slot, item_ids are OR'd (any variant counts); across
			// Slots, the requirements are AND'd (all slots must be filled).
			List<Slot> slots = new ArrayList<>();
			List<RequiredItem> requiredItems = task.getRequiredItems();


			if (requiredItems != null)
			{
				for (RequiredItem item : requiredItems)
				{
					List<Integer> itemIds = item.getItemIds();
					int requiredQty = item.getRequiredQuantity();

					if (itemIds != null && !itemIds.isEmpty())
					{
						Set<Integer> variantIds = new HashSet<>(itemIds);
						slots.add(new Slot(variantIds, requiredQty));
						watchedItemIds.addAll(variantIds);
					}
					else
					{
						log.warn("Obtain task: required item has no ids");
					}
				}
			}
			else
			{
				log.warn("Obtain task: no required_items");
			}

			taskSlots.put(task.getTaskId(), slots);
			// Skilling tasks: seed the inventory-only snapshot on the client thread
			// (countIn() asserts it). Until then an XP drop sees no prior count and skips
			// crediting. Items already held at task load are NOT credited. OBTAIN
			// (no-skill) tasks also run checkTaskProgress, so anything held in
			// inv+bank+equip counts immediately.
			final List<Slot> slotsRef = slots;
			final Skill xpSkill = skillForCompletionType(task.getCompletionType());
			// invokeLater(BooleanSupplier): returning false retries next tick.
			//
			// THIS MUST NOT RUN BEFORE THE LOGIN SYNC COMPLETES. At LOGGED_IN neither the
			// inventory nor the skill table is necessarily populated. Seeding from an
			// empty client gives a 0 snapshot and previousXp 0, so on a cold start the
			// login StatChanged passes the "first sighting" guard as a huge gain, and the
			// real inventory then credits as a delta of everything held.
			clientThread.invokeLater(() ->
			{
				if (client.getGameState() != GameState.LOGGED_IN
					|| client.getItemContainer(InventoryID.INV) == null
					// Hitpoints XP is never 0 on a real account (a fresh level 3 has
					// 1154), so this is a cheap probe for "the skill table has synced".
					|| client.getSkillExperience(Skill.HITPOINTS) <= 0)
				{
					return false;
				}

				inventoryHeldSnapshot.put(task.getTaskId(), snapshotInventoryCounts(slotsRef));
				if (xpSkill == null)
				{
					// OBTAIN (no skill gate): credit anything already held in inv+bank+equip.
					checkTaskProgress(task);
				}
				else
				{
					// Seed the XP baseline from CURRENT xp. Otherwise onStatChanged
					// swallows the first drop as a "first sighting" (prevXp == null), and
					// a quantity-1 skilling task never completes.
					previousXp.put(xpSkill, client.getSkillExperience(xpSkill));
				}
				return true;
			});
		}
		catch (Exception e)
		{
			log.error("ObtainModule failed to add a task", e);
		}
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		taskSlots.clear();
		watchedItemIds.clear();
		inventoryHeldSnapshot.clear();
	}

	@Override
	public void checkProgress()
	{
		// Check progress for all active tasks
		for (NuzlockeTask task : activeTasks)
		{
			checkTaskProgress(task);
		}
	}

	/**
	 * Check progress for an OBTAIN (no-skill) task. Held = inv+bank+equip; the
	 * player either has the item or not. Skilling tasks are routed through
	 * onStatChanged instead — see creditSkillingDelta — so this method early-exits
	 * for them.
	 */
	private void checkTaskProgress(NuzlockeTask task)
	{
		if (task == null)
		{
			return;
		}

		// Skilling tasks credit only when an item appears in inventory in the
		// same tick as a matching-skill XP drop (see onStatChanged). Held-based
		// progress is not used — bank stockpiles, GE buys, ground pickups, etc.
		// don't count for "Mine 5 mithril ore". Bail here; their state is owned
		// by the inventoryHeldSnapshot path.
		Skill taskSkill = skillForCompletionType(task.getCompletionType());
		if (taskSkill != null)
		{
			return;
		}

		List<Slot> slots = taskSlots.get(task.getTaskId());
		if (slots == null || slots.isEmpty())
		{
			return;
		}

		int totalRequired = 0;
		int totalObtained = 0;

		// Build details about each slot for logging.
		// For each slot we sum the player's held count ACROSS its variant IDs
		// (variants OR'd) and cap at the slot's required quantity. Then sum
		// per-slot contributions for the task total. This is the OR-within-
		// slot, AND-across-slots semantic — e.g. "Obtain Prospector Set" has
		// 4 slots, each accepts any variant of that piece.
		StringBuilder itemDetails = new StringBuilder();
		for (Slot slot : slots)
		{
			int slotHeld = 0;
			for (Integer variantId : slot.variantIds)
			{
				slotHeld += getItemCount(variantId);
			}
			int countForSlot = Math.min(slotHeld, slot.requiredQuantity);
			totalRequired += slot.requiredQuantity;
			totalObtained += countForSlot;

			if (itemDetails.length() > 0)
			{
				itemDetails.append(", ");
			}
			// One representative name per slot rather than every variant id, so a
			// recoloured set (e.g. Graceful) doesn't spam a long variant breakdown.
			String slotName = slot.variantIds.isEmpty() ? "item"
				: getItemName(slot.variantIds.iterator().next());
			itemDetails.append(slotName).append(" ")
				.append(countForSlot).append("/").append(slot.requiredQuantity);
		}

		// Floor progress at the previously-saved value so we never regress across sessions
		// or before the cumulative map is fully reseeded.
		int previousProgress = task.getCurrentProgress();
		int newProgress = Math.max(previousProgress, totalObtained);
		task.setCurrentProgress(newProgress);

		// Send progress chat message if progress increased
		if (newProgress > previousProgress)
		{
			String details = "Obtained: " + itemDetails.toString();
			sendTaskProgress(task, details, newProgress, totalRequired);

			if (completionCallback != null)
			{
				completionCallback.onProgressUpdated(task, newProgress);
			}
		}


		// Check for completion
		if (newProgress >= totalRequired && !task.isCompleted())
		{
			task.setCompleted(true);

			// Send success chat message. The task name already states what was
			// obtained; the per-slot detail line would just repeat it (and for a
			// recoloured set it used to spam every variant), so omit it.
			sendTaskSuccess(task, null);

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up task tracking
			taskSlots.remove(task.getTaskId());
			inventoryHeldSnapshot.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatchedItems();
		}
	}

	/**
	 * Total count of an item across inventory + bank + equipment. Dropped items are NOT
	 * counted, which defeats the drop-and-pick-up cheat. Uses canonicalized IDs so noted
	 * items count.
	 */
	private int getItemCount(int itemId)
	{
		return countAll(OWNED, itemId, false);
	}

	// Bags that catch items before the inventory sees them: a Master Farmer's seeds go
	// straight into an open seed box, so an inventory-only count never moved. Counted
	// with the inventory for production; a move between bag and pack nets to zero.
	private static final Set<Integer> CARRIED = Set.of(InventoryID.INV, InventoryID.SEED_BOX, InventoryID.LOOTING_BAG,
		InventoryID.TACKLE_BOX, InventoryID.FORESTRY_KIT, InventoryID.HUNTSMANS_KIT);

	// Everything that counts as owning an item: carried, worn, banked, the seed vault.
	private static final Set<Integer> OWNED = Set.of(InventoryID.INV, InventoryID.SEED_BOX, InventoryID.LOOTING_BAG,
		InventoryID.TACKLE_BOX, InventoryID.FORESTRY_KIT, InventoryID.HUNTSMANS_KIT,
		InventoryID.WORN, InventoryID.BANK, InventoryID.SEED_VAULT);

	// Stores that keep a count instead of the items: bowstrings spun straight onto a spool.
	private static final Map<Integer, Integer> STORED = Map.of(ItemID.BOW_STRING, VarbitID.BOWSTRING_SPOOL_CHARGES);

	/** seenOnly skips bags whose contents haven't arrived this session (see seenBags). */
	private int countAll(Set<Integer> containerIds, int itemId, boolean seenOnly)
	{
		Integer stored = STORED.get(itemId);
		int total = stored != null ? client.getVarbitValue(stored) : 0;
		for (int id : containerIds)
		{
			if (!seenOnly || id == InventoryID.INV || seenBags.contains(id))
			{
				total += countIn(id, itemId);
			}
		}
		return total;
	}


	private int countIn(int containerId, int itemId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return 0;
		}
		int count = 0;
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0)
			{
				int canonicalId = itemManager.canonicalize(item.getId());
				if (canonicalId == itemId)
				{
					count += item.getQuantity();
				}
			}
		}
		return count;
	}

	/**
	 * Rebuild the set of watched item IDs from all active tasks.
	 */
	private void rebuildWatchedItems()
	{
		watchedItemIds.clear();
		for (List<Slot> slots : taskSlots.values())
		{
			for (Slot slot : slots)
			{
				watchedItemIds.addAll(slot.variantIds);
			}
		}
	}

	/**
	 * Map a task completion type to the OSRS skill whose XP drops should
	 * verify the player actually performed the activity. Returns null for
	 * non-skilling types (OBTAIN), where no XP gating applies.
	 */
	private static Skill skillForCompletionType(String completionType)
	{
		if (completionType == null)
		{
			return null;
		}
		switch (completionType.toUpperCase())
		{
			case "COOKING":      return Skill.COOKING;
			case "CRAFTING":     return Skill.CRAFTING;
			case "SMITHING":     return Skill.SMITHING;
			case "MINING":       return Skill.MINING;
			case "WOODCUTTING":  return Skill.WOODCUTTING;
			case "FISHING":      return Skill.FISHING;
			case "FLETCHING":    return Skill.FLETCHING;
			case "HERBLORE":     return Skill.HERBLORE;
			case "RUNECRAFTING": return Skill.RUNECRAFT;
			case "HUNTER":       return Skill.HUNTER;
			default:             return null;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int newXp = event.getXp();
		Integer prevXp = previousXp.put(skill, newXp);

		// First sighting of this skill (e.g. login/initial sync) — record baseline only.
		if (prevXp == null)
		{
			return;
		}
		// Stat changes can fire for level/boost recalcs without an XP gain. Filter.
		if (newXp <= prevXp)
		{
			return;
		}
		// Mark this skill as having gained XP this tick so onItemContainerChanged
		// can credit a production item that lands on the same tick even if its
		// inventory event fired before this XP event.
		skillsXpGainedThisTick.add(skill);
		if (activeTasks.isEmpty())
		{
			return;
		}

		// For each active task matching this XP drop's skill, a watched item's INVENTORY
		// rise since the last snapshot was just produced: credit +delta.
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Skill taskSkill = skillForCompletionType(task.getCompletionType());
			if (taskSkill != skill)
			{
				continue;
			}
			creditSkillingDelta(task, skill);
		}
	}

	/**
	 * Compute per-item inventory delta vs the stored snapshot for this task,
	 * credit any positive delta to the task's progress, fire chat + completion,
	 * and roll the snapshot forward.
	 */
	private void creditSkillingDelta(NuzlockeTask task, Skill skill)
	{
		List<Slot> slots = taskSlots.get(task.getTaskId());
		if (slots == null || slots.isEmpty())
		{
			return;
		}
		Map<Integer, Integer> snapshot = inventoryHeldSnapshot.get(task.getTaskId());
		if (snapshot == null)
		{
			// Deferred init in addActiveTask hasn't run yet — skip this drop
			// rather than risk crediting bank-stocked items as a "delta from 0".
			return;
		}
		// Nothing is cooked, smithed, fletched or crafted with the bank open, so items
		// arriving then were withdrawn. Without this, a withdrawal in the same tick as a
		// skilling XP drop counted as production. Roll the snapshot past them instead.
		if (isBankOpen())
		{
			inventoryHeldSnapshot.put(task.getTaskId(), snapshotInventoryCounts(slots));
			return;
		}

		int totalDelta = 0;
		int totalRequired = 0;
		int lastGainedId = -1;
		StringBuilder details = new StringBuilder();
		Map<Integer, Integer> nextSnapshot = new HashMap<>();
		// Per-slot delta = this tick's PRODUCTION, capped at the slot's requirement:
		//   slotDelta = clamp(currSumOfVariants - prevSumOfVariants, 0..slot.required)
		// Use the raw rise, not min(curr,req) - min(prev,req), or items already held
		// suppress the credit. The XP gate proves the skilling, and the task-level cap
		// below stops over-crediting. The per-slot cap keeps one over-producing tick from
		// completing a multi-item task.
		for (Slot slot : slots)
		{
			totalRequired += slot.requiredQuantity;
			int prevSum = 0;
			int currSum = 0;
			StringBuilder slotGains = new StringBuilder();
			for (Integer variantId : slot.variantIds)
			{
				int prev = snapshot.getOrDefault(variantId, 0);
				int curr = countAll(CARRIED, variantId, true);
				nextSnapshot.put(variantId, curr);
				prevSum += prev;
				currSum += curr;
				if (curr > prev)
				{
					int variantGain = curr - prev;
					lastGainedId = variantId;
					if (slotGains.length() > 0)
					{
						slotGains.append(", ");
					}
					slotGains.append("+").append(variantGain).append(" ").append(getItemName(variantId));
				}
			}
			int slotDelta = Math.min(Math.max(currSum - prevSum, 0), slot.requiredQuantity);
			if (slotDelta > 0)
			{
				totalDelta += slotDelta;
				if (details.length() > 0)
				{
					details.append("; ");
				}
				details.append(slotGains);
			}
		}
		// Always slide the snapshot forward, even on no delta — keeps the gate
		// honest if inventory dropped between drops (e.g. banked ores).
		inventoryHeldSnapshot.put(task.getTaskId(), nextSnapshot);

		// Cooking makes at most one item per tick (make-all at a range, wine, 1-tick
		// karambwans), so anything more in one tick arrived some other way, such as a
		// shop, trade or pickup landing in the same tick as the XP.
		if (skill == Skill.COOKING && totalDelta > 0)
		{
			if (!cookedThisTick.add(task.getTaskId()))
			{
				return;
			}
			if (totalDelta > 1)
			{
				totalDelta = 1;
				details = new StringBuilder("+1 ").append(getItemName(lastGainedId));
			}
		}

		if (totalDelta == 0)
		{
			return;
		}
		creditedThisTick.merge(task.getTaskId(), totalDelta, Integer::sum);
		addProgress(task, totalDelta, totalRequired, details.toString());
	}

	private void addProgress(NuzlockeTask task, int amount, int totalRequired, String details)
	{
		int previousProgress = task.getCurrentProgress();
		int newProgress = Math.min(totalRequired, previousProgress + amount);
		if (newProgress <= previousProgress)
		{
			return;
		}
		task.setCurrentProgress(newProgress);
		sendTaskProgress(task, details, newProgress, totalRequired);
		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		if (newProgress >= totalRequired && !task.isCompleted())
		{
			task.setCompleted(true);
			sendTaskSuccess(task, "Task complete!");
			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}
			taskSlots.remove(task.getTaskId());
			inventoryHeldSnapshot.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatchedItems();
		}
	}

	private boolean isBankOpen()
	{
		Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		return bank != null && !bank.isHidden();
	}

	/**
	 * Snapshot the current carried count (inventory plus carried bags) of every
	 * watched item for a task. Used as the baseline against which onStatChanged credits a delta.
	 * Must run on the client thread — countIn() asserts it.
	 */
	private Map<Integer, Integer> snapshotInventoryCounts(List<Slot> slots)
	{
		Map<Integer, Integer> snap = new HashMap<>();
		for (Slot slot : slots)
		{
			for (Integer variantId : slot.variantIds)
			{
				snap.put(variantId, countAll(CARRIED, variantId, true));
			}
		}
		return snap;
	}

	// Gathering messages, which the game still sends when the item goes into a fish barrel,
	// log basket, gem or coal bag instead of the pack (the wording Hub plugins that track
	// those bags count from). Group 1 is the item as the game names it.
	private static final Pattern GAIN = Pattern.compile(
		"you (?:catch (?:an?|some) (?:raw )?|get some |manage to mine some |just (?:found|mined) an? )([a-z' -]+?)[.!].*");
	private static final Pattern EXTRA = Pattern.compile(".*(enabled you to (catch|chop) an extra|provides you with an additional).*");
	private final Map<String, Integer> gainedThisTick = new HashMap<>();
	private final Map<String, Integer> creditedThisTick = new HashMap<>();
	private String lastGain;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.SPAM && event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		String msg = Text.removeTags(event.getMessage()).toLowerCase();
		Matcher m = GAIN.matcher(msg);
		String name = m.matches() ? m.group(1) : EXTRA.matcher(msg).matches() ? lastGain : null;
		if (name != null)
		{
			lastGain = name;
			gainedThisTick.merge(name, 1, Integer::sum);
		}
	}

	/**
	 * A gathering task whose skill gained XP this tick gets whatever its messages show
	 * beyond what the pack already credited, so an item caught into a bag still counts
	 * and one that reached the pack isn't counted twice.
	 */
	private void creditGains()
	{
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Skill skill = skillForCompletionType(task.getCompletionType());
			List<Slot> slots = taskSlots.get(task.getTaskId());
			if (skill == null || slots == null || !skillsXpGainedThisTick.contains(skill))
			{
				continue;
			}
			int got = 0;
			int required = 0;
			for (Slot slot : slots)
			{
				required += slot.requiredQuantity;
				int slotGot = 0;
				for (Integer id : slot.variantIds)
				{
					slotGot = Math.max(slotGot,
						gainedThisTick.getOrDefault(getItemName(id).toLowerCase().replaceAll("^(raw|uncut) | ore$", ""), 0));
				}
				got += Math.min(slotGot, slot.requiredQuantity);
			}
			int extra = got - creditedThisTick.getOrDefault(task.getTaskId(), 0);
			if (extra > 0)
			{
				addProgress(task, extra, required, null);
			}
		}
	}

	private void rollSnapshots()
	{
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			List<Slot> slots = taskSlots.get(task.getTaskId());
			if (slots != null && skillForCompletionType(task.getCompletionType()) != null)
			{
				inventoryHeldSnapshot.put(task.getTaskId(), snapshotInventoryCounts(slots));
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!gainedThisTick.isEmpty())
		{
			creditGains();
		}
		gainedThisTick.clear();
		creditedThisTick.clear();

		// GameTick fires after this tick's StatChanged / ItemContainerChanged, so
		// crediting is done. Roll snapshots to "now" so non-skilling changes (banking,
		// GE, pickups) can't be miscredited next tick.
		rollSnapshots();
		skillsXpGainedThisTick.clear();
		cookedThisTick.clear();


	}

	// Carried bags whose contents this session has already seen; see onItemContainerChanged.
	private final Set<Integer> seenBags = ConcurrentHashMap.newKeySet();

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			seenBags.clear();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (activeTasks.isEmpty() || watchedItemIds.isEmpty())
		{
			return;
		}

		int containerId = event.getContainerId();
		if (!OWNED.contains(containerId))
		{
			return;
		}


		// OBTAIN (no-skill) tasks — re-derive progress from inv+bank+equip.
		// Skilling tasks early-exit inside checkTaskProgress, so this loop only
		// does work for the OBTAIN ones.
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			checkTaskProgress(task);
		}

		// Item and XP order varies by skill (see skillsXpGainedThisTick). If this skill
		// also gained XP this tick, the item is genuine production: credit it now.
		// Otherwise leave the snapshot: onStatChanged credits it when the XP arrives, or
		// the end-of-tick slide rolls past a non-skilling change.
		if (CARRIED.contains(containerId))
		{
			// A bag's first contents this session arrive whole. They're the baseline, not
			// production, or one pickpocket into a full seed box would credit every seed in it.
			if (containerId != InventoryID.INV && seenBags.add(containerId))
			{
				rollSnapshots();
				return;
			}
			creditSkilledThisTick();
		}
	}

	/** A spool's count moved: the same as a bag changing, for obtain and skilling tasks. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!activeTasks.isEmpty() && STORED.containsValue(event.getVarbitId()))
		{
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				checkTaskProgress(task);
			}
			creditSkilledThisTick();
		}
	}

	private void creditSkilledThisTick()
	{
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Skill taskSkill = skillForCompletionType(task.getCompletionType());
			if (taskSkill != null && skillsXpGainedThisTick.contains(taskSkill))
			{
				creditSkillingDelta(task, taskSkill);
			}
		}
	}

	/**
	 * Get item name from ID.
	 * Note: This may not work on non-client threads, so we fall back to just the ID.
	 */
	private String getItemName(int itemId)
	{
		try
		{
			// Check if we're on the client thread
			if (client.isClientThread())
			{
				return itemManager.getItemComposition(itemId).getName();
			}
			else
			{
				// Not on client thread - just return the ID
				return "Item#" + itemId;
			}
		}
		catch (Exception e)
		{
			return "Item#" + itemId;
		}
	}

	// ==================== CHAT MESSAGE METHODS ====================

}
