package net.runelite.client.plugins.chunkblazer.modules;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredObject;
import net.runelite.client.plugins.chunkblazer.TargetNpc;

/**
 * Module for handling THIEVING completion type tasks.
 *
 * Two detection paths run in parallel:
 *   1. NPC pickpockets — task has target_npc; we track InteractingChanged with watched NPC IDs.
 *   2. GameObject thefts (stalls, chests) — task has required_object; we track MenuOptionClicked
 *      on watched object IDs.
 * Either path gates a credit on a Thieving XP gain inside INTERACTION_TIMEOUT_TICKS of the
 * recorded interaction. Tasks with neither requirement no longer auto-credit — the old
 * "credit any thieving XP to no-NPC tasks" fallback caused one stall theft to complete every
 * unrelated stall task simultaneously.
 */
@Slf4j
@Singleton
public class ThievingModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "THIEVING";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	// Minimum XP gain to count as successful pickpocket
	private static final int MIN_XP_THRESHOLD = 1;

	// How many ticks after interacting with an NPC we consider XP gains as pickpockets
	private static final int INTERACTION_TIMEOUT_TICKS = 5;

	// Menu actions that count as an actual interaction with a GameObject (not just hovering/examine).
	private static final Set<MenuAction> GAME_OBJECT_ACTIONS = EnumSet.of(
		MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION,
		MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION
	);

	@Inject
	private ChatMessageManager chatMessageManager;

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

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100;

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
			log.error("ThievingModule.addActiveTask() EXCEPTION: ", e);
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
		taskRequiredObjectIds.clear();
		watchedNpcIds.clear();
		watchedObjectIds.clear();
		previousThievingXp = -1;
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
	public void onGameTick(GameTick event)
	{
		tickCounter++;

		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
		}
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

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (activeTasks.isEmpty() || watchedObjectIds.isEmpty())
		{
			return;
		}

		if (!GAME_OBJECT_ACTIONS.contains(event.getMenuAction()))
		{
			return;
		}

		int objectId = event.getId();
		if (watchedObjectIds.contains(objectId))
		{
			lastInteractionObjectId = objectId;
			lastInteractionObjectTick = client.getTickCount();
		}
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
				(currentTick - lastInteractionTick) <= INTERACTION_TIMEOUT_TICKS &&
				watchedNpcIds.contains(lastInteractionNpcId);
			boolean recentObject = lastInteractionObjectId > 0 &&
				(currentTick - lastInteractionObjectTick) <= INTERACTION_TIMEOUT_TICKS &&
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
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					Set<Integer> taskNpcs = taskTargetNpcs.get(task.getTaskId());
					if (taskNpcs != null && taskNpcs.contains(lastInteractionNpcId))
					{
						creditTaskProgress(task, 1);
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

		sendTaskProgress(task, "Pickpocket successful", newProgress, required);

		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		// Check for completion
		if (newProgress >= required && !task.isCompleted())
		{
			task.setCompleted(true);

			sendTaskSuccess(task, "Thieving task complete!");

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

	private void sendTaskProgress(NuzlockeTask task, String details, int current, int total)
	{
		if (!config.showChatProgress())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_GREEN + ">Task Progress:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col> " +
			"(" + current + "/" + total + ")";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

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
