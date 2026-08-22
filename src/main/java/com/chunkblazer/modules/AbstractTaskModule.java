package com.chunkblazer.modules;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import com.chunkblazer.ChunkBlazerConfig;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.api.ChunkBlazerApiClient;
import com.chunkblazer.api.TaskVerificationResponse;

/**
 * Base class for task completion modules with common functionality.
 */
@Slf4j
public abstract class AbstractTaskModule implements TaskCompletionModule
{
	@Inject
	protected Client client;

	@Inject
	protected ClientThread clientThread;

	@Inject
	protected EventBus eventBus;

	@Inject
	protected ChunkBlazerApiClient apiClient;

	@Inject
	protected ChunkBlazerConfig config;

	@Getter
	protected NuzlockeTask activeTask; // Legacy single task

	@Getter
	protected List<NuzlockeTask> activeTasks = new CopyOnWriteArrayList<>(); // Multiple active tasks (thread-safe)

	@Getter
	protected int currentProgress;

	protected TaskCompletionCallback completionCallback;

	/**
	 * Set the callback to be invoked when a task is completed.
	 */
	public void setCompletionCallback(TaskCompletionCallback callback)
	{
		this.completionCallback = callback;
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		this.activeTask = task;
		this.currentProgress = task.getCurrentProgress();
	}

	/**
	 * Add an active task for multi-task tracking.
	 */
	public void addActiveTask(NuzlockeTask task)
	{
		if (!activeTasks.contains(task))
		{
			activeTasks.add(task);
			// Also set as activeTask for backward compatibility if it's the first
			if (activeTask == null)
			{
				activeTask = task;
				currentProgress = task.getCurrentProgress();
			}
		}
		else
		{
		}
	}

	@Override
	public void onTaskCleared()
	{
		this.activeTask = null;
		this.activeTasks.clear();
		this.currentProgress = 0;
	}

	@Override
	public boolean canHandle(NuzlockeTask task)
	{
		return getCompletionType().equalsIgnoreCase(task.getCompletionType());
	}

	/**
	 * Called when task completion is detected locally.
	 * Triggers server verification.
	 */
	protected void onTaskCompleted()
	{
		if (activeTask == null || completionCallback == null)
		{
			return;
		}


		// Notify the plugin that this task is complete
		completionCallback.onTaskCompleted(activeTask, currentProgress);
	}

	/**
	 * Handle the server verification response for the task the report was ABOUT.
	 *
	 * `reportedTask` is not optional. The old signature had no task parameter and
	 * applied the ack to `activeTask` — the legacy single-task pointer, which
	 * addActiveTask() sets to whichever task happened to register FIRST. So every
	 * ack, for every kill, landed on one arbitrary task:
	 *
	 *   Cruk, session_2026-07-16_20-00-46 — he killed a SCORPION at 20:01:47, which
	 *   sent three kill reports (one per matching task). Each came back with the
	 *   hardcoded verifiedProgress=1 and each raised progress on the module's first
	 *   registered task, 'Defeat a Highwayman in 24 Seconds'. It ticked itself off
	 *   with no kill and no chat message (incrementTaskProgress was never called),
	 *   and only ChunkBlazerPlugin's PROGRESS REGRESSION guard caught it at login.
	 *
	 * The raise-only rule below was the earlier half of this fix; it stopped an ack
	 * LOWERING the wrong task but still let it raise one. Routing is the other half.
	 */
	protected void handleVerificationResponse(TaskVerificationResponse response, NuzlockeTask reportedTask)
	{
		if (response.isSuccess())
		{
			if (response.isTaskCompleted())
			{
				if (completionCallback != null && reportedTask != null)
				{
					completionCallback.onServerVerified(reportedTask, response.getPointsAwarded());
				}
			}
			else
			{
				// The per-event ack is a RECEIPT, not a verification: the
				// server's event endpoints hardcode verifiedProgress=1 (they
				// record the event and answer OK). Treating that as
				// authoritative stomped local progress back to 1 after every
				// kill — Cruk's 25-pirate task ping-ponged 1<->2 forever
				// (session_2026-07-15), and the save no-oped because the
				// stored value never changed. Server-ahead catch-up is
				// allowed; REGRESSION is not — locally observed progress wins
				// until a real sync says otherwise.
				int verified = response.getVerifiedProgress();
				if (reportedTask != null && verified > reportedTask.getCurrentProgress())
				{
					reportedTask.setCurrentProgress(verified);
					if (reportedTask == activeTask)
					{
						currentProgress = verified;
					}
				}
			}
		}
		else
		{
			log.warn("Server verification failed: {}", response.getErrorMessage());
			if (response.getRejectionReason() != null)
			{
				log.warn("Rejection reason: {}", response.getRejectionReason());
			}
		}
	}

	/**
	 * Get a hash of the player's RSN for API calls.
	 */
	protected String getPlayerHash()
	{
		Player player = client.getLocalPlayer();
		if (player == null || player.getName() == null)
		{
			return "unknown";
		}

		return Hashing.sha256()
			.hashString(player.getName().toLowerCase().trim(), StandardCharsets.UTF_8)
			.toString()
			.substring(0, 16);
	}

	/**
	 * Get the current region ID.
	 */
	protected int getCurrentRegionId()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return -1;
		}
		return player.getWorldLocation().getRegionID();
	}

	/**
	 * Get current game tick.
	 */
	protected int getGameTick()
	{
		return client.getTickCount();
	}

	/**
	 * Callback interface for task completion events.
	 */
	public interface TaskCompletionCallback
	{
		/**
		 * Called when a task is completed locally (before server verification).
		 */
		void onTaskCompleted(NuzlockeTask task, int progress);

		/**
		 * Called when the server has verified the task completion.
		 */
		void onServerVerified(NuzlockeTask task, int pointsAwarded);

		/**
		 * Called when progress is updated.
		 */
		void onProgressUpdated(NuzlockeTask task, int newProgress);
	}
}
