/*
 * Copyright (c) 2026, Vani-Lab
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

package com.chunkblazer;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;
import net.runelite.api.gameval.InterfaceID;

/**
 * Outlines every NPC and object in the scene that an active (unfinished) task
 * needs, and adds a "Tasks" right-click submenu on them listing those tasks.
 *
 * Two kinds of match:
 *  - by id: NPCs via TargetNpc npc ids, objects via RequiredObject ids (kills,
 *    pickpocketing, agility, stalls, construction): the same ids the task
 *    modules use to credit progress.
 *  - by rule: tasks with no target id are matched by what the thing is CALLED and
 *    what it can DO (its right-click options). Stations (cook at a range, smith at
 *    an anvil), resource nodes (oak trees, iron rocks) and fishing spots work this
 *    way. Requiring the right option is what keeps decorative look-alikes out: an
 *    ornamental cooking pot has no "Cook" option, a tree stump has no "Chop down",
 *    and a fishing spot only counts for lobsters if it offers "Cage".
 *
 * The outline is the normal colour if at least one of the target's tasks is
 * doable (level requirement met), and the "unavailable" colour if none are.
 * Archived tasks (see TaskArchive) are left out entirely: no outline, no menu entry.
 *
 * Which targets get an outline is the Outline Mode (task window cogwheel): all task
 * targets, only those of saved tasks, only those with a task you have the level
 * for, or none. The right-click Tasks menu always lists every task.
 *
 * Holding Shift over a task target lists its tasks in a tooltip at the cursor (names,
 * progress, and any levels you're missing), without opening the right-click menu.
 *
 * Inventory tools get a Tasks menu too (knife: fletching, tinderbox: firemaking,
 * pestle and mortar: herblore...; see TaskTargetExtras.matchesItem).
 *
 * Auto-tracking (setting "Auto-Track Tasks"): using a task target (attack, talk,
 * chop, mine, cook on it...) tracks that target's lowest-points task in the task box.
 * It only ever replaces a task it tracked itself: a task you chose (right-click menu,
 * task window, tracker or side panel) stays until you change or clear it.
 */
@Singleton
public class TaskTargetHighlighter extends Overlay
{
	private static final int OUTLINE_WIDTH = 2;
	private static final int OUTLINE_FEATHER = 4;

	/**
	 * "Something with this name and one of these right-click options." Rules are
	 * compared by {@code key}, so the same rule built for two tasks is one entry.
	 */
	static final class Rule
	{
		final String key;
		final Predicate<String> nameTest;
		final Set<String> actions;

		Rule(String key, Predicate<String> nameTest, String... actions)
		{
			this.key = key;
			this.nameTest = nameTest;
			this.actions = new HashSet<>(Arrays.asList(actions));
		}

		/** Name must pass; if the rule lists options, the target must offer at least one. */
		boolean matches(Info info)
		{
			return nameTest.test(info.name)
				&& (actions.isEmpty() || !Collections.disjoint(actions, info.actions));
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof Rule && ((Rule) o).key.equals(key);
		}

		@Override
		public int hashCode()
		{
			return key.hashCode();
		}
	}

	/** Lowercased name and right-click options of an object or NPC. */
	static final class Info
	{
		final String name;
		final Set<String> actions;

		Info(String name, Set<String> actions)
		{
			this.name = name;
			this.actions = actions;
		}
	}

	private static final Predicate<String> ANY_NAME = name -> true;

	/**
	 * Objects that look like a station or node but can't actually be used: decorative
	 * fire pits, ornamental props, etc. Most are already filtered out by requiring the
	 * right option, but things matched by name alone (fires, pottery) can't be, so
	 * their ids go here. Find an id with Developer Tools > Game Objects.
	 */
	private static final Set<Integer> NOT_USABLE_OBJECT_IDS = new HashSet<>(Arrays.<Integer>asList(
		26577 // fire pit near the H.A.M. camp: "The fire already seems to be in use."
	));

