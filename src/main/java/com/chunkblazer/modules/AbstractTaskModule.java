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

import static com.chunkblazer.Strings.t;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.MenuAction;
import java.util.Set;
import java.util.EnumSet;
import net.runelite.api.MessageNode;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.api.ChatMessageType;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

	// Colours of the plugin's chat lines, shared by every module.
	protected static final String COLOR_BLUE = "3366ff";
	protected static final String COLOR_DARK_BLUE = "1a5276";
	protected static final String COLOR_DARK_GREEN = "228b22";
	protected static final String COLOR_RED = "ff3333";
	protected static final String COLOR_BLACK = "000000";

	// The left-click/right-click options on a game object (climb, steal, build...).
	protected static final Set<MenuAction> GAME_OBJECT_ACTIONS = EnumSet.of(
		MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION,
		MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION
	);

	private static final Set<Integer> TUTORIAL_ISLAND_REGIONS = ImmutableSet.of(12336, 12335, 12592, 12080, 12079, 12436);

	@Inject
	protected ChatMessageManager chatMessageManager;

	private HashMap<NuzlockeTask, MessageNode[]> taskMessages = new HashMap<>();


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

	// chatMessageManager.add() does not return the MessageNode of the sent message
	// We could try and add listeners or query the chatbox buffer but this introduces possible races and is not performant
	// Calling client.addChatMessage() directly skips message formatting passes and would need a rewrite of the messages here
	// Reimplement chatMessageManager.add in this class to capture the MessageNode
	private MessageNode add(QueuedMessage message)
	{
		// Do not send message if the player is on tutorial island
		final Player player = client.getLocalPlayer();
		if (player != null && player.getWorldLocation() != null
			&& TUTORIAL_ISLAND_REGIONS.contains(player.getWorldLocation().getRegionID()))
		{
			return null;
		}

		// this updates chat cycle
		final MessageNode line = client.addChatMessage(
			message.getType(),
			MoreObjects.firstNonNull(message.getName(), ""),
			MoreObjects.firstNonNull(message.getRuneLiteFormattedMessage(), message.getValue()),
			message.getSender());

		// Null when the client didn't add the line (a mocked Client in tests).
		if (line == null)
		{
			return null;
		}

		// Update the message with RuneLite additions
		line.setRuneLiteFormatMessage(message.getRuneLiteFormattedMessage());

		if (message.getTimestamp() != 0)
		{
			line.setTimestamp(message.getTimestamp());
		}

		return line;
	}

	/**
	 * Queue one line in the game chat.
	 */
	protected MessageNode chatLine(String message)
	{
		/*
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());
		*/

		return add(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());
	}

	/** "[ChunkBlazer] heading task-name suffix", then an indented detail line when there is one. */
	protected void announce(String headingColor, String heading, NuzlockeTask task, String suffix, String detail)
	{
		// The chat line map is null on a mocked Client (tests), so guard it.
		Map<Integer, ChatLineBuffer> chatLines = client.getChatLineMap();
		ChatLineBuffer ccInfoBuffer = chatLines == null ? null : chatLines.get(ChatMessageType.GAMEMESSAGE.getType());
		MessageNode[] lastMessages = taskMessages.get(task);
		if (lastMessages != null)
		{
			if (ccInfoBuffer != null)
			{
				if (lastMessages[0] != null)
				{
					ccInfoBuffer.removeMessageNode(lastMessages[0]);
				}
				if (lastMessages[1] != null)
				{
					ccInfoBuffer.removeMessageNode(lastMessages[1]);
				}
			}
		}
		MessageNode taskMessage = chatLine(
			t("module.announce", COLOR_BLUE, headingColor, heading, COLOR_BLACK, task.getName(), suffix));

		MessageNode detailMessage = null;
		if (detail != null && !detail.isEmpty())
		{
			detailMessage = chatLine("  - " + detail);
		}
		MessageNode[] messages = {taskMessage, detailMessage};
		taskMessages.put(task, messages);
	}

	protected void sendTaskSuccess(NuzlockeTask task, String details)
	{
		if (config.showChatSuccess())
		{
			announce(COLOR_DARK_BLUE, "Task Complete!", task, "", details);
		}
	}

	protected void sendTaskProgress(NuzlockeTask task, String details, int current, int total)
	{
		if (config.showChatProgress())
		{
			announce(COLOR_DARK_GREEN, "Task Progress:", task, " (" + current + "/" + total + ")", details);
		}
	}

	protected void sendTaskFailure(NuzlockeTask task, String reason)
	{
		if (config.showChatFailed())
		{
			announce(COLOR_RED, "Task Failed:", task, "", "Reason: " + reason);
		}
	}
}
