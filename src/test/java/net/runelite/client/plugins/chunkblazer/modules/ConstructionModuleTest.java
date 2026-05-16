package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.GameObject;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.ChunkBlazerPlugin;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.inject.Provider;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

	@Mock
	private ChunkBlazerPlugin chunkBlazerPlugin;

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
		injectField(constructionModule, "pluginProvider", (Provider<ChunkBlazerPlugin>) () -> chunkBlazerPlugin);

		constructionModule.setCompletionCallback(completionCallback);

		lenient().when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
		// Default: tests opt out of the region gate by returning -1 ("unknown"),
		// which the module treats as a non-enforced check. Tests that exercise
		// the gate stub a real region per-test.
		lenient().when(chunkBlazerPlugin.findRegionForTask(anyString())).thenReturn(-1);
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

	// --- GameObject-gated build confirmation tests --------------------------------------------
	// The new contract: a Construction task with required_object only credits when the watched
	// object_id spawns near the player AND a Construction XP gain fires within INTERACTION_TIMEOUT_TICKS.

	private static final int HARD_STASH_UNIT = 29018;
	private static final int EASY_STASH_BUSH = 28980;
	private static final int OAK_LARDER = 13566; // representative house furniture id

	private GameObjectSpawned mockSpawn(int objectId)
	{
		GameObject obj = mock(GameObject.class);
		when(obj.getId()).thenReturn(objectId);
		GameObjectSpawned event = mock(GameObjectSpawned.class);
		when(event.getGameObject()).thenReturn(obj);
		return event;
	}

	private NuzlockeTask buildConstructionTaskWithObject(String name, String taskId, int objectId)
	{
		// Task has required_object pointing at the BUILT furniture's GameObject id.
		// We keep required_items empty — the new object-gated path doesn't need them.
		NuzlockeTask task = createTaskWithRequiredObject(name, taskId, "CONSTRUCTION", 1,
			Collections.singletonList(objectId));
		return task;
	}

	@Test
	void testBuildCorrectObject_Credits()
	{
		NuzlockeTask task = buildConstructionTaskWithObject(
			"Build a Hard STASH", "build_hard_stash", HARD_STASH_UNIT);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		constructionModule.addActiveTask(task);

		// Spawn the built object, then Construction XP fires (~90 XP for a Hard STASH).
		when(client.getTickCount()).thenReturn(100);
		constructionModule.onGameObjectSpawned(mockSpawn(HARD_STASH_UNIT));
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, 90, 1, 1));

		verify(completionCallback).onProgressUpdated(eq(task), eq(1));
	}

	@Test
	void testBuildWrongObject_DoesNotCredit()
	{
		// Task wants Hard STASH but player builds an Easy STASH (different object_id) —
		// the spawn doesn't match the watched id, so even with a Construction XP gain we
		// don't credit. Pre-fix this would have credited on item consumption alone.
		NuzlockeTask task = buildConstructionTaskWithObject(
			"Build a Hard STASH", "build_hard_stash", HARD_STASH_UNIT);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		constructionModule.addActiveTask(task);

		when(client.getTickCount()).thenReturn(100);
		constructionModule.onGameObjectSpawned(mockSpawn(EASY_STASH_BUSH)); // wrong object
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, 31, 1, 1));

		verify(completionCallback, never()).onProgressUpdated(eq(task), anyInt());
	}

	@Test
	void testXpWithoutSpawn_DoesNotCreditObjectGatedTask()
	{
		NuzlockeTask task = buildConstructionTaskWithObject(
			"Build a Hard STASH", "build_hard_stash", HARD_STASH_UNIT);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		constructionModule.addActiveTask(task);

		// XP fires but no GameObject spawned at all — e.g. flatpack from inventory.
		when(client.getTickCount()).thenReturn(100);
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, 90, 1, 1));

		verify(completionCallback, never()).onProgressUpdated(any(NuzlockeTask.class), anyInt());
	}

	@Test
	void testSpawnWithoutXp_DoesNotCredit()
	{
		// Walking into a house and seeing pre-existing furniture spawn in must not
		// credit a Construction task — there's no XP gain to confirm a build happened.
		NuzlockeTask task = buildConstructionTaskWithObject(
			"Build an Oak Larder", "build_oak_larder", OAK_LARDER);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		constructionModule.addActiveTask(task);

		when(client.getTickCount()).thenReturn(100);
		constructionModule.onGameObjectSpawned(mockSpawn(OAK_LARDER));
		// No StatChanged — player just walked into the room, didn't build.

		verify(completionCallback, never()).onProgressUpdated(any(NuzlockeTask.class), anyInt());
	}

	@Test
	void testSpawnAfterTimeout_DoesNotCredit()
	{
		// If the XP gain arrives more than INTERACTION_TIMEOUT_TICKS (5) after the
		// spawn, it must NOT be associated with that spawn.
		NuzlockeTask task = buildConstructionTaskWithObject(
			"Build a Hard STASH", "build_hard_stash", HARD_STASH_UNIT);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		constructionModule.addActiveTask(task);

		when(client.getTickCount()).thenReturn(100);
		constructionModule.onGameObjectSpawned(mockSpawn(HARD_STASH_UNIT));

		// 10 ticks later — past the 5-tick window. Unrelated Construction XP.
		when(client.getTickCount()).thenReturn(110);
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, 90, 1, 1));

		verify(completionCallback, never()).onProgressUpdated(any(NuzlockeTask.class), anyInt());
	}

	@Test
	void testCrossContamination_OnlyMatchingObjectTaskCredits()
	{
		NuzlockeTask hardStash = buildConstructionTaskWithObject(
			"Build a Hard STASH", "build_hard_stash", HARD_STASH_UNIT);
		NuzlockeTask easyStash = buildConstructionTaskWithObject(
			"Build an Easy STASH", "build_easy_stash", EASY_STASH_BUSH);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		constructionModule.addActiveTask(hardStash);
		constructionModule.addActiveTask(easyStash);

		// Build the Hard STASH — only that task should credit; the Easy STASH task must
		// not, even though both are CONSTRUCTION and the XP event is shared.
		when(client.getTickCount()).thenReturn(100);
		constructionModule.onGameObjectSpawned(mockSpawn(HARD_STASH_UNIT));
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, 90, 1, 1));

		verify(completionCallback).onProgressUpdated(eq(hardStash), eq(1));
		verify(completionCallback, never()).onProgressUpdated(eq(easyStash), anyInt());
	}

	// --- Region gate + clearTask sensor regression -------------------------------------------

	/**
	 * Many built-furniture object_ids are reused across locations (e.g. the
	 * Easy STASH "inconspicuous hole" 50738 exists at the Grand Museum, Sunrise
	 * Palace, and many other spots). Without a region check, building at any
	 * location would credit a task expecting a specific one.
	 *
	 * <p>This test: player is in region 99999, task is for region 12345. Even
	 * though the watched object spawns and Construction XP fires, the task must
	 * NOT credit because the player is in the wrong region.
	 */
	@Test
	void testBuildCorrectObjectInWrongRegion_DoesNotCredit()
	{
		NuzlockeTask task = buildConstructionTaskWithObject(
			"Build an Easy STASH at the Grand Museum", "build_easy_stash_grand_museum", HARD_STASH_UNIT);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		when(chunkBlazerPlugin.findRegionForTask("build_easy_stash_grand_museum")).thenReturn(12345);
		// The shared playerLocation mock returns regionID 12345 by default
		// (AbstractTaskModuleTest setup). Override to a different region.
		when(playerLocation.getRegionID()).thenReturn(99999);
		constructionModule.addActiveTask(task);

		when(client.getTickCount()).thenReturn(100);
		constructionModule.onGameObjectSpawned(mockSpawn(HARD_STASH_UNIT));
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, 90, 1, 1));

		verify(completionCallback, never()).onProgressUpdated(any(NuzlockeTask.class), anyInt());
	}

	/**
	 * Companion to the wrong-region test: same shape, but with the player IN the
	 * task's region — credit must fire.
	 */
	@Test
	void testBuildCorrectObjectInRightRegion_Credits()
	{
		NuzlockeTask task = buildConstructionTaskWithObject(
			"Build an Easy STASH at the Grand Museum", "build_easy_stash_grand_museum", HARD_STASH_UNIT);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		when(chunkBlazerPlugin.findRegionForTask("build_easy_stash_grand_museum")).thenReturn(12345);
		// Default mock setup has playerLocation.getRegionID() = 12345 — match.
		constructionModule.addActiveTask(task);

		when(client.getTickCount()).thenReturn(100);
		constructionModule.onGameObjectSpawned(mockSpawn(HARD_STASH_UNIT));
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, 90, 1, 1));

		verify(completionCallback).onProgressUpdated(eq(task), eq(1));
	}

	/**
	 * Mike's bug repro from session_2026-05-15_16-49-04: a GameObjectSpawned
	 * fires (e.g. on scene load OR the actual build), then a clearTask() runs
	 * (panel/active-task refresh), THEN the Construction XP fires. Before this
	 * fix, onTaskCleared() wiped lastSpawnedObjectId — so by the time the XP
	 * arrived there was no recent-spawn signal, and the build silently failed
	 * to credit. After the fix, onTaskCleared no longer touches the sensor
	 * state, and the spawn signal survives long enough for the XP to match it.
	 */
	@Test
	void testTaskClearedBetweenSpawnAndXp_StillCredits()
	{
		NuzlockeTask task = buildConstructionTaskWithObject(
			"Build a Hard STASH", "build_hard_stash", HARD_STASH_UNIT);

		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inventoryContainer);
		when(inventoryContainer.getItems()).thenReturn(new Item[0]);
		when(client.getSkillExperience(Skill.CONSTRUCTION)).thenReturn(0);
		constructionModule.addActiveTask(task);

		when(client.getTickCount()).thenReturn(100);
		constructionModule.onGameObjectSpawned(mockSpawn(HARD_STASH_UNIT));

		// Simulate the panel/active-task refresh that happens mid-build.
		// onTaskCleared() is called, then the task is re-added.
		constructionModule.onTaskCleared();
		constructionModule.addActiveTask(task);

		// XP fires shortly after — must still credit, sensor must have survived.
		when(client.getTickCount()).thenReturn(102);
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, 90, 1, 1));

		verify(completionCallback).onProgressUpdated(eq(task), eq(1));
	}
}
