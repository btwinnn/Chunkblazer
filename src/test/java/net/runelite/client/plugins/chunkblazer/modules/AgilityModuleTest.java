package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.Skill;
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

import static org.junit.jupiter.api.Assertions.*;
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

	/**
	 * Mike's bug #22: Draynor Agility laps "complete on START not finish".
	 *
	 * <p>The Draynor Rooftop course awards 5–8 XP per obstacle (7 obstacles
	 * per lap) plus a 79 XP lap-completion bonus at the end of the lap.
	 * Crediting +1 progress on every obstacle (the previous behaviour at
	 * {@code MIN_XP_THRESHOLD = 5}) would tick a 1-lap task to complete on
	 * the very first obstacle. The fix raises the threshold so only the
	 * per-lap bonus crosses it.
	 */
	@Test
	void testDraynorLap_OnlyCompletesOnLapBonusNotEachObstacle()
	{
		NuzlockeTask task = createTestTask("Draynor Lap", "complete_draynor_roof", "AGILITY", 1);

		when(client.getSkillExperience(Skill.AGILITY)).thenReturn(0);
		agilityModule.addActiveTask(task);

		// Seed the previousAgilityXp baseline. Without this the first event
		// fires the early-return at line 158 and gets used as the baseline.
		// initializeXpTracking() ran inside addActiveTask via the mocked
		// clientThread.invokeLater, so the baseline is already 0.

		// Walk through one full Draynor lap: seven obstacle XP drops, none
		// of which is a lap completion. None should credit progress.
		int xp = 0;
		int[] obstacleXps = {5, 8, 8, 7, 7, 5, 5};
		for (int gain : obstacleXps)
		{
			xp += gain;
			agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, xp, 1, 1));
			assertEquals(0, task.getCurrentProgress(),
				"Obstacle gains (" + gain + " XP) must not credit lap progress");
			assertFalse(task.isCompleted(),
				"Task must not complete on intra-lap obstacle XP");
		}

		// The 79 XP lap-completion bonus arrives as a separate StatChanged
		// event right after the last obstacle. THIS is the lap signal.
		xp += 79;
		agilityModule.onStatChanged(new StatChanged(Skill.AGILITY, xp, 1, 1));

		assertEquals(1, task.getCurrentProgress(), "Lap bonus should credit +1 lap");
		assertTrue(task.isCompleted(), "1-lap task should be complete after one lap-end bonus");
	}
}
