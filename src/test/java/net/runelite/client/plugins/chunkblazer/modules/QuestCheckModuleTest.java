package net.runelite.client.plugins.chunkblazer.modules;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeChunk;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.TaskConstraints;
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
 * Unit tests for QuestCheckModule and the shipped Quest_Tasks.json ("Global Tasks").
 *
 * Quest.getState() is exercised for real: it calls client.runScript(...) then
 * reads client.getIntStack()[0], both of which are mocked here, so stubbing the
 * int stack to 2 makes any quest report FINISHED.
 */
@ExtendWith(MockitoExtension.class)
class QuestCheckModuleTest extends AbstractTaskModuleTest
{
	private static final String QUEST_TASKS_RESOURCE =
		"/net/runelite/client/plugins/chunkblazer/Quest_Tasks.json";

	// QUEST_STATUS_GET result codes, as mapped by Quest.getState().
	private static final int[] STACK_FINISHED = { 2 };
	private static final int[] STACK_NOT_STARTED = { 1 };
	private static final int[] STACK_IN_PROGRESS = { 0 };

	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private QuestCheckModule questCheckModule;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(questCheckModule, "client", client);
		injectField(questCheckModule, "clientThread", clientThread);
		injectField(questCheckModule, "eventBus", eventBus);
		injectField(questCheckModule, "config", config);

		questCheckModule.setCompletionCallback(completionCallback);
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

	private NuzlockeTask questTask(String taskId, String questConstant)
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setName("Complete " + questConstant);
		task.setTaskId(taskId);
		task.setCategory("Quest");
		task.setCompletionType("QUEST_CHECK");
		task.setBasePoints(1);
		task.setTargetQuantity(1);

		TaskConstraints constraints = new TaskConstraints();
		constraints.setQuest(questConstant);
		task.setConstraints(constraints);

