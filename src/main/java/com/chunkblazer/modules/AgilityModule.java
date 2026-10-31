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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredObject;
import com.google.common.collect.ImmutableSet;

/**
 * Module for handling AGILITY completion type tasks.
 *
 * Two task shapes:
 *   Object-gated tasks — JSON has required_object pointing at the obstacle (e.g.
 *                 Ardougne → Gap #4, id 15612; a Shilo stepping stone, id 16466).
 *                 The player clicks THAT specific object, then we credit once their
 *                 USE of it is confirmed by a player animation OR an Agility XP gain
 *                 within {@link #TRAVERSAL_TIMEOUT_TICKS}. Confirming on use (not the
 *                 raw click) means obstacles that award NO XP still credit, while a
 *                 misclick the player walks away from — or a shortcut they lack the
 *                 level for — does not (neither animates nor grants XP). Matching is
 *                 by the task's own object id, so only the intended task credits.
 *   Objectless shortcut tasks — no required_object, e.g. some "Use the … Shortcut"
 *                 tasks. Legacy fallback: credit on any small Agility XP gain. Known
 *                 cross-credit limitation when several are active at once; the fix is
 *                 to give each a required_object id so it uses the path above.
 */
@Slf4j
@Singleton
public class AgilityModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "AGILITY";
	// Object-gated tasks (have required_object) credit on the watched-object CLICK
	// once the player's USE of it is confirmed by an animation or Agility XP (see
	// onMenuOptionClicked / onAnimationChanged / confirmPendingTraversal).
	//
	// Legacy objectless shortcut tasks (no required_object) still credit on any
	// small Agility XP gain. This threshold catches every legitimate shortcut XP
	// gain (the smallest are ~5). The end goal is to give every agility task a
	// required_object id so nothing relies on this loose XP path.
	private static final int SHORTCUT_XP_THRESHOLD = 5;

	// After clicking a watched obstacle we wait this many ticks for proof the
	// player ACTUALLY used it (an animation or an Agility XP gain), counted from
	// when they stop walking to it. If nothing confirms in time the click is dropped — so a
	// misclick they walk away from, or a shortcut they lack the level for (neither
	// produces an animation/XP), never credits.
	private static final int TRAVERSAL_TIMEOUT_TICKS = 12;

	// Once an obstacle is credited it can't credit again for this long. Spam-clicking
	// an obstacle mid-use re-arms the click, and each frame of a multi-part jump then
	// confirmed another credit (Draynor crate: 5 credits for one lap, Dors 2026-10-04).
	// One use is a few ticks; using the same obstacle again for real (next lap, or a
	// shortcut and back) takes longer than this.
	private static final int REUSE_COOLDOWN_TICKS = 10;

	// Interfaces whose buttons never move the player or play an animation (see panelClick):
	// the side panel tab buttons on each window layout, the minimap orbs, and the side
	// panels that are only lists or toggles.
	private static final Set<Integer> PANEL_INTERFACES = ImmutableSet.of(
		InterfaceID.TOPLEVEL, InterfaceID.TOPLEVEL_OSRS_STRETCH, InterfaceID.TOPLEVEL_PRE_EOC,
		InterfaceID.ORBS, InterfaceID.ORBS_NOMAP, InterfaceID.PRAYERBOOK, InterfaceID.QUICKPRAYER,
		InterfaceID.CHATBOX, InterfaceID.SETTINGS_SIDE, InterfaceID.STATS, InterfaceID.QUESTLIST,
		InterfaceID.SIDE_JOURNAL, InterfaceID.FRIENDS, InterfaceID.SIDE_CHANNELS,
		InterfaceID.SIDE_CHANNELS_LARGE, InterfaceID.ACCOUNT, InterfaceID.MUSIC, InterfaceID.XP_DROPS);
	private static final Set<String> GEAR_OPTIONS = ImmutableSet.of("Wield", "Wear", "Equip", "Remove", "Examine");
	private static final Set<MenuAction> EXAMINE_ACTIONS = EnumSet.of(MenuAction.EXAMINE_OBJECT,
		MenuAction.EXAMINE_NPC, MenuAction.EXAMINE_ITEM_GROUND, MenuAction.EXAMINE_WORLD_ENTITY);

	// Track Agility XP for detecting gains
	private int previousAgilityXp = -1;

	// Per-task lap-end GameObject IDs (parsed from required_object in JSON).
	private final Map<String, Set<Integer>> taskRequiredObjectIds = new ConcurrentHashMap<>();
	// Union of all lap-end object IDs across active lap tasks.
	private final Set<Integer> watchedObjectIds = ConcurrentHashMap.newKeySet();

	// A watched obstacle the player clicked and that is awaiting use-confirmation
	// (animation or Agility XP). -1 when nothing is pending. Cleared if the player
	// clicks away (cancels), or on timeout.
	private int pendingObjectId = -1;
	private int pendingTick = -1;
	private WorldPoint lastPosition;
	private boolean animatedThisTick;
	private int hitTick = -1;

	// Obstacle id -> tick it was last credited (see REUSE_COOLDOWN_TICKS).
	private final Map<Integer, Integer> lastCreditTick = new ConcurrentHashMap<>();

	// Course tasks (lap_message set) count a lap from the game's "Your <course> lap count
	// is: N." message, which arrives exactly once per finished lap. Until a course's
	// message has been seen this session, its lap-end click still counts as a fallback,
	// and the click and the message for the same lap only count once.
	private static final Pattern LAP_COUNT = Pattern.compile("^Your (.+) lap count is: [0-9,]+\\.?$");
	private static final int LAP_DEDUPE_TICKS = 10;
	private final Map<String, Integer> lapCreditTick = new ConcurrentHashMap<>();
	private final Set<String> lapMessageSeen = ConcurrentHashMap.newKeySet();


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
	public void startUp()
	{
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		previousAgilityXp = -1;
		taskRequiredObjectIds.clear();
		watchedObjectIds.clear();
		pendingObjectId = -1;
		pendingTick = -1;
		lastCreditTick.clear();
		lapCreditTick.clear();
		lapMessageSeen.clear();
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);


			// Capture lap-end object IDs for lap tasks.
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
			log.error("AgilityModule failed to add a task", e);
		}
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		previousAgilityXp = -1;
		taskRequiredObjectIds.clear();
		watchedObjectIds.clear();
		pendingObjectId = -1;
		pendingTick = -1;
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
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (animatedThisTick && hitTick != client.getTickCount())
		{
			confirmPendingTraversal("animation");
		}
		animatedThisTick = false;

		// A log balance plays no animation, only a balancing walk, and some give no XP
		// either (Tlati Rainforest). That walk is the proof.
		Player me = client.getLocalPlayer();
		if (pendingObjectId > 0 && me != null && balancing(me))
		{
			confirmPendingTraversal("pose");
		}

		// Still walking to the clicked obstacle: the wait starts once they get there, so a
		// shortcut clicked from across the screen with no run energy still counts.
		WorldPoint at = me != null ? me.getWorldLocation() : null;
		if (pendingObjectId > 0 && at != null && !at.equals(lastPosition))
		{
			pendingTick = client.getTickCount();
		}
		lastPosition = at;

		// Expire a pending click that never got confirmed (e.g. the player lacked
		// the level — no animation/XP ever fires). Prevents a much-later unrelated
		// animation from crediting it.
		if (pendingObjectId > 0 && (client.getTickCount() - pendingTick) > TRAVERSAL_TIMEOUT_TICKS)
		{
			pendingObjectId = -1;
			pendingTick = -1;
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
		boolean objectClick = GAME_OBJECT_ACTIONS.contains(action);
		// The repaired Mausoleum bridge is clicked by its multiloc id, but the task lists the
		// repaired look. Matching the look also keeps the broken or Build forms from counting.
		int objectId = objectClick && !watchedObjectIds.contains(event.getId()) ? shownId(event.getId()) : event.getId();

		// Cancel a pending traversal if the player clicks away before using the
		// obstacle — a walk somewhere, an attack, or a different object. (Auto-walk
		// to the clicked obstacle does NOT fire its own click.) Working the interface on
		// the way there, like toggling run or switching tabs, doesn't change their mind.
		boolean reSelectingPending = objectClick && objectId == pendingObjectId;
		if (pendingObjectId > 0 && !reSelectingPending && !panelClick(event))
		{
			pendingObjectId = -1;
			pendingTick = -1;
		}

		if (!objectClick || !watchedObjectIds.contains(objectId))
		{
			return;
		}

		// Don't credit yet — record the click as INTENT and wait for proof the
		// player actually used the obstacle (an animation or an Agility XP gain;
		// see onAnimationChanged / onStatChanged). This avoids crediting a misclick
		// the player walks away from, or a shortcut they lack the level for —
		// neither produces an animation or XP.
		pendingObjectId = objectId;
		pendingTick = client.getTickCount();
	}
	/**
	 * The player started an animation — proof they're actually performing the
	 * obstacle they just clicked. Works for obstacles that award NO Agility XP
	 * (stepping stones, pipes), which an XP gate can't catch. Confirms the pending
	 * traversal at the end of the tick; a misclick the player walks away from, or one they lack the level
	 * for, never animates, so it never credits.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (pendingObjectId <= 0)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null || event.getActor() != local)
		{
			return;
		}
		if (local.getAnimation() == -1)
		{
			return; // animation reset to idle, not the start of an obstacle anim
		}
		// A swing at something we're fighting isn't the obstacle. Judged at the end of the
		// tick, once any hit on us is known: the flinch from a chicken's peck isn't a fence
		// jump either (Niddy).
		animatedThisTick |= !(local.getInteracting() instanceof NPC);
	}

	/**
	 * A click that only works the interface: a side panel tab, the run or prayer orb, a
	 * prayer, putting gear on or taking it off, an examine, or "Cancel" on a right-click
	 * menu. None of these moves the player or plays an animation, so the obstacle clicked
	 * before it still counts. That matters most where the animation is the only proof, like a
	 * grapple, which gives no XP (Eirund, Catherby cliff). Eating, drinking, teleports,
	 * spells, emotes and the special attack orb all animate, so they still cancel it.
	 */
	private static boolean panelClick(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		if (action == MenuAction.CANCEL || EXAMINE_ACTIONS.contains(action))
		{
			return true;
		}
		if (action != MenuAction.CC_OP && action != MenuAction.CC_OP_LOW_PRIORITY)
		{
			return false;
		}
		int widget = event.getParam1();
		if (widget == InterfaceID.Orbs.SPECBUTTON || widget == InterfaceID.OrbsNomap.SPECBUTTON)
		{
			return false; // some specs fire straight from the orb (dragon battleaxe)
		}
		return PANEL_INTERFACES.contains(WidgetUtil.componentToInterface(widget))
			|| GEAR_OPTIONS.contains(Text.removeTags(event.getMenuOption()));
	}

	/** Moving in a pose that isn't one of the player's own: balancing on a log or a rope. */
	private static boolean balancing(Player p)
	{
		int pose = p.getPoseAnimation();
		return pose != -1 && pose != p.getIdlePoseAnimation() && pose != p.getWalkAnimation()
			&& pose != p.getRunAnimation() && pose != p.getWalkRotate180() && pose != p.getWalkRotateLeft()
			&& pose != p.getWalkRotateRight() && pose != p.getIdleRotateLeft() && pose != p.getIdleRotateRight();
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			hitTick = client.getTickCount();
		}
	}

	/**
	 * Credit the task(s) watching the pending obstacle, if the use-confirmation
	 * arrived in time. Cleared afterwards so one traversal credits once.
	 */
	private void confirmPendingTraversal(String trigger)
	{
		if (pendingObjectId <= 0)
		{
			return;
		}
		int obj = pendingObjectId;
		if (client.getTickCount() - pendingTick > TRAVERSAL_TIMEOUT_TICKS)
		{
			// Stale — the confirmation came too late to be from this click.
			pendingObjectId = -1;
			pendingTick = -1;
			return;
		}
		pendingObjectId = -1;
		pendingTick = -1;

		int now = client.getTickCount();
		Integer last = lastCreditTick.get(obj);
		// now < last: the tick counter restarted (relog), so the old credit doesn't count.
		if (last != null && now >= last && now - last < REUSE_COOLDOWN_TICKS)
		{
			return; // the same use, confirmed again by a spam click
		}
		lastCreditTick.put(obj, now);

		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Set<Integer> taskObjects = taskRequiredObjectIds.get(task.getTaskId());
			if (taskObjects == null || !taskObjects.contains(obj) || !hasLevelFor(task))
			{
				continue;
			}
			if (task.getLapMessage() == null)
			{
				creditTaskProgress(task, 1);
			}
			else if (!lapMessageSeen.contains(task.getLapMessage().toLowerCase()))
			{
				creditLap(task); // fallback until this course's lap message shows up
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE || activeTasks.isEmpty())
		{
			return;
		}
		// "You need an Agility level of 62 to use this course.": the game refused the
		// obstacle just clicked, so whatever animates or gives XP next isn't it.
		if (Text.removeTags(event.getMessage()).toLowerCase().contains("agility level of"))
		{
			pendingObjectId = -1;
			pendingTick = -1;
			return;
		}
		Matcher m = LAP_COUNT.matcher(Text.removeTags(event.getMessage()));
		if (!m.matches())
		{
			return;
		}
		String course = m.group(1);
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			if (course.equalsIgnoreCase(task.getLapMessage()))
			{
				lapMessageSeen.add(course.toLowerCase());
				creditLap(task);
			}
		}
	}

	/**
	 * The task's Agility level, boosts included (the game accepts boosts for courses
	 * and shortcuts). Stops an under-levelled click on a course entrance or shortcut
	 * crediting off the animation or XP of whatever the player did just before.
	 */
	private boolean hasLevelFor(NuzlockeTask task)
	{
		return client.getBoostedSkillLevel(Skill.AGILITY) >= task.getLevelRequirement();
	}

	/** One credit per lap, however many signals (click, lap message) report it. */
	private void creditLap(NuzlockeTask task)
	{
		int now = client.getTickCount();
		Integer last = lapCreditTick.get(task.getTaskId());
		if (last != null && now >= last && now - last < LAP_DEDUPE_TICKS)
		{
			return;
		}
		lapCreditTick.put(task.getTaskId(), now);
		creditTaskProgress(task, 1);
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
		if (xpGained <= 0)
		{
			return;
		}

		// An Agility XP gain also confirms a pending object-gated traversal (e.g.
		// rooftop lap-end). Most obstacles that award XP also animate, but this is a
		// cheap belt-and-suspenders for any that don't.
		confirmPendingTraversal("Agility XP +" + xpGained);

		// Legacy path for shortcut tasks that have NO required_object: they still
		// credit on any small Agility XP gain. (Going forward, give every agility
		// task a required_object id so it uses the precise click+confirm path.)
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Set<Integer> taskObjects = taskRequiredObjectIds.get(task.getTaskId());
			boolean isObjectGated = taskObjects != null && !taskObjects.isEmpty();
			if (isObjectGated)
			{
				continue; // credited via the watched-object click + confirmation
			}
			if (xpGained >= SHORTCUT_XP_THRESHOLD && hasLevelFor(task))
			{
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

			// Clean up — drop the per-task object IDs and rebuild the watched union so
			// other tasks' lap-end objects keep firing but this task's no longer do.
			activeTasks.remove(task);
			taskRequiredObjectIds.remove(task.getTaskId());
			rebuildWatchedObjects();
		}
	}

	private void rebuildWatchedObjects()
	{
		watchedObjectIds.clear();
		for (Set<Integer> ids : taskRequiredObjectIds.values())
		{
			watchedObjectIds.addAll(ids);
		}
	}
}
