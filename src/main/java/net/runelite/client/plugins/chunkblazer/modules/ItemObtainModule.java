package net.runelite.client.plugins.chunkblazer.modules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;
import net.runelite.client.plugins.chunkblazer.api.ItemObtainedReport;

/**
 * Module for handling ITEM_OBTAIN completion type tasks.
 * Tracks item acquisition in inventory.
 */
@Slf4j
@Singleton
public class ItemObtainModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "ITEM_OBTAIN";

	@Inject
	private ItemManager itemManager;

	// Track previous inventory state
	private final Map<Integer, Integer> previousInventory = new HashMap<>();

	// Target items for current task
	private final Map<Integer, Integer> targetItems = new HashMap<>(); // itemId -> required quantity

	@Inject
	public ItemObtainModule()
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
		// Don't initialize inventory here - it must be done on client thread
		// Inventory tracking will be initialized when a task is assigned
		log.info("ItemObtainModule started");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		previousInventory.clear();
		targetItems.clear();
		log.info("ItemObtainModule stopped");
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		super.onTaskAssigned(task);

		// Parse required items from task
		targetItems.clear();
		List<RequiredItem> requiredItems = task.getRequiredItems();
		if (requiredItems != null)
		{
			for (RequiredItem item : requiredItems)
			{
				// Add all item IDs from this required item
				List<Integer> itemIds = item.getItemIds();
				if (itemIds != null)
				{
					for (Integer itemId : itemIds)
					{
						targetItems.put(itemId, item.getRequiredQuantity());
					}
				}
			}
		}

		log.info("Tracking {} item IDs for task", targetItems.size());

		// Initialize inventory tracking and check progress on client thread
		clientThread.invokeLater(() ->
		{
			initializeInventoryTracking();
			checkCurrentProgress();
		});
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		targetItems.clear();
	}

	@Override
	public void checkProgress()
	{
		checkCurrentProgress();
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
					previousInventory.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}

		// Also track bank if needed
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank != null)
		{
			for (Item item : bank.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					previousInventory.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
	}

	private void checkCurrentProgress()
	{
		if (activeTask == null || targetItems.isEmpty())
		{
			return;
		}

		int totalRequired = 0;
		int totalObtained = 0;

		for (Map.Entry<Integer, Integer> target : targetItems.entrySet())
		{
			int itemId = target.getKey();
			int required = target.getValue();
			totalRequired += required;

			int current = getItemCount(itemId);
			totalObtained += Math.min(current, required);
		}

		currentProgress = totalObtained;
		if (activeTask != null)
		{
			activeTask.setCurrentProgress(totalObtained);
		}

		if (totalObtained >= totalRequired)
		{
			log.info("All required items obtained!");
			onTaskCompleted();
		}
	}

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

		// Also check bank
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank != null)
		{
			for (Item item : bank.getItems())
			{
				if (item != null && item.getId() == itemId)
				{
					count += item.getQuantity();
				}
			}
		}

		return count;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (activeTask == null || targetItems.isEmpty())
		{
			return;
		}

		// Only track inventory and bank changes
		int containerId = event.getContainerId();
		if (containerId != InventoryID.INVENTORY.getId() && containerId != InventoryID.BANK.getId())
		{
			return;
		}

		// Build current state
		Map<Integer, Integer> currentInventory = new HashMap<>();
		ItemContainer container = event.getItemContainer();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					currentInventory.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}

		// Check for newly obtained target items
		for (int targetItemId : targetItems.keySet())
		{
			int previousCount = previousInventory.getOrDefault(targetItemId, 0);
			int currentCount = currentInventory.getOrDefault(targetItemId, 0);

			if (currentCount > previousCount)
			{
				int obtained = currentCount - previousCount;
				log.info("Obtained {} x {} (target item)", obtained, getItemName(targetItemId));
				sendItemObtainedReport(targetItemId, obtained, containerId == InventoryID.INVENTORY.getId() ? "INVENTORY" : "BANK");
			}
		}

		// Update previous state for this container
		for (int itemId : currentInventory.keySet())
		{
			previousInventory.put(itemId, currentInventory.get(itemId));
		}

		// Check overall progress
		checkCurrentProgress();
	}

	private String getItemName(int itemId)
	{
		return itemManager.getItemComposition(itemId).getName();
	}

	private void sendItemObtainedReport(int itemId, int quantity, String destination)
	{
		ItemObtainedReport report = ItemObtainedReport.builder()
			.playerHash(getPlayerHash())
			.taskId(activeTask != null ? activeTask.getTaskId() : "")
			.itemId(itemId)
			.itemName(getItemName(itemId))
			.quantity(quantity)
			.source("UNKNOWN") // Could be enhanced to track source
			.sourceId(-1)
			.regionId(getCurrentRegionId())
			.worldX(client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation().getX() : 0)
			.worldY(client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation().getY() : 0)
			.plane(client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation().getPlane() : 0)
			.gameTick(getGameTick())
			.timestamp(System.currentTimeMillis())
			.geValue(itemManager.getItemPrice(itemId))
			.destination(destination)
			.build();

		apiClient.reportItemObtained(report)
			.thenAccept(this::handleVerificationResponse);
	}
}
