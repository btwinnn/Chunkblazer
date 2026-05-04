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
 * Unit tests for ConstructionModule.
 * Tests furniture building detection via item consumption and XP.
 */
@ExtendWith(MockitoExtension.class)
class ConstructionModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private ConstructionModule constructionModule;

	@Mock
	private ItemContainer inventoryContainer;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(constructionModule, "client", client);
		injectField(constructionModule, "clientThread", clientThread);
		injectField(constructionModule, "eventBus", eventBus);
		injectField(constructionModule, "config", config);
		injectField(constructionModule, "itemManager", itemManager);

		constructionModule.setCompletionCallback(completionCallback);

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
		assertEquals("CONSTRUCTION", constructionModule.getCompletionType());
	}

	@Test
	void testCanHandle_ConstructionType()
	{
		NuzlockeTask task = createTestTask("Build Table", "build_table", "CONSTRUCTION", 1);
		assertTrue(constructionModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Mine Ore", "mine_ore", "MINING", 5);
		assertFalse(constructionModule.canHandle(task));
	}

	@Test
	void testAddActiveTask()
	{
		// Oak Plank ID: 8778
		NuzlockeTask task = createTaskWithItems("Build Oak Table", "build_table", "CONSTRUCTION", 1, Arrays.asList(8778));

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(1000);

		constructionModule.addActiveTask(task);

		assertEquals(1, constructionModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithItems("Build Oak Table", "build_table", "CONSTRUCTION", 1, Arrays.asList(8778));

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(1000);

		constructionModule.addActiveTask(task);
		constructionModule.onTaskCleared();

		assertTrue(constructionModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		constructionModule.startUp();
		verify(eventBus).register(constructionModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		constructionModule.shutDown();
		verify(eventBus).unregister(constructionModule);
	}
}
