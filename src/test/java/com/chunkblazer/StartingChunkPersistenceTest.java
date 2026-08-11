package com.chunkblazer;

import java.lang.reflect.Field;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The start chunk must be PERSISTED, not merely readable.
 *
 * <p>{@code ChunkBlazerConfig.unlockedChunks()} carries an interface default of
 * {@code "12850"}, so the typed accessor answers "Lumbridge is unlocked" even
 * when the key is absent from stored config. Every reader inside this plugin
 * inherits that default and is therefore correct by accident.
 *
 * <p>The standalone ChunkBlazer GPU addon is not. It reads the key raw across a
 * repo boundary — {@code configManager.getConfiguration("chunkblazer",
 * "unlockedChunks")} — with no access to our defaults, so an absent key reads as
 * an EMPTY unlock set and it greys the entire world, Lumbridge included.
 *
 * <p>Bruh Blazer hit that on 2026-07-27. An account switch unset the key at
 * 21:11:03; the server had no regions for the new account so the merge returned
 * early; {@code ensureStartingChunkUnlocked()} saw the default and concluded
 * there was nothing to write. He walked around a fully grey Lumbridge until
 * 21:16:47, when his first paid unlock finally wrote a concrete value and both
 * chunks lit up at once. A second account in the same session never reproduced
 * it — the server had regions for that one, so the merge wrote them immediately.
 *
 * <p>These tests pin the distinction the accessor cannot express: the mocks for
 * the typed read and the raw read are set independently, which is exactly the
 * state the bug lived in.
 */
@ExtendWith(MockitoExtension.class)
class StartingChunkPersistenceTest
{
	private static final int LUMBRIDGE = 12850;
	private static final String KEY = "unlockedChunks";

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

	/** Set up the two reads independently: what the accessor says vs what is stored. */
	private void given(String typedValue, String storedValue)
	{
		when(config.unlockedChunks()).thenReturn(typedValue);
		when(configManager.getConfiguration(eq("chunkblazer"), eq(KEY))).thenReturn(storedValue);
	}

	private String captureWrite()
	{
		ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
		verify(configManager).setConfiguration(eq("chunkblazer"), eq(KEY), written.capture());
		return String.valueOf(written.getValue());
	}

	private void assertNoWrite()
	{
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq(KEY), any());
	}

	/**
	 * THE incident. Key unset by the account-switch clear, server had nothing to
	 * merge, accessor still answers "12850" off the default. Must write anyway.
	 */
	@Test
	void absentKeyIsSeededEvenThoughTheAccessorClaimsLumbridge() throws Exception
	{
		given(String.valueOf(LUMBRIDGE), null);

		plugin.ensureStartingChunkUnlocked();

		assertEquals(String.valueOf(LUMBRIDGE), captureWrite(),
			"an absent key must be persisted, or the GPU addon greys the whole world");
	}

	/** A blank stored value is as invisible to a raw reader as a missing one. */
	@Test
	void blankStoredValueIsAlsoSeeded() throws Exception
	{
		given(String.valueOf(LUMBRIDGE), "   ");

		plugin.ensureStartingChunkUnlocked();

		assertEquals(String.valueOf(LUMBRIDGE), captureWrite());
	}

	/** Already on disk — this runs on every loadActiveTasks, so it must not churn config. */
	@Test
	void alreadyPersistedStartRegionIsNotRewritten() throws Exception
	{
		given(String.valueOf(LUMBRIDGE), String.valueOf(LUMBRIDGE));

		plugin.ensureStartingChunkUnlocked();

		assertNoWrite();
	}

	/** Idempotence with real progress present: seeding must never clobber unlocks. */
	@Test
	void existingUnlocksAreLeftAlone() throws Exception
	{
		given("12850,12851,12595", "12850,12851,12595");

		plugin.ensureStartingChunkUnlocked();

		assertNoWrite();
	}

	/**
	 * A stored set that genuinely lacks the start region still gets it appended,
	 * and keeps everything already there. This is the original contract.
	 *
	 * <p>No raw-read stub: when the typed value is already missing the start
	 * region the first branch decides it, and the persisted-check is never
	 * consulted. Strict stubbing enforces that short-circuit.
	 */
	@Test
	void missingStartRegionIsAppendedWithoutLosingOthers() throws Exception
	{
		when(config.unlockedChunks()).thenReturn("12851,12595");

		plugin.ensureStartingChunkUnlocked();

		String written = captureWrite();
		assertTrue(written.contains(String.valueOf(LUMBRIDGE)), "start region must be added: " + written);
		assertTrue(written.contains("12851"), "existing unlock lost: " + written);
		assertTrue(written.contains("12595"), "existing unlock lost: " + written);
	}
}
