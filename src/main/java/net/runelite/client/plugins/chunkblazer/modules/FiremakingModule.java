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
 * Module for handling FIREMAKING completion type tasks.
 * Unlike other skilling tasks, firemaking CONSUMES logs rather than producing items.
 * Detection: When Firemaking XP increases AND required logs are consumed from inventory.
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

	// Track logs consumed since last XP gain (to match XP with consumption)
	private final Map<Integer, Integer> logsConsumedSinceLastXp = new ConcurrentHashMap<>();

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100;

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
		logsConsumedSinceLastXp.clear();
		previousFiremakingXp = -1;
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
		logsConsumedSinceLastXp.clear();
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
	public void onGameTick(GameTick event)
	{
		tickCounter++;

		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
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
		for (int watchedItemId : watchedItemIds)
		{
			int previousCount = previousInventory.getOrDefault(watchedItemId, 0);
			int currentCount = currentInventory.getOrDefault(watchedItemId, 0);

			if (currentCount < previousCount)
			{
				int consumed = previousCount - currentCount;

				// Track consumed logs to match with XP gain
				logsConsumedSinceLastXp.merge(watchedItemId, consumed, Integer::sum);
			}
		}

		// Update previous state
		previousInventory.clear();
		previousInventory.putAll(currentInventory);
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

		if (xpGained > 0)
		{

			// Check if we consumed any watched logs
			if (!logsConsumedSinceLastXp.isEmpty())
			{

				// Credit progress for tasks
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					checkTaskProgressFromBurn(task, logsConsumedSinceLastXp);
				}

				// Clear consumed tracking
				logsConsumedSinceLastXp.clear();
			}
		}
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
