package net.runelite.client.plugins.chunkblazer.modules;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.TaskConstraints;
import net.runelite.client.plugins.chunkblazer.api.SkillChangeReport;
import net.runelite.client.plugins.chunkblazer.verification.HiscoreVerificationService;

/**
 * Module for handling SKILL_LEVEL and SKILL_XP completion type tasks.
 * Tracks skill level ups and XP gains.
 */
@Slf4j
@Singleton
public class SkillModule extends AbstractTaskModule
{
    private static final String COMPLETION_TYPE_LEVEL = "SKILL_LEVEL";
    private static final String COMPLETION_TYPE_XP = "SKILL_XP";

    @Inject
    private HiscoreVerificationService hiscoreService;

    // Track previous skill states
    private Map<Skill, Integer> previousLevels = new HashMap<>();
    private Map<Skill, Integer> previousXp = new HashMap<>();

    // The skill this task is tracking
    private Skill targetSkill;
    private int targetLevel;
    private int targetXp;
    private boolean trackingLevel; // true for level, false for XP

    @Inject
    public SkillModule()
    {
    }

    @Override
    public String getCompletionType()
    {
        // This module handles both level and XP tasks
        return COMPLETION_TYPE_LEVEL;
    }

    @Override
    public boolean canHandle(NuzlockeTask task)
    {
        String type = task.getCompletionType();
        return COMPLETION_TYPE_LEVEL.equalsIgnoreCase(type) ||
               COMPLETION_TYPE_XP.equalsIgnoreCase(type);
    }

    @Override
    public void startUp()
    {
        eventBus.register(this);
        initializeSkillTracking();
        log.info("SkillModule started");
    }

    @Override
    public void shutDown()
    {
        eventBus.unregister(this);
        previousLevels.clear();
        previousXp.clear();
        log.info("SkillModule stopped");
    }

    @Override
    public void onTaskAssigned(NuzlockeTask task)
    {
        super.onTaskAssigned(task);

        // Parse the task constraints to find target skill and level/XP
        parseTaskRequirements(task);

        // Initialize tracking for current skill levels
        initializeSkillTracking();

        // Check if already completed
        checkCurrentSkillProgress();
    }

    @Override
    public void onTaskCleared()
    {
        super.onTaskCleared();
        targetSkill = null;
        targetLevel = 0;
        targetXp = 0;
    }

    @Override
    public void checkProgress()
    {
        checkCurrentSkillProgress();
    }

    private void initializeSkillTracking()
    {
        if (client.getLocalPlayer() == null)
        {
            return;
        }

        for (Skill skill : Skill.values())
        {
            previousLevels.put(skill, client.getRealSkillLevel(skill));
            previousXp.put(skill, client.getSkillExperience(skill));
        }
    }

    private void parseTaskRequirements(NuzlockeTask task)
    {
        TaskConstraints constraints = task.getConstraints();
        if (constraints == null)
        {
            log.warn("Task {} has no constraints", task.getTaskId());
            return;
        }

        // Get the target skill from constraints
        String skillName = constraints.getRequiredSkill();
        if (skillName != null)
        {
            targetSkill = parseSkillName(skillName);
        }

        // Determine if tracking level or XP
        trackingLevel = COMPLETION_TYPE_LEVEL.equalsIgnoreCase(task.getCompletionType());

        if (trackingLevel)
        {
            targetLevel = constraints.getRequiredLevel();
            log.info("Tracking skill level: {} to level {}", targetSkill, targetLevel);
        }
        else
        {
            targetXp = constraints.getRequiredXp();
            log.info("Tracking skill XP: {} to {} XP", targetSkill, targetXp);
        }
    }

    private Skill parseSkillName(String name)
    {
        if (name == null)
        {
            return null;
        }

        String normalized = name.toUpperCase().trim().replace(" ", "_");

        try
        {
            return Skill.valueOf(normalized);
        }
        catch (IllegalArgumentException e)
        {
            // Try common variations
            switch (normalized)
            {
                case "ATTACK":
                    return Skill.ATTACK;
                case "STRENGTH":
                    return Skill.STRENGTH;
                case "DEFENCE":
                case "DEFENSE":
                    return Skill.DEFENCE;
                case "RANGED":
                case "RANGE":
                    return Skill.RANGED;
                case "PRAYER":
                    return Skill.PRAYER;
                case "MAGIC":
                    return Skill.MAGIC;
                case "RUNECRAFT":
                case "RUNECRAFTING":
                    return Skill.RUNECRAFT;
                case "HITPOINTS":
                case "HP":
                    return Skill.HITPOINTS;
                case "CRAFTING":
                    return Skill.CRAFTING;
                case "MINING":
                    return Skill.MINING;
                case "SMITHING":
                    return Skill.SMITHING;
                case "FISHING":
                    return Skill.FISHING;
                case "COOKING":
                    return Skill.COOKING;
                case "FIREMAKING":
                    return Skill.FIREMAKING;
                case "WOODCUTTING":
                    return Skill.WOODCUTTING;
                case "AGILITY":
                    return Skill.AGILITY;
                case "HERBLORE":
                    return Skill.HERBLORE;
                case "THIEVING":
                    return Skill.THIEVING;
                case "FLETCHING":
                    return Skill.FLETCHING;
                case "SLAYER":
                    return Skill.SLAYER;
                case "FARMING":
                    return Skill.FARMING;
                case "CONSTRUCTION":
                    return Skill.CONSTRUCTION;
                case "HUNTER":
                    return Skill.HUNTER;
                default:
                    log.warn("Unknown skill name: {}", name);
                    return null;
            }
        }
    }

