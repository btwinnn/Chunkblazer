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

import static com.chunkblazer.TaskStyle.*;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

/**
 * Which tasks the lists show and in what order, shared by the side panel and the task window:
 * the search, filters, sort and chunk limit (set from either), saved and archived tasks, and
 * what's known about each task (its chunk, requirements, whether you can do it yet).
 */
@Singleton
public class TaskLists
{
	@Inject
	private TaskArchive archive;

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChunkBlazerPlugin plugin;

	@Inject
	private QuestRequirements quests;

	private final List<Runnable> refreshListeners = new CopyOnWriteArrayList<>();
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

	public enum SortField
	{
		POINTS("Points", false),
		PROGRESS("Progress", false),
		LEVEL("Level needed", true),
		CHUNK("Chunk", true),
		NAME("Name", true);

		public final String label;
		public final boolean ascendingByDefault;

		SortField(String label, boolean ascendingByDefault)
		{
			this.label = label;
			this.ascendingByDefault = ascendingByDefault;
		}
	}

	/**
	 * Tickable filters. Types are what a task is, so ticking several shows any of them
	 * (Combat + Talk to); conditions narrow whatever is shown, so all ticked ones must
	 * hold (Quests + Can do = ready quests). Hiding either removes those tasks.
	 */
	public enum Filter
	{
		COMBAT("Kills & combat", "Combat", false),
		OBTAIN("Obtain items", "Obtain", false),
		EQUIP("Equip items", "Equip", false),
		TALK("Talk to", "Talk to", false),
		ACHIEVEMENTS("Raids & combat achievements", "Raids & CAs", false),
		QUESTS("Quests (global)", "Quests", false),
		PROGRESSION("Level ups (global)", "Level ups", false),
		CAN_DO("Can do now", "Can do", true),
		OFFLINE("Works offline (mobile)", "Offline", true),
		BOSS("Boss chunks", "Bosses", true);

		public final String label;
		public final String shortLabel;
		public final boolean condition;

		Filter(String label, String shortLabel, boolean condition)
		{
			this.label = label;
			this.shortLabel = shortLabel;
			this.condition = condition;
		}
	}

	public volatile String search = "";
	public volatile boolean currentChunkOnly;
	// A chunk picked on the world map; while set, only its tasks are listed.
	public volatile String pinnedChunk;
	public volatile SortField sortField = SortField.POINTS;
	public volatile boolean ascending = SortField.POINTS.ascendingByDefault;
	// Filters. The sets are replaced, never changed in place, so a list build sees one state.
	public volatile Set<Filter> filterOn = Collections.emptySet();
	public volatile Set<Filter> filterHidden = Collections.emptySet();
	public volatile Skill filterSkill;
	public volatile TaskCardTier filterTier;
	// Filled on the client thread (side list), read on the Swing thread (its rows): concurrent.
	public final Map<String, String> chunkNames = new ConcurrentHashMap<>();
	private final Map<String, List<String>> requirements = new HashMap<>();

	/** Chunks paid for with points or a boss token; free, charter and starting chunks don't count. */
	public int paidChunks()
	{
		return plugin.countPayableUnlockedChunks() + (int) plugin.getUnlockedRegionIds().stream()
			.filter(id -> id.trim().matches("\\d+") && plugin.isBossRegion(Integer.parseInt(id.trim()))).count();
	}

	public Set<String> savedIds()
	{
		return archive.savedIds();
	}

	public void toggleSaved(String taskId)
	{
		archive.toggleSaved(taskId);
		changed();
	}

	/**
	 * A click on a task in any list: a quest opens its requirements ({@code openQuest}),
	 * the tracked task stops being tracked, and any other task is tracked.
	 */
	public void trackOrOpen(NuzlockeTask task, Consumer<NuzlockeTask> openQuest)
	{
		if (QuestRequirements.isQuestTask(task))
		{
			openQuest.accept(task);
		}
		else if (isTracked(task))
		{
			plugin.clearSelectedTask();
		}
		else
		{
			plugin.selectTaskFromGame(task);
		}
	}

	/** True if this is the task being tracked. */
	public boolean isTracked(NuzlockeTask task)
	{
		NuzlockeTask tracked = plugin.getSelectedTask();
		return tracked != null && task != null && tracked.getTaskId().equals(task.getTaskId());
	}

	/** The list changed in a way that should start it again from the top (filters, search, chunk). */
	public void refresh()
	{
		refreshListeners.forEach(Runnable::run);
	}

	/** The list's contents changed (saved, archived): rebuild it where it is. */
	private void changed()
	{
		changeListeners.forEach(Runnable::run);
	}

