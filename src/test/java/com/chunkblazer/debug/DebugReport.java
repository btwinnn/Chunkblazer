package com.chunkblazer.debug;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RequiredItem;
import com.chunkblazer.RequiredObject;
import com.chunkblazer.TargetNpc;
import com.chunkblazer.TaskConstraints;
import com.chunkblazer.debug.TaskDebugPlugin.Check;
import com.chunkblazer.debug.TaskDebugPlugin.Entry;
import com.chunkblazer.debug.TaskDebugPlugin.Interaction;
import com.chunkblazer.debug.TaskDebugPlugin.Kill;
import com.chunkblazer.debug.TaskDebugPlugin.Match;
import com.chunkblazer.debug.TaskDebugPlugin.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Writes the "Debug report" for one NPC or object: a MOST LIKELY section first, then the
 * evidence behind it. Each task gets checked three ways:
 *  1. the catalog: does it list this thing's id (or match it by rule), or only name it?
 *  2. ChunkBlazer (if running): unlocked, rolled, active, tracked by a module, in the menu?
 *  3. this session: what happened when you used it (XP, items, chat, Slayer count, progress).
 * When there's no evidence, the usual failure point for that kind of task is given instead.
 */
final class DebugReport
{
	private static final int BUG = 3;
	private static final int CAUSE = 2;
	private static final int INFO = 1;
	private static final Set<String> SKILLING = new HashSet<>(Arrays.asList("COOKING", "CRAFTING", "SMITHING", "MINING",
		"WOODCUTTING", "FISHING", "FLETCHING", "HERBLORE", "RUNECRAFTING", "HUNTER"));

	private static final class Verdict
	{
		final int level;
		final String task;
		final String text;

		final String taskName;

		Verdict(int level, String task, String taskName, String text)
		{
			this.level = level;
			this.task = task;
			this.taskName = taskName;
			this.text = text;
		}
	}

	private final TaskDebugPlugin p;
	private final List<Verdict> verdicts = new ArrayList<>();
	private boolean running;

	DebugReport(TaskDebugPlugin p)
	{
		this.p = p;
	}

