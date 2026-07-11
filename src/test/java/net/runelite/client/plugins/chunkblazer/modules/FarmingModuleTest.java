package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.ChatMessageType;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FarmingModule.
 *
 * <p>The verification model under test: a Farming XP drop on the same game
 * tick as a change in the watched item's inventory count credits ONE action.
 * Plant tasks consume the seed/sapling (count drops — allotments burn 3 seeds
 * for one planting); the rake task produces Weeds (count rises).
 */
@ExtendWith(MockitoExtension.class)
class FarmingModuleTest extends AbstractTaskModuleTest
{
	private static final int SWEETCORN_SEED = 5320;
	private static final int WEEDS = 6055;
	private static final int DRAGONFRUIT_SAPLING = 22866;
	private static final int ONION_SEED = 5319;
	private static final int GUAM_SEED = 5291;

	// Mutable game tick returned by the client mock; tests advance it to
	// model the plant-animation gap between item change and marker.
	private int currentTick;

	@Mock
	private ItemManager itemManager;

	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private FarmingModule farmingModule;

	@Mock
	private ItemContainer inventoryContainer;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(farmingModule, "client", client);
		injectField(farmingModule, "clientThread", clientThread);
		injectField(farmingModule, "eventBus", eventBus);
		injectField(farmingModule, "config", config);
		injectField(farmingModule, "itemManager", itemManager);

		farmingModule.setCompletionCallback(completionCallback);

