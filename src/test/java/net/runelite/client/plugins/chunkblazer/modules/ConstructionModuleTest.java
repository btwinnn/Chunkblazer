package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.WallObject;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.chat.ChatMessageManager;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the rewritten ConstructionModule.
 *
 * <p>Detection rule under test: a watched finished-furniture object spawns
 * within the match window of a Construction XP gain — either order — subject
 * to the overworld region gate (skipped inside instances/POHs).
 */
@ExtendWith(MockitoExtension.class)
class ConstructionModuleTest extends AbstractTaskModuleTest
{
	// "Medium STASH Unit (inconspicuous bush)" — overworld GameObject.
	private static final int STASH_OBJECT = 29003;
	// "Amulet of glory (mounted)" — POH wall furniture, a DecorativeObject.
	private static final int MOUNTED_GLORY = 13523;

	@Mock
	private ChatMessageManager chatMessageManager;

	@Mock
	private ChunkBlazerPlugin chunkBlazerPlugin;

	@InjectMocks
	private ConstructionModule constructionModule;

	// Mutable game tick returned by the client mock.
	private int currentTick;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(constructionModule, "client", client);
		injectField(constructionModule, "clientThread", clientThread);
		injectField(constructionModule, "eventBus", eventBus);
		injectField(constructionModule, "config", config);
		injectField(constructionModule, "pluginProvider", (Provider<ChunkBlazerPlugin>) () -> chunkBlazerPlugin);

		constructionModule.setCompletionCallback(completionCallback);

		lenient().when(client.getTickCount()).thenAnswer(inv -> currentTick);
		lenient().when(client.isInInstancedRegion()).thenReturn(false);
		// Default: tests opt out of the region gate by returning -1 ("unknown"),
		// which the module treats as a non-enforced check. Tests that exercise
		// the gate stub a real region per-test.
		lenient().when(chunkBlazerPlugin.findRegionForTask(anyString())).thenReturn(-1);
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

	private NuzlockeTask buildTask(String name, String taskId, int objectId)
	{
		return createTaskWithRequiredObject(name, taskId, "CONSTRUCTION", 1, Arrays.asList(objectId));
	}

	private void spawnGameObject(int objectId)
	{
		GameObject obj = mock(GameObject.class);
		lenient().when(obj.getId()).thenReturn(objectId);
		GameObjectSpawned event = new GameObjectSpawned();
		event.setGameObject(obj);
		constructionModule.onGameObjectSpawned(event);
	}

	private void spawnDecorativeObject(int objectId)
	{
		DecorativeObject obj = mock(DecorativeObject.class);
		lenient().when(obj.getId()).thenReturn(objectId);
		DecorativeObjectSpawned event = new DecorativeObjectSpawned();
		event.setDecorativeObject(obj);
		constructionModule.onDecorativeObjectSpawned(event);
	}

	private void spawnWallObject(int objectId)
	{
		WallObject obj = mock(WallObject.class);
		lenient().when(obj.getId()).thenReturn(objectId);
		WallObjectSpawned event = new WallObjectSpawned();
		event.setWallObject(obj);
		constructionModule.onWallObjectSpawned(event);
	}

	private void spawnGroundObject(int objectId)
	{
		GroundObject obj = mock(GroundObject.class);
		lenient().when(obj.getId()).thenReturn(objectId);
		GroundObjectSpawned event = new GroundObjectSpawned();
		event.setGroundObject(obj);
		constructionModule.onGroundObjectSpawned(event);
	}

	private void gainConstructionXp(int newTotalXp)
	{
		constructionModule.onStatChanged(new StatChanged(Skill.CONSTRUCTION, newTotalXp, 50, 50));
	}

