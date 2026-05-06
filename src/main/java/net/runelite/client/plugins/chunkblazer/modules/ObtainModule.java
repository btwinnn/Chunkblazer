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

	// Track task-specific data
	// Map: taskId -> (Map: itemId -> required quantity)
	private final Map<String, Map<Integer, Integer>> taskTargetItems = new ConcurrentHashMap<>();

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
		log.info("=== ObtainModule STARTED ===");
		log.info("ObtainModule: eventBus={}, client={}", eventBus != null ? "OK" : "NULL", client != null ? "OK" : "NULL");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
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

			// Run an initial progress check on the client thread. checkTaskProgress
			// reads inv+bank+equip directly, so anything the player already holds
			// (e.g. cowhide stockpiled in the bank) counts immediately.
			clientThread.invokeLater(() -> checkTaskProgress(task));
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

			// Count what the player actually holds across inventory + bank +
			// equipment. Dropped items are NOT counted, which defeats the
			// drop-and-pick-up cheat. The Math.max(prev, ...) below preserves
			// progress when items are consumed (eaten, used, etc.) so this
			// only credits items that came in from outside the cheat path.
			int held = getItemCount(itemId);
			int countForTask = Math.min(held, required);
			totalObtained += countForTask;

			if (itemDetails.length() > 0)
			{
				itemDetails.append(", ");
			}
			itemDetails.append(getItemName(itemId)).append(" ").append(held).append("/").append(required);

			log.debug("ObtainModule: Item {} - held {}/{} (inv+bank+equip)", getItemName(itemId), held, required);
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

		log.debug("ObtainModule: Task '{}' progress: {}/{}", task.getName(), newProgress, totalRequired);

		// Check for completion
		if (newProgress >= totalRequired && !task.isCompleted())
		{
			log.info("ObtainModule: Task '{}' COMPLETED! ({}/{})", task.getName(), totalObtained, totalRequired);
			task.setCompleted(true);

			// Send success chat message
			String successDetails = "All items obtained: " + itemDetails.toString();
			sendTaskSuccess(task, successDetails);

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up task tracking
			taskTargetItems.remove(task.getTaskId());
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

		// Container progress is computed directly from current inv+bank+equip
		// counts inside checkTaskProgress. Math.max with the previously-saved
		// progress prevents regression when items are consumed, and dropped
		// items aren't in any container so the drop-and-pickup cheat can't
		// inflate the count.
		log.debug(">>> ObtainModule: container {} changed - rechecking progress for {} tasks",
			containerId, activeTasks.size());
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			checkTaskProgress(task);
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
