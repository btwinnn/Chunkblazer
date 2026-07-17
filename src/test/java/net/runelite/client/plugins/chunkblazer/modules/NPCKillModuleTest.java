package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.Actor;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.api.Hitsplat;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.TaskConstraints;
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
		setTick(100);

		// The restricted-kill freshness check samples NPC health each tick.
		lenient().when(client.getNpcs()).thenReturn(java.util.Collections.emptyList());
	}

	/** The game tick these tests are "at". Kills, XP and drains are sequenced against it. */
	private int now;

	private void setTick(int tick)
	{
		now = tick;
		lenient().when(client.getTickCount()).thenReturn(tick);
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

	/**
	 * Drive a death through the REAL event path: ActorDeath queues it, GameTick drains
	 * it. Going through the events (rather than reaching in and calling processNpcDeath)
	 * is what lets these tests see the tick-ordering bugs — an on-task kill's Slayer XP
	 * arrives on a LATER tick than the death, and only the drain loop knows to wait.
	 *
	 * The fight record is seeded via a hitsplat, exactly as the game does it. Note
	 * there is no interaction anywhere here: damage is the only thing that creates a
	 * stake in an NPC now, which is precisely why cannon kills work.
	 */
	private void simulateKill(NPC npc) throws Exception
	{
		fireMyHitsplat(npc, 10);
		killAndDrain(npc);
	}

	/** ActorDeath + the GameTick drain, without seeding any damage of our own. */
	private void killAndDrain(NPC npc)
	{
		ActorDeath death = mock(ActorDeath.class);
		when(death.getActor()).thenReturn(npc);
		npcKillModule.onActorDeath(death);
		npcKillModule.onGameTick(new GameTick());
	}

	/** Advance the clock by n ticks and pump a GameTick, so held deaths can resolve. */
	private void advance(int ticks)
	{
		setTick(now + ticks);
		npcKillModule.onGameTick(new GameTick());
	}

	/**
	 * An ON-TASK slayer kill, sequenced the way the game actually does it: the NPC
	 * dies, and the Slayer XP lands on a LATER tick (Mike's goblin — kill at 14:57:04,
	 * XP at 14:57:05). The old helper granted XP BEFORE the kill, which quietly
	 * encoded the very bug it was meant to guard: the gate only looked backwards, so
	 * XP-then-kill passed in the test while kill-then-XP failed in production.
	 */
	private void simulateOnTaskKill(NPC npc)
	{
		fireMyHitsplat(npc, 10);
		killAndDrain(npc);  // death is HELD here — its evidence hasn't arrived yet
		setTick(now + 1);
		grantSlayerXp();    // ...and lands a tick later
		npcKillModule.onGameTick(new GameTick());
	}

	/** An OFF-TASK kill: no Slayer XP ever arrives, so the hold times out. */
	private void simulateOffTaskKill(NPC npc)
	{
		fireMyHitsplat(npc, 10);
		killAndDrain(npc);
		advance(ON_TASK_WAIT_TICKS);
	}

	private static final int ON_TASK_WAIT_TICKS = 2;

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

	/** Simulate a SINGLE Slayer XP gain at the current tick, exactly as the game
	 *  emits it for an on-task kill (off-task kills award none). The baseline
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
		simulateOffTaskKill(mockNpc(100, 1, "Cow"));

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

		simulateOnTaskKill(mockNpc(100, 1, "Cow"));

		assertEquals(1, cowTask.getCurrentProgress(),
			"an on-task slayer kill (Slayer XP awarded) credits the task");
	}

	/**
	 * Mike's goblin (session_2026-07-16 14:57:04): he WAS on a goblin task (11
	 * remaining) and still got "Not on a slayer task for this monster". The Slayer XP
	 * arrives the tick AFTER the death — the log shows the verdict at :04 and the XP
	 * at :05 — but the gate only looked backwards, so it judged the kill on evidence
	 * that had not been sent yet and refused every genuine on-task kill.
	 *
	 * This is the ordering the old fixture had backwards, so it is spelled out
	 * explicitly rather than hidden in a helper: XP strictly AFTER the death.
	 */
	@Test
	void testSlayerGate_waitsForXpThatArrivesAfterTheDeath() throws Exception
	{
		NuzlockeTask goblin = createTaskWithNpc("Defeat a Goblin on Task", "defeat_goblin_on_task", "SLAYER", 11, Arrays.asList(3034));
		npcKillModule.addActiveTask(goblin);

		NPC npc = mockNpc(3034, 1, "Goblin");
		fireMyHitsplat(npc, 10);
		killAndDrain(npc); // tick 100: the goblin dies

		assertEquals(0, goblin.getCurrentProgress(),
			"the verdict must not be reached yet — the XP that decides it hasn't arrived");

		setTick(101);
		grantSlayerXp();   // tick 101: Slayer XP lands, as in Mike's log
		npcKillModule.onGameTick(new GameTick());

		assertEquals(1, goblin.getCurrentProgress(),
			"an on-task kill must credit once its Slayer XP arrives on the following tick");
	}

	/**
	 * The flip side: waiting for XP must not become a free pass. If it never arrives,
	 * the hold expires and the kill is ruled off-task.
	 */
	@Test
	void testSlayerGate_holdExpiresAndRulesOffTask() throws Exception
	{
		NuzlockeTask goblin = createTaskWithNpc("Defeat a Goblin on Task", "defeat_goblin_on_task", "SLAYER", 1, Arrays.asList(3034));
		npcKillModule.addActiveTask(goblin);

		NPC npc = mockNpc(3034, 1, "Goblin");
		fireMyHitsplat(npc, 10);
		killAndDrain(npc);
		advance(1);
		advance(1); // no XP ever arrives

		assertEquals(0, goblin.getCurrentProgress(),
			"a held death whose Slayer XP never arrives must be ruled off task");
	}

	// --- Relog / restricted-kill integrity (internal tester report, 2026-07-16) --

	private NuzlockeTask speedTask(int npcId, int maxTicks)
	{
		NuzlockeTask t = createTaskWithNpc("Defeat a Mugger in 16 Seconds", "defeat_mugger_fast", "NPC_KILL", 1, Arrays.asList(npcId));
		TaskConstraints c = new TaskConstraints();
		c.setTimeInTicks(maxTicks);
		t.setConstraints(c);
		return t;
	}

	private GameStateChanged gameState(GameState s)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		return e;
	}

	/**
	 * The relog exploit: soften the NPC, log out/in (combat tracking resets),
	 * finish it — the measured fight is only the post-relog tail, so the speed
	 * window passes. Restricted kills must belong to a fight STARTED a grace
	 * period after the session began.
	 */
	@Test
	void testSpeedKill_rejectedWhenFightStartsRightAfterRelog() throws Exception
	{
		NuzlockeTask task = speedTask(200, 10);
		npcKillModule.addActiveTask(task);

		// Session began at tick 95; fight starts at 98 (3 ticks later) and the mugger
		// dies at 100. Elapsed fight time is 2 ticks, so the TIME check alone would
		// pass — only the fresh-fight gate can refuse this kill.
		injectField(npcKillModule, "lastLoginTick", 95);

		NPC mugger = mockNpc(200, 1, "Mugger");
		setTick(98);
		fireMyHitsplat(mugger, 5); // combat starts here
		setTick(100);
		killAndDrain(mugger);

		assertEquals(0, task.getCurrentProgress(),
			"a speed kill finished right after a relog must not credit");
	}

	@Test
	void testSpeedKill_creditsWhenFightStartsWellAfterLogin() throws Exception
	{
		NuzlockeTask task = speedTask(200, 10);
		npcKillModule.addActiveTask(task);

		injectField(npcKillModule, "lastLoginTick", 30); // logged in 70 ticks ago

		NPC mugger = mockNpc(200, 1, "Mugger");
		setTick(98);
		fireMyHitsplat(mugger, 5); // fresh 2-tick fight
		setTick(100);
		killAndDrain(mugger);

		assertEquals(1, task.getCurrentProgress(), "a genuinely fresh fast kill credits");
	}

	/**
	 * Mike's note (2026-07-16): "some timed tasks are longer than 30s". A flat 30s
	 * grace leaves a task with a longer limit exposed — the fight can start after the
	 * grace and still be finished entirely with damage dealt before the relog. The
	 * grace must be at least the task's own limit.
	 */
	@Test
	void testFreshFightGrace_scalesToTasksOwnTimeLimit() throws Exception
	{
		NuzlockeTask task = speedTask(200, 100); // 60s limit — longer than the 30s grace
		npcKillModule.addActiveTask(task);

		injectField(npcKillModule, "lastLoginTick", 100);

		// Fight starts 60 ticks (36s) after login: past the flat 30s grace, but well
		// inside this task's own 60s window.
		NPC mugger = mockNpc(200, 1, "Mugger");
		setTick(160);
		fireMyHitsplat(mugger, 5);
		setTick(162);
		killAndDrain(mugger);

		assertEquals(0, task.getCurrentProgress(),
			"the grace must cover the task's own time limit, not a flat 30s");
	}

	private void fireMyHitsplat(NPC target, int dmg)
	{
		HitsplatApplied e = mock(HitsplatApplied.class);
		Hitsplat h = mock(Hitsplat.class);
		when(e.getActor()).thenReturn(target);
		when(e.getHitsplat()).thenReturn(h);
		lenient().when(h.isOthers()).thenReturn(false);
		when(h.isMine()).thenReturn(true);
		lenient().when(h.getAmount()).thenReturn(dmg);
		npcKillModule.onHitsplatApplied(e);
	}

	private void fireOtherPlayerHitsplat(NPC target)
	{
		HitsplatApplied e = mock(HitsplatApplied.class);
		Hitsplat h = mock(Hitsplat.class);
		when(e.getActor()).thenReturn(target);
		when(e.getHitsplat()).thenReturn(h);
		when(h.isOthers()).thenReturn(true);   // DAMAGE_OTHER* — another player
		lenient().when(h.isMine()).thenReturn(false);
		npcKillModule.onHitsplatApplied(e);
	}

	/**
	 * Exclusive-damage rule: a restricted (speed/equipment) kill on a monster
	 * ANOTHER player also damaged must not credit — closes the shared-spawn /
	 * duo-partner variant of the relog cheat (friend softens it, you last-hit).
	 */
	@Test
	void testRestrictedKill_rejectedWhenAnotherPlayerDamagedTarget() throws Exception
	{
		NuzlockeTask task = speedTask(200, 10);
		npcKillModule.addActiveTask(task);

		NPC mugger = mockNpc(200, 1, "Mugger");
		fireMyHitsplat(mugger, 5);       // our fight starts (combatStartTick = now)
		fireOtherPlayerHitsplat(mugger); // someone else damages it → contested

		killAndDrain(mugger);
		assertEquals(0, task.getCurrentProgress(),
			"a restricted kill on a monster another player damaged must not credit");
	}

	@Test
	void testRestrictedKill_soloDamageStillCredits() throws Exception
	{
		NuzlockeTask task = speedTask(200, 10);
		npcKillModule.addActiveTask(task);

		NPC mugger = mockNpc(200, 1, "Mugger");
		fireMyHitsplat(mugger, 5); // only our damage — no contest
		killAndDrain(mugger);
		assertEquals(1, task.getCurrentProgress(), "a solo restricted kill credits normally");
	}

	@Test
	void testExclusiveDamage_doesNotAffectPlainKillTasks() throws Exception
	{
		// A plain "defeat X" task (no time/equip constraint) credits even if
		// another player also hit the monster — exclusivity is only for
		// restricted kills.
		NuzlockeTask task = createTaskWithNpc("Defeat a Mugger", "defeat_mugger", "NPC_KILL", 1, Arrays.asList(200));
		npcKillModule.addActiveTask(task);

		NPC mugger = mockNpc(200, 1, "Mugger");
		fireMyHitsplat(mugger, 5);
		fireOtherPlayerHitsplat(mugger); // contested, but this task doesn't care
		killAndDrain(mugger);
		assertEquals(1, task.getCurrentProgress(),
			"a plain defeat task credits regardless of who else damaged the monster");
	}

	/**
	 * Cruk's green dragon (session_2026-07-16): a REAL login fires
	 * LOGIN_SCREEN → LOGGING_IN → LOADING → LOGGED_IN — the LOADING right
	 * before LOGGED_IN is part of the LOGIN, and the old "previous state !=
	 * LOADING" guard classified every genuine relog as a region crossing, so
	 * the fresh-fight gate never armed and his one-shot "in 0.0s" credited a
	 * 27-second speed task.
	 */
	@Test
	void testRealLoginSequence_armsFreshFightGate() throws Exception
	{
		NuzlockeTask task = speedTask(264, 45); // Defeat a Green Dragon in 27 Seconds
		npcKillModule.addActiveTask(task);

		npcKillModule.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		npcKillModule.onGameStateChanged(gameState(GameState.LOGGING_IN));
		npcKillModule.onGameStateChanged(gameState(GameState.LOADING));
		npcKillModule.onGameStateChanged(gameState(GameState.LOGGED_IN)); // arms lastLoginTick at 100

		// The one-shot lands immediately after the relog.
		simulateKill(mockNpc(264, 1, "Green dragon"));

		assertEquals(0, task.getCurrentProgress(),
			"a kill right after a REAL relog (LOADING precedes LOGGED_IN) must be gated");
	}

	@Test
	void testRegionCrossingDoesNotResetFightIntegrity() throws Exception
	{
		NuzlockeTask task = speedTask(200, 10);
		npcKillModule.addActiveTask(task);
		injectField(npcKillModule, "lastLoginTick", 30);

		NPC mugger = mockNpc(200, 1, "Mugger");
		setTick(98);
		fireMyHitsplat(mugger, 5); // a fight is under way

		// A region crossing fires LOADING → LOGGED_IN. It must NOT count as a
		// fresh login (that would wipe the in-progress fight and reject the kill).
		npcKillModule.onGameStateChanged(gameState(GameState.LOADING));
		npcKillModule.onGameStateChanged(gameState(GameState.LOGGED_IN));

		setTick(100);
		killAndDrain(mugger);
		assertEquals(1, task.getCurrentProgress(),
			"LOADING -> LOGGED_IN is a region crossing, not a relog");
	}

	/**
	 * The unequip variant: gear worn at ANY point during the fight must fail an
	 * equipment-restricted kill — removing it just before the killing blow (or
	 * around a relog) must not launder the earlier hits.
	 */
	@Test
	void testEquipRestriction_violationMidFightNotLaunderedByUnequip() throws Exception
	{
		NuzlockeTask task = createTaskWithNpc("Defeat a Mugger with No Equipment", "defeat_mugger_naked", "NPC_KILL", 1, Arrays.asList(200));
		TaskConstraints c = new TaskConstraints();
		c.setEquipNothing(true);
		task.setConstraints(c);
		npcKillModule.addActiveTask(task);

		NPC mugger = mockNpc(200, 1, "Mugger");

		// A mid-fight hit lands while a weapon is equipped.
		ItemContainer equipment = mock(ItemContainer.class);
		Item weapon = mock(Item.class);
		lenient().when(weapon.getId()).thenReturn(1333);
		lenient().when(equipment.getItems()).thenReturn(new Item[]{weapon});
		when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(equipment);

		HitsplatApplied hit = mock(HitsplatApplied.class);
		when(hit.getActor()).thenReturn(mugger);
		when(hit.getHitsplat()).thenReturn(hitsplat);
		when(hitsplat.isMine()).thenReturn(true);
		lenient().when(hitsplat.getAmount()).thenReturn(5);
		npcKillModule.onHitsplatApplied(hit);

		// Unequip everything before the killing blow. (lenient: the fix rejects
		// at the mid-fight taint check BEFORE re-reading equipment, so this
		// stub going unused is itself evidence the gate fired early.)
		lenient().when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(null);

		killAndDrain(mugger);
		assertEquals(0, task.getCurrentProgress(),
			"restricted gear worn mid-fight must not be laundered by unequipping for the killing blow");
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
		simulateOnTaskKill(mockNpc(3034, 1, "Goblin"));

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

		simulateOnTaskKill(mockNpc(14704, 1, "Custodian Stalker"));

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
		simulateOffTaskKill(mockNpc(200, 1, "Moss Giant"));
		assertEquals(0, moss.getCurrentProgress(),
			"an 'on Task' task must be slayer-gated even when mistyped as NPC_KILL");

		// On-task kill (Slayer XP in window): credits normally.
		simulateOnTaskKill(mockNpc(200, 2, "Moss Giant"));
		assertEquals(1, moss.getCurrentProgress(),
			"the same mistyped task still credits when genuinely on task");
	}

	// --- Cannon kills (Mike + Cruk, 2026-07-16) ---------------------------------

	/**
	 * Mike: "defeating a scorpion with a cannon does not count towards anything."
	 * Tracking used to hang off onInteractingChanged, so an NPC the player never
	 * clicked had no fight record, every hitsplat on it was dropped, and the death
	 * was rejected for 0 damage — silently. (In his log the cannon kills produce no
	 * [NPCKILL-DEBUG] line at all, while his three manual kills each produce one.)
	 * A cannonball is our damage, so it is our kill.
	 */
	@Test
	void testCannonOnlyKill_credits() throws Exception
	{
		NuzlockeTask task = createTaskWithNpc("Defeat some Scorpions", "defeat_scorpions", "NPC_KILL", 5, Arrays.asList(3024));
		npcKillModule.addActiveTask(task);

		// No interaction ever — just our cannonball landing on it.
		NPC scorpion = mockNpc(3024, 1, "Scorpion");
		fireMyHitsplat(scorpion, 8);
		killAndDrain(scorpion);

		assertEquals(1, task.getCurrentProgress(),
			"a kill dealt entirely by our own cannon must credit a plain defeat task");
	}

	/**
	 * Cruk: "had a task to kill a scorpion in one hit, a cannonball hit it for 90% of
	 * its hp, i finished off the last 10% with whip and it counted as 'one hit'."
	 *
	 * "One hit" tasks are authored as a 1-tick time limit (see defeat_scorpion_first_hit
	 * in his log: "max allowed is 1 ticks"), and the clock used to start at the first
	 * hit the plugin could SEE — which excluded the cannon. Cannon damage now starts
	 * the clock like any other damage of ours, so the real fight length is measured.
	 */
	@Test
	void testCannonSoftenedThenFinished_doesNotPassAsAOneHitKill() throws Exception
	{
		NuzlockeTask task = speedTask(3024, 1); // "Defeat a Scorpion in the First Hit"
		npcKillModule.addActiveTask(task);

		NPC scorpion = mockNpc(3024, 1, "Scorpion");
		setTick(100);
		fireMyHitsplat(scorpion, 9);  // cannonball takes it to 10% — the clock starts HERE
		setTick(110);
		fireMyHitsplat(scorpion, 1);  // whip finishes it, 10 ticks later
		killAndDrain(scorpion);

		assertEquals(0, task.getCurrentProgress(),
			"cannon-softening then finishing must not read as a one-hit kill");
	}

	/**
	 * The same cheat with the cannon replaced by anything else that chipped the NPC
	 * first (another player's cannon, a passing NPC, a previous session). If it wasn't
	 * at full health when we first hit it, a restricted kill can't be judged.
	 */
	@Test
	void testRestrictedKill_rejectedWhenMonsterWasAlreadyDamaged() throws Exception
	{
		NuzlockeTask task = speedTask(200, 10);
		npcKillModule.addActiveTask(task);

		NPC mugger = mockNpc(200, 1, "Mugger");
		// It is already on 20% health at the end of the previous tick.
		lenient().when(mugger.getHealthRatio()).thenReturn(6);
		lenient().when(mugger.getHealthScale()).thenReturn(30);
		lenient().when(client.getNpcs()).thenReturn(java.util.Collections.singletonList(mugger));
		npcKillModule.onGameTick(new GameTick()); // sample health

		setTick(101);
		fireMyHitsplat(mugger, 5); // our first hit lands on a pre-softened monster
		killAndDrain(mugger);

		assertEquals(0, task.getCurrentProgress(),
			"a restricted kill must start from full health");
	}

	/**
	 * The freshness gate must not refuse honest kills: a monster at full health when
	 * we open on it credits normally, even though it is obviously damaged by the time
	 * it dies.
	 */
	@Test
	void testRestrictedKill_fullHealthMonsterStillCredits() throws Exception
	{
		NuzlockeTask task = speedTask(200, 10);
		npcKillModule.addActiveTask(task);

		NPC mugger = mockNpc(200, 1, "Mugger");
		lenient().when(mugger.getHealthRatio()).thenReturn(30); // untouched
		lenient().when(mugger.getHealthScale()).thenReturn(30);
		lenient().when(client.getNpcs()).thenReturn(java.util.Collections.singletonList(mugger));
		npcKillModule.onGameTick(new GameTick());

		setTick(101);
		fireMyHitsplat(mugger, 5);
		killAndDrain(mugger);

		assertEquals(1, task.getCurrentProgress(),
			"opening on a full-health monster is exactly what the gate is meant to allow");
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

		simulateOnTaskKill(mockNpc(7989, 1, "Ogress"));

		assertEquals(1, ogress.getCurrentProgress(), "the specific Ogress task credits");
		assertEquals(0, ogre.getCurrentProgress(), "the broad Ogre superset task must NOT also credit");
	}
}