	/** A boss chunk (region) to list tasks from, optionally only task ids containing a keyword. */
	static final class Entrance
	{
		int region;
		Set<String> taskIdKeywords = Collections.emptySet();

		boolean includes(NuzlockeTask task)
		{
			if (taskIdKeywords.isEmpty())
			{
				return true;
			}
			String id = task.getTaskId() == null ? "" : task.getTaskId().toLowerCase();
			for (String keyword : taskIdKeywords)
			{
				if (id.contains(keyword))
				{
					return true;
				}
			}
			return false;
		}
	}

	// --- Station rules (things you use, rather than gather from) ---
	// Any object offering the option counts. Fires and pottery have no option of
	// their own (you use an item on them), so those are matched by exact name.
	private static final Rule COOK = new Rule("station:cook", ANY_NAME, "cook");
	private static final Rule FIRE = new Rule("station:fire", "fire"::equals);
	private static final Rule CHURN = new Rule("station:churn", ANY_NAME, "churn");
	private static final Rule SMELT = new Rule("station:smelt", ANY_NAME, "smelt");
	private static final Rule SMITH = new Rule("station:smith", ANY_NAME, "smith");
	private static final Rule SPIN = new Rule("station:spin", ANY_NAME, "spin");
	private static final Rule POTTERY = new Rule("station:pottery",
		name -> name.equals("potter's wheel") || name.equals("pottery oven"));
	private static final Rule RUNE_ALTAR = new Rule("station:craft-rune", ANY_NAME, "craft-rune");

	private static final Pattern JEWELLERY = Pattern.compile("\\b(ring|necklace|amulet|bracelet)\\b");
	private static final Pattern POTTERY_ITEM = Pattern.compile("\\b(pot|bowl|pie dish|vase|plant pot)\\b");

	private static final String[] CHOP = {"chop down", "chop", "cut"};
	private static final Set<String> PLAIN_TREES = new HashSet<>(Arrays.asList(
		"tree", "dead tree", "dying tree", "evergreen", "evergreen tree", "jungle tree"));

	/**
	 * The two tables in target_tables.json, read once at startup.
	 *
	 * bossEntrances: object id -> the boss chunk whose tasks it lists, so boss tasks
	 * (mostly done inside instances) show on the thing you use to get in. Chunks with
	 * more than one boss pass task id keywords so each entrance lists only its boss.
	 * Entrances whose object id hasn't been found yet: Royal Titans (11824), Moons of
	 * Peril (5680), Yama (5689).
	 *
	 * fish: fish (matched against the task's item name) to the fishing-spot option that
	 * catches it. Checked top to bottom, so more specific names come first
	 * ("karambwanji" before "karambwan", "leaping trout" before "trout").
	 */
	static final class Tables
	{
		Map<Integer, Entrance> bossEntrances;
		LinkedHashMap<String, String[]> fish;
	}

	private final Map<Integer, Entrance> bossEntrances;
	private final Set<Integer> bossRegions = new HashSet<>();
	private final Map<String, String[]> fish;

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final ModelOutlineRenderer outlineRenderer;
	private final TaskArchive archive;
	private final TaskItemOverlay itemOverlay;

	// The task auto-tracking last put in the task box. If the tracked task is anything
	// else, the player chose it, and auto-tracking leaves it alone.
	private volatile String autoTrackedId;

	// What the active tasks want. Rebuilt every game tick (cheap: ~100 tasks), so
	// newly unlocked or completed tasks show up within a tick.
	private Map<Integer, List<NuzlockeTask>> npcTasks = Collections.emptyMap();
	private Map<Integer, List<NuzlockeTask>> objectTasks = Collections.emptyMap();
	private Map<Rule, List<NuzlockeTask>> npcRules = Collections.emptyMap();
	private Map<Rule, List<NuzlockeTask>> objectRules = Collections.emptyMap();
	private Map<Integer, List<NuzlockeTask>> bossRegionTasks = Collections.emptyMap();

