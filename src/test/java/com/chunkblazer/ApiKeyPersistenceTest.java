package com.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import com.chunkblazer.api.ChunkBlazerApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The account API key is a bearer credential. Since the server now returns it only on the
 * FIRST claim of an RSN (security C1 - the public rsn_hash must no longer buy anyone's key),
 * the client persists it PER-ACCOUNT and recovers it from a UUID-format copy-paste field.
 *
 * <p>These tests lock in the C1 invariants:
 * <ol>
 *   <li>A claimed key is stored only per-account (RSProfile) and is NEVER mirrored into the
 *       shared {@code config.apiKey()} slot - that shared slot was the cross-account leak.</li>
 *   <li>A pasted recovery key must be UUID-format; a non-UUID paste is rejected and the box
 *       cleared.</li>
 *   <li>A valid pasted key becomes the pending candidate and is adopted only after the server
 *       confirms it, so it is NOT written into RSProfile at load time.</li>
 *   <li>On login the client uses this account's key, or none, never a previous account's.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyPersistenceTest
{
	// Canonical UUIDs: the only shape the server issues and isValidApiKeyFormat accepts.
	private static final String KEY_A = "11111111-1111-1111-1111-111111111111";
	private static final String KEY_B = "22222222-2222-2222-2222-222222222222";
	private static final String KEY_C = "33333333-3333-3333-3333-333333333333";

	@Mock
	private ChunkBlazerConfig config;
	@Mock
	private ConfigManager configManager;
	@Mock
	private ChunkBlazerApiClient apiClient;
	@Mock
	private ClientThread clientThread;

	private ChunkBlazerPlugin plugin;
	private Map<String, String> rsProfile;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);
		setField(plugin, "apiClient", apiClient);
		// Needed so the invalid-key branch's chat message (which posts via clientThread)
		// does not NPE. Left unstubbed: invoke() is a no-op, the lambda never runs.
		setField(plugin, "clientThread", clientThread);
		rsProfile = RsProfileTestSupport.install(configManager, config);
	}

	@Test
	void firstClaimKeyIsStoredPerAccountAndNotMirrored() throws Exception
	{
		persist(KEY_A);

		assertEquals(KEY_A, rsProfile.get("apiKey"), "key persisted per-account (RSProfile)");
		// The shared config slot is the cross-account leak: it must NEVER be written.
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("apiKey"), any());
	}

	@Test
	void anAlreadyStoredKeyIsNotRewritten() throws Exception
	{
		rsProfile.put("apiKey", KEY_A);

		persist(KEY_A);

		verify(configManager, never()).setRSProfileConfiguration(eq("chunkblazer"), eq("apiKey"), any());
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("apiKey"), any());
	}

	@Test
	void anEmptyKeyIsIgnored() throws Exception
	{
		persist("");
		persist(null);

		verify(configManager, never()).setRSProfileConfiguration(eq("chunkblazer"), eq("apiKey"), any());
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("apiKey"), any());
	}

	@Test
	void loadUsesPerAccountKeyWhenNoPaste() throws Exception
	{
		rsProfile.put("apiKey", KEY_A);
		lenient().when(config.apiKey()).thenReturn(""); // empty recovery box

		load();

		verify(apiClient).setPlayerApiKey(KEY_A);
		// No paste, so nothing is written back anywhere: no mirror, no self-heal.
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("apiKey"), any());
	}

	@Test
	void pastedKeyLoadsAsPendingOnly() throws Exception
	{
		// RSProfile empty (fresh install / new profile); the player pasted a saved UUID key.
		lenient().when(config.apiKey()).thenReturn("  " + KEY_B + "  ");

		load();

		// Tried on the client (trimmed) as the pending candidate for this login...
		verify(apiClient).setPlayerApiKey(KEY_B);
		// ...but NOT persisted at load: adoption waits for the server to confirm it.
		assertNull(rsProfile.get("apiKey"), "pasted key is not written into RSProfile at load time");
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("apiKey"), any());
	}

	@Test
	void loadRejectsInvalidPastedKeyAndClearsBox() throws Exception
	{
		// RSProfile empty, and the recovery box holds something that is not a UUID.
		lenient().when(config.apiKey()).thenReturn("not-a-real-key");

		load();

		// The garbage paste is wiped from the box so it cannot be retried or override a key.
		verify(configManager).setConfiguration("chunkblazer", "apiKey", "");
		// With no valid key for this account, the client is cleared so it never
		// authenticates as a previously-logged-in account.
		verify(apiClient).setPlayerApiKey(null);
	}

	// --- helpers -----------------------------------------------------------

	private void persist(String key) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("persistApiKey", String.class);
		m.setAccessible(true);
		m.invoke(plugin, key);
	}

	private void load() throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("loadPersistedApiKey");
		m.setAccessible(true);
		m.invoke(plugin);
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
