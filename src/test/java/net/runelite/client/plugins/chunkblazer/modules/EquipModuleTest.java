package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.api.ChunkBlazerApiClient;
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
 * Unit tests for EquipModule.
 * Tests equipment detection and tracking.
 */
@ExtendWith(MockitoExtension.class)
class EquipModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private ChatMessageManager chatMessageManager;

	@Mock
	private ChunkBlazerApiClient apiClient;

	@InjectMocks
	private EquipModule equipModule;

	@Mock
	private ItemContainer equipmentContainer;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(equipModule, "client", client);
		injectField(equipModule, "clientThread", clientThread);
		injectField(equipModule, "eventBus", eventBus);
		injectField(equipModule, "config", config);
		injectField(equipModule, "itemManager", itemManager);

		equipModule.setCompletionCallback(completionCallback);

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
		assertEquals("EQUIP", equipModule.getCompletionType());
	}

	@Test
	void testCanHandle_EquipType()
	{
		NuzlockeTask task = createTestTask("Equip Bronze Sword", "equip_bronze_sword", "EQUIP", 1);
		assertTrue(equipModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Obtain Logs", "obtain_logs", "OBTAIN", 5);
		assertFalse(equipModule.canHandle(task));
	}

	@Test
	void testAddActiveTask()
	{
		NuzlockeTask task = createTaskWithItems("Equip Bronze Sword", "equip_sword", "EQUIP", 1, Arrays.asList(1277));

		when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(equipmentContainer);
		when(equipmentContainer.getItems()).thenReturn(new Item[0]);

		equipModule.addActiveTask(task);

		assertEquals(1, equipModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithItems("Equip Bronze Sword", "equip_sword", "EQUIP", 1, Arrays.asList(1277));

		when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(equipmentContainer);
		when(equipmentContainer.getItems()).thenReturn(new Item[0]);

		equipModule.addActiveTask(task);
		equipModule.onTaskCleared();

		assertTrue(equipModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		equipModule.startUp();
		verify(eventBus).register(equipModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		equipModule.shutDown();
		verify(eventBus).unregister(equipModule);
	}
}
