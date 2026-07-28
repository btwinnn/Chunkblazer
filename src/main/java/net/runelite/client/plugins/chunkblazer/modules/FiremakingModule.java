package net.runelite.client.plugins.chunkblazer.modules;

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
import net.runelite.api.ChatMessageType;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;

/**
 * Module for handling FIREMAKING completion type tasks.
 *
 * <p>Unlike other skilling tasks, firemaking CONSUMES logs rather than producing
 * items, so a burn is detected as a watched log leaving the inventory paired
 * with a Firemaking XP gain. Every authored firemaking task is of the form
 * "burn N of item X", so that pair is the whole rule.
 *
 * <p><b>Both lighting methods must credit.</b> The traditional path is
 * tinderbox-on-log: one log leaves the inventory and the XP lands with it. The
 * bonfire path is add-log-to-an-existing-fire — less XP per log, far more AFK,
 * and the method most players actually use for bulk burning. A bonfire burn is
 * still "log consumed + Firemaking XP", so it needs no separate sensor; what it
 * needs is for the pairing not to assume a fixed event order or a same-tick
 * landing.
 *
 * <p>Lessons this encodes:
 * <ul>
 *   <li><b>Either event order must credit.</b> The original rule credited only
 *       when consumption was already banked at the moment the XP arrived, and
 *       threw the XP away otherwise. Any burn whose StatChanged is dispatched
 *       ahead of its ItemContainerChanged silently scored nothing, leaving the
 *       task permanently one short. Consumption-then-XP and XP-then-consumption
 *       now both credit, via {@link #MATCH_WINDOW_TICKS} — the same symmetric
 *       pairing ConstructionModule uses.</li>
 *   <li><b>Consumption and XP need not share a tick.</b> Adding a batch to a
 *       bonfire can drain logs faster than the XP drops arrive, so the pair is
 *       matched across a short window rather than within one tick.</li>
 *   <li><b>Unpaired consumption must expire.</b> Pending consumption used to
 *       accumulate forever, so logs banked, dropped or sold were still sitting
 *       in the map when the next real burn landed and were credited alongside
 *       it. Consumption older than the window is now discarded instead.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class FiremakingModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "FIREMAKING";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	// How far apart (in ticks) the log leaving the inventory and the Firemaking
	// XP gain may land and still count as the same burn. Covers a bonfire batch
	// draining ahead of its XP drops; short enough that logs banked or dropped
	// minutes earlier cannot ride along on an unrelated burn. Matches
	// ConstructionModule/FarmingModule's window.
	private static final int MATCH_WINDOW_TICKS = 5;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track task-specific data
	// Map: taskId -> (Map: itemId -> required quantity)
	private final Map<String, Map<Integer, Integer>> taskTargetItems = new ConcurrentHashMap<>();

	// Track previous inventory state for detecting consumed items
	private final Map<Integer, Integer> previousInventory = new ConcurrentHashMap<>();

	// Items we're currently watching for (union of all task requirements)
	private final Set<Integer> watchedItemIds = ConcurrentHashMap.newKeySet();

	// Track Firemaking XP for detecting burns
	private int previousFiremakingXp = -1;

	// Watched logs seen leaving the inventory but not yet paired with a
	// Firemaking XP gain. Ages out of MATCH_WINDOW_TICKS lazily — see
	// expirePendingBurns().
	//
	// A LIST, not a map keyed by item id: entries must expire by when they were
	// consumed, and merging same-id consumptions into one bucket would refresh
	// the timestamp of an old batch every time a new log of that type went. A
	// player who banked 20 logs and later burned 1 would then have all 21 still
	// live at credit time — exactly the false credit expiry exists to stop.
	private final List<PendingBurn> pendingBurns = new CopyOnWriteArrayList<>();

	// Tick of the most recent Firemaking XP gain, or -1 if none this session.
	// Lets consumption that arrives AFTER its XP still complete the pair.
	private int lastFiremakingXpTick = -1;

	/** One watched-log consumption, stamped with the tick it happened on. */
	private static final class PendingBurn
	{
		private final int itemId;
		private final int quantity;
		private final int tick;

		private PendingBurn(int itemId, int quantity, int tick)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.tick = tick;
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
	public boolean canHandle(NuzlockeTask task)
	{
		String type = task.getCompletionType();
		return type != null && type.equalsIgnoreCase(COMPLETION_TYPE);
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
			clientThread.invokeLater(() -> {
				initializeInventoryTracking();
				initializeXpTracking();
			});
		}
		catch (Exception e)
		{
			log.error("FiremakingModule.addActiveTask() EXCEPTION: ", e);
		}
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		super.onTaskAssigned(task);
		addActiveTask(task);
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

		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
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

		if (event.getContainerId() != InventoryID.INVENTORY.getId())
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
				pendingBurns.add(new PendingBurn(watchedItemId, consumed, tick));
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
			creditPendingBurns("consumption after XP");
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
		creditPendingBurns("XP after consumption");
	}

	/**
	 * Pair up any un-expired consumption with a Firemaking XP gain and credit it.
	 *
	 * <p>Called from BOTH event handlers, so whichever of the two arrives second
	 * completes the burn. Safe to call speculatively: it no-ops when there is
	 * nothing pending.
	 */
	private void creditPendingBurns(String trigger)
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

		log.info("[FIREMAKING-DEBUG] burn credited ({}): {} at tick {}",
			trigger, burned, getGameTick());

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

			sendTaskProgress(task, "Burned " + progressIncrement + " logs", newProgress, required);

			if (completionCallback != null)
			{
				completionCallback.onProgressUpdated(task, newProgress);
			}

			// Check for completion
			if (newProgress >= required && !task.isCompleted())
			{
				task.setCompleted(true);

				sendTaskSuccess(task, "All logs burned!");

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

	}
}
