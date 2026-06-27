package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
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
	 * Mike's bug #22: Draynor Agility laps "complete on START not finish".
	 * Per-obstacle XP at this course is 5–8, lap-end bonus is 79. Threshold
	 * 30 keeps obstacles from crediting and lets the bonus through.
	 */
	@Test
	void testDraynorLap_OnlyCompletesOnLapBonusNotEachObstacle()
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
	void testObjectlessShortcut_creditedByUnrelatedXp_documentsBug()
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
	void testTwoObjectlessShortcuts_oneUseCreditsBoth_documentsBug()
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
	void testWrongWikiId_creditsDifferentTask_documentsBug()
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
	void testObjectGatedShortcuts_onlyTheUsedOneCredits()
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

	/**
	 * And the unrelated-XP contamination is gone too once gated: obstacle XP from
	 * a rooftop lap (no shortcut object clicked) does not credit a gated shortcut.
	 */
	@Test
	void testObjectGatedShortcut_notCreditedByUnrelatedXp()
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
}