	String write(Target tg, Check c)
	{
		running = p.resolve();
		List<Interaction> ins = p.interactions.stream().filter(i -> same(tg, i.name, i.ids)).collect(Collectors.toList());
		List<Kill> ks = tg.npc == null ? Collections.emptyList()
			: p.kills.stream().filter(k -> same(tg, k.name, k.ids)).collect(Collectors.toList());

		StringBuilder tasks = new StringBuilder();
		for (Match m : c.matches)
		{
			int before = verdicts.size();
			diagnose(m, tg, c, ins, ks);
			describe(tasks, m, c, verdicts.subList(before, verdicts.size()));
		}

		StringBuilder r = new StringBuilder();
		WorldPoint wp = tg.where;
		int region = wp == null ? -1 : wp.getRegionID();
		r.append("CHUNKBLAZER TASK DEBUG REPORT\n").append(LINE);
		r.append(tg.name == null ? "(no name)" : tg.name).append("   (").append(tg.kind)
			.append(tg.level > 0 ? ", level " + tg.level : "").append(")   ")
			.append(c.matches.size()).append(c.matches.size() == 1 ? " task" : " tasks").append('\n');
		r.append("Region ").append(region);
		if (running && region >= 0)
		{
			r.append(" ").append(p.cb.getRegionName(region)).append(p.cb.isRegionUnlocked(region) ? " (unlocked)" : " (locked)");
		}
		r.append('\n');
		r.append(running ? "ChunkBlazer was running, so unlocks, rolls, progress and menus were checked too.\n"
			: "ChunkBlazer was NOT running: only the catalog was checked (no progress, unlocks or menus).\n");
		if (!p.rules.ok())
		{
			r.append("Warning: ChunkBlazer's target rules couldn't be loaded, so only ids and names were checked.\n");
		}

		// --- What's wrong ---
		r.append("\nWHAT'S WRONG\n").append(LINE);
		long gaps = c.matches.stream().filter(m -> m.gap).count();
		if (c.matches.isEmpty())
		{
			wrap(r, "No task in the catalog points at this " + tg.kind.toLowerCase() + " (by id, rule or name). If one should,"
				+ " its ids or rule are missing.", 0);
		}
		else if (gaps == 0)
		{
			wrap(r, "Ids and names are fine: " + (c.matches.size() == 1 ? "its task matches" : "all " + c.matches.size() + " tasks match")
				+ " this " + tg.kind.toLowerCase() + (c.rulesOnly() ? " (by name/option rule, no ids)." : "."), 0);
		}
		else
		{
			wrap(r, "ID PROBLEM: " + gaps + " of " + c.matches.size() + " tasks name this " + tg.kind.toLowerCase()
				+ " but don't list its id, so they can never count here (fix below).", 0);
		}
		List<Verdict> ranked = new ArrayList<>(verdicts);
		ranked.sort(Comparator.comparingInt((Verdict v) -> -v.level));
		Set<String> said = new HashSet<>();
		int n = 0;
		List<Verdict> notes = new ArrayList<>();
		for (Verdict v : ranked)
		{
			if (!said.add(v.text))
			{
				continue;
			}
			if (v.level == INFO)
			{
				notes.add(v);
				continue;
			}
			if (n >= 6)
			{
				continue;
			}
			n++;
			r.append('\n').append(n).append(". ").append(v.level == BUG ? "LIKELY BUG" : "PROBABLE CAUSE")
				.append(v.taskName == null ? "" : " - " + v.taskName).append('\n');
			wrap(r, v.text, 3);
		}
		if (n == 0 && !c.matches.isEmpty())
		{
			r.append('\n');
			wrap(r, "Nothing wrong found. Use it with debug mode on (attack, chop, cook, steal...), then report again"
				+ " so there's evidence to check.", 0);
		}
		if (!notes.isEmpty())
		{
			r.append("\nNotes:\n");
			for (Verdict v : notes)
			{
				wrap(r, "- " + (v.taskName == null ? "" : v.taskName + ": ") + v.text, 2);
			}
		}

		// --- Tasks ---
		if (!c.matches.isEmpty())
		{
			r.append("\nTASKS\n").append(LINE).append(tasks);
		}

		// --- This session ---
		r.append("\nWHAT HAPPENED THIS SESSION (newest first)\n").append(LINE);
		int shown = 0;
		for (Interaction in : ins)
		{
			if (shown++ >= 6)
			{
				break;
			}
			interaction(r, in);
		}
		for (Kill k : ks)
		{
			if (shown++ >= 12)
			{
				break;
			}
			kill(r, k);
		}
		if (shown == 0)
		{
			wrap(r, "Nothing yet. Use it with debug mode on, then report again.", 0);
		}

		// --- Raw details for whoever fixes it ---
		r.append("\nDEV DETAILS\n").append(LINE);
		r.append("Catalog: ").append(p.catalogSource).append(", ").append(p.catalog.size()).append(" tasks\n");
		r.append("Ids: base ").append(tg.baseId).append(", shown as ").append(tg.shownId).append(", all ").append(tg.ids)
			.append(tg.npc == null ? "  (objects match by base id)" : "  (NPCs match by any)").append('\n');
		r.append("Options: ").append(tg.actions == null ? "none"
			: Arrays.stream(tg.actions).filter(x -> x != null && !x.isEmpty()).collect(Collectors.joining(", "))).append('\n');
		if (running)
		{
			r.append("Menu: ").append(c.expected.size()).append(" active task(s) for it, ChunkBlazer's lookup finds ").append(c.shown.size());
			r.append(c.missing.isEmpty() && c.extra.isEmpty() ? " (matches)\n" : "\n");
			c.missing.forEach(t -> r.append("  missing from menu: ").append(t.getName()).append(" (").append(t.getTaskId()).append(")\n"));
			c.extra.forEach(t -> r.append("  in menu but not linked in the catalog: ").append(t.getName()).append(" (").append(t.getTaskId()).append(")\n"));
		}
		if (c.matches.stream().anyMatch(m -> "kill".equals(kind(m.e.task))))
		{
			r.append("Slayer: task count ").append(p.client.getVarpValue(VarPlayerID.SLAYER_COUNT))
				.append(", task target ").append(p.client.getVarpValue(VarPlayerID.SLAYER_TARGET));
			if (running)
			{
				Object kill = killModule();
				r.append(", kill module: previousSlayerCount=").append(TaskDebugPlugin.fieldOrNull(kill, "previousSlayerCount"))
					.append(" lastSlayerCountDropTick=").append(TaskDebugPlugin.fieldOrNull(kill, "lastSlayerCountDropTick"))
					.append(" lastSlayerXpGainTick=").append(TaskDebugPlugin.fieldOrNull(kill, "lastSlayerXpGainTick"))
					.append(" lastLoginTick=").append(TaskDebugPlugin.fieldOrNull(kill, "lastLoginTick"));
			}
			r.append('\n');
		}
		r.append("Tick now: ").append(p.client.getTickCount()).append('\n');
		r.append("Event log (ticks after each use):\n");
		int logged = 0;
		for (Interaction in : ins)
		{
			if (logged++ >= 6)
			{
				break;
			}
			r.append("  \"").append(in.option).append("\" ").append(in.name).append(' ').append(in.ids).append(" at tick ").append(in.tick).append('\n');
			in.notes.stream().limit(20).forEach(x -> r.append("     ").append(x).append('\n'));
		}
		for (Kill k : ks)
		{
			if (logged++ >= 12)
			{
				break;
			}
			r.append("  kill ").append(k.name).append(' ').append(k.ids).append(" at tick ").append(k.tick).append('\n');
			k.notes.stream().limit(20).forEach(x -> r.append("     ").append(x).append('\n'));
		}
		if (logged == 0)
		{
			r.append("  (empty)\n");
		}
		r.append("\nNo names, file paths, world, coordinates or times are included in this report.\n");
		return r.toString();
	}

