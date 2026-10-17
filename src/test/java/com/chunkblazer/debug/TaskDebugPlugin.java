package com.chunkblazer.debug;

import com.chunkblazer.ChunkBlazerPlugin;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredObject;
import com.chunkblazer.TargetNpc;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

/**
 * DEV ONLY (lives in src/test, never ships). Checks ChunkBlazer's task catalog against everything
 * in the game a task can point at: NPCs AND objects (trees, rocks, ranges, altars, stalls,
 * obstacles, patches, boss entrances...). Works with or without ChunkBlazer running; it loads the
 * catalog itself and matches with ChunkBlazer's own rules (see Rules).
 *
 * Outlines: green = a task lists this thing's id; cyan = matched by a name/option rule (no id);
 * red = a task names this thing but lists none of its ids (it would never count here), or, with
 * ChunkBlazer running, an active task is missing from its Tasks menu. Thicker = an active task.
 *
 * Right-click: "Debug report" writes a report with a MOST LIKELY section: which problem it is
 * (id/naming, not unlocked/rolled, no module, Slayer gate, timed-fight rules, item went to a bag,
 * wrong item id...), worked out from the catalog, ChunkBlazer's state and what happened when you
 * used it this session. "Debug tasks (n)" lists every task for it.
 *
 * Commands: ::taskdebug [all|npcs|objects|off] (outlines), ::taskaudit (every task vs every NPC
 * and object definition), ::taskreload (reload the catalog).
 */
@Slf4j
@PluginDescriptor(name = "ChunkBlazer Task Debug", description = "Dev only: checks every task's NPCs and objects against the game")
public class TaskDebugPlugin extends Plugin
{
	static final int WINDOW_TICKS = 20;
	static final int KILL_WINDOW_TICKS = 12;
	private static final int MAX_NPC_ID = 25000;
	private static final int MAX_OBJECT_ID = 65000;
	private static final Pattern LEVEL = Pattern.compile("(?i)level (\\d+)");
	static final Set<Integer> BAGS = new HashSet<>(java.util.Arrays.asList(InventoryID.INV, InventoryID.SEED_BOX,
		InventoryID.LOOTING_BAG, InventoryID.TACKLE_BOX, InventoryID.FORESTRY_KIT, InventoryID.HUNTSMANS_KIT));

	@Inject
	Client client;
	@Inject
	private PluginManager pluginManager;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private TaskDebugOverlay overlay;
	@Inject
	Gson gson;
	@Inject
	ItemManager itemManager;

	// --- The catalog ---------------------------------------------------------------------------

	/** A catalog task and every chunk (and region) it's listed in. */
	static final class Entry
	{
		NuzlockeTask task;
		final List<String> chunks = new ArrayList<>();
		final Set<Integer> regions = new LinkedHashSet<>();
		boolean own;
	}

	/** A rule and the tasks that use it (grouped by the rule's key, like the plugin does). */
	static final class RuleGroup
	{
		final Rules.Ref ref;
		final List<Entry> entries = new ArrayList<>();

		RuleGroup(Rules.Ref ref)
		{
			this.ref = ref;
		}
	}

	final Map<String, Entry> catalog = new LinkedHashMap<>();
	private final Map<Integer, List<Entry>> npcById = new HashMap<>();
	private final Map<Integer, List<Entry>> objById = new HashMap<>();
	private final Map<Integer, List<Entry>> npcByExtra = new HashMap<>();
	private final Map<Integer, List<Entry>> objByExtra = new HashMap<>();
	private final Map<String, List<Entry>> npcByName = new HashMap<>();
	private final Map<String, List<Entry>> objByName = new HashMap<>();
	private final Map<String, RuleGroup> npcRules = new LinkedHashMap<>();
	private final Map<String, RuleGroup> objRules = new LinkedHashMap<>();
	String catalogSource = "not loaded";
	Rules rules;

	// --- What's in the scene -------------------------------------------------------------------

	/** One thing in the game (an NPC or object) as the checks see it. */
	static final class Target
	{
		String kind;
		String name;
		final Set<Integer> ids = new LinkedHashSet<>();
		int baseId;
		int shownId = -1;
		int level = -1;
		String[] actions;
		WorldPoint where;
		NPC npc;
		TileObject object;
	}

	/** How a task matched a target. A gap is a task that names the target but would never count on it. */
	static final class Match
	{
		final Entry e;
		final String how;
		final boolean gap;
		final String fix;

		Match(Entry e, String how, boolean gap, String fix)
		{
			this.e = e;
			this.how = how;
			this.gap = gap;
			this.fix = fix;
		}

		boolean byRule()
		{
			return how.startsWith("rule") || how.startsWith("boss entrance");
		}
	}

	/** One target's check. The ChunkBlazer parts are filled only while it runs. */
	static final class Check
	{
		final List<Match> matches = new ArrayList<>();
		final List<NuzlockeTask> expected = new ArrayList<>();
		final List<NuzlockeTask> shown = new ArrayList<>();
		final List<NuzlockeTask> missing = new ArrayList<>();
		final List<NuzlockeTask> extra = new ArrayList<>();

		boolean empty()
		{
			return matches.isEmpty() && extra.isEmpty();
		}

		boolean anyGap()
		{
			return matches.stream().anyMatch(m -> m.gap);
		}

		boolean wrong()
		{
			return anyGap() || !missing.isEmpty() || !extra.isEmpty();
		}

		boolean rulesOnly()
		{
			return !matches.isEmpty() && matches.stream().allMatch(Match::byRule);
		}
	}

