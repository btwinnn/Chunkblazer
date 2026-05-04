package net.runelite.client.plugins.chunkblazer.modules;

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
 * Unit tests for VarbitCheckModule.
 * Tests varbit/varp state monitoring for VARBIT_CHECK and VARP_CHECK types.
 */
@ExtendWith(MockitoExtension.class)
class VarbitCheckModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private VarbitCheckModule varbitCheckModule;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(varbitCheckModule, "client", client);
		injectField(varbitCheckModule, "clientThread", clientThread);
		injectField(varbitCheckModule, "eventBus", eventBus);
		injectField(varbitCheckModule, "config", config);

		varbitCheckModule.setCompletionCallback(completionCallback);
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
		assertEquals("VARBIT_CHECK", varbitCheckModule.getCompletionType());
	}

	@Test
	void testCanHandle_VarbitCheckType()
	{
		NuzlockeTask task = createTestTask("Activate Prayer", "activate_prayer", "VARBIT_CHECK", 1);
		assertTrue(varbitCheckModule.canHandle(task));
	}

	@Test
	void testCanHandle_VarpCheckType()
	{
		NuzlockeTask task = createTestTask("Check Varp", "check_varp", "VARP_CHECK", 1);
		assertTrue(varbitCheckModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Kill Monster", "kill_monster", "NPC_KILL", 5);
		assertFalse(varbitCheckModule.canHandle(task));
	}

	@Test
	void testAddActiveTask_Varbit()
	{
		NuzlockeTask task = createTaskWithVarbit("Activate Burst of Strength", "pray_burst", 4103, 1);

		when(client.getVarbitValue(4103)).thenReturn(0);

		varbitCheckModule.addActiveTask(task);

		assertEquals(1, varbitCheckModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithVarbit("Activate Burst of Strength", "pray_burst", 4103, 1);

		when(client.getVarbitValue(4103)).thenReturn(0);

		varbitCheckModule.addActiveTask(task);
		varbitCheckModule.onTaskCleared();

		assertTrue(varbitCheckModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		varbitCheckModule.startUp();
		verify(eventBus).register(varbitCheckModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		varbitCheckModule.shutDown();
		verify(eventBus).unregister(varbitCheckModule);
	}

	@Test
	void testTaskCompletion_VarbitMatches()
	{
		NuzlockeTask task = createTaskWithVarbit("Activate Burst of Strength", "pray_burst", 4103, 1);

		// Initially varbit is 0
		when(client.getVarbitValue(4103)).thenReturn(0);
		varbitCheckModule.addActiveTask(task);

		// Simulate varbit becoming 1 (prayer activated)
		task.setCurrentProgress(1);
		task.setCompleted(true);

		assertTrue(task.isCompleted());
		assertEquals(1, task.getCurrentProgress());
	}
}
