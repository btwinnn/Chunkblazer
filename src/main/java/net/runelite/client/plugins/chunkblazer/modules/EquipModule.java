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
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;
import net.runelite.client.plugins.chunkblazer.TaskConstraints;
import net.runelite.client.plugins.chunkblazer.api.ItemEquippedReport;

import java.util.ArrayList;

/**
 * Module for handling EQUIP completion type tasks.
 * Tracks equipment changes and validates that required items are equipped.
 * Supports level requirements, slot constraints, and server-side verification.
 */
@Slf4j
@Singleton
public class EquipModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "EQUIP";

	// Chat colors for ChunkBlazer messages (matching other modules)
	private static final String COLOR_BLUE = "3366ff";        // [ChunkBlazer] branding
	private static final String COLOR_DARK_BLUE = "1a5276";   // Task Success (dark blue, readable)
	private static final String COLOR_DARK_GREEN = "228b22";  // Task Progress
	private static final String COLOR_RED = "ff3333";         // Task Failed
	private static final String COLOR_BLACK = "000000";       // Task name text

	// The 11 valid equipment slot indices (some indices are skipped in the game)
	// HEAD=0, CAPE=1, AMULET=2, WEAPON=3, BODY=4, SHIELD=5, LEGS=7, GLOVES=9, BOOTS=10, RING=12, AMMO=13
	private static final int[] VALID_EQUIPMENT_SLOTS = {0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13};

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track task-specific data
	// Map: taskId -> (Map: itemId -> required)
	private final Map<String, Map<Integer, Boolean>> taskTargetItems = new ConcurrentHashMap<>();

	// Track previous equipment state for detecting new equips
	private final Map<Integer, Integer> previousEquipment = new ConcurrentHashMap<>();

	// Items we're currently watching for (union of all task requirements)
	private final Set<Integer> watchedItemIds = ConcurrentHashMap.newKeySet();

	// Track items that were in inventory before equip (for server verification)
	private final Set<Integer> inventoryItemIds = ConcurrentHashMap.newKeySet();

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100; // Log every 100 ticks (~60 seconds)

	@Inject
	public EquipModule()
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
		// Handle both "EQUIP" completion type and "Equip" in name
		String type = task.getCompletionType();
		String category = task.getCategory();

		return (type != null && type.equalsIgnoreCase(COMPLETION_TYPE));
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
		log.info("=== EquipModule STARTED ===");
		log.info("EquipModule: eventBus={}, client={}", eventBus != null ? "OK" : "NULL", client != null ? "OK" : "NULL");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		previousEquipment.clear();
		taskTargetItems.clear();
		watchedItemIds.clear();
		inventoryItemIds.clear();
		log.info("EquipModule stopped");
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			log.info(">>> EquipModule.addActiveTask() ENTRY - calling super...");
			super.addActiveTask(task);
			log.info(">>> EquipModule.addActiveTask() - super returned");

			log.info("=== EquipModule: ADDING ACTIVE TASK ===");
			log.info("  Task Name: {}", task.getName());
			log.info("  Task ID: {}", task.getTaskId());
			log.info("  Completion Type: {}", task.getCompletionType());
			log.info("  Category: {}", task.getCategory());

			// Parse required items from task
			Map<Integer, Boolean> targetItems = new HashMap<>();
			List<RequiredItem> requiredItems = task.getRequiredItems();

			log.info("  Required Items: {}", requiredItems != null ? requiredItems.size() + " items" : "NULL");

			if (requiredItems != null)
			{
				for (RequiredItem item : requiredItems)
				{
					log.info("    Processing RequiredItem: {}", item);
					List<Integer> itemIds = item.getItemIds();
					log.info("      Item IDs: {}", itemIds);

					if (itemIds != null)
					{
						for (Integer itemId : itemIds)
						{
							targetItems.put(itemId, false); // false = not yet equipped
							watchedItemIds.add(itemId);
							log.info("      >>> WATCHING: Item ID {} ({}) for task '{}'",
								itemId, getItemName(itemId), task.getName());
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
				log.warn("  >>> WARNING: No required_items defined for this EQUIP task!");
			}

			taskTargetItems.put(task.getTaskId(), targetItems);
			log.info("  Total items being watched for this task: {}", targetItems.size());
			log.info("  All watched item IDs across all tasks: {}", watchedItemIds);
			log.info("=== END ADDING TASK ===");

			// Initialize tracking on client thread
			clientThread.invokeLater(() ->
			{
				initializeEquipmentTracking();
				initializeInventoryTracking();
				// Pass true to indicate this is initial check - handles already-equipped items
				checkTaskProgress(task, true);
			});
		}
		catch (Exception e)
		{
			log.error(">>> EquipModule.addActiveTask() EXCEPTION: ", e);
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
		previousEquipment.clear();
		inventoryItemIds.clear();
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
	 * Initialize tracking of current equipment state.
	 */
	private void initializeEquipmentTracking()
	{
		previousEquipment.clear();

		log.info(">>> EquipModule: Initializing equipment tracking...");

		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment != null)
		{
			Item[] items = equipment.getItems();
			for (int slot = 0; slot < items.length; slot++)
			{
				Item item = items[slot];
				if (item != null && item.getId() > 0)
				{
					previousEquipment.put(slot, item.getId());

					// Log if this is a watched item
					if (watchedItemIds.contains(item.getId()))
					{
						log.info(">>>   Already equipped watched item: {} (ID: {}) in slot {}",
							getItemName(item.getId()), item.getId(), getSlotName(slot));
					}
				}
			}
			log.info(">>> EquipModule: Initialized with {} equipped items", previousEquipment.size());
		}
		else
		{
			log.warn(">>> EquipModule: Equipment container is NULL during initialization!");
		}
	}

	/**
	 * Initialize tracking of inventory items (for server verification).
	 */
	private void initializeInventoryTracking()
	{
		inventoryItemIds.clear();

		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					int canonicalId = itemManager.canonicalize(item.getId());
					inventoryItemIds.add(canonicalId);
				}
			}
			log.info(">>> EquipModule: Tracking {} unique items in inventory", inventoryItemIds.size());
		}
	}

	/**
	 * Check progress for a specific task.
	 * @param isInitialCheck true if this is called during task assignment (for already-equipped items)
	 */
	private void checkTaskProgress(NuzlockeTask task)
	{
		checkTaskProgress(task, false);
	}

	/**
	 * Check progress for a specific task.
	 * @param isInitialCheck true if this is called during task assignment (for already-equipped items)
	 */
	private void checkTaskProgress(NuzlockeTask task, boolean isInitialCheck)
	{
		if (task == null)
		{
			return;
		}

		Map<Integer, Boolean> targetItems = taskTargetItems.get(task.getTaskId());
		if (targetItems == null || targetItems.isEmpty())
		{
			return;
		}

		// Check if any of the required items are currently equipped
		List<Integer> equippedIds = getEquippedItemIds();
		int totalRequired = targetItems.size();
		int totalEquipped = 0;
		int foundItemId = -1;
		int foundSlot = -1;

		StringBuilder itemDetails = new StringBuilder();
		for (Map.Entry<Integer, Boolean> target : targetItems.entrySet())
		{
			int itemId = target.getKey();
			boolean isEquipped = equippedIds.contains(itemId);

			if (isEquipped)
			{
				totalEquipped++;
				// Track the first equipped item for server report
				if (foundItemId == -1)
				{
					foundItemId = itemId;
					foundSlot = findSlotForItem(itemId);
				}
			}

			if (itemDetails.length() > 0)
			{
				itemDetails.append(", ");
			}
			itemDetails.append(getItemName(itemId)).append(": ").append(isEquipped ? "EQUIPPED" : "not equipped");
		}

		int previousProgress = task.getCurrentProgress();

		// For equip tasks, progress is binary per item (0 or 1)
		// The task is complete when any required item is equipped
		// (tasks typically only have one required item for equip)
		task.setCurrentProgress(totalEquipped);

		log.debug("EquipModule: Task '{}' progress: {}/{}", task.getName(), totalEquipped, totalRequired);

		// Check for completion (at least one required item equipped)
		if (totalEquipped > 0 && !task.isCompleted())
		{
			// Validate level requirements before marking complete
			String levelViolation = validateLevelRequirements(task, equippedIds);
			if (levelViolation != null)
			{
				log.warn("EquipModule: Task '{}' - Level requirement not met: {}", task.getName(), levelViolation);
				sendTaskFailure(task, levelViolation);
				return;
			}

			// Validate any equipment constraints
			String constraintViolation = validateEquipmentConstraints(task);
			if (constraintViolation != null)
			{
				log.warn("EquipModule: Task '{}' - Constraint violated: {}", task.getName(), constraintViolation);
				sendTaskFailure(task, constraintViolation);
				return;
			}

			log.info("EquipModule: Task '{}' COMPLETED! Item equipped.", task.getName());
			task.setCompleted(true);

			// EDGE CASE: If this is initial check (item already equipped on task assignment),
			// we need to send the server report here since ItemContainerChanged won't fire
			if (isInitialCheck && foundItemId > 0)
			{
				log.info("EquipModule: Item was already equipped - sending server report for verification");
				// Item was already equipped, not from inventory swap
				sendItemEquippedReport(foundItemId, foundSlot, -1, false);
			}

			// Send success chat message
			String successDetails = "Equipped: " + itemDetails.toString();
			sendTaskSuccess(task, successDetails);

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, totalEquipped);
			}

			// Clean up task tracking
			taskTargetItems.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatchedItems();
		}
		else if (totalEquipped > previousProgress && totalEquipped > 0)
		{
			// Progress was made but task not complete (multi-item equip task)
			sendTaskProgress(task, itemDetails.toString(), totalEquipped, totalRequired);

			if (completionCallback != null)
			{
				completionCallback.onProgressUpdated(task, totalEquipped);
			}
		}
	}

	/**
	 * Find which equipment slot contains the given item ID.
	 * @return slot index, or -1 if not found
	 */
	private int findSlotForItem(int itemId)
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return -1;
		}

		Item[] items = equipment.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item != null && item.getId() == itemId)
			{
				return slot;
			}
		}
		return -1;
	}

	/**
	 * Validate level requirements for equipping items.
	 * Returns null if valid, or error message if invalid.
	 */
	private String validateLevelRequirements(NuzlockeTask task, List<Integer> equippedIds)
	{
		// Check task-level requirements from the 'level' field
		if (task.getLevelRequirement() > 1)
		{
			int requiredLevel = task.getLevelRequirement();
			String category = task.getCategory();

			// Map category to skill
			Skill requiredSkill = mapCategoryToSkill(category);
			if (requiredSkill != null)
			{
				int playerLevel = client.getRealSkillLevel(requiredSkill);
				if (playerLevel < requiredLevel)
				{
					return String.format("Requires %s level %d (you have %d)",
						requiredSkill.getName(), requiredLevel, playerLevel);
				}
				log.info(">>> LEVEL CHECK PASSED for '{}': {} level {} >= required {}",
					task.getName(), requiredSkill.getName(), playerLevel, requiredLevel);
			}
		}

		// Check constraint-level requirements
		TaskConstraints constraints = task.getConstraints();
		if (constraints != null)
		{
			if (constraints.getRequiredLevel() > 1 && constraints.getRequiredSkill() != null)
			{
				Skill skill = Skill.valueOf(constraints.getRequiredSkill().toUpperCase());
				int playerLevel = client.getRealSkillLevel(skill);
				if (playerLevel < constraints.getRequiredLevel())
				{
					return String.format("Requires %s level %d (you have %d)",
						skill.getName(), constraints.getRequiredLevel(), playerLevel);
				}
			}

			// Combat level check
			if (constraints.getMinCombatLevel() != null)
			{
				Player player = client.getLocalPlayer();
				if (player != null && player.getCombatLevel() < constraints.getMinCombatLevel())
				{
					return String.format("Requires combat level %d (you have %d)",
						constraints.getMinCombatLevel(), player.getCombatLevel());
				}
			}
		}

		return null; // All level checks passed
	}

	/**
	 * Validate equipment-specific constraints.
	 */
	private String validateEquipmentConstraints(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();
		if (constraints == null)
		{
			return null;
		}

		List<Integer> equippedIds = getEquippedItemIds();

		// Check forbidden equipment
		List<Integer> forbiddenIds = constraints.getForbiddenEquipmentIds();
		if (forbiddenIds != null && !forbiddenIds.isEmpty())
		{
			for (Integer forbiddenId : forbiddenIds)
			{
				if (equippedIds.contains(forbiddenId))
				{
					return "Cannot have item " + getItemName(forbiddenId) + " equipped";
				}
			}
		}

		// Check allowed regions
		List<Integer> allowedRegions = constraints.getAllowedRegions();
		if (allowedRegions != null && !allowedRegions.isEmpty())
		{
			int currentRegion = getCurrentRegionId();
			if (!allowedRegions.contains(currentRegion))
			{
				return "Must be in an allowed region to equip this item";
			}
		}

		// Check required region
		if (constraints.getRequiredRegion() != null)
		{
			int currentRegion = getCurrentRegionId();
			if (currentRegion != constraints.getRequiredRegion())
			{
				return "Must be in specific region to equip this item";
			}
		}

		return null; // All constraints passed
	}

	/**
	 * Map task category to skill for level requirements.
	 */
	private Skill mapCategoryToSkill(String category)
	{
		if (category == null)
		{
			return null;
		}

		switch (category.toLowerCase())
		{
			case "attack":
				return Skill.ATTACK;
			case "strength":
				return Skill.STRENGTH;
			case "defence":
				return Skill.DEFENCE;
			case "ranged":
				return Skill.RANGED;
			case "magic":
				return Skill.MAGIC;
			case "prayer":
				return Skill.PRAYER;
			default:
				return null;
		}
	}

	/**
	 * Get list of currently equipped item IDs.
	 */
	private List<Integer> getEquippedItemIds()
	{
		List<Integer> ids = new ArrayList<>();
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);

		if (equipment == null)
		{
			return ids;
		}

		Item[] items = equipment.getItems();
		for (Item item : items)
		{
			if (item != null && item.getId() > 0)
			{
				ids.add(item.getId());
			}
		}

		return ids;
	}

	/**
	 * Get the item ID at a specific equipment slot.
	 */
	private int getItemAtSlot(int slotIndex)
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return -1;
		}

		Item[] items = equipment.getItems();
		if (slotIndex < 0 || slotIndex >= items.length)
		{
			return -1;
		}

		Item item = items[slotIndex];
		if (item == null || item.getId() <= 0)
		{
			return -1;
		}

		return item.getId();
	}

	/**
	 * Get a human-readable name for an equipment slot index.
	 */
	private String getSlotName(int slotIndex)
	{
		switch (slotIndex)
		{
			case 0: return "Head";
			case 1: return "Cape";
			case 2: return "Amulet";
			case 3: return "Weapon";
			case 4: return "Body";
			case 5: return "Shield";
			case 7: return "Legs";
			case 9: return "Gloves";
			case 10: return "Boots";
			case 12: return "Ring";
			case 13: return "Ammo";
			default: return "Slot " + slotIndex;
		}
	}

	/**
	 * Rebuild the set of watched item IDs from all active tasks.
	 */
	private void rebuildWatchedItems()
	{
		watchedItemIds.clear();
		for (Map<Integer, Boolean> items : taskTargetItems.values())
		{
			watchedItemIds.addAll(items.keySet());
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;

		// Update inventory tracking for server verification
		initializeInventoryTracking();

		// Log heartbeat periodically to confirm module is running
		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
			log.info(">>> EquipModule HEARTBEAT - tick {} - activeTasks: {}, watchedItems: {}",
				tickCounter, activeTasks.size(), watchedItemIds.size());

			// List all active equip tasks
			for (NuzlockeTask task : activeTasks)
			{
				Map<Integer, Boolean> items = taskTargetItems.get(task.getTaskId());
				String itemInfo = items != null ? "watching " + items.size() + " item IDs: " + items.keySet() : "NO ITEMS";
				log.info(">>>   Active equip task: {} ({}) - {}/{} - {}",
					task.getName(), task.getTaskId(),
					task.getCurrentProgress(), task.getTargetQuantity(), itemInfo);
			}

			if (activeTasks.isEmpty())
			{
				log.info(">>>   (No active EQUIP tasks)");
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();

		// Skip if no tasks or no watched items
		if (activeTasks.isEmpty())
		{
			return;
		}

		if (watchedItemIds.isEmpty())
		{
			return;
		}

		// Only track equipment changes
		if (containerId != InventoryID.EQUIPMENT.getId())
		{
			return;
		}

		log.info(">>> EquipModule: EQUIPMENT CHANGED - checking for watched items...");
		log.info(">>> Watched item IDs: {}", watchedItemIds);

		// Build current equipment state
		Map<Integer, Integer> currentEquipment = new HashMap<>();
		ItemContainer container = event.getItemContainer();
		if (container != null)
		{
			Item[] items = container.getItems();
			for (int slot = 0; slot < items.length; slot++)
			{
				Item item = items[slot];
				if (item != null && item.getId() > 0)
				{
					currentEquipment.put(slot, item.getId());

					// Log if this is a watched item
					if (watchedItemIds.contains(item.getId()))
					{
						log.info(">>>   FOUND watched item in equipment: {} (ID: {}) in slot {}",
							getItemName(item.getId()), item.getId(), getSlotName(slot));
					}
				}
			}
		}

		// Check for newly equipped watched items
		boolean anyNewEquips = false;
		for (int slot : VALID_EQUIPMENT_SLOTS)
		{
			int previousItemId = previousEquipment.getOrDefault(slot, -1);
			int currentItemId = currentEquipment.getOrDefault(slot, -1);

			// Check if a watched item was newly equipped in this slot
			if (currentItemId > 0 && currentItemId != previousItemId && watchedItemIds.contains(currentItemId))
			{
				log.info(">>> EquipModule: DETECTED newly equipped item: {} (ID: {}) in {} slot",
					getItemName(currentItemId), currentItemId, getSlotName(slot));
				anyNewEquips = true;

				// Check if item was in inventory (for server verification)
				boolean wasInInventory = inventoryItemIds.contains(currentItemId);

				// Send equipment report to server
				sendItemEquippedReport(currentItemId, slot, previousItemId, wasInInventory);
			}
		}

		// Update previous state
		previousEquipment.clear();
		previousEquipment.putAll(currentEquipment);

		// Check progress for all tasks if any watched items were equipped
		if (anyNewEquips)
		{
			log.info(">>> New equips detected - checking progress for {} active tasks", activeTasks.size());
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				checkTaskProgress(task);
			}
		}
	}

	/**
	 * Get item name from ID.
	 */
	private String getItemName(int itemId)
	{
		try
		{
			if (client.isClientThread())
			{
				return itemManager.getItemComposition(itemId).getName();
			}
			else
			{
				return "Item#" + itemId;
			}
		}
		catch (Exception e)
		{
			return "Item#" + itemId;
		}
	}

	/**
	 * Send item equipped report to API for server verification.
	 */
	private void sendItemEquippedReport(int itemId, int slot, int previousItemInSlot, boolean wasInInventory)
	{
		// Find which task this item belongs to
		String taskId = "";
		int requiredLevel = 0;
		String requiredSkill = null;

		for (NuzlockeTask task : activeTasks)
		{
			Map<Integer, Boolean> items = taskTargetItems.get(task.getTaskId());
			if (items != null && items.containsKey(itemId))
			{
				taskId = task.getTaskId();
				requiredLevel = task.getLevelRequirement();
				requiredSkill = task.getCategory();
				break;
			}
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}

		// Generate inventory state hash for anti-cheat
		String inventoryHash = generateInventoryHash();

		ItemEquippedReport report = ItemEquippedReport.builder()
			.playerHash(getPlayerHash())
			.taskId(taskId)
			.itemId(itemId)
			.itemName(getItemName(itemId))
			.equipmentSlot(slot)
			.equipmentSlotName(getSlotName(slot))
			.regionId(getCurrentRegionId())
			.worldX(player.getWorldLocation().getX())
			.worldY(player.getWorldLocation().getY())
			.plane(player.getWorldLocation().getPlane())
			.gameTick(getGameTick())
			.timestamp(System.currentTimeMillis())
			.geValue(itemManager.getItemPrice(itemId))
			.playerCombatLevel(player.getCombatLevel())
			// Skill levels for server verification
			.attackLevel(client.getRealSkillLevel(Skill.ATTACK))
			.strengthLevel(client.getRealSkillLevel(Skill.STRENGTH))
			.defenceLevel(client.getRealSkillLevel(Skill.DEFENCE))
			.rangedLevel(client.getRealSkillLevel(Skill.RANGED))
			.magicLevel(client.getRealSkillLevel(Skill.MAGIC))
			.prayerLevel(client.getRealSkillLevel(Skill.PRAYER))
			// Equipment state
			.allEquippedItemIds(getEquippedItemIds())
			.previousItemInSlot(previousItemInSlot)
			.fromInventory(wasInInventory)
			// Constraints
			.hasLevelRequirements(requiredLevel > 1)
			.requiredLevel(requiredLevel)
			.requiredSkill(requiredSkill)
			// Anti-cheat
			.inventoryStateHash(inventoryHash)
			.itemWasInInventory(wasInInventory)
			.worldType(client.getWorldType().stream().mapToInt(Enum::ordinal).sum())
			.build();

		apiClient.reportItemEquipped(report)
			.thenAccept(response ->
			{
				if (response != null && !response.isSuccess())
				{
					log.warn("EquipModule: Server rejected equip report: {}", response.getErrorMessage());
				}
				else
				{
					log.info("EquipModule: Server verified equip of {} in {} slot", getItemName(itemId), getSlotName(slot));
				}
			})
			.exceptionally(ex ->
			{
				log.debug("EquipModule: Failed to send equip report (API may be offline): {}", ex.getMessage());
				return null;
			});
	}

	/**
	 * Generate a hash of the current inventory state for anti-cheat verification.
	 */
	private String generateInventoryHash()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null)
		{
			return "empty";
		}

		StringBuilder sb = new StringBuilder();
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() > 0)
			{
				sb.append(item.getId()).append(":").append(item.getQuantity()).append(",");
			}
		}

		// Simple hash
		return Integer.toHexString(sb.toString().hashCode());
	}

	// ==================== CHAT MESSAGE METHODS ====================

	/**
	 * Send a task progress message to the player's chatbox.
	 */
	private void sendTaskProgress(NuzlockeTask task, String details, int current, int total)
	{
		if (!config.showChatProgress())
		{
			log.info("[CHAT] Equip progress (hidden by config): {} - {}", task.getName(), details);
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

		log.info("[CHAT] Equip progress: {} ({}/{}) - {}", task.getName(), current, total, details);
	}

	/**
	 * Send a task success message to the player's chatbox.
	 */
	private void sendTaskSuccess(NuzlockeTask task, String details)
	{
		if (!config.showChatSuccess())
		{
			log.info("[CHAT] Equip success (hidden by config): {} - {}", task.getName(), details);
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

		log.info("[CHAT] Equip success: {} - {}", task.getName(), details);
	}

	/**
	 * Send a task failure message to the player's chatbox.
	 */
	private void sendTaskFailure(NuzlockeTask task, String reason)
	{
		if (!config.showChatFailed())
		{
			log.info("[CHAT] Equip failed (hidden by config): {} - Reason: {}", task.getName(), reason);
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_RED + ">Task Failed:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col>";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

		String reasonMessage = "  - Reason: " + reason;

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(reasonMessage)
			.build());

		log.info("[CHAT] Equip failed: {} - Reason: {}", task.getName(), reason);
	}
}