	// --- Diagnosis --------------------------------------------------------------------------

	private void add(int level, NuzlockeTask t, String text)
	{
		verdicts.add(new Verdict(level, t == null ? null : t.getTaskId(), t == null ? null : t.getName(), text));
	}

	private void diagnose(Match m, Target tg, Check c, List<Interaction> ins, List<Kill> ks)
	{
		NuzlockeTask t = m.e.task;
		String id = t.getTaskId();
		String kind = kind(t);
		int structural = verdicts.size();

		if (m.gap)
		{
			add(BUG, t, "ID/NAMING: the task names this " + tg.kind.toLowerCase() + " (\"" + tg.name + "\") but lists none of its ids "
				+ tg.ids + ", so it can never count here. Fix: " + m.fix + ".");
			long kills = ks.size();
			if (kills > 0)
			{
				add(INFO, t, "you killed it " + kills + " time(s) this session; none could count because of the id gap.");
			}
			return;
		}

		NuzlockeTask live = null;
		if (running)
		{
			live = p.live(id);
			if (p.cb.isTaskCompleted(id))
			{
				add(INFO, t, "already completed on this account.");
				return;
			}
			if (p.archived().contains(id))
			{
				add(INFO, t, "archived (set aside) by the player: no outline or menu entry, by design.");
				return;
			}
			if (live == null)
			{
				notActive(t, m.e);
			}
			else
			{
				if (p.moduleFor(id) == null && p.cb.getActiveTasks().contains(live))
				{
					add(BUG, t, "active, but NO module tracks completion type \"" + t.getCompletionType()
						+ "\", so it can never complete. Check TaskModuleManager's type list.");
				}
				if (c.missing.stream().anyMatch(x -> x.getTaskId().equals(id)))
				{
					add(BUG, t, "active, but missing from ChunkBlazer's own lookup for this " + tg.kind.toLowerCase()
						+ " (no outline, no Tasks menu entry). It should match by " + m.how + ": highlighter index/rule bug.");
				}
				if (!p.cb.meetsLevelRequirement(live))
				{
					add(INFO, t, "you're below its level requirement (" + t.getLevelRequirement() + "): shown greyed out.");
				}
			}
		}

		// Evidence from this session.
		List<Interaction> mine = ins.stream().filter(i -> i.taskIds.contains(id)).collect(Collectors.toList());
		List<Kill> myKills = ks.stream().filter(k -> k.taskIds.contains(id)).collect(Collectors.toList());
		for (Interaction in : mine)
		{
			if (counted(in.progressBefore, in.progressAfter, id))
			{
				add(INFO, t, "it COUNTED this session (progress " + show(in.progressBefore.get(id)) + " -> " + show(in.progressAfter.get(id))
					+ "). Can't reproduce here: compare the reporter's chunk, levels, gear and inventory.");
				return;
			}
		}
		for (Kill k : myKills)
		{
			if (counted(k.progressBefore, k.progressAfter, id))
			{
				add(INFO, t, "a kill COUNTED this session (progress " + show(k.progressBefore.get(id)) + " -> " + show(k.progressAfter.get(id))
					+ "). Can't reproduce here: compare the reporter's setup (on task? solo? gear?).");
				return;
			}
		}
		boolean evidence = false;
		switch (kind)
		{
			case "kill":
				evidence = killEvidence(t, myKills, live != null);
				break;
			case "skill":
			case "obtain":
				evidence = itemEvidence(t, mine, live != null, kind.equals("skill"));
				break;
			case "pickpocket":
			case "steal":
			case "agility":
			case "farming":
			case "construction":
			case "firemaking":
			case "prayer":
				evidence = xpEvidence(t, mine, live != null, kind);
				break;
			case "talk":
				if (!mine.isEmpty())
				{
					evidence = true;
					Interaction in = mine.get(0);
					if (!in.closed)
					{
						add(INFO, t, "you just talked to it; report again in a few seconds to see if it counted.");
					}
					else if (live != null)
					{
						add(BUG, t, "you talked to it and it didn't count -> NpcDialogueModule didn't recognise the dialogue"
							+ " (check the task's NPC id/name against " + tg.ids + ").");
					}
				}
				break;
			default:
				break;
		}

		// No structural problem and no evidence: the usual failure point for this kind of task.
		if (verdicts.size() == structural && !evidence)
		{
			TaskConstraints tc = t.getConstraints();
			boolean gates = gated(t) || (tc != null && (tc.hasTimeLimit() || tc.hasDroppedItemConstraint() || tc.hasEquipmentConstraints()));
			add(gates || !kind.equals("kill") ? CAUSE : INFO, t, prior(t, kind));
		}
	}

