package net.runelite.client.plugins.chunkblazer.modules;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
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
public class NpcKillModule extends AbstractTaskModule
{
    // Handles both NPC_KILL and COMBAT task types
    private static final String COMPLETION_TYPE = "NPC_KILL";
    private static final String COMBAT_TYPE = "COMBAT";

    @Inject
    private VarPlayerVerificationService varPlayerService;

    // Track the NPC we're currently fighting
    private NPC currentTarget;
    private int currentTargetIndex = -1; // Track by index to avoid reference issues
    private int damageDealtToTarget;
    private int lastKillingBlowAnimation;

    // Equipment constraint tracking
    private boolean equipmentConstraintViolated = false;
    private String equipmentViolationReason = null;

    // For boss KC verification
    private int baselineKc = -1;
    private String currentBossName;

    // Debug heartbeat
    private int tickCounter = 0;
    private static final int DEBUG_LOG_INTERVAL = 100; // Log every 100 ticks (~60 seconds)

    @Inject
    public NpcKillModule()
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
        log.info("NpcKillModule stopped");
    }

    @Override
    public void onTaskAssigned(NuzlockeTask task)
    {
        super.onTaskAssigned(task);
        currentTarget = null;
        currentTargetIndex = -1;
        damageDealtToTarget = 0;

        // For boss tasks, get baseline KC from VarPlayer (instant server-side)
        TargetNpc targetNpc = task.getTargetNpc();
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
                log.info("Non-boss NPC task: {} (client-side tracking only)",
                    bossName != null ? bossName : "unknown");
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
        baselineKc = -1;
        currentBossName = null;
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

        // Continuously check equipment constraints while in combat
        if (currentTarget != null && !equipmentConstraintViolated)
        {
            checkEquipmentConstraints();
        }

        // Log heartbeat periodically to confirm event bus is working
        if (tickCounter % DEBUG_LOG_INTERVAL == 0)
        {
            log.info(">>> NpcKillModule HEARTBEAT - tick {} - activeTasks: {}, currentTarget: {}",
                tickCounter, activeTasks.size(), currentTarget != null ? currentTarget.getName() : "none");

            // List all active combat tasks
            for (NuzlockeTask task : activeTasks)
            {
                TargetNpc targetNpc = task.getTargetNpc();
                String npcInfo = targetNpc != null ? "NPC IDs: " + targetNpc.getNpcIds() : "no target NPC";
                log.info(">>>   Active combat task: {} ({}) - {}/{} - {}",
                    task.getName(), task.getTaskId(),
                    task.getCurrentProgress(), task.getTargetQuantity(), npcInfo);
            }
        }
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
                // If same target (same index), preserve the equipment violation flag!
                boolean isSameTarget = (currentTargetIndex == npc.getIndex());

                if (!isSameTarget)
                {
                    // New target - reset everything
                    currentTarget = npc;
                    currentTargetIndex = npc.getIndex();
                    damageDealtToTarget = 0;
                    equipmentConstraintViolated = false;
                    equipmentViolationReason = null;
                    log.info(">>> NEW target: {} (ID: {}, Index: {}) - reset tracking", npc.getName(), npc.getId(), npc.getIndex());
                }
                else
                {
                    // Same target - keep equipment violation flag, just update reference
                    currentTarget = npc;
                    log.info(">>> SAME target: {} (Index: {}) - preserving equipmentConstraintViolated={}",
                        npc.getName(), npc.getIndex(), equipmentConstraintViolated);
                }

                // Always check equipment constraints on each interaction
                checkEquipmentConstraints();
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
                log.info(">>> DAMAGE DEALT to {}: {} (total: {})", currentTarget.getName(), damage, damageDealtToTarget);

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
            // Check if equipment constraint was violated during combat
            if (equipmentConstraintViolated)
            {
                log.info("Kill NOT credited for task '{}' - equipment constraint violated: {}",
                    task.getName(), equipmentViolationReason);
                continue; // Skip this task, don't credit the kill
            }

            // Final equipment check at time of kill
            String finalViolation = validateEquipmentForTask(task);
            if (finalViolation != null)
            {
                log.info("Kill NOT credited for task '{}' - equipment invalid at kill time: {}",
                    task.getName(), finalViolation);
                continue; // Skip this task, don't credit the kill
            }

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
            completionCallback.onTaskCompleted(task, task.getCurrentProgress());
            activeTasks.remove(task);
        }
    }

    /**
     * Check if the given NPC matches the task's target NPC.
     * Legacy method for backward compatibility.
     */
    private boolean isTargetNpc(NPC npc)
    {
        if (activeTask == null)
        {
            return false;
        }

        TargetNpc targetNpc = activeTask.getTargetNpc();
        if (targetNpc == null)
        {
            return false;
        }

        // Check by NPC ID first (most reliable)
        if (targetNpc.matchesNpcId(npc.getId()))
        {
            return true;
        }

        // Fall back to name matching using NPC IDs list
        // If no IDs defined, we can't match
        return false;
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
            .lootReceived(new ArrayList<>()); // Could track loot with LootReceived event

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
                    sb.append(">>>   ").append(slotName).append(" [").append(slotIndex).append("]: Item ID ").append(item.getId()).append("\n");
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
     * Check equipment constraints for all active tasks.
     * If any constraint is violated, mark it so the kill won't count.
     */
    private void checkEquipmentConstraints()
    {
        if (activeTasks.isEmpty())
        {
            return;
        }

        // Check each active task for equipment constraints
        for (NuzlockeTask task : activeTasks)
        {
            String violation = validateEquipmentForTask(task);
            if (violation != null)
            {
                equipmentConstraintViolated = true;
                equipmentViolationReason = violation;
                log.info(">>> EQUIPMENT CONSTRAINT VIOLATED for task '{}': {}",
                    task.getName(), violation);
                return; // One violation is enough
            }
        }
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
}
