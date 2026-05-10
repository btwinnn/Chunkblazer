package net.runelite.client.plugins.chunkblazer.modules;

import java.util.HashSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;

/**
 * Module for handling AGILITY completion type tasks.
 * Detects agility course lap completions via XP gains.
 *
 * AGILITY tasks are typically "Complete X laps of Y course".
 * Detection: When Agility XP is gained, credit progress.
 * For lap-based tasks, significant XP gains (20+ XP) indicate obstacle completion.
 */
@Slf4j
@Singleton
public class AgilityModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "AGILITY";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	// Per-task XP threshold for a credit. AGILITY tasks come in two shapes:
	//
	//   Lap tasks (have required_object in JSON, e.g. "Complete some Laps of
	//   Draynor Rooftop"): every obstacle awards 5–22 XP, then the lap-end
	//   bonus awards 39+ XP as a separate StatChanged event. We want to count
	//   only the lap-end bonus, so the threshold has to sit above any single
	//   obstacle.
	//
	//   Shortcut tasks (no required_object, e.g. "Use the Level 21 Underwall
	//   Tunnel"): one tiny XP gain per use, want to credit on it. Threshold
	//   has to be small.
	//
	// 30 sits between the largest single-obstacle XP (~22 XP on rooftop
	// courses) and the smallest lap-end bonus (39 XP, Gnome Stronghold).
	// 5 catches every legitimate shortcut XP gain.
	//
	// Caveat: a few non-rooftop lap courses have a single obstacle whose XP
	// straddles 30 (e.g. Wilderness Agility's Pile of Rocks at 62.5 XP) and
	// will double-count laps until AgilityModule moves to per-task
	// required_object id tracking. Acceptable for now — Mike's report was
	// Draynor.
	private static final int LAP_XP_THRESHOLD = 30;
	private static final int SHORTCUT_XP_THRESHOLD = 5;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track Agility XP for detecting gains
	private int previousAgilityXp = -1;

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100;

	@Inject
	public AgilityModule()
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
		log.info("=== AgilityModule STARTED ===");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		previousAgilityXp = -1;
		log.info("AgilityModule stopped");
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);

			log.info("=== AgilityModule: ADDING ACTIVE TASK ===");
			log.info("  Task Name: {}", task.getName());
			log.info("  Task ID: {}", task.getTaskId());
			log.info("  Target Quantity: {}", task.getTargetQuantity());

			// Initialize XP tracking on client thread
			clientThread.invokeLater(this::initializeXpTracking);
		}
		catch (Exception e)
		{
			log.error("AgilityModule.addActiveTask() EXCEPTION: ", e);
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
		previousAgilityXp = -1;
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
			previousAgilityXp = client.getSkillExperience(Skill.AGILITY);
			log.info("AgilityModule: Initialized XP tracking at {} xp", previousAgilityXp);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;

		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
			log.info(">>> AgilityModule HEARTBEAT - tick {} - activeTasks: {}",
				tickCounter, activeTasks.size());
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.AGILITY)
		{
			return;
		}

		if (activeTasks.isEmpty())
		{
			return;
		}

		int currentXp = event.getXp();
		if (previousAgilityXp < 0)
		{
			previousAgilityXp = currentXp;
			return;
		}

		int xpGained = currentXp - previousAgilityXp;
		previousAgilityXp = currentXp;

		// Per-task threshold: a lap-style task (required_object present) only
		// credits on the per-lap bonus; a shortcut credits on any obstacle XP.
		// Both kinds can be active at the same time, so check each task.
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			int threshold = task.isHasRequiredObject() ? LAP_XP_THRESHOLD : SHORTCUT_XP_THRESHOLD;
			if (xpGained >= threshold)
			{
				log.info(">>> AgilityModule: '{}' credited (gained {} XP, threshold {})",
					task.getName(), xpGained, threshold);
				creditTaskProgress(task, 1);
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

		sendTaskProgress(task, "Obstacle completed", newProgress, required);

		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		// Check for completion
		if (newProgress >= required && !task.isCompleted())
		{
			log.info("AgilityModule: Task '{}' COMPLETED! ({}/{})",
				task.getName(), newProgress, required);
			task.setCompleted(true);

			sendTaskSuccess(task, "Course completed!");

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up
			activeTasks.remove(task);
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

		log.info("[CHAT] Agility progress: {} ({}/{}) - {}", task.getName(), current, total, details);
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

		log.info("[CHAT] Agility success: {} - {}", task.getName(), details);
	}
}