	private void notActive(NuzlockeTask t, Entry e)
	{
		if (e.regions.isEmpty())
		{
			add(INFO, t, "not active right now (no chunk region in the catalog).");
			return;
		}
		boolean unlocked = false;
		boolean rolled = false;
		List<String> names = new ArrayList<>();
		for (int region : e.regions)
		{
			names.add(p.cb.getRegionName(region) + " [" + region + "]");
			if (p.cb.isRegionUnlocked(region))
			{
				unlocked = true;
				if (p.cb.getRolledTasksForRegion(region).contains(t.getTaskId()))
				{
					rolled = true;
				}
			}
		}
		boolean faceDown = p.cb.getUnrevealedTaskIds().contains(t.getTaskId());
		if (!unlocked)
		{
			add(CAUSE, t, "not active: its chunk " + names + " isn't unlocked. Not a bug.");
		}
		else if (faceDown)
		{
			add(CAUSE, t, "rolled, but its card hasn't been flipped yet. Not a bug.");
		}
		else if (!rolled)
		{
			add(CAUSE, t, "its chunk is unlocked but this task wasn't rolled there (only 4-5 of a chunk's tasks are). Not a bug.");
		}
		else
		{
			add(BUG, t, "rolled in an unlocked chunk, not completed, not face-down, yet NOT active -> loadActiveTasks/registration bug.");
		}
	}

	private boolean killEvidence(NuzlockeTask t, List<Kill> kills, boolean active)
	{
		if (kills.isEmpty())
		{
			return false;
		}
		Kill k = kills.get(0);
		if (!k.closed)
		{
			add(INFO, t, "your last kill was " + (p.client.getTickCount() - k.tick) + " ticks ago; report again after "
				+ TaskDebugPlugin.KILL_WINDOW_TICKS + " ticks to see whether it counted.");
			return true;
		}
		TaskConstraints tc = t.getConstraints();
		boolean found = false;
		if (gated(t))
		{
			found = true;
			if (k.slayerCount <= 0 && !k.onTaskSignal)
			{
				add(CAUSE, t, "SLAYER GATE: the kill was seen, but you weren't on a Slayer task (task count 0 at the kill)."
					+ " That's why it didn't count - player error, not a bug.");
			}
			else if (!k.onTaskSignal)
			{
				add(BUG, t, "SLAYER GATE: on a task (count " + k.slayerCount + ") but the count didn't drop and no Slayer XP came within 6 ticks"
					+ " of the kill -> either this monster isn't the player's current Slayer task, or the on-task check missed it.");
			}
			else if (active)
			{
				add(BUG, t, "SLAYER GATE passed (count dropped / Slayer XP), the id matched, but no credit -> NPCKillModule bug (check the client log).");
			}
		}
		if (tc != null && tc.hasTimeLimit())
		{
			found = true;
			int limit = tc.getTimeInTicks();
			if (k.ticksSinceLogin >= 0 && k.ticksSinceLogin < 50)
			{
				add(CAUSE, t, "TIMED: the fight started " + k.ticksSinceLogin + " ticks after login; timed kills need 50+ (30s).");
			}
			else if (k.hpAtFirstHit < k.maxHp)
			{
				add(CAUSE, t, "TIMED: you weren't at full health when the fight started (" + k.hpAtFirstHit + "/" + k.maxHp + ").");
			}
			else if (k.fightTicks > limit)
			{
				add(CAUSE, t, "TIMED: the fight took " + k.fightTicks + " ticks from your first hit; the limit is " + limit + ".");
			}
			else if (active)
			{
				add(BUG, t, "TIMED: " + k.fightTicks + "/" + limit + " ticks, full health, fresh login, still no credit -> check the solo/cannon"
					+ " rules (another player or a cannon hit it?) or when the timer starts.");
			}
		}
		if (tc != null && tc.hasDroppedItemConstraint())
		{
			found = true;
			List<Integer> want = tc.getDroppedItemIds();
			boolean dropped = k.drops.stream().anyMatch(want::contains);
			if (!dropped)
			{
				add(CAUSE, t, "DROP: needs " + tc.getDroppedItem() + " " + want + " to drop; it wasn't seen in the loot.");
			}
			else if (active)
			{
				add(BUG, t, "DROP: " + tc.getDroppedItem() + " dropped but no credit -> drop detection bug.");
			}
		}
		if (tc != null && tc.hasEquipmentConstraints())
		{
			found = true;
			add(CAUSE, t, "GEAR: this task has equipment rules" + (tc.isNoEquipment() || tc.isEquipNothing() ? " (nothing equipped)" : "")
				+ "; a kill in the wrong gear doesn't count.");
		}
		if (!found)
		{
			if (active)
			{
				add(BUG, t, "the kill was yours, the id matched and the task has no gates, yet no credit -> NPCKillModule bug (check the client log).");
			}
			else if (!running)
			{
				add(INFO, t, "ChunkBlazer isn't running; your kill shows this task WOULD match by id.");
			}
		}
		return true;
	}

