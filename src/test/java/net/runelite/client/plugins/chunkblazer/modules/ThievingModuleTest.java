package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
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
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ThievingModule.
 * Tests NPC pickpocket detection.
 */
@ExtendWith(MockitoExtension.class)
class ThievingModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private ThievingModule thievingModule;

	@Mock
	private NPC targetNpc;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(thievingModule, "client", client);
		injectField(thievingModule, "clientThread", clientThread);
		injectField(thievingModule, "eventBus", eventBus);
		injectField(thievingModule, "config", config);

		thievingModule.setCompletionCallback(completionCallback);
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
		assertEquals("THIEVING", thievingModule.getCompletionType());
	}

	@Test
	void testCanHandle_ThievingType()
	{
		NuzlockeTask task = createTestTask("Pickpocket Man", "pickpocket_man", "THIEVING", 10);
		assertTrue(thievingModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Kill Man", "kill_man", "NPC_KILL", 10);
		assertFalse(thievingModule.canHandle(task));
	}

	@Test
	void testAddActiveTask()
	{
		NuzlockeTask task = createTaskWithNpc("Pickpocket Man", "pickpocket_man", "THIEVING", 10, Arrays.asList(3106, 3107));

		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1000);

		thievingModule.addActiveTask(task);

		assertEquals(1, thievingModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithNpc("Pickpocket Man", "pickpocket_man", "THIEVING", 10, Arrays.asList(3106));

		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1000);

		thievingModule.addActiveTask(task);
		thievingModule.onTaskCleared();

		assertTrue(thievingModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		thievingModule.startUp();
		verify(eventBus).register(thievingModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		thievingModule.shutDown();
		verify(eventBus).unregister(thievingModule);
	}

	// --- GameObject (stall/chest) tests for bug #23 -------------------------------------------
	// Repro: user has Seed Stall (id 7053) and Fortunato's Market Stall (id 14011) tasks active,
	// steals from an unrelated Wine merchant's stall — pre-fix, BOTH tasks completed in lockstep
	// because XP gain with no NPC match fell through to "credit all tasks with no NPC".

	private static final int SEED_STALL_ID = 7053;
	private static final int FORTUNATO_STALL_ID = 14011;
	private static final int WINE_STALL_ID = 14009; // arbitrary unrelated stall

	private MenuOptionClicked mockObjectClick(int objectId, MenuAction action)
	{
		// Lenient stubs because non-object actions short-circuit before getId/getMenuOption
		// are read, and Mockito would otherwise flag those as unnecessary.
		MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuAction()).thenReturn(action);
		lenient().when(event.getId()).thenReturn(objectId);
		lenient().when(event.getMenuOption()).thenReturn("Steal-from");
		return event;
	}

	private StatChanged mockThievingXp(int xp)
	{
		StatChanged event = mock(StatChanged.class);
		when(event.getSkill()).thenReturn(Skill.THIEVING);
		when(event.getXp()).thenReturn(xp);
		return event;
	}

	@Test
	void testStallTheft_OnlyMatchingObjectIdCredited()
	{
		// Both stall tasks active.
		NuzlockeTask seedTask = createTaskWithRequiredObject(
			"Steal from a Seed Stall", "steal_seed_stall", "THIEVING", 1, Collections.singletonList(SEED_STALL_ID));
		NuzlockeTask fortunatoTask = createTaskWithRequiredObject(
			"Steal from Fortunato's Market Stall", "steal_market_stall_draynor", "THIEVING", 1, Collections.singletonList(FORTUNATO_STALL_ID));

		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1000);
		thievingModule.addActiveTask(seedTask);
		thievingModule.addActiveTask(fortunatoTask);

		// Player clicks Steal-from on the Seed Stall, then gains thieving XP a tick later.
		when(client.getTickCount()).thenReturn(100);
		thievingModule.onMenuOptionClicked(mockObjectClick(SEED_STALL_ID, MenuAction.GAME_OBJECT_SECOND_OPTION));

		when(client.getTickCount()).thenReturn(101);
		thievingModule.onStatChanged(mockThievingXp(1010));

		// Only the Seed Stall task should progress.
		verify(completionCallback).onProgressUpdated(eq(seedTask), eq(1));
		verify(completionCallback, never()).onProgressUpdated(eq(fortunatoTask), anyInt());
	}

	@Test
	void testStallTheft_UnrelatedStallCreditsNothing()
	{
		// This is Mike's exact bug: stealing from a Wine merchant stall (no matching task)
		// must not credit Seed Stall or Fortunato's.
		NuzlockeTask seedTask = createTaskWithRequiredObject(
			"Steal from a Seed Stall", "steal_seed_stall", "THIEVING", 1, Collections.singletonList(SEED_STALL_ID));
		NuzlockeTask fortunatoTask = createTaskWithRequiredObject(
			"Steal from Fortunato's Market Stall", "steal_market_stall_draynor", "THIEVING", 1, Collections.singletonList(FORTUNATO_STALL_ID));

		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1000);
		thievingModule.addActiveTask(seedTask);
		thievingModule.addActiveTask(fortunatoTask);

		// Wine stall is not in watchedObjectIds, so the click is ignored.
		when(client.getTickCount()).thenReturn(100);
		thievingModule.onMenuOptionClicked(mockObjectClick(WINE_STALL_ID, MenuAction.GAME_OBJECT_SECOND_OPTION));

		when(client.getTickCount()).thenReturn(101);
		thievingModule.onStatChanged(mockThievingXp(1010));

		// Neither task should progress — the old fallback would have credited both.
		verify(completionCallback, never()).onProgressUpdated(any(NuzlockeTask.class), anyInt());
	}

	@Test
	void testStallTheft_NonObjectMenuActionIgnored()
	{
		// A non-object menu action (e.g. walking, examining) must not register as a theft
		// even if the target ID happens to match a watched stall.
		NuzlockeTask seedTask = createTaskWithRequiredObject(
			"Steal from a Seed Stall", "steal_seed_stall", "THIEVING", 1, Collections.singletonList(SEED_STALL_ID));

		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1000);
		thievingModule.addActiveTask(seedTask);

		when(client.getTickCount()).thenReturn(100);
		// Examine (not an interaction) with the stall's id should be filtered out.
		thievingModule.onMenuOptionClicked(mockObjectClick(SEED_STALL_ID, MenuAction.EXAMINE_OBJECT));

		when(client.getTickCount()).thenReturn(101);
		thievingModule.onStatChanged(mockThievingXp(1010));

		verify(completionCallback, never()).onProgressUpdated(any(NuzlockeTask.class), anyInt());
	}

	@Test
	void testStallTheft_XpGainAfterTimeoutDoesNotCredit()
	{
		// If the XP gain arrives more than INTERACTION_TIMEOUT_TICKS (5) after the click,
		// it must not be associated with that click.
		NuzlockeTask seedTask = createTaskWithRequiredObject(
			"Steal from a Seed Stall", "steal_seed_stall", "THIEVING", 1, Collections.singletonList(SEED_STALL_ID));

		when(client.getSkillExperience(Skill.THIEVING)).thenReturn(1000);
		thievingModule.addActiveTask(seedTask);

		when(client.getTickCount()).thenReturn(100);
		thievingModule.onMenuOptionClicked(mockObjectClick(SEED_STALL_ID, MenuAction.GAME_OBJECT_SECOND_OPTION));

		// 10 ticks later, well past the 5-tick timeout. XP source is some unrelated thieving activity.
		when(client.getTickCount()).thenReturn(110);
		thievingModule.onStatChanged(mockThievingXp(1010));

		verify(completionCallback, never()).onProgressUpdated(any(NuzlockeTask.class), anyInt());
	}
}