	// Name/options per id (fixed per id, kept for the session) and the resulting
	// task list per id (cleared whenever the index is rebuilt).
	private final Map<Integer, Info> objectInfoCache = new HashMap<>();
	private final Map<Integer, Info> npcInfoCache = new HashMap<>();
	private final Map<Integer, List<NuzlockeTask>> objectMatchCache = new HashMap<>();
	private final Map<Integer, List<NuzlockeTask>> npcMatchCache = new HashMap<>();

	// Objects in the scene that currently have at least one task. Kept up to date
	// by spawn/despawn events and fully rescanned when the wanted set changes.
	private final Set<TileObject> trackedObjects = new HashSet<>();

	private final TooltipManager tooltipManager;

	// Most tasks listed in the Shift + hover tooltip before it says "and N more".
	private static final int HOVER_MAX_TASKS = 8;

	@Inject
	public TaskTargetHighlighter(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config,
		ModelOutlineRenderer outlineRenderer, TaskArchive archive, Gson gson,
		TaskItemOverlay itemOverlay, TooltipManager tooltipManager)
	{
		Tables tables = loadTables(gson);
		bossEntrances = tables.bossEntrances;
		fish = tables.fish;
		for (Entrance entrance : bossEntrances.values())
		{
			bossRegions.add(entrance.region);
		}
		this.tooltipManager = tooltipManager;
		this.itemOverlay = itemOverlay;
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.outlineRenderer = outlineRenderer;
		this.archive = archive;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	static Tables loadTables(Gson gson)
	{
		try (Reader in = new InputStreamReader(TaskTargetHighlighter.class.getResourceAsStream("target_tables.json"),
			StandardCharsets.UTF_8))
		{
			return gson.fromJson(in, Tables.class);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/** Drop all state; called on plugin shutdown. */
	public void reset()
	{
		npcTasks = Collections.emptyMap();
		objectTasks = Collections.emptyMap();
		npcRules = Collections.emptyMap();
		objectRules = Collections.emptyMap();
		bossRegionTasks = Collections.emptyMap();
		objectInfoCache.clear();
		npcInfoCache.clear();
		objectMatchCache.clear();
		npcMatchCache.clear();
		trackedObjects.clear();
	}

	// --- Index of what the active tasks want ---------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!isEnabled())
		{
			// Drop everything so a switch back to this style starts from a fresh scan.
			if (!npcTasks.isEmpty() || !objectTasks.isEmpty() || !npcRules.isEmpty()
				|| !objectRules.isEmpty() || !bossRegionTasks.isEmpty() || !trackedObjects.isEmpty())
			{
				reset();
			}
			return;
		}
		rebuildIndex();
	}

	/** The outlines have their own checkbox; the Tasks menu belongs to the Yellow paint style. */
	private boolean isEnabled()
	{
		return config.taskOutlineMode() != OutlineMode.OFF || isMenuEnabled() || config.autoTrackTasks();
	}

	private boolean isMenuEnabled()
	{
		return config.taskTrackerStyle() == TaskTrackerStyle.VANI && config.taskRightClickMenu();
	}

	private void rebuildIndex()
	{
		Map<Integer, List<NuzlockeTask>> npcs = new HashMap<>();
		Map<Integer, List<NuzlockeTask>> objects = new HashMap<>();
		Map<Rule, List<NuzlockeTask>> npcRuleMap = new HashMap<>();
		Map<Rule, List<NuzlockeTask>> objectRuleMap = new HashMap<>();
		Map<Integer, List<NuzlockeTask>> bossTasks = new HashMap<>();

		Set<String> archived = archive.ids();
		for (NuzlockeTask task : plugin.getActiveTasks())
		{
			// Archived tasks are put aside: they don't outline anything or appear in menus.
			if (task == null || task.isCompleted() || archived.contains(task.getTaskId()))
			{
				continue;
			}

			if (!bossEntrances.isEmpty() && task.getTaskId() != null)
			{
				int region = plugin.findRegionForTask(task.getTaskId());
				if (bossRegions.contains(region))
				{
					addTask(bossTasks, region, task);
				}
			}

			TargetNpc target = task.getTargetNpc();
			if (target != null && target.getNpcIds() != null)
			{
				for (Integer id : target.getNpcIds())
				{
					addTask(npcs, id, task);
				}
			}

			if (task.getRequiredObjects() != null)
			{
				for (RequiredObject ro : task.getRequiredObjects())
				{
					if (ro != null && ro.getObjectIds() != null)
					{
						for (Integer id : ro.getObjectIds())
						{
							addTask(objects, id, task);
						}
					}
				}
			}

			for (Rule rule : objectRulesFor(task))
			{
				addTask(objectRuleMap, rule, task);
			}
			Rule npcRule = npcRuleFor(task);
			if (npcRule != null)
			{
				addTask(npcRuleMap, npcRule, task);
			}

			// UI-only extras for tasks with no ids of their own (see TaskTargetExtras).
			if (!hasOwnTargets(task))
			{
				for (Integer id : TaskTargetExtras.objectIds(task))
				{
					addTask(objects, id, task);
				}
				for (Integer id : TaskTargetExtras.npcIds(task))
				{
					addTask(npcs, id, task);
				}
				for (Rule rule : TaskTargetExtras.objectRules(task))
				{
					addTask(objectRuleMap, rule, task);
				}
				Rule extraNpcRule = TaskTargetExtras.npcRule(task);
				if (extraNpcRule != null)
				{
					addTask(npcRuleMap, extraNpcRule, task);
				}
			}
		}

		boolean objectsChanged = !objects.keySet().equals(objectTasks.keySet())
			|| !objectRuleMap.keySet().equals(objectRules.keySet())
			|| !bossTasks.keySet().equals(bossRegionTasks.keySet());
		npcTasks = npcs;
		objectTasks = objects;
		npcRules = npcRuleMap;
		objectRules = objectRuleMap;
		bossRegionTasks = bossTasks;
		objectMatchCache.clear();
		npcMatchCache.clear();
		if (objectsChanged)
		{
			rescanScene();
		}
	}

	/** True if the task data itself names the NPCs or objects this task needs. */
	private static boolean hasOwnTargets(NuzlockeTask task)
	{
		TargetNpc target = task.getTargetNpc();
		if (target != null && target.getNpcIds() != null && !target.getNpcIds().isEmpty())
		{
			return true;
		}
		if (task.getRequiredObjects() != null)
		{
			for (RequiredObject ro : task.getRequiredObjects())
			{
				if (ro != null && ro.getObjectIds() != null && !ro.getObjectIds().isEmpty())
				{
					return true;
				}
			}
		}
		return false;
	}

	private static <K> void addTask(Map<K, List<NuzlockeTask>> map, K key, NuzlockeTask task)
	{
		if (key == null)
		{
			return;
		}
		List<NuzlockeTask> list = map.computeIfAbsent(key, k -> new ArrayList<>());
		if (!list.contains(task))
		{
			list.add(task);
		}
	}

	// --- Which rules a task needs ---------------------------------------------

	/** Object rules for a task: the station it's done at, or the node it's gathered from. */
	static List<Rule> objectRulesFor(NuzlockeTask task)
	{
		String type = task.getCompletionType() == null ? "" : task.getCompletionType().toUpperCase();
		String name = task.getName() == null ? "" : task.getName().toLowerCase();
		String verb = name.contains(" ") ? name.substring(0, name.indexOf(' ')) : name;

		switch (type)
		{
			case "COOKING":
				if (verb.equals("cook") || verb.equals("bake"))
				{
					return Arrays.asList(COOK, FIRE);
				}
				return verb.equals("churn") ? Collections.singletonList(CHURN) : Collections.emptyList();
			case "SMITHING":
				if (verb.equals("smelt"))
				{
					return Collections.singletonList(SMELT);
				}
				return verb.equals("smith") ? Collections.singletonList(SMITH) : Collections.emptyList();
			case "CRAFTING":
				if (verb.equals("spin"))
				{
					return Collections.singletonList(SPIN);
				}
				if (JEWELLERY.matcher(name).find())
				{
					return Collections.singletonList(SMELT);
				}
				return POTTERY_ITEM.matcher(name).find() ? Collections.singletonList(POTTERY) : Collections.emptyList();
			case "RUNECRAFTING":
				return Collections.singletonList(RUNE_ALTAR);
			case "WOODCUTTING":
				return ruleOrNone(treeRule(itemName(task)));
			case "MINING":
				return ruleOrNone(rockRule(itemName(task)));
			default:
				return Collections.emptyList();
		}
	}

	/** NPC rule for a task: currently only fishing spots. */
	Rule npcRuleFor(NuzlockeTask task)
	{
		if (!"FISHING".equalsIgnoreCase(task.getCompletionType()))
		{
			return null;
		}
		String fish = itemName(task);
		for (Map.Entry<String, String[]> entry : this.fish.entrySet())
		{
			if (fish.contains(entry.getKey()))
			{
				return new Rule("fish:" + entry.getKey(), name -> name.contains("fishing spot"), entry.getValue());
			}
		}
		return null;
	}

	/** "Oak Logs" -> anything named "oak..." that can be chopped. Plain "Logs" -> normal trees. */
	static Rule treeRule(String item)
	{
		if (item.equals("logs"))
		{
			return new Rule("tree:plain", PLAIN_TREES::contains, CHOP);
		}
		String wood = item.replaceAll("\\s*logs?$", "").trim();
		if (wood.isEmpty() || item.equals(wood))
		{
			return null; // not a log (branches, mushrooms, thatch spars...)
		}
		return new Rule("tree:" + wood, name -> name.startsWith(wood), CHOP);
	}

	/** "Iron Ore" -> "Iron rocks" that can be mined; uncut gems -> "Gem rocks". */
	static Rule rockRule(String item)
	{
		if (item.isEmpty())
		{
			return null;
		}
		if (item.startsWith("uncut "))
		{
			return new Rule("rock:gem", "gem rocks"::equals, "mine");
		}
		if (item.contains("essence"))
		{
			String key = item.startsWith("dense") ? "dense" : "essence";
			Predicate<String> test = key.equals("dense")
				? name -> name.startsWith("dense")
				: name -> name.contains("essence");
			return new Rule("rock:" + key, test, "mine", "chip");
		}
		String rock = item.replaceAll("^perfect\\s+", "").replaceAll("\\s+(ore|shards)$", "").trim();
		return new Rule("rock:" + rock, name -> name.startsWith(rock), "mine");
	}

	private static List<Rule> ruleOrNone(Rule rule)
	{
		return rule == null ? Collections.emptyList() : Collections.singletonList(rule);
	}

	/** The task's first required item, lowercased ("" if none). */
	private static String itemName(NuzlockeTask task)
	{
		if (task.getRequiredItems() == null || task.getRequiredItems().isEmpty()
			|| task.getRequiredItems().get(0).getItem() == null)
		{
			return "";
		}
		return task.getRequiredItems().get(0).getItem().toLowerCase().trim();
	}

	// --- Looking up what a target is ---------------------------------------

	private static Info info(String rawName, String[] rawActions)
	{
		String name = rawName == null ? "" : Text.removeTags(rawName).toLowerCase();
		Set<String> actions = new HashSet<>();
		if (rawActions != null)
		{
			for (String action : rawActions)
			{
				if (action != null)
				{
					actions.add(Text.removeTags(action).toLowerCase());
				}
			}
		}
		return new Info(name, actions);
	}

	private Info objectInfo(int id)
	{
		return objectInfoCache.computeIfAbsent(id, key ->
		{
			ObjectComposition comp = client.getObjectDefinition(key);
			if (comp == null)
			{
				return info(null, null);
			}
			if (comp.getImpostorIds() != null)
			{
				ObjectComposition impostor = comp.getImpostor();
				if (impostor != null)
				{
					comp = impostor;
				}
			}
			return info(comp.getName(), comp.getActions());
		});
	}

	private Info npcInfo(NPC npc)
	{
		return npcInfoCache.computeIfAbsent(npc.getId(), key ->
		{
			NPCComposition comp = npc.getTransformedComposition();
			if (comp == null)
			{
				comp = npc.getComposition();
			}
			return comp == null ? info(null, null) : info(comp.getName(), comp.getActions());
		});
	}

	/** Every active task this object id is relevant to (by id or by rule). */
	private List<NuzlockeTask> tasksForObject(int id)
	{
		return objectMatchCache.computeIfAbsent(id, key ->
		{
			List<NuzlockeTask> result = new ArrayList<>();
			List<NuzlockeTask> direct = objectTasks.get(key);
			if (direct != null)
			{
				result.addAll(direct);
			}
			if (!objectRules.isEmpty() && !NOT_USABLE_OBJECT_IDS.contains(key))
			{
				collectRuleMatches(objectRules, objectInfo(key), result);
			}
			Entrance entrance = bossEntrances.get(key);
			List<NuzlockeTask> bossTasks = entrance == null ? null : bossRegionTasks.get(entrance.region);
			if (bossTasks != null)
			{
				for (NuzlockeTask task : bossTasks)
				{
					if (entrance.includes(task) && !result.contains(task))
					{
						result.add(task);
					}
				}
			}
			return result;
		});
	}

	/** Every active task this NPC is relevant to (by id or by rule). */
	private List<NuzlockeTask> tasksForNpc(NPC npc)
	{
		return npcMatchCache.computeIfAbsent(npc.getId(), key ->
		{
			List<NuzlockeTask> result = new ArrayList<>();
			List<NuzlockeTask> direct = npcTasks.get(key);
			if (direct != null)
			{
				result.addAll(direct);
			}
			if (!npcRules.isEmpty())
			{
				collectRuleMatches(npcRules, npcInfo(npc), result);
			}
			return result;
		});
	}

	private static void collectRuleMatches(Map<Rule, List<NuzlockeTask>> rules, Info info, List<NuzlockeTask> result)
	{
		for (Map.Entry<Rule, List<NuzlockeTask>> entry : rules.entrySet())
		{
			if (entry.getKey().matches(info))
			{
				for (NuzlockeTask task : entry.getValue())
				{
					if (!result.contains(task))
					{
						result.add(task);
					}
				}
			}
		}
	}

	private boolean anyDoable(List<NuzlockeTask> tasks)
	{
		for (NuzlockeTask task : tasks)
		{
			if (plugin.canDo(task))
			{
				return true;
			}
		}
		return false;
	}

	// --- Object tracking ----------------------------------------------------

	private void rescanScene()
	{
		trackedObjects.clear();
		if (objectTasks.isEmpty() && objectRules.isEmpty() && bossRegionTasks.isEmpty())
		{
			return;
		}
		Scene scene = client.getScene();
		if (scene == null)
		{
			return;
		}
		for (Tile[][] plane : scene.getTiles())
		{
			for (Tile[] row : plane)
			{
				for (Tile tile : row)
				{
					if (tile == null)
					{
						continue;
					}
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject go : gameObjects)
						{
							consider(go);
						}
					}
					consider(tile.getWallObject());
					consider(tile.getDecorativeObject());
					consider(tile.getGroundObject());
				}
			}
		}
	}