	private boolean itemEvidence(NuzlockeTask t, List<Interaction> ins, boolean active, boolean skilling)
	{
		Interaction in = ins.stream().filter(i -> i.closed).findFirst().orElse(null);
		if (in == null)
		{
			if (!ins.isEmpty())
			{
				add(INFO, t, "you just used it; report again in a few seconds to see the result.");
				return true;
			}
			return false;
		}
		Set<Integer> want = wanted(t);
		Set<Integer> got = new LinkedHashSet<>(in.items.keySet());
		Set<Integer> gotWanted = new LinkedHashSet<>(got);
		gotWanted.retainAll(want);
		Skill skill = skilling ? skillOf(t) : null;
		String what = itemNames(want);
		if (skilling && skill != null)
		{
			Integer xp = in.xp.get(skill);
			if (xp == null)
			{
				add(CAUSE, t, skill.getName().toUpperCase() + ": no " + skill.getName() + " XP after \"" + in.option + "\" -> the action failed or was"
					+ " interrupted, or this isn't the thing that makes " + what + ".");
			}
			else if (gotWanted.isEmpty() && !got.isEmpty())
			{
				add(BUG, t, "ITEM ID: you got " + itemNames(got) + " with the " + skill.getName() + " XP, but the task wants " + what
					+ " -> the task's item ids are wrong or miss this variant.");
			}
			else if (gotWanted.isEmpty())
			{
				add(BUG, t, "ITEM WENT ELSEWHERE: +" + xp + " " + skill.getName() + " XP but nothing reached the inventory or a watched bag."
					+ " The item went into something ChunkBlazer doesn't count (meat pouch, other pouch/sack, noted to bank, dropped).");
			}
			else
			{
				int xpAt = in.xpTick.getOrDefault(skill, -1);
				int itemAt = gotWanted.stream().mapToInt(i -> in.itemTick.getOrDefault(i, -1)).min().orElse(-1);
				if (xpAt != itemAt)
				{
					add(BUG, t, "TIMING: " + skill.getName() + " XP arrived at +" + xpAt + "t and " + what + " at +" + itemAt
						+ "t. ObtainModule only counts an item that arrives on the SAME tick as the XP.");
				}
				else if (active)
				{
					add(BUG, t, "XP and " + what + " arrived on the same tick and it still didn't count -> ObtainModule bug (bank open? snapshot?).");
				}
				else if (!running)
				{
					add(INFO, t, "XP and the item arrived together, so ChunkBlazer should count it (it isn't running to confirm).");
				}
			}
		}
		else
		{
			if (gotWanted.isEmpty())
			{
				add(CAUSE, t, "you didn't get " + what + " from this" + (got.isEmpty() ? "." : " (got " + itemNames(got) + ")."));
			}
			else if (active)
			{
				add(BUG, t, "you got " + what + " but progress didn't move -> ObtainModule owned-count bug (wrong id variant? noted?).");
			}
		}
		return true;
	}

	private boolean xpEvidence(NuzlockeTask t, List<Interaction> ins, boolean active, String kind)
	{
		Interaction in = ins.stream().filter(i -> i.closed).findFirst().orElse(null);
		if (in == null)
		{
			if (!ins.isEmpty())
			{
				add(INFO, t, "you just used it; report again in a few seconds to see the result.");
				return true;
			}
			return false;
		}
		Skill skill = kind.equals("pickpocket") || kind.equals("steal") ? Skill.THIEVING : skillOf(t);
		Integer xp = skill == null ? null : in.xp.get(skill);
		if (skill != null && xp == null)
		{
			add(CAUSE, t, "no " + skill.getName() + " XP after \"" + in.option + "\" -> it failed (stunned, caught, fell, wrong tool) or didn't finish.");
		}
		else if (active)
		{
			String module = kind.equals("pickpocket") || kind.equals("steal") ? "ThievingModule (success message / target id)"
				: kind.equals("agility") ? "AgilityModule (is this obstacle's id listed? lap vs obstacle)"
				: kind.substring(0, 1).toUpperCase() + kind.substring(1) + " module";
			add(BUG, t, (skill == null ? "used it" : "+" + xp + " " + skill.getName() + " XP") + " but no credit -> " + module + ".");
		}
		return true;
	}

