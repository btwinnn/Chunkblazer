/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer.modules;

import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TaskConstraints;

/**
 * Module for SKILL_THRESHOLD tasks, the Progression tier of the Global Tasks pool: "reach
 * level N in skill S", ten rungs per skill.
 *
 * <h2>Non-retroactive by design</h2>
 * Only levels gained after tracking starts pay. A per-skill BASELINE, captured once and
 * frozen, makes a rung eligible only when {@code threshold > baseline[skill]}; other
 * rungs are never registered. It lives plugin-side
 * ({@code ChunkBlazerPlugin#getProgressionBaseline}) because it needs live client skill
 * data.
 *
 * <p>Hitpoints has no level-10 rung (accounts spawn at 10 HP); it is omitted from the
 * task data.
 *
 * <h2>Why StatChanged and not a sweep</h2>
 * {@link QuestCheckModule} polls because no event pinpoints the quest that changed.
 * StatChanged carries the skill and new level, so only that skill's rungs are checked.
 */
@Slf4j
@Singleton
public class ProgressionModule extends AbstractTaskModule
{
	private static final String PROGRESSION_TYPE = "SKILL_THRESHOLD";
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
			log.warn("Task '{}': no required_skill", task.getTaskId());
			return;
		}

		final Skill skill;
		try
		{
			skill = Skill.valueOf(skillName.toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			log.warn("Task '{}': unknown skill '{}'", task.getTaskId(), skillName);
			return;
		}

		int level = constraints.getRequiredLevel();
		if (level <= 1)
		{
			log.warn("Task '{}': bad required_level {}", task.getTaskId(), level);
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
		sendTaskSuccess(task, null);

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
}
