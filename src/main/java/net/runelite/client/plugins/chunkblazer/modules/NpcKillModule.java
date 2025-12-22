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
                currentTarget = npc;
                currentTargetIndex = npc.getIndex(); // Track by index for reliable matching
                damageDealtToTarget = 0;
                log.info(">>> Tracking target: {} (ID: {}, Index: {})", npc.getName(), npc.getId(), npc.getIndex());
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
}