	/** The usual failure point for this kind of task, when nothing more specific is known. */
	private String prior(NuzlockeTask t, String kind)
	{
		TaskConstraints tc = t.getConstraints();
		List<String> gates = new ArrayList<>();
		if (gated(t))
		{
			gates.add("it's a Slayer on-task task, so the usual failure is the on-task check: the Slayer task count must drop (or Slayer XP"
				+ " arrive while the count is unknown) within 6 ticks of the kill. Players not on a task for this monster, or late counter"
				+ " updates, fail here");
		}
		if (tc != null && tc.hasTimeLimit())
		{
			gates.add("it's timed (" + tc.getTimeInTicks() + " ticks): the fight must start 30s+ after login, at full health, solo, no cannon");
		}
		if (tc != null && tc.hasDroppedItemConstraint())
		{
			gates.add("it needs " + tc.getDroppedItem() + " to drop");
		}
		if (tc != null && tc.hasEquipmentConstraints())
		{
			gates.add("it has gear rules");
		}
		if (!gates.isEmpty())
		{
			return "Ids/names are fine; if a player reports it, " + String.join("; ", gates) + ". Kill one with this mode on for evidence.";
		}
		switch (kind)
		{
			case "kill":
				return "Ids/names are fine; the kill must be yours (you dealt damage) for it to count. Kill one with this mode on for evidence.";
			case "skill":
				Skill s = skillOf(t);
				return "Ids/names are fine; it counts when " + itemNames(wanted(t)) + " lands in the inventory (or a watched bag) on the same tick as "
					+ (s == null ? "the skill's" : s.getName()) + " XP. Pouches/sacks it doesn't watch, noted loot, or a wrong item id are the usual failures.";
			case "obtain":
				return "Ids/names are fine; it counts owning " + itemNames(wanted(t)) + " (inventory, bank, worn, bags). A different item variant id is the usual failure.";
			case "pickpocket":
			case "steal":
				return "Ids/names are fine; it needs a successful steal (Thieving XP + loot) from a listed id.";
			case "talk":
				return "Ids/names are fine; it needs the dialogue with this NPC to open.";
			case "agility":
				return "Ids/names are fine; it needs the obstacle completed (Agility XP) on a listed object id.";
			default:
				return "Ids/names are fine; use it with this mode on and report again for evidence.";
		}
	}

	// --- Writing ----------------------------------------------------------------------------

	private static final String LINE = "------------------------------------------------------------\n";
	private static final int WIDTH = 90;

	private void describe(StringBuilder r, Match m, Check c, List<Verdict> mine)
	{
		NuzlockeTask t = m.e.task;
		r.append('\n').append(m.gap ? "\u2717 " : "\u2713 ").append(t.getName()).append("   [").append(t.getTaskId()).append(", ")
			.append(t.getCompletionType()).append("]\n");

		String how = m.gap ? "NOT matched: it names this but lists none of its ids. Fix: " + m.fix + "."
			: m.how.startsWith("rule") ? "Matched by a name/option rule (" + m.how.substring(5) + ")."
			: m.how.startsWith("boss entrance") ? "Shown on this boss entrance."
			: "Matched by " + m.how + ".";
		wrap(r, how, 3);

		List<String> needs = new ArrayList<>();
		TargetNpc target = t.getTargetNpc();
		if (target != null && target.getNpcIds() != null)
		{
			needs.add("NPC \"" + target.getName() + "\" " + shortList(target.getNpcIds()));
		}
		if (t.getRequiredObjects() != null)
		{
			for (RequiredObject ro : t.getRequiredObjects())
			{
				if (ro != null)
				{
					needs.add("object \"" + ro.getObject() + "\" " + shortList(ro.getObjectIds()));
				}
			}
		}
		if (t.getRequiredItems() != null && !t.getRequiredItems().isEmpty())
		{
			needs.add("item " + itemNames(wanted(t)));
		}
		if (!needs.isEmpty())
		{
			wrap(r, "Wants: " + String.join("; ", needs), 3);
		}

		List<String> rules = new ArrayList<>();
		if (gated(t))
		{
			rules.add("must be on a Slayer task for it");
		}
		TaskConstraints tc = t.getConstraints();
		if (tc != null && tc.hasTimeLimit())
		{
			rules.add("within " + tc.getTimeInTicks() + " ticks (" + seconds(tc.getTimeInTicks()) + "), fresh fight at full health, solo");
		}
		if (tc != null && tc.hasEquipmentConstraints())
		{
			rules.add("gear limits");
		}
		if (tc != null && tc.hasDroppedItemConstraint())
		{
			rules.add("needs a " + tc.getDroppedItem() + " drop");
		}
		if (!rules.isEmpty())
		{
			wrap(r, "Rules: " + String.join("; ", rules), 3);
		}

		if (running && !m.gap)
		{
			NuzlockeTask live = p.live(t.getTaskId());
			String state;
			if (p.cb.isTaskCompleted(t.getTaskId()))
			{
				state = "completed";
			}
			else if (live == null)
			{
				state = "not active";
			}
			else
			{
				boolean inMenu = c.missing.stream().noneMatch(x -> x.getTaskId().equals(t.getTaskId()));
				Object module = p.moduleFor(t.getTaskId());
				state = "active, " + live.getCurrentProgress() + "/" + live.getTargetQuantity()
					+ (module == null ? ", \u2717 no module tracks it" : ", tracked by " + module.getClass().getSimpleName())
					+ (inMenu ? ", \u2713 in its menu" : ", \u2717 missing from its menu");
			}
			wrap(r, "ChunkBlazer: " + state, 3);
		}
		wrap(r, "Listed in: " + m.e.chunks.stream().map(x -> x.replaceFirst("^\\S+_Tasks ", "")).collect(Collectors.joining("; ")), 3);
		for (Verdict v : mine)
		{
			wrap(r, "\u2192 " + v.text, 3);
		}
	}

