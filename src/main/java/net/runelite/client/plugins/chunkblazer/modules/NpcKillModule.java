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
    private int damageDealtToTarget;
    private int lastKillingBlowAnimation;

    // For boss KC verification
    private int baselineKc = -1;
    private String currentBossName;

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
        log.info("NpcKillModule started");
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        currentTarget = null;
        damageDealtToTarget = 0;
        log.info("NpcKillModule stopped");
    }

    @Override
    public void onTaskAssigned(NuzlockeTask task)
    {
        super.onTaskAssigned(task);
        currentTarget = null;
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
    public void onInteractingChanged(InteractingChanged event)
    {
        if (activeTask == null)
        {
            return;
        }

        Actor source = event.getSource();
        Actor target = event.getTarget();

        // Check if player started attacking an NPC
        if (source == client.getLocalPlayer() && target instanceof NPC)
        {
            NPC npc = (NPC) target;
            if (isTargetNpc(npc))
            {
                currentTarget = npc;
                damageDealtToTarget = 0;
                log.debug("Started attacking target NPC: {} (ID: {})", npc.getName(), npc.getId());
            }
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        if (activeTask == null || currentTarget == null)
        {
            return;
        }

        // Track damage dealt to our target
        if (event.getActor() == currentTarget)
        {
            // Only count damage from the player
            if (event.getHitsplat().isMine())
            {
                damageDealtToTarget += event.getHitsplat().getAmount();

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
        if (activeTasks.isEmpty())
        {
            return;
        }

        Actor actor = event.getActor();
        if (!(actor instanceof NPC))
        {
            return;
        }

        NPC npc = (NPC) actor;

        // Verify player was involved (dealt damage or was in combat)
        if (currentTarget != npc && damageDealtToTarget <= 0)
        {
            log.debug("NPC died but player wasn't involved: {}", npc.getName());
            return;
        }

        // Check all active tasks for a match
        List<NuzlockeTask> matchingTasks = findMatchingTasks(npc);

        if (matchingTasks.isEmpty())
        {
            log.debug("NPC {} doesn't match any active tasks", npc.getName());
            // Reset tracking anyway
            currentTarget = null;
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
