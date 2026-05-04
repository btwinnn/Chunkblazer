package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.NPC;
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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
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
}
