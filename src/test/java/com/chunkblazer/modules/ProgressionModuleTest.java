package com.chunkblazer.modules;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import com.chunkblazer.NuzlockeChunk;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TaskConstraints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProgressionModule and the generated Progression_Tasks.json.
 *
 * <p>The behaviour that matters most here is what must NOT happen: a boosted
 * level must not buy a rung, and the shipped ladder must match the designed
 * point schedule exactly, since base_points drift between the plugin and server
 * catalogs silently scores tasks at zero.
 */
@ExtendWith(MockitoExtension.class)
class ProgressionModuleTest extends AbstractTaskModuleTest
{
	private static final String PROGRESSION_RESOURCE =
		"/com/chunkblazer/Progression_Tasks.json";

	/** The designed ladder: threshold -> base_points. */
	private static final Map<Integer, Integer> LADDER = new HashMap<>();

	static
	{
		LADDER.put(10, 1);
		LADDER.put(20, 1);
		LADDER.put(30, 2);
		LADDER.put(40, 2);
		LADDER.put(50, 2);
		LADDER.put(60, 3);
		LADDER.put(70, 3);
		LADDER.put(80, 4);
		LADDER.put(90, 5);
		LADDER.put(99, 5);
	}

	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private ProgressionModule progressionModule;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(progressionModule, "client", client);
		injectField(progressionModule, "clientThread", clientThread);
		injectField(progressionModule, "eventBus", eventBus);
		injectField(progressionModule, "config", config);