		lenient().when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
		lenient().when(client.getTickCount()).thenAnswer(inv -> currentTick);
	}

	private void injectField(Object target, String fieldName, Object value) throws Exception
	{
		for (Class<?> clazz = target.getClass(); clazz != null; clazz = clazz.getSuperclass())
		{
			try
			{
				Field field = clazz.getDeclaredField(fieldName);
				field.setAccessible(true);
				field.set(target, value);
				return;
			}
			catch (NoSuchFieldException ignored)
			{
				// walk up
			}
		}
	}

	private NuzlockeTask farmingTask(String name, String taskId, int itemId, int target)
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setName(name);
		task.setTaskId(taskId);
		task.setCategory("Farming");
		task.setCompletionType("FARMING");
		task.setCurrentProgress(0);
		task.setCompleted(false);
		RequiredItem item = new RequiredItem();
		item.setItemIds(Arrays.asList(itemId));
		item.setQuantity(1);
		task.setRequiredItems(Collections.singletonList(item));
		task.setTargetQuantity(target);
		return task;
	}

	private Item itemOf(int itemId, int quantity)
	{
		Item item = mock(Item.class);
		lenient().when(item.getId()).thenReturn(itemId);
		lenient().when(item.getQuantity()).thenReturn(quantity);
		return item;
	}

	private void setInventory(Item... items)
	{
		lenient().when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		lenient().when(inventoryContainer.getItems()).thenReturn(items);
	}

	@Test
	void testGetCompletionType()
	{
		assertEquals("FARMING", farmingModule.getCompletionType());
	}

	@Test
	void testCanHandle_FarmingTypeAndCategory()
	{
		assertTrue(farmingModule.canHandle(farmingTask("Plant some Sweetcorn", "plant_sweetcorn_seed", SWEETCORN_SEED, 1)));

		NuzlockeTask wrongType = new NuzlockeTask();
		wrongType.setCompletionType("OBTAIN");
		wrongType.setCategory("Obtain");
		assertFalse(farmingModule.canHandle(wrongType));
	}

	@Test
	void plantingSeedWithFarmingXpCompletesTask()
	{
		NuzlockeTask task = farmingTask("Plant some Sweetcorn", "plant_sweetcorn_seed", SWEETCORN_SEED, 1);

		// 5 seeds in a stack at task-load time (baseline snapshot).
		Item seedStack = itemOf(SWEETCORN_SEED, 5);
		setInventory(seedStack);
		farmingModule.addActiveTask(task);

		// One planting: allotment consumes 3 seeds, Farming XP fires same tick
		// (XP first, then the container event — the gathering-style order).
		setInventory(itemOf(SWEETCORN_SEED, 2));
		farmingModule.onStatChanged(new StatChanged(Skill.FARMING, 17, 20, 20));

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted(), "one planting must complete a target-1 plant task");
		verify(completionCallback).onTaskCompleted(task, 1);
	}

	@Test
	void itemChangeBeforeXpSameTickStillCredits()
	{
		NuzlockeTask task = farmingTask("Plant some Sweetcorn", "plant_sweetcorn_seed", SWEETCORN_SEED, 1);

		setInventory(itemOf(SWEETCORN_SEED, 5));
		farmingModule.addActiveTask(task);

		// Container event fires FIRST this tick — no XP yet, so nothing credits
		// and the baseline must NOT move.
		setInventory(itemOf(SWEETCORN_SEED, 2));
		farmingModule.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer));
		assertEquals(0, task.getCurrentProgress());

		// The XP drop lands later the same tick — the pending delta credits now.
		farmingModule.onStatChanged(new StatChanged(Skill.FARMING, 17, 20, 20));
		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	@Test
	void allotmentPlantingCreditsOneActionNotThreeSeeds()
	{
		// Hypothetical "plant twice" task: a single 3-seed planting must credit
		// one ACTION, not three items.
		NuzlockeTask task = farmingTask("Plant some Sweetcorn twice", "plant_sweetcorn_twice", SWEETCORN_SEED, 2);

		setInventory(itemOf(SWEETCORN_SEED, 6));
		farmingModule.addActiveTask(task);

		setInventory(itemOf(SWEETCORN_SEED, 3));
		farmingModule.onStatChanged(new StatChanged(Skill.FARMING, 17, 20, 20));

		assertEquals(1, task.getCurrentProgress(), "3 consumed seeds = 1 planting action");
		assertFalse(task.isCompleted());

		// Second planting on a later tick completes it.
		farmingModule.onGameTick(new GameTick());
		setInventory(itemOf(SWEETCORN_SEED, 0));
		farmingModule.onStatChanged(new StatChanged(Skill.FARMING, 34, 20, 20));

		assertEquals(2, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	@Test
	void bankingSeedsWithoutXpDoesNotCredit()
	{
		NuzlockeTask task = farmingTask("Plant some Sweetcorn", "plant_sweetcorn_seed", SWEETCORN_SEED, 1);

		setInventory(itemOf(SWEETCORN_SEED, 5));
		farmingModule.addActiveTask(task);

		// Seeds banked: container change with NO Farming XP this tick.
		currentTick = 0;
		setInventory();
		farmingModule.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer));
		assertEquals(0, task.getCurrentProgress());

		farmingModule.onGameTick(new GameTick());

		// A LATER farming XP drop (watering, harvesting — no watched change)
		// must not claim the banked decrease: XP is a same-tick marker only.
		currentTick = 2;
		farmingModule.onStatChanged(new StatChanged(Skill.FARMING, 17, 20, 20));
		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void rakingWeedsIntoInventoryWithXpCompletesRakeTask()
	{
		// The two "Rake a Farming Patch" tasks watch Weeds, which APPEAR in the
		// inventory when raking grants Farming XP — the opposite direction from
		// plant tasks. Any deviation from the baseline counts.
		NuzlockeTask task = farmingTask("Rake a Farming Patch", "rake_farming_patch", WEEDS, 1);

		setInventory();
		farmingModule.addActiveTask(task);

		setInventory(itemOf(WEEDS, 1));
		farmingModule.onStatChanged(new StatChanged(Skill.FARMING, 4, 3, 3));

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	/**
	 * Cruk's Farmers' Guild regression (session_2026-07-10): tree and
	 * fruit-tree SAPLINGS grant no Farming XP on the plant tick, so the
	 * XP marker never fires — the sapling left the inventory and the module
	 * shrugged. The server's "You plant ..." message is the action marker
	 * for those crops.
	 */
	@Test
	void treeSaplingPlantWithNoXpCreditsViaPlantMessage()
	{
		NuzlockeTask task = farmingTask("Plant a Dragonfruit Tree Sapling",
			"plant_dragonfruit_tree_sapling", DRAGONFRUIT_SAPLING, 1);

		setInventory(itemOf(DRAGONFRUIT_SAPLING, 1));
		farmingModule.addActiveTask(task);

		// Plant tick: sapling consumed, ZERO Farming XP, only the SPAM
		// confirmation message (message-after-item order).
		setInventory();
		farmingModule.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer));
		assertEquals(0, task.getCurrentProgress(), "item change alone must not credit");

		farmingModule.onChatMessage(plantMessage(ChatMessageType.SPAM,
			"You plant the dragonfruit tree sapling in the fruit tree patch."));

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted(), "sapling plant must complete via the plant message marker");
		verify(completionCallback).onTaskCompleted(task, 1);
	}

	@Test
	void plantMessageBeforeItemChangeSameTickStillCredits()
	{
		NuzlockeTask task = farmingTask("Plant a Dragonfruit Tree Sapling",
			"plant_dragonfruit_tree_sapling", DRAGONFRUIT_SAPLING, 1);

		setInventory(itemOf(DRAGONFRUIT_SAPLING, 1));
		farmingModule.addActiveTask(task);

		// Message fires first — no delta yet, but the flag is set...
		farmingModule.onChatMessage(plantMessage(ChatMessageType.SPAM,
			"You plant the dragonfruit tree sapling in the fruit tree patch."));
		assertEquals(0, task.getCurrentProgress());

		// ...so the container change later the same tick credits.
		setInventory();
		farmingModule.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer));

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	@Test
	void plantMessageWithoutWatchedItemChangeDoesNotCredit()
	{
		// Someone plants a DIFFERENT crop: the message fires, the watched
		// sapling is untouched — no credit.
		NuzlockeTask task = farmingTask("Plant a Dragonfruit Tree Sapling",
			"plant_dragonfruit_tree_sapling", DRAGONFRUIT_SAPLING, 1);

		setInventory(itemOf(DRAGONFRUIT_SAPLING, 1));
		farmingModule.addActiveTask(task);

		farmingModule.onChatMessage(plantMessage(ChatMessageType.SPAM,
			"You plant the apple tree sapling in the fruit tree patch."));

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void nonPlantChatMessagesAreIgnored()
	{
		NuzlockeTask task = farmingTask("Plant a Dragonfruit Tree Sapling",
			"plant_dragonfruit_tree_sapling", DRAGONFRUIT_SAPLING, 1);

		setInventory(itemOf(DRAGONFRUIT_SAPLING, 1));
		farmingModule.addActiveTask(task);

		// Wrong type (public chat saying the magic words) must not arm the tick.
		farmingModule.onChatMessage(plantMessage(ChatMessageType.PUBLICCHAT,
			"You plant the dragonfruit tree sapling in the fruit tree patch."));
		// Unrelated game message must not either.
		farmingModule.onChatMessage(plantMessage(ChatMessageType.GAMEMESSAGE,
			"You rake the patch clear of weeds."));

		setInventory();
		farmingModule.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer));

		assertEquals(0, task.getCurrentProgress());
	}

	private ChatMessage plantMessage(ChatMessageType type, String message)
	{
		return new ChatMessage(null, type, "", message, null, 0);
	}

	/**
	 * bao's guam/onion regression (session 2026-07-10 20:01): planting
	 * consumes the seeds at the START of the animation (onion 22->19 at tick
	 * 12, no marker) and the "You plant" message arrives at its END (tick 15).
	 * The change must survive as a pending entry across the gap and be
	 * credited by the message.
	 */
	@Test
	void plantAnimationGap_PendingChangeClaimedByLaterMessage()
	{
		NuzlockeTask task = farmingTask("Plant some Onions", "plant_onions", ONION_SEED, 1);

		setInventory(itemOf(ONION_SEED, 22));
		farmingModule.addActiveTask(task);

		// Tick 12: 3 seeds consumed, no marker anywhere this tick.
		currentTick = 12;
		setInventory(itemOf(ONION_SEED, 19));
		farmingModule.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer));
		assertEquals(0, task.getCurrentProgress(), "no marker yet — must not credit");
		farmingModule.onGameTick(new GameTick());

		// Ticks 13-14: nothing happens; the pending must survive the slides.
		currentTick = 13;
		farmingModule.onGameTick(new GameTick());
		currentTick = 14;
		farmingModule.onGameTick(new GameTick());

		// Tick 15: the plant confirmation lands.
		currentTick = 15;
		farmingModule.onChatMessage(plantMessage(ChatMessageType.SPAM,
			"You plant 3 onion seeds in the allotment."));

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted(), "message must claim the pending seed consumption from tick 12");
		verify(completionCallback).onTaskCompleted(task, 1);
	}

	@Test
	void pendingChangeExpiresOutsideWindow()
	{
		// A watched-seed decrease with NO plant message inside the window
		// (deposited/dropped) must not be claimable by a much later message.
		NuzlockeTask task = farmingTask("Plant some Onions", "plant_onions", ONION_SEED, 1);

		setInventory(itemOf(ONION_SEED, 22));
		farmingModule.addActiveTask(task);

		currentTick = 0;
		setInventory(itemOf(ONION_SEED, 19));
		farmingModule.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer));
		for (int t = 0; t <= 6; t++)
		{
			currentTick = t;
			farmingModule.onGameTick(new GameTick());
		}

		currentTick = 10;
		farmingModule.onChatMessage(plantMessage(ChatMessageType.SPAM,
			"You plant 3 onion seeds in the allotment."));

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void withdrawnSeedsIncreaseNotClaimedByPlantMessage()
	{
		// Withdrawing the watched seeds (count INCREASES) followed by
		// planting a different crop within the window must not credit — only
		// decreases are plant-shaped.
		NuzlockeTask task = farmingTask("Plant a Guam Seed", "plant_guam", GUAM_SEED, 1);

		setInventory();
		farmingModule.addActiveTask(task);

		currentTick = 1;
		setInventory(itemOf(GUAM_SEED, 18));
		farmingModule.onItemContainerChanged(new ItemContainerChanged(InventoryID.INVENTORY.getId(), inventoryContainer));

		currentTick = 3;
		farmingModule.onChatMessage(plantMessage(ChatMessageType.SPAM,
			"You plant 3 onion seeds in the allotment."));

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void farmingXpWithoutWatchedItemChangeDoesNotCredit()
	{
		// Watering / composting / harvesting grant Farming XP but never touch
		// the watched seed.
		NuzlockeTask task = farmingTask("Plant some Sweetcorn", "plant_sweetcorn_seed", SWEETCORN_SEED, 1);

		setInventory(itemOf(SWEETCORN_SEED, 5));
		farmingModule.addActiveTask(task);

		farmingModule.onStatChanged(new StatChanged(Skill.FARMING, 1, 20, 20));

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void onTaskClearedResetsTracking()
	{
		NuzlockeTask task = farmingTask("Plant some Sweetcorn", "plant_sweetcorn_seed", SWEETCORN_SEED, 1);
		setInventory(itemOf(SWEETCORN_SEED, 5));
		farmingModule.addActiveTask(task);
		assertEquals(1, farmingModule.getActiveTasks().size());

		farmingModule.onTaskCleared();
		assertTrue(farmingModule.getActiveTasks().isEmpty());

		// A post-clear planting-shaped tick must not credit the removed task.
		setInventory(itemOf(SWEETCORN_SEED, 2));
		farmingModule.onStatChanged(new StatChanged(Skill.FARMING, 17, 20, 20));
		assertEquals(0, task.getCurrentProgress());
	}

	@Test
	void startUpAndShutDownManageEventBus()
	{
		farmingModule.startUp();
		verify(eventBus).register(farmingModule);
		farmingModule.shutDown();
		verify(eventBus).unregister(farmingModule);
	}
}
