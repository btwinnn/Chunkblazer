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
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.TargetNpc;

/**
 * Module for handling THIEVING completion type tasks.
 * Detects successful pickpocket actions on target NPCs.
 *
 * THIEVING tasks have target_npc with npc_ids and quantity.
 * Detection: Player gains Thieving XP while having recently interacted with a watched NPC.
 */
@Slf4j
@Singleton
public class ThievingModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "THIEVING";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	// Minimum XP gain to count as successful pickpocket
	private static final int MIN_XP_THRESHOLD = 1;

	// How many ticks after interacting with an NPC we consider XP gains as pickpockets
	private static final int INTERACTION_TIMEOUT_TICKS = 5;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track task-specific data
	// Map: taskId -> Set of target NPC IDs
	private final Map<String, Set<Integer>> taskTargetNpcs = new ConcurrentHashMap<>();

	// All NPC IDs we're watching (union of all task requirements)
	private final Set<Integer> watchedNpcIds = ConcurrentHashMap.newKeySet();

	// Track Thieving XP for detecting gains
	private int previousThievingXp = -1;

	// Track recent NPC interactions
	private int lastInteractionNpcId = -1;
	private int lastInteractionTick = -1;

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100;

	@Inject
	public ThievingModule()
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
		log.info("=== ThievingModule STARTED ===");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		taskTargetNpcs.clear();
		watchedNpcIds.clear();
		previousThievingXp = -1;
		lastInteractionNpcId = -1;
		lastInteractionTick = -1;
		log.info("ThievingModule stopped");
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);

			log.info("=== ThievingModule: ADDING ACTIVE TASK ===");
			log.info("  Task Name: {}", task.getName());
			log.info("  Task ID: {}", task.getTaskId());

			// Parse target NPCs
			Set<Integer> targetNpcs = new HashSet<>();
			TargetNpc targetNpc = task.getTargetNpc();

			if (targetNpc != null)
			{
				List<Integer> npcIds = targetNpc.getNpcIds();
				if (npcIds != null)
				{
					for (Integer npcId : npcIds)
					{
						targetNpcs.add(npcId);
						watchedNpcIds.add(npcId);
						log.info("      >>> WATCHING NPC ID: {}", npcId);
					}
				}
			}

			taskTargetNpcs.put(task.getTaskId(), targetNpcs);
			log.info("  Target quantity: {}", task.getTargetQuantity());

			// Initialize XP tracking on client thread
			clientThread.invokeLater(this::initializeXpTracking);
		}
		catch (Exception e)
		{
			log.error("ThievingModule.addActiveTask() EXCEPTION: ", e);
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
		taskTargetNpcs.clear();
		watchedNpcIds.clear();
		previousThievingXp = -1;
	}

	@Override
	public void checkProgress()
	{
		// Progress is tracked via XP events, nothing to poll
	}

	private void initializeXpTracking()
	{
		if (client.getLocalPlayer() != null)
		{
			previousThievingXp = client.getSkillExperience(Skill.THIEVING);
			log.info("ThievingModule: Initialized XP tracking at {} xp", previousThievingXp);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;

		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
			log.info(">>> ThievingModule HEARTBEAT - tick {} - activeTasks: {}, watchedNpcs: {}",
				tickCounter, activeTasks.size(), watchedNpcIds.size());
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (activeTasks.isEmpty() || watchedNpcIds.isEmpty())
		{
			return;
		}

		// Check if the player is interacting with a watched NPC
		if (event.getSource() == client.getLocalPlayer() && event.getTarget() instanceof NPC)
		{
			NPC npc = (NPC) event.getTarget();
			int npcId = npc.getId();

			if (watchedNpcIds.contains(npcId))
			{
				lastInteractionNpcId = npcId;
				lastInteractionTick = client.getTickCount();
				log.info(">>> ThievingModule: Player interacting with watched NPC {} (ID: {})",
					npc.getName(), npcId);
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.THIEVING)
		{
			return;
		}

		if (activeTasks.isEmpty())
		{
			return;
		}

		int currentXp = event.getXp();
		if (previousThievingXp < 0)
		{
			previousThievingXp = currentXp;
			return;
		}

		int xpGained = currentXp - previousThievingXp;
		previousThievingXp = currentXp;

		if (xpGained >= MIN_XP_THRESHOLD)
		{
			log.info(">>> ThievingModule: Gained {} Thieving XP", xpGained);

			// Check if we recently interacted with a watched NPC
			int currentTick = client.getTickCount();
			boolean recentInteraction = lastInteractionNpcId > 0 &&
				(currentTick - lastInteractionTick) <= INTERACTION_TIMEOUT_TICKS;

			if (recentInteraction && watchedNpcIds.contains(lastInteractionNpcId))
			{
				log.info(">>> ThievingModule: Successful pickpocket of NPC ID {} detected!", lastInteractionNpcId);

				// Credit progress to matching tasks
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					Set<Integer> taskNpcs = taskTargetNpcs.get(task.getTaskId());
					if (taskNpcs != null && taskNpcs.contains(lastInteractionNpcId))
					{
						creditTaskProgress(task, 1);
					}
				}
			}
			else
			{
				// Still credit if we have tasks but no specific NPC requirement
				// (in case some THIEVING tasks don't specify target_npc)
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					Set<Integer> taskNpcs = taskTargetNpcs.get(task.getTaskId());
					if (taskNpcs == null || taskNpcs.isEmpty())
					{
						// No specific NPC required, credit any thieving XP
						creditTaskProgress(task, 1);
					}
				}
			}
		}
	}

	private void creditTaskProgress(NuzlockeTask task, int amount)
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

		sendTaskProgress(task, "Pickpocket successful", newProgress, required);

		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		// Check for completion
		if (newProgress >= required && !task.isCompleted())
		{
			log.info("ThievingModule: Task '{}' COMPLETED! ({}/{})",
				task.getName(), newProgress, required);
			task.setCompleted(true);

			sendTaskSuccess(task, "Thieving task complete!");

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up
			taskTargetNpcs.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatchedNpcs();
		}
	}

	private void rebuildWatchedNpcs()
	{
		watchedNpcIds.clear();
		for (Set<Integer> npcs : taskTargetNpcs.values())
		{
			watchedNpcIds.addAll(npcs);
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

		log.info("[CHAT] Thieving progress: {} ({}/{}) - {}", task.getName(), current, total, details);
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

		log.info("[CHAT] Thieving success: {} - {}", task.getName(), details);
	}
}
