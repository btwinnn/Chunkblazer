package net.runelite.client.plugins.chunkblazer.modules;

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
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;

/**
 * Module for FARMING completion type tasks.
 *
 * <p>The authored farming tasks are ACTIONS, not item counts: "Plant a
 * Sweetcorn Seed", "Plant an Apple Tree Sapling", "Rake a Farming Patch".
 * The verification signal is a Farming XP drop landing on the same game
 * tick as a change in the task's watched item count in the INVENTORY:
 *
 * <ul>
 *   <li><b>Plant tasks</b> — the watched seed/sapling count DECREASES
 *       (the patch consumes it). Allotments consume 3 seeds for one
 *       planting, so progress is credited per action-tick, NOT per item:
 *       a -3 seed delta in one tick is +1 progress.</li>
 *   <li><b>Rake tasks</b> — the watched item (Weeds) count INCREASES.
 *       Same rule, opposite direction; each rake tick is one action.</li>
 * </ul>
 *
 * <p>Watering, composting, harvesting and other Farming XP sources don't
 * touch the watched item, so they never credit. Banking or dropping the
 * seed changes the count but has no same-tick Farming XP, so the
 * end-of-tick snapshot slide in {@link #onGameTick} rolls the baseline
 * past it uncredited — the same gate model as ObtainModule's skilling
 * path (and deliberately NOT FiremakingModule's buffer, which can carry a
 * bank withdrawal across ticks into a later unrelated XP drop).
 */
