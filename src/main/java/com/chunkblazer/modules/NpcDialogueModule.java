package com.chunkblazer.modules;

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
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TargetNpc;

/**
 * Module for handling NPC_DIALOGUE completion type tasks.
 * Detects when the player talks to specific NPCs.
 *
 * Tasks have target_npc with npc_ids.
 * Detection: Player interacts with watched NPC and dialogue widget opens.
 */
@Slf4j
@Singleton
public class NpcDialogueModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "NPC_DIALOGUE";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_BLACK = "000000";

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track task-specific data
	// Map: taskId -> Set of target NPC IDs
	private final Map<String, Set<Integer>> taskTargetNpcs = new ConcurrentHashMap<>();

	// All NPC IDs we're watching
	private final Set<Integer> watchedNpcIds = ConcurrentHashMap.newKeySet();

	// Track recent NPC interaction to confirm dialogue
	private int lastInteractionNpcId = -1;
	private int lastInteractionTick = -1;
	private static final int INTERACTION_TIMEOUT_TICKS = 5;

	// Track if dialogue was open last tick (for edge detection)
	private boolean wasDialogueOpen = false;


	@Inject
	public NpcDialogueModule()
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
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		taskTargetNpcs.clear();
		watchedNpcIds.clear();
		lastInteractionNpcId = -1;
		wasDialogueOpen = false;
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);


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
					}
				}
			}

			taskTargetNpcs.put(task.getTaskId(), targetNpcs);
		}
		catch (Exception e)
		{
			log.error("NpcDialogueModule.addActiveTask() EXCEPTION: ", e);
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
	}

	@Override
	public void checkProgress()
	{
		// Progress is tracked via events
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{


		if (activeTasks.isEmpty())
		{
			return;
		}

		// Check if dialogue widget is now open
		boolean isDialogueOpen = isDialogueOpen();

		// Detect dialogue opening (edge detection: was closed, now open)
		if (isDialogueOpen && !wasDialogueOpen)
		{

			// Check if we recently interacted with a watched NPC
			int currentTick = client.getTickCount();
			boolean recentInteraction = lastInteractionNpcId > 0 &&
				(currentTick - lastInteractionTick) <= INTERACTION_TIMEOUT_TICKS;

			if (recentInteraction && watchedNpcIds.contains(lastInteractionNpcId))
			{

				// Credit progress to matching tasks
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					Set<Integer> taskNpcs = taskTargetNpcs.get(task.getTaskId());
					if (taskNpcs != null && taskNpcs.contains(lastInteractionNpcId))
					{
						creditTaskProgress(task);
					}
				}
			}
		}

		wasDialogueOpen = isDialogueOpen;
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
			}
		}
	}

	private boolean isDialogueOpen()
	{
		// Check various dialogue widget groups
		// NPC dialogue
		Widget npcDialogue = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
		if (npcDialogue != null && !npcDialogue.isHidden())
		{
			return true;
		}

		// Player dialogue options
		Widget playerDialogue = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
		if (playerDialogue != null && !playerDialogue.isHidden())
		{
			return true;
		}

		return false;
	}

	private void creditTaskProgress(NuzlockeTask task)
	{
		if (task.isCompleted())
		{
			return;
		}

		int previousProgress = task.getCurrentProgress();
		int newProgress = previousProgress + 1;
		int required = task.getTargetQuantity();

		// Default to 1 if no target quantity specified
		if (required <= 0)
		{
			required = 1;
		}

		task.setCurrentProgress(newProgress);

		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		// Check for completion
		if (newProgress >= required)
		{
			task.setCompleted(true);

			sendTaskSuccess(task, "Dialogue complete!");

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

	}
}