	private void interaction(StringBuilder r, Interaction in)
	{
		r.append('\n').append('"').append(in.option).append("\" ").append(in.name).append(", ").append(ago(in.tick))
			.append(in.closed ? "" : " (still watching)").append('\n');
		List<String> got = new ArrayList<>();
		in.xp.forEach((skill, xp) -> got.add("+" + xp + " " + skill.getName() + " XP"));
		in.items.forEach((item, qty) -> got.add("+" + qty + " " + p.itemName(item)));
		boolean sameTick = !in.xp.isEmpty() && !in.items.isEmpty()
			&& in.itemTick.values().stream().anyMatch(t -> in.xpTick.containsValue(t));
		wrap(r, "Got: " + (got.isEmpty() ? "nothing" : String.join(", ", got)
			+ (in.xp.isEmpty() || in.items.isEmpty() ? "" : sameTick ? " (XP and item on the same tick)" : " (XP and item on DIFFERENT ticks)")), 3);
		progress(r, in.taskIds, in.progressBefore, in.progressAfter, in.closed);
	}

	private void kill(StringBuilder r, Kill k)
	{
		r.append('\n').append("Killed ").append(k.name).append(", ").append(ago(k.tick)).append(k.closed ? "" : " (still watching)").append('\n');
		wrap(r, "Fight: " + seconds(k.fightTicks) + " from your first hit, "
			+ (k.hpAtFirstHit >= k.maxHp ? "full health" : "health " + k.hpAtFirstHit + "/" + k.maxHp) + " at the start, "
			+ (k.ticksSinceLogin < 0 ? "login time unknown" : seconds(k.ticksSinceLogin) + " after logging in"), 3);
		wrap(r, "Slayer: task count " + k.slayerCount + (k.onTaskSignal ? ", on-task signal seen (count dropped or Slayer XP)" : ", no on-task signal"), 3);
		if (!k.drops.isEmpty())
		{
			wrap(r, "Drops: " + k.drops.stream().map(p::itemName).collect(Collectors.joining(", ")), 3);
		}
		progress(r, k.taskIds, k.progressBefore, k.progressAfter, k.closed);
	}

	private void progress(StringBuilder r, Set<String> ids, Map<String, Integer> before, Map<String, Integer> after, boolean closed)
	{
		List<String> parts = new ArrayList<>();
		for (String id : ids)
		{
			if (!before.containsKey(id) && !after.containsKey(id))
			{
				continue;
			}
			Entry e = p.catalog.get(id);
			String name = e == null ? id : e.task.getName();
			if (!closed)
			{
				parts.add(name + " " + show(before.get(id)) + " -> ?");
			}
			else
			{
				parts.add((counted(before, after, id) ? "\u2713 " : "\u2717 ") + name + " " + show(before.get(id)) + " -> " + show(after.get(id)));
			}
		}
		if (!parts.isEmpty())
		{
			wrap(r, "Progress: " + String.join("   ", parts), 3);
		}
		else if (!running)
		{
			wrap(r, "Progress: unknown (ChunkBlazer wasn't running)", 3);
		}
	}

