package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.api.Hitsplat;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.api.ChunkBlazerApiClient;
import net.runelite.client.plugins.chunkblazer.verification.VarPlayerVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NPCKillModule.
 * Tests NPC kill detection, slayer task verification, and combat tracking.
 */
@ExtendWith(MockitoExtension.class)
class NPCKillModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private VarPlayerVerificationService varPlayerService;

	@Mock
	private ChatMessageManager chatMessageManager;

	@Mock
	private ChunkBlazerApiClient apiClient;

	@InjectMocks
	private NPCKillModule npcKillModule;

	@Mock
	private NPC targetNpc;

	@Mock
	private Hitsplat hitsplat;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		// Inject mocks into the module using reflection
		injectField(npcKillModule, "client", client);
		injectField(npcKillModule, "clientThread", clientThread);
		injectField(npcKillModule, "eventBus", eventBus);
		injectField(npcKillModule, "config", config);

		npcKillModule.setCompletionCallback(completionCallback);

		// The credit path fires a fire-and-forget kill report; hand it an
		// incomplete future so the callback chain never runs (telemetry is not
		// what these tests are about).
		lenient().when(apiClient.reportNpcKill(any())).thenReturn(new java.util.concurrent.CompletableFuture<>());
		// A positive, stable game tick so the on-task Slayer-XP window math works.
		lenient().when(client.getTickCount()).thenReturn(100);
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

	@Test
	void testGetCompletionType()
	{
		assertEquals("NPC_KILL", npcKillModule.getCompletionType());
	}

	@Test
	void testCanHandle_NpcKillType()
	{
		NuzlockeTask task = createTestTask("Kill Goblin", "kill_goblin", "NPC_KILL", 5);
		assertTrue(npcKillModule.canHandle(task));
	}

	@Test
	void testCanHandle_CombatType()
	{
		NuzlockeTask task = createTestTask("Combat Task", "combat_task", "COMBAT", 1);
		assertTrue(npcKillModule.canHandle(task));
	}

	@Test
	void testCanHandle_SlayerType()
	{
		NuzlockeTask task = createTestTask("Slayer Task", "slayer_task", "SLAYER", 10);
		assertTrue(npcKillModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Fishing Task", "fishing", "FISHING", 5);
		assertFalse(npcKillModule.canHandle(task));
	}

	@Test
	void testCanHandle_CombatCategory()
	{
		NuzlockeTask task = createTestTask("Combat Category", "combat_cat", "OTHER", 1);
		task.setCategory("combat");
		assertTrue(npcKillModule.canHandle(task));
	}

	@Test
	void testAddActiveTask()
	{
		NuzlockeTask task = createTaskWithNpc("Kill Goblin", "kill_goblin", "NPC_KILL", 5, Arrays.asList(100, 101));

		npcKillModule.addActiveTask(task);

		assertEquals(1, npcKillModule.getActiveTasks().size());
		assertTrue(npcKillModule.getActiveTasks().contains(task));
	}

	@Test
	void testAddActiveTask_DuplicatePrevented()
	{
		NuzlockeTask task = createTaskWithNpc("Kill Goblin", "kill_goblin", "NPC_KILL", 5, Arrays.asList(100));

		npcKillModule.addActiveTask(task);
		npcKillModule.addActiveTask(task); // Add again

		assertEquals(1, npcKillModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithNpc("Kill Goblin", "kill_goblin", "NPC_KILL", 5, Arrays.asList(100));
		npcKillModule.addActiveTask(task);

		npcKillModule.onTaskCleared();

		assertTrue(npcKillModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		npcKillModule.startUp();
		verify(eventBus).register(npcKillModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		npcKillModule.shutDown();
		verify(eventBus).unregister(npcKillModule);
	}

	// Integration-style test for the kill detection flow
	@Test
	void testTaskProgressTracking()
	{
		NuzlockeTask task = createTaskWithNpc("Kill 5 Goblins", "kill_goblins", "NPC_KILL", 5, Arrays.asList(100));

		npcKillModule.addActiveTask(task);

		// Simulate progress
		task.setCurrentProgress(3);

		assertEquals(3, task.getCurrentProgress());
		assertFalse(task.isCompleted());

		// Complete the task
		task.setCurrentProgress(5);
		task.setCompleted(true);

		assertEquals(5, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	// --- Slayer credit problems (parallel to the agility cross-credit) ---------------------------
	//
	// processNpcDeath() is the credit decision. We drive it directly (reflection,
	// like the field injection above) after marking the NPC as "our kill", so the
	// tests focus on the matching + slayer gate rather than the combat-tracking
	// plumbing. SLAYER_TASK_COUNT_VARP is 394.

	private NPC mockNpc(int id, int index, String name)
	{
		NPC npc = mock(NPC.class);
		lenient().when(npc.getId()).thenReturn(id);
		lenient().when(npc.getIndex()).thenReturn(index);
		lenient().when(npc.getName()).thenReturn(name);
		lenient().when(npc.getWorldLocation()).thenReturn(mock(WorldPoint.class)); // for the kill report
		return npc;
	}

	private void simulateKill(NPC npc) throws Exception
	{
		// Pretend the combat tracker confirmed we damaged THIS npc instance.
		injectField(npcKillModule, "currentTarget", npc);
		injectField(npcKillModule, "currentTargetIndex", npc.getIndex());
		injectField(npcKillModule, "damageDealtToTarget", 10);

		java.lang.reflect.Method m = findMethod(npcKillModule.getClass(), "processNpcDeath", NPC.class);
		m.setAccessible(true);
		m.invoke(npcKillModule, npc);
	}

	private java.lang.reflect.Method findMethod(Class<?> clazz, String name, Class<?>... params)
	{
		while (clazz != null)
		{
			try
			{
				return clazz.getDeclaredMethod(name, params);
			}
			catch (NoSuchMethodException e)
			{
				clazz = clazz.getSuperclass();
			}
		}
		return null;
	}

	/** Simulate an ON-TASK kill: a Slayer XP gain at the current tick (off-task
	 *  kills award none). First event sets the baseline, second registers the gain. */
	private void grantSlayerXp()
	{
		npcKillModule.onStatChanged(new StatChanged(Skill.SLAYER, 1000, 50, 50));
		npcKillModule.onStatChanged(new StatChanged(Skill.SLAYER, 1010, 50, 50));
	}

	/**
	 * Fix (#1): a SLAYER task only credits when the kill was ON TASK — i.e. it
	 * awarded Slayer XP. Killing the matching monster while assigned to something
	 * else (or to nothing) yields no Slayer XP, so it must NOT credit.
	 */
	@Test
	void testSlayerTask_offTaskKill_notCredited() throws Exception
	{
		NuzlockeTask cowTask = createTaskWithNpc("Defeat a Cow on Task", "slay_cow", "SLAYER", 1, Arrays.asList(100));
		npcKillModule.addActiveTask(cowTask);

		// No Slayer XP gain → off-task (assigned to something other than cows, or no task).
		simulateKill(mockNpc(100, 1, "Cow"));

		assertEquals(0, cowTask.getCurrentProgress(),
			"a SLAYER task must not credit an off-task kill (no Slayer XP awarded)");
	}

	/**
	 * The same kill DOES credit when it's on task — a Slayer XP gain landed in the
	 * kill's tick window.
	 */
	@Test
	void testSlayerTask_onTaskKill_credits() throws Exception
	{
		NuzlockeTask cowTask = createTaskWithNpc("Defeat a Cow on Task", "slay_cow", "SLAYER", 1, Arrays.asList(100));
		npcKillModule.addActiveTask(cowTask);

		grantSlayerXp(); // on task: the kill awards Slayer XP
		simulateKill(mockNpc(100, 1, "Cow"));

		assertEquals(1, cowTask.getCurrentProgress(),
			"an on-task slayer kill (Slayer XP awarded) credits the task");
	}

	/**
	 * Remaining code limitation: two tasks with IDENTICAL (equal-size) npc_id sets
	 * can't be told apart by most-specific-wins, so one kill credits both. In the
	 * real data this was Elder vs Ancient Custodian Stalker (both 14704) — now fixed
	 * by giving Ancient its own id (14520). This synthetic case documents that the
	 * code still can't split genuinely-identical id sets.
	 */
	@Test
	void testIdenticalNpcIds_bothCredit_codeLimitation() throws Exception
	{
		NuzlockeTask a = createTaskWithNpc("Task A", "slay_a", "SLAYER", 1, Arrays.asList(14704));
		NuzlockeTask b = createTaskWithNpc("Task B", "slay_b", "SLAYER", 1, Arrays.asList(14704));
		npcKillModule.addActiveTask(a);
		npcKillModule.addActiveTask(b);

		grantSlayerXp();
		simulateKill(mockNpc(14704, 1, "Custodian Stalker"));

		assertEquals(1, a.getCurrentProgress());
		assertEquals(1, b.getCurrentProgress(),
			"identical id sets can't be disambiguated — both credit (needs distinct ids in data)");
	}

	/**
	 * Data/type bug: some "... on Task" tasks ship as completion_type NPC_Kill, not
	 * SLAYER — e.g. "Defeat a Moss Giant on Task". The on-task gate only runs for
	 * SLAYER, so these credit even with NO slayer assignment ("on Task" unenforced).
	 */
	@Test
	void testOnTaskTypedNpcKill_creditsOffTask_documentsBug() throws Exception
	{
		NuzlockeTask moss = createTaskWithNpc("Defeat a Moss Giant on Task", "slay_moss_giant", "NPC_KILL", 1, Arrays.asList(200));
		npcKillModule.addActiveTask(moss);

		// No slayer assignment — and the gate is skipped entirely for NPC_KILL, so
		// getVarpValue(394) is never even read here.
		simulateKill(mockNpc(200, 1, "Moss Giant"));

		assertEquals(1, moss.getCurrentProgress(),
			"WRONG: an 'on Task' task typed NPC_KILL skips the slayer gate and credits off-task");
	}

	/**
	 * Fix (#3): most-specific match wins. An Ogress's ids are a subset of the broad
	 * Ogre task, so killing an Ogress credits ONLY the Ogress task, not the Ogre one.
	 */
	@Test
	void testMostSpecificMatch_subMonsterCreditsOnlySpecificTask() throws Exception
	{
		NuzlockeTask ogre = createTaskWithNpc("Defeat an Ogre on Task", "slay_ogre", "SLAYER", 1,
			Arrays.asList(136, 866, 867, 868, 7989, 7990, 7991, 7992));
		NuzlockeTask ogress = createTaskWithNpc("Defeat an Ogress on Task", "slay_ogress", "SLAYER", 1,
			Arrays.asList(7989, 7990, 7991, 7992));
		npcKillModule.addActiveTask(ogre);
		npcKillModule.addActiveTask(ogress);

		grantSlayerXp();
		simulateKill(mockNpc(7989, 1, "Ogress"));

		assertEquals(1, ogress.getCurrentProgress(), "the specific Ogress task credits");
		assertEquals(0, ogre.getCurrentProgress(), "the broad Ogre superset task must NOT also credit");
	}
}
