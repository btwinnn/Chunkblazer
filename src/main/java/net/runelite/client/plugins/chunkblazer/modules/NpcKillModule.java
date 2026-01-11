package net.runelite.client.plugins.chunkblazer.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.TargetNpc;
import net.runelite.client.plugins.chunkblazer.TaskConstraints;
import net.runelite.client.plugins.chunkblazer.api.NpcKillReport;
import net.runelite.client.plugins.chunkblazer.verification.VarPlayerVerificationService;

/**
 * Module for handling NPC_KILL completion type tasks.
 * Tracks NPC kills and verifies them with the server.
 */
@Slf4j
@Singleton
public class NPCKillModule extends AbstractTaskModule
{
	// Handles both NPC_KILL and COMBAT task types
	private static final String COMPLETION_TYPE = "NPC_KILL";
	private static final String COMBAT_TYPE = "COMBAT";

	@Inject
	private VarPlayerVerificationService varPlayerService;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track the NPC we're currently fighting
	private NPC currentTarget;
	private int currentTargetIndex = -1; // Track by index to avoid reference issues
	private int damageDealtToTarget;
	private int lastKillingBlowAnimation;

	// Equipment constraints are now checked per-task at kill time (no global flag needed)

	// Time constraint tracking - track when combat started (first hitsplat)
	private int combatStartTick = -1;

	// For boss KC verification
	private int baselineKc = -1;
	private String currentBossName;

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100; // Log every 100 ticks (~60 seconds)

	// Pending drop-based kills: tasks waiting for a specific item to drop
	// Key: task ID, Value: pending kill info
	private final Map<String, PendingDropKill> pendingDropKills = new HashMap<>();
	private static final int PENDING_DROP_TIMEOUT_TICKS = 10; // ~6 seconds to pick up item

	/**
	 * Tracks a kill that's pending verification via dropped item.
	 */
	private static class PendingDropKill
	{
		final NuzlockeTask task;
		final NPC killedNpc;
		final WorldPoint deathLocation;
		final int deathTick;
		final List<Integer> requiredItemIds;
		final int requiredQuantity;
		int collectedQuantity = 0;

		PendingDropKill(NuzlockeTask task, NPC npc, WorldPoint location, int tick,
						List<Integer> requiredItemIds, int requiredQuantity)
		{
			this.task = task;
			this.killedNpc = npc;
			this.deathLocation = location;
			this.deathTick = tick;
			this.requiredItemIds = requiredItemIds;
			this.requiredQuantity = requiredQuantity;
		}
	}

	@Inject
	public NPCKillModule()
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
		// Check completion_type first
		String type = task.getCompletionType();
		if (type != null)
		{
			// Handle NPC_KILL and COMBAT completion types
			if (COMPLETION_TYPE.equalsIgnoreCase(type) || COMBAT_TYPE.equalsIgnoreCase(type))
			{
				return true;
			}
		}

		// Also check category field for "combat" tasks
		String category = task.getCategory();
		if (category != null && COMBAT_TYPE.equalsIgnoreCase(category))
		{
			return true;
		}

		return false;
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
		log.info("=== NpcKillModule STARTED - Event bus registered ===");
		log.info("NpcKillModule: eventBus={}, client={}", eventBus != null ? "OK" : "NULL", client != null ? "OK" : "NULL");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		currentTarget = null;
		currentTargetIndex = -1;
		damageDealtToTarget = 0;
		combatStartTick = -1;
		pendingDropKills.clear();
		log.info("NpcKillModule stopped");
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		super.onTaskAssigned(task);
		currentTarget = null;
		currentTargetIndex = -1;
		damageDealtToTarget = 0;
		combatStartTick = -1;

		// Log task assignment with full details
		log.info("=== [TASK ASSIGNED] ===");
		log.info("  Task Name: {}", task.getName());
		log.info("  Task ID: {}", task.getTaskId());
		log.info("  Completion Type: {}", task.getCompletionType());
		log.info("  Progress: {}/{}", task.getCurrentProgress(), task.getTargetQuantity());

		// Log target NPC details
		TargetNpc targetNpc = task.getTargetNpc();
		if (targetNpc != null)
		{
			log.info("  Target NPC: {} (IDs: {})", targetNpc.getName(), targetNpc.getNpcIds());
		}
		else
		{
			log.info("  Target NPC: (none specified)");
		}

