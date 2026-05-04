package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.Skill;
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
}