		progressionModule.setCompletionCallback(completionCallback);
		lenient().when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
	}

	private void injectField(Object target, String fieldName, Object value) throws Exception
	{
		Field field = findField(target.getClass(), fieldName);
		if (field != null)
		{
			field.setAccessible(true);
			field.set(target, value);
		}
	}

	private Field findField(Class<?> clazz, String fieldName)
	{
		while (clazz != null)
		{
			try
			{
				return clazz.getDeclaredField(fieldName);
			}
			catch (NoSuchFieldException e)
			{
				clazz = clazz.getSuperclass();
			}
		}
		return null;
	}

	private NuzlockeTask rung(Skill skill, int level)
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setName("Reach Level " + level + " " + skill.name());
		task.setTaskId("progression_" + skill.name().toLowerCase() + "_" + level);
		task.setCategory("Progression");
		task.setCompletionType("SKILL_THRESHOLD");
		task.setBasePoints(LADDER.get(level));
		task.setTargetQuantity(1);

		TaskConstraints constraints = new TaskConstraints();
		constraints.setRequiredSkill(skill.name());
		constraints.setRequiredLevel(level);
		task.setConstraints(constraints);

		return task;
	}

	// --- Runtime behaviour ------------------------------------------------

	@Test
	void rungCompletesWhenLevelIsReached()
	{
		when(client.getRealSkillLevel(Skill.THIEVING)).thenReturn(29);
		NuzlockeTask task = rung(Skill.THIEVING, 30);
		progressionModule.addActiveTask(task);
		assertFalse(task.isCompleted(), "29 Thieving must not complete the level-30 rung");

		when(client.getRealSkillLevel(Skill.THIEVING)).thenReturn(30);
		progressionModule.onStatChanged(statChanged(Skill.THIEVING, 30));

		assertTrue(task.isCompleted(), "reaching 30 Thieving must complete the rung");
		verify(completionCallback).onTaskCompleted(eq(task), anyInt());
	}

	/**
	 * The reason getRealSkillLevel is used rather than getBoostedSkillLevel: a
	 * stew, a potion, or any temporary boost must not buy a permanent rung.
	 */
	@Test
	void boostedLevelDoesNotCompleteRung()
	{
		when(client.getRealSkillLevel(Skill.MINING)).thenReturn(55);
		lenient().when(client.getBoostedSkillLevel(Skill.MINING)).thenReturn(61);

		NuzlockeTask task = rung(Skill.MINING, 60);
		progressionModule.addActiveTask(task);
		progressionModule.onStatChanged(statChanged(Skill.MINING, 55));

		assertFalse(task.isCompleted(), "a boosted 61 Mining must not complete the level-60 rung");
		verify(completionCallback, never()).onTaskCompleted(any(), anyInt());
	}

	@Test
	void rungAlreadyReachedAtRegistrationCompletesImmediately()
	{
		// Levelled past an ELIGIBLE rung while the plugin was off — real forward
		// progress we didn't witness, so it still pays.
		when(client.getRealSkillLevel(Skill.HERBLORE)).thenReturn(42);
		NuzlockeTask task = rung(Skill.HERBLORE, 40);

		progressionModule.addActiveTask(task);

		assertTrue(task.isCompleted(), "a rung already passed at registration should settle immediately");
	}

	@Test
	void statChangeInAnotherSkillDoesNotCompleteRung()
	{
		when(client.getRealSkillLevel(Skill.FISHING)).thenReturn(1);
		NuzlockeTask task = rung(Skill.FISHING, 20);
		progressionModule.addActiveTask(task);

		// Cooking XP must not be able to tick a Fishing rung — the cross-credit
		// failure mode that hit the agility tasks.
		progressionModule.onStatChanged(statChanged(Skill.COOKING, 40));

		assertFalse(task.isCompleted(), "a different skill's stat change must not complete this rung");
	}

	@Test
	void taskWithUnknownSkillIsNotTracked()
	{
		NuzlockeTask task = rung(Skill.SLAYER, 50);
		task.getConstraints().setRequiredSkill("NOT_A_SKILL");

		progressionModule.addActiveTask(task);

		assertFalse(progressionModule.getActiveTasks().contains(task),
			"a task naming an unknown skill must not be tracked");
	}

	@Test
	void moduleOnlyHandlesSkillThresholdTasks()
	{
		assertTrue(progressionModule.canHandle(rung(Skill.ATTACK, 40)));

		NuzlockeTask quest = new NuzlockeTask();
		quest.setCompletionType("QUEST_CHECK");
		assertFalse(progressionModule.canHandle(quest), "QUEST_CHECK belongs to QuestCheckModule");
	}

	/** StatChanged(skill, xp, level, boostedLevel) — immutable in this API version. */
	private StatChanged statChanged(Skill skill, int level)
	{
		return new StatChanged(skill, 1, level, level);
	}

	// --- Shipped data -----------------------------------------------------

	private List<NuzlockeTask> loadShippedProgressionTasks() throws Exception
	{
		try (InputStream is = getClass().getResourceAsStream(PROGRESSION_RESOURCE))
		{
			assertNotNull(is, "Progression_Tasks.json is not on the classpath at " + PROGRESSION_RESOURCE);

			Type mapType = new TypeToken<Map<String, List<NuzlockeChunk>>>()
			{
			}.getType();
			Map<String, List<NuzlockeChunk>> data =
				new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), mapType);

			assertNotNull(data);
			assertTrue(data.containsKey("Progression_Tasks"),
				"root key should be Progression_Tasks, got " + data.keySet());

			List<NuzlockeChunk> groups = data.get("Progression_Tasks");
			assertNotNull(groups);
			assertEquals(1, groups.size(), "expected exactly one region group");

			List<NuzlockeTask> tasks = groups.get(0).getTasks();
			assertNotNull(tasks, "region group has no tasks array");
			return tasks;
		}
	}

	@Test
	void shippedLadderHasEveryRungForEverySkill() throws Exception
	{
		List<NuzlockeTask> tasks = loadShippedProgressionTasks();

		Map<String, Set<Integer>> bySkill = new HashMap<>();
		for (NuzlockeTask task : tasks)
		{
			assertEquals("SKILL_THRESHOLD", task.getCompletionType(), task.getTaskId());
			assertNotNull(task.getConstraints(), "no constraints on " + task.getTaskId());
			String skill = task.getConstraints().getRequiredSkill();
			assertNotNull(skill, "no required_skill on " + task.getTaskId());
			bySkill.computeIfAbsent(skill, k -> new HashSet<>())
				.add(task.getConstraints().getRequiredLevel());
		}

		assertEquals(24, bySkill.size(), "expected all 24 skills, got " + bySkill.keySet());

		for (Map.Entry<String, Set<Integer>> e : bySkill.entrySet())
		{
			Set<Integer> expected = new HashSet<>(LADDER.keySet());
			if ("HITPOINTS".equals(e.getKey()))
			{
				// Accounts spawn at 10 HP, so that rung would be unearnable.
				expected.remove(10);
			}
			assertEquals(expected, e.getValue(), "wrong rungs for " + e.getKey());
		}

		assertEquals(239, tasks.size(), "24 skills x 10 rungs, minus the impossible Hitpoints 10");
	}

	@Test
	void everyShippedRungMatchesTheDesignedPointLadder() throws Exception
	{
		for (NuzlockeTask task : loadShippedProgressionTasks())
		{
			int level = task.getConstraints().getRequiredLevel();
			assertEquals(LADDER.get(level).intValue(), task.getBasePoints(),
				"base_points off the designed ladder for " + task.getTaskId());
		}
	}

	@Test
	void shippedTaskIdsAreUnique() throws Exception
	{
		List<NuzlockeTask> tasks = loadShippedProgressionTasks();
		Set<String> ids = new HashSet<>();
		List<String> dupes = new ArrayList<>();
		for (NuzlockeTask task : tasks)
		{
			if (!ids.add(task.getTaskId()))
			{
				dupes.add(task.getTaskId());
			}
		}
		assertTrue(dupes.isEmpty(), "duplicate task IDs: " + dupes);
	}

	/**
	 * The Hitpoints level-10 rung must not exist: every account starts at 10 HP,
	 * so shipping it would put a permanently unearnable task in the pool.
	 */
	@Test
	void noHitpointsLevelTenRung() throws Exception
	{
		for (NuzlockeTask task : loadShippedProgressionTasks())
		{
			boolean hpTen = "HITPOINTS".equals(task.getConstraints().getRequiredSkill())
				&& task.getConstraints().getRequiredLevel() == 10;
			assertFalse(hpTen, "Hitpoints 10 is unearnable and must not ship: " + task.getTaskId());
		}
	}

	@Test
	void shippedLadderTotalsTheExpectedPoints() throws Exception
	{
		int total = 0;
		for (NuzlockeTask task : loadShippedProgressionTasks())
		{
			total += task.getBasePoints();
		}
		// 24 skills x 28 points, less the 1-point Hitpoints 10 rung.
		assertEquals(24 * 28 - 1, total, "Progression pool point total changed");
	}
}