		// Log constraint details
		TaskConstraints constraints = task.getConstraints();
		if (constraints != null)
		{
			log.info("  === CONSTRAINTS ===");
			if (constraints.hasTimeLimit())
			{
				log.info("    Time Limit: {} ticks ({} seconds)",
					constraints.getTimeInTicks(), constraints.getTimeInSeconds());
			}
			if (constraints.hasEquipmentConstraints())
			{
				log.info("    Equipment Constraints: YES");
				if (constraints.isNoEquipment())
				{
					log.info("      - no_equipment: true (must have nothing equipped)");
				}
				if (constraints.isEquipNothing())
				{
					log.info("      - equip_nothing: true (must have zero equipment)");
				}
				if (constraints.getRequiredEquipmentIds() != null)
				{
					log.info("      - required_equipment_ids: {}", constraints.getRequiredEquipmentIds());
				}
				if (constraints.getAllowedEquipmentIds() != null)
				{
					log.info("      - allowed_equipment_ids: {}", constraints.getAllowedEquipmentIds());
				}
				if (constraints.getForbiddenEquipmentIds() != null)
				{
					log.info("      - forbidden_equipment_ids: {}", constraints.getForbiddenEquipmentIds());
				}
				if (constraints.getMustBeEmptySlots() != null)
				{
					log.info("      - must_be_empty slots: {}", constraints.getMustBeEmptySlots());
				}
				if (constraints.getEquippableSlots() != null)
				{
					log.info("      - equippable_slots: {}", constraints.getEquippableSlots());
				}
			}
			if (constraints.hasDroppedItemConstraint())
			{
				log.info("    Dropped Item Constraint: YES");
				log.info("      - dropped_item: {}", constraints.getDroppedItem());
				log.info("      - dropped_item_ids: {}", constraints.getDroppedItemIds());
				log.info("      - quantity: {}", constraints.getDroppedItemQuantity());
			}
			if (!constraints.hasTimeLimit() && !constraints.hasEquipmentConstraints() && !constraints.hasDroppedItemConstraint())
			{
				log.info("    (No active constraints)");
			}
		}
		else
		{
			log.info("  Constraints: (none)");
		}
		log.info("=== END TASK DETAILS ===");

