package com.chunkblazer.debug;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TaskTargetExtras;
import com.chunkblazer.TaskTargetHighlighter;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Text;

/**
 * ChunkBlazer's own target rules, called by reflection (they're package-private), so the debug
 * mode matches NPCs and objects exactly the way the plugin does: TaskTargetHighlighter's
 * station/node/fishing rules and boss entrances, and TaskTargetExtras' ids and name rules.
 * Works without ChunkBlazer running: the tables it normally loads on startup are loaded here.
 */
@Slf4j
final class Rules
{
	/** One rule a task is matched by, with where it came from. */
	static final class Ref
	{
		final Object rule;
		final String key;
		final String from;

		Ref(Object rule, String key, String from)
		{
			this.rule = rule;
			this.key = key;
			this.from = from;
		}
	}

	private Method objectRulesFor;
	private Method npcRuleFor;
	private Method xObjectIds;
	private Method xNpcIds;
	private Method xObjectRules;
	private Method xNpcRule;
	private Method xLoad;
	private Method ruleMatches;
	private Constructor<?> infoCtor;
	private Field ruleKey;
	private Map<Integer, Object> bossEntrances;
	private Set<Integer> notUsable;
	private Field entranceRegion;
	private Method entranceIncludes;
	String error;

	@SuppressWarnings("unchecked")
	Rules(Gson gson)
	{
		try
		{
			Class<?> h = TaskTargetHighlighter.class;
			Class<?> x = TaskTargetExtras.class;
			objectRulesFor = method(h, "objectRulesFor", NuzlockeTask.class);
			npcRuleFor = method(h, "npcRuleFor", NuzlockeTask.class);
			xObjectIds = method(x, "objectIds", NuzlockeTask.class);
			xNpcIds = method(x, "npcIds", NuzlockeTask.class);
			xObjectRules = method(x, "objectRules", NuzlockeTask.class);
			xNpcRule = method(x, "npcRule", NuzlockeTask.class);
			xLoad = method(x, "load", String.class, Gson.class);

			Class<?> rule = inner(h, "Rule");
			Class<?> info = inner(h, "Info");
			ruleMatches = method(rule, "matches", info);
			ruleKey = rule.getDeclaredField("key");
			ruleKey.setAccessible(true);
			infoCtor = info.getDeclaredConstructor(String.class, Set.class);
			infoCtor.setAccessible(true);

			bossEntrances = (Map<Integer, Object>) staticField(h, "BOSS_ENTRANCES");
			Map<String, String[]> fish = (Map<String, String[]>) staticField(h, "FISH");
			Set<Integer> bossRegions = (Set<Integer>) staticField(h, "BOSS_REGIONS");
			notUsable = (Set<Integer>) staticField(h, "NOT_USABLE_OBJECT_IDS");
			// ChunkBlazer fills these when its highlighter is built. Without it, fill them here.
			if (fish.isEmpty() || bossEntrances.isEmpty())
			{
				Object tables = method(h, "loadTables", Gson.class).invoke(null, gson);
				Map<Integer, Object> entrances = (Map<Integer, Object>) field(tables, "bossEntrances");
				Map<String, String[]> fishTable = (Map<String, String[]>) field(tables, "fish");
				if (bossEntrances.isEmpty() && entrances != null)
				{
					bossEntrances.putAll(entrances);
				}
				if (fish.isEmpty() && fishTable != null)
				{
					fish.putAll(fishTable);
				}
			}
			Class<?> entrance = inner(h, "Entrance");
			entranceRegion = entrance.getDeclaredField("region");
			entranceRegion.setAccessible(true);
			entranceIncludes = method(entrance, "includes", NuzlockeTask.class);
			for (Object e : bossEntrances.values())
			{
				bossRegions.add(entranceRegion.getInt(e));
			}
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			error = e.toString();
			log.warn("Task debug: couldn't reach ChunkBlazer's target rules; only ids and names will be checked", e);
		}
	}

	boolean ok()
	{
		return error == null;
	}

	/** Load the catalog sidecar _Target_Extras.json into TaskTargetExtras (what ChunkBlazer does on catalog load). */
	void loadExtras(String json, Gson gson)
	{
		if (ok() && json != null)
		{
			call(xLoad, json, gson);
		}
	}

