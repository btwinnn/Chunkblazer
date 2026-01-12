package net.runelite.client.plugins.chunkblazer.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;
import net.runelite.client.plugins.chunkblazer.api.ItemObtainedReport;

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

	// Chat colors for ChunkBlazer messages (matching NPCKillModule)
	private static final String COLOR_BLUE = "3366ff";        // [ChunkBlazer] branding
	private static final String COLOR_DARK_BLUE = "1a5276";   // Task Success (dark blue, readable)
	private static final String COLOR_DARK_GREEN = "228b22";  // Task Progress
	private static final String COLOR_BLACK = "000000";       // Task name text

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track task-specific data
	// Map: taskId -> (Map: itemId -> required quantity)
	private final Map<String, Map<Integer, Integer>> taskTargetItems = new ConcurrentHashMap<>();

	// Track previous inventory state for detecting new items
	private final Map<Integer, Integer> previousInventory = new ConcurrentHashMap<>();

	// Items we're currently watching for (union of all task requirements)
	// Use thread-safe set to avoid ConcurrentModificationException
	private final Set<Integer> watchedItemIds = ConcurrentHashMap.newKeySet();

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100; // Log every 100 ticks (~60 seconds)

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
		// Handle both "OBTAIN" completion type and "Obtain" category
		String type = task.getCompletionType();
		String category = task.getCategory();

		return (type != null && type.equalsIgnoreCase(COMPLETION_TYPE)) ||
			(category != null && category.equalsIgnoreCase(COMPLETION_TYPE));
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
		log.info("=== ObtainModule STARTED ===");
		log.info("ObtainModule: eventBus={}, client={}", eventBus != null ? "OK" : "NULL", client != null ? "OK" : "NULL");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		previousInventory.clear();
		taskTargetItems.clear();
		watchedItemIds.clear();
		log.info("ObtainModule stopped");
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			log.info(">>> ObtainModule.addActiveTask() ENTRY - calling super...");
			super.addActiveTask(task);
			log.info(">>> ObtainModule.addActiveTask() - super returned");

			log.info("=== ObtainModule: ADDING ACTIVE TASK ===");
			log.info("  Task Name: {}", task.getName());
			log.info("  Task ID: {}", task.getTaskId());
			log.info("  Completion Type: {}", task.getCompletionType());
			log.info("  Category: {}", task.getCategory());

			// Parse required items from task
			Map<Integer, Integer> targetItems = new HashMap<>();
			List<RequiredItem> requiredItems = task.getRequiredItems();

			log.info("  Required Items: {}", requiredItems != null ? requiredItems.size() + " items" : "NULL");

			if (requiredItems != null)
			{
				for (RequiredItem item : requiredItems)
				{
					log.info("    Processing RequiredItem: {}", item);
					List<Integer> itemIds = item.getItemIds();
					log.info("      Item IDs: {}", itemIds);
					log.info("      Required Qty: {}", item.getRequiredQuantity());

					if (itemIds != null)
					{
						for (Integer itemId : itemIds)
						{
							targetItems.put(itemId, item.getRequiredQuantity());
							watchedItemIds.add(itemId);
							log.info("      >>> WATCHING: Item ID {} ({}) - qty: {} for task '{}'",
								itemId, getItemName(itemId), item.getRequiredQuantity(), task.getName());
						}
					}
					else
					{
						log.warn("      >>> WARNING: itemIds is NULL for this RequiredItem!");
					}
				}
			}
			else
			{
				log.warn("  >>> WARNING: No required_items defined for this OBTAIN task!");
			}

			taskTargetItems.put(task.getTaskId(), targetItems);
			log.info("  Total items being watched for this task: {}", targetItems.size());
			log.info("  All watched item IDs across all tasks: {}", watchedItemIds);
			log.info("=== END ADDING TASK ===");

			// Initialize inventory tracking on client thread
			clientThread.invokeLater(() ->
			{
				initializeInventoryTracking();
				checkTaskProgress(task);
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
		taskTargetItems.clear();
		watchedItemIds.clear();
		previousInventory.clear();
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
	 * Initialize tracking of current inventory state.
	 */
	private void initializeInventoryTracking()
	{
		previousInventory.clear();

		log.info(">>> ObtainModule: Initializing inventory tracking...");

		// Track inventory
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					previousInventory.merge(item.getId(), item.getQuantity(), Integer::sum);

					// Log if this is a watched item
					if (watchedItemIds.contains(item.getId()))
					{
						log.info(">>>   Already have watched item: {} (ID: {}) x {}",
							getItemName(item.getId()), item.getId(), item.getQuantity());
					}
				}
			}
			log.info(">>> ObtainModule: Initialized with {} unique items in inventory", previousInventory.size());
		}
		else
		{
			log.warn(">>> ObtainModule: Inventory container is NULL during initialization!");
		}
	}

	/**
	 * Check progress for a specific task.
	 */
	private void checkTaskProgress(NuzlockeTask task)
	{
		if (task == null)
		{
			return;
		}

		Map<Integer, Integer> targetItems = taskTargetItems.get(task.getTaskId());
		if (targetItems == null || targetItems.isEmpty())
		{
			return;
		}

		int totalRequired = 0;
		int totalObtained = 0;

		// Build details about each item for logging
		StringBuilder itemDetails = new StringBuilder();
		for (Map.Entry<Integer, Integer> target : targetItems.entrySet())
		{
			int itemId = target.getKey();
			int required = target.getValue();
			totalRequired += required;

			int current = getItemCount(itemId);
			int countForTask = Math.min(current, required);
			totalObtained += countForTask;

			if (itemDetails.length() > 0)
			{
				itemDetails.append(", ");
			}
			itemDetails.append(getItemName(itemId)).append(" ").append(current).append("/").append(required);

			log.debug("ObtainModule: Item {} - have {}/{}", getItemName(itemId), current, required);
		}

		int previousProgress = task.getCurrentProgress();
		task.setCurrentProgress(totalObtained);

		// Send progress chat message if progress changed
		if (totalObtained != previousProgress && totalObtained > previousProgress)
		{
			String details = "Obtained: " + itemDetails.toString();
			sendTaskProgress(task, details, totalObtained, totalRequired);

			if (completionCallback != null)
			{
				completionCallback.onProgressUpdated(task, totalObtained);
			}
		}

		log.debug("ObtainModule: Task '{}' progress: {}/{}", task.getName(), totalObtained, totalRequired);

		// Check for completion
		if (totalObtained >= totalRequired && !task.isCompleted())
		{
			log.info("ObtainModule: Task '{}' COMPLETED! ({}/{})", task.getName(), totalObtained, totalRequired);
			task.setCompleted(true);

			// Send success chat message
			String successDetails = "All items obtained: " + itemDetails.toString();
			sendTaskSuccess(task, successDetails);

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, totalObtained);
			}

			// Clean up task tracking
			taskTargetItems.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatchedItems();
		}
	}

	/**
	 * Get total count of an item across inventory only.
	 * We only check inventory since that's where items are "obtained".
	 */
	private int getItemCount(int itemId)
	{
		int count = 0;

		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item != null && item.getId() == itemId)
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
		for (Map<Integer, Integer> items : taskTargetItems.values())
		{
			watchedItemIds.addAll(items.keySet());
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;

		// Log heartbeat periodically to confirm module is running
		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
			log.info(">>> ObtainModule HEARTBEAT - tick {} - activeTasks: {}, watchedItems: {}",
				tickCounter, activeTasks.size(), watchedItemIds.size());

			// List all active obtain tasks
			for (NuzlockeTask task : activeTasks)
			{
				Map<Integer, Integer> items = taskTargetItems.get(task.getTaskId());
				String itemInfo = items != null ? "watching " + items.size() + " item IDs: " + items.keySet() : "NO ITEMS";
				log.info(">>>   Active obtain task: {} ({}) - {}/{} - {}",
					task.getName(), task.getTaskId(),
					task.getCurrentProgress(), task.getTargetQuantity(), itemInfo);
			}

			if (activeTasks.isEmpty())
			{
				log.info(">>>   (No active OBTAIN tasks)");
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();

		// Log ALL container changes for debugging (at debug level to not spam)
		log.debug(">>> ItemContainerChanged: containerId={} (INVENTORY={})",
			containerId, InventoryID.INVENTORY.getId());

		// Skip if no tasks or no watched items
		if (activeTasks.isEmpty())
		{
			log.debug(">>> ObtainModule: No active tasks, ignoring container change");
			return;
		}

		if (watchedItemIds.isEmpty())
		{
			log.debug(">>> ObtainModule: No watched item IDs, ignoring container change");
			return;
		}

		// Only track inventory changes
		if (containerId != InventoryID.INVENTORY.getId())
		{
			log.debug(">>> ObtainModule: Container {} is not INVENTORY ({}), ignoring",
				containerId, InventoryID.INVENTORY.getId());
			return;
		}

		log.info(">>> ObtainModule: INVENTORY CHANGED - checking for watched items...");
		log.info(">>> Watched item IDs: {}", watchedItemIds);

		// Build current inventory state
		Map<Integer, Integer> currentInventory = new HashMap<>();
		ItemContainer container = event.getItemContainer();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					currentInventory.merge(item.getId(), item.getQuantity(), Integer::sum);
					// Log if this is a watched item
					if (watchedItemIds.contains(item.getId()))
					{
						log.info(">>>   FOUND watched item in inventory: {} (ID: {}) x {}",
							getItemName(item.getId()), item.getId(), item.getQuantity());
					}
				}
			}
		}

		// Check for newly obtained watched items
		boolean anyNewItems = false;
		for (int watchedItemId : watchedItemIds)
		{
			int previousCount = previousInventory.getOrDefault(watchedItemId, 0);
			int currentCount = currentInventory.getOrDefault(watchedItemId, 0);

			log.info(">>>   Item {} ({}): previous={}, current={}",
				getItemName(watchedItemId), watchedItemId, previousCount, currentCount);

			if (currentCount > previousCount)
			{
				int obtained = currentCount - previousCount;
				log.info(">>> ObtainModule: DETECTED {} x {} obtained!", obtained, getItemName(watchedItemId));
				anyNewItems = true;

				// Send report for tracking (optional, for server verification)
				sendItemObtainedReport(watchedItemId, obtained);
			}
			else if (currentCount < previousCount)
			{
				log.info(">>>   Item {} DECREASED (dropped/used?)", getItemName(watchedItemId));
			}
		}

		// Update previous state
		previousInventory.clear();
		previousInventory.putAll(currentInventory);
		log.info(">>> Updated previousInventory with {} unique items", previousInventory.size());

		// Check progress for all tasks if any watched items were obtained
		if (anyNewItems)
		{
			log.info(">>> New items detected - checking progress for {} active tasks", activeTasks.size());
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				checkTaskProgress(task);
			}
		}
		else
		{
			log.info(">>> No new watched items detected in this inventory change");
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

	/**
	 * Send item obtained report to API (for server verification).
	 */
	private void sendItemObtainedReport(int itemId, int quantity)
	{
		// Find which task this item belongs to (for the report)
		String taskId = "";
		for (NuzlockeTask task : activeTasks)
		{
			Map<Integer, Integer> items = taskTargetItems.get(task.getTaskId());
			if (items != null && items.containsKey(itemId))
			{
				taskId = task.getTaskId();
				break;
			}
		}

		ItemObtainedReport report = ItemObtainedReport.builder()
			.playerHash(getPlayerHash())
			.taskId(taskId)
			.itemId(itemId)
			.itemName(getItemName(itemId))
			.quantity(quantity)
			.source("UNKNOWN")
			.sourceId(-1)
			.regionId(getCurrentRegionId())
			.worldX(client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation().getX() : 0)
			.worldY(client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation().getY() : 0)
			.plane(client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation().getPlane() : 0)
			.gameTick(getGameTick())
			.timestamp(System.currentTimeMillis())
			.geValue(itemManager.getItemPrice(itemId))
			.destination("INVENTORY")
			.build();

		apiClient.reportItemObtained(report)
			.thenAccept(response ->
			{
				if (response != null && !response.isSuccess())
				{
					log.warn("ObtainModule: Server rejected item report: {}", response.getErrorMessage());
				}
			})
			.exceptionally(ex ->
			{
				log.debug("ObtainModule: Failed to send item report (API may be offline): {}", ex.getMessage());
				return null;
			});
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
			log.info("[CHAT] Obtain progress (hidden by config): {} - {}", task.getName(), details);
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

		log.info("[CHAT] Obtain progress: {} ({}/{}) - {}", task.getName(), current, total, details);
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
			log.info("[CHAT] Obtain success (hidden by config): {} - {}", task.getName(), details);
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

		log.info("[CHAT] Obtain success: {} - {}", task.getName(), details);
	}
}
