package com.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import com.google.common.hash.Hashing;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cross-account state protection.
 *
 * <p>ChunkBlazer progress lives in RuneLite config, which is scoped to the
 * PROFILE, not the account. An alt logging in on the same profile used to find
 * the main's completed tasks still there; hydration only fills state in when
 * local is EMPTY so it skipped, and the next sync — destructive and
 * client-authoritative — wrote the main's progress over the alt's server record.
 *
 * <p>The dangerous half of the fix is the wipe, so most of these tests are about
 * when it must NOT happen.
 */
@ExtendWith(MockitoExtension.class)
class AccountStateReconcileTest
{
	private static final String MAIN = "SeaShantyBoy";
	private static final String ALT = "FullOfSodium";

	@Mock
	private ChunkBlazerConfig config;

	@Mock
	private ConfigManager configManager;

	@Mock
	private com.chunkblazer.modules.TaskModuleManager taskModuleManager;

	@Mock
	private net.runelite.client.callback.ClientThread clientThread;

	private ChunkBlazerPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);
		setField(plugin, "taskModuleManager", taskModuleManager);
		setField(plugin, "clientThread", clientThread);
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

	private void reconcile(String rsn) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("reconcileAccountState", String.class);
		m.setAccessible(true);
		m.invoke(plugin, rsn);
	}

	private static String hashOf(String rsn)
	{
		return Hashing.sha256()
			.hashString(rsn.toLowerCase().trim(), StandardCharsets.UTF_8)
			.toString()
			.substring(0, 16);
	}

	private static String[] stateKeys() throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField("ACCOUNT_STATE_KEYS");
		f.setAccessible(true);
		return (String[]) f.get(null);
	}

	/** The corruption case: a different account logs in on this profile. */
	@Test
	void switchingAccountClearsThePreviousAccountsProgress() throws Exception
	{
		when(config.accountStateOwner()).thenReturn(hashOf(MAIN));

		reconcile(ALT);

		for (String key : stateKeys())
		{
			verify(configManager).unsetConfiguration("chunkblazer", key);
		}
		verify(configManager).setConfiguration("chunkblazer", "accountStateOwner", hashOf(ALT));
	}

	/** The common case — same account, every login. Nothing may be touched. */
	@Test
	void sameAccountIsLeftCompletelyAlone() throws Exception
	{
		when(config.accountStateOwner()).thenReturn(hashOf(MAIN));

		reconcile(MAIN);

		verify(configManager, never()).unsetConfiguration(eq("chunkblazer"), any());
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("accountStateOwner"), any());
	}

	/**
	 * The upgrade path, and the one that would be catastrophic to get wrong:
	 * every existing player has real progress and no owner tag. Treating "no
	 * tag" as "someone else's" would wipe them all on first login.
	 */
	@Test
	void existingProgressIsAdoptedNotWipedOnFirstRun() throws Exception
	{
		when(config.accountStateOwner()).thenReturn("");

		reconcile(MAIN);

		verify(configManager, never()).unsetConfiguration(eq("chunkblazer"), any());
		verify(configManager).setConfiguration("chunkblazer", "accountStateOwner", hashOf(MAIN));
	}

	/**
	 * Upgrade-window hole: on the FIRST login after this shipped there is no
	 * owner tag, so the default is to adopt. If the resident progress actually
	 * belongs to another account, adopting it would let this account sync over
	 * that account's server record — the very corruption being fixed.
	 * accountModeHash is a pre-existing per-account tag and settles it.
	 */
	@Test
	void untaggedStateProvablyOwnedByAnotherAccountIsClearedNotAdopted() throws Exception
	{
		when(config.accountStateOwner()).thenReturn("");
		when(config.accountModeHash()).thenReturn(hashOf(MAIN) + ":CASUAL");

		reconcile(ALT);

		for (String key : stateKeys())
		{
			verify(configManager).unsetConfiguration("chunkblazer", key);
		}
		verify(configManager).setConfiguration("chunkblazer", "accountStateOwner", hashOf(ALT));
	}

	/** Same tag, same account — an ordinary upgrade. Adopt, never wipe. */
	@Test
	void untaggedStateMatchingTheModeHashIsAdopted() throws Exception
	{
		when(config.accountStateOwner()).thenReturn("");
		when(config.accountModeHash()).thenReturn(hashOf(MAIN) + ":CASUAL");

		reconcile(MAIN);

		verify(configManager, never()).unsetConfiguration(eq("chunkblazer"), any());
		verify(configManager).setConfiguration("chunkblazer", "accountStateOwner", hashOf(MAIN));
	}

	/**
	 * No mode locked means no evidence either way. Absence of proof must not
	 * become proof of foreignness — destroying unprovable progress is worse than
	 * the rare inherited-state case.
	 */
	@Test
	void untaggedStateWithNoModeHashIsAdoptedNotWiped() throws Exception
	{
		when(config.accountStateOwner()).thenReturn("");
		when(config.accountModeHash()).thenReturn("");

		reconcile(MAIN);

		verify(configManager, never()).unsetConfiguration(eq("chunkblazer"), any());
		verify(configManager).setConfiguration("chunkblazer", "accountStateOwner", hashOf(MAIN));
	}

	@Test
	void ownerMatchIsCaseAndSpaceInsensitive() throws Exception
	{
		// RuneScape treats "Sea Shanty Boy" / "sea_shanty_boy" as the same name;
		// hashRsn lowercases and trims, so a display-name variant must not read
		// as a different account and trigger a wipe.
		when(config.accountStateOwner()).thenReturn(hashOf("seashantyboy"));

		reconcile("  SeaShantyBoy  ");

		verify(configManager, never()).unsetConfiguration(eq("chunkblazer"), any());
	}

	/** progressionBaseline is client-only, so the wipe must include it. */
	@Test
	void theClearedKeysCoverEveryPerAccountStateKey() throws Exception
	{
		java.util.List<String> keys = java.util.Arrays.asList(stateKeys());

		assertTrue(keys.contains("completedTasks"), "completed tasks are per-account");
		assertTrue(keys.contains("unlockedChunks"), "unlocked chunks are per-account");
		assertTrue(keys.contains("totalPoints"), "points are per-account");
		assertTrue(keys.contains("progressionBaseline"),
			"the Progression baseline is client-only and must not survive an account switch");
		assertFalse(keys.contains("apiBaseUrl"), "the API URL is an install setting, not account state");
	}
}
