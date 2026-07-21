package net.runelite.client.plugins.chunkblazer.modules;

import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.TaskConstraints;

/**
 * Module for SKILL_THRESHOLD tasks — the Progression tier of the Global Tasks
 * pool. Each task is "reach level N in skill S", for ten rungs per skill.
 *
 * <h2>Non-retroactive by design</h2>
 * Progression only ever pays for levels gained AFTER an account starts being
 * tracked. An established account that is already 80 Thieving must not be handed
 * the 10/20/30/40/50/60/70/80 rungs for work it did before ChunkBlazer ever saw
 * it. That is enforced with a per-skill BASELINE, captured once and then frozen:
 * a rung is eligible only when {@code threshold > baseline[skill]}. Ineligible
 * rungs are never registered here at all — they don't show in the panel, can't
 * complete, and score nothing.
 *
 * <p>The baseline lives plugin-side ({@code ChunkBlazerPlugin#getProgressionBaseline})
 * because it must be captured from live client skill data. This module is handed
 * only the tasks that survived that filter, so it holds no baseline logic itself
 * — it just watches for the level actually being reached.
 *
 * <p>Hitpoints has no level-10 rung: accounts spawn at 10 HP, so it would be
 * unearnable. It is omitted from the task data rather than special-cased here.
 *
 * <h2>Why StatChanged and not a sweep</h2>
 * {@link QuestCheckModule} polls, because {@code Quest.getState()} is a script
 * invocation with no event that pinpoints the quest that changed. Skill levels
 * have exactly such an event: StatChanged carries the skill and its new level,
 * so this module checks only the rungs of the skill that just changed — no
 * polling, no rate limiting, no per-tick cost.
 */
@Slf4j
@Singleton
public class ProgressionModule extends AbstractTaskModule
{
	private static final String PROGRESSION_TYPE = "SKILL_THRESHOLD";

	// Chat colors for ChunkBlazer messages (matches QuestCheckModule).
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_BLACK = "000000";

	@Inject
	private ChatMessageManager chatMessageManager;

	// taskId -> the rung it represents. Resolved once in addActiveTask so a bad
	// skill name in the JSON fails loudly at registration rather than silently
	// never firing.
	private final Map<String, Rung> taskRungs = new ConcurrentHashMap<>();

	/** One "reach level N in skill S" rung. */
	private static final class Rung
	{
		final Skill skill;
		final int level;

		Rung(Skill skill, int level)
		{
			this.skill = skill;
			this.level = level;
		}
	}

	@Inject
	public ProgressionModule()
	{
	}

	@Override
	public String getCompletionType()
	{
		return PROGRESSION_TYPE;
	}

	@Override
	public boolean canHandle(NuzlockeTask task)
	{
		return PROGRESSION_TYPE.equalsIgnoreCase(task.getCompletionType());
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
		taskRungs.clear();
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();
		String skillName = constraints != null ? constraints.getRequiredSkill() : null;

		if (skillName == null || skillName.isEmpty())
		{
			log.warn("SKILL_THRESHOLD task '{}' has no constraints.required_skill — not tracking",
				task.getTaskId());
			return;
		}

		final Skill skill;
		try
		{
			skill = Skill.valueOf(skillName.toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			log.warn("SKILL_THRESHOLD task '{}' names unknown skill '{}' — not tracking",
				task.getTaskId(), skillName);
			return;
		}

		int level = constraints.getRequiredLevel();
		if (level <= 1)
		{
			log.warn("SKILL_THRESHOLD task '{}' has no meaningful required_level ({}) — not tracking",
				task.getTaskId(), level);
			return;
		}

		super.addActiveTask(task);
		taskRungs.put(task.getTaskId(), new Rung(skill, level));

		// The plugin only registers rungs ABOVE the frozen baseline, but a player
		// can still have crossed one while the plugin was off (or on another
		// machine). Settle those at registration instead of making them re-level.
		checkTaskCompletion(task);
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
		taskRungs.clear();
	}

	@Override
	public void checkProgress()
	{
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			checkTaskCompletion(task);
		}
	}

	/**
	 * A level up (or any XP gain) in a tracked skill. Only the rungs of THIS
	 * skill are examined, so the cost is a handful of int comparisons.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}

		Skill changed = event.getSkill();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Rung rung = taskRungs.get(task.getTaskId());
			if (rung != null && rung.skill == changed)
			{
				checkTaskCompletion(task);
			}
		}
	}

	/** @return true if this call completed the task. */
	private boolean checkTaskCompletion(NuzlockeTask task)
	{
		if (task.isCompleted())
		{
			activeTasks.remove(task);
			taskRungs.remove(task.getTaskId());
			return false;
		}

		Rung rung = taskRungs.get(task.getTaskId());
		if (rung == null)
		{
			return false;
		}

		// Skill data is only valid once logged in.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}

		// REAL level, not boosted — a stew or a potion must not buy a rung.
		if (client.getRealSkillLevel(rung.skill) < rung.level)
		{
			return false;
		}

		task.setCurrentProgress(1);
		task.setCompleted(true);
		sendTaskSuccess(task);

		if (completionCallback != null)
		{
			completionCallback.onTaskCompleted(task, 1);
		}

		// Stop tracking — completeTask() on the plugin side persists this task
		// into the completed set, and the next loadActiveTasks() won't re-register it.
		activeTasks.remove(task);
		taskRungs.remove(task.getTaskId());
		return true;
	}

	private void sendTaskSuccess(NuzlockeTask task)
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
	}
}
