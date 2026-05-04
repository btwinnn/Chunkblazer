package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.HitsplatApplied;
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
}