    private void checkCurrentSkillProgress()
    {
        if (activeTask == null || targetSkill == null)
        {
            return;
        }

        if (trackingLevel)
        {
            int currentLevel = client.getRealSkillLevel(targetSkill);
            currentProgress = currentLevel;

            if (currentLevel >= targetLevel)
            {
                log.info("Skill level target reached locally: {} level {}", targetSkill, currentLevel);
                // Verify via Jagex Hiscores before completing
                verifyAndComplete();
            }
        }
        else
        {
            int currentXp = client.getSkillExperience(targetSkill);
            currentProgress = currentXp;

            if (currentXp >= targetXp)
            {
                log.info("Skill XP target reached locally: {} XP {}", targetSkill, currentXp);
                // Verify via Jagex Hiscores before completing
                verifyAndComplete();
            }
        }
    }

    /**
     * Verify skill completion via Jagex Hiscores API before marking complete.
     */
    private void verifyAndComplete()
    {
        if (targetSkill == null || activeTask == null)
        {
            return;
        }

        log.info("Verifying {} completion via Jagex Hiscores...", targetSkill.getName());

        if (trackingLevel)
        {
            hiscoreService.verifySkillLevel(targetSkill, targetLevel)
                .thenAccept(result -> {
                    if (result.verified)
                    {
                        log.info("VERIFIED via Hiscores: {} level {} (actual: {})",
                            targetSkill.getName(), targetLevel, result.actualValue);
                        onTaskCompleted();
                    }
                    else
                    {
                        log.warn("Hiscore verification failed: {}", result.message);
                        // Could still complete if hiscores are delayed/unavailable
                        // For now, trust client if hiscores fail
                        if (result.actualValue < 0)
                        {
                            log.info("Hiscores unavailable, trusting client");
                            onTaskCompleted();
                        }
                    }
                });
        }
        else
        {
            hiscoreService.verifySkillXp(targetSkill, targetXp)
                .thenAccept(result -> {
                    if (result.verified)
                    {
                        log.info("VERIFIED via Hiscores: {} XP {} (actual: {})",
                            targetSkill.getName(), targetXp, result.actualValue);
                        onTaskCompleted();
                    }
                    else
                    {
                        log.warn("Hiscore verification failed: {}", result.message);
                        if (result.actualValue < 0)
                        {
                            log.info("Hiscores unavailable, trusting client");
                            onTaskCompleted();
                        }
                    }
                });
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (activeTask == null || targetSkill == null)
        {
            return;
        }

        Skill skill = event.getSkill();
        if (skill != targetSkill)
        {
            return;
        }

        int previousLevel = previousLevels.getOrDefault(skill, 1);
        int previousXpValue = previousXp.getOrDefault(skill, 0);
        int newLevel = event.getLevel();
        int newXp = event.getXp();

        // Update tracking
        previousLevels.put(skill, newLevel);
        previousXp.put(skill, newXp);

        // Check for level up
        if (newLevel > previousLevel)
        {
            log.info("{} leveled up: {} -> {}", skill.getName(), previousLevel, newLevel);
            sendSkillChangeReport(skill, previousLevel, newLevel, previousXpValue, newXp);
        }
        else if (newXp > previousXpValue)
        {
            // XP gain without level up
            int xpGained = newXp - previousXpValue;
            log.debug("{} XP gained: {} (total: {})", skill.getName(), xpGained, newXp);
        }

        // Update progress
        if (trackingLevel)
        {
            currentProgress = newLevel;
            if (activeTask != null)
            {
                activeTask.setCurrentProgress(newLevel);
            }

            if (newLevel >= targetLevel)
            {
                onTaskCompleted();
            }
        }
        else
        {
            currentProgress = newXp;
            if (activeTask != null)
            {
                activeTask.setCurrentProgress(newXp);
            }

            if (newXp >= targetXp)
            {
                onTaskCompleted();
            }
        }
    }

    private void sendSkillChangeReport(Skill skill, int prevLevel, int newLevel, int prevXp, int newXp)
    {
        SkillChangeReport report = SkillChangeReport.builder()
            .playerHash(getPlayerHash())
            .taskId(activeTask != null ? activeTask.getTaskId() : "")
            .skillId(skill.ordinal())
            .skillName(skill.getName())
            .previousLevel(prevLevel)
            .newLevel(newLevel)
            .previousXp(prevXp)
            .newXp(newXp)
            .xpGained(newXp - prevXp)
            .regionId(getCurrentRegionId())
            .gameTick(getGameTick())
            .timestamp(System.currentTimeMillis())
            .totalLevel(client.getTotalLevel())
            .build();

        apiClient.reportSkillChange(report)
            .thenAccept(this::handleVerificationResponse);
    }
}