	private void consider(TileObject object)
	{
		if (object != null && !tasksForObject(object.getId()).isEmpty())
		{
			trackedObjects.add(object);
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		consider(event.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		trackedObjects.remove(event.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		consider(event.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		trackedObjects.remove(event.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		consider(event.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		trackedObjects.remove(event.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		consider(event.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		trackedObjects.remove(event.getGroundObject());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// A new scene is loading; its objects arrive again as spawn events.
		if (event.getGameState() == GameState.LOADING)
		{
			trackedObjects.clear();
		}
	}

	// --- Drawing ------------------------------------------------------------

	@Override
	public Dimension render(Graphics2D graphics)
	{
		showHoveredTasks();
		OutlineMode mode = config.taskOutlineMode();
		if (mode == OutlineMode.OFF)
		{
			return null;
		}
		Color doable = config.taskHighlightColor();
		Color unavailable = config.taskHighlightUnavailableColor();

		if (!npcTasks.isEmpty() || !npcRules.isEmpty())
		{
			for (NPC npc : client.getNpcs())
			{
				if (npc == null)
				{
					continue;
				}
				Color color = mode.color(tasksForNpc(npc), plugin, doable, unavailable);
				if (color != null)
				{
					outlineRenderer.drawOutline(npc, OUTLINE_WIDTH, color, OUTLINE_FEATHER);
				}
			}
		}

		int plane = client.getPlane();
		for (TileObject object : trackedObjects)
		{
			if (object.getPlane() != plane)
			{
				continue;
			}
			Color color = mode.color(tasksForObject(object.getId()), plugin, doable, unavailable);
			if (color != null)
			{
				outlineRenderer.drawOutline(object, OUTLINE_WIDTH, color, OUTLINE_FEATHER);
			}
		}
		return null;
	}

	/** The Examine option on an item in the inventory (one per item, like NPCs and objects). */
	private static boolean isInventoryExamine(MenuEntry entry)
	{
		return entry.getItemId() > 0
			&& entry.getParam1() == InterfaceID.Inventory.ITEMS
			&& "examine".equalsIgnoreCase(Text.removeTags(entry.getOption()));
	}

	/** Tasks listed on this inventory item: tools, bones and equip gear (see TaskItemOverlay). */
	private List<NuzlockeTask> tasksForItem(int itemId)
	{
		return itemOverlay.tasksFor(itemId);
	}

	// --- Auto-tracking -------------------------------------------------------

	/**
	 * Using an NPC or object that has tasks tracks one of them. Our own Tasks submenu
	 * entries are RUNELITE-type, so picking a task from it (a manual choice) never
	 * lands here.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.autoTrackTasks())
		{
			return;
		}
		MenuAction action = event.getMenuAction();
		List<NuzlockeTask> tasks;
		if (isNpcAction(action))
		{
			NPC npc = event.getMenuEntry().getNpc();
			if (npc == null)
			{
				return;
			}
			tasks = tasksForNpc(npc);
		}
		else if (isObjectAction(action))
		{
			tasks = tasksForObject(event.getId());
		}
		else
		{
			return;
		}
		autoTrack(tasks);
	}

	private static boolean isNpcAction(MenuAction action)
	{
		switch (action)
		{
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case WIDGET_TARGET_ON_NPC:
				return true;
			default:
				return false;
		}
	}

	private static boolean isObjectAction(MenuAction action)
	{
		switch (action)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Track the lowest-points task you have the level for (then the one furthest along).
	 * Never replaces a task you chose yourself; only one auto-tracking put there (or an
	 * empty task box). Also leaves it alone if it already belongs to this target.
	 */
	private void autoTrack(List<NuzlockeTask> tasks)
	{
		if (tasks == null || tasks.isEmpty())
		{
			return;
		}
		NuzlockeTask tracked = plugin.getSelectedTask();
		if (tracked != null && tracked.getTaskId() != null)
		{
			if (!tracked.getTaskId().equals(autoTrackedId))
			{
				return; // chosen by the player: keep it
			}
			for (NuzlockeTask task : tasks)
			{
				if (tracked.getTaskId().equals(task.getTaskId()))
				{
					return;
				}
			}
		}

		NuzlockeTask pick = null;
		Comparator<NuzlockeTask> order = Comparator.comparingInt(NuzlockeTask::getBasePoints)
			.thenComparing(Comparator.comparingDouble(TaskTargetHighlighter::fraction).reversed())
			.thenComparing(t -> t.getName() == null ? "" : t.getName());
		for (NuzlockeTask task : tasks)
		{
			if (plugin.canDo(task) && (pick == null || order.compare(task, pick) < 0))
			{
				pick = task;
			}
		}
		if (pick != null)
		{
			autoTrackedId = pick.getTaskId();
			plugin.selectTaskFromGame(pick);
		}
	}

	private static double fraction(NuzlockeTask task)
	{
		int target = Math.max(1, task.getTargetQuantity());
		return Math.min(1.0, task.getCurrentProgress() / (double) target);
	}

	// --- Right-click "Tasks" submenu ----------------------------------------

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!isMenuEnabled())
		{
			return;
		}

		// Hook the Examine entry: every NPC/object has exactly one, so the Tasks
		// entry is added once per target.
		MenuEntry entry = event.getMenuEntry();
		List<NuzlockeTask> tasks;
		if (entry.getType() == MenuAction.EXAMINE_NPC)
		{
			NPC npc = entry.getNpc();
			if (npc == null)
			{
				return;
			}
			tasks = tasksForNpc(npc);
		}
		else if (entry.getType() == MenuAction.EXAMINE_OBJECT)
		{
			tasks = tasksForObject(event.getIdentifier());
		}
		else if (isInventoryExamine(entry))
		{
			tasks = tasksForItem(entry.getItemId());
		}
		else
		{
			return;
		}

		if (tasks == null || tasks.isEmpty())
		{
			return;
		}

		// Index 1 = just above "Cancel", so it never becomes the left-click action.
		MenuEntry parent = client.createMenuEntry(1)
			.setOption(anyDoable(tasks) ? "<col=ff9040>Tasks</col>" : "<col=ff5050>Tasks</col>")
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE);

		Menu submenu = parent.createSubMenu();
		for (NuzlockeTask task : tasks)
		{
			String option = plugin.canDo(task)
				? task.getName()
				: "<col=ff5050>" + task.getName() + " " + levelNote(task) + "</col>";
			submenu.createMenuEntry(0)
				.setOption(option)
				.setTarget("<col=ffff00>" + task.getCurrentProgress() + "/" + task.getTargetQuantity() + "</col>")
				.setType(MenuAction.RUNELITE)
				.onClick(e -> plugin.selectTaskFromGame(task));
		}
	}


	/** "(20 Defence, 20 Ranged)": every level you're missing, for a task you can't do yet. */
	private String levelNote(NuzlockeTask task)
	{
		String missing = TaskTargetExtras.missingRequirement(client, task);
		return missing != null ? "(" + missing + ")" : "(Lvl " + task.getLevelRequirement() + ")";
	}


	// --- Shift + hover ---------------------------------------------------------------

	/**
	 * While Shift is held over an NPC or object with tasks (the action the game would
	 * show at the top left, like "Attack Cow"), list its tasks in a tooltip at the cursor.
	 */
	private void showHoveredTasks()
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT) || client.isMenuOpen())
		{
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return;
		}
		MenuEntry top = entries[entries.length - 1];
		List<NuzlockeTask> tasks;
		if (top.getNpc() != null)
		{
			tasks = tasksForNpc(top.getNpc());
		}
		else if (isObjectAction(top.getType()) || top.getType() == MenuAction.EXAMINE_OBJECT)
		{
			tasks = tasksForObject(top.getIdentifier());
		}
		else
		{
			return;
		}
		if (tasks == null || tasks.isEmpty())
		{
			return;
		}

		StringBuilder text = new StringBuilder("<col=ff9040>Tasks</col>");
		int shown = 0;
		for (NuzlockeTask task : tasks)
		{
			if (shown == HOVER_MAX_TASKS)
			{
				text.append("</br><col=9f9f9f>and ").append(tasks.size() - shown).append(" more</col>");
				break;
			}
			text.append("</br>");
			if (plugin.canDo(task))
			{
				text.append(task.getName());
			}
			else
			{
				text.append("<col=ff5050>").append(task.getName()).append(' ').append(levelNote(task)).append("</col>");
			}
			if (task.getTargetQuantity() > 1)
			{
				text.append(" <col=ffff00>").append(Math.min(task.getCurrentProgress(), task.getTargetQuantity()))
					.append('/').append(task.getTargetQuantity()).append("</col>");
			}
			shown++;
		}
		tooltipManager.add(new Tooltip(text.toString()));
	}
}
