package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FiremakingModule.
 * Tests log consumption detection for firemaking tasks.
 */
@ExtendWith(MockitoExtension.class)
class FiremakingModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private FiremakingModule firemakingModule;

	@Mock
	private ItemContainer inventoryContainer;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(firemakingModule, "client", client);
		injectField(firemakingModule, "clientThread", clientThread);
		injectField(firemakingModule, "eventBus", eventBus);
		injectField(firemakingModule, "config", config);
		injectField(firemakingModule, "itemManager", itemManager);

		firemakingModule.setCompletionCallback(completionCallback);

		lenient().when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
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
		assertEquals("FIREMAKING", firemakingModule.getCompletionType());
	}

	@Test
	void testCanHandle_FiremakingType()
	{
		NuzlockeTask task = createTestTask("Burn Logs", "burn_logs", "FIREMAKING", 10);
		assertTrue(firemakingModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Chop Logs", "chop_logs", "WOODCUTTING", 10);
		assertFalse(firemakingModule.canHandle(task));
	}

	@Test
	void testAddActiveTask()
	{
		NuzlockeTask task = createTaskWithItems("Burn Normal Logs", "burn_logs", "FIREMAKING", 10, Arrays.asList(1511));

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(1000);

		firemakingModule.addActiveTask(task);

		assertEquals(1, firemakingModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithItems("Burn Normal Logs", "burn_logs", "FIREMAKING", 10, Arrays.asList(1511));

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(1000);

		firemakingModule.addActiveTask(task);
		firemakingModule.onTaskCleared();

		assertTrue(firemakingModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		firemakingModule.startUp();
		verify(eventBus).register(firemakingModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		firemakingModule.shutDown();
		verify(eventBus).unregister(firemakingModule);
	}
}
