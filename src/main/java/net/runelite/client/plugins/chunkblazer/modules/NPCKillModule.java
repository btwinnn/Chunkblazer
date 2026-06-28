package net.runelite.client.plugins.chunkblazer.modules;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import net.runelite.api.Skill;
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
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.chunkblazer.ChunkBlazerConfig;
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
	// Handles NPC_KILL, COMBAT, and SLAYER task types
	private static final String COMPLETION_TYPE = "NPC_KILL";
	private static final String COMBAT_TYPE = "COMBAT";
	private static final String SLAYER_TYPE = "SLAYER";

	// Slayer task VarPlayer IDs (from RuneLite's SlayerPlugin)
	private static final int SLAYER_TASK_COUNT_VARP = 394;  // VarPlayerID.SLAYER_COUNT

	// On-task slayer kills award Slayer XP; off-task kills award none. So a Slayer
	// XP gain in the same tick window as a kill means the dead NPC was the player's
	// ASSIGNED monster — a name-agnostic "on the right task" signal. The kill's XP
	// event fires during the tick; deaths are processed at end-of-tick (onGameTick),
	// so the gain is already recorded by then. A small window covers timing skew.
	private static final int SLAYER_XP_WINDOW_TICKS = 2;
	private int previousSlayerXp = -1;
	private int lastSlayerXpGainTick = -1;

	@Inject
	private VarPlayerVerificationService varPlayerService;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ChunkBlazerConfig config;

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

	// Pending drop-based kills: tasks waiting for a specific item to drop
	// Key: task ID, Value: pending kill info
	// Using ConcurrentHashMap for thread safety (accessed from multiple event handlers)
	private final Map<String, PendingDropKill> pendingDropKills = new ConcurrentHashMap<>();
	// How many ticks after NPC death we'll still credit a drop. Needs to cover
	// the full death animation + server loot delay (observed at ~7 ticks for
	// goblins, can be higher for larger NPCs). Set generously — false-positives
	// are blocked by ownership/distance checks anyway.
	private static final int PENDING_DROP_TIMEOUT_TICKS = 20; // ~12s
	// Per-event freshness check inside onItemSpawned. Must be >= the longest
	// realistic death-animation-to-loot delay in OSRS. 5 was too tight (goblins
	// alone hit 7); 15 covers all known cases including larger boss death anims.
	private static final int DROP_SPAWN_FRESHNESS_TICKS = 15; // ~9s

	// NPC deaths queued for end-of-tick processing.
	// Why: ActorDeath can fire BEFORE the killing blow's HitsplatApplied on same-tick
	// kills (one-shots, low-HP NPCs like Highwayman/Man). At ActorDeath time damage is
	// still 0 and combatStartTick is still -1, so the kill gets rejected and the time
	// constraint can't be evaluated. Draining this list in onGameTick ensures all
	// same-tick hitsplats have been processed before we decide.
	private final List<NPC> pendingDeaths = new ArrayList<>();

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
			// Handle NPC_KILL, COMBAT, and SLAYER completion types
			if (COMPLETION_TYPE.equalsIgnoreCase(type) ||
				COMBAT_TYPE.equalsIgnoreCase(type) ||
				SLAYER_TYPE.equalsIgnoreCase(type))
			{
				return true;
			}
		}

		// Also check category field for "combat" or "slayer" tasks
		String category = task.getCategory();
		if (category != null &&
			(COMBAT_TYPE.equalsIgnoreCase(category) || SLAYER_TYPE.equalsIgnoreCase(category)))
		{
			return true;
		}

		return false;
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
		currentTarget = null;
		currentTargetIndex = -1;
		damageDealtToTarget = 0;
		combatStartTick = -1;
		previousSlayerXp = -1;
		lastSlayerXpGainTick = -1;
		pendingDropKills.clear();
		pendingDeaths.clear();
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		super.onTaskAssigned(task);
		currentTarget = null;
		currentTargetIndex = -1;
		damageDealtToTarget = 0;
		combatStartTick = -1;

		// Baseline Slayer XP so the first on-task kill after assignment registers a
		// gain (rather than being swallowed as the baseline). Guarded for tests/off-thread.
		if (client.getLocalPlayer() != null)
		{
			previousSlayerXp = client.getSkillExperience(Skill.SLAYER);
		}

		// For boss tasks, get baseline KC from VarPlayer (instant server-side)
		TargetNpc targetNpc = task.getTargetNpc();
		if (targetNpc != null)
		{
			String bossName = targetNpc.getName();
			if (bossName != null && varPlayerService.isBossTracked(bossName))
			{
				currentBossName = bossName;
				baselineKc = varPlayerService.getBossKillCount(bossName);
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
		previousSlayerXp = -1;
		lastSlayerXpGainTick = -1;
		pendingDropKills.clear();
		pendingDeaths.clear();
	}

	@Override
	public void checkProgress()
	{
		// For NPC kills, progress is tracked via events.
		// Could add a sync check with hiscores here for verification.
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int currentTick = client.getTickCount();

		// Drain deaths queued from this tick's ActorDeath events. By now any same-tick
		// HitsplatApplied for the killing blow has been processed, so damageDealtToTarget
		// and combatStartTick are correct.
		if (!pendingDeaths.isEmpty())
		{
			List<NPC> toProcess = new ArrayList<>(pendingDeaths);
			pendingDeaths.clear();
			for (NPC deadNpc : toProcess)
			{
				processNpcDeath(deadNpc);
			}
		}

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

					sendTaskFailure(pending.task, reason);

					it.remove();
				}
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Track Slayer XP gains so we can tell on-task kills (XP awarded) from
		// off-task kills (no XP) — see wasOnTaskKill().
		if (event.getSkill() != Skill.SLAYER)
		{
			return;
		}
		int xp = event.getXp();
		if (previousSlayerXp < 0)
		{
			previousSlayerXp = xp;
			return;
		}
		if (xp > previousSlayerXp)
		{
			lastSlayerXpGainTick = client.getTickCount();
		}
		previousSlayerXp = xp;
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		Actor source = event.getSource();
		Actor target = event.getTarget();

		// Only track player interactions with NPCs
		if (source != client.getLocalPlayer() || !(target instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) target;

		// IMPORTANT: Only track combat-capable NPCs or task target NPCs
		// This prevents resetting tracking when talking to shopkeepers, random events, etc.
		boolean isCombatNpc = npc.getCombatLevel() > 0;
		boolean isTaskTarget = isNpcTaskTarget(npc.getId());

		if (!isCombatNpc && !isTaskTarget)
		{
			return; // Don't reset tracking for non-combat NPCs
		}

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
			}
			else
			{
				// Same target - just update reference
				currentTarget = npc;
			}
			// Equipment constraints are checked per-task at kill time in onActorDeath
		}
	}

	/**
	 * Check if an NPC ID is a target for any active task.
	 */
	private boolean isNpcTaskTarget(int npcId)
	{
		for (NuzlockeTask task : activeTasks)
		{
			TargetNpc targetNpc = task.getTargetNpc();
			if (targetNpc != null && targetNpc.matchesNpcId(npcId))
			{
				return true;
			}
		}
		return false;
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
				}

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
				continue;
			}

			// TIMING CHECK: drops can lag the death event by the full death
			// animation + server loot delay. Goblins observed at ~7 ticks;
			// larger NPCs are higher. Use the constant rather than a literal.
			int ticksSinceDeath = currentTick - pending.deathTick;
			if (ticksSinceDeath > DROP_SPAWN_FRESHNESS_TICKS)
			{
				continue;
			}

			// LOCATION CHECK: Item must spawn at or very near the death location (within 1 tile)
			// NPC drops spawn at the NPC's location, so this should be exact or 1 tile away
			int distance = itemLocation.distanceTo(pending.deathLocation);
			if (distance > 1)
			{
				continue;
			}

			// Check if this is one of the required items
			if (!pending.requiredItemIds.contains(itemId))
			{
				continue;
			}

			// Found a matching drop that belongs to us!
			pending.collectedQuantity += quantity;

			// Check if we've collected enough
			if (pending.collectedQuantity >= pending.requiredQuantity)
			{
				String dropName = pending.task.getConstraints().getDroppedItem();

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

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";        // [ChunkBlazer] branding
	private static final String COLOR_RED = "ff3333";         // Task Failed
	private static final String COLOR_DARK_BLUE = "1a5276";   // Task Success (dark blue, readable)
	private static final String COLOR_DARK_GREEN = "228b22";  // Task Progress
	private static final String COLOR_BLACK = "000000";       // Task name text

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
			"<col=" + COLOR_DARK_BLUE + ">Task Success:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col> " +
			"(" + task.getCurrentProgress() + "/" + task.getTargetQuantity() + ")";

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
	 * Send a task progress message to the player's chatbox.
	 * Used when progress is made but task is not yet complete.
	 */
	private void sendTaskProgress(NuzlockeTask task, String details)
	{
		// Check config - if showChatProgress is disabled, don't send
		if (!config.showChatProgress())
		{
			return;
		}

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
	}

	/**
	 * Send a task failure message to the player's chatbox.
	 */
	private void sendTaskFailure(NuzlockeTask task, String reason)
	{
		// Check config - if showChatFailed is disabled, don't send
		if (!config.showChatFailed())
		{
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
			return 0;
		}

		Tile tile = tiles[plane][sceneX][sceneY];
		if (tile == null)
		{
			return 0;
		}

		// Check ground items on this tile
		List<TileItem> groundItems = tile.getGroundItems();
		if (groundItems == null || groundItems.isEmpty())
		{
			return 0;
		}

		for (TileItem item : groundItems)
		{
			int itemId = item.getId();
			int ownership = item.getOwnership();
			int quantity = item.getQuantity();

			// Only count items that belong to us
			if (ownership != TileItem.OWNERSHIP_SELF)
			{
				continue;
			}

			// Check if this is one of the required items
			if (requiredItemIds.contains(itemId))
			{
				totalFound += quantity;
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

		// Defer to onGameTick. ActorDeath can fire BEFORE the killing blow's
		// HitsplatApplied on same-tick kills (one-shots, low-HP NPCs), so damage
		// and combatStartTick are still 0/-1 here. Processing on the next GameTick
		// drain ensures hitsplats from this tick are counted first.
		pendingDeaths.add(npc);
	}

	private void processNpcDeath(NPC npc)
	{
		if (activeTasks.isEmpty())
		{
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
			return;
		}

		// Diagnostic for collecting real runtime NPC ids in-game: the wiki id often
		// differs from / is incomplete vs what the client reports (level/spawn/hue
		// variants). If a kill doesn't credit a task you expected, grep this line
		// for the actual id and add it to the task's npc_ids.
		log.info("[NPCKILL-DEBUG] confirmed kill: id={} name='{}'", npc.getId(), npc.getName());

		// Check all active tasks for a match
		List<NuzlockeTask> matchingTasks = mostSpecificMatches(findMatchingTasks(npc));

		if (matchingTasks.isEmpty())
		{
			// Reset tracking anyway
			currentTarget = null;
			currentTargetIndex = -1;
			damageDealtToTarget = 0;
			return;
		}

		// Update progress for all matching tasks
		for (NuzlockeTask task : matchingTasks)
		{
			// SLAYER task check — must be killing the ASSIGNED monster, not just
			// holding any slayer assignment. On-task kills award Slayer XP; off-task
			// kills don't, so a Slayer XP gain in this kill's tick window proves the
			// dead NPC was the assigned creature. (Old check was SLAYER_COUNT>0, which
			// credited the right monster even while assigned to a different one.)
			if (SLAYER_TYPE.equalsIgnoreCase(task.getCompletionType()))
			{
				if (!wasOnTaskKill())
				{
					sendTaskFailure(task, "Not on a slayer task for this monster");
					continue; // Skip this task, don't credit the kill
				}
			}

			TaskConstraints constraints = task.getConstraints();
			boolean hasDropConstraint = constraints != null && constraints.hasDroppedItemConstraint();
			boolean hasTimeConstraint = constraints != null && constraints.hasTimeLimit();
			boolean hasEquipConstraint = constraints != null && constraints.hasEquipmentConstraints();
			boolean hasVarbitConstraint = constraints != null && constraints.hasVarbitConstraints();

			// If task ONLY has dropped_item constraint (no time/equipment/varbit constraints),
			// skip time and equipment checks - only verify the drop
			boolean dropOnlyTask = hasDropConstraint && !hasTimeConstraint && !hasEquipConstraint && !hasVarbitConstraint;

			if (!dropOnlyTask)
			{
				// Varbit constraint check (e.g., no cannon during timed tasks)
				String varbitViolation = validateVarbitConstraintForTask(task);
				if (varbitViolation != null)
				{
					sendTaskFailure(task, varbitViolation);
					continue; // Skip this task, don't credit the kill
				}

				// Per-task equipment constraint check - only check THIS task's constraints
				// (removed global flag check which was incorrectly blocking tasks without constraints)
				String equipViolation = validateEquipmentForTask(task);
				if (equipViolation != null)
				{
					sendTaskFailure(task, "Equipment: " + equipViolation);
					continue; // Skip this task, don't credit the kill
				}

				// Time constraint check - validate kill was fast enough
				String timeViolation = validateTimeConstraintForTask(task);
				if (timeViolation != null)
				{
					sendTaskFailure(task, "Time: " + timeViolation);
					continue; // Skip this task, don't credit the kill
				}
			}

			// If task has a dropped item constraint, check for the drop
			if (hasDropConstraint)
			{
				WorldPoint deathLocation = npc.getWorldLocation();
				List<Integer> requiredItemIds = constraints.getDroppedItemIds();
				int requiredQuantity = constraints.getDroppedItemQuantity();
				String dropName = constraints.getDroppedItem();

				// IMMEDIATELY check if the item is already on the ground at the death location
				// (ItemSpawned might have fired BEFORE ActorDeath in the same tick)
				int foundQuantity = checkGroundItemsAtLocation(deathLocation, requiredItemIds);

				if (foundQuantity >= requiredQuantity)
				{
					// Send progress to chatbox
					String details = String.format("Killed %s and received %s drop", npc.getName(), dropName) + killTimeSuffix();
					sendTaskProgress(task, details);

					// Credit the kill immediately
					sendKillReport(npc, task);
					incrementTaskProgress(task, 1);
					continue;
				}

				// Item not found yet - add to pending and wait for ItemSpawned event
				PendingDropKill pending = new PendingDropKill(
					task, npc, deathLocation, client.getTickCount(),
					requiredItemIds, requiredQuantity);
				pending.collectedQuantity = foundQuantity; // Track what we already found
				pendingDropKills.put(task.getTaskId(), pending);

				// Don't credit the kill yet - wait for the drop to appear
				continue;
			}

			// No drop constraint - credit the kill immediately
			// Send progress to chatbox
			String details = String.format("Killed %s", npc.getName()) + killTimeSuffix();
			sendTaskProgress(task, details);

			// Send kill report to server
			sendKillReport(npc, task);

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
	 * When a kill matches several tasks, credit only the MOST SPECIFIC — the
	 * task(s) with the smallest target-NPC id set. This stops a kill of a
	 * sub-monster (e.g. an Ogress, ids {7989-7992}) from also crediting a broader
	 * superset task (e.g. Ogre, a 28-id list that contains those). Tasks with
	 * equal-size id sets (genuine duplicates — e.g. two tasks both pinned to the
	 * single id 14704) can't be told apart, so they're all kept.
	 */
	private List<NuzlockeTask> mostSpecificMatches(List<NuzlockeTask> matches)
	{
		if (matches.size() <= 1)
		{
			return matches;
		}

		int minSize = Integer.MAX_VALUE;
		for (NuzlockeTask task : matches)
		{
			int size = npcIdSetSize(task);
			if (size > 0 && size < minSize)
			{
				minSize = size;
			}
		}

		List<NuzlockeTask> specific = new ArrayList<>();
		for (NuzlockeTask task : matches)
		{
			if (npcIdSetSize(task) == minSize)
			{
				specific.add(task);
			}
		}
		return specific;
	}

	private int npcIdSetSize(NuzlockeTask task)
	{
		TargetNpc targetNpc = task.getTargetNpc();
		return (targetNpc != null && targetNpc.getNpcIds() != null) ? targetNpc.getNpcIds().size() : 0;
	}

	/**
	 * Increment progress for a specific task.
	 */
	private void incrementTaskProgress(NuzlockeTask task, int amount)
	{
		int newProgress = task.getCurrentProgress() + amount;
		task.setCurrentProgress(newProgress);

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
	 * True if the kill currently being processed was an on-task slayer kill — i.e.
	 * a Slayer XP gain landed within SLAYER_XP_WINDOW_TICKS of now. Off-task kills
	 * award no Slayer XP, so this distinguishes "killed my assigned monster" from
	 * "killed a monster that merely matches the task while assigned to something
	 * else". SLAYER_COUNT (varp 394) is intentionally no longer used — it only
	 * told us the player had *an* assignment, not *which* one.
	 */
	private boolean wasOnTaskKill()
	{
		int tick = client.getTickCount();
		return lastSlayerXpGainTick > 0 && (tick - lastSlayerXpGainTick) <= SLAYER_XP_WINDOW_TICKS;
	}

	/**
	 * Validate equipment against a task's constraints.
	 * @return null if valid, or a string describing the violation
	 */
	private String validateEquipmentForTask(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();

		if (constraints == null)
		{
			return null; // No equipment constraints
		}

		if (!constraints.hasEquipmentConstraints())
		{
			return null; // No equipment constraints
		}

		List<Integer> equippedIds = getEquipmentIds();

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
		if (mustBeEmptySlots != null && !mustBeEmptySlots.isEmpty())
		{
			for (Integer slotIndex : mustBeEmptySlots)
			{
				int itemId = getItemAtSlot(slotIndex);
				if (itemId > 0)
				{
					return getSlotName(slotIndex) + " slot must be empty (has item ID " + itemId + ")";
				}
			}
		}

		// Check equippable_slots (ONLY these slots can have equipment, all others must be empty)
		List<Integer> equippableSlots = constraints.getEquippableSlots();
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
	 * Validate that no prohibited varbits are active.
	 * For example, varbit 57 controls cannon deployment - value 0 means no cannon.
	 * @param task The task with potential varbit constraints
	 * @return null if valid (or no constraint), or a string describing the violation
	 */
	private String validateVarbitConstraintForTask(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();

		if (constraints == null || !constraints.hasVarbitConstraints())
		{
			return null; // No varbit constraints
		}

		for (TaskConstraints.VarbitConstraint vc : constraints.getProhibitedActiveVarbits())
		{
			int currentValue = client.getVarbitValue(vc.getVarbitId());
			if (currentValue != vc.getMustBeValue())
			{
				String failMsg = vc.getFailMessage();
				if (failMsg != null && !failMsg.isEmpty())
				{
					return failMsg;
				}
				return "Varbit " + vc.getVarbitId() + " must be " + vc.getMustBeValue() + " but is " + currentValue;
			}
		}

		return null; // All varbit constraints passed
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
			return null; // No time constraint
		}

		int allowedTicks = constraints.getTimeInTicks();
		int currentTick = client.getTickCount();

		// Check if we have a valid combat start tick
		if (combatStartTick < 0)
		{
			return "Time constraint failed - no combat start recorded";
		}

		int elapsedTicks = currentTick - combatStartTick;
		double elapsedSeconds = elapsedTicks * 0.6;

		if (elapsedTicks > allowedTicks)
		{
			return String.format("Kill took %d ticks (%.1f sec), max allowed is %d ticks (%.1f sec)",
				elapsedTicks, elapsedSeconds, allowedTicks, allowedTicks * 0.6);
		}

		return null; // Constraint satisfied
	}

	/**
	 * " in X.Xs" suffix describing how long the current target took to kill, or ""
	 * if no combat start was recorded (e.g. an instant/one-shot kill whose first
	 * hitsplat and death landed before timing began). Appended to the success
	 * message so players see the kill time on success too — mirroring the time the
	 * failure message already reports. 1 game tick = 0.6 seconds.
	 */
	private String killTimeSuffix()
	{
		if (combatStartTick < 0)
		{
			return "";
		}
		int elapsedTicks = client.getTickCount() - combatStartTick;
		if (elapsedTicks < 0)
		{
			return "";
		}
		return String.format(" in %.1fs", elapsedTicks * 0.6);
	}
}
