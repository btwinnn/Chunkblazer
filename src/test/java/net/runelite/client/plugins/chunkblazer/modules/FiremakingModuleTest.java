package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
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

	// ---- Burn detection: tinderbox AND bonfire ----------------------------
	//
	// A burn is "a watched log left the inventory" paired with "Firemaking XP
	// landed". Both lighting methods produce that pair; what differs is the
	// order and spacing of the two events, which is what these pin down.

	private static final int LOGS = 1511;
	private static final int OAK_LOGS = 1521;

	private int currentTick;
	private int firemakingXp;

	/**
	 * Build mock inventory items. Always assign the result to a local BEFORE
	 * passing it to {@code thenReturn} — stubbing these mocks while an outer
	 * {@code when(...)} is still open trips Mockito's UnfinishedStubbing.
	 */
	private Item[] items(int... idQtyPairs)
	{
		Item[] result = new Item[idQtyPairs.length / 2];
		for (int i = 0; i < result.length; i++)
		{
			Item item = mock(Item.class);
			lenient().when(item.getId()).thenReturn(idQtyPairs[i * 2]);
			lenient().when(item.getQuantity()).thenReturn(idQtyPairs[i * 2 + 1]);
			result[i] = item;
		}
		return result;
	}

	/** An ItemContainer reporting exactly these items. */
	private ItemContainer containerOf(int... idQtyPairs)
	{
		Item[] contents = items(idQtyPairs);
		ItemContainer container = mock(ItemContainer.class);
		lenient().when(container.getItems()).thenReturn(contents);
		return container;
	}

	private void fireInventoryChanged(ItemContainer container)
	{
		firemakingModule.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.INVENTORY.getId(), container));
	}

	/** Start a "burn N logs" task with a starting inventory of that log. */
	private NuzlockeTask startBurnTask(int logId, int target, int startingLogs)
	{
		NuzlockeTask task = createTaskWithItems(
			"Burn Logs", "burn_logs", "FIREMAKING", target, Arrays.asList(logId));

		Item[] startingInventory = items(logId, startingLogs);

		lenient().when(client.getTickCount()).thenAnswer(inv -> currentTick);
		lenient().when(client.getSkillExperience(Skill.FIREMAKING)).thenReturn(firemakingXp);
		lenient().when(inventoryContainer.getItems()).thenReturn(startingInventory);
		lenient().when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);

		firemakingModule.addActiveTask(task);
		return task;
	}

	/** Report the inventory now holding {@code remaining} of {@code logId}. */
	private void inventoryNowHolds(int logId, int remaining)
	{
		fireInventoryChanged(containerOf(logId, remaining));
	}

	private void gainFiremakingXp(int amount)
	{
		firemakingXp += amount;
		firemakingModule.onStatChanged(new StatChanged(Skill.FIREMAKING, firemakingXp, 1, 1));
	}

	/** The traditional tinderbox path: the log goes, then the XP lands. */
	@Test
	void consumptionThenXpCreditsTheBurn()
	{
		NuzlockeTask task = startBurnTask(LOGS, 10, 27);

		inventoryNowHolds(LOGS, 26);
		gainFiremakingXp(40);

		assertEquals(1, task.getCurrentProgress(), "a tinderbox burn must credit");
	}

	/**
	 * THE bonfire regression. When the XP is dispatched ahead of the inventory
	 * update, the old rule found nothing banked, discarded the XP, and scored
	 * zero — leaving the task permanently short.
	 */
	@Test
	void xpThenConsumptionCreditsTheBurn()
	{
		NuzlockeTask task = startBurnTask(LOGS, 10, 27);

		gainFiremakingXp(40);
		inventoryNowHolds(LOGS, 26);

		assertEquals(1, task.getCurrentProgress(),
			"a burn must credit even when its XP arrives before the inventory update");
	}

	/**
	 * A bonfire batch can drain logs faster than the XP drops arrive. Every log
	 * consumed inside the match window counts once the XP confirms the burn.
	 */
	@Test
	void bonfireBatchCreditsEveryLogConsumedInTheWindow()
	{
		NuzlockeTask task = startBurnTask(LOGS, 10, 27);

		inventoryNowHolds(LOGS, 26);
		currentTick++;
		inventoryNowHolds(LOGS, 24);
		currentTick++;
		inventoryNowHolds(LOGS, 22);

		gainFiremakingXp(30);

		assertEquals(5, task.getCurrentProgress(), "every log in the batch must count");
	}

	/**
	 * Logs banked, dropped or sold look exactly like a burn at the inventory
	 * layer. They must not ride along on the next genuine burn — the flaw that
	 * made pending consumption accumulate forever.
	 */
	@Test
	void staleConsumptionDoesNotRideAlongOnALaterBurn()
	{
		NuzlockeTask task = startBurnTask(LOGS, 10, 27);

		// Bank 20 logs — no Firemaking XP accompanies this.
		inventoryNowHolds(LOGS, 7);

		// Much later, genuinely burn one.
		currentTick += 50;
		inventoryNowHolds(LOGS, 6);
		gainFiremakingXp(40);

		assertEquals(1, task.getCurrentProgress(),
			"only the burned log counts; the 20 banked logs must have expired");
	}

	/** Consumption of a log the task doesn't ask for is not progress. */
	@Test
	void unwatchedLogTypeIsIgnored()
	{
		NuzlockeTask task = startBurnTask(LOGS, 10, 27);

		fireInventoryChanged(containerOf(LOGS, 27, OAK_LOGS, 4));
		fireInventoryChanged(containerOf(LOGS, 27, OAK_LOGS, 3));

		gainFiremakingXp(60);

		assertEquals(0, task.getCurrentProgress(), "burning oak must not advance a normal-logs task");
	}

	/** Firemaking XP with no log consumed at all credits nothing. */
	@Test
	void xpWithoutConsumptionCreditsNothing()
	{
		NuzlockeTask task = startBurnTask(LOGS, 10, 27);

		gainFiremakingXp(40);

		assertEquals(0, task.getCurrentProgress());
	}

	/** Reaching the target completes the task and fires the callback. */
	@Test
	void reachingTargetCompletesTheTask()
	{
		NuzlockeTask task = startBurnTask(LOGS, 3, 27);

		int remaining = 27;
		for (int i = 0; i < 3; i++)
		{
			remaining--;
			inventoryNowHolds(LOGS, remaining);
			gainFiremakingXp(40);
			currentTick++;
		}

		assertTrue(task.isCompleted(), "burning the target count must complete the task");
		verify(completionCallback).onTaskCompleted(eq(task), anyInt());
	}

	/** Bonfire ordering must complete a task too, not just score the first log. */
	@Test
	void bonfireOrderingCompletesTheWholeTask()
	{
		NuzlockeTask task = startBurnTask(LOGS, 3, 27);

		int remaining = 27;
		for (int i = 0; i < 3; i++)
		{
			gainFiremakingXp(30);
			remaining--;
			inventoryNowHolds(LOGS, remaining);
			currentTick++;
		}

		assertTrue(task.isCompleted(), "XP-first ordering must be able to finish a task");
	}
}
