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

	/** Simulate an ON-TASK kill: a SINGLE Slayer XP gain at the current tick,
	 *  exactly as the game emits it (off-task kills award none). The baseline
	 *  comes from addActiveTask's seeding (mocked getSkillExperience = 0), NOT
	 *  from a sacrificial first event — the old two-event version of this
	 *  helper was papering over the swallowed-baseline bug that refused
	 *  single on-task kills in production. */
	private void grantSlayerXp()
	{
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
	 * Cruk's 25-pirate regression (session_2026-07-15 22:02): the server's
	 * event endpoints return a hardcoded verifiedProgress=1 receipt, and the
	 * async ack used to OVERWRITE local progress with it — every kill after
	 * the second stomped progress back to 1 and the task ping-ponged 1<->2
	 * forever. An ack may only ever RAISE local progress, never lower it.
	 */
	@Test
	void testEventAck_neverRegressesLocalProgress() throws Exception
	{
		NuzlockeTask pirates = createTaskWithNpc("Defeat a Pirate", "defeat_pirate", "NPC_KILL", 25, Arrays.asList(522));
		// First addActiveTask also sets the legacy activeTask pointer the ack path uses.
		npcKillModule.addActiveTask(pirates);

		// Two kills land locally.
		simulateKill(mockNpc(522, 1, "Pirate"));
		simulateKill(mockNpc(522, 2, "Pirate"));
		assertEquals(2, pirates.getCurrentProgress());

		// The kill-report ack arrives with the hardcoded receipt value.
		java.lang.reflect.Method m = findMethod(npcKillModule.getClass(), "handleVerificationResponse",
			net.runelite.client.plugins.chunkblazer.api.TaskVerificationResponse.class);
		m.setAccessible(true);
		m.invoke(npcKillModule, net.runelite.client.plugins.chunkblazer.api.TaskVerificationResponse.builder()
			.success(true).taskCompleted(false).verifiedProgress(1).build());

		assertEquals(2, pirates.getCurrentProgress(),
			"a verifiedProgress=1 receipt must not stomp locally observed progress");

		// Third kill continues from 2, not from a stomped 1.
		simulateKill(mockNpc(522, 3, "Pirate"));
		assertEquals(3, pirates.getCurrentProgress());

		// A genuinely AHEAD server value is still allowed to catch us up.
		m.invoke(npcKillModule, net.runelite.client.plugins.chunkblazer.api.TaskVerificationResponse.builder()
			.success(true).taskCompleted(false).verifiedProgress(7).build());
		assertEquals(7, pirates.getCurrentProgress(),
			"a server value ahead of local progress may raise it");
	}

	/**
	 * Mike's goblin regression (session_2026-07-15): onTaskCleared() fires on
	 * ROUTINE task-list refreshes and used to reset the Slayer XP sensor, so
	 * the next (single!) Slayer XP event was swallowed as a baseline and a
	 * genuinely on-task kill was refused. The sensor must survive refreshes.
	 */
	@Test
	void testSlayerGate_survivesRoutineTaskListRefresh() throws Exception
	{
		NuzlockeTask goblin = createTaskWithNpc("Defeat a Goblin on Task", "defeat_goblin_on_task", "SLAYER", 1, Arrays.asList(3034));
		npcKillModule.addActiveTask(goblin);

		// Routine refresh mid-session: clearTask() → onTaskCleared() → re-register.
		// Happens constantly (chunk unlocks, task rolls, region changes).
		npcKillModule.onTaskCleared();
		npcKillModule.addActiveTask(goblin);

		// ONE on-task kill = ONE Slayer XP event. Must credit.
		grantSlayerXp();
		simulateKill(mockNpc(3034, 1, "Goblin"));

		assertEquals(1, goblin.getCurrentProgress(),
			"a single on-task kill right after a task-list refresh must credit");
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
	 * Mistype fail-safe: a "... on Task" task shipped as completion_type NPC_Kill
	 * (an authoring slip — the data is clean as of 2026-07-14, but it happened
	 * before) must STILL pass the on-task slayer gate. The gate now keys on the
	 * task NAME as well as the type, so a mistype fails safe (gated) instead of
	 * fail-open (free off-task credit).
	 */
	@Test
	void testOnTaskTypedNpcKill_stillGatedByName() throws Exception
	{
		NuzlockeTask moss = createTaskWithNpc("Defeat a Moss Giant on Task", "slay_moss_giant", "NPC_KILL", 1, Arrays.asList(200));
		npcKillModule.addActiveTask(moss);

		// Off-task kill (no Slayer XP): must NOT credit despite the NPC_Kill type.
		simulateKill(mockNpc(200, 1, "Moss Giant"));
		assertEquals(0, moss.getCurrentProgress(),
			"an 'on Task' task must be slayer-gated even when mistyped as NPC_KILL");

		// On-task kill (Slayer XP in window): credits normally.
		grantSlayerXp();
		simulateKill(mockNpc(200, 1, "Moss Giant"));
		assertEquals(1, moss.getCurrentProgress(),
			"the same mistyped task still credits when genuinely on task");
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