	private final Map<Object, Check> checks = new HashMap<>();
	private int checksTick = -1;
	private final Map<Long, Check> objectCheckCache = new HashMap<>();
	private final Map<Integer, Boolean> relevantById = new HashMap<>();
	private final Set<TileObject> sceneObjects = new HashSet<>();
	final List<TileObject> taskObjects = new ArrayList<>();
	private boolean rescan;
	private int lastFilterTick = -1;
	String outlineMode = "all";

	// --- Evidence: what happened when you used things ------------------------------------------

	/** One use of a task target (attack, talk, chop, cook on, steal from...) and what followed. */
	static final class Interaction
	{
		int tick;
		String kind;
		String name;
		Set<Integer> ids;
		String option;
		WorldPoint where;
		final Set<String> taskIds = new LinkedHashSet<>();
		final Map<String, Integer> progressBefore = new HashMap<>();
		final Map<String, Integer> progressAfter = new HashMap<>();
		final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
		final Map<Skill, Integer> xpTick = new EnumMap<>(Skill.class);
		final Map<Integer, Integer> items = new LinkedHashMap<>();
		final Map<Integer, Integer> itemTick = new HashMap<>();
		final List<String> notes = new ArrayList<>();
		boolean closed;
	}

	/** One NPC you killed (landed a hit on) and what followed. */
	static final class Kill
	{
		int tick;
		int firstHitTick;
		String name;
		Set<Integer> ids;
		WorldPoint where;
		int slayerCount;
		int ticksSinceLogin;
		int hpAtFirstHit;
		int maxHp;
		int fightTicks;
		final Set<String> taskIds = new LinkedHashSet<>();
		final Map<String, Integer> progressBefore = new HashMap<>();
		final Map<String, Integer> progressAfter = new HashMap<>();
		final List<Integer> drops = new ArrayList<>();
		final List<String> notes = new ArrayList<>();
		boolean onTaskSignal;
		boolean closed;
	}

	final Deque<Interaction> interactions = new ArrayDeque<>();
	final Deque<Kill> kills = new ArrayDeque<>();
	private final Map<Integer, int[]> fights = new HashMap<>(); // npc index -> {first hit tick, hp, max hp, last hit tick}
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final Map<Integer, Map<Integer, Integer>> lastContainers = new HashMap<>();
	final Map<Integer, Integer> menuCounts = new HashMap<>();
	int loginTick = -1;
	private GameState lastState;

	@Override
	protected void startUp()
	{
		rules = new Rules(gson);
		loadCatalog();
		overlayManager.add(overlay);
		rescan = true;
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		checks.clear();
		objectCheckCache.clear();
		sceneObjects.clear();
		taskObjects.clear();
		interactions.clear();
		kills.clear();
		fights.clear();
	}

	// --- Loading the catalog -------------------------------------------------------------------

