package com.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The one-time move of a legacy account's profile-global progress into its RSProfile store
 * ({@code migrateLegacyGlobalStateToRSProfile}). This is the dangerous step — getting the
 * ownership rules wrong loses real progress — so the branches are covered explicitly, per
 * RSPROFILE-MIGRATION-PLAN.md section 4. Mutation-test the mismatch and already-migrated
 * branches: revert the guard and confirm the matching test fails.
 */
@ExtendWith(MockitoExtension.class)
class MigrationToRsProfileTest
{
	@Mock
	private ChunkBlazerConfig config;
	@Mock
	private ConfigManager configManager;
	@Mock
	private Client client;

	private ChunkBlazerPlugin plugin;
	private Map<String, String> rsProfile;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);
		setField(plugin, "client", client);
		rsProfile = RsProfileTestSupport.install(configManager, config);
	}

	// --- the branches ------------------------------------------------------

	@Test
	void untaggedLegacyStateIsMigratedAndOriginalsAreKept() throws Exception
	{
		loggedInAs("Main");
		legacy("unlockedChunks", "12850,12851");
		legacy("completedTasks", "chop_tree,mine_coal");
		legacy("totalPoints", "42");
		// no accountModeHash / accountStateOwner tag: the common single-account case

		migrate();

		assertEquals("12850,12851", rsProfile.get("unlockedChunks"), "unlocks moved to RSProfile");
		assertEquals("chop_tree,mine_coal", rsProfile.get("completedTasks"), "tasks moved to RSProfile");
		assertEquals("42", rsProfile.get("totalPoints"), "points moved to RSProfile");
		// Section 4.4: the source is kept as the only rollback — never deleted this release.
		verify(configManager, never()).unsetConfiguration(eq("chunkblazer"), anyString());
	}

	@Test
	void migrationIsSkippedOnceTheDestinationHoldsData() throws Exception
	{
		loggedInAs("Main");
		rsProfile.put("unlockedChunks", "12850"); // already migrated (destination present)
		legacy("unlockedChunks", "12850,99999");
		legacy("completedTasks", "should_not_be_copied");

		migrate();

		assertEquals("12850", rsProfile.get("unlockedChunks"), "existing RSProfile data is untouched");
		assertFalse(rsProfile.containsKey("completedTasks"), "a re-run must not copy anything");
	}

	@Test
	void aForeignBlobIsNeitherMigratedNorDeleted() throws Exception
	{
		loggedInAs("Main");
		legacy("unlockedChunks", "12850,12851");
		// The resident global blob is tagged as belonging to a DIFFERENT account.
		legacy("accountModeHash", hashRsn("SomeoneElse") + ":CASUAL");

		migrate();

		assertFalse(rsProfile.containsKey("unlockedChunks"),
			"another account's data must not be adopted into this account's store");
		verify(configManager, never()).unsetConfiguration(eq("chunkblazer"), anyString());
	}

	@Test
	void aBlobTaggedForThisAccountIsMigrated() throws Exception
	{
		loggedInAs("Main");
		legacy("unlockedChunks", "12850,12851");
		legacy("accountModeHash", hashRsn("Main") + ":CASUAL"); // matches the current account

		migrate();

		assertEquals("12850,12851", rsProfile.get("unlockedChunks"), "own tagged data migrates");
	}

	@Test
	void nothingHappensWhenTheLegacyStoreIsEmpty() throws Exception
	{
		loggedInAs("Main");
		// no legacy globals at all

		migrate();

		assertTrue(rsProfile.isEmpty(), "no legacy data means nothing to move");
	}

	// --- helpers -----------------------------------------------------------

	private void migrate() throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("migrateLegacyGlobalStateToRSProfile");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	private void legacy(String key, String value)
	{
		lenient().when(configManager.getConfiguration("chunkblazer", key)).thenReturn(value);
	}

	private void loggedInAs(String rsn)
	{
		Player p = org.mockito.Mockito.mock(Player.class);
		lenient().when(p.getName()).thenReturn(rsn);
		lenient().when(client.getLocalPlayer()).thenReturn(p);
	}

	private String hashRsn(String rsn) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("hashRsn", String.class);
		m.setAccessible(true);
		return (String) m.invoke(plugin, rsn);
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		Class<?> c = target.getClass();
		while (c != null)
		{
			try
			{
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				f.set(target, value);
				return;
			}
			catch (NoSuchFieldException e)
			{
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
