package com.chunkblazer.modules;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TaskConstraints;

/**
 * Module for handling QUEST_CHECK completion type tasks — the "Global Tasks"
 * pool, which is region-independent and active for every account from load.
 *
 * constraints.quest holds the net.runelite.api.Quest enum constant name (e.g.
 * "DRAGON_SLAYER_II"). Quest.getState() runs the QUEST_STATUS_GET script, which
 * resolves that quest's varp/varbit AND its per-quest "finished" threshold
 * internally and normalises the answer (2 == FINISHED for every quest). So
 * unlike VarbitCheckModule this module keeps no per-task varp id or expected
 * value — the enum name is the entire configuration.
 *
 * SWEEP COST: getState() is a client script invocation, and the Global Tasks
 * pool is ~200 tasks. Checking every task on every VarbitChanged (which fires
 * many times per tick, especially on login and region change) would mean
 * thousands of runScript calls in a burst. Instead a VarbitChanged only marks
 * the pool dirty; the actual sweep happens on GameTick, at most once every
 * SWEEP_INTERVAL_TICKS. Quest completion is not latency-critical — a couple of
 * seconds between finishing a quest and the task ticking off is fine.
 */
@Slf4j
@Singleton
public class QuestCheckModule extends AbstractTaskModule
{
	private static final String QUEST_TYPE = "QUEST_CHECK";

	// Minimum game ticks between full sweeps of the pool. See SWEEP COST above.
	// 25 ticks = ~15s. The plugin backfills every already-finished quest in one
	// batch at registration, so this sweep only has to notice quests finished
	// DURING play — which is not latency-critical, and a slower poll keeps the
	// steady-state script cost negligible.
	private static final int SWEEP_INTERVAL_TICKS = 25;

	// Hard cap on completions fired per sweep. The plugin's backfill should mean
	// at most one or two quests ever complete in the same sweep, but a single
	// completion runs an expensive per-task pipeline (config writes + a full
	// Swing rebuild of the panel), and firing ~150 of them in one tick is what
	// hard-locked the client on 2026-07-19. Leftovers roll into the next sweep.
	private static final int MAX_COMPLETIONS_PER_SWEEP = 5;

	// Chat colors for ChunkBlazer messages (matches VarbitCheckModule).
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_BLACK = "000000";

	@Inject
	private ChatMessageManager chatMessageManager;

	// taskId -> resolved Quest enum constant. Resolved once in addActiveTask so
	// a bad name in the JSON fails loudly at registration rather than silently
	// never firing, and so the sweep doesn't re-run valueOf() 200x per sweep.
	private final Map<String, Quest> taskQuests = new ConcurrentHashMap<>();

	// Quest constants named in JSON that don't exist in this client's Quest
	// enum. Tracked only so the warning is logged once per name instead of on
	// every reload. Happens when the task data is newer than the RuneLite API
	// the plugin is built against (e.g. a quest released since the last sync).
	private final Set<String> unknownQuestNames = ConcurrentHashMap.newKeySet();

	private boolean sweepPending;
	private int lastSweepTick;

	@Inject
	public QuestCheckModule()
	{
	}

	@Override
	public String getCompletionType()
	{
		return QUEST_TYPE;
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
		taskQuests.clear();
		unknownQuestNames.clear();
		sweepPending = false;
		lastSweepTick = 0;
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();
		String questName = constraints != null ? constraints.getQuest() : null;

		if (questName == null || questName.isEmpty())
		{
			log.warn("QUEST_CHECK task '{}' has no constraints.quest — not tracking", task.getTaskId());
			return;
		}

		final Quest quest;
		try
		{
			quest = Quest.valueOf(questName);
		}
		catch (IllegalArgumentException e)
		{
			if (unknownQuestNames.add(questName))
			{
				log.warn("QUEST_CHECK task '{}' names unknown quest constant '{}' — "
					+ "task data is likely newer than the RuneLite API this plugin was built against",
					task.getTaskId(), questName);
			}
			return;
		}

		super.addActiveTask(task);
		taskQuests.put(task.getTaskId(), quest);

		// A player may already have finished this quest before the task pool was
		// ever registered (the common case — Global Tasks are handed out to
		// every account at load, including maxed questers). Sweep so those
		// complete immediately instead of waiting for an unrelated varbit.
		requestSweep();
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
		taskQuests.clear();
		sweepPending = false;
	}

	@Override
	public void checkProgress()
	{
		sweep();
	}

	/**
	 * Quest state is varp/varbit backed, so any quest completion raises this.
	 * We only flag the pool dirty here — the sweep itself is rate limited in
	 * onGameTick so a burst of varbit changes can't trigger a burst of sweeps.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!activeTasks.isEmpty())
		{
			requestSweep();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!sweepPending || activeTasks.isEmpty())
		{
			return;
		}

		int now = client.getTickCount();
		if (now - lastSweepTick < SWEEP_INTERVAL_TICKS)
		{
			return;
		}

		lastSweepTick = now;
		sweepPending = false;
		sweep();
	}

	private void requestSweep()
	{
		sweepPending = true;
	}

	/**
	 * Check every tracked task once. Runs on the client thread (all callers are
	 * @Subscribe handlers or clientThread work), which getState() requires.
	 */
	private void sweep()
	{
		// getState() runs a script against the quest interface, which only holds
		// valid data once the player is logged in. Calling it at the login
		// screen returns garbage rather than failing, so gate explicitly.
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int completions = 0;
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			if (checkTaskCompletion(task))
			{
				completions++;
				if (completions >= MAX_COMPLETIONS_PER_SWEEP)
				{
					// Anything still finished gets picked up next sweep.
					requestSweep();
					return;
				}
			}
		}
	}

	/** @return true if this call completed the task. */
	private boolean checkTaskCompletion(NuzlockeTask task)
	{
		if (task.isCompleted())
		{
			activeTasks.remove(task);
			taskQuests.remove(task.getTaskId());
			return false;
		}

		Quest quest = taskQuests.get(task.getTaskId());
		if (quest == null)
		{
			return false;
		}

		if (quest.getState(client) != QuestState.FINISHED)
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
		taskQuests.remove(task.getTaskId());
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