	/** Plugin rules for objects: the station or node a task is done at. Extras only count for tasks without own ids. */
	List<Ref> objectRules(NuzlockeTask t, boolean ownTargets)
	{
		List<Ref> out = new ArrayList<>();
		if (!ok())
		{
			return out;
		}
		for (Object r : list(call(objectRulesFor, t)))
		{
			out.add(new Ref(r, key(r), "plugin rule"));
		}
		if (!ownTargets)
		{
			for (Object r : list(call(xObjectRules, t)))
			{
				out.add(new Ref(r, key(r), "extras rule"));
			}
		}
		return out;
	}

	List<Ref> npcRules(NuzlockeTask t, boolean ownTargets)
	{
		List<Ref> out = new ArrayList<>();
		if (!ok())
		{
			return out;
		}
		Object r = call(npcRuleFor, t);
		if (r != null)
		{
			out.add(new Ref(r, key(r), "plugin rule"));
		}
		if (!ownTargets)
		{
			Object x = call(xNpcRule, t);
			if (x != null)
			{
				out.add(new Ref(x, key(x), "extras rule"));
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	Set<Integer> extraObjectIds(NuzlockeTask t)
	{
		Object ids = ok() ? call(xObjectIds, t) : null;
		return ids == null ? Collections.emptySet() : (Set<Integer>) ids;
	}

	@SuppressWarnings("unchecked")
	Set<Integer> extraNpcIds(NuzlockeTask t)
	{
		Object ids = ok() ? call(xNpcIds, t) : null;
		return ids == null ? Collections.emptySet() : (Set<Integer>) ids;
	}

	/** ChunkBlazer's view of a thing: its lowercase name and right-click options. Build once, test many rules. */
	Object info(String rawName, String[] rawActions)
	{
		try
		{
			String name = rawName == null ? "" : Text.removeTags(rawName).toLowerCase();
			Set<String> actions = new HashSet<>();
			if (rawActions != null)
			{
				for (String a : rawActions)
				{
					if (a != null)
					{
						actions.add(Text.removeTags(a).toLowerCase());
					}
				}
			}
			return infoCtor.newInstance(name, actions);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return null;
		}
	}

	/** Whether a rule matches that thing (the plugin's own test). */
	boolean matches(Ref ref, Object info)
	{
		try
		{
			return info != null && (Boolean) ruleMatches.invoke(ref.rule, info);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return false;
		}
	}

	boolean notUsable(int objectId)
	{
		return notUsable != null && notUsable.contains(objectId);
	}

	/** The boss chunk an entrance object leads to, or -1. */
	int entranceRegion(int objectId)
	{
		Object e = bossEntrances == null ? null : bossEntrances.get(objectId);
		try
		{
			return e == null ? -1 : entranceRegion.getInt(e);
		}
		catch (IllegalAccessException ex)
		{
			return -1;
		}
	}

	boolean entranceIncludes(int objectId, NuzlockeTask t)
	{
		Object e = bossEntrances == null ? null : bossEntrances.get(objectId);
		Object r = e == null ? null : call(entranceIncludes, e, t);
		return Boolean.TRUE.equals(r);
	}

	Collection<Integer> entranceObjects()
	{
		return bossEntrances == null ? Collections.emptySet() : bossEntrances.keySet();
	}

	// --- reflection ---

	private String key(Object rule)
	{
		try
		{
			return String.valueOf(ruleKey.get(rule));
		}
		catch (IllegalAccessException e)
		{
			return "?";
		}
	}

	private static List<?> list(Object o)
	{
		return o instanceof List ? (List<?>) o : Collections.emptyList();
	}

	private static Object call(Method m, Object... args)
	{
		try
		{
			boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
			if (isStatic)
			{
				return m.invoke(null, args);
			}
			Object[] rest = new Object[args.length - 1];
			System.arraycopy(args, 1, rest, 0, rest.length);
			return m.invoke(args[0], rest);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return null;
		}
	}

	private static Method method(Class<?> c, String name, Class<?>... types) throws NoSuchMethodException
	{
		Method m = c.getDeclaredMethod(name, types);
		m.setAccessible(true);
		return m;
	}

	private static Class<?> inner(Class<?> outer, String simple) throws ClassNotFoundException
	{
		for (Class<?> c : outer.getDeclaredClasses())
		{
			if (c.getSimpleName().equals(simple))
			{
				return c;
			}
		}
		throw new ClassNotFoundException(outer.getName() + "$" + simple);
	}

	private static Object staticField(Class<?> c, String name) throws ReflectiveOperationException
	{
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(null);
	}

	private static Object field(Object owner, String name) throws ReflectiveOperationException
	{
		Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(owner);
	}
}