	// --- Which tasks, in what order -----------------------------------------

	/** Unfinished chunk tasks, plus unfinished Global tasks (quests, level-ups) if asked. */
	public List<NuzlockeTask> pool(boolean includeGlobal)
	{
		List<NuzlockeTask> pool = new ArrayList<>();
		// The account's completed list, not just each card's flag: a completion that
		// arrived another way (the login merge) left Cectli Snow's card showing as active.
		Set<String> completed = plugin.getCompletedTaskIdSet();
		for (NuzlockeTask task : plugin.getActiveTasks())
		{
			if (task != null && task.getTaskId() != null && !task.isCompleted() && !completed.contains(task.getTaskId()))
			{
				pool.add(task);
			}
		}
		if (includeGlobal)
		{
			for (NuzlockeTask task : plugin.getVisibleGlobalTasks())
			{
				if (task != null && task.getTaskId() != null && !completed.contains(task.getTaskId()))
				{
					pool.add(task);
				}
			}
		}
		return pool;
	}

	/**
	 * Skill and tier must match, at least one ticked type (if any), every ticked
	 * condition, and no hidden filter.
	 */
	public Predicate<NuzlockeTask> filterTest(Set<Filter> on, Set<Filter> hidden, Skill skill, TaskCardTier tier)
	{
		boolean anyType = on.stream().anyMatch(f -> !f.condition);
		return task -> (skill == null || isSkillTask(task, skill))
			&& (tier == null || TaskCardTier.fromTask(task) == tier)
			&& on.stream().allMatch(f -> !f.condition || matches(f, task))
			&& (!anyType || on.stream().anyMatch(f -> !f.condition && matches(f, task)))
			&& hidden.stream().noneMatch(f -> matches(f, task));
	}

	private boolean matches(Filter f, NuzlockeTask task)
	{
		switch (f)
		{
			case COMBAT:
				return typeIs(task, "NPC_KILL", "SLAYER")
					|| (task.getCategory() != null && task.getCategory().toLowerCase().contains("combat"));
			case OBTAIN:
				// Includes items that come from a quest (Obtain a Black Candle).
				return typeIs(task, "OBTAIN") || "Obtain".equalsIgnoreCase(task.getCategory());
			case EQUIP:
				return typeIs(task, "EQUIP");
			case TALK:
				return typeIs(task, "NPC_DIALOGUE");
			case ACHIEVEMENTS:
				return typeIs(task, "COMBAT_ACHIEVEMENT", "RAID_CHALLENGE");
			case QUESTS:
				return QuestRequirements.isQuestTask(task);
			case PROGRESSION:
				return typeIs(task, "SKILL_THRESHOLD");
			case CAN_DO:
				return canDo(task);
			case OFFLINE:
				return isOfflineTrackable(task);
			default:
				return plugin.isBossTask(task);
		}
	}

	/**
	 * True if a task done away from RuneLite (on mobile) still completes at the next
	 * RuneLite login: it's checked against saved game state. Quests (QuestCheckModule
	 * sweep), real levels (ProgressionModule), combat achievement varps, persistent unlock
	 * and achievement diary varbits, items held in inventory/bank/worn (ObtainModule; the bank once opened) and
	 * worn equipment (EquipModule). Kills, skilling XP drops, dialogue and the active-prayer
	 * varbit (it resets) need RuneLite watching.
	 */
	static boolean isOfflineTrackable(NuzlockeTask task)
	{
		return typeIs(task, "QUEST_CHECK", "SKILL_THRESHOLD", "COMBAT_ACHIEVEMENT", "EQUIP", "OBTAIN")
			|| (typeIs(task, "VARBIT_CHECK", "VARP_CHECK") && ("unlock".equalsIgnoreCase(task.getCategory())
			|| "Achievement Diary".equalsIgnoreCase(task.getCategory())));
	}

	public boolean anyFilter()
	{
		return filterSkill != null || filterTier != null || !filterOn.isEmpty() || !filterHidden.isEmpty();
	}

	public void clearFilters()
	{
		filterOn = Collections.emptySet();
		filterHidden = Collections.emptySet();
		filterSkill = null;
		filterTier = null;
		saveFilters();
	}

	/** Tick or untick a filter (show) or its eye (hide). One undoes the other. */
	public void toggleFilter(Filter f, boolean hide)
	{
		Set<Filter> on = EnumSet.noneOf(Filter.class);
		on.addAll(filterOn);
		Set<Filter> hidden = EnumSet.noneOf(Filter.class);
		hidden.addAll(filterHidden);
		Set<Filter> mine = hide ? hidden : on;
		if (!mine.remove(f))
		{
			mine.add(f);
			(hide ? on : hidden).remove(f);
		}
		filterOn = Collections.unmodifiableSet(on);
		filterHidden = Collections.unmodifiableSet(hidden);
		saveFilters();
		refresh();
	}

