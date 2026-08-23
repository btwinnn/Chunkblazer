package com.chunkblazer.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
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

	// Chat colors for ChunkBlazer messages (matching NPCKillModule)
	private static final String COLOR_BLUE = "3366ff";        // [ChunkBlazer] branding
	private static final String COLOR_DARK_BLUE = "1a5276";   // Task Success (dark blue, readable)
	private static final String COLOR_DARK_GREEN = "228b22";  // Task Progress
	private static final String COLOR_BLACK = "000000";       // Task name text

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	/**
	 * One "slot" of a task's required items. A slot represents a single
	 * conceptual requirement (e.g. "the helmet of the Prospector set" or
	 * "any elemental staff"). Variants inside the slot are OR'd: holding
	 * any one of them counts toward the slot's quantity. Slots are AND'd
	 * across the task: a multi-piece set requires all slots filled.
	 *
	 * <p>Mirrors the JSON shape: each entry in {@code required_items}
	 * becomes one slot, with {@code item_ids} → {@link #variantIds}.
	 *
	 * <p>Why this exists: the previous flat {@code Map<itemId, qty>}
	 * model AND'd everything — "Obtain an Elemental Staff" with 4
	 * variant IDs at qty 1 each demanded all 4 staves, not any one.
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

	// Per-task per-item snapshot of the current INVENTORY-only count of each
	// watched item. Used by skilling tasks: when a matching-skill XP drop fires
	// and a watched item count in inventory has increased since the snapshot,
	// the +delta is credited directly to task progress. The snapshot then moves
	// to current. Inventory changes that DON'T coincide with a matching XP drop
	// (banking, dropping, GE buys, ground pickups) just slide the snapshot to
	// current — no progress credit. This is the "you mined an ore = +1" model:
	// the only way to get an XP drop in Mining is to actually mine.
	// OBTAIN tasks (no associated skill) ignore this map and use the
	// inv+bank+equip held count via checkTaskProgress instead.
	private final Map<String, Map<Integer, Integer>> inventoryHeldSnapshot = new ConcurrentHashMap<>();

	// Last observed XP per skill, used to detect XP gains in onStatChanged.
	// Seeded from initial StatChanged events; null for a skill we haven't seen yet.
	private final Map<Skill, Integer> previousXp = new ConcurrentHashMap<>();

	// Skills that gained XP during the CURRENT game tick. This makes the skilling
	// credit order-independent: a produced item is credited whether the matching
	// StatChanged or the ItemContainerChanged fires first, as long as both happen
	// on the same tick (gathering skills fire XP-then-item; production skills like
	// crafting/fletching fire item-then-XP — the old onStatChanged-only path only
	// caught the former). Cleared at the end of every tick in onGameTick.
	private final Set<Skill> skillsXpGainedThisTick = ConcurrentHashMap.newKeySet();


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
						log.warn("      >>> WARNING: itemIds is NULL/empty for this RequiredItem — slot ignored!");
					}
				}
			}
			else
			{
				log.warn("  >>> WARNING: No required_items defined for this OBTAIN task!");
			}

			taskSlots.put(task.getTaskId(), slots);
			// Skilling tasks: track inventory-only counts of watched items so we
			// can detect "you produced one" deltas in onStatChanged. Seeded by
			// the deferred client-thread block below — getItemCount() / countIn()
			// require the client thread. Until the seed runs, an empty snapshot
			// means any XP drop that fires would see "no prior count" and skip
			// crediting (safe default).

			// Run initial setup on the client thread. countIn() asserts the client
			// thread. Seed the inventory-only snapshot so future XP drops compare
			// against today's starting state — a watched item already in inventory
			// at task-load time is NOT credited (no XP drop produced it now).
			// For OBTAIN (no-skill) tasks, also run checkTaskProgress so anything
			// the player already holds in inv+bank+equip (e.g. a forestry kit
			// stockpiled in the bank) counts immediately.
			final List<Slot> slotsRef = slots;
			final Skill xpSkill = skillForCompletionType(task.getCompletionType());
			// invokeLater(BooleanSupplier): returning false retries next tick.
			//
			// THIS MUST NOT RUN BEFORE THE LOGIN SYNC COMPLETES. Both values seeded
			// below are read straight off the client, and at LOGGED_IN neither the
			// inventory container nor the skill table is necessarily populated yet.
			// Seeding from an empty client produced a false completion on every cold
			// start (FullOfSodium, 2026-07-19: "log out, CLOSE the client, log back
			// in" completed runecraft tasks for runes he already held):
			//
			//   1. snapshotInventoryCounts() saw a null/empty inventory -> snapshot 0
			//      for every watched item.
			//   2. getSkillExperience() returned 0 -> previousXp seeded to 0.
			//   3. The login StatChanged then arrived with the player's real XP.
			//      Because previousXp was 0 rather than null, it passed the
			//      "first sighting" guard in onStatChanged and looked like a
			//      genuine gain of the player's entire lifetime XP, which flagged
			//      skillsXpGainedThisTick.
			//   4. The real inventory arrived; delta vs the 0 snapshot equalled
			//      everything held, and the XP flag from (3) let it be credited.
			//
			// Both halves had to be wrong for the bug to fire, which is why it only
			// reproduced on a cold client (a warm relog keeps previousXp in memory).
			clientThread.invokeLater(() ->
			{
				if (client.getGameState() != GameState.LOGGED_IN
					|| client.getItemContainer(InventoryID.INVENTORY) == null
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
					// Seed the XP baseline for this skill from the player's CURRENT xp so
					// the FIRST xp drop after a (re)load produces a real delta. previousXp
					// is cleared on startUp and otherwise seeded lazily from StatChanged, so
					// without this the first skilling action is swallowed as a "first
					// sighting" baseline (onStatChanged returns at prevXp == null) — and a
					// quantity-1 skilling task (e.g. Spin a Ball of Wool, Fletch a Shortbow(u))
					// consumes the single action it needs and never completes.
					previousXp.put(xpSkill, client.getSkillExperience(xpSkill));
				}
				return true;
			});
		}
		catch (Exception e)
		{
			log.error(">>> ObtainModule.addActiveTask() EXCEPTION: ", e);
		}
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		// For legacy single-task support
		super.onTaskAssigned(task);
		addActiveTask(task);
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
	 * Total count of an item across inventory + bank + equipment.
	 *
	 * <p>Why all three: progress for OBTAIN-style tasks is "items the player
	 * actually possesses". Dropped items are intentionally NOT counted, which
	 * defeats the drop-and-pick-up cheat — the count can't be inflated by
	 * dropping then re-collecting because the dropped item wasn't in any of
	 * these containers in the first place. Banking is preserved because banked
	 * items still belong to the player.
	 *
	 * <p>Uses canonicalized IDs so noted items count toward the requirement.
	 */
	private int getItemCount(int itemId)
	{
		return countIn(InventoryID.INVENTORY, itemId)
			+ countIn(InventoryID.BANK, itemId)
			+ countIn(InventoryID.EQUIPMENT, itemId);
	}

	private int countIn(InventoryID containerId, int itemId)
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

		// For every active task whose skill matches this XP drop, look at each
		// watched item: if its INVENTORY count went up since the last snapshot,
		// the player just produced one — credit +delta to task progress. The
		// snapshot then moves to current. Inventory items already in the bank
		// or equipment, or that arrived without a matching XP drop (banking,
		// GE buys, pickups), don't count. This is the "+1 progress when an
		// ore lands in your inventory at the same time a Mining XP drop fires"
		// model — the only way an XP drop in skill X reaches you is by doing
		// skill X.
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

		int totalDelta = 0;
		int totalRequired = 0;
		StringBuilder details = new StringBuilder();
		Map<Integer, Integer> nextSnapshot = new HashMap<>();
		// Per-slot delta = the PRODUCTION this tick (how much the inventory rose),
		// capped at the slot's requirement:
		//   slotDelta = clamp(currSumOfVariants - prevSumOfVariants, 0..slot.required)
		// The snapshot is rolled forward every tick (and after every credit), so
		// prevSum is last tick's count and (currSum - prevSum) is exactly what was
		// produced this tick. Crucially this uses the RAW rise, not
		// min(curr,req) - min(prev,req): items the player already HELD when the task
		// started no longer suppress the credit. Before, spinning a 2nd ball of wool
		// while already holding one gave min(2,1)-min(1,1)=0 and never completed; now
		// it credits the +1 you just made. The XP-drop gate already proves you skilled
		// it, and the task-level cap below still prevents over-crediting past target.
		// Per-slot cap keeps a single over-producing tick from completing a
		// multi-item task on one item alone.
		for (Slot slot : slots)
		{
			totalRequired += slot.requiredQuantity;
			int prevSum = 0;
			int currSum = 0;
			StringBuilder slotGains = new StringBuilder();
			for (Integer variantId : slot.variantIds)
			{
				int prev = snapshot.getOrDefault(variantId, 0);
				int curr = countIn(InventoryID.INVENTORY, variantId);
				nextSnapshot.put(variantId, curr);
				prevSum += prev;
				currSum += curr;
				if (curr > prev)
				{
					int variantGain = curr - prev;
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

		if (totalDelta == 0)
		{
			return;
		}

		int previousProgress = task.getCurrentProgress();
		int newProgress = Math.min(totalRequired, previousProgress + totalDelta);
		if (newProgress <= previousProgress)
		{
			return;
		}
		task.setCurrentProgress(newProgress);
		sendTaskProgress(task, details.toString(), newProgress, totalRequired);
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

	/**
	 * Snapshot the current INVENTORY-only count of every watched item for a
	 * task. Used as the baseline against which onStatChanged credits a delta.
	 * Must run on the client thread — countIn() asserts it.
	 */
	private Map<Integer, Integer> snapshotInventoryCounts(List<Slot> slots)
	{
		Map<Integer, Integer> snap = new HashMap<>();
		for (Slot slot : slots)
		{
			for (Integer variantId : slot.variantIds)
			{
				snap.put(variantId, countIn(InventoryID.INVENTORY, variantId));
			}
		}
		return snap;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{

		// End-of-tick reconciliation for skilling tasks. GameTick fires after this
		// tick's StatChanged / ItemContainerChanged, so all production crediting for
		// the tick has already happened. Roll each skilling task's inventory snapshot
		// forward to "now" — this moves the baseline past any non-skilling inventory
		// change (banking, GE, pickups) that didn't gain XP, so it can't be
		// miscredited next tick. Then clear the per-tick XP-gain markers.
		if (!activeTasks.isEmpty())
		{
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				if (skillForCompletionType(task.getCompletionType()) == null)
				{
					continue; // OBTAIN (no-skill) tasks don't use the snapshot.
				}
				List<Slot> slots = taskSlots.get(task.getTaskId());
				if (slots != null)
				{
					inventoryHeldSnapshot.put(task.getTaskId(), snapshotInventoryCounts(slots));
				}
			}
		}
		skillsXpGainedThisTick.clear();


	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (activeTasks.isEmpty() || watchedItemIds.isEmpty())
		{
			return;
		}

		int containerId = event.getContainerId();
		boolean isRelevant = containerId == InventoryID.INVENTORY.getId()
			|| containerId == InventoryID.BANK.getId()
			|| containerId == InventoryID.EQUIPMENT.getId();

		if (!isRelevant)
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

		// Skilling tasks: PRODUCTION skills (crafting/fletching/cooking/smithing)
		// update the inventory BEFORE the XP event fires this tick — the opposite
		// of gathering skills (mining/woodcutting/fishing), where the XP fires
		// first. The onStatChanged path only catches the XP-first order. So here,
		// if this skill ALSO gained XP this tick, the item that just landed is a
		// genuine production — credit the delta now. If no XP has fired yet this
		// tick, leave the snapshot alone: either onStatChanged will credit it when
		// the XP arrives later this same tick, or it was a non-skilling change
		// (banking/GE/pickup) that the end-of-tick slide in onGameTick rolls past.
		if (containerId == InventoryID.INVENTORY.getId())
		{
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				Skill taskSkill = skillForCompletionType(task.getCompletionType());
				if (taskSkill == null)
				{
					continue;
				}
				if (skillsXpGainedThisTick.contains(taskSkill))
				{
					creditSkillingDelta(task, taskSkill);
				}
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

	/**
	 * Send a task progress message to the player's chatbox.
	 * Used when progress is made but task is not yet complete.
	 */
	private void sendTaskProgress(NuzlockeTask task, String details, int current, int total)
	{
		// Check config - if showChatProgress is disabled, don't send
		if (!config.showChatProgress())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_GREEN + ">Task Progress:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col> " +
			"(" + current + "/" + total + ")";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

		if (details != null && !details.isEmpty())
		{
			String detailMessage = "  - " + details;

			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value(detailMessage)
				.build());
		}

	}

	/**
	 * Send a task success message to the player's chatbox.
	 * Used when a task is fully completed.
	 */
	private void sendTaskSuccess(NuzlockeTask task, String details)
	{
		// Check config - if showChatSuccess is disabled, don't send
		if (!config.showChatSuccess())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_BLUE + ">Task Complete!</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col>";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

		if (details != null && !details.isEmpty())
		{
			String detailMessage = "  - " + details;

			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value(detailMessage)
				.build());
		}

	}
}