	private void sceneLoad()
	{
		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOADING);
		constructionModule.onGameStateChanged(event);
	}

	@Test
	void testGetCompletionType()
	{
		assertEquals("CONSTRUCTION", constructionModule.getCompletionType());
	}

	@Test
	void testCanHandle()
	{
		assertTrue(constructionModule.canHandle(buildTask("Build a Medium STASH", "build_stash", STASH_OBJECT)));

		NuzlockeTask wrong = createTestTask("Mine Ore", "mine_ore", "MINING", 5);
		assertFalse(constructionModule.canHandle(wrong));
	}

	@Test
	void spawnThenXpCreditsAndCompletes()
	{
		NuzlockeTask task = buildTask("Build a Medium STASH", "build_medium_stash", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 10;
		spawnGameObject(STASH_OBJECT);
		assertEquals(0, task.getCurrentProgress(), "spawn alone must not credit");

		gainConstructionXp(150);

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted());
		verify(completionCallback).onTaskCompleted(task, 1);
	}

	@Test
	void xpThenSpawnSameTickStillCredits()
	{
		// Event order within the build tick is not guaranteed. The old module
		// only handled spawn-then-XP; XP-then-spawn silently missed.
		NuzlockeTask task = buildTask("Build a Medium STASH", "build_medium_stash", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 10;
		gainConstructionXp(150);
		assertEquals(0, task.getCurrentProgress(), "xp alone must not credit");

		spawnGameObject(STASH_OBJECT);

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	@Test
	void xpThenSpawnWithinWindowCredits()
	{
		NuzlockeTask task = buildTask("Build a Medium STASH", "build_medium_stash", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 10;
		gainConstructionXp(150);
		currentTick = 12;
		spawnGameObject(STASH_OBJECT);

		assertTrue(task.isCompleted());
	}

	@Test
	void mountedFurnitureCreditsViaDecorativeObject()
	{
		// Mounted glory / capes are DecorativeObjects, not GameObjects — the
		// old module never heard them at all.
		NuzlockeTask task = buildTask("Build a Mounted Glory", "build_mounted_glory", MOUNTED_GLORY);
		constructionModule.addActiveTask(task);

		currentTick = 20;
		spawnDecorativeObject(MOUNTED_GLORY);
		gainConstructionXp(290);

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	@Test
	void wallAndGroundObjectSpawnsAlsoCredit()
	{
		NuzlockeTask wallTask = buildTask("Build a Wall Thing", "build_wall_thing", 13100);
		constructionModule.addActiveTask(wallTask);
		currentTick = 5;
		spawnWallObject(13100);
		gainConstructionXp(100);
		assertTrue(wallTask.isCompleted());

		NuzlockeTask rugTask = buildTask("Build a Rug", "build_rug", 13588);
		constructionModule.addActiveTask(rugTask);
		currentTick = 30;
		spawnGroundObject(13588);
		gainConstructionXp(220);
		assertTrue(rugTask.isCompleted());
	}

	@Test
	void spawnOutsideWindowDoesNotCredit()
	{
		NuzlockeTask task = buildTask("Build a Medium STASH", "build_medium_stash", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 0;
		spawnGameObject(STASH_OBJECT);
		currentTick = 10; // window is 5 ticks
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void unwatchedObjectSpawnDoesNotCredit()
	{
		NuzlockeTask task = buildTask("Build a Medium STASH", "build_medium_stash", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 10;
		spawnGameObject(99999);
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
	}

	@Test
	void regionGateBlocksBuildInWrongOverworldRegion()
	{
		// "Easy STASH Unit" object_ids repeat across the overworld; a build in
		// the wrong chunk must not credit this chunk's task.
		NuzlockeTask task = buildTask("Build an Easy STASH", "build_easy_stash_exam_centre", 28980);
		constructionModule.addActiveTask(task);
		when(chunkBlazerPlugin.findRegionForTask("build_easy_stash_exam_centre")).thenReturn(13363);
		when(playerLocation.getRegionID()).thenReturn(11826); // somewhere else

		currentTick = 10;
		spawnGameObject(28980);
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void regionGatePassesInMatchingRegion()
	{
		NuzlockeTask task = buildTask("Build an Easy STASH", "build_easy_stash_exam_centre", 28980);
		constructionModule.addActiveTask(task);
		when(chunkBlazerPlugin.findRegionForTask("build_easy_stash_exam_centre")).thenReturn(13363);
		when(playerLocation.getRegionID()).thenReturn(13363);

		currentTick = 10;
		spawnGameObject(28980);
		gainConstructionXp(150);

		assertTrue(task.isCompleted());
	}

	@Test
	void instancedRegionBypassesRegionGate()
	{
		// POH interiors are instanced regions whose ids never match an
		// overworld chunk — the old module's gate blocked every indoor build.
		NuzlockeTask task = buildTask("Build a Mounted Glory", "build_mounted_glory", MOUNTED_GLORY);
		constructionModule.addActiveTask(task);
		// lenient: the instance check short-circuits BEFORE the region lookup —
		// that short-circuit is exactly what this test proves.
		lenient().when(chunkBlazerPlugin.findRegionForTask("build_mounted_glory")).thenReturn(10553);
		lenient().when(playerLocation.getRegionID()).thenReturn(7513); // POH instance
		when(client.isInInstancedRegion()).thenReturn(true);

		currentTick = 10;
		spawnDecorativeObject(MOUNTED_GLORY);
		gainConstructionXp(290);

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	@Test
	void sceneReplaySpawnIsSuppressed()
	{
		// Walking into an area (or toggling POH build mode) re-fires spawn
		// events for furniture that ALREADY exists. Those must not pair with
		// an unrelated XP drop.
		NuzlockeTask task = buildTask("Build a Medium STASH", "build_medium_stash", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 5;
		sceneLoad();
		spawnGameObject(STASH_OBJECT); // replay of the existing STASH
		currentTick = 6;
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void buildShortlyAfterSceneLoadStillCredits()
	{
		// The suppression window is short — a real build a few ticks after
		// entering the area must still count.
		NuzlockeTask task = buildTask("Build a Medium STASH", "build_medium_stash", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 5;
		sceneLoad();
		currentTick = 10;
		spawnGameObject(STASH_OBJECT);
		gainConstructionXp(150);

		assertTrue(task.isCompleted());
	}

	@Test
	void secondXpGainDoesNotDoubleCreditConsumedSpawn()
	{
		// Target-2 task: one build credits once even if a second XP drop lands
		// inside the window — the spawn is consumed on first credit.
		NuzlockeTask task = createTaskWithRequiredObject(
			"Build two STASHes", "build_two_stashes", "CONSTRUCTION", 2, Arrays.asList(STASH_OBJECT));
		task.setTargetQuantity(2);
		constructionModule.addActiveTask(task);

		currentTick = 10;
		spawnGameObject(STASH_OBJECT);
		gainConstructionXp(150);
		assertEquals(1, task.getCurrentProgress());

		currentTick = 12;
		gainConstructionXp(300); // unrelated XP, no new spawn
		assertEquals(1, task.getCurrentProgress(), "consumed spawn must not credit twice");

		// A second real build completes it.
		currentTick = 20;
		spawnGameObject(STASH_OBJECT);
		gainConstructionXp(450);
		assertEquals(2, task.getCurrentProgress());
		assertTrue(task.isCompleted());
	}

	@Test
	void taskWithoutRequiredObjectIsRejectedLoudly()
	{
		// Every authored construction task names its finished object; one
		// without it can never credit and must not be silently tracked.
		NuzlockeTask task = createTestTask("Build Something", "build_something", "CONSTRUCTION", 1);
		constructionModule.addActiveTask(task);

		currentTick = 10;
		spawnGameObject(STASH_OBJECT);
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	/**
	 * Mike's 2026-07-14 QA regression: STASH units are varbit multilocs — the
	 * unbuilt marker and the built STASH are the same scene object re-dressed
	 * by an impostor, so building one fires NO spawn event (his build XP
	 * landed with "recent spawns: []"). The Build menu click on the watched
	 * object id, followed by Construction XP, is the detection path.
	 */
	@Test
	void stashBuildClickThenXpCredits_NoSpawnEverFires()
	{
		NuzlockeTask task = buildTask("Build a Medium STASH Unit at Catherby Shore",
			"build_medium_stash_catherby_shore", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 100;
		constructionModule.onMenuOptionClicked(buildClick(STASH_OBJECT));
		assertEquals(0, task.getCurrentProgress(), "click alone must not credit");

		// Build animation runs; XP lands a few ticks later. No spawn event.
		currentTick = 106;
		gainConstructionXp(150);

		assertEquals(1, task.getCurrentProgress());
		assertTrue(task.isCompleted());
		verify(completionCallback).onTaskCompleted(task, 1);
	}

	@Test
	void stashClickWithoutXpNeverCredits()
	{
		// Misclick or missing materials: no Construction XP follows, and by the
		// time some unrelated XP arrives the click has aged out of the window.
		NuzlockeTask task = buildTask("Build a Medium STASH Unit at Catherby Shore",
			"build_medium_stash_catherby_shore", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 100;
		constructionModule.onMenuOptionClicked(buildClick(STASH_OBJECT));
		currentTick = 120; // click window is 12 ticks
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void buildClickOnUnwatchedObjectDoesNotCredit()
	{
		// A "Build" click on a POH hotspot (unwatched id) must not arm the
		// STASH task.
		NuzlockeTask task = buildTask("Build a Medium STASH Unit at Catherby Shore",
			"build_medium_stash_catherby_shore", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 100;
		constructionModule.onMenuOptionClicked(buildClick(15361));
		currentTick = 103;
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
	}

	@Test
	void nonBuildClickOnWatchedObjectDoesNotCredit()
	{
		// "Search" on an already-built STASH followed by unrelated XP must not
		// credit — only the Build verb arms the click path.
		NuzlockeTask task = buildTask("Build a Medium STASH Unit at Catherby Shore",
			"build_medium_stash_catherby_shore", STASH_OBJECT);
		constructionModule.addActiveTask(task);

		currentTick = 100;
		MenuOptionClicked search = mock(MenuOptionClicked.class);
		when(search.getMenuAction()).thenReturn(MenuAction.GAME_OBJECT_FIRST_OPTION);
		lenient().when(search.getId()).thenReturn(STASH_OBJECT);
		lenient().when(search.getMenuOption()).thenReturn("Search");
		constructionModule.onMenuOptionClicked(search);

		currentTick = 103;
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
	}

	@Test
	void regionGateAppliesToBuildClickPath()
	{
		NuzlockeTask task = buildTask("Build an Easy STASH", "build_easy_stash_exam_centre", 28980);
		constructionModule.addActiveTask(task);
		when(chunkBlazerPlugin.findRegionForTask("build_easy_stash_exam_centre")).thenReturn(13363);
		when(playerLocation.getRegionID()).thenReturn(11826); // wrong chunk

		currentTick = 100;
		constructionModule.onMenuOptionClicked(buildClick(28980));
		currentTick = 103;
		gainConstructionXp(150);

		assertEquals(0, task.getCurrentProgress());
		assertFalse(task.isCompleted());
	}

	@Test
	void consumedBuildClickDoesNotDoubleCredit()
	{
		NuzlockeTask task = createTaskWithRequiredObject(
			"Build two STASHes", "build_two_stashes", "CONSTRUCTION", 2, Arrays.asList(STASH_OBJECT));
		task.setTargetQuantity(2);
		constructionModule.addActiveTask(task);

		currentTick = 100;
		constructionModule.onMenuOptionClicked(buildClick(STASH_OBJECT));
		currentTick = 104;
		gainConstructionXp(150);
		assertEquals(1, task.getCurrentProgress());

		// A second XP drop inside the click window: the click was consumed.
		currentTick = 108;
		gainConstructionXp(300);
		assertEquals(1, task.getCurrentProgress(), "consumed click must not credit twice");
	}

	private MenuOptionClicked buildClick(int objectId)
	{
		MenuOptionClicked event = mock(MenuOptionClicked.class);
		when(event.getMenuAction()).thenReturn(MenuAction.GAME_OBJECT_FIRST_OPTION);
		lenient().when(event.getId()).thenReturn(objectId);
		lenient().when(event.getMenuOption()).thenReturn("Build");
		lenient().when(event.getMenuTarget()).thenReturn("Inconspicuous bush");
		return event;
	}

	@Test
	void startUpAndShutDownManageEventBus()
	{
		constructionModule.startUp();
		verify(eventBus).register(constructionModule);
		constructionModule.shutDown();
		verify(eventBus).unregister(constructionModule);
	}
}