	/** Appends text word-wrapped at WIDTH, every line indented by {@code indent} spaces. */
	private static void wrap(StringBuilder r, String text, int indent)
	{
		StringBuilder spaces = new StringBuilder();
		for (int i = 0; i < indent; i++)
		{
			spaces.append(' ');
		}
		String pad = spaces.toString();
		StringBuilder line = new StringBuilder(pad);
		for (String word : text.split(" "))
		{
			if (line.length() > indent && line.length() + 1 + word.length() > WIDTH)
			{
				r.append(line).append('\n');
				line = new StringBuilder(pad).append("  ");
			}
			if (line.length() > indent && line.charAt(line.length() - 1) != ' ')
			{
				line.append(' ');
			}
			line.append(word);
		}
		r.append(line).append('\n');
	}

	private String ago(int tick)
	{
		return seconds(p.client.getTickCount() - tick) + " ago";
	}

	/** Game ticks as human time: "12s", "3 min". */
	private static String seconds(int ticks)
	{
		int s = Math.round(ticks * 0.6f);
		return s < 90 ? s + "s" : (s + 30) / 60 + " min";
	}

	private static String shortList(List<Integer> ids)
	{
		if (ids == null || ids.isEmpty())
		{
			return "(no ids)";
		}
		return ids.size() <= 8 ? ids.toString() : ids.subList(0, 8).toString().replace("]", ", ... " + ids.size() + " ids]");
	}

	// --- Helpers ----------------------------------------------------------------------------

	private static boolean same(Target tg, String name, Set<Integer> ids)
	{
		if (ids != null)
		{
			for (int id : ids)
			{
				if (tg.ids.contains(id))
				{
					return true;
				}
			}
		}
		return tg.name != null && tg.name.equalsIgnoreCase(name);
	}

	private static boolean counted(Map<String, Integer> before, Map<String, Integer> after, String id)
	{
		Integer b = before.get(id);
		Integer a = after.get(id);
		return a != null && (b == null ? a > 0 : a > b);
	}

	private static String show(Integer v)
	{
		return v == null ? "?" : v == Integer.MAX_VALUE ? "done" : String.valueOf(v);
	}

	private static String simple(Object o)
	{
		return o == null ? "NONE" : o.getClass().getSimpleName();
	}

	static boolean gated(NuzlockeTask t)
	{
		return "SLAYER".equalsIgnoreCase(t.getCompletionType()) || (t.getName() != null && t.getName().toLowerCase().contains("on task"));
	}

	static String kind(NuzlockeTask t)
	{
		String type = TaskDebugPlugin.type(t);
		switch (type)
		{
			case "NPC_KILL":
			case "SLAYER":
			case "COMBAT":
				return "kill";
			case "THIEVING":
				return t.getTargetNpc() != null ? "pickpocket" : t.getRequiredObjects() != null ? "steal" : "thieving";
			case "NPC_DIALOGUE":
				return "talk";
			case "AGILITY":
				return "agility";
			case "OBTAIN":
				return "obtain";
			case "RAID_CHALLENGE":
			case "COMBAT_ACHIEVEMENT":
				return "boss";
			default:
				return SKILLING.contains(type) ? "skill" : type.toLowerCase();
		}
	}

	private static Skill skillOf(NuzlockeTask t)
	{
		String type = TaskDebugPlugin.type(t);
		if (type.equals("RUNECRAFTING"))
		{
			return Skill.RUNECRAFT;
		}
		try
		{
			return Skill.valueOf(type);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private Set<Integer> wanted(NuzlockeTask t)
	{
		Set<Integer> out = new LinkedHashSet<>();
		if (t.getRequiredItems() != null)
		{
			for (RequiredItem ri : t.getRequiredItems())
			{
				if (ri != null && ri.getItemIds() != null)
				{
					for (int id : ri.getItemIds())
					{
						out.add(p.itemManager.canonicalize(id));
					}
				}
			}
		}
		return out;
	}

	private String itemNames(Set<Integer> ids)
	{
		if (ids.isEmpty())
		{
			return "(no item ids)";
		}
		return ids.stream().limit(6).map(i -> p.itemName(i) + " (" + i + ")").collect(Collectors.joining(", ")) + (ids.size() > 6 ? ", ..." : "");
	}

	private Object killModule()
	{
		Object modules = TaskDebugPlugin.fieldOrNull(p.moduleManager, "modules");
		if (modules instanceof Iterable)
		{
			for (Object m : (Iterable<?>) modules)
			{
				if (m.getClass().getSimpleName().equals("NPCKillModule"))
				{
					return m;
				}
			}
		}
		return null;
	}
}
