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
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredObject;

/**
 * Module for handling AGILITY completion type tasks.
 *
 * Two task shapes:
 *   Lap tasks  — JSON has required_object pointing at the course's lap-end obstacle
 *                 (e.g. Ardougne → Gap #4, id 15612). Credit only when the player
 *                 clicks THAT specific object and an Agility XP gain ≥ LAP_XP_THRESHOLD
 *                 fires shortly after. This prevents a single high-XP obstacle from
 *                 crediting every active lap task at once.
 *   Shortcut tasks — no required_object, e.g. "Use the Level 21 Underwall Tunnel".
 *                 Credit on any small Agility XP gain. Known cross-credit limitation
 *                 when multiple shortcut tasks are active simultaneously; out of scope
 *                 for this pass.
 */
@Slf4j
@Singleton
public class AgilityModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "AGILITY";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	// Per-task XP threshold for a credit. AGILITY tasks come in two shapes:
	//
	//   Lap tasks (have required_object in JSON, e.g. "Complete some Laps of
	//   Draynor Rooftop"): every obstacle awards 5–22 XP, then the lap-end
	//   bonus awards 39+ XP as a separate StatChanged event. We want to count
	//   only the lap-end bonus, so the threshold has to sit above any single
	//   obstacle.
	//
	//   Shortcut tasks (no required_object, e.g. "Use the Level 21 Underwall
	//   Tunnel"): one tiny XP gain per use, want to credit on it. Threshold
	//   has to be small.
	//
	// 30 sits between the largest single-obstacle XP (~22 XP on rooftop
	// courses) and the smallest lap-end bonus (39 XP, Gnome Stronghold).
	// 5 catches every legitimate shortcut XP gain.
	//
	// Caveat: a few non-rooftop lap courses have a single obstacle whose XP
	// straddles 30 (e.g. Wilderness Agility's Pile of Rocks at 62.5 XP) and
	// will double-count laps until AgilityModule moves to per-task
	// required_object id tracking. Acceptable for now — Mike's report was
	// Draynor.
	// Lap tasks credit on any positive Agility XP gain that fires within
	// INTERACTION_TIMEOUT_TICKS of clicking the watched object — supports
	// "hop on the course = 1 lap" semantics where the first obstacle's XP
	// may be as low as 5 (Draynor). The discriminator is the object click,
	// not the XP magnitude. Consume-after-credit prevents double-counting
	// when multiple XP events fire from one click.
	private static final int LAP_XP_THRESHOLD = 1;
	private static final int SHORTCUT_XP_THRESHOLD = 5;

	// How many ticks after clicking the lap-end obstacle the XP event is still
	// considered "from that click". Matches ThievingModule's window.
	private static final int INTERACTION_TIMEOUT_TICKS = 5;

	// GameObject menu actions — same set used in ThievingModule. Anything else
	// (examine, walk, cancel) is ignored.
	private static final Set<MenuAction> GAME_OBJECT_ACTIONS = EnumSet.of(
		MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION,
		MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION
	);

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track Agility XP for detecting gains
	private int previousAgilityXp = -1;

	// Per-task lap-end GameObject IDs (parsed from required_object in JSON).
	private final Map<String, Set<Integer>> taskRequiredObjectIds = new ConcurrentHashMap<>();
	// Union of all lap-end object IDs across active lap tasks.
	private final Set<Integer> watchedObjectIds = ConcurrentHashMap.newKeySet();

	// Most recent watched-object interaction.
	private int lastInteractionObjectId = -1;
	private int lastInteractionObjectTick = -1;

	// Debug heartbeat
	private int tickCounter = 0;
	private static final int DEBUG_LOG_INTERVAL = 100;

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
	public boolean canHandle(NuzlockeTask task)
	{
		String type = task.getCompletionType();
		return type != null && type.equalsIgnoreCase(COMPLETION_TYPE);
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
		log.info("=== AgilityModule STARTED ===");
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		previousAgilityXp = -1;
		taskRequiredObjectIds.clear();
		watchedObjectIds.clear();
		lastInteractionObjectId = -1;
		lastInteractionObjectTick = -1;
		log.info("AgilityModule stopped");
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);

			log.info("=== AgilityModule: ADDING ACTIVE TASK ===");
			log.info("  Task Name: {}", task.getName());
			log.info("  Task ID: {}", task.getTaskId());
			log.info("  Target Quantity: {}", task.getTargetQuantity());

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
							log.info("      >>> WATCHING LAP-END OBJECT ID: {}", id);
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
			log.error("AgilityModule.addActiveTask() EXCEPTION: ", e);
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
		previousAgilityXp = -1;
		taskRequiredObjectIds.clear();
		watchedObjectIds.clear();
		lastInteractionObjectId = -1;
		lastInteractionObjectTick = -1;
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
			log.info("AgilityModule: Initialized XP tracking at {} xp", previousAgilityXp);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;

		if (tickCounter % DEBUG_LOG_INTERVAL == 0)
		{
			log.info(">>> AgilityModule HEARTBEAT - tick {} - activeTasks: {}",
				tickCounter, activeTasks.size());
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
			log.info(">>> AgilityModule: Player acted on watched lap-end object (ID: {}, option: {})",
				objectId, event.getMenuOption());
		}
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

		int currentTick = client.getTickCount();
		boolean recentObjectInteraction = lastInteractionObjectId > 0
			&& (currentTick - lastInteractionObjectTick) <= INTERACTION_TIMEOUT_TICKS;

		// Two paths:
		//   Lap task  -> require recent click on THIS task's lap-end object AND XP ≥ threshold.
		//   Shortcut  -> any XP ≥ threshold credits (existing loose behaviour, cross-credit risk).
		boolean creditedLapThisEvent = false;
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Set<Integer> taskObjects = taskRequiredObjectIds.get(task.getTaskId());
			boolean isLapTask = taskObjects != null && !taskObjects.isEmpty();

			if (isLapTask)
			{
				if (!recentObjectInteraction || !taskObjects.contains(lastInteractionObjectId))
				{
					continue;
				}
				if (xpGained < LAP_XP_THRESHOLD)
				{
					// Sanity check — ignore zero/negative XP deltas. The actual
					// double-credit protection is the consume-after-credit below.
					continue;
				}
				log.info(">>> AgilityModule: '{}' credited (lap-end object {} clicked, gained {} XP)",
					task.getName(), lastInteractionObjectId, xpGained);
				creditTaskProgress(task, 1);
				creditedLapThisEvent = true;
			}
			else
			{
				if (xpGained >= SHORTCUT_XP_THRESHOLD)
				{
					log.info(">>> AgilityModule: '{}' credited (shortcut, gained {} XP, threshold {})",
						task.getName(), xpGained, SHORTCUT_XP_THRESHOLD);
					creditTaskProgress(task, 1);
				}
			}
		}

		// Consume the click after a successful lap credit. Some courses (Ardougne) fire
		// both the final obstacle XP and the dismount-bonus XP within the same window,
		// and both would credit otherwise — double-counting one lap on multi-lap tasks.
		// Clear the ID (the gate uses `lastInteractionObjectId > 0`) so the next XP
		// event in this window can't satisfy the recent-interaction check.
		if (creditedLapThisEvent)
		{
			lastInteractionObjectId = -1;
			lastInteractionObjectTick = -1;
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

		sendTaskProgress(task, "Obstacle completed", newProgress, required);

		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		// Check for completion
		if (newProgress >= required && !task.isCompleted())
		{
			log.info("AgilityModule: Task '{}' COMPLETED! ({}/{})",
				task.getName(), newProgress, required);
			task.setCompleted(true);

			sendTaskSuccess(task, "Course completed!");

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

		log.info("[CHAT] Agility progress: {} ({}/{}) - {}", task.getName(), current, total, details);
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

		log.info("[CHAT] Agility success: {} - {}", task.getName(), details);
	}
}
