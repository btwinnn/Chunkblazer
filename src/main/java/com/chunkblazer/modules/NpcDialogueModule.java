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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TargetNpc;

/**
 * Module for NPC_DIALOGUE completion type tasks: the player interacts with a watched NPC
 * and the dialogue widget opens. When the game starts the conversation itself (Death
 * after you die), the speaker's name in the dialogue box stands in.
 */
@Slf4j
@Singleton
public class NpcDialogueModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "NPC_DIALOGUE";
	// Track task-specific data
	// Map: taskId -> Set of target NPC IDs
	private final Map<String, Set<Integer>> taskTargetNpcs = new ConcurrentHashMap<>();

	// All NPC IDs we're watching
	private final Set<Integer> watchedNpcIds = ConcurrentHashMap.newKeySet();

	// Map: taskId -> target NPC name (lower case), for the speaker-name fallback
	private final Map<String, String> taskTargetNames = new ConcurrentHashMap<>();
	// Tasks credited by speaker name during the dialogue that is open now
	private final Set<String> creditedThisDialogue = new HashSet<>();

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
		taskTargetNames.clear();
		creditedThisDialogue.clear();
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
			if (targetNpc != null && targetNpc.getName() != null)
			{
				taskTargetNames.put(task.getTaskId(), targetNpc.getName().toLowerCase());
			}
		}
		catch (Exception e)
		{
			log.error("NpcDialogueModule failed to add a task", e);
		}
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		taskTargetNpcs.clear();
		watchedNpcIds.clear();
		taskTargetNames.clear();
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
			int npcId = dialogueNpcId();
			if (npcId > 0)
			{
				// Credit progress to matching tasks
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					Set<Integer> taskNpcs = taskTargetNpcs.get(task.getTaskId());
					if (taskNpcs != null && taskNpcs.contains(npcId))
					{
						creditTaskProgress(task);
					}
				}
			}
		}

		// Speaker-name fallback, checked on every tick the dialogue is open: the speaker
		// can change mid-conversation, and a scripted conversation has no interaction to
		// read. Only while we aren't interacting with some OTHER npc, so a same-named npc
		// that isn't a target (another "Guard") can't stand in for the real one.
		if (isDialogueOpen)
		{
			String speaker = dialogueSpeaker();
			if (speaker != null && !interactingWithOtherNpc())
			{
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					String taskId = task.getTaskId();
					if (speaker.equals(taskTargetNames.get(taskId)) && creditedThisDialogue.add(taskId))
					{
						creditTaskProgress(task);
					}
				}
			}
		}
		else
		{
			creditedThisDialogue.clear();
		}

		wasDialogueOpen = isDialogueOpen;
	}

	/**
	 * The watched NPC the dialogue that just opened belongs to, or -1. Either the one we
	 * clicked a few ticks ago, or the one we are interacting with right now. The second
	 * matters because the target is set on the CLICK: clicking Talk-to from across a room
	 * means the walk alone outlasts the short window, and the talk was never credited
	 * (the Varrock estate agent report).
	 */
	int dialogueNpcId()
	{
		int currentTick = client.getTickCount();
		if (lastInteractionNpcId > 0 && (currentTick - lastInteractionTick) <= INTERACTION_TIMEOUT_TICKS
			&& watchedNpcIds.contains(lastInteractionNpcId))
		{
			return lastInteractionNpcId;
		}
		Player me = client.getLocalPlayer();
		Actor target = me != null ? me.getInteracting() : null;
		return target instanceof NPC ? watchedId((NPC) target) : -1;
	}

	/**
	 * The watched id this NPC answers to, or -1. getId() is the base NPC; one that changes
	 * with quest progress (Helvia) shows a transformed id, which is what the task lists.
	 */
	private int watchedId(NPC npc)
	{
		if (watchedNpcIds.contains(npc.getId()))
		{
			return npc.getId();
		}
		NPCComposition shown = npc.getTransformedComposition();
		return shown != null && watchedNpcIds.contains(shown.getId()) ? shown.getId() : -1;
	}

	/** The name of the NPC speaking in the dialogue box (lower case), or null. */
	private String dialogueSpeaker()
	{
		Widget name = client.getWidget(InterfaceID.ChatLeft.NAME);
		if (name == null || name.isHidden() || name.getText() == null)
		{
			return null;
		}
		String text = Text.removeTags(name.getText()).trim().toLowerCase();
		return text.isEmpty() ? null : text;
	}

	/**
	 * Whether the player is interacting with a living NPC that none of our tasks target.
	 * A dead one doesn't count: Morgan Le Faye starts talking the moment Sir Mordred
	 * dies, while the player is still locked onto his corpse.
	 */
	private boolean interactingWithOtherNpc()
	{
		Player me = client.getLocalPlayer();
		Actor target = me != null ? me.getInteracting() : null;
		return target instanceof NPC && !target.isDead() && watchedId((NPC) target) < 0;
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
			int npcId = watchedId((NPC) event.getTarget());

			if (npcId > 0)
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
		Widget npcDialogue = client.getWidget(InterfaceID.ChatLeft.TEXT);
		if (npcDialogue != null && !npcDialogue.isHidden())
		{
			return true;
		}

		// Player dialogue options
		Widget playerDialogue = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
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

			sendTaskSuccess(task, null);

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up
			taskTargetNpcs.remove(task.getTaskId());
			taskTargetNames.remove(task.getTaskId());
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
}