	void loadCatalog()
	{
		catalog.clear();
		npcById.clear();
		objById.clear();
		npcByExtra.clear();
		objByExtra.clear();
		npcByName.clear();
		objByName.clear();
		npcRules.clear();
		objRules.clear();
		objectCheckCache.clear();
		relevantById.clear();
		try
		{
			JsonObject root;
			File newest = null;
			for (File f : new File[]{
				new File(RuneLite.RUNELITE_DIR, "plugin-data/chunkblazer/tasks_catalog.json"),
				new File(RuneLite.RUNELITE_DIR, "chunkblazer/tasks_catalog.json")})
			{
				if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified()))
				{
					newest = f;
				}
			}
			if (newest != null)
			{
				try (Reader r = Files.newBufferedReader(newest.toPath(), StandardCharsets.UTF_8))
				{
					root = gson.fromJson(r, JsonObject.class);
				}
				catalogSource = newest.getName() + " (downloaded " + new SimpleDateFormat("yyyy-MM-dd").format(new Date(newest.lastModified())) + ")";
			}
			else
			{
				try (InputStream in = ChunkBlazerPlugin.class.getResourceAsStream("/com/chunkblazer/tasks_catalog.json.gz");
					Reader r = new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))
				{
					root = gson.fromJson(r, JsonObject.class);
				}
				catalogSource = "bundled tasks_catalog.json.gz";
			}
			for (Map.Entry<String, JsonElement> file : root.entrySet())
			{
				JsonElement content = file.getValue();
				if (content.isJsonPrimitive())
				{
					content = gson.fromJson(content.getAsString(), JsonElement.class);
				}
				if (file.getKey().startsWith("_Target_Extras"))
				{
					rules.loadExtras(gson.toJson(content), gson);
					continue;
				}
				if (file.getKey().startsWith("_"))
				{
					continue;
				}
				walk(content, file.getKey().replace(".json", ""), Collections.emptySet());
			}
			index();
			log.info("Task debug: {} tasks from {}", catalog.size(), catalogSource);
		}
		catch (Exception e)
		{
			log.warn("Task debug: couldn't load the task catalog", e);
			catalogSource = "FAILED: " + e.getClass().getSimpleName();
		}
	}

	private void walk(JsonElement el, String where, Set<Integer> regions)
	{
		if (el.isJsonArray())
		{
			for (JsonElement e : el.getAsJsonArray())
			{
				walk(e, where, regions);
			}
			return;
		}
		if (!el.isJsonObject())
		{
			return;
		}
		JsonObject o = el.getAsJsonObject();
		if (o.has("taskID"))
		{
			NuzlockeTask task = gson.fromJson(o, NuzlockeTask.class);
			if (task != null && task.getTaskId() != null)
			{
				Entry e = catalog.computeIfAbsent(task.getTaskId(), k -> new Entry());
				if (e.task == null)
				{
					e.task = task;
				}
				e.chunks.add(where);
				e.regions.addAll(regions);
			}
			return;
		}
		String here = where;
		Set<Integer> regs = regions;
		if (o.has("tasks"))
		{
			String name = o.has("Friendly_Name") ? o.get("Friendly_Name").getAsString() : o.has("boss_name") ? o.get("boss_name").getAsString() : "";
			regs = new LinkedHashSet<>();
			if (o.has("region_id"))
			{
				JsonElement r = o.get("region_id");
				if (r.isJsonArray())
				{
					for (JsonElement x : r.getAsJsonArray())
					{
						regs.add(x.getAsInt());
					}
				}
				else if (r.isJsonPrimitive())
				{
					regs.add(r.getAsInt());
				}
			}
			here = where + " " + (name + " " + regs).trim();
		}
		for (Map.Entry<String, JsonElement> e : o.entrySet())
		{
			walk(e.getValue(), here, regs);
		}
	}

	private void index()
	{
		for (Entry e : catalog.values())
		{
			NuzlockeTask t = e.task;
			TargetNpc target = t.getTargetNpc();
			boolean npcIds = target != null && target.getNpcIds() != null && !target.getNpcIds().isEmpty();
			boolean objIds = false;
			if (t.getRequiredObjects() != null)
			{
				for (RequiredObject ro : t.getRequiredObjects())
				{
					if (ro != null && ro.getObjectIds() != null && !ro.getObjectIds().isEmpty())
					{
						objIds = true;
					}
				}
			}
			e.own = npcIds || objIds;
			if (target != null)
			{
				if (target.getNpcIds() != null)
				{
					for (int id : target.getNpcIds())
					{
						add(npcById, id, e);
					}
				}
				if (target.getName() != null)
				{
					add(npcByName, target.getName().trim().toLowerCase(), e);
				}
			}
			if (t.getRequiredObjects() != null)
			{
				for (RequiredObject ro : t.getRequiredObjects())
				{
					if (ro == null)
					{
						continue;
					}
					if (ro.getObjectIds() != null)
					{
						for (int id : ro.getObjectIds())
						{
							add(objById, id, e);
						}
					}
					if (ro.getObject() != null)
					{
						add(objByName, ro.getObject().trim().toLowerCase(), e);
					}
				}
			}
			if (!e.own)
			{
				for (int id : rules.extraNpcIds(t))
				{
					add(npcByExtra, id, e);
				}
				for (int id : rules.extraObjectIds(t))
				{
					add(objByExtra, id, e);
				}
			}
			for (Rules.Ref ref : rules.npcRules(t, e.own))
			{
				npcRules.computeIfAbsent(ref.key, k -> new RuleGroup(ref)).entries.add(e);
			}
			for (Rules.Ref ref : rules.objectRules(t, e.own))
			{
				objRules.computeIfAbsent(ref.key, k -> new RuleGroup(ref)).entries.add(e);
			}
		}
	}

	private static <K> void add(Map<K, List<Entry>> map, K key, Entry e)
	{
		List<Entry> list = map.computeIfAbsent(key, k -> new ArrayList<>());
		if (!list.contains(e))
		{
			list.add(e);
		}
	}

	// --- Targets -------------------------------------------------------------------------------

	static Set<Integer> npcIds(NPC npc)
	{
		Set<Integer> ids = new LinkedHashSet<>();
		ids.add(npc.getId());
		NPCComposition base = npc.getComposition();
		if (base != null)
		{
			ids.add(base.getId());
		}
		NPCComposition shown = npc.getTransformedComposition();
		if (shown != null)
		{
			ids.add(shown.getId());
		}
		return ids;
	}

	Target target(NPC npc)
	{
		Target t = new Target();
		t.kind = "NPC";
		t.npc = npc;
		t.name = npc.getName() == null ? null : Text.removeTags(npc.getName());
		t.ids.addAll(npcIds(npc));
		t.baseId = npc.getId();
		NPCComposition shown = npc.getTransformedComposition() != null ? npc.getTransformedComposition() : npc.getComposition();
		t.shownId = shown == null ? -1 : shown.getId();
		t.actions = shown == null ? null : shown.getActions();
		t.level = npc.getCombatLevel();
		t.where = npc.getWorldLocation();
		return t;
	}

	Target target(TileObject obj)
	{
		Target t = new Target();
		t.kind = "Object";
		t.object = obj;
		t.baseId = obj.getId();
		t.ids.add(obj.getId());
		ObjectComposition comp = client.getObjectDefinition(obj.getId());
		if (comp != null && comp.getImpostorIds() != null)
		{
			ObjectComposition imp = comp.getImpostor();
			if (imp != null)
			{
				comp = imp;
				t.ids.add(imp.getId());
			}
		}
		t.shownId = comp == null ? -1 : comp.getId();
		t.name = comp == null || comp.getName() == null || comp.getName().equals("null") ? null : Text.removeTags(comp.getName());
		t.actions = comp == null ? null : comp.getActions();
		t.where = obj.getWorldLocation();
		return t;
	}

	static int taskLevel(NuzlockeTask t)
	{
		Matcher m = LEVEL.matcher(t.getName() == null ? "" : t.getName());
		return m.find() ? Integer.parseInt(m.group(1)) : -1;
	}

	static String type(NuzlockeTask t)
	{
		return t.getCompletionType() == null ? "" : t.getCompletionType().toUpperCase();
	}

	/** Whether something with these options could do this task at all (a decorative look-alike can't). */
	static boolean fits(NuzlockeTask t, String[] actions, boolean npc)
	{
		String type = type(t);
		String need = !npc ? null
			: type.equals("NPC_KILL") || type.equals("SLAYER") || type.equals("COMBAT") ? "attack"
			: type.equals("THIEVING") ? "pickpocket"
			: type.equals("NPC_DIALOGUE") ? "talk-to" : null;
		if (need == null)
		{
			// Objects: anything with at least one option could be the task's object.
			if (!npc)
			{
				if (actions == null)
				{
					return false;
				}
				for (String a : actions)
				{
					if (a != null && !a.isEmpty())
					{
						return true;
					}
				}
				return false;
			}
			return true;
		}
		if (actions == null)
		{
			return false;
		}
		for (String a : actions)
		{
			if (a != null && Text.removeTags(a).equalsIgnoreCase(need))
			{
				return true;
			}
		}
		return false;
	}

	/** Every catalog task for this target, matched the way ChunkBlazer matches, plus the id gaps. */
	private void match(Target tg, Check c)
	{
		Set<Entry> seen = new LinkedHashSet<>();
		boolean npc = tg.npc != null;
		Object info = rules.ok() ? rules.info(tg.name, tg.actions) : null;
		if (npc)
		{
			for (int id : tg.ids)
			{
				for (Entry e : npcById.getOrDefault(id, Collections.emptyList()))
				{
					if (seen.add(e))
					{
						c.matches.add(new Match(e, "id " + id, false, null));
					}
				}
				for (Entry e : npcByExtra.getOrDefault(id, Collections.emptyList()))
				{
					if (seen.add(e))
					{
						c.matches.add(new Match(e, "extras id " + id, false, null));
					}
				}
			}
			for (RuleGroup g : npcRules.values())
			{
				if (rules.matches(g.ref, info))
				{
					for (Entry e : g.entries)
					{
						if (seen.add(e))
						{
							c.matches.add(new Match(e, "rule " + g.ref.key + " (" + g.ref.from + ")", false, null));
						}
					}
				}
			}
		}
		else
		{
			// ChunkBlazer matches an object's ids by its BASE id only (what the scene reports).
			for (Entry e : objById.getOrDefault(tg.baseId, Collections.emptyList()))
			{
				if (seen.add(e))
				{
					c.matches.add(new Match(e, "id " + tg.baseId, false, null));
				}
			}
			for (Entry e : objByExtra.getOrDefault(tg.baseId, Collections.emptyList()))
			{
				if (seen.add(e))
				{
					c.matches.add(new Match(e, "extras id " + tg.baseId, false, null));
				}
			}
			if (tg.shownId >= 0 && tg.shownId != tg.baseId)
			{
				List<Entry> byShown = new ArrayList<>(objById.getOrDefault(tg.shownId, Collections.emptyList()));
				byShown.addAll(objByExtra.getOrDefault(tg.shownId, Collections.emptyList()));
				for (Entry e : byShown)
				{
					if (seen.add(e))
					{
						c.matches.add(new Match(e, "lists the SHOWN id " + tg.shownId + " only", true,
							"add base id " + tg.baseId + " (ChunkBlazer matches objects by the scene's base id, not the form it shows as)"));
					}
				}
			}
			if (!rules.notUsable(tg.baseId))
			{
				for (RuleGroup g : objRules.values())
				{
					if (rules.matches(g.ref, info))
					{
						for (Entry e : g.entries)
						{
							if (seen.add(e))
							{
								c.matches.add(new Match(e, "rule " + g.ref.key + " (" + g.ref.from + ")", false, null));
							}
						}
					}
				}
			}
			int bossRegion = rules.entranceRegion(tg.baseId);
			if (bossRegion >= 0)
			{
				for (Entry e : catalog.values())
				{
					if (e.regions.contains(bossRegion) && rules.entranceIncludes(tg.baseId, e.task) && seen.add(e))
					{
						c.matches.add(new Match(e, "boss entrance to region " + bossRegion, false, null));
					}
				}
			}
		}
		// Gaps: tasks that name this target (at its level, if they say one) but would never count on it.
		Map<String, List<Entry>> byName = npc ? npcByName : objByName;
		for (Entry e : byName.getOrDefault(tg.name == null ? "" : tg.name.toLowerCase(), Collections.emptyList()))
		{
			int want = taskLevel(e.task);
			if (seen.contains(e) || (npc && want >= 0 && want != tg.level) || !fits(e.task, tg.actions, npc))
			{
				continue;
			}
			seen.add(e);
			c.matches.add(new Match(e, "NAME ONLY", true, "add id " + tg.baseId + (tg.shownId >= 0 && tg.shownId != tg.baseId && npc ? " (or " + tg.shownId + ")" : "")
				+ " to " + (npc ? "target_npc npc_ids" : "required_objects object_id")));
		}
	}

	/** The check for this target, worked out once a tick. Client thread only. */
	@SuppressWarnings("unchecked")
	Check check(Target tg)
	{
		if (client.getTickCount() != checksTick)
		{
			checks.clear();
			checksTick = client.getTickCount();
		}
		Object key = tg.npc != null ? (Object) ("n" + tg.npc.getIndex()) : (Object) tg.object;
		return checks.computeIfAbsent(key, k ->
		{
			Check c;
			if (tg.object != null)
			{
				c = new Check();
				c.matches.addAll(baseCheck(tg).matches);
			}
			else
			{
				c = new Check();
				match(tg, c);
			}
			if (resolve())
			{
				Set<String> archived = archived();
				Set<String> want = new HashSet<>();
				c.matches.stream().filter(m -> !m.gap).forEach(m -> want.add(m.e.task.getTaskId()));
				for (NuzlockeTask task : cb.getActiveTasks())
				{
					if (task != null && !task.isCompleted() && !archived.contains(task.getTaskId()) && want.contains(task.getTaskId()))
					{
						c.expected.add(task);
					}
				}
				try
				{
					Object shown = tg.npc != null ? tasksForNpc.invoke(highlighter, tg.npc) : tasksForObject.invoke(highlighter, tg.baseId);
					if (shown instanceof List)
					{
						c.shown.addAll((List<NuzlockeTask>) shown);
					}
				}
				catch (ReflectiveOperationException | RuntimeException e)
				{
					log.debug("Task debug: ChunkBlazer lookup failed", e);
				}
				Set<String> shownIds = new HashSet<>();
				c.shown.forEach(t -> shownIds.add(t.getTaskId()));
				c.expected.stream().filter(t -> !shownIds.contains(t.getTaskId())).forEach(c.missing::add);
				Set<String> all = new HashSet<>();
				c.matches.forEach(m -> all.add(m.e.task.getTaskId()));
				c.shown.stream().filter(t -> !all.contains(t.getTaskId())).forEach(c.extra::add);
			}
			return c;
		});
	}

	Check check(NPC npc)
	{
		return check(target(npc));
	}

	// --- Objects in the scene ------------------------------------------------------------------

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		objectAdded(e.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		objectRemoved(e.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned e)
	{
		objectAdded(e.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned e)
	{
		objectRemoved(e.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
	{
		objectAdded(e.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
	{
		objectRemoved(e.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned e)
	{
		objectAdded(e.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned e)
	{
		objectRemoved(e.getGroundObject());
	}

	private void objectAdded(TileObject o)
	{
		if (o != null)
		{
			sceneObjects.add(o);
			lastFilterTick = -1;
		}
	}

	private void objectRemoved(TileObject o)
	{
		if (o != null)
		{
			sceneObjects.remove(o);
			taskObjects.remove(o);
		}
	}

	private void rescanScene()
	{
		sceneObjects.clear();
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv == null ? null : wv.getScene();
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
					for (GameObject g : tile.getGameObjects())
					{
						if (g != null && g.getSceneMinLocation().equals(tile.getSceneLocation()))
						{
							sceneObjects.add(g);
						}
					}
					WallObject w = tile.getWallObject();
					if (w != null)
					{
						sceneObjects.add(w);
					}
					DecorativeObject d = tile.getDecorativeObject();
					if (d != null)
					{
						sceneObjects.add(d);
					}
					GroundObject gr = tile.getGroundObject();
					if (gr != null)
					{
						sceneObjects.add(gr);
					}
				}
			}
		}
		lastFilterTick = -1;
	}

	/** The scene objects any task points at. Rebuilt every few ticks (multiloc objects change form). */
	private void filterObjects()
	{
		taskObjects.clear();
		for (TileObject o : sceneObjects)
		{
			int id = o.getId();
			Boolean relevant = relevantById.get(id);
			if (relevant == null)
			{
				ObjectComposition comp = client.getObjectDefinition(id);
				boolean multiloc = comp != null && comp.getImpostorIds() != null;
				relevant = !baseCheck(target(o)).matches.isEmpty();
				if (!multiloc)
				{
					relevantById.put(id, relevant);
				}
			}
			if (relevant)
			{
				taskObjects.add(o);
			}
		}
		lastFilterTick = client.getTickCount();
	}

	/** The catalog part of an object's check (no ChunkBlazer state), cached per base id and shown form. */
	private Check baseCheck(Target tg)
	{
		long cacheKey = ((long) tg.baseId << 32) | (tg.shownId & 0xffffffffL);
		Check base = objectCheckCache.get(cacheKey);
		if (base == null)
		{
			base = new Check();
			match(tg, base);
			objectCheckCache.put(cacheKey, base);
		}
		return base;
	}

	// --- ChunkBlazer, if it's running (optional) -----------------------------------------------

	ChunkBlazerPlugin cb;
	private Object highlighter;
	private Method tasksForNpc;
	private Method tasksForObject;
	private Object archive;
	private Method archiveIds;
	Object moduleManager;
	private long lastResolveTry;

	boolean resolve()
	{
		if (cb != null && pluginManager.isPluginEnabled(cb))
		{
			return true;
		}
		cb = null;
		long now = System.currentTimeMillis();
		if (now - lastResolveTry < 5000)
		{
			return false;
		}
		lastResolveTry = now;
		try
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p instanceof ChunkBlazerPlugin && pluginManager.isPluginEnabled(p))
				{
					ChunkBlazerPlugin found = (ChunkBlazerPlugin) p;
					highlighter = field(found, "taskTargetHighlighter");
					tasksForNpc = highlighter.getClass().getDeclaredMethod("tasksForNpc", NPC.class);
					tasksForNpc.setAccessible(true);
					tasksForObject = highlighter.getClass().getDeclaredMethod("tasksForObject", int.class);
					tasksForObject.setAccessible(true);
					archive = field(highlighter, "archive");
					archiveIds = archive.getClass().getMethod("ids");
					moduleManager = field(found, "taskModuleManager");
					cb = found;
				}
			}
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			log.warn("Task debug: ChunkBlazer is running but its insides couldn't be reached", e);
			cb = null;
		}
		return cb != null;
	}

	static Object field(Object owner, String name) throws ReflectiveOperationException
	{
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass())
		{
			try
			{
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f.get(owner);
			}
			catch (NoSuchFieldException e)
			{
				// Try the superclass.
			}
		}
		throw new NoSuchFieldException(name);
	}

	static Object fieldOrNull(Object owner, String name)
	{
		try
		{
			return owner == null ? null : field(owner, name);
		}
		catch (ReflectiveOperationException e)
		{
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	Set<String> archived()
	{
		try
		{
			return (Set<String>) archiveIds.invoke(archive);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return Collections.emptySet();
		}
	}

	/** ChunkBlazer's live copy of a task (active chunk task or global), or null. */
	NuzlockeTask live(String taskId)
	{
		if (cb == null)
		{
			return null;
		}
		for (NuzlockeTask t : cb.getActiveTasks())
		{
			if (t != null && taskId.equals(t.getTaskId()))
			{
				return t;
			}
		}
		try
		{
			for (NuzlockeTask t : cb.getVisibleGlobalTasks())
			{
				if (t != null && taskId.equals(t.getTaskId()))
				{
					return t;
				}
			}
		}
		catch (RuntimeException e)
		{
			// Older builds: no globals.
		}
		return null;
	}

	/** The module ChunkBlazer registered this task with, or null. */
	Object moduleFor(String taskId)
	{
		Object map = fieldOrNull(moduleManager, "taskToModuleMap");
		return map instanceof Map ? ((Map<?, ?>) map).get(taskId) : null;
	}

	private Map<String, Integer> progress(Collection<String> taskIds)
	{
		Map<String, Integer> out = new HashMap<>();
		if (!resolve())
		{
			return out;
		}
		for (String id : taskIds)
		{
			NuzlockeTask t = live(id);
			if (t != null)
			{
				out.put(id, t.getCurrentProgress());
			}
			else if (cb.isTaskCompleted(id))
			{
				out.put(id, Integer.MAX_VALUE);
			}
		}
		return out;
	}

	// --- Right-click ---------------------------------------------------------------------------

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		MenuEntry entry = event.getMenuEntry();
		Target tg = null;
		if (entry.getType() == MenuAction.EXAMINE_NPC && entry.getNpc() != null)
		{
			tg = target(entry.getNpc());
		}
		else if (entry.getType() == MenuAction.EXAMINE_OBJECT)
		{
			TileObject o = findObject(entry.getIdentifier(), entry.getParam0(), entry.getParam1());
			if (o != null)
			{
				tg = target(o);
			}
		}
		if (tg == null)
		{
			return;
		}
		Check c = check(tg);
		if (c.empty() && tg.npc == null)
		{
			return; // objects with no task at all get no debug entries (too many)
		}
		final Target target = tg;
		client.createMenuEntry(1)
			.setOption("<col=00ffff>Debug report</col>")
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> report(target));
		if (c.matches.isEmpty())
		{
			return;
		}
		Menu sub = client.createMenuEntry(1)
			.setOption("<col=00ffff>Debug tasks (" + c.matches.size() + ")</col>")
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE)
			.createSubMenu();
		Set<String> missing = new HashSet<>();
		Set<String> active = new HashSet<>();
		c.missing.forEach(t -> missing.add(t.getTaskId()));
		c.expected.forEach(t -> active.add(t.getTaskId()));
		for (Match m : c.matches)
		{
			String id = m.e.task.getTaskId();
			String state = missing.contains(id) ? " <col=ff4040>[active, NOT in menu]</col>" : active.contains(id) ? " <col=40ff40>[active]</col>" : "";
			sub.createMenuEntry(0)
				.setOption((m.gap ? "<col=ff4040>\u2717 id gap</col> " : m.byRule() ? "<col=40e0ff>~ rule</col> " : "<col=40ff40>\u2713</col> ") + m.e.task.getName() + state)
				.setType(MenuAction.RUNELITE)
				.onClick(x -> report(target));
		}
	}

	TileObject findObject(int id, int sceneX, int sceneY)
	{
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv == null ? null : wv.getScene();
		if (scene == null)
		{
			return null;
		}
		Tile[][] plane = scene.getTiles()[wv.getPlane()];
		if (sceneX < 0 || sceneY < 0 || sceneX >= plane.length || sceneY >= plane[sceneX].length)
		{
			return null;
		}
		Tile tile = plane[sceneX][sceneY];
		if (tile == null)
		{
			return null;
		}
		for (GameObject g : tile.getGameObjects())
		{
			if (g != null && g.getId() == id)
			{
				return g;
			}
		}
		if (tile.getWallObject() != null && tile.getWallObject().getId() == id)
		{
			return tile.getWallObject();
		}
		if (tile.getDecorativeObject() != null && tile.getDecorativeObject().getId() == id)
		{
			return tile.getDecorativeObject();
		}
		if (tile.getGroundObject() != null && tile.getGroundObject().getId() == id)
		{
			return tile.getGroundObject();
		}
		return null;
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		for (MenuEntry entry : event.getMenuEntries())
		{
			Menu sub = entry.getSubMenu();
			if (sub != null && Text.removeTags(entry.getOption()).equals("Tasks"))
			{
				int key = entry.getNpc() != null ? entry.getNpc().getIndex() : -1 - entry.getIdentifier();
				menuCounts.put(key, sub.getMenuEntries().length);
			}
		}
	}

	// --- Evidence ------------------------------------------------------------------------------

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuEntry entry = event.getMenuEntry();
		MenuAction type = entry.getType();
		if (type == MenuAction.RUNELITE || type == MenuAction.EXAMINE_NPC || type == MenuAction.EXAMINE_OBJECT)
		{
			return;
		}
		Target tg = null;
		if (entry.getNpc() != null)
		{
			tg = target(entry.getNpc());
		}
		else if (type == MenuAction.GAME_OBJECT_FIRST_OPTION || type == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| type == MenuAction.GAME_OBJECT_THIRD_OPTION || type == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| type == MenuAction.GAME_OBJECT_FIFTH_OPTION || type == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT)
		{
			TileObject o = findObject(entry.getIdentifier(), entry.getParam0(), entry.getParam1());
			if (o != null)
			{
				tg = target(o);
			}
		}
		if (tg == null)
		{
			return;
		}
		Check c = check(tg);
		if (c.matches.isEmpty())
		{
			return;
		}
		Interaction in = new Interaction();
		in.tick = client.getTickCount();
		in.kind = tg.kind;
		in.name = tg.name;
		in.ids = tg.ids;
		in.option = Text.removeTags(entry.getOption());
		in.where = tg.where;
		c.matches.forEach(m -> in.taskIds.add(m.e.task.getTaskId()));
		in.progressBefore.putAll(progress(in.taskIds));
		interactions.addFirst(in);
		while (interactions.size() > 80)
		{
			interactions.removeLast();
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!(event.getActor() instanceof NPC) || !event.getHitsplat().isMine())
		{
			return;
		}
		int idx = ((NPC) event.getActor()).getIndex();
		int[] f = fights.get(idx);
		int now = client.getTickCount();
		if (f == null || now - f[3] > 100)
		{
			f = new int[]{now, client.getBoostedSkillLevel(Skill.HITPOINTS), client.getRealSkillLevel(Skill.HITPOINTS), now};
			fights.put(idx, f);
		}
		f[3] = now;
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		int[] f = fights.remove(npc.getIndex());
		int now = client.getTickCount();
		if (f == null || now - f[3] > 100)
		{
			return;
		}
		Kill k = new Kill();
		k.tick = now;
		k.firstHitTick = f[0];
		k.hpAtFirstHit = f[1];
		k.maxHp = f[2];
		k.fightTicks = now - f[0];
		k.ticksSinceLogin = loginTick < 0 ? -1 : f[0] - loginTick;
		k.name = npc.getName() == null ? "?" : Text.removeTags(npc.getName());
		k.ids = npcIds(npc);
		k.where = npc.getWorldLocation();
		k.slayerCount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		check(npc).matches.stream().filter(m -> !m.gap).forEach(m -> k.taskIds.add(m.e.task.getTaskId()));
		k.progressBefore.putAll(progress(k.taskIds));
		kills.addFirst(k);
		while (kills.size() > 60)
		{
			kills.removeLast();
		}
	}

	private void note(String text)
	{
		int now = client.getTickCount();
		for (Interaction in : interactions)
		{
			if (!in.closed && now - in.tick <= WINDOW_TICKS)
			{
				in.notes.add("+" + (now - in.tick) + "t " + text);
			}
		}
		for (Kill k : kills)
		{
			if (!k.closed && now - k.tick <= KILL_WINDOW_TICKS)
			{
				k.notes.add("+" + (now - k.tick) + "t " + text);
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType t = event.getType();
		if (t == ChatMessageType.GAMEMESSAGE || t == ChatMessageType.SPAM || t == ChatMessageType.MESBOX)
		{
			note("chat: " + anonymize(Text.removeTags(event.getMessage())));
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() == VarPlayerID.SLAYER_COUNT)
		{
			note("slayer task count -> " + event.getValue());
			int now = client.getTickCount();
			for (Kill k : kills)
			{
				if (!k.closed && now - k.tick <= 6 && event.getValue() < k.slayerCount)
				{
					k.onTaskSignal = true;
				}
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		Integer prev = lastXp.put(skill, event.getXp());
		if (prev == null || event.getXp() <= prev)
		{
			return;
		}
		int gain = event.getXp() - prev;
		int now = client.getTickCount();
		note("+" + gain + " " + skill.getName() + " xp");
		for (Interaction in : interactions)
		{
			if (!in.closed && now - in.tick <= WINDOW_TICKS)
			{
				in.xp.merge(skill, gain, Integer::sum);
				in.xpTick.putIfAbsent(skill, now - in.tick);
			}
		}
		if (skill == Skill.SLAYER)
		{
			for (Kill k : kills)
			{
				if (!k.closed && now - k.tick <= 6 && k.slayerCount <= 0)
				{
					k.onTaskSignal = true;
				}
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (!BAGS.contains(id))
		{
			return;
		}
		Map<Integer, Integer> now = counts(event.getItemContainer());
		Map<Integer, Integer> before = lastContainers.put(id, now);
		if (before == null)
		{
			return; // first sighting: baseline
		}
		Set<Integer> keys = new HashSet<>(now.keySet());
		keys.addAll(before.keySet());
		int tick = client.getTickCount();
		for (int item : keys)
		{
			int delta = now.getOrDefault(item, 0) - before.getOrDefault(item, 0);
			if (delta == 0)
			{
				continue;
			}
			String where = id == InventoryID.INV ? "inventory" : "bag " + id;
			note((delta > 0 ? "+" : "") + delta + " " + itemName(item) + " (" + item + ") " + where);
			if (delta > 0)
			{
				for (Interaction in : interactions)
				{
					if (!in.closed && tick - in.tick <= WINDOW_TICKS)
					{
						in.items.merge(item, delta, Integer::sum);
						in.itemTick.putIfAbsent(item, tick - in.tick);
					}
				}
			}
		}
	}

	private Map<Integer, Integer> counts(ItemContainer c)
	{
		Map<Integer, Integer> out = new HashMap<>();
		if (c != null)
		{
			for (Item i : c.getItems())
			{
				if (i != null && i.getId() > 0)
				{
					out.merge(itemManager.canonicalize(i.getId()), i.getQuantity(), Integer::sum);
				}
			}
		}
		return out;
	}

	String itemName(int id)
	{
		try
		{
			return itemManager.getItemComposition(id).getName();
		}
		catch (RuntimeException e)
		{
			return "item " + id;
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		TileItem item = event.getItem();
		WorldPoint at = event.getTile().getWorldLocation();
		int now = client.getTickCount();
		for (Kill k : kills)
		{
			if (!k.closed && now - k.tick <= KILL_WINDOW_TICKS && k.where != null && at != null && k.where.distanceTo2D(at) <= 3)
			{
				k.drops.add(item.getId());
				k.notes.add("+" + (now - k.tick) + "t drop: " + item.getQuantity() + " " + itemName(item.getId()) + " (" + item.getId() + ")");
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState s = event.getGameState();
		if (s == GameState.LOGGED_IN && (lastState == GameState.LOGGING_IN || lastState == GameState.HOPPING || lastState == GameState.LOGIN_SCREEN))
		{
			loginTick = client.getTickCount();
		}
		if (s == GameState.LOADING)
		{
			sceneObjects.clear();
			taskObjects.clear();
		}
		if (s == GameState.LOGGED_IN)
		{
			rescan = true;
		}
		if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING)
		{
			lastContainers.clear();
			lastXp.clear();
		}
		lastState = s;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int now = client.getTickCount();
		if (rescan)
		{
			rescan = false;
			rescanScene();
		}
		if (lastFilterTick < 0 || now - lastFilterTick >= 5)
		{
			filterObjects();
		}
		for (Interaction in : interactions)
		{
			if (!in.closed && now - in.tick > WINDOW_TICKS)
			{
				in.closed = true;
				in.progressAfter.putAll(progress(in.taskIds));
			}
		}
		for (Kill k : kills)
		{
			if (!k.closed && now - k.tick > KILL_WINDOW_TICKS)
			{
				k.closed = true;
				k.progressAfter.putAll(progress(k.taskIds));
			}
		}
		fights.values().removeIf(f -> now - f[3] > 200);
	}

	// --- Commands ------------------------------------------------------------------------------

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		String cmd = event.getCommand().toLowerCase();
		String[] args = event.getArguments();
		if (cmd.equals("taskaudit"))
		{
			chat("Auditing " + catalog.size() + " tasks against every NPC and object in the game (the client may pause a few seconds)...");
			new DebugAudit(this).run(MAX_NPC_ID, MAX_OBJECT_ID);
		}
		else if (cmd.equals("taskreload"))
		{
			loadCatalog();
			lastFilterTick = -1;
			chat("Reloaded " + catalog.size() + " tasks from " + catalogSource + (rules.ok() ? "" : " (rules unavailable: " + rules.error + ")"));
		}
		else if (cmd.equals("taskdebug"))
		{
			outlineMode = args.length == 0 ? "all" : args[0].toLowerCase();
			chat("Outlines: " + outlineMode + " (all, npcs, objects or off)");
		}
	}

	// --- Reports -------------------------------------------------------------------------------

	void report(Target tg)
	{
		String text;
		try
		{
			text = new DebugReport(this).write(tg, check(tg));
		}
		catch (RuntimeException e)
		{
			log.warn("Task debug report failed", e);
			text = "Report failed: " + e;
		}
		String name = tg.name == null ? tg.kind : tg.name.replaceAll("[^A-Za-z0-9]+", "_");
		save(name, text, "Debug report for " + tg.name + " copied to clipboard.");
	}

	void save(String what, String text, String done)
	{
		File dir = new File(RuneLite.RUNELITE_DIR, "chunkblazer-debug");
		File file = new File(dir, new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + "_" + what + ".txt");
		try
		{
			dir.mkdirs();
			Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
			chat(done + " Saved to .runelite/chunkblazer-debug/" + file.getName());
		}
		catch (Exception e)
		{
			log.warn("Task debug: couldn't save", e);
			log.info("Task debug output:\n{}", text);
			chat("Couldn't save (" + e.getMessage() + "); it's in the client log.");
		}
	}

	/**
	 * Reports must not identify the player: their name is replaced in any game message.
	 * (Reports also leave out file paths, the world, coordinates, the time and total XP.)
	 */
	String anonymize(String text)
	{
		Player me = client.getLocalPlayer();
		String name = me == null ? null : me.getName();
		if (text == null || name == null || name.isEmpty())
		{
			return text;
		}
		return text.replace(name, "[you]").replace(name.replace('\u00a0', ' '), "[you]");
	}

	void chat(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=00ffff>[Task Debug]</col> " + text, null);
	}

	Player me()
	{
		return client.getLocalPlayer();
	}
}
