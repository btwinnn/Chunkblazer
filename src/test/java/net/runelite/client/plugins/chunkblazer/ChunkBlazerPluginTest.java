package net.runelite.client.plugins.chunkblazer;

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
		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq("chunkblazer"), eq("unlockedChunks"), written.capture());
		assertTrue(written.getValue().contains(String.valueOf(NON_CHARTER_REGION)));
		assertFalse(written.getValue().contains(String.valueOf(CHARTER_REGION)));

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
		java.lang.reflect.Method load = plugin.getClass().getDeclaredMethod("loadFreeChunks");
		load.setAccessible(true);
		load.invoke(plugin);

		assertTrue(freeUnlockableRegionIds().contains(12336)); // Tutorial Island
		assertTrue(freeUnlockableRegionIds().contains(11323)); // Ice Path
		assertEquals(0, plugin.getRegionUnlockCost(12336));
		assertEquals("Tutorial Island (12336)", plugin.getRegionName(12336));
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