		// For boss tasks, get baseline KC from VarPlayer (instant server-side)
		if (targetNpc != null)
		{
			String bossName = targetNpc.getName();
			if (bossName != null && varPlayerService.isBossTracked(bossName))
			{
				currentBossName = bossName;
				baselineKc = varPlayerService.getBossKillCount(bossName);
				log.info("Boss task detected: {} - baseline KC from VarPlayer: {}", bossName, baselineKc);
			}
			else
			{
				currentBossName = null;
				baselineKc = -1;
			}
		}
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		currentTarget = null;
		currentTargetIndex = -1;
		damageDealtToTarget = 0;
		combatStartTick = -1;
		baselineKc = -1;
		currentBossName = null;
		pendingDropKills.clear();
		log.info("[TASK DEBUG] Task cleared - reset all tracking state");
	}

	@Override
	public void checkProgress()
	{
		// For NPC kills, progress is tracked via events
		// Could add a sync check with hiscores here for verification
		log.info("NPC Kill progress check: {}/{}",
			currentProgress, activeTask != null ? activeTask.getTargetQuantity() : 0);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;
		int currentTick = client.getTickCount();

		// Check for expired pending drop kills
		if (!pendingDropKills.isEmpty())
		{
			Iterator<Map.Entry<String, PendingDropKill>> it = pendingDropKills.entrySet().iterator();
			while (it.hasNext())
			{
				Map.Entry<String, PendingDropKill> entry = it.next();
				PendingDropKill pending = entry.getValue();
				int elapsed = currentTick - pending.deathTick;

				if (elapsed > PENDING_DROP_TIMEOUT_TICKS)
				{
					String dropName = pending.task.getConstraints().getDroppedItem();
					String reason = String.format("Required drop '%s' was not received (collected %d/%d)",
						dropName, pending.collectedQuantity, pending.requiredQuantity);

					log.warn("[TASK FAILED] Task '{}' (ID: {}) - {} - IDs checked: {}",
						pending.task.getName(), pending.task.getTaskId(), reason, pending.requiredItemIds);

					// Log all ground items near death location for debugging
					log.info("[TASK DEBUG] Logging ground items near death location for debugging:");
					logAllGroundItemsNearLocation(pending.deathLocation, 2);

					// Send failure to chatbox
					sendTaskFailure(pending.task, reason);

					it.remove();
				}
			}
		}

		// Log heartbeat periodically to confirm event bus is working
		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
			log.info(">>> NpcKillModule HEARTBEAT - tick {} - activeTasks: {}, currentTarget: {}, pendingDropKills: {}",
				tickCounter, activeTasks.size(), currentTarget != null ? currentTarget.getName() : "none",
				pendingDropKills.size());

			// List all active combat tasks
			for (NuzlockeTask task : activeTasks)
			{
				TargetNpc targetNpc = task.getTargetNpc();
				String npcInfo = targetNpc != null ? "NPC IDs: " + targetNpc.getNpcIds() : "no target NPC";
				TaskConstraints constraints = task.getConstraints();
				String constraintInfo = getConstraintSummary(constraints);
				log.info(">>>   Active combat task: {} ({}) - {}/{} - {} - Constraints: [{}]",
					task.getName(), task.getTaskId(),
					task.getCurrentProgress(), task.getTargetQuantity(), npcInfo, constraintInfo);
			}

			// List pending drop kills
			for (Map.Entry<String, PendingDropKill> entry : pendingDropKills.entrySet())
			{
				PendingDropKill pending = entry.getValue();
				log.info(">>>   Pending drop kill: {} - waiting for item IDs {} ({}/{} collected)",
					pending.task.getName(), pending.requiredItemIds,
					pending.collectedQuantity, pending.requiredQuantity);
			}
		}
	}

	/**
	 * Get a summary of task constraints for logging.
	 */
	private String getConstraintSummary(TaskConstraints constraints)
	{
		if (constraints == null)
		{
			return "none";
		}

		List<String> parts = new ArrayList<>();

		if (constraints.hasTimeLimit())
		{
			parts.add("time:" + constraints.getTimeInTicks() + "t");
		}
		if (constraints.hasEquipmentConstraints())
		{
			parts.add("equipment");
		}
		if (constraints.hasDroppedItemConstraint())
		{
			parts.add("drop:" + constraints.getDroppedItem() + " IDs:" + constraints.getDroppedItemIds());
		}

		return parts.isEmpty() ? "none" : String.join(", ", parts);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		Actor source = event.getSource();
		Actor target = event.getTarget();

		// Log all player interactions for debugging
		if (source == client.getLocalPlayer() && target instanceof NPC)
		{
			NPC npc = (NPC) target;
			log.info(">>> PLAYER ATTACKING NPC: {} (ID: {}, Index: {}) - activeTasks count: {}",
				npc.getName(), npc.getId(), npc.getIndex(), activeTasks.size());

			// Always track the current target if we have any active tasks
			if (!activeTasks.isEmpty())
			{
				// Only reset tracking if switching to a NEW target
				boolean isSameTarget = (currentTargetIndex == npc.getIndex());

				if (!isSameTarget)
				{
					// New target - reset everything
					currentTarget = npc;
					currentTargetIndex = npc.getIndex();
					damageDealtToTarget = 0;
					combatStartTick = -1; // Reset combat timer for new target
					log.info(">>> NEW target: {} (ID: {}, Index: {}) - reset tracking", npc.getName(), npc.getId(), npc.getIndex());
				}
				else
				{
					// Same target - just update reference
					currentTarget = npc;
					log.info(">>> SAME target: {} (Index: {})", npc.getName(), npc.getIndex());
				}
				// Equipment constraints are checked per-task at kill time in onActorDeath
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (currentTarget == null)
		{
			return;
		}

		// Track damage dealt to our target
		if (event.getActor() == currentTarget)
		{
			// Only count damage from the player
			if (event.getHitsplat().isMine())
			{
				int damage = event.getHitsplat().getAmount();
				damageDealtToTarget += damage;

				// Track combat start tick (first hitsplat on this target)
				if (combatStartTick < 0)
				{
					combatStartTick = client.getTickCount();
					log.info(">>> COMBAT STARTED on {} at tick {}", currentTarget.getName(), combatStartTick);
				}

				log.info(">>> DAMAGE DEALT to {}: {} (total: {}, combat tick: {})",
					currentTarget.getName(), damage, damageDealtToTarget, combatStartTick);

				// Track animation for verification
				Player player = client.getLocalPlayer();
				if (player != null)
				{
					lastKillingBlowAnimation = player.getAnimation();
				}
			}
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		if (pendingDropKills.isEmpty())
		{
			return;
		}

		TileItem item = event.getItem();
		WorldPoint itemLocation = event.getTile().getWorldLocation();
		int itemId = item.getId();
		int quantity = item.getQuantity();
		int ownership = item.getOwnership();
		int currentTick = client.getTickCount();

		log.info("[ITEM SPAWNED] Item ID {} (qty: {}) at {} - ownership: {}",
			itemId, quantity, itemLocation, getOwnershipName(ownership));

		// Check all pending drop kills
		Iterator<Map.Entry<String, PendingDropKill>> it = pendingDropKills.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, PendingDropKill> entry = it.next();
			PendingDropKill pending = entry.getValue();

			// OWNERSHIP CHECK: Must be our drop (OWNERSHIP_SELF only - no group ironmen in this mode)
			// OWNERSHIP_NONE (0) = public, OWNERSHIP_SELF (1) = ours, OWNERSHIP_OTHER (2) = someone else's
			if (ownership != TileItem.OWNERSHIP_SELF)
			{
				log.info("[ITEM SPAWNED] Skipping item {} - not our drop (ownership: {})",
					itemId, getOwnershipName(ownership));
				continue;
			}

			// TIMING CHECK: Item must spawn within 5 ticks of NPC death
			// (drops can be delayed by animations, network latency, etc.)
			int ticksSinceDeath = currentTick - pending.deathTick;
			if (ticksSinceDeath > 5)
			{
				log.info("[ITEM SPAWNED] Skipping item {} - spawned {} ticks after NPC death (max 5)",
					itemId, ticksSinceDeath);
				continue;
			}

			// LOCATION CHECK: Item must spawn at or very near the death location (within 1 tile)
			// NPC drops spawn at the NPC's location, so this should be exact or 1 tile away
			int distance = itemLocation.distanceTo(pending.deathLocation);
			if (distance > 1)
			{
				log.info("[ITEM SPAWNED] Skipping item {} - too far from death location ({} tiles)",
					itemId, distance);
				continue;
			}

			// Check if this is one of the required items
			if (!pending.requiredItemIds.contains(itemId))
			{
				continue;
			}

			// Found a matching drop that belongs to us!
			pending.collectedQuantity += quantity;
			log.info("[TASK DEBUG] Task '{}' - OUR required drop found! Item ID {} (qty: {}), ownership: {}, " +
					"distance: {} tiles, ticks since death: {}, total collected: {}/{}",
				pending.task.getName(), itemId, quantity, getOwnershipName(ownership),
				distance, ticksSinceDeath, pending.collectedQuantity, pending.requiredQuantity);

			// Check if we've collected enough
			if (pending.collectedQuantity >= pending.requiredQuantity)
			{
				String dropName = pending.task.getConstraints().getDroppedItem();
				log.info("[TASK SUCCESS] Task '{}' (ID: {}) - All required drops obtained! Crediting kill.",
					pending.task.getName(), pending.task.getTaskId());

				// Send progress to chatbox
				String details = String.format("Killed %s and received %s drop",
					pending.killedNpc.getName(), dropName);
				sendTaskProgress(pending.task, details);

				// Credit the kill
				sendKillReport(pending.killedNpc, pending.task);
				incrementTaskProgress(pending.task, 1);

				it.remove();
			}
		}
	}

	/**
	 * Get human-readable name for item ownership value.
	 */
	private String getOwnershipName(int ownership)
	{
		switch (ownership)
		{
			case TileItem.OWNERSHIP_NONE: return "PUBLIC";
			case TileItem.OWNERSHIP_SELF: return "SELF";
			case TileItem.OWNERSHIP_OTHER: return "OTHER";
			case TileItem.OWNERSHIP_GROUP: return "GROUP";
			default: return "UNKNOWN(" + ownership + ")";
		}
	}

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";        // [ChunkBlazer] branding
	private static final String COLOR_RED = "ff3333";         // Task Failed
	private static final String COLOR_LIGHT_GREEN = "66ff66"; // Task Success
	private static final String COLOR_DARK_GREEN = "228b22";  // Task Progress
	private static final String COLOR_BLACK = "000000";       // Task name text

	/**
	 * Send a task success message to the player's chatbox.
	 * Used when a task is fully completed.
	 */
	private void sendTaskSuccess(NuzlockeTask task, String details)
	{
		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_LIGHT_GREEN + ">Task Success:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col> " +
			"(" + (task.getCurrentProgress() + 1) + "/" + task.getTargetQuantity() + ")";

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

		log.info("[CHAT] Task success: {} - {}", task.getName(), details);
	}

	/**
	 * Send a task progress message to the player's chatbox.
	 * Used when progress is made but task is not yet complete.
	 */
	private void sendTaskProgress(NuzlockeTask task, String details)
	{
		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_GREEN + ">Task Progress:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col> " +
			"(" + (task.getCurrentProgress() + 1) + "/" + task.getTargetQuantity() + ")";

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

		log.info("[CHAT] Task progress: {} - {}", task.getName(), details);
	}

	/**
	 * Send a task failure message to the player's chatbox.
	 */
	private void sendTaskFailure(NuzlockeTask task, String reason)
	{
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

		log.info("[CHAT] Task failed: {} - Reason: {}", task.getName(), reason);
	}

	/**
	 * Log all ground items at and around a location for debugging.
	 */
	private void logAllGroundItemsNearLocation(WorldPoint center, int radius)
	{
		log.info("[GROUND ITEMS] Scanning {}x{} area around {} for ground items...",
			(radius * 2 + 1), (radius * 2 + 1), center);

		Scene scene = client.getScene();
		Tile[][][] tiles = scene.getTiles();
		int plane = center.getPlane();
		int itemsFound = 0;

		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				WorldPoint checkPoint = new WorldPoint(
					center.getX() + dx,
					center.getY() + dy,
					plane
				);

				LocalPoint localPoint = LocalPoint.fromWorld(client, checkPoint);
				if (localPoint == null)
				{
					continue;
				}

				int sceneX = localPoint.getSceneX();
				int sceneY = localPoint.getSceneY();

				if (sceneX < 0 || sceneX >= tiles[plane].length ||
					sceneY < 0 || sceneY >= tiles[plane][sceneX].length)
				{
					continue;
				}

				Tile tile = tiles[plane][sceneX][sceneY];
				if (tile == null)
				{
					continue;
				}

				List<TileItem> groundItems = tile.getGroundItems();
				if (groundItems == null || groundItems.isEmpty())
				{
					continue;
				}

				for (TileItem item : groundItems)
				{
					itemsFound++;
					String itemName = client.getItemDefinition(item.getId()).getName();
					log.info("[GROUND ITEMS]   ({},{}) Item: {} (ID: {}) qty: {} ownership: {}",
						dx, dy, itemName, item.getId(), item.getQuantity(),
						getOwnershipName(item.getOwnership()));
				}
			}
		}

		if (itemsFound == 0)
		{
			log.info("[GROUND ITEMS] No ground items found in area");
		}
		else
		{
			log.info("[GROUND ITEMS] Total items found: {}", itemsFound);
		}
	}

	/**
	 * Check ground items at a specific location for matching item IDs.
	 * Only counts items that belong to us (OWNERSHIP_SELF).
	 *
	 * @param location The world location to check
	 * @param requiredItemIds List of item IDs we're looking for
	 * @return Total quantity of matching items found that belong to us
	 */
	private int checkGroundItemsAtLocation(WorldPoint location, List<Integer> requiredItemIds)
	{
		int totalFound = 0;

		// Convert world point to local point for tile lookup
		LocalPoint localPoint = LocalPoint.fromWorld(client, location);
		if (localPoint == null)
		{
			log.info("[GROUND CHECK] Could not convert world location {} to local point", location);
			return 0;
		}

		// Get the scene and tile
		Scene scene = client.getScene();
		Tile[][][] tiles = scene.getTiles();
		int plane = location.getPlane();

		// Convert local point to scene coordinates
		int sceneX = localPoint.getSceneX();
		int sceneY = localPoint.getSceneY();

		// Bounds check
		if (sceneX < 0 || sceneX >= tiles[plane].length ||
			sceneY < 0 || sceneY >= tiles[plane][sceneX].length)
		{
			log.info("[GROUND CHECK] Scene coordinates out of bounds: ({}, {})", sceneX, sceneY);
			return 0;
		}

		Tile tile = tiles[plane][sceneX][sceneY];
		if (tile == null)
		{
			log.info("[GROUND CHECK] No tile at scene coordinates ({}, {})", sceneX, sceneY);
			return 0;
		}

		// Check ground items on this tile
		List<TileItem> groundItems = tile.getGroundItems();
		if (groundItems == null || groundItems.isEmpty())
		{
			log.info("[GROUND CHECK] No ground items at {} (scene: {}, {})", location, sceneX, sceneY);
			return 0;
		}

		log.info("[GROUND CHECK] Found {} ground items at {} - checking for IDs: {}",
			groundItems.size(), location, requiredItemIds);

		for (TileItem item : groundItems)
		{
			int itemId = item.getId();
			int ownership = item.getOwnership();
			int quantity = item.getQuantity();

			log.info("[GROUND CHECK]   Item ID: {}, qty: {}, ownership: {}",
				itemId, quantity, getOwnershipName(ownership));

			// Only count items that belong to us
			if (ownership != TileItem.OWNERSHIP_SELF)
			{
				log.info("[GROUND CHECK]   Skipping - not our item");
				continue;
			}

			// Check if this is one of the required items
			if (requiredItemIds.contains(itemId))
			{
				totalFound += quantity;
				log.info("[GROUND CHECK]   MATCH! Running total: {}", totalFound);
			}
		}

		return totalFound;
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) actor;

		// Debug: Log every NPC death
		log.info("DEBUG: NPC died - Name: {}, ID: {}, Index: {}, activeTasks: {}, currentTargetIndex: {}, damageDealt: {}",
			npc.getName(), npc.getId(), npc.getIndex(), activeTasks.size(),
			currentTargetIndex, damageDealtToTarget);

		if (activeTasks.isEmpty())
		{
			log.info("DEBUG: No active tasks to check");
			return;
		}

		// STRICT CHECK: Only credit kills where we damaged THIS SPECIFIC NPC
		// Must match by index (unique per NPC instance) AND have dealt damage
		boolean wasOurKill = (currentTargetIndex == npc.getIndex()) && (damageDealtToTarget > 0);

		// Also check by reference as backup (same object in memory)
		if (!wasOurKill && currentTarget == npc && damageDealtToTarget > 0)
		{
			wasOurKill = true;
		}

		if (!wasOurKill)
		{
			log.info("DEBUG: Not our kill - targetIndex: {}, npcIndex: {}, damage: {}",
				currentTargetIndex, npc.getIndex(), damageDealtToTarget);
			return;
		}

		log.info(">>> CONFIRMED OUR KILL: {} (Index: {}, Damage dealt: {})",
			npc.getName(), npc.getIndex(), damageDealtToTarget);

		// Check all active tasks for a match
		List<NuzlockeTask> matchingTasks = findMatchingTasks(npc);

		// Debug: Log what we're checking against
		for (NuzlockeTask task : activeTasks)
		{
			TargetNpc targetNpc = task.getTargetNpc();
			if (targetNpc != null)
			{
				log.info("DEBUG: Checking task '{}' - expected NPC IDs: {}, killed NPC ID: {}, matches: {}",
					task.getName(), targetNpc.getNpcIds(), npc.getId(), targetNpc.matchesNpcId(npc.getId()));
			}
		}

		if (matchingTasks.isEmpty())
		{
			log.info("DEBUG: NPC {} (ID: {}) doesn't match any active tasks", npc.getName(), npc.getId());
			// Reset tracking anyway
			currentTarget = null;
			currentTargetIndex = -1;
			damageDealtToTarget = 0;
			return;
		}

		log.info("Target NPC killed: {} (damage dealt: {}, matching {} tasks)",
			npc.getName(), damageDealtToTarget, matchingTasks.size());

		// Update progress for all matching tasks
		for (NuzlockeTask task : matchingTasks)
		{
			TaskConstraints constraints = task.getConstraints();
			boolean hasDropConstraint = constraints != null && constraints.hasDroppedItemConstraint();
			boolean hasTimeConstraint = constraints != null && constraints.hasTimeLimit();
			boolean hasEquipConstraint = constraints != null && constraints.hasEquipmentConstraints();

			log.info("[TASK DEBUG] Evaluating task '{}' (ID: {}) - hasDropConstraint: {}, hasTimeConstraint: {}, hasEquipConstraint: {}",
				task.getName(), task.getTaskId(), hasDropConstraint, hasTimeConstraint, hasEquipConstraint);

			// If task ONLY has dropped_item constraint (no time/equipment constraints),
			// skip time and equipment checks - only verify the drop
			boolean dropOnlyTask = hasDropConstraint && !hasTimeConstraint && !hasEquipConstraint;

			if (!dropOnlyTask)
			{
				// Per-task equipment constraint check - only check THIS task's constraints
				// (removed global flag check which was incorrectly blocking tasks without constraints)
				String equipViolation = validateEquipmentForTask(task);
				if (equipViolation != null)
				{
					log.warn("[TASK FAILED] Task '{}' (ID: {}) - Equipment constraint violated: {}",
						task.getName(), task.getTaskId(), equipViolation);
					sendTaskFailure(task, "Equipment: " + equipViolation);
					continue; // Skip this task, don't credit the kill
				}

				// Time constraint check - validate kill was fast enough
				String timeViolation = validateTimeConstraintForTask(task);
				if (timeViolation != null)
				{
					log.warn("[TASK FAILED] Task '{}' (ID: {}) - Time constraint violated: {}",
						task.getName(), task.getTaskId(), timeViolation);
					sendTaskFailure(task, "Time: " + timeViolation);
					continue; // Skip this task, don't credit the kill
				}
			}
			else
			{
				log.info("[TASK DEBUG] Task '{}' - Drop-only task, skipping time/equipment constraint checks",
					task.getName());
			}

			// If task has a dropped item constraint, check for the drop
			if (hasDropConstraint)
			{
				WorldPoint deathLocation = npc.getWorldLocation();
				List<Integer> requiredItemIds = constraints.getDroppedItemIds();
				int requiredQuantity = constraints.getDroppedItemQuantity();
				String dropName = constraints.getDroppedItem();

				log.info("[TASK DEBUG] Task '{}' - Requires dropped item '{}' (IDs: {}, qty: {}) - checking ground items",
					task.getName(), dropName, requiredItemIds, requiredQuantity);

				// Log all ground items near the death location for debugging
				log.info("[TASK DEBUG] Scanning ground items near NPC death location:");
				logAllGroundItemsNearLocation(deathLocation, 2);

				// IMMEDIATELY check if the item is already on the ground at the death location
				// (ItemSpawned might have fired BEFORE ActorDeath in the same tick)
				int foundQuantity = checkGroundItemsAtLocation(deathLocation, requiredItemIds);

				if (foundQuantity >= requiredQuantity)
				{
					log.info("[TASK SUCCESS] Task '{}' (ID: {}) - Required drop already on ground! Found {} of {} needed. Crediting kill.",
						task.getName(), task.getTaskId(), foundQuantity, requiredQuantity);

					// Send progress to chatbox
					String details = String.format("Killed %s and received %s drop", npc.getName(), dropName);
					sendTaskProgress(task, details);

					// Credit the kill immediately
					sendKillReport(npc, task);
					incrementTaskProgress(task, 1);
					continue;
				}

				// Item not found yet - add to pending and wait for ItemSpawned event
				log.info("[TASK DEBUG] Task '{}' - Drop '%s' not found yet (found: {}/{}), waiting for ItemSpawned event",
					task.getName(), dropName, foundQuantity, requiredQuantity);

				PendingDropKill pending = new PendingDropKill(
					task, npc, deathLocation, client.getTickCount(),
					requiredItemIds, requiredQuantity);
				pending.collectedQuantity = foundQuantity; // Track what we already found
				pendingDropKills.put(task.getTaskId(), pending);

				// Don't credit the kill yet - wait for the drop to appear
				continue;
			}

			// No drop constraint - credit the kill immediately
			log.info("[TASK SUCCESS] Task '{}' (ID: {}) - All constraints passed! Crediting kill.",
				task.getName(), task.getTaskId());

			// Send progress to chatbox
			String details = String.format("Killed %s", npc.getName());
			sendTaskProgress(task, details);

			// Send kill report to server
			sendKillReport(npc, task);

			// For bosses, verify via VarPlayer (instant server-side verification)
			TargetNpc targetNpc = task.getTargetNpc();
			String bossName = targetNpc != null ? targetNpc.getName() : null;

			if (bossName != null && varPlayerService.isBossTracked(bossName))
			{
				int baseKc = varPlayerService.getBossKillCount(bossName);
				log.info("Boss {} KC via VarPlayer: {}", bossName, baseKc);
			}

			// Increment progress on the task
			incrementTaskProgress(task, 1);
		}

		// Reset tracking
		currentTarget = null;
		currentTargetIndex = -1;
		damageDealtToTarget = 0;
		combatStartTick = -1;
	}

	/**
	 * Find all active tasks that match this NPC.
	 */
	private List<NuzlockeTask> findMatchingTasks(NPC npc)
	{
		List<NuzlockeTask> matches = new ArrayList<>();

		for (NuzlockeTask task : activeTasks)
		{
			TargetNpc targetNpc = task.getTargetNpc();
			if (targetNpc != null && targetNpc.matchesNpcId(npc.getId()))
			{
				matches.add(task);
			}
		}

		return matches;
	}

	/**
	 * Increment progress for a specific task.
	 */
	private void incrementTaskProgress(NuzlockeTask task, int amount)
	{
		int newProgress = task.getCurrentProgress() + amount;
		task.setCurrentProgress(newProgress);

		log.info("Task {} progress: {}/{}", task.getName(), newProgress, task.getTargetQuantity());

		// Notify callback about progress update (to update UI and save)
		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		if (newProgress >= task.getTargetQuantity())
		{
			onTaskCompleted(task);
		}
	}

	/**
	 * Called when a specific task is completed.
	 */
	private void onTaskCompleted(NuzlockeTask task)
	{
		if (completionCallback != null)
		{
			log.info("Task completed: {}", task.getName());

			// Send task completion message to chatbox
			sendTaskSuccess(task, "Task complete!");

			completionCallback.onTaskCompleted(task, task.getCurrentProgress());
			activeTasks.remove(task);
		}
	}

	/**
	 * Send a kill report to the server for verification.
	 */
	private void sendKillReport(NPC npc, NuzlockeTask task)
	{
		Player player = client.getLocalPlayer();
		if (player == null || task == null)
		{
			return;
		}

		NpcKillReport.NpcKillReportBuilder builder = NpcKillReport.builder()
			.playerHash(getPlayerHash())
			.taskId(task.getTaskId())
			.npcId(npc.getId())
			.npcName(npc.getName())
			.npcCombatLevel(npc.getCombatLevel())
			.worldX(npc.getWorldLocation().getX())
			.worldY(npc.getWorldLocation().getY())
			.plane(npc.getWorldLocation().getPlane())
			.regionId(getCurrentRegionId())
			.gameTick(getGameTick())
			.timestamp(System.currentTimeMillis())
			.playerCombatLevel(player.getCombatLevel())
			.playerCurrentHp(client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS))
			.killingBlowAnimationId(lastKillingBlowAnimation)
			.damageDealt(damageDealtToTarget)
			.equipmentIds(getEquipmentIds())
			.lootReceived(new ArrayList<>());

		apiClient.reportNpcKill(builder.build())
			.thenAccept(this::handleVerificationResponse);
	}

	/**
	 * Get list of equipped item IDs for verification.
	 */
	private List<Integer> getEquipmentIds()
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
	 * @param slotIndex The equipment slot index (see EquipmentInventorySlot)
	 * @return The item ID at that slot, or -1 if empty or invalid
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
	 * The 11 valid equipment slot indices (some indices are skipped in the game).
	 * HEAD=0, CAPE=1, AMULET=2, WEAPON=3, BODY=4, SHIELD=5, LEGS=7, GLOVES=9, BOOTS=10, RING=12, AMMO=13
	 */
	private static final int[] VALID_EQUIPMENT_SLOTS = {0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13};

	/**
	 * Log detailed equipment status for debugging.
	 */
	private void logEquipmentStatus(String taskName)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(">>> PLAYER EQUIPMENT for task '").append(taskName).append("':\n");

		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			log.info(">>> PLAYER EQUIPMENT: Unable to read equipment (container null)");
			return;
		}

		Item[] items = equipment.getItems();
		boolean hasAnyEquipment = false;

		// Check all 11 valid equipment slots
		for (int slotIndex : VALID_EQUIPMENT_SLOTS)
		{
			String slotName = getSlotName(slotIndex);

			if (slotIndex < items.length)
			{
				Item item = items[slotIndex];
				if (item != null && item.getId() > 0)
				{
					sb.append(">>>   ").append(slotName).append(" [").append(slotIndex).append("]: Item ID ").append(item.getId()).append('\n');
					hasAnyEquipment = true;
				}
				else
				{
					sb.append(">>>   ").append(slotName).append(" [").append(slotIndex).append("]: EMPTY\n");
				}
			}
			else
			{
				sb.append(">>>   ").append(slotName).append(" [").append(slotIndex).append("]: EMPTY\n");
			}
		}

		if (!hasAnyEquipment)
		{
			sb.append(">>>   (No equipment in any slot)\n");
		}

		log.info(sb.toString());
	}

	/**
	 * Validate equipment against a task's constraints.
	 * @return null if valid, or a string describing the violation
	 */
	private String validateEquipmentForTask(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();

		// Log equipment status for debugging
		logEquipmentStatus(task.getName());

		if (constraints == null)
		{
			log.info(">>> EQUIPMENT CHECK for '{}': No constraints object on task", task.getName());
			return null; // No equipment constraints
		}

		if (!constraints.hasEquipmentConstraints())
		{
			log.info(">>> EQUIPMENT CHECK for '{}': hasEquipmentConstraints() = false", task.getName());
			log.info(">>>   no_equipment={}, equip_nothing={}, must_be_empty={}, equippable_slots={}",
				constraints.getNoEquipment(), constraints.getEquipNothing(),
				constraints.getMustBeEmptySlots(), constraints.getEquippableSlots());
			return null; // No equipment constraints
		}

		log.info(">>> EQUIPMENT CHECK for '{}': Validating constraints...", task.getName());

		List<Integer> equippedIds = getEquipmentIds();
		log.info(">>> Total equipped item IDs: {}", equippedIds);

		// Check no_equipment constraint (must have nothing equipped)
		if (constraints.isNoEquipment())
		{
			if (!equippedIds.isEmpty())
			{
				return "Must have no equipment - currently have " + equippedIds.size() + " items equipped";
			}
		}

		// Check equip_nothing constraint (must have ZERO equipment - nothing equipped at all)
		if (constraints.isEquipNothing())
		{
			if (!equippedIds.isEmpty())
			{
				return "Equip nothing required - currently have " + equippedIds.size() + " items equipped";
			}
		}

		// Check required_equipment_ids (these items MUST be equipped)
		List<Integer> requiredIds = constraints.getRequiredEquipmentIds();
		if (requiredIds != null && !requiredIds.isEmpty())
		{
			for (Integer requiredId : requiredIds)
			{
				if (!equippedIds.contains(requiredId))
				{
					return "Missing required equipment: item ID " + requiredId;
				}
			}
		}

		// Check allowed_equipment_ids (ONLY these items can be equipped)
		List<Integer> allowedIds = constraints.getAllowedEquipmentIds();
		if (allowedIds != null && !allowedIds.isEmpty())
		{
			for (Integer equippedId : equippedIds)
			{
				if (!allowedIds.contains(equippedId))
				{
					return "Forbidden equipment detected: item ID " + equippedId + " is not in allowed list";
				}
			}
		}

		// Check forbidden_equipment_ids (these items must NOT be equipped)
		List<Integer> forbiddenIds = constraints.getForbiddenEquipmentIds();
		if (forbiddenIds != null && !forbiddenIds.isEmpty())
		{
			for (Integer forbiddenId : forbiddenIds)
			{
				if (equippedIds.contains(forbiddenId))
				{
					return "Forbidden equipment detected: item ID " + forbiddenId;
				}
			}
		}

		// Check must_be_empty slots (specific slots that MUST be empty)
		List<Integer> mustBeEmptySlots = constraints.getMustBeEmptySlots();
		log.info(">>> MUST_BE_EMPTY CHECK for '{}': slots to check = {}", task.getName(), mustBeEmptySlots);
		if (mustBeEmptySlots != null && !mustBeEmptySlots.isEmpty())
		{
			for (Integer slotIndex : mustBeEmptySlots)
			{
				int itemId = getItemAtSlot(slotIndex);
				log.info(">>>   Checking slot {} ({}): itemId = {}", slotIndex, getSlotName(slotIndex), itemId);
				if (itemId > 0)
				{
					return getSlotName(slotIndex) + " slot must be empty (has item ID " + itemId + ")";
				}
			}
		}

		// Check equippable_slots (ONLY these slots can have equipment, all others must be empty)
		List<Integer> equippableSlots = constraints.getEquippableSlots();
		log.info(">>> EQUIPPABLE_SLOTS CHECK for '{}': allowed slots = {}", task.getName(), equippableSlots);
		if (equippableSlots != null && !equippableSlots.isEmpty())
		{
			// Check all 11 valid equipment slots
			for (int slotIndex : VALID_EQUIPMENT_SLOTS)
			{
				int itemId = getItemAtSlot(slotIndex);
				boolean slotAllowed = equippableSlots.contains(slotIndex);

				if (itemId > 0 && !slotAllowed)
				{
					// Item in a slot that's not allowed
					return getSlotName(slotIndex) + " slot must be empty - only allowed slots: " +
						equippableSlots.stream()
							.map(this::getSlotName)
							.reduce((a, b) -> a + ", " + b)
							.orElse("none");
				}
			}
		}

		return null; // All constraints passed
	}

	/**
	 * Validate that the kill was completed within the time limit.
	 * @param task The task with potential time constraints
	 * @return null if valid (or no constraint), or a string describing the violation
	 */
	private String validateTimeConstraintForTask(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();

		if (constraints == null || !constraints.hasTimeLimit())
		{
			log.info(">>> TIME CHECK for '{}': No time constraint", task.getName());
			return null; // No time constraint
		}

		int allowedTicks = constraints.getTimeInTicks();
		int currentTick = client.getTickCount();

		// Check if we have a valid combat start tick
		if (combatStartTick < 0)
		{
			log.info(">>> TIME CHECK for '{}': No combat start tick recorded", task.getName());
			return "Time constraint failed - no combat start recorded";
		}

		int elapsedTicks = currentTick - combatStartTick;
		double elapsedSeconds = elapsedTicks * 0.6;

		log.info(">>> TIME CHECK for '{}': {} ticks elapsed (max allowed: {}), {:.1f} seconds",
			task.getName(), elapsedTicks, allowedTicks, elapsedSeconds);

		if (elapsedTicks > allowedTicks)
		{
			return String.format("Kill took %d ticks (%.1f sec), max allowed is %d ticks (%.1f sec)",
				elapsedTicks, elapsedSeconds, allowedTicks, allowedTicks * 0.6);
		}

		log.info(">>> TIME CONSTRAINT PASSED for task '{}' - killed in {} tick(s)", task.getName(), elapsedTicks);
		return null; // Constraint satisfied
	}
}
