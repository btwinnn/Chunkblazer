package com.chunkblazer.debug;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredObject;
import com.chunkblazer.TargetNpc;
import com.chunkblazer.debug.TaskDebugPlugin.Entry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.client.util.Text;

/**
 * ::taskaudit. Every task's NPC and object ids against every NPC and object definition in the
 * game: ids that don't exist or are a different thing, names nothing in the game has, and
 * same-name things the task doesn't list. Writes catalog-audit to .runelite/chunkblazer-debug/.
 */
final class DebugAudit
{
	private final TaskDebugPlugin p;

	DebugAudit(TaskDebugPlugin p)
	{
		this.p = p;
	}

	/** Name, level and options of one definition. */
	private static final class Def
	{
		String name;
		int level;
		String[] actions;
	}

	void run(int maxNpc, int maxObject)
	{
		Map<Integer, Def> npcs = new HashMap<>();
		Map<String, List<Integer>> npcIdsByName = new HashMap<>();
		for (int id = 0; id < maxNpc; id++)
		{
			NPCComposition d;
			try
			{
				d = p.client.getNpcDefinition(id);
			}
			catch (RuntimeException e)
			{
				continue;
			}
			if (d == null || d.getName() == null || d.getName().equals("null"))
			{
				continue;
			}
			Def def = new Def();
			def.name = Text.removeTags(d.getName());
			def.level = d.getCombatLevel();
			def.actions = d.getActions();
			npcs.put(id, def);
			npcIdsByName.computeIfAbsent(def.name.toLowerCase(), k -> new ArrayList<>()).add(id);
		}
		Map<Integer, Def> objects = new HashMap<>();
		Map<String, List<Integer>> objIdsByName = new HashMap<>();
		for (int id = 0; id < maxObject; id++)
		{
			ObjectComposition d;
			try
			{
				d = p.client.getObjectDefinition(id);
			}
			catch (RuntimeException e)
			{
				continue;
			}
			if (d == null || d.getName() == null || d.getName().equals("null"))
			{
				continue;
			}
			Def def = new Def();
			def.name = Text.removeTags(d.getName());
			def.actions = d.getActions();
			objects.put(id, def);
			objIdsByName.computeIfAbsent(def.name.toLowerCase(), k -> new ArrayList<>()).add(id);
		}

		StringBuilder problems = new StringBuilder();
		int checked = 0;
		int bad = 0;
		Map<String, Integer> tally = new TreeMap<>();
		for (Entry e : p.catalog.values())
		{
			NuzlockeTask t = e.task;
			List<String> issues = new ArrayList<>();
			boolean any = false;
			TargetNpc target = t.getTargetNpc();
			if (target != null)
			{
				any = true;
				String want = target.getName() == null ? "" : target.getName().trim();
				int level = TaskDebugPlugin.taskLevel(t);
				List<Integer> listed = target.getNpcIds() == null ? Collections.emptyList() : target.getNpcIds();
				check(issues, "NPC", want, listed, npcs, npcIdsByName, level, t, true);
			}
			if (t.getRequiredObjects() != null)
			{
				for (RequiredObject ro : t.getRequiredObjects())
				{
					if (ro == null)
					{
						continue;
					}
					any = true;
					String want = ro.getObject() == null ? "" : ro.getObject().trim();
					List<Integer> listed = ro.getObjectIds() == null ? Collections.emptyList() : ro.getObjectIds();
					check(issues, "object", want, listed, objects, objIdsByName, -1, t, false);
				}
			}
			if (!any)
			{
				continue;
			}
			checked++;
			if (issues.isEmpty())
			{
				continue;
			}
			bad++;
			problems.append(t.getTaskId()).append("  \"").append(t.getName()).append("\"  [").append(t.getCompletionType())
				.append("]  in ").append(String.join("; ", e.chunks)).append('\n');
			for (String i : issues)
			{
				problems.append("    - ").append(i).append('\n');
				tally.merge(i.replaceAll("\".*?\"", "\"...\"").replaceAll("\\d+", "#").replaceAll(":.*", ""), 1, Integer::sum);
			}
		}

		StringBuilder r = new StringBuilder();
		r.append("=== ChunkBlazer catalog audit (NPCs and objects) ===\n");
		r.append("Catalog: ").append(p.catalogSource).append('\n');
		r.append("NPC definitions: ").append(npcs.size()).append("   Object definitions: ").append(objects.size())
			.append("   Tasks with an NPC/object: ").append(checked).append("   with something to look at: ").append(bad).append("\n\n");
		r.append("Kinds of issue (counts):\n");
		tally.forEach((k, v) -> r.append("  ").append(v).append("  ").append(k).append('\n'));
		r.append("\nNotes: \"no name\" ids are usually hidden base forms (multiloc objects, NPCs shown as another form).\n")
			.append("\"Same-name ... not listed\" may be deliberate (a task meant for one place); check those by hand.\n")
			.append("Objects: ChunkBlazer matches the scene's BASE id, so a task listing only a multiloc's shown form never matches.\n");
		r.append("\n=== Tasks to look at ===\n").append(problems);
		p.save("catalog-audit", r.toString(), "Audit done: " + bad + " of " + checked + " tasks have something to look at.");
	}

	private void check(List<String> issues, String what, String want, List<Integer> listed, Map<Integer, Def> defs,
		Map<String, List<Integer>> idsByName, int level, NuzlockeTask t, boolean npc)
	{
		int real = 0;
		for (int id : listed)
		{
			Def d = defs.get(id);
			if (d == null)
			{
				issues.add(what + " id " + id + " has no name in the game (a hidden base form, or doesn't exist)");
				continue;
			}
			real++;
			if (!want.isEmpty() && !d.name.equalsIgnoreCase(want) && !want.contains("#"))
			{
				issues.add(what + " id " + id + " is \"" + d.name + "\"" + (npc ? " (lvl " + d.level + ")" : "") + ", not \"" + want + "\"");
			}
		}
		if (listed.isEmpty())
		{
			issues.add("no " + what + " ids listed");
		}
		else if (real == 0)
		{
			issues.add("none of its " + what + " ids is a named " + what + " in the game");
		}
		String key = want.contains("#") ? want.substring(0, want.indexOf('#')).trim().toLowerCase() : want.toLowerCase();
		if (!key.isEmpty() && !idsByName.containsKey(key))
		{
			issues.add("no " + what + " in the game is named \"" + want + "\"");
		}
		List<String> unlisted = new ArrayList<>();
		for (int id : idsByName.getOrDefault(key, Collections.emptyList()))
		{
			Def d = defs.get(id);
			if (!listed.contains(id) && TaskDebugPlugin.fits(t, d.actions, npc) && (level < 0 || d.level == level))
			{
				unlisted.add(id + (npc ? " (lvl " + d.level + ")" : ""));
			}
		}
		if (!unlisted.isEmpty())
		{
			issues.add("same-name " + what + "s not listed" + (level < 0 ? "" : " at level " + level) + ": "
				+ String.join(", ", unlisted.subList(0, Math.min(40, unlisted.size()))) + (unlisted.size() > 40 ? ", ..." : ""));
		}
	}
}
