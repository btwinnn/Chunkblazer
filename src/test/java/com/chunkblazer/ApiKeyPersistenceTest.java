package com.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import com.chunkblazer.api.ChunkBlazerApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The account API key is a bearer credential. Since the server now returns it only on the
 * FIRST claim of an RSN (security C1 — the public rsn_hash must no longer buy anyone's key),
 * the client has to persist it per-account and be able to recover it from the copy-paste
 * field, or a returning account silently loses write access.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyPersistenceTest
{
	@Mock
	private ChunkBlazerConfig config;
	@Mock
	private ConfigManager configManager;
	@Mock
	private ChunkBlazerApiClient apiClient;

	private ChunkBlazerPlugin plugin;
	private Map<String, String> rsProfile;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);
		setField(plugin, "apiClient", apiClient);
		rsProfile = RsProfileTestSupport.install(configManager, config);
	}

	@Test
	void firstClaimKeyIsStoredPerAccountAndMirroredToTheRecoveryField() throws Exception
	{
		persist("KEY-abc");

		assertEquals("KEY-abc", rsProfile.get("apiKey"), "key persisted per-account (RSProfile)");
		verify(configManager).setConfiguration("chunkblazer", "apiKey", "KEY-abc");
	}

	@Test
	void anAlreadyStoredKeyIsNotRewritten() throws Exception
	{
		rsProfile.put("apiKey", "KEY-abc");
		lenient().when(config.apiKey()).thenReturn("KEY-abc");

		persist("KEY-abc");

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("apiKey"), any());
	}

	@Test
	void anEmptyKeyIsIgnored() throws Exception
	{
		persist("");
		persist(null);

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("apiKey"), any());
	}

	@Test
	void loadPrefersThePerAccountKey() throws Exception
	{
		rsProfile.put("apiKey", "ACCOUNT-KEY");
		lenient().when(config.apiKey()).thenReturn("PASTED-KEY");

		load();

		verify(apiClient).setPlayerApiKey("ACCOUNT-KEY");
	}

	@Test
	void loadFallsBackToThePastedRecoveryKey() throws Exception
	{
		// RSProfile empty (fresh install / new profile); the player pasted a backup key.
		lenient().when(config.apiKey()).thenReturn("  PASTED-KEY  ");

		load();

		verify(apiClient).setPlayerApiKey("PASTED-KEY"); // trimmed
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
