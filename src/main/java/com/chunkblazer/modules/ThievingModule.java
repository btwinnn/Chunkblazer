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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredObject;
import com.chunkblazer.TargetNpc;

/**
 * Module for THIEVING completion type tasks. Two paths, each gating credit on a Thieving
 * XP gain inside CLICK_TIMEOUT_TICKS of the recorded interaction:
 *   1. NPC pickpockets (target_npc): a "Pickpocket" click on a watched NPC. Any other
 *      interaction cancels it, so a stall's XP can't pass as a pickpocket.
 *   2. GameObject thefts (required_object): MenuOptionClicked on watched object IDs.
 * Tasks with neither never auto-credit, or one stall theft would complete every stall
 * task.
 */
@Slf4j
@Singleton
public class ThievingModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "THIEVING";
	// Minimum XP gain to count as successful pickpocket
	private static final int MIN_XP_THRESHOLD = 1;

	// How long a click waits for its Thieving XP. A chest is walked to and searched for traps
	// first (Chaos Druid Tower), a guard who wanders off is chased (Hosidius). Any other
	// click still disarms it.
	private static final int CLICK_TIMEOUT_TICKS = 20;
	private static final Set<MenuAction> NPC_ACTIONS = EnumSet.of(
		MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION, MenuAction.NPC_THIRD_OPTION,
		MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION);

	// Per-task NPC IDs (pickpocket tasks).
	private final Map<String, Set<Integer>> taskTargetNpcs = new ConcurrentHashMap<>();

	// Per-task GameObject IDs (stall/chest tasks).
	private final Map<String, Set<Integer>> taskRequiredObjectIds = new ConcurrentHashMap<>();

	// Union of watched NPCs and objects across active tasks.
	private final Set<Integer> watchedNpcIds = ConcurrentHashMap.newKeySet();
	private final Set<Integer> watchedObjectIds = ConcurrentHashMap.newKeySet();

	private int previousThievingXp = -1;

	// Recent interactions. Whichever fires more recently wins on the next XP gain.
	private int lastInteractionNpcId = -1;
	private int lastInteractionTick = -1;
	private int lastInteractionObjectId = -1;
	private int lastInteractionObjectTick = -1;


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
	public void startUp()
	{
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		taskTargetNpcs.clear();
		taskRequiredObjectIds.clear();
		watchedNpcIds.clear();
		watchedObjectIds.clear();
		previousThievingXp = -1;
		lastInteractionNpcId = -1;
		lastInteractionTick = -1;
		lastInteractionObjectId = -1;
		lastInteractionObjectTick = -1;
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

			// Parse required GameObjects (stalls, chests).
			Set<Integer> requiredObjectIds = new HashSet<>();
			List<RequiredObject> requiredObjects = task.getRequiredObjects();
			if (requiredObjects != null)
			{
				for (RequiredObject ro : requiredObjects)
				{
					List<Integer> ids = ro.getObjectIds();
					if (ids != null)
					{
						for (Integer id : ids)
						{
							requiredObjectIds.add(id);
							watchedObjectIds.add(id);
						}
					}
				}
			}

			taskRequiredObjectIds.put(task.getTaskId(), requiredObjectIds);

			// Initialize XP tracking on client thread
			clientThread.invokeLater(this::initializeXpTracking);
		}
		catch (Exception e)
		{
			log.error("ThievingModule failed to add a task", e);
		}
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		taskTargetNpcs.clear();
		taskRequiredObjectIds.clear();
		watchedNpcIds.clear();
		watchedObjectIds.clear();
		previousThievingXp = -1;
		drops.clear();
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
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}
		MenuAction action = event.getMenuAction();
		boolean npcAction = NPC_ACTIONS.contains(action);
		boolean objectAction = GAME_OBJECT_ACTIONS.contains(action);

		// Only a Pickpocket click arms an NPC task (Dig Site workmen say "Steal-from").
		// Anything else done to the world replaces it: the XP that follows belongs to
		// that, not to the pickpocket.
		String option = event.getMenuOption();
		if (npcAction && ("Pickpocket".equalsIgnoreCase(option) || "Steal-from".equalsIgnoreCase(option)))
		{
			NPC npc = event.getMenuEntry() != null ? event.getMenuEntry().getNpc() : null;
			int npcId = npc != null ? npc.getId() : -1;
			lastInteractionNpcId = watchedNpcIds.contains(npcId) ? npcId : -1;
			lastInteractionTick = client.getTickCount();
			lastInteractionObjectId = -1;
			drops.clear();
			boxKnown = client.getItemContainer(InventoryID.SEED_BOX) != null;
			for (NuzlockeTask task : activeTasks)
			{
				if (needsDrop(task))
				{
					drops.put(task, new int[]{held(task), 0});
				}
			}
			return;
		}
		if (npcAction || objectAction || action == MenuAction.WALK)
		{
			lastInteractionNpcId = -1;
		}
		if (!objectAction)
		{
			return;
		}

		// An unrelated stall replaces a pending task stall too.
		int objectId = event.getId();
		lastInteractionObjectId = watchedObjectIds.contains(objectId) ? objectId : -1;
		lastInteractionObjectTick = client.getTickCount();
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

			int currentTick = client.getTickCount();
			boolean recentNpc = lastInteractionNpcId > 0 &&
				(currentTick - lastInteractionTick) <= CLICK_TIMEOUT_TICKS &&
				watchedNpcIds.contains(lastInteractionNpcId);
			boolean recentObject = lastInteractionObjectId > 0 &&
				(currentTick - lastInteractionObjectTick) <= CLICK_TIMEOUT_TICKS &&
				watchedObjectIds.contains(lastInteractionObjectId);

			// If both fired, the more recent one wins — protects against e.g. clicking a stall
			// while still interacting-flagged on a nearby NPC.
			if (recentNpc && recentObject)
			{
				if (lastInteractionObjectTick >= lastInteractionTick)
				{
					recentNpc = false;
				}
				else
				{
					recentObject = false;
				}
			}

			if (recentNpc)
			{
				// A distracted wealthy citizen is pickpocketed over and over from one
				// click, so each steal keeps the click alive for the next.
				lastInteractionTick = currentTick;
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					Set<Integer> taskNpcs = taskTargetNpcs.get(task.getTaskId());
					if (taskNpcs != null && taskNpcs.contains(lastInteractionNpcId))
					{
						int[] drop = drops.get(task);
						if (drop != null)
						{
							drop[1] = currentTick + 3;
						}
						else if (!needsDrop(task))
						{
							creditTaskProgress(task, 1);
						}
					}
				}
			}
			else if (recentObject)
			{
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					Set<Integer> taskObjects = taskRequiredObjectIds.get(task.getTaskId());
					if (taskObjects != null && taskObjects.contains(lastInteractionObjectId))
					{
						creditTaskProgress(task, 1);
					}
				}
			}
			// No matching recent interaction: do nothing. Every THIEVING task in the JSON
			// declares either target_npc or required_object; falling through and crediting
			// "no-requirement" tasks caused unrelated stall tasks to complete in lockstep.
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

		sendTaskProgress(task, null, newProgress, required);

		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		// Check for completion
		if (newProgress >= required && !task.isCompleted())
		{
			task.setCompleted(true);

			sendTaskSuccess(task, null);

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up
			taskTargetNpcs.remove(task.getTaskId());
			taskRequiredObjectIds.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatched();
		}
	}

	// Pickpocket tasks that need a specific item (a Master Farmer's ranarr seed) credit only
	// once it lands, in the pack or an open seed box, a few ticks after the XP. Without
	// this, any Master Farmer pickpocket counted, whatever seed it gave.
	// task -> {item count at the click, tick the wait ends (0 = not waiting)}
	private final Map<NuzlockeTask, int[]> drops = new ConcurrentHashMap<>();
	// The seed box only counts if its contents were known at the click; its first update
	// carries the whole box, which would read as a fresh seed.
	private boolean boxKnown;

	private static boolean needsDrop(NuzlockeTask task)
	{
		return task.getConstraints() != null && task.getConstraints().hasDroppedItemConstraint();
	}

	private int held(NuzlockeTask task)
	{
		int n = 0;
		for (int c : new int[]{InventoryID.INV, boxKnown ? InventoryID.SEED_BOX : -1})
		{
			ItemContainer inv = client.getItemContainer(c);
			for (Item item : inv == null ? new Item[0] : inv.getItems())
			{
				if (task.getConstraints().getDroppedItemIds().contains(item.getId()))
				{
					n += item.getQuantity();
				}
			}
		}
		return n;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		for (Map.Entry<NuzlockeTask, int[]> e : drops.entrySet())
		{
			int[] drop = e.getValue();
			if (drop[1] == 0)
			{
				continue;
			}
			int now = held(e.getKey());
			if (now > drop[0])
			{
				drop[0] = now;
				drop[1] = 0;
				creditTaskProgress(e.getKey(), 1);
			}
			else if (client.getTickCount() > drop[1])
			{
				drop[1] = 0;
			}
		}
	}

	private void rebuildWatched()
	{
		watchedNpcIds.clear();
		for (Set<Integer> npcs : taskTargetNpcs.values())
		{
			watchedNpcIds.addAll(npcs);
		}
		watchedObjectIds.clear();
		for (Set<Integer> objects : taskRequiredObjectIds.values())
		{
			watchedObjectIds.addAll(objects);
		}
	}
}
