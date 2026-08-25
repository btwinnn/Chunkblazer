package com.chunkblazer;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the charter-port unlock model: charter ports are unlock-on-demand
 * (free, 0-cost, unlockable from anywhere because they're reached by boat and
 * aren't adjacent neighbours), plus the one-time migration that strips
 * previously-seeded charter regions so existing players converge on it.
 */
@ExtendWith(MockitoExtension.class)
class ChunkBlazerPluginTest
{
	// Musa Point Docks charter port. regionY (11825 & 0xFF = 49) sits inside the
	// surface band [39,64], so it is NOT treated as a free dungeon region.
	private static final int CHARTER_REGION = 11825;
	// Lumbridge — a normal (non-charter) surface region, deliberately NOT
	// registered as a chunk in these tests.
	private static final int NON_CHARTER_REGION = 12850;
	// A surface-band region (12345 & 0xFF = 57, inside [39,64]) used as a
	// Free_Chunks.json entry — so it isn't caught by the free-dungeon coordinate rule.
	private static final int FREE_REGION = 12345;

	@Mock
	private ChunkBlazerConfig config;

	@Mock
	private ConfigManager configManager;

	private ChunkBlazerPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);

		// Register a charter-port chunk at CHARTER_REGION.
		NuzlockeChunk charter = new NuzlockeChunk();
		setField(charter, "chunkType", "CHARTER");
		setField(charter, "regionIds", Arrays.asList(CHARTER_REGION));
		chunksByRegionId().put(CHARTER_REGION, charter);
	}

	@Test
	void isCharterRegion_trueForCharterChunk_falseForOthers()
	{
		assertTrue(plugin.isCharterRegion(CHARTER_REGION));
		assertFalse(plugin.isCharterRegion(NON_CHARTER_REGION));
	}

	@Test
	void charterPortsCostZeroToUnlock()
	{
		assertEquals(0, plugin.getRegionUnlockCost(CHARTER_REGION));
	}

	@Test
	void charterPortIsUnlockableWhileLocked()
	{
		when(config.unlockedChunks()).thenReturn(String.valueOf(NON_CHARTER_REGION));
		// Unlockable even though it isn't a neighbour of anything unlocked.
		assertTrue(plugin.isUnlockableRegion(CHARTER_REGION));
	}

	@Test
	void charterPortNotUnlockableOnceUnlocked()
	{
		when(config.unlockedChunks()).thenReturn(NON_CHARTER_REGION + "," + CHARTER_REGION);
		assertFalse(plugin.isUnlockableRegion(CHARTER_REGION));
	}

	@Test
	void migrationStripsSeededCharterRegionsAndSetsFlag()
	{
		when(configManager.getConfiguration("chunkblazer", "charterSeedStripped")).thenReturn(null);
		when(config.unlockedChunks()).thenReturn(NON_CHARTER_REGION + "," + CHARTER_REGION);

		plugin.migrateStripSeededCharterChunks();

		// unlockedChunks rewritten WITHOUT the charter region, keeping the rest.
		//
		// Captured as Object, not String: per-account writes now go through
		// setAccountState(String, Object), which binds to ConfigManager's generic
		// setConfiguration(String, String, T) overload rather than the all-String
		// one. Both stringify into the same ConfigData, so this is purely about
		// naming the overload Mockito should watch.
		ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
		verify(configManager).setConfiguration(eq("chunkblazer"), eq("unlockedChunks"), written.capture());
		assertTrue(String.valueOf(written.getValue()).contains(String.valueOf(NON_CHARTER_REGION)));
		assertFalse(String.valueOf(written.getValue()).contains(String.valueOf(CHARTER_REGION)));

		// Flag set so the migration never runs again.
		verify(configManager).setConfiguration("chunkblazer", "charterSeedStripped", "true");
	}

	@Test
	void migrationIsNoOpWhenFlagAlreadySet()
	{
		when(configManager.getConfiguration("chunkblazer", "charterSeedStripped")).thenReturn("true");

		plugin.migrateStripSeededCharterChunks();

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("unlockedChunks"), any());
	}

	// --- Boss Tokens (secondary currency) ---

	@Test
	void newPlayerStartsWithTwoBossTokens()
	{
		when(config.bossTokens()).thenReturn(2); // the config default
		assertEquals(2, plugin.getBossTokens());
	}

	@Test
	void spendBossTokenDecrementsWhenAvailable()
	{
		when(config.bossTokens()).thenReturn(2);
		assertTrue(plugin.spendBossToken());
		verify(configManager).setConfiguration("chunkblazer", "bossTokens", 1);
	}

	@Test
	void spendBossTokenFailsWhenNoneLeft()
	{
		when(config.bossTokens()).thenReturn(0);
		assertFalse(plugin.spendBossToken());
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("bossTokens"), any());
	}

	@Test
	void addBossTokensIncrements()
	{
		when(config.bossTokens()).thenReturn(2);
		plugin.addBossTokens(1);
		verify(configManager).setConfiguration("chunkblazer", "bossTokens", 3);
	}

	@Test
	void addBossTokensClampsAtZero()
	{
		when(config.bossTokens()).thenReturn(0);
		plugin.addBossTokens(-5);
		verify(configManager).setConfiguration("chunkblazer", "bossTokens", 0);
	}

	// --- Free chunks (Free_Chunks.json: 0-cost, unlock-on-demand, no tasks) ---

	@Test
	void freeChunkIsZeroCostAndUnlockableFromAnywhere() throws Exception
	{
		freeUnlockableRegionIds().add(FREE_REGION);
		when(config.unlockedChunks()).thenReturn(""); // not yet unlocked
		assertTrue(plugin.isFreeUnlockableRegion(FREE_REGION));
		assertEquals(0, plugin.getRegionUnlockCost(FREE_REGION));
		// Unlockable even though it's neither a neighbour nor a defined chunk.
		assertTrue(plugin.isUnlockableRegion(FREE_REGION));
	}

	@Test
	void freeChunkStartsLockedNotAlwaysAccessible() throws Exception
	{
		// Regression for the decouple: older builds treated Free_Chunks.json regions
		// as always-unlocked. Being in the list must NOT make it always-free now.
		freeUnlockableRegionIds().add(FREE_REGION);
		when(config.unlockedChunks()).thenReturn("");
		assertFalse(plugin.isFreeRegion(FREE_REGION));     // coordinate rule only
		assertFalse(plugin.isRegionUnlocked(FREE_REGION)); // not auto-unlocked
	}

	@Test
	void freeChunksJsonParsesAndLoadsFromResource() throws Exception
	{
		// Guards the bundled Free_Chunks.json against syntax/schema breakage: inject a
		// real Gson, run the loader, and confirm it populated the free set + names.
		setField(plugin, "gson", new com.google.gson.Gson());
		// Free_Chunks.json now ships inside the bundled seed and is read through
		// CatalogStore; give the plugin a seed-backed store (getFileContent falls back
		// to the bundled seed with no init()/network needed).
		setField(plugin, "catalogStore", seedBackedCatalogStore());
		java.lang.reflect.Method load = plugin.getClass().getDeclaredMethod("loadFreeChunks");
		load.setAccessible(true);
		load.invoke(plugin);

		assertTrue(freeUnlockableRegionIds().contains(12336)); // Tutorial Island
		assertTrue(freeUnlockableRegionIds().contains(11323)); // Ice Path
		assertEquals(0, plugin.getRegionUnlockCost(12336));
		assertEquals("Tutorial Island (12336)", plugin.getRegionName(12336));
	}

	@Test
	void unlockedFreeChunkOffersItsNeighbors() throws Exception
	{
		// Regression (Cruk's report): free chunks have no task-chunk entry, so an
		// unlocked one contributed NO neighbours — a connectivity dead end that
		// made e.g. the Rellekka islands unreachable. Their neighbor_ids come
		// from Free_Chunks.json and must feed the unlockable set.
		setField(plugin, "gson", new com.google.gson.Gson());
		// Free_Chunks.json now ships inside the bundled seed and is read through
		// CatalogStore; give the plugin a seed-backed store (getFileContent falls back
		// to the bundled seed with no init()/network needed).
		setField(plugin, "catalogStore", seedBackedCatalogStore());
		java.lang.reflect.Method load = plugin.getClass().getDeclaredMethod("loadFreeChunks");
		load.setAccessible(true);
		load.invoke(plugin);

		// Troll Arena (11576) unlocked -> its JSON neighbours become unlockable.
		when(config.unlockedChunks()).thenReturn("11576");
		Set<Integer> neighbors = plugin.getNeighborRegionIds();
		assertTrue(neighbors.containsAll(Arrays.asList(11577, 11832, 11575, 11320)));

		// Already-unlocked neighbours are not re-offered.
		when(config.unlockedChunks()).thenReturn("11576,11320");
		assertFalse(plugin.getNeighborRegionIds().contains(11320));
	}

	@Test
	void freeChunkWithoutAuthoredNeighborsStillOffersFourCardinals() throws Exception
	{
		// Invariant: a free chunk ALWAYS opens its 4 cardinal neighbours, even
		// when its Free_Chunks.json entry has no neighbor_ids (derived from the
		// region grid: ±1 = N/S, ±256 = E/W).
		freeUnlockableRegionIds().add(FREE_REGION); // no neighbours registered
		when(config.unlockedChunks()).thenReturn(String.valueOf(FREE_REGION));
		Set<Integer> neighbors = plugin.getNeighborRegionIds();
		assertTrue(neighbors.containsAll(Arrays.asList(
			FREE_REGION + 1, FREE_REGION - 1, FREE_REGION + 256, FREE_REGION - 256)));
	}

	// --- Prifddinas: real city regions in instance coordinates (regionY 94-95) ---

	@Test
	void prifCityRegionsAreNotAutoFreedByCoordinateRule()
	{
		// The city's regionY (94-95) is outside the surface band [39,64], which
		// used to auto-free it like a dungeon. It must be exempt...
		assertFalse(plugin.isFreeRegion(12894));
		assertFalse(plugin.isFreeRegion(12895));
		assertFalse(plugin.isFreeRegion(13150));
		assertFalse(plugin.isFreeRegion(13151));
		// ...while genuine out-of-band regions stay free (regionY 16 < 39).
		assertTrue(plugin.isFreeRegion(10000));
	}

	@Test
	void prifCityLockedAndNotUnlockableWithoutGateChunk()
	{
		when(config.unlockedChunks()).thenReturn("");
		assertFalse(plugin.isRegionUnlocked(12894));
		assertFalse(plugin.isUnlockableRegion(12894));
	}

	@Test
	void prifCityUnlockableOnceAnyGateChunkUnlocked()
	{
		// Unlocking one surrounding Tirannwn gate chunk (8757) bridges all four
		// city chunks into the unlockable (yellow) set.
		when(config.unlockedChunks()).thenReturn("8757");
		Set<Integer> neighbors = plugin.getNeighborRegionIds();
		assertTrue(neighbors.containsAll(Arrays.asList(12894, 12895, 13150, 13151)));
		assertTrue(plugin.isUnlockableRegion(12894));
	}

	@Test
	void prifCityChunkNotReofferedOnceUnlocked()
	{
		when(config.unlockedChunks()).thenReturn("8757,12894");
		Set<Integer> neighbors = plugin.getNeighborRegionIds();
		assertFalse(neighbors.contains(12894));
		assertTrue(neighbors.contains(12895)); // the others remain unlockable
	}

	// --- initializeTask: saved-target restore must not corrupt multi-item sets ---

	@Test
	void restoreDoesNotPinSummedTargetOntoFirstSetPiece() throws Exception
	{
		// Mike's obtain_set bug: for a multi-item set the saved target is the
		// SUM across items (5 for Splitbark). Restoring pinned that sum onto the
		// FIRST item's roll cache, so the helm slot demanded 5 helms — duplicate
		// copies of one piece then counted toward the whole set (3 helms = 3/9).
		when(config.taskProgressData()).thenReturn("obtain_splitbark_set:2:5");

		NuzlockeTask task = new NuzlockeTask();
		task.setName("Obtain a Splitbark Set");
		task.setTaskId("obtain_splitbark_set");
		task.setCompletionType("OBTAIN");
		java.util.List<RequiredItem> pieces = new java.util.ArrayList<>();
		for (int itemId : new int[]{3385, 3387, 3389, 3391, 3393})
		{
			RequiredItem piece = new RequiredItem();
			piece.setItemIds(Arrays.asList(itemId));
			pieces.add(piece);
		}
		task.setRequiredItems(pieces);

		initializeTask(task);

		assertEquals(5, task.getTargetQuantity());
		assertEquals(2, task.getCurrentProgress());
		for (RequiredItem piece : task.getRequiredItems())
		{
			assertEquals(1, piece.getRequiredQuantity(),
				"each set slot must keep its authored quantity of 1, not the task-total");
		}
	}

	@Test
	void restoreStillPinsSavedRollForSingleItemTask() throws Exception
	{
		// Single-item tasks with a quantity range must keep the pin: the saved
		// value IS that item's roll, and without it modules would re-roll and
		// disagree with the panel (the original roll-cache bug).
		when(config.taskProgressData()).thenReturn("cook_shrimp:1:17");

		NuzlockeTask task = new NuzlockeTask();
		task.setName("Cook some Shrimp");
		task.setTaskId("cook_shrimp");
		task.setCompletionType("COOKING");
		RequiredItem shrimp = new RequiredItem();
		shrimp.setItemIds(Arrays.asList(315));
		shrimp.setQuantityRange(Arrays.asList(5, 25));
		task.setRequiredItems(java.util.Collections.singletonList(shrimp));

		initializeTask(task);

		assertEquals(17, task.getTargetQuantity());
		assertEquals(17, shrimp.getRequiredQuantity());
	}

	private void initializeTask(NuzlockeTask task) throws Exception
	{
		java.lang.reflect.Method init = plugin.getClass().getDeclaredMethod("initializeTask", NuzlockeTask.class);
		init.setAccessible(true);
		init.invoke(plugin, task);
	}

	// --- reflection helpers (the plugin's fields are private; no test seam) ---

	@SuppressWarnings("unchecked")
	private Map<Integer, NuzlockeChunk> chunksByRegionId() throws Exception
	{
		Field f = findField(plugin.getClass(), "chunksByRegionId");
		f.setAccessible(true);
		return (Map<Integer, NuzlockeChunk>) f.get(plugin);
	}

	@SuppressWarnings("unchecked")
	private Set<Integer> freeUnlockableRegionIds() throws Exception
	{
		Field f = findField(plugin.getClass(), "freeUnlockableRegionIds");
		f.setAccessible(true);
		return (Set<Integer>) f.get(plugin);
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		Field f = findField(target.getClass(), name);
		f.setAccessible(true);
		f.set(target, value);
	}

	/**
	 * A CatalogStore that serves files from the bundled seed. getFileContent() falls
	 * back to the gzipped seed per-file, so no init()/network is needed here — the http
	 * client is never touched on that path.
	 */
	private com.chunkblazer.api.CatalogStore seedBackedCatalogStore()
	{
		// A real (unused) client — the constructor calls newBuilder() on it, and the
		// seed path never makes a request. Cheap to build; opens no connections.
		return new com.chunkblazer.api.CatalogStore(
			new okhttp3.OkHttpClient(), config, new com.google.gson.Gson());
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException
	{
		for (Class<?> k = type; k != null; k = k.getSuperclass())
		{
			try
			{
				return k.getDeclaredField(name);
			}
			catch (NoSuchFieldException ignored)
			{
				// walk up to superclass
			}
		}
		throw new NoSuchFieldException(name);
	}
}