	/** Filters and sort are shared by the window and side panel, and remembered per account. */
	public void saveFilters()
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		configManager.setRSProfileConfiguration(CONFIG_GROUP, "taskFilters", (filterSkill == null ? "" : filterSkill.name())
			+ ";" + (filterTier == null ? "" : filterTier.name()) + ";" + names(filterOn) + ";" + names(filterHidden)
			+ ";" + sortField.name() + ";" + ascending);
	}

	public static String names(Set<Filter> filters)
	{
		return filters.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(","));
	}

	public void loadFilters()
	{
		String raw = configManager.getRSProfileConfiguration(CONFIG_GROUP, "taskFilters");
		String[] p = raw == null ? new String[0] : raw.split(";", -1);
		if (p.length < 6)
		{
			return;
		}
		try
		{
			filterSkill = p[0].isEmpty() ? null : Skill.valueOf(p[0]);
			filterTier = p[1].isEmpty() ? null : TaskCardTier.valueOf(p[1]);
			filterOn = Collections.unmodifiableSet(filterSet(p[2]));
			filterHidden = Collections.unmodifiableSet(filterSet(p[3]));
			sortField = SortField.valueOf(p[4]);
			ascending = Boolean.parseBoolean(p[5]);
		}
		catch (IllegalArgumentException e)
		{
			clearFilters();
		}
	}

	private static Set<Filter> filterSet(String csv)
	{
		Set<Filter> set = EnumSet.noneOf(Filter.class);
		for (String name : csv.split(","))
		{
			if (!name.isEmpty())
			{
				set.add(Filter.valueOf(name));
			}
		}
		return set;
	}

	/** "Mining + Tier 5 + Combat + no Bosses": every active filter. */
	public String filterLabel()
	{
		List<String> parts = new ArrayList<>();
		if (filterSkill != null)
		{
			parts.add(filterSkill.getName());
		}
		if (filterTier != null)
		{
			parts.add(filterTier.getDisplayName());
		}
		filterOn.forEach(f -> parts.add(f.shortLabel));
		filterHidden.forEach(f -> parts.add("no " + f.shortLabel));
		return parts.isEmpty() ? "All" : String.join(" + ", parts);
	}

	public static boolean typeIs(NuzlockeTask task, String... types)
	{
		String type = task.getCompletionType();
		return type != null && Arrays.stream(types).anyMatch(type::equalsIgnoreCase);
	}

	/** The task trains or needs this skill (exact match: Runecrafting isn't Crafting). */
	private static boolean isSkillTask(NuzlockeTask task, Skill skill)
	{
		if (TaskTargetExtras.categorySkill(task.getCategory()) == skill
			|| TaskTargetExtras.requirements(task).containsKey(skill))
		{
			return true;
		}
		String type = task.getCompletionType();
		if (type != null && type.toLowerCase().startsWith(skill.getName().toLowerCase()))
		{
			return true;
		}
		TaskConstraints c = task.getConstraints();
		return c != null && skill.name().equalsIgnoreCase(c.getRequiredSkill());
	}

	public Comparator<NuzlockeTask> sorter(SortField by, boolean asc)
	{
		Comparator<NuzlockeTask> byName = Comparator.comparing(t -> t.getName() == null ? "" : t.getName());
		Comparator<NuzlockeTask> main;
		switch (by)
		{
			case PROGRESS:
				main = Comparator.comparingDouble(TaskTargetExtras::progress);
				break;
			case LEVEL:
				main = Comparator.comparingInt(NuzlockeTask::getLevelRequirement);
				break;
			case CHUNK:
				main = Comparator.comparing((NuzlockeTask t) -> chunkNames.getOrDefault(t.getTaskId(), ""));
				break;
			case NAME:
				main = byName;
				break;
			default:
				main = Comparator.comparingInt(NuzlockeTask::getBasePoints);
				break;
		}
		return (asc ? main : main.reversed()).thenComparing(byName);
	}

	public String currentChunkName()
	{
		return chunkNameFor(plugin.getCurrentRegionId());
	}

	/** Chunk name without its "(region id)" suffix; "" if it isn't a known chunk. */
	public String chunkNameFor(int regionId)
	{
		String name = plugin.getRegionName(regionId);
		return name == null || name.startsWith("Unknown Region") ? "" : stripRegionId(name);
	}

	public String chunkName(NuzlockeTask task)
	{
		if (plugin.isGlobalTask(task.getTaskId()))
		{
			return "Global";
		}
		String name = plugin.getTaskRegionName(task);
		return name == null ? "" : stripRegionId(name);
	}

	private static String stripRegionId(String name)
	{
		return name.replaceAll("\\s*\\(\\d+\\)$", "").trim();
	}

	public boolean matchesSearch(NuzlockeTask task, String query)
	{
		for (String text : new String[]{task.getName(), task.getDescription(),
			NuzlockeTask.displayCategory(task.getCategory()), chunkNames.get(task.getTaskId())})
		{
			if (text != null && text.toLowerCase().contains(query))
			{
				return true;
			}
		}
		return false;
	}

	public List<String> requirementsFor(NuzlockeTask task)
	{
		return requirements.computeIfAbsent(task.getTaskId(), id -> requirementLines(task));
	}

	// Finished task ids for the header's count, read at most every two seconds.
	public Set<String> doneIds = Collections.emptySet();

	/** Level checks, real requirements (TaskTargetExtras) and, for quests, their requirements. */
	public boolean canDo(NuzlockeTask task)
	{
		return TaskTargetExtras.canDo(client, plugin, task) && quests.isReady(task);
	}

	public static List<String> wrap(FontMetrics fm, String text, int width)
	{
		List<String> lines = new ArrayList<>();
		String line = "";
		for (String word : text.split(" "))
		{
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (fm.stringWidth(candidate) > width && !line.isEmpty())
			{
				lines.add(line);
				line = word;
			}
			else
			{
				line = candidate;
			}
		}
		if (!line.isEmpty())
		{
			lines.add(line);
		}
		return lines;
	}

	/**
	 * Plain-English requirements for the task. Uses the task's description when it
	 * has one (challenges and combat achievements, e.g. "Defeat Scurrius with a Green
	 * d'hide body, chaps, vambraces, and Maple shortbow equipped."); otherwise spells
	 * out the common restrictions from its constraints.
	 */
	public static List<String> requirementLines(NuzlockeTask task)
	{
		return requirementLines(task, true);
	}

	/** The same, with or without the time limit ("Within 0:18"). */
	public static List<String> requirementLines(NuzlockeTask task, boolean withTime)
	{
		List<String> lines = new ArrayList<>();
		String description = task.getDescription();
		if (description != null && !description.trim().isEmpty()
				&& !simplify(description).equals(simplify(task.getName())))
		{
			lines.add(TaskSources.tidy(description.trim()));
			return lines;
		}

		TaskConstraints c = task.getConstraints();
		if (c == null)
		{
			return lines;
		}
		// One tick ("Within 0:01", a first-hit kill) says nothing useful.
		if (withTime && c.getTimeInTicks() != null && c.getTimeInTicks() > 1 && !TIME_IN_NAME.matcher(String.valueOf(task.getName())).find())
		{
			int seconds = (int) Math.round(c.getTimeInTicks() * 0.6);
			lines.add(String.format("Within %d:%02d", seconds / 60, seconds % 60));
		}
		if (Boolean.TRUE.equals(c.getNoPrayer()))
		{
			lines.add("No prayer");
		}
		if (Boolean.TRUE.equals(c.getNoFood()))
		{
			lines.add("No food");
		}
		if (Boolean.TRUE.equals(c.getNoEquipment()) || Boolean.TRUE.equals(c.getEquipNothing()))
		{
			lines.add("Nothing equipped");
		}
		if (c.getMaxCombatLevel() != null)
		{
			lines.add("Combat level " + c.getMaxCombatLevel() + " or lower");
		}
		if (c.getMinCombatLevel() != null)
		{
			lines.add("Combat level " + c.getMinCombatLevel() + " or higher");
		}
		return lines;
	}

	// A name that already gives the time limit ("in 60 Seconds", "under 1:20").
	private static final java.util.regex.Pattern TIME_IN_NAME = java.util.regex.Pattern.compile(
		"(?i)\\b\\d+(\\.\\d+)?\\s*(seconds?|secs?|minutes?|mins?|ticks?)\\b|\\b\\d+:\\d{2}\\b");

	private static String simplify(String text)
	{
		// "Obtain a Pendant of ates." says no more than "Obtain a Pendant of Ates".
		return text == null ? "" : text.toLowerCase().replaceAll("\\b(a|an|the|some|pair|of)\\b", "").replaceAll("[^a-z0-9]", "");
	}
}