		return task;
	}

	/**
	 * Drive enough ticks to clear the module's sweep rate limit. Stubs are
	 * lenient: re-stubbing getTickCount each iteration makes every earlier stub
	 * look "unnecessary" to strict Mockito.
	 */
	private void advanceAndSweep()
	{
		questCheckModule.onVarbitChanged(new VarbitChanged());
		for (int i = 1; i <= 12; i++)
		{
			lenient().when(client.getTickCount()).thenReturn(i * 10);
			questCheckModule.onGameTick(new GameTick());
		}
	}

	// ---------------------------------------------------------------
	// Shipped data: the failure this guards against is silent — a bad
	// wrapper shape parses fine and yields zero tasks, and then no quest
	// ever awards a point.
	// ---------------------------------------------------------------

	private List<NuzlockeTask> loadShippedQuestTasks() throws Exception
	{
		try (InputStream is = getClass().getResourceAsStream(QUEST_TASKS_RESOURCE))
		{
			assertNotNull(is, "Quest_Tasks.json is not on the classpath at " + QUEST_TASKS_RESOURCE);

			Type mapType = new TypeToken<Map<String, List<NuzlockeChunk>>>()
			{
			}.getType();
			Map<String, List<NuzlockeChunk>> data =
				new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), mapType);

			assertNotNull(data);
			assertTrue(data.containsKey("Quest_Tasks"), "root key should be Quest_Tasks, got " + data.keySet());

			List<NuzlockeChunk> groups = data.get("Quest_Tasks");
			assertNotNull(groups);
			assertEquals(1, groups.size(), "expected exactly one region group");

			List<NuzlockeTask> tasks = groups.get(0).getTasks();
			assertNotNull(tasks, "region group has no tasks array");
			return tasks;
		}
	}

	@Test
	void shippedQuestTasksFileLoadsTasks() throws Exception
	{
		List<NuzlockeTask> tasks = loadShippedQuestTasks();

		assertFalse(tasks.isEmpty(), "Quest_Tasks.json loaded ZERO tasks — check the region-group wrapper");
		assertTrue(tasks.size() > 150, "expected the full quest pool, got " + tasks.size());
	}

	@Test
	void everyShippedQuestTaskHasResolvableQuestConstant() throws Exception
	{
		for (NuzlockeTask task : loadShippedQuestTasks())
		{
			assertEquals("QUEST_CHECK", task.getCompletionType(), task.getTaskId());
			assertNotNull(task.getConstraints(), "no constraints on " + task.getTaskId());

			String questName = task.getConstraints().getQuest();
			assertNotNull(questName, "constraints.quest did not deserialize for " + task.getTaskId());

			// Quest.valueOf is what the module calls; a name the enum doesn't
			// have would make the task silently untrackable at runtime.
			assertDoesNotThrow(() -> Quest.valueOf(questName),
				"unknown Quest constant '" + questName + "' on " + task.getTaskId());
		}
	}

	@Test
	void shippedQuestTaskIdsAreUniqueAndPointsInRange() throws Exception
	{
		Set<String> seen = new HashSet<>();
		for (NuzlockeTask task : loadShippedQuestTasks())
		{
			assertTrue(seen.add(task.getTaskId()), "duplicate taskID: " + task.getTaskId());
			assertTrue(task.getBasePoints() >= 1 && task.getBasePoints() <= 5,
				task.getTaskId() + " has base_points " + task.getBasePoints());
		}
	}

	@Test
	void excludedQuestsAreAbsent() throws Exception
	{
		Set<String> excluded = new HashSet<>();
		excluded.add("LEARNING_THE_ROPES");
		excluded.add("CURRENT_AFFAIRS");
		excluded.add("PRYING_TIMES");
		excluded.add("PANDEMONIUM");
		excluded.add("TROUBLED_TORTUGANS");
		excluded.add("THE_RED_REEF");

		for (NuzlockeTask task : loadShippedQuestTasks())
		{
			assertFalse(excluded.contains(task.getConstraints().getQuest()),
				"excluded quest present: " + task.getConstraints().getQuest());
		}
	}

	// ---------------------------------------------------------------
	// Module behaviour
	// ---------------------------------------------------------------

	@Test
	void completesTaskWhenQuestIsFinished()
	{
		lenient().when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getIntStack()).thenReturn(STACK_FINISHED);

		NuzlockeTask task = questTask("quest_cooks_assistant", "COOKS_ASSISTANT");
		questCheckModule.addActiveTask(task);

		advanceAndSweep();

		assertTrue(task.isCompleted(), "task should complete when the quest reports FINISHED");
		assertEquals(1, task.getCurrentProgress());
		verify(completionCallback).onTaskCompleted(task, 1);
	}

	@Test
	void doesNotCompleteWhenQuestNotStarted()
	{
		lenient().when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getIntStack()).thenReturn(STACK_NOT_STARTED);

		NuzlockeTask task = questTask("quest_cooks_assistant", "COOKS_ASSISTANT");
		questCheckModule.addActiveTask(task);

		advanceAndSweep();

		assertFalse(task.isCompleted());
		verify(completionCallback, never()).onTaskCompleted(any(), anyInt());
	}

	@Test
	void doesNotCompleteWhenQuestInProgress()
	{
		lenient().when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getIntStack()).thenReturn(STACK_IN_PROGRESS);

		NuzlockeTask task = questTask("quest_dragon_slayer_ii", "DRAGON_SLAYER_II");
		questCheckModule.addActiveTask(task);

		advanceAndSweep();

		assertFalse(task.isCompleted());
		verify(completionCallback, never()).onTaskCompleted(any(), anyInt());
	}

	@Test
	void doesNotRunScriptsWhenLoggedOut()
	{
		lenient().when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		NuzlockeTask task = questTask("quest_cooks_assistant", "COOKS_ASSISTANT");
		questCheckModule.addActiveTask(task);

		advanceAndSweep();

		assertFalse(task.isCompleted());
		// getState() would have run a script; the login gate must stop it.
		verify(client, never()).runScript(any(Object[].class));
		verify(completionCallback, never()).onTaskCompleted(any(), anyInt());
	}

	@Test
	void unknownQuestConstantIsNotTrackedAndDoesNotThrow()
	{
		NuzlockeTask task = questTask("quest_not_a_real_quest", "THIS_QUEST_DOES_NOT_EXIST");

		assertDoesNotThrow(() -> questCheckModule.addActiveTask(task));
		assertFalse(questCheckModule.getActiveTasks().contains(task),
			"a task naming an unknown Quest constant must not be tracked");
	}

	@Test
	void missingQuestConstraintIsNotTracked()
	{
		NuzlockeTask task = questTask("quest_missing", "COOKS_ASSISTANT");
		task.setConstraints(new TaskConstraints()); // quest == null

		assertDoesNotThrow(() -> questCheckModule.addActiveTask(task));
		assertFalse(questCheckModule.getActiveTasks().contains(task));
	}

	@Test
	void varbitChangeAloneDoesNotSweep()
	{
		lenient().when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getIntStack()).thenReturn(STACK_FINISHED);
		lenient().when(client.getTickCount()).thenReturn(0);

		NuzlockeTask task = questTask("quest_cooks_assistant", "COOKS_ASSISTANT");
		questCheckModule.addActiveTask(task);

		// A burst of varbit changes with no tick in between must not each
		// trigger a full ~200-task script sweep.
		for (int i = 0; i < 50; i++)
		{
			questCheckModule.onVarbitChanged(new VarbitChanged());
		}

		assertFalse(task.isCompleted(), "sweep should be deferred to onGameTick, not run per VarbitChanged");
	}

	@Test
	void completedTaskIsDroppedFromTracking()
	{
		lenient().when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getIntStack()).thenReturn(STACK_FINISHED);

		NuzlockeTask task = questTask("quest_cooks_assistant", "COOKS_ASSISTANT");
		questCheckModule.addActiveTask(task);

		advanceAndSweep();
		assertTrue(task.isCompleted());

		// Second sweep must not re-fire the callback for the same task.
		advanceAndSweep();
		verify(completionCallback, times(1)).onTaskCompleted(task, 1);
	}

	/**
	 * Regression guard for the 2026-07-19 client freeze: ~150 quest tasks all
	 * completed in one sweep, and each completion runs an expensive per-task
	 * pipeline on the client thread (config writes + a full Swing rebuild).
	 * A single sweep must never fire more than the cap.
	 */
	@Test
	void sweepCapsCompletionsPerPass()
	{
		lenient().when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		lenient().when(client.getIntStack()).thenReturn(STACK_FINISHED);

		// 20 already-finished quests registered at once.
		String[] quests = {
			"COOKS_ASSISTANT", "DRAGON_SLAYER_I", "DEMON_SLAYER", "IMP_CATCHER",
			"SHEEP_SHEARER", "RUNE_MYSTERIES", "DORICS_QUEST", "WITCHS_POTION",
			"THE_RESTLESS_GHOST", "PIRATES_TREASURE", "GOBLIN_DIPLOMACY",
			"BLACK_KNIGHTS_FORTRESS", "VAMPYRE_SLAYER", "ERNEST_THE_CHICKEN",
			"PRINCE_ALI_RESCUE", "SHIELD_OF_ARRAV", "MISTHALIN_MYSTERY",
			"X_MARKS_THE_SPOT", "BELOW_ICE_MOUNTAIN", "THE_CORSAIR_CURSE",
		};
		for (int i = 0; i < quests.length; i++)
		{
			questCheckModule.addActiveTask(questTask("quest_" + i, quests[i]));
		}

		// Exactly one sweep.
		questCheckModule.onVarbitChanged(new VarbitChanged());
		lenient().when(client.getTickCount()).thenReturn(1000);
		questCheckModule.onGameTick(new GameTick());

		verify(completionCallback, atMost(5)).onTaskCompleted(any(), anyInt());
	}

	@Test
	void handlesQuestCheckCompletionType()
	{
		assertEquals("QUEST_CHECK", questCheckModule.getCompletionType());
		assertTrue(questCheckModule.canHandle(questTask("q", "COOKS_ASSISTANT")));
	}
}
