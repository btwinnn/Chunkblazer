package com.chunkblazer.modules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import com.chunkblazer.ChunkBlazerPlugin;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredObject;

/**
 * Module for CONSTRUCTION completion type tasks.
 *
 * <p>Every authored construction task (80 as of 2026-07) names the FINISHED
 * furniture via {@code required_finished_object} object_ids at quantity 1 —
 * "Build an Oak Larder", "Build a Medium STASH", "Build a Mounted Glory".
 * Detection is therefore a single rule: the watched finished-object SPAWNS
 * within {@link #MATCH_WINDOW_TICKS} of a Construction XP gain, in either
 * order. (The old module's item-consumption fallback served zero tasks and
 * was deleted with the rewrite.)
 *
 * <p>Lessons this rewrite encodes:
 * <ul>
 *   <li><b>Wall-mounted furniture is not a GameObject.</b> Mounted glory /
 *       mounted capes / portraits spawn as {@link DecorativeObjectSpawned}
 *       (some hotspots as {@link WallObjectSpawned}); rugs as
 *       {@link GroundObjectSpawned}. The old module only heard
 *       GameObjectSpawned, so mounted builds could never credit. All four
 *       spawn streams now funnel into one sensor.</li>
 *   <li><b>Either event order must credit.</b> The XP StatChanged and the
 *       object spawn land on the same tick, but their order within the tick
 *       is not guaranteed. Spawn-then-XP and XP-then-spawn both credit via
 *       symmetric recent-spawn / recent-XP memories.</li>
 *   <li><b>POHs are instanced regions.</b> The region gate (needed because
 *       STASH object_ids repeat across the overworld) compared the POH's
 *       instanced region id against the task's overworld chunk and blocked
 *       every indoor build. The gate is now skipped while
 *       {@code client.isInInstancedRegion()} — inside a POH the furniture
 *       object_ids are unique enough, and which portal was used cannot be
 *       determined anyway.</li>
 *   <li><b>Scene loads replay spawns.</b> Walking into an area (or toggling
 *       POH build mode) re-fires spawn events for furniture that already
 *       exists. Spawns within {@link #SCENE_LOAD_SUPPRESS_TICKS} of a
 *       {@link GameState#LOADING} transition are ignored so pre-existing
 *       furniture can't pair with an unrelated XP drop.</li>
 *   <li><b>STASH units never spawn when built.</b> A STASH is a varbit
 *       multiloc: the "inconspicuous bush" and the built STASH are the SAME
 *       scene object re-dressed by an impostor, so building one fires no
 *       spawn event at all (Mike's 2026-07-14 QA: build XP landed with
 *       "recent spawns: []" — the only STASH spawns in the session were
 *       scene-load replays). The build is detected instead by the player's
 *       "Build" MENU CLICK on the watched object id followed by Construction
 *       XP within {@link #BUILD_CLICK_WINDOW_TICKS} — the same
 *       MenuOptionClicked-then-XP pattern AgilityModule uses, and clicks
 *       carry the real runtime id even for varbit-morphed objects.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class ConstructionModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "CONSTRUCTION";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	// How far apart (in ticks) the watched-object spawn and the Construction
	// XP gain may land and still count as the same build.
	private static final int MATCH_WINDOW_TICKS = 5;

	// Spawns this close after a LOADING transition are scene replays of
	// furniture that already existed, not player builds.
	private static final int SCENE_LOAD_SUPPRESS_TICKS = 2;

	// Authoring aid: how many recent spawns the debug ring remembers, and how
	// far back the [CONSTRUCTION-DEBUG] id-capture dump looks on an XP gain.
	private static final int DEBUG_RING_CAPACITY = 32;

	// How long after a "Build" click on a watched object its Construction XP
	// still confirms the build. Covers walking to the object plus the build
	// animation — same allowance as AgilityModule's traversal timeout.
	private static final int BUILD_CLICK_WINDOW_TICKS = 12;

	// GameObject menu actions — same set as Agility/Thieving. Anything else
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

	// Provider (not direct injection) to break the plugin <-> module cycle —
	// ChunkBlazerPlugin instantiates this module via Guice, so we can't depend
	// on it eagerly. Lazy fetch via Provider.get() at use time.
	@Inject
	private Provider<ChunkBlazerPlugin> pluginProvider;

	// Per-task finished-furniture object IDs (the build confirmation).
	private final Map<String, Set<Integer>> taskRequiredObjectIds = new ConcurrentHashMap<>();

	// Union of all watched object IDs across active tasks (fast filter).
	private final Set<Integer> watchedObjectIds = ConcurrentHashMap.newKeySet();

	// Recent spawns of WATCHED objects: objectId -> tick. Entries age out of
	// MATCH_WINDOW_TICKS lazily. Deliberately NOT cleared in onTaskCleared():
	// routine task-list refreshes can land between the spawn and its XP (Mike,
	// session_2026-05-15), and the re-registered task must still credit.
	private final Map<Integer, Integer> recentWatchedSpawns = new ConcurrentHashMap<>();

	// Recent "Build" clicks on WATCHED objects: objectId -> tick. The STASH
	// path: varbit multilocs never spawn on build, so the interaction is the
	// object discriminator. Survives onTaskCleared for the same reason as
	// recentWatchedSpawns; ages out of BUILD_CLICK_WINDOW_TICKS lazily.
	private final Map<Integer, Integer> recentBuildClicks = new ConcurrentHashMap<>();

	// Tick of the most recent Construction XP gain (-1 = none yet). Lets a
	// watched spawn that arrives AFTER the XP event still credit.
	private int lastXpGainTick = -1;

	// Last observed Construction XP; -1 until the first sighting seeds it.
	private int previousConstructionXp = -1;

	// Tick of the last LOADING transition, for scene-replay suppression.
	private int lastSceneLoadTick = -1;

	// Authoring aid: ring of the latest object spawns (any id) while
	// construction tasks are active, dumped on an uncredited XP gain so wrong
	// authored object_ids can be corrected from the log.
	private final Deque<int[]> debugSpawnRing = new ArrayDeque<>();

	@Inject
	public ConstructionModule()
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
		taskRequiredObjectIds.clear();
		watchedObjectIds.clear();
		recentWatchedSpawns.clear();
		recentBuildClicks.clear();
		debugSpawnRing.clear();
		lastXpGainTick = -1;
		previousConstructionXp = -1;
		lastSceneLoadTick = -1;
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);

			Set<Integer> requiredObjectIds = new HashSet<>();
			List<RequiredObject> requiredObjects = task.getRequiredObjects();
			if (requiredObjects != null)
			{
				for (RequiredObject ro : requiredObjects)
				{
					List<Integer> ids = ro.getObjectIds();
					if (ids != null)
					{
						requiredObjectIds.addAll(ids);
					}
				}
			}
			if (requiredObjectIds.isEmpty())
			{
				// Every authored construction task names its finished object;
				// a task without one can never credit. Shout so the authoring
				// gap is visible instead of silently untrackable.
				log.warn("CONSTRUCTION task '{}' ({}) has no required_finished_object — cannot track it",
					task.getName(), task.getTaskId());
				return;
			}

			taskRequiredObjectIds.put(task.getTaskId(), requiredObjectIds);
			watchedObjectIds.addAll(requiredObjectIds);

			clientThread.invokeLater(() ->
			{
				if (previousConstructionXp < 0 && client.getLocalPlayer() != null)
				{
					// Seed from current XP so the FIRST build after a (re)load
					// produces a real delta instead of a baseline sighting.
					previousConstructionXp = client.getSkillExperience(Skill.CONSTRUCTION);
				}
			});
		}
		catch (Exception e)
		{
			log.error("ConstructionModule.addActiveTask() EXCEPTION: ", e);
		}
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		// For legacy single-task support
		super.onTaskAssigned(task);
		addActiveTask(task);
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		taskRequiredObjectIds.clear();
		watchedObjectIds.clear();
		// IMPORTANT: recentWatchedSpawns and lastXpGainTick survive on purpose —
		// see the field comment. They age out via MATCH_WINDOW_TICKS.
	}

	@Override
	public void checkProgress()
	{
		// Construction progress is event-based (a build happened); it cannot
		// be re-derived from current state. Present for interface parity.
	}

	// ── Spawn sensors ─────────────────────────────────────────────────────
	// All four furniture flavours funnel into recordSpawn: floor furniture and
	// STASH units are GameObjects, mounted amulets/capes/portraits are
	// DecorativeObjects, some wall hotspots are WallObjects, rugs are
	// GroundObjects.

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (event.getGameObject() != null)
		{
			recordSpawn(event.getGameObject());
		}
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		if (event.getDecorativeObject() != null)
		{
			recordSpawn(event.getDecorativeObject());
		}
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		if (event.getWallObject() != null)
		{
			recordSpawn(event.getWallObject());
		}
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		if (event.getGroundObject() != null)
		{
			recordSpawn(event.getGroundObject());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			lastSceneLoadTick = getGameTick();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}
		if (!GAME_OBJECT_ACTIONS.contains(event.getMenuAction()))
		{
			return;
		}
		String option = event.getMenuOption();
		if (option == null || !option.equalsIgnoreCase("Build"))
		{
			return;
		}
		int objectId = event.getId();

		// Authoring aid: surface the REAL runtime object id for every Build
		// click while a construction task is active — varbit multilocs (STASH
		// units) often have wiki ids that don't line up with what the client
		// reports, and the click id is the ground truth to author against.

		if (!watchedObjectIds.contains(objectId))
		{
			return;
		}
		recentBuildClicks.put(objectId, getGameTick());
	}

	private void recordSpawn(TileObject obj)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}
		int tick = getGameTick();

		// Authoring ring: remember the latest spawns regardless of watch state
		// so an uncredited XP gain can dump the real runtime ids.
		synchronized (debugSpawnRing)
		{
			debugSpawnRing.addLast(new int[]{tick, obj.getId()});
			while (debugSpawnRing.size() > DEBUG_RING_CAPACITY)
			{
				debugSpawnRing.removeFirst();
			}
		}

		if (!watchedObjectIds.contains(obj.getId()))
		{
			return;
		}
		// Scene replays (area entry, POH build-mode toggle) re-fire spawns for
		// furniture that already exists — those are not builds.
		if (lastSceneLoadTick >= 0 && tick - lastSceneLoadTick <= SCENE_LOAD_SUPPRESS_TICKS)
		{
			return;
		}

		recentWatchedSpawns.put(obj.getId(), tick);

		// XP-before-spawn order: the Construction XP for this build may have
		// already fired this tick (or a tick or two ago). Credit now.
		if (lastXpGainTick >= 0 && tick - lastXpGainTick <= MATCH_WINDOW_TICKS)
		{
			creditMatchingTasks();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.CONSTRUCTION)
		{
			return;
		}

		int newXp = event.getXp();
		int prevXp = previousConstructionXp;
		previousConstructionXp = newXp;

		// First sighting (login/initial sync) records the baseline only, and
		// level/boost recalcs without an XP gain don't count as building.
		if (prevXp < 0 || newXp <= prevXp)
		{
			return;
		}

		lastXpGainTick = getGameTick();

		if (activeTasks.isEmpty())
		{
			return;
		}


		boolean credited = creditMatchingTasks();
		if (!credited)
		{
			// Authoring aid: the player gained Construction XP but no watched
			// object spawned in the window. Dump the real runtime spawn ids so
			// a wrong authored object_id can be corrected from this log line.
			StringBuilder recent = new StringBuilder();
			synchronized (debugSpawnRing)
			{
				for (int[] entry : debugSpawnRing)
				{
					if (lastXpGainTick - entry[0] <= MATCH_WINDOW_TICKS)
					{
						if (recent.length() > 0)
						{
							recent.append(", ");
						}
						recent.append(entry[1]).append("@t").append(entry[0]);
					}
				}
			}
		}
	}

	/**
	 * Credit every active task whose finished object spawned — or, for varbit
	 * multilocs like STASH units that never spawn on build, whose watched
	 * object was Build-clicked — within its window, subject to the region
	 * gate. Returns whether anything credited.
	 */
	private boolean creditMatchingTasks()
	{
		int tick = getGameTick();
		// Age out stale memories first.
		recentWatchedSpawns.entrySet().removeIf(e -> tick - e.getValue() > MATCH_WINDOW_TICKS);
		recentBuildClicks.entrySet().removeIf(e -> tick - e.getValue() > BUILD_CLICK_WINDOW_TICKS);
		if (recentWatchedSpawns.isEmpty() && recentBuildClicks.isEmpty())
		{
			return false;
		}

		boolean credited = false;
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Set<Integer> taskObjects = taskRequiredObjectIds.get(task.getTaskId());
			if (taskObjects == null || taskObjects.isEmpty())
			{
				continue;
			}
			Integer spawnMatch = null;
			Integer clickMatch = null;
			for (Integer objectId : taskObjects)
			{
				if (spawnMatch == null && recentWatchedSpawns.containsKey(objectId))
				{
					spawnMatch = objectId;
				}
				if (clickMatch == null && recentBuildClicks.containsKey(objectId))
				{
					clickMatch = objectId;
				}
			}
			if (spawnMatch == null && clickMatch == null)
			{
				continue;
			}
			if (!passesRegionGate(task))
			{
				continue;
			}

			// Consume the evidence so a later unrelated XP drop in the window
			// can't credit the same build twice.
			if (spawnMatch != null)
			{
				recentWatchedSpawns.remove(spawnMatch);
			}
			if (clickMatch != null)
			{
				recentBuildClicks.remove(clickMatch);
			}
			int matchedObjectId = spawnMatch != null ? spawnMatch : clickMatch;
			applyCredit(task, "Built: " + task.getName());
			credited = true;
		}
		return credited;
	}

	/**
	 * Region gate: STASH-style object_ids repeat across the overworld ("Easy
	 * STASH Unit" exists at dozens of spots), so outdoors the player's region
	 * must match the task's chunk. POHs are INSTANCED regions whose ids never
	 * match any overworld chunk — the gate is skipped there, or every indoor
	 * build would be blocked (the old module's bug).
	 */
	private boolean passesRegionGate(NuzlockeTask task)
	{
		try
		{
			if (client.isInInstancedRegion())
			{
				return true;
			}
		}
		catch (Exception e)
		{
			// Instance probe failed — fall through to the region comparison.
		}

		// Resolved lazily through the Provider to break the plugin-module DI
		// cycle. Unknown region (-1) means the gate is not enforced — that
		// preserves behaviour for tasks whose chunk can't be resolved.
		int taskRegionId = -1;
		try
		{
			taskRegionId = pluginProvider.get().findRegionForTask(task.getTaskId());
		}
		catch (Exception e)
		{
			log.warn("ConstructionModule: failed to resolve region for task '{}'", task.getTaskId(), e);
		}
		int playerRegionId = getCurrentRegionId();
		if (taskRegionId > 0 && playerRegionId > 0 && taskRegionId != playerRegionId)
		{
			return false;
		}
		return true;
	}

	/**
	 * Apply one build of progress, fire chat + callbacks, and clean up on
	 * completion.
	 */
	private void applyCredit(NuzlockeTask task, String details)
	{
		int totalRequired = Math.max(1, task.getTargetQuantity());
		int previousProgress = task.getCurrentProgress();
		int newProgress = Math.min(totalRequired, previousProgress + 1);
		if (newProgress <= previousProgress)
		{
			return;
		}
		task.setCurrentProgress(newProgress);

		sendTaskProgress(task, details, newProgress, totalRequired);
		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		if (newProgress >= totalRequired && !task.isCompleted())
		{
			task.setCompleted(true);
			sendTaskSuccess(task, details);
			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up task tracking
			taskRequiredObjectIds.remove(task.getTaskId());
			activeTasks.remove(task);
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

	// ==================== CHAT MESSAGE METHODS ====================

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

		if (details != null && !details.isEmpty())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value("  - " + details)
				.build());
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

		if (details != null && !details.isEmpty())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value("  - " + details)
				.build());
		}
	}
}