@Slf4j
@Singleton
public class FarmingModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "FARMING";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Per-task watched item IDs (union of the task's required_items variants).
	private final Map<String, Set<Integer>> taskWatchedItems = new ConcurrentHashMap<>();

	// Per-task per-item INVENTORY-only count baseline. A same-tick Farming XP
	// drop plus any deviation from this baseline is one credited action; the
	// baseline then rolls to current. GameTick slides it past non-farming
	// inventory changes (banking, GE, drops) so they can't credit later.
	private final Map<String, Map<Integer, Integer>> inventorySnapshot = new ConcurrentHashMap<>();

	// Union of all watched item IDs across active tasks (fast relevance check).
	private final Set<Integer> watchedItemIds = ConcurrentHashMap.newKeySet();

	// Last observed Farming XP; -1 until the first sighting seeds the baseline.
	private int previousFarmingXp = -1;

	// True once Farming XP has risen during the CURRENT game tick. Makes the
	// credit order-independent within the tick: planting fires an inventory
	// change and an XP drop on the same tick, in either order. Cleared in
	// onGameTick.
	private boolean farmingXpGainedThisTick = false;

	@Inject
	public FarmingModule()
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
		String type = task.getCompletionType();
		String category = task.getCategory();
		return (type != null && type.equalsIgnoreCase(COMPLETION_TYPE)) ||
			(category != null && category.equalsIgnoreCase(COMPLETION_TYPE));
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
		taskWatchedItems.clear();
		inventorySnapshot.clear();
		watchedItemIds.clear();
		previousFarmingXp = -1;
		farmingXpGainedThisTick = false;
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);

			// Union all required_items variants into one watched set. Every
			// authored farming task has a single required item at quantity 1
			// (the seed/sapling to plant, or Weeds for rake tasks), but stay
			// general: any watched variant changing counts as the action.
			Set<Integer> watched = new HashSet<>();
			List<RequiredItem> requiredItems = task.getRequiredItems();
			if (requiredItems != null)
			{
				for (RequiredItem item : requiredItems)
				{
					List<Integer> itemIds = item.getItemIds();
					if (itemIds != null)
					{
						watched.addAll(itemIds);
					}
				}
			}
			if (watched.isEmpty())
			{
				log.warn("FARMING task '{}' ({}) has no required_items — cannot track it",
					task.getName(), task.getTaskId());
				return;
			}

			taskWatchedItems.put(task.getTaskId(), watched);
			watchedItemIds.addAll(watched);

			// Seed the inventory baseline and the XP baseline on the client
			// thread (item counts require it). Seeding XP here means the FIRST
			// planting after a (re)load produces a real delta instead of being
			// swallowed as a first-sighting baseline.
			clientThread.invokeLater(() ->
			{
				inventorySnapshot.put(task.getTaskId(), snapshotInventoryCounts(watched));
				if (previousFarmingXp < 0 && client.getLocalPlayer() != null)
				{
					previousFarmingXp = client.getSkillExperience(Skill.FARMING);
				}
			});
		}
		catch (Exception e)
		{
			log.error("FarmingModule.addActiveTask() EXCEPTION: ", e);
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
		taskWatchedItems.clear();
		inventorySnapshot.clear();
		watchedItemIds.clear();
	}

	@Override
	public void checkProgress()
	{
		// Farming progress is action-based (a seed planted is gone); it cannot
		// be re-derived from current possessions. Present for interface parity.
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.FARMING)
		{
			return;
		}

		int newXp = event.getXp();
		int prevXp = previousFarmingXp;
		previousFarmingXp = newXp;

		// First sighting (login/initial sync) records the baseline only, and
		// level/boost recalcs without an XP gain don't count as farming.
		if (prevXp < 0 || newXp <= prevXp)
		{
			return;
		}

		farmingXpGainedThisTick = true;

		// XP-after-item order: the inventory may already differ from the
		// snapshot (the container event fired earlier this tick without the
		// XP flag). Credit that pending change now.
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			creditFarmingAction(task);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (activeTasks.isEmpty() || watchedItemIds.isEmpty())
		{
			return;
		}
		if (event.getContainerId() != InventoryID.INVENTORY.getId())
		{
			return;
		}
		// Item-after-XP order: only credit when this tick's Farming XP already
		// fired. Without the flag, leave the snapshot untouched — either the
		// XP arrives later this tick (onStatChanged credits then), or this was
		// a non-farming change that onGameTick rolls past uncredited.
		if (!farmingXpGainedThisTick)
		{
			return;
		}
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			creditFarmingAction(task);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// End-of-tick reconciliation: GameTick fires after this tick's
		// StatChanged / ItemContainerChanged, so all crediting has happened.
		// Slide every snapshot to "now" so non-farming inventory changes
		// (banking, GE, drops) can't be miscredited on a later XP drop.
		if (!activeTasks.isEmpty())
		{
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				Set<Integer> watched = taskWatchedItems.get(task.getTaskId());
				if (watched != null)
				{
					inventorySnapshot.put(task.getTaskId(), snapshotInventoryCounts(watched));
				}
			}
		}
		farmingXpGainedThisTick = false;
	}

	/**
	 * Credit ONE farming action to the task if any watched item's inventory
	 * count deviates from the snapshot, then roll the snapshot forward. Called
	 * only on ticks where Farming XP rose. One action per tick regardless of
	 * the delta magnitude — planting an allotment consumes 3 seeds but is one
	 * "Plant" (and every authored task needs just 1 action anyway).
	 */
	private void creditFarmingAction(NuzlockeTask task)
	{
		Set<Integer> watched = taskWatchedItems.get(task.getTaskId());
		if (watched == null || watched.isEmpty())
		{
			return;
		}
		Map<Integer, Integer> snapshot = inventorySnapshot.get(task.getTaskId());
		if (snapshot == null)
		{
			// Deferred init in addActiveTask hasn't run yet — skip rather than
			// treat the whole inventory as a fresh delta.
			return;
		}

		boolean changed = false;
		StringBuilder details = new StringBuilder();
		Map<Integer, Integer> nextSnapshot = new HashMap<>();
		for (Integer itemId : watched)
		{
			int prev = snapshot.getOrDefault(itemId, 0);
			int curr = countInInventory(itemId);
			nextSnapshot.put(itemId, curr);
			if (curr == prev)
			{
				continue;
			}
			changed = true;
			if (details.length() > 0)
			{
				details.append(", ");
			}
			// Consumed = planted into a patch; gained = raked up (Weeds).
			details.append(curr < prev ? "Planted " : "Collected ")
				.append(Math.abs(curr - prev)).append(" x ").append(getItemName(itemId));
		}
		inventorySnapshot.put(task.getTaskId(), nextSnapshot);

		if (!changed)
		{
			return;
		}

		int totalRequired = Math.max(1, task.getTargetQuantity());
		int previousProgress = task.getCurrentProgress();
		int newProgress = Math.min(totalRequired, previousProgress + 1);
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
			sendTaskSuccess(task, details.toString());
			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up task tracking
			taskWatchedItems.remove(task.getTaskId());
			inventorySnapshot.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatchedItems();
		}
	}

	/**
	 * Snapshot the current INVENTORY-only count of every watched item. Must
	 * run on the client thread.
	 */
	private Map<Integer, Integer> snapshotInventoryCounts(Set<Integer> watched)
	{
		Map<Integer, Integer> snap = new HashMap<>();
		for (Integer itemId : watched)
		{
			snap.put(itemId, countInInventory(itemId));
		}
		return snap;
	}

	/**
	 * Count of an item in the INVENTORY only. Planting happens from the
	 * inventory; bank and equipment are irrelevant to the action signal.
	 * Uses canonicalized IDs (noted stacks can't be planted, but the
	 * canonical count keeps the baseline arithmetic consistent).
	 */
	private int countInInventory(int itemId)
	{
		ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
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
	 * Rebuild the union of watched item IDs from all remaining tasks.
	 */
	private void rebuildWatchedItems()
	{
		watchedItemIds.clear();
		for (Set<Integer> watched : taskWatchedItems.values())
		{
			watchedItemIds.addAll(watched);
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
			if (client.isClientThread())
			{
				return itemManager.getItemComposition(itemId).getName();
			}
			return "Item#" + itemId;
		}
		catch (Exception e)
		{
			return "Item#" + itemId;
		}
	}

	// ==================== CHAT MESSAGE METHODS ====================

	private void sendTaskProgress(NuzlockeTask task, String details, int current, int total)
	{
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
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value("  - " + details)
				.build());
		}
	}

	private void sendTaskSuccess(NuzlockeTask task, String details)
	{
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
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value("  - " + details)
				.build());
		}
	}
}
