package com.chunkblazer.modules;

import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.chat.ChatMessageManager;
import com.chunkblazer.NuzlockeTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AgilityModule.
 * Tests agility course lap detection via XP gains.
 */
@ExtendWith(MockitoExtension.class)
class AgilityModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private AgilityModule agilityModule;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(agilityModule, "client", client);
		injectField(agilityModule, "clientThread", clientThread);
		injectField(agilityModule, "eventBus", eventBus);
		injectField(agilityModule, "config", config);

		agilityModule.setCompletionCallback(completionCallback);
		// Level-capable by default; the level-gate tests lower it.
		lenient().when(client.getBoostedSkillLevel(Skill.AGILITY)).thenReturn(99);
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
		assertEquals("AGILITY", agilityModule.getCompletionType());
	}

	@Test
	void testCanHandle_AgilityType()
	{
		NuzlockeTask task = createTestTask("Complete Lap", "complete_lap", "AGILITY", 1);
		assertTrue(agilityModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Run Around", "run_around", "TRAVEL", 1);
		assertFalse(agilityModule.canHandle(task));
	}

	@Test
	void testAddActiveTask()
	{
		NuzlockeTask task = createTestTask("Complete 5 Laps", "complete_laps", "AGILITY", 5);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(1000);

		agilityModule.addActiveTask(task);

		assertEquals(1, agilityModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTestTask("Complete 5 Laps", "complete_laps", "AGILITY", 5);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(1000);

		agilityModule.addActiveTask(task);
		agilityModule.onTaskCleared();

		assertTrue(agilityModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		agilityModule.startUp();
		verify(eventBus).register(agilityModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		agilityModule.shutDown();
		verify(eventBus).unregister(agilityModule);
	}

	// Lap-end object IDs (from each course's required_object in JSON).
	private static final int DRAYNOR_LAP_END = 11632;        // Draynor Rooftop Crate
	private static final int VARROCK_LAP_END = 14841;        // Edge (Varrock Rooftop Course)
	private static final int FALADOR_LAP_END = 14925;        // Falador Rooftop Edge
	private static final int SEERS_LAP_END = 14931;          // Seers' Rooftop Edge
	private static final int ARDOUGNE_LAP_END = 15612;       // Gap (Ardougne Rooftop Course)#4
	private static final int AL_KHARID_LAP_END = 14847;      // Al Kharid Roof-top Beams (placeholder if not in JSON)
	private static final int CANIFIS_LAP_END = 14922;        // Canifis Pole-vault (placeholder)
	private static final int GNOME_LAP_END = 4059;           // Gnome end Pipe (placeholder)
	private static final int BARBARIAN_LAP_END = 20210;      // Barbarian Crumbling wall (placeholder)
	private static final int COLOSSAL_BASIC_LAP_END = 51354; // Colossal Wyrm Anti-toxin Vine (placeholder)

	private MenuOptionClicked mockObjectClick(int objectId, MenuAction action)
	{
		MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuAction()).thenReturn(action);
		lenient().when(event.getId()).thenReturn(objectId);
		lenient().when(event.getMenuOption()).thenReturn("Climb");
		return event;
	}

	// A "Walk here" click — used to simulate the player cancelling/clicking away.
	private MenuOptionClicked mockWalkClick()
	{
		MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuAction()).thenReturn(MenuAction.WALK);
		lenient().when(event.getId()).thenReturn(0);
		return event;
	}

	// Simulate the local player starting an animation (proof they actually used
	// the obstacle they clicked).
	private void simulatePlayerAnimation(int animId)
	{
		lenient().when(localPlayer.getAnimation()).thenReturn(animId);
		AnimationChanged ev = mock(AnimationChanged.class);
		lenient().when(ev.getActor()).thenReturn(localPlayer);
		agilityModule.onAnimationChanged(ev);
		agilityModule.onGameTick(new GameTick());
	}

	/**
	 * Walk one course through AgilityModule and assert that none of the
	 * obstacle XP drops credits the lap-style task, but the final lap-end
	 * bonus does — gated on the player having clicked the course's specific
	 * lap-end obstacle (the required_object in JSON).
	 *
	 * <p>Used by every per-course test below — same shape, different XP
	 * numbers (sourced from the OSRS Wiki) and different lap-end object IDs.
	 */
	private void runOneLap(String taskName, String taskId, int[] obstacleXps, int lapBonus, int lapEndObjectId)
	{
		NuzlockeTask task = createTaskWithRequiredObject(taskName, taskId, "AGILITY", 1,
			Collections.singletonList(lapEndObjectId));
		task.setHasRequiredObject(true);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(task);

		int xp = 0;
		for (int gain : obstacleXps)
		{
			xp += gain;
			agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, xp, 1, 1));
			assertEquals(0, task.getCurrentProgress(),
				taskName + ": obstacle gain " + gain + " XP must not credit lap progress");
			assertFalse(task.isCompleted(),
				taskName + ": task must not complete on intra-lap obstacle XP");
		}

		// Now the player clicks the lap-end obstacle, then the lap-bonus XP fires.
		agilityModule.onMenuOptionClicked(mockObjectClick(lapEndObjectId, MenuAction.GAME_OBJECT_FIRST_OPTION));
		xp += lapBonus;
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, xp, 1, 1));

		assertEquals(1, task.getCurrentProgress(),
			taskName + ": " + lapBonus + " XP lap bonus should credit +1");
		assertTrue(task.isCompleted(),
			taskName + ": 1-lap task should complete on lap-end bonus");

		// Clean up so the next per-course test starts with no active task.
		agilityModule.onTaskCleared();
	}

	/**
	 * Dors' report: spam-clicking the Draynor crate mid-jump credited the lap 5 times.
	 * Each click re-armed it and each animation frame confirmed it; one use is one credit,
	 * and the next real lap still counts.
	 */
	@Test
	void spamClickLaps()
	{
		NuzlockeTask task = createTaskWithRequiredObject("Draynor Laps", "complete_draynor_roof", "AGILITY", 18,
			Collections.singletonList(DRAYNOR_LAP_END));
		task.setHasRequiredObject(true);
		agilityModule.addActiveTask(task);

		jumpAt(100, 827);
		for (int frame = 1; frame <= 4; frame++)
		{
			jumpAt(100 + frame, 827 + frame); // spam click, next frame of the same jump
		}
		assertEquals(1, task.getCurrentProgress());

		jumpAt(160, 827); // the next lap, a minute later
		assertEquals(2, task.getCurrentProgress());
	}

	@Test
	void lapMessageParsed()
	{
		NuzlockeTask task = new com.google.gson.Gson().fromJson(
			"{\"taskID\":\"complete_draynor_roof\",\"lap_message\":\"Draynor Village Rooftop\"}", NuzlockeTask.class);
		assertEquals("Draynor Village Rooftop", task.getLapMessage());
	}

	/**
	 * Course tasks count laps from "Your <course> lap count is: N.". The first lap's
	 * click and message are one lap; once the message is proven, clicks stop counting.
	 */
	@Test
	void lapMessageCountsLaps()
	{
		NuzlockeTask task = createTaskWithRequiredObject("Draynor Laps", "complete_draynor_roof", "AGILITY", 18,
			Collections.singletonList(DRAYNOR_LAP_END));
		task.setHasRequiredObject(true);
		task.setLapMessage("Draynor Village Rooftop");
		agilityModule.addActiveTask(task);

		jumpAt(100, 827);
		lapMessageAt(103, "Draynor Village Rooftop", 5);
		assertEquals(1, task.getCurrentProgress());

		jumpAt(160, 827);
		assertEquals(1, task.getCurrentProgress());
		lapMessageAt(163, "Draynor Village Rooftop", 6);
		assertEquals(2, task.getCurrentProgress());

		lapMessageAt(200, "Canifis Rooftop", 3);
		assertEquals(2, task.getCurrentProgress());
	}

	private void lapMessageAt(int tick, String course, int laps)
	{
		lenient().when(client.getTickCount()).thenReturn(tick);
		ChatMessage msg = mock(ChatMessage.class);
		when(msg.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
		when(msg.getMessage()).thenReturn("Your " + course + " lap count is: <col=ff0000>" + laps + "</col>.");
		agilityModule.onChatMessage(msg);
	}

	private void jumpAt(int tick, int animation)
	{
		lenient().when(client.getTickCount()).thenReturn(tick);
		agilityModule.onMenuOptionClicked(mockObjectClick(DRAYNOR_LAP_END, MenuAction.GAME_OBJECT_FIRST_OPTION));
		simulatePlayerAnimation(animation);
	}

	/**
	 * Mike's bug #22: Draynor Agility laps "complete on START not finish".
	 * Per-obstacle XP at this course is 5–8, lap-end bonus is 79. Threshold
	 * 30 keeps obstacles from crediting and lets the bonus through.
	 */
	@Test
	void testDraynorLap_OnlyCompletesOnLap()
	{
		runOneLap("Draynor Lap", "complete_draynor_roof",
			new int[]{5, 8, 8, 7, 7, 5, 5}, 79, DRAYNOR_LAP_END);
	}

	@Test
	void testAlKharidLap()
	{
		// Al Kharid Rooftop (level 20). Obstacles 5–8 XP, lap bonus 180 XP.
		runOneLap("Al Kharid Lap", "complete_kharid_roof",
			new int[]{5, 5, 5, 8, 5, 5, 8}, 180, AL_KHARID_LAP_END);
	}

	@Test
	void testVarrockLap()
	{
		// Varrock Rooftop (level 30). Obstacles 5–8 XP, lap bonus 238 XP.
		runOneLap("Varrock Lap", "complete_varrock_roof",
			new int[]{5, 8, 5, 8, 5, 8}, 238, VARROCK_LAP_END);
	}

	@Test
	void testCanifisLap()
	{
		// Canifis Rooftop (level 40). Obstacles 8–9 XP, lap bonus 240 XP.
		runOneLap("Canifis Lap", "complete_canifis_roof",
			new int[]{8, 9, 8, 9, 9, 8}, 240, CANIFIS_LAP_END);
	}

	@Test
	void testFaladorLap()
	{
		// Falador Rooftop (level 50). Obstacles 5–12 XP, lap bonus 440 XP.
		runOneLap("Falador Lap", "complete_falador_roof",
			new int[]{7, 12, 12, 7, 5, 5, 12, 8, 9}, 440, FALADOR_LAP_END);
	}

	@Test
	void testSeersLap()
	{
		// Seers Rooftop (level 60). Obstacles uniform 9 XP, lap bonus 570.
		// Real-world Seers' first obstacle is ~45 XP, well above the old 30 threshold.
		runOneLap("Seers Lap", "complete_seers_roof",
			new int[]{45, 9, 9, 9, 9}, 570, SEERS_LAP_END);
	}

	@Test
	void testArdougneLap()
	{
		// Ardougne Rooftop (level 90). Obstacles 11–22 XP — the highest single
		// rooftop obstacle XP we test. Lap bonus 793 XP. This is the course
		// that justifies the 30 threshold being above ~22.
		// Real-world Ardougne XPs (Mike's measurements): 43 climb, 65 first jump,
		// 50 plank, 21 second jump, 28 hop, 57 shimmy, 625 dismount.
		runOneLap("Ardougne Lap", "complete_ardougne_roof",
			new int[]{43, 65, 50, 21, 28, 57}, 625, ARDOUGNE_LAP_END);
	}

	@Test
	void testGnomeAgilityLap()
	{
		// Gnome Stronghold (level 1, non-rooftop). Obstacles 2–8 XP. Lap end
		// gives ~39.5 XP — the smallest lap bonus across all courses, which
		// is what determines the lower bound of the LAP_XP_THRESHOLD.
		runOneLap("Gnome Course Lap", "complete_gnome_course",
			new int[]{8, 6, 6, 8, 5, 3, 3}, 39, GNOME_LAP_END);
	}

	@Test
	void testBarbarianOutpostLap()
	{
		// Barbarian Outpost (level 35). Uniform 13.5 XP per obstacle (rounded
		// down by client to 13), lap bonus 152.5 (rounded to 152 here).
		runOneLap("Barbarian Lap", "complete_barbarian_course",
			new int[]{13, 13, 13, 13, 13}, 152, BARBARIAN_LAP_END);
	}

	@Test
	void testColossalWyrmBasicPath()
	{
		// Colossal Wyrm Basic Path (level 50, Varlamore). Obstacles ~7 XP,
		// path-completion bonus 75 XP.
		runOneLap("Colossal Wyrm Basic", "agility_level_50_path_colossal_wyrm_course",
			new int[]{7, 7, 7, 7, 7}, 75, COLOSSAL_BASIC_LAP_END);
	}

	/**
	 * Shortcut path: a single small XP gain has to credit immediately. These
	 * tasks have no required_object in JSON, so AgilityModule should fall
	 * back to the lower SHORTCUT_XP_THRESHOLD (5).
	 */
	@Test
	void testShortcut_SingleSmallXpGainCredits()
	{
		NuzlockeTask task = createTestTask("Use the Level 21 Underwall Tunnel",
			"agility_level_21_underwall_tunnel", "AGILITY", 1);
		task.setHasRequiredObject(false); // shortcut tasks have no required_object

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(task);

		// Underwall tunnel awards a small one-shot XP gain (~8 XP). Below
		// LAP_XP_THRESHOLD=30 but above SHORTCUT_XP_THRESHOLD=5.
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 8, 21, 21));

		assertEquals(1, task.getCurrentProgress(),
			"Shortcut task should credit on a single small Agility XP gain");
		assertTrue(task.isCompleted(),
			"1-quantity shortcut task should complete on the only XP event");
	}

	// --- Cross-contamination tests (Mike's bug) -------------------------------------------------

	/**
	 * Mike's repro: Ardougne and Seers lap tasks both active. He clicked the FIRST
	 * Ardougne obstacle (Wooden Beams, 52 XP — above the old 30 threshold). The
	 * pre-fix code credited both Ardougne AND Seers because neither knew which
	 * course the XP came from.
	 */
	@Test
	void testArdougneFirstObstacle_DoesNotCreditAnyLap()
	{
		NuzlockeTask ardougne = createTaskWithRequiredObject(
			"Ardougne Lap", "complete_ardougne_roof", "AGILITY", 1,
			Collections.singletonList(ARDOUGNE_LAP_END));
		ardougne.setHasRequiredObject(true);
		NuzlockeTask seers = createTaskWithRequiredObject(
			"Seers Lap", "complete_seers_roof", "AGILITY", 1,
			Collections.singletonList(SEERS_LAP_END));
		seers.setHasRequiredObject(true);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(ardougne);
		agilityModule.addActiveTask(seers);

		// Player clicks the first Ardougne obstacle (NOT a watched lap-end object).
		// Real-world XP for the first obstacle is 43 (Mike's measurement).
		final int FIRST_OBSTACLE = 15609;
		agilityModule.onMenuOptionClicked(mockObjectClick(FIRST_OBSTACLE, MenuAction.GAME_OBJECT_FIRST_OPTION));
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 43, 1, 1));

		verify(completionCallback, never()).onProgressUpdated(eq(ardougne), anyInt());
		verify(completionCallback, never()).onProgressUpdated(eq(seers), anyInt());
	}

	/**
	 * Ardougne and Seers both active, player completes an Ardougne lap.
	 * Only Ardougne should credit.
	 */
	@Test
	void testArdougneLapCompletion_DoesNotCreditSeers()
	{
		NuzlockeTask ardougne = createTaskWithRequiredObject(
			"Ardougne Lap", "complete_ardougne_roof", "AGILITY", 1,
			Collections.singletonList(ARDOUGNE_LAP_END));
		ardougne.setHasRequiredObject(true);
		NuzlockeTask seers = createTaskWithRequiredObject(
			"Seers Lap", "complete_seers_roof", "AGILITY", 1,
			Collections.singletonList(SEERS_LAP_END));
		seers.setHasRequiredObject(true);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(ardougne);
		agilityModule.addActiveTask(seers);

		agilityModule.onMenuOptionClicked(mockObjectClick(ARDOUGNE_LAP_END, MenuAction.GAME_OBJECT_FIRST_OPTION));
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 625, 1, 1));

		verify(completionCallback).onProgressUpdated(eq(ardougne), eq(1));
		verify(completionCallback, never()).onProgressUpdated(eq(seers), anyInt());
	}

	/**
	 * Real Ardougne mechanics: clicking Gap #4 fires BOTH a shimmy/jump XP event
	 * (57) AND the dismount bonus (625) within the same tick window. A multi-lap
	 * task must credit exactly once per click, not twice.
	 */
	@Test
	void testMultiLapTask_OneClickCreditsOnce()
	{
		NuzlockeTask ardougne = createTaskWithRequiredObject(
			"Run 5 Ardougne Laps", "ardougne_5_laps", "AGILITY", 5,
			Collections.singletonList(ARDOUGNE_LAP_END));
		ardougne.setHasRequiredObject(true);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(ardougne);

		// One click of the lap-end, then BOTH lap-end XP events fire (shimmy + dismount).
		agilityModule.onMenuOptionClicked(mockObjectClick(ARDOUGNE_LAP_END, MenuAction.GAME_OBJECT_FIRST_OPTION));
		int xp = 0;
		xp += 57;
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, xp, 1, 1));
		xp += 625;
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, xp, 1, 1));

		assertEquals(1, ardougne.getCurrentProgress(),
			"One lap-end click should credit +1, not +2, even though two ≥30-XP events fired");
		assertFalse(ardougne.isCompleted(),
			"5-lap task should still need 4 more laps after one click");
	}

	/**
	 * "Hop on the course" semantics: clicking the watched obstacle and getting
	 * ANY positive Agility XP within the window credits the lap, even small
	 * obstacle XPs like Draynor's 5-8 per obstacle. Multi-XP-event protection
	 * is now handled by consume-after-credit, not by a high XP threshold.
	 */
	@Test
	void testHopOnCourse_SmallXpCredits()
	{
		NuzlockeTask draynor = createTaskWithRequiredObject(
			"Draynor Lap", "complete_draynor_roof", "AGILITY", 1,
			Collections.singletonList(DRAYNOR_LAP_END));
		draynor.setHasRequiredObject(true);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(draynor);

		// Click the start crate, get the small obstacle XP for climbing on (~5 XP).
		agilityModule.onMenuOptionClicked(mockObjectClick(DRAYNOR_LAP_END, MenuAction.GAME_OBJECT_FIRST_OPTION));
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 5, 1, 1));

		verify(completionCallback).onProgressUpdated(eq(draynor), eq(1));
		assertEquals(1, draynor.getCurrentProgress());
	}

	// --- Shortcut mis-completion (the reported bug) ----------------------------------------------
	//
	// 49 of 73 AGILITY tasks ship with NO required_object. AgilityModule credits
	// those "shortcut" tasks on ANY Agility XP >= SHORTCUT_XP_THRESHOLD, with no
	// idea which obstacle produced it. The next three tests CHARACTERIZE the
	// resulting bugs (they assert today's wrong behaviour, with WRONG: notes), and
	// the two after them prove the fix direction: once a shortcut carries its real
	// required_object id, the existing object-gated path isolates it correctly.

	/**
	 * Cross-type contamination: an objectless shortcut task is credited by Agility
	 * XP that came from something else entirely — e.g. an obstacle on a rooftop lap
	 * the player is also running. No shortcut object of its own was ever used.
	 */
	@Test
	void testObjectlessShortcut_anyXpCredits_bug()
	{
		NuzlockeTask shortcut = createTestTask(
			"Use the Rocks Agility Shortcut", "agility_rocks_shortcut", "AGILITY", 1);
		shortcut.setHasRequiredObject(false); // matches the JSON: no required_object

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(shortcut);

		// A single unrelated obstacle XP drop (e.g. a Draynor rooftop step ~8 XP).
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 8, 1, 1));

		assertEquals(1, shortcut.getCurrentProgress(),
			"WRONG: objectless shortcut is credited by unrelated Agility XP — it has no obstacle of its own to gate on");
	}

	/**
	 * Cross-task contamination: two objectless shortcut tasks active, the player
	 * uses ONE shortcut, and BOTH complete off the single XP event.
	 */
	@Test
	void testObjectlessShortcuts_creditBoth_bug()
	{
		NuzlockeTask tunnel = createTestTask(
			"Use the Level 21 Agility Underwall Tunnel", "agility_level_21_underwall_tunnel", "AGILITY", 1);
		tunnel.setHasRequiredObject(false);
		NuzlockeTask stones = createTestTask(
			"Use the Agility Stepping Stones", "agility_stepping_stones", "AGILITY", 1);
		stones.setHasRequiredObject(false);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(tunnel);
		agilityModule.addActiveTask(stones);

		// Player uses ONE shortcut → one XP event.
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 8, 1, 1));

		assertEquals(1, tunnel.getCurrentProgress());
		assertEquals(1, stones.getCurrentProgress(),
			"WRONG: using one shortcut credits every active objectless shortcut task");
	}

	/**
	 * "It completes a different one." A shortcut was given a wiki object id that
	 * doesn't match what the client actually reports, while a *different* active
	 * task happens to hold the real runtime id. Clicking the real object credits
	 * the wrong task and never the intended one. (Reproduces the "ids don't line
	 * up" symptom; the [AGILITY-DEBUG] log added to AgilityModule surfaces the
	 * real id so the JSON can be corrected.)
	 */
	@Test
	void testWrongWikiId_creditsOtherTask_bug()
	{
		final int WIKI_ID_WRONG = 16545;    // taken from the wiki, but NOT what the client reports
		final int REAL_RUNTIME_ID = 11631;  // what MenuOptionClicked actually carries

		NuzlockeTask rocks = createTaskWithRequiredObject(
			"Use the Rocks Agility Shortcut", "agility_rocks_shortcut", "AGILITY", 1,
			Collections.singletonList(WIKI_ID_WRONG));
		rocks.setHasRequiredObject(true);
		NuzlockeTask other = createTaskWithRequiredObject(
			"Some Other Obstacle", "agility_other_obstacle", "AGILITY", 1,
			Collections.singletonList(REAL_RUNTIME_ID));
		other.setHasRequiredObject(true);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(rocks);
		agilityModule.addActiveTask(other);

		// Player clicks the REAL rocks object (the id the client reports) + gains XP.
		agilityModule.onMenuOptionClicked(mockObjectClick(REAL_RUNTIME_ID, MenuAction.GAME_OBJECT_FIRST_OPTION));
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 8, 1, 1));

		assertEquals(0, rocks.getCurrentProgress(),
			"WRONG: the rocks shortcut holds a wiki id the client never reports, so it never credits");
		assertEquals(1, other.getCurrentProgress(),
			"WRONG: a different task that happens to hold the real runtime id credits instead");
	}

	/**
	 * Fix direction: give each shortcut its REAL required_object id. The existing
	 * object-gated path then isolates them — using one shortcut credits only that
	 * task, even with several shortcuts active.
	 */
	@Test
	void testObjectGatedShortcuts_onlyUsedCredits()
	{
		final int TUNNEL_ID = 16529;
		final int STONES_ID = 16533;

		NuzlockeTask tunnel = createTaskWithRequiredObject(
			"Use the Level 21 Agility Underwall Tunnel", "agility_level_21_underwall_tunnel", "AGILITY", 1,
			Collections.singletonList(TUNNEL_ID));
		tunnel.setHasRequiredObject(true);
		NuzlockeTask stones = createTaskWithRequiredObject(
			"Use the Agility Stepping Stones", "agility_stepping_stones", "AGILITY", 1,
			Collections.singletonList(STONES_ID));
		stones.setHasRequiredObject(true);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(tunnel);
		agilityModule.addActiveTask(stones);

		// Use the tunnel: click ITS object, then gain XP.
		agilityModule.onMenuOptionClicked(mockObjectClick(TUNNEL_ID, MenuAction.GAME_OBJECT_FIRST_OPTION));
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 8, 1, 1));

		assertEquals(1, tunnel.getCurrentProgress(), "the shortcut actually used credits");
		assertEquals(0, stones.getCurrentProgress(), "the other shortcut must NOT credit");
	}

	private static final int SHILO_STEPPING_STONE = 16466; // captured in-game (watched=true, gives NO XP)

	private NuzlockeTask shiloSteppingTask()
	{
		NuzlockeTask t = createTaskWithRequiredObject(
			"Shilo Village Stepping Stones", "shilo_stepping_stones", "AGILITY", 1,
			Collections.singletonList(SHILO_STEPPING_STONE));
		t.setHasRequiredObject(true);
		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(t);
		return t;
	}

	/**
	 * Mike's "object IDs without XP drops did not work": stepping stones/pipes give
	 * NO Agility XP. The fix credits on the watched click once USE is confirmed by
	 * a player animation — so a no-XP obstacle still credits.
	 */
	@Test
	void testObjectGatedTask_creditsOnAnimation()
	{
		NuzlockeTask stones = shiloSteppingTask();

		agilityModule.onMenuOptionClicked(mockObjectClick(SHILO_STEPPING_STONE, MenuAction.GAME_OBJECT_FIRST_OPTION));
		simulatePlayerAnimation(769); // player crosses the stone — no XP fires

		assertEquals(1, stones.getCurrentProgress(),
			"object-gated task credits on confirmed use (animation) even with zero Agility XP");
		assertTrue(stones.isCompleted());
	}

	/**
	 * The "no level" / accidental-click case: the player clicks but never actually
	 * uses it (no animation, no XP) — must NOT credit.
	 */
	@Test
	void testObjectGatedTask_clickOnlyNotCredited()
	{
		NuzlockeTask stones = shiloSteppingTask();

		agilityModule.onMenuOptionClicked(mockObjectClick(SHILO_STEPPING_STONE, MenuAction.GAME_OBJECT_FIRST_OPTION));
		// No animation, no XP — e.g. they lack the level, or never reached it.

		assertEquals(0, stones.getCurrentProgress(),
			"a click that produces no animation/XP (no level, or walked away) must not credit");
		assertFalse(stones.isCompleted());
	}

	/**
	 * The "click then click away" case: clicking elsewhere cancels the pending
	 * traversal, so a later animation does not credit it.
	 */
	@Test
	void testObjectGatedTask_clickAwayCancels()
	{
		NuzlockeTask stones = shiloSteppingTask();

		agilityModule.onMenuOptionClicked(mockObjectClick(SHILO_STEPPING_STONE, MenuAction.GAME_OBJECT_FIRST_OPTION));
		agilityModule.onMenuOptionClicked(mockWalkClick()); // changed mind, walked away
		simulatePlayerAnimation(769);                       // some later animation

		assertEquals(0, stones.getCurrentProgress(),
			"clicking away cancels the pending traversal; a later animation must not credit");
	}

	/**
	 * And the unrelated-XP contamination is gone too once gated: obstacle XP from
	 * a rooftop lap (no shortcut object clicked) does not credit a gated shortcut.
	 */
	@Test
	void testObjectGatedShortcut_ignoresOtherXp()
	{
		final int TUNNEL_ID = 16529;
		NuzlockeTask tunnel = createTaskWithRequiredObject(
			"Use the Level 21 Agility Underwall Tunnel", "agility_level_21_underwall_tunnel", "AGILITY", 1,
			Collections.singletonList(TUNNEL_ID));
		tunnel.setHasRequiredObject(true);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(tunnel);

		// Unrelated obstacle XP, no tunnel click.
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, 8, 1, 1));

		assertEquals(0, tunnel.getCurrentProgress(),
			"a gated shortcut is not credited by Agility XP unless its own object was clicked");
	}

	// --- Level gate (Tokays: the Colossal Wyrm advanced ladder at 50 Agility) ----------

	private static final int WYRM_ADVANCED_LADDER = 55191;

	private NuzlockeTask wyrmAdvancedTask()
	{
		NuzlockeTask task = createTaskWithRequiredObject("Take the Advanced Path at the Colossal Wyrm Agility Course",
			"agility_level_62_path_colossal_wyrm_course", "AGILITY", 1, Collections.singletonList(WYRM_ADVANCED_LADDER));
		task.setHasRequiredObject(true);
		task.setLevel(62);
		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(task);
		return task;
	}

	@Test
	void underLevelledClickOnCourseEntranceDoesNotCredit()
	{
		when(client.getBoostedSkillLevel(Skill.AGILITY)).thenReturn(50);
		NuzlockeTask task = wyrmAdvancedTask();
		lenient().when(client.getTickCount()).thenReturn(100);
		agilityModule.onMenuOptionClicked(mockObjectClick(WYRM_ADVANCED_LADDER, MenuAction.GAME_OBJECT_FIRST_OPTION));
		simulatePlayerAnimation(828); // e.g. the basic obstacle just finished
		assertEquals(0, task.getCurrentProgress());
	}

	@Test
	void gameRefusingTheObstacleCancelsThePendingClick()
	{
		NuzlockeTask task = wyrmAdvancedTask();
		lenient().when(client.getTickCount()).thenReturn(100);
		agilityModule.onMenuOptionClicked(mockObjectClick(WYRM_ADVANCED_LADDER, MenuAction.GAME_OBJECT_FIRST_OPTION));
		ChatMessage refused = mock(ChatMessage.class);
		when(refused.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
		when(refused.getMessage()).thenReturn("You need an Agility level of 62 to use this course.");
		agilityModule.onChatMessage(refused);
		simulatePlayerAnimation(828);
		assertEquals(0, task.getCurrentProgress());
	}

	@Test
	void boostedToTheLevelStillCredits()
	{
		when(client.getBoostedSkillLevel(Skill.AGILITY)).thenReturn(62); // e.g. 57 + summer pie
		NuzlockeTask task = wyrmAdvancedTask();
		lenient().when(client.getTickCount()).thenReturn(100);
		agilityModule.onMenuOptionClicked(mockObjectClick(WYRM_ADVANCED_LADDER, MenuAction.GAME_OBJECT_FIRST_OPTION));
		simulatePlayerAnimation(828);
		assertEquals(1, task.getCurrentProgress());
	}

	/** Niddy: fighting a chicken on the way to the Varrock fence completed it before the jump. */
	@Test
	void flinchObstacle()
	{
		NuzlockeTask task = wyrmAdvancedTask();
		lenient().when(client.getTickCount()).thenReturn(100);
		agilityModule.onMenuOptionClicked(mockObjectClick(WYRM_ADVANCED_LADDER, MenuAction.GAME_OBJECT_FIRST_OPTION));

		HitsplatApplied peck = mock(HitsplatApplied.class);
		when(peck.getActor()).thenReturn(localPlayer);
		agilityModule.onHitsplatApplied(peck);
		simulatePlayerAnimation(424); // flinch from the hit
		assertEquals(0, task.getCurrentProgress());

		lenient().when(client.getTickCount()).thenReturn(101);
		when(localPlayer.getInteracting()).thenReturn(mock(NPC.class));
		simulatePlayerAnimation(422); // a last swing at the chicken
		assertEquals(0, task.getCurrentProgress());

		lenient().when(client.getTickCount()).thenReturn(103);
		when(localPlayer.getInteracting()).thenReturn(null);
		simulatePlayerAnimation(828); // the jump
		assertEquals(1, task.getCurrentProgress());
	}

	/** Venomphile: a log balance shows only a balancing walk, and some give no XP at all. */
	@Test
	void logBalancePose()
	{
		NuzlockeTask task = createTaskWithRequiredObject("Use the Coal Truck Log Balance", "log_balance", "AGILITY", 1,
			Collections.singletonList(23274));
		task.setHasRequiredObject(true);
		agilityModule.addActiveTask(task);
		when(localPlayer.getIdlePoseAnimation()).thenReturn(808);
		when(localPlayer.getPoseAnimation()).thenReturn(808);
		lenient().when(client.getTickCount()).thenReturn(100);
		agilityModule.onMenuOptionClicked(mockObjectClick(23274, MenuAction.GAME_OBJECT_FIRST_OPTION));
		agilityModule.onGameTick(new GameTick());
		assertEquals(0, task.getCurrentProgress());

		when(localPlayer.getPoseAnimation()).thenReturn(762); // balancing across
		lenient().when(client.getTickCount()).thenReturn(103);
		agilityModule.onGameTick(new GameTick());
		assertEquals(1, task.getCurrentProgress());
	}

	/** Walking (no run energy) to a shortcut clicked from far away outlasts the wait. */
	@Test
	void longWalkShortcut()
	{
		NuzlockeTask task = createTaskWithRequiredObject("Use a shortcut twice", "shortcut_twice", "AGILITY", 2,
			Collections.singletonList(WYRM_ADVANCED_LADDER));
		task.setHasRequiredObject(true);
		agilityModule.addActiveTask(task);
		lenient().when(client.getTickCount()).thenReturn(100);
		agilityModule.onMenuOptionClicked(mockObjectClick(WYRM_ADVANCED_LADDER, MenuAction.GAME_OBJECT_FIRST_OPTION));
		for (int t = 101; t <= 125; t++)
		{
			lenient().when(client.getTickCount()).thenReturn(t);
			when(localPlayer.getWorldLocation()).thenReturn(new WorldPoint(1600 + t, 2900, 0));
			agilityModule.onGameTick(new GameTick());
		}
		simulatePlayerAnimation(828);
		assertEquals(1, task.getCurrentProgress());

		// Standing still, the click still runs out.
		lenient().when(client.getTickCount()).thenReturn(200);
		agilityModule.onMenuOptionClicked(mockObjectClick(WYRM_ADVANCED_LADDER, MenuAction.GAME_OBJECT_FIRST_OPTION));
		for (int t = 201; t <= 215; t++)
		{
			lenient().when(client.getTickCount()).thenReturn(t);
			agilityModule.onGameTick(new GameTick());
		}
		simulatePlayerAnimation(828);
		assertEquals(1, task.getCurrentProgress());
	}

	/**
	 * dotzo: the repaired Mausoleum bridge never credited. It's a varbit multiloc, so the
	 * click reports 57715 while the task lists the repaired look, 57713.
	 */
	@Test
	void mausoleumBridge()
	{
		NuzlockeTask task = createTaskWithRequiredObject("Mausoleum Bridge", "agility_level_69_mausoleum_bridge_jump",
			"AGILITY", 1, Collections.singletonList(57713));
		task.setHasRequiredObject(true);
		agilityModule.addActiveTask(task);
		ObjectComposition site = mock(ObjectComposition.class);
		ObjectComposition look = mock(ObjectComposition.class);
		when(client.getObjectDefinition(57715)).thenReturn(site);
		when(site.getImpostorIds()).thenReturn(new int[]{5160, 57711, 57713});
		when(site.getImpostor()).thenReturn(look);

		when(look.getId()).thenReturn(5160); // still broken
		lenient().when(client.getTickCount()).thenReturn(100);
		agilityModule.onMenuOptionClicked(mockObjectClick(57715, MenuAction.GAME_OBJECT_FIRST_OPTION));
		simulatePlayerAnimation(828);
		assertEquals(0, task.getCurrentProgress());

		when(look.getId()).thenReturn(57713); // repaired
		lenient().when(client.getTickCount()).thenReturn(200);
		agilityModule.onMenuOptionClicked(mockObjectClick(57715, MenuAction.GAME_OBJECT_FIRST_OPTION));
		simulatePlayerAnimation(828);
		assertEquals(1, task.getCurrentProgress());
	}

	// --- Interface clicks on the way to the obstacle (Eirund: the Catherby cliff grapple) ---

	private static final int CATHERBY_GRAPPLE_ROCK = 17042;

	private MenuOptionClicked mockWidgetClick(MenuAction action, int widget, String option)
	{
		MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuAction()).thenReturn(action);
		lenient().when(event.getParam1()).thenReturn(widget);
		lenient().when(event.getMenuOption()).thenReturn(option);
		return event;
	}

	/**
	 * Grapple the Catherby cliff with one other click between the Grapple click and the
	 * grapple's animation. The grapple gives no Agility XP, so the animation is the only proof.
	 */
	private int grappleAfter(MenuOptionClicked between)
	{
		NuzlockeTask task = createTaskWithRequiredObject("Use the Level 32 Agility Crossbow Catherby Cliff Shortcut",
			"agility_level_32_catherby_cliff", "AGILITY", 1, Collections.singletonList(CATHERBY_GRAPPLE_ROCK));
		task.setHasRequiredObject(true);
		task.setLevel(32);
		agilityModule.addActiveTask(task);
		lenient().when(client.getTickCount()).thenReturn(100);
		agilityModule.onMenuOptionClicked(mockObjectClick(CATHERBY_GRAPPLE_ROCK, MenuAction.GAME_OBJECT_FIRST_OPTION));
		agilityModule.onMenuOptionClicked(between);
		simulatePlayerAnimation(4455); // firing the grapple
		return task.getCurrentProgress();
	}

	/** Turning run on while walking to the rock keeps the Grapple click. */
	@Test
	void runOrbClick()
	{
		assertEquals(1, grappleAfter(mockWidgetClick(MenuAction.CC_OP, InterfaceID.Orbs.RUNBUTTON, "Toggle Run")));
	}

	/** Opening the inventory tab to check for the grapple keeps the Grapple click. */
	@Test
	void tabClick()
	{
		assertEquals(1, grappleAfter(mockWidgetClick(MenuAction.CC_OP, InterfaceID.ToplevelOsrsStretch.STONE3, "Inventory")));
	}

	/** Flicking a prayer on the way keeps the Grapple click. */
	@Test
	void prayerClick()
	{
		assertEquals(1, grappleAfter(mockWidgetClick(MenuAction.CC_OP, InterfaceID.Prayerbook.PRAYER1, "Activate")));
	}

	/** Right-clicking around the rock and picking "Cancel" keeps the Grapple click. */
	@Test
	void cancelClick()
	{
		assertEquals(1, grappleAfter(mockWidgetClick(MenuAction.CANCEL, 0, "Cancel")));
	}

	/** Wielding the crossbow from the inventory doesn't animate, so it keeps the Grapple click. */
	@Test
	void wieldClick()
	{
		assertEquals(1, grappleAfter(mockWidgetClick(MenuAction.CC_OP, InterfaceID.Inventory.ITEMS, "Wield")));
	}

	/** Eating animates, and that animation isn't the grapple, so it still cancels the click. */
	@Test
	void eatingCancels()
	{
		assertEquals(0, grappleAfter(mockWidgetClick(MenuAction.CC_OP, InterfaceID.Inventory.ITEMS, "Eat")));
	}

	/** A teleport spell animates too, so it still cancels the click. */
	@Test
	void teleportCancels()
	{
		assertEquals(0, grappleAfter(mockWidgetClick(MenuAction.CC_OP, InterfaceID.MagicSpellbook.VARROCK_TELEPORT, "Cast")));
	}

	/** Some special attacks fire straight from the orb (dragon battleaxe), so it still cancels. */
	@Test
	void specOrbCancels()
	{
		assertEquals(0, grappleAfter(mockWidgetClick(MenuAction.CC_OP, InterfaceID.Orbs.SPECBUTTON, "Use Special Attack")));
	}
}
