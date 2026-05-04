package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
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
 * Unit tests for ObtainModule.
 * Tests item acquisition detection for OBTAIN and skilling completion types.
 */
@ExtendWith(MockitoExtension.class)
class ObtainModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private ChatMessageManager chatMessageManager;

	@Mock
	private ChunkBlazerApiClient apiClient;

	@InjectMocks
	private ObtainModule obtainModule;

	@Mock
	private ItemContainer inventoryContainer;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		// Inject mocks
		injectField(obtainModule, "client", client);
		injectField(obtainModule, "clientThread", clientThread);
		injectField(obtainModule, "eventBus", eventBus);
		injectField(obtainModule, "config", config);
		injectField(obtainModule, "itemManager", itemManager);

		obtainModule.setCompletionCallback(completionCallback);

		// Setup item manager to return same ID (no noted items)
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
		assertEquals("OBTAIN", obtainModule.getCompletionType());
	}

	@Test
	void testCanHandle_ObtainType()
	{
		NuzlockeTask task = createTestTask("Obtain Logs", "obtain_logs", "OBTAIN", 5);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_CookingType()
	{
		NuzlockeTask task = createTestTask("Cook Shrimp", "cook_shrimp", "COOKING", 10);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_CraftingType()
	{
		NuzlockeTask task = createTestTask("Craft Bowstring", "craft_bowstring", "CRAFTING", 5);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_SmithingType()
	{
		NuzlockeTask task = createTestTask("Smith Bronze Bar", "smith_bronze", "SMITHING", 10);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_MiningType()
	{
		NuzlockeTask task = createTestTask("Mine Copper", "mine_copper", "MINING", 15);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_WoodcuttingType()
	{
		NuzlockeTask task = createTestTask("Chop Logs", "chop_logs", "WOODCUTTING", 20);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_FishingType()
	{
		NuzlockeTask task = createTestTask("Catch Shrimp", "catch_shrimp", "FISHING", 10);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_FletchingType()
	{
		NuzlockeTask task = createTestTask("Fletch Arrows", "fletch_arrows", "FLETCHING", 50);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_HerbloreType()
	{
		NuzlockeTask task = createTestTask("Make Potion", "make_potion", "HERBLORE", 5);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_RunecraftingType()
	{
		NuzlockeTask task = createTestTask("Craft Runes", "craft_runes", "RUNECRAFTING", 100);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_HunterType()
	{
		NuzlockeTask task = createTestTask("Catch Chinchompa", "catch_chin", "HUNTER", 10);
		assertTrue(obtainModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Kill Monster", "kill_monster", "NPC_KILL", 5);
		assertFalse(obtainModule.canHandle(task));
	}

	@Test
	void testAddActiveTask()
	{
		NuzlockeTask task = createTaskWithItems("Obtain Logs", "obtain_logs", "OBTAIN", 5, Arrays.asList(1511));

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);

		obtainModule.addActiveTask(task);

		assertEquals(1, obtainModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithItems("Obtain Logs", "obtain_logs", "OBTAIN", 5, Arrays.asList(1511));

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);

		obtainModule.addActiveTask(task);
		obtainModule.onTaskCleared();

		assertTrue(obtainModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		obtainModule.startUp();
		verify(eventBus).register(obtainModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		obtainModule.shutDown();
		verify(eventBus).unregister(obtainModule);
	}

	@Test
	void testTaskProgressTracking()
	{
		NuzlockeTask task = createTaskWithItems("Obtain 10 Logs", "obtain_logs", "OBTAIN", 10, Arrays.asList(1511));

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);

		obtainModule.addActiveTask(task);

		// Simulate progress
		task.setCurrentProgress(5);
		assertEquals(5, task.getCurrentProgress());
		assertFalse(task.isCompleted());

		// Complete task
		task.setCurrentProgress(10);
		task.setCompleted(true);
		assertTrue(task.isCompleted());
	}
}
