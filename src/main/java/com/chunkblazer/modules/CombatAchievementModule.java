package com.chunkblazer.modules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import com.chunkblazer.NuzlockeTask;

/**
 * Handles COMBAT_ACHIEVEMENT tasks: "complete all these Combat Achievements".
 *
 * <p>A task carries {@code ca_ids} — the sparse in-game Combat Achievement task
 * ids (see CA_Struct_IDs.json) — and completes when EVERY listed id reads done.
 *
 * <p>Completion state is not a per-task varbit: the game packs every CA task's
 * done-flag into a block of 21 VarPlayers (varps), 32 bits each. For CA id N the
 * flag is bit {@code N % 32} of varp {@code CA_TASK_COMPLETED_VARPS[N / 32]}. The
 * varp numbers are non-contiguous (Jagex ran out of adjacent ids over the years),
 * so we index into an explicit ordered array rather than {@code base + n}.
 *
 * <p>Read with {@code getVarpValue} (NOT {@code getVarbitValue} — these are whole
 * 32-bit VarPlayers, not defined varbits). Values are populated from login, so no
 * need to open the CA interface. Confirmed against ehubbartt/combat-achievements-tracker
 * and osrs-reldo/tasks-tracker-plugin.
 */
@Slf4j
@Singleton
public class CombatAchievementModule extends AbstractTaskModule
{
	private static final String TYPE = "COMBAT_ACHIEVEMENT";

	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_BLACK = "000000";

	/**
	 * The 21 VarPlayers packing CA task completion, in task-id order (index 0..20).
	 * Raw ids (not gameval constants) so this compiles against any runelite-api
	 * version. Grows by one entry each time Jagex crosses a 32-task boundary — the
	 * bounds check in {@link #isCaComplete(int)} keeps an out-of-range id safe.
	 */
	private static final int[] CA_TASK_COMPLETED_VARPS = {
		3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125, 3126, 3127, 3128,
		3387, 3718, 3773, 3774, 4204, 4496, 4721, 5673
	};

	@Inject
	private ChatMessageManager chatMessageManager;

	// taskId -> the CA ids that must all be complete.
	private final Map<String, List<Integer>> taskCaIds = new ConcurrentHashMap<>();

	@Inject
	public CombatAchievementModule()
	{
	}

	@Override
	public String getCompletionType()
	{
		return TYPE;
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
		taskCaIds.clear();
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		super.onTaskAssigned(task);
		addActiveTask(task);
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		super.addActiveTask(task);

		List<Integer> caIds = task.getCaIds();
		if (caIds == null || caIds.isEmpty())
		{
			log.warn("COMBAT_ACHIEVEMENT task {} has no ca_ids — it can never complete", task.getTaskId());
			return;
		}
		taskCaIds.put(task.getTaskId(), new ArrayList<>(caIds));

		// A boss chunk grants every task at once; the player may already have these
		// CAs done, so check immediately (on the client thread).
		clientThread.invokeLater(() -> checkTaskCompletion(task));
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		taskCaIds.clear();
	}

	@Override
	public void checkProgress()
	{
		for (NuzlockeTask task : activeTasks)
		{
			checkTaskCompletion(task);
		}
	}

	/**
	 * CA completion varps are re-broadcast on login; re-scan so a task assigned
	 * while logged out (or a CA earned on another client) is picked up.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN && !activeTasks.isEmpty())
		{
			clientThread.invokeLater(this::checkProgress);
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}
		// Only re-scan when a CA-completion varp actually changed. VarbitChanged
		// fires for every varp/varbit, and a fresh login replays THOUSANDS in one
		// burst on the client thread — re-scanning every active CA task on each of
		// those was needless work stacked on the heaviest moment of the session
		// (a maxed account with many active tasks). CA completion lives only in the
		// packed varps below, and those change rarely, so filter to them. We still
		// force a full re-scan on login via onGameStateChanged.
		if (!isCaVarp(event.getVarpId()))
		{
			return;
		}
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			checkTaskCompletion(task);
		}
	}

	private static boolean isCaVarp(int varpId)
	{
		for (int v : CA_TASK_COMPLETED_VARPS)
		{
			if (v == varpId)
			{
				return true;
			}
		}
		return false;
	}

	private void checkTaskCompletion(NuzlockeTask task)
	{
		if (task.isCompleted())
		{
			return;
		}
		List<Integer> caIds = taskCaIds.get(task.getTaskId());
		if (caIds == null || caIds.isEmpty())
		{
			return;
		}

		int done = 0;
		for (int caId : caIds)
		{
			if (isCaComplete(caId))
			{
				done++;
			}
		}

		task.setTargetQuantity(caIds.size());
		task.setCurrentProgress(done);

		if (done < caIds.size())
		{
			if (completionCallback != null)
			{
				completionCallback.onProgressUpdated(task, done);
			}
			return;
		}

		task.setCompleted(true);
		sendTaskSuccess(task);
		if (completionCallback != null)
		{
			completionCallback.onTaskCompleted(task, done);
		}

		taskCaIds.remove(task.getTaskId());
		activeTasks.remove(task);
	}

	/**
	 * @return true if the given Combat Achievement task id reads complete. Must run
	 * on the client thread (reads a VarPlayer).
	 */
	private boolean isCaComplete(int caId)
	{
		int arrayIndex = caId / 32;
		int bitIndex = caId % 32;
		if (arrayIndex < 0 || arrayIndex >= CA_TASK_COMPLETED_VARPS.length)
		{
			return false;
		}
		int varpValue = client.getVarpValue(CA_TASK_COMPLETED_VARPS[arrayIndex]);
		return (varpValue & (1 << bitIndex)) != 0;
	}

	private void sendTaskSuccess(NuzlockeTask task)
	{
		if (!config.showChatSuccess())
		{
			return;
		}
		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> "
			+ "<col=" + COLOR_DARK_BLUE + ">Task Complete!</col> "
			+ "<col=" + COLOR_BLACK + ">" + task.getName() + "</col>";
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());
	}
}
