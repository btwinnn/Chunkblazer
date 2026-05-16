package net.runelite.client.plugins.chunkblazer.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameObject;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.ChunkBlazerPlugin;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;
import net.runelite.client.plugins.chunkblazer.RequiredObject;

/**
 * Module for handling CONSTRUCTION completion type tasks.
 *
 * Two detection paths:
 *   Object-gated (preferred) — task has required_object with the built furniture's
 *     object_id (e.g. Hard STASH Unit 29018, an Oak Larder, etc). We subscribe to
 *     GameObjectSpawned and credit only when the watched object_id spawns within
 *     INTERACTION_TIMEOUT_TICKS of a Construction XP gain. This is the precise
 *     check: the player built THIS specific furniture, not just "any" furniture.
 *   Item-consumption fallback — for tasks without required_object, fall back to
 *     the legacy "any Construction XP after a tracked plank/nail consumption"
 *     logic. Less precise but preserves behavior for older task definitions.
 */
@Slf4j
@Singleton
public class ConstructionModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "CONSTRUCTION";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Provider (not direct injection) to break the plugin <-> module cycle —
	// ChunkBlazerPlugin instantiates this module via Guice, so we can't depend
	// on it eagerly. Lazy fetch via Provider.get() at use time.
	@Inject
	private Provider<ChunkBlazerPlugin> pluginProvider;

	// Track task-specific data
	// Map: taskId -> (Map: itemId -> required quantity)
	private final Map<String, Map<Integer, Integer>> taskTargetItems = new ConcurrentHashMap<>();

	// Track previous inventory state for detecting consumed items
	private final Map<Integer, Integer> previousInventory = new ConcurrentHashMap<>();

	// Items we're currently watching for (union of all task requirements)
	private final Set<Integer> watchedItemIds = ConcurrentHashMap.newKeySet();

	// Track Construction XP for detecting builds
	private int previousConstructionXp = -1;

	// Track items consumed since last XP gain (to match XP with consumption)
	private final Map<Integer, Integer> itemsConsumedSinceLastXp = new ConcurrentHashMap<>();

	// Per-task required GameObject IDs (the built furniture: e.g. STASH, Oak Larder).
	private final Map<String, Set<Integer>> taskRequiredObjectIds = new ConcurrentHashMap<>();
	// Union of all required object IDs across active tasks.
	private final Set<Integer> watchedObjectIds = ConcurrentHashMap.newKeySet();

	// Most recent spawn of a watched built-furniture object. Cleared after credit.
	private int lastSpawnedObjectId = -1;
	private int lastSpawnedObjectTick = -1;

	// How many ticks after a watched GameObject spawns the XP gain still counts
	// as "from that build". Matches the window used in Thieving/Agility.
	private static final int INTERACTION_TIMEOUT_TICKS = 5;

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100;

	@Inject
	public ConstructionModule()
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
		log.info("=== ConstructionModule STARTED ===");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		previousInventory.clear();
		taskTargetItems.clear();
		watchedItemIds.clear();
		itemsConsumedSinceLastXp.clear();
		taskRequiredObjectIds.clear();
		watchedObjectIds.clear();
		lastSpawnedObjectId = -1;
		lastSpawnedObjectTick = -1;
		previousConstructionXp = -1;
		log.info("ConstructionModule stopped");
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);

			log.info("=== ConstructionModule: ADDING ACTIVE TASK ===");
			log.info("  Task Name: {}", task.getName());
			log.info("  Task ID: {}", task.getTaskId());

			// Parse required items (planks, nails, etc.)
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
							log.info("      >>> WATCHING ITEM: Item ID {} ({}) - qty: {}",
								itemId, getItemName(itemId), item.getRequiredQuantity());
						}
					}
				}
			}

			taskTargetItems.put(task.getTaskId(), targetItems);

			// Parse required built-furniture object IDs (the precise discriminator).
			Set<Integer> requiredObjectIds = new HashSet<>();
			List<RequiredObject> requiredObjects = task.getRequiredObjects();
			if (requiredObjects != null)
			{
				for (RequiredObject ro : requiredObjects)
				{
					List<Integer> ids = ro.getObjectIds();
					if (ids != null)
					{
						for (Integer id : ids)
						{
							requiredObjectIds.add(id);
							watchedObjectIds.add(id);
							log.info("      >>> WATCHING BUILT OBJECT ID: {}", id);
						}
					}
				}
			}
			taskRequiredObjectIds.put(task.getTaskId(), requiredObjectIds);

			// Initialize tracking on client thread
			clientThread.invokeLater(() -> {
				initializeInventoryTracking();
				initializeXpTracking();
			});
		}
		catch (Exception e)
		{
			log.error("ConstructionModule.addActiveTask() EXCEPTION: ", e);
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
		itemsConsumedSinceLastXp.clear();
		taskRequiredObjectIds.clear();
		watchedObjectIds.clear();
		// IMPORTANT: do NOT reset lastSpawnedObjectId / lastSpawnedObjectTick here.
		// onTaskCleared() is called on routine panel/active-task refreshes (e.g.
		// region change), and a refresh can fire between a GameObjectSpawned
		// event and the matching Construction XP event. If we wiped the spawn
		// sensor, the XP event would arrive with no recent-spawn signal and the
		// build would silently fail to credit — that's exactly what Mike hit
		// (session_2026-05-15_16-49-04: spawn at tick 400, clearTask at tick
		// ~400, XP at tick ~403, no credit). The sensor ages out on its own via
		// INTERACTION_TIMEOUT_TICKS in onStatChanged. Hard-reset still happens
		// in shutDown() below.
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
		log.info("ConstructionModule: Initialized inventory tracking");
	}

	private void initializeXpTracking()
	{
		if (client.getLocalPlayer() != null)
		{
			previousConstructionXp = client.getSkillExperience(Skill.CONSTRUCTION);
			log.info("ConstructionModule: Initialized Construction XP tracking at {} xp", previousConstructionXp);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;

		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
			log.info(">>> ConstructionModule HEARTBEAT - tick {} - activeTasks: {}, watchedItems: {}",
				tickCounter, activeTasks.size(), watchedItemIds.size());
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

		// Check for consumed items (decrease in quantity)
		for (int watchedItemId : watchedItemIds)
		{
			int previousCount = previousInventory.getOrDefault(watchedItemId, 0);
			int currentCount = currentInventory.getOrDefault(watchedItemId, 0);

			if (currentCount < previousCount)
			{
				int consumed = previousCount - currentCount;
				log.info(">>> ConstructionModule: DETECTED {} x {} consumed!",
					consumed, getItemName(watchedItemId));

				// Track consumed items to match with XP gain
				itemsConsumedSinceLastXp.merge(watchedItemId, consumed, Integer::sum);
			}
		}

		// Update previous state
		previousInventory.clear();
		previousInventory.putAll(currentInventory);
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (activeTasks.isEmpty() || watchedObjectIds.isEmpty())
		{
			return;
		}
		GameObject obj = event.getGameObject();
		if (obj == null)
		{
			return;
		}
		int objectId = obj.getId();
		if (watchedObjectIds.contains(objectId))
		{
			lastSpawnedObjectId = objectId;
			lastSpawnedObjectTick = client.getTickCount();
			log.info(">>> ConstructionModule: Watched built object {} spawned at tick {}",
				objectId, lastSpawnedObjectTick);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.CONSTRUCTION)
		{
			return;
		}

		if (activeTasks.isEmpty())
		{
			return;
		}

		int currentXp = event.getXp();
		if (previousConstructionXp < 0)
		{
			previousConstructionXp = currentXp;
			return;
		}

		int xpGained = currentXp - previousConstructionXp;
		previousConstructionXp = currentXp;

		if (xpGained <= 0)
		{
			return;
		}

		log.info(">>> ConstructionModule: Gained {} Construction XP", xpGained);

		int currentTick = client.getTickCount();
		boolean recentSpawn = lastSpawnedObjectId > 0
			&& (currentTick - lastSpawnedObjectTick) <= INTERACTION_TIMEOUT_TICKS;

		// Region check inputs. Many built-furniture object_ids are shared across
		// locations (e.g. "Easy STASH Unit (inconspicuous hole)" 50738 exists at
		// dozens of spots), so an object_id match alone can't distinguish "built
		// at the Grand Museum" from "built at Sunrise Palace". Require the
		// player's current region to match the task's defined region.
		int playerRegionId = getCurrentRegionId();

		boolean creditedFromObject = false;
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Set<Integer> taskObjects = taskRequiredObjectIds.get(task.getTaskId());
			boolean isObjectGated = taskObjects != null && !taskObjects.isEmpty();
			if (!isObjectGated)
			{
				// Object-less tasks handled by the item-consumption fallback below.
				continue;
			}
			if (!recentSpawn || !taskObjects.contains(lastSpawnedObjectId))
			{
				continue;
			}

			// Region gate. Resolved lazily through the Provider to break the
			// plugin-module DI cycle. If the lookup fails (-1) we don't enforce
			// the gate — that preserves behavior for any task whose chunk we
			// can't resolve and keeps unit tests, which don't populate the
			// chunk map, working unchanged.
			int taskRegionId = -1;
			try
			{
				taskRegionId = pluginProvider.get().findRegionForTask(task.getTaskId());
			}
			catch (Exception e)
			{
				log.warn("ConstructionModule: failed to resolve region for task '{}'", task.getTaskId(), e);
			}
			if (taskRegionId > 0 && playerRegionId > 0 && taskRegionId != playerRegionId)
			{
				log.info(">>> ConstructionModule: '{}' NOT credited — built in region {} but task is in region {}",
					task.getName(), playerRegionId, taskRegionId);
				continue;
			}

			log.info(">>> ConstructionModule: '{}' credited (built object {} confirmed in region {})",
				task.getName(), lastSpawnedObjectId, playerRegionId);
			creditTaskProgress(task, 1, "Built object " + lastSpawnedObjectId + " confirmed");
			creditedFromObject = true;
		}

		// Consume the spawn after crediting so a second XP event in the same window
		// (rare for construction, but matches the Agility pattern) can't double-credit.
		if (creditedFromObject)
		{
			lastSpawnedObjectId = -1;
			lastSpawnedObjectTick = -1;
		}

		// Fallback path: for tasks WITHOUT a required_object, fall back to the
		// legacy item-consumption check. Skips object-gated tasks (already handled).
		if (!itemsConsumedSinceLastXp.isEmpty())
		{
			log.info(">>> Items consumed since last XP: {}", itemsConsumedSinceLastXp);
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				Set<Integer> taskObjects = taskRequiredObjectIds.get(task.getTaskId());
				if (taskObjects != null && !taskObjects.isEmpty())
				{
					continue; // handled above by the object-gated path
				}
				checkTaskProgressFromBuild(task, itemsConsumedSinceLastXp);
			}
			itemsConsumedSinceLastXp.clear();
		}
	}

	private void checkTaskProgressFromBuild(NuzlockeTask task, Map<Integer, Integer> consumedItems)
	{
		Map<Integer, Integer> targetItems = taskTargetItems.get(task.getTaskId());
		if (targetItems == null || targetItems.isEmpty())
		{
			// No specific items required, credit for any construction XP
			creditTaskProgress(task, 1, "Furniture built");
			return;
		}

		// Check if any consumed item matches task requirements
		boolean matched = false;
		for (Map.Entry<Integer, Integer> consumed : consumedItems.entrySet())
		{
			int itemId = consumed.getKey();
			if (targetItems.containsKey(itemId))
			{
				matched = true;
				break;
			}
		}

		if (matched)
		{
			creditTaskProgress(task, 1, "Furniture built with required materials");
		}
	}

	private void checkTaskProgress(NuzlockeTask task)
	{
		// For construction, progress is tracked via XP + item consumption events
		// This method is for consistency with other modules
	}

	private void creditTaskProgress(NuzlockeTask task, int amount, String details)
	{
		int previousProgress = task.getCurrentProgress();
		int newProgress = previousProgress + amount;
		int required = task.getTargetQuantity();

		// Default to 1 if no target quantity specified
		if (required <= 0)
		{
			required = 1;
		}

		task.setCurrentProgress(newProgress);

		sendTaskProgress(task, details, newProgress, required);

		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		// Check for completion
		if (newProgress >= required && !task.isCompleted())
		{
			log.info("ConstructionModule: Task '{}' COMPLETED! ({}/{})",
				task.getName(), newProgress, required);
			task.setCompleted(true);

			sendTaskSuccess(task, "Construction task complete!");

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up
			taskTargetItems.remove(task.getTaskId());
			taskRequiredObjectIds.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatched();
		}
	}

	private void rebuildWatched()
	{
		watchedItemIds.clear();
		for (Map<Integer, Integer> items : taskTargetItems.values())
		{
			watchedItemIds.addAll(items.keySet());
		}
		watchedObjectIds.clear();
		for (Set<Integer> ids : taskRequiredObjectIds.values())
		{
			watchedObjectIds.addAll(ids);
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

		log.info("[CHAT] Construction progress: {} ({}/{}) - {}", task.getName(), current, total, details);
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

		log.info("[CHAT] Construction success: {} - {}", task.getName(), details);
	}
}
