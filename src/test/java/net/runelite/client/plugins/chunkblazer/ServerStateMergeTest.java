package net.runelite.client.plugins.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.chunkblazer.api.PlayerLoginResponse;
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
 * Recovering an account's progress from the server.
 *
 * <p>The design intent is that a reinstall, a lost RuneLite profile, or a new
 * machine must NOT cost progress — the server record is the recovery source.
 * The original "hydrate only when local is empty" rule could never deliver
 * that, because the plugin bootstraps local state before any login response
 * arrives: {@code ensureStartingChunkUnlocked()} writes the Lumbridge region
 * from {@code startUp()} and from every {@code loadActiveTasks()}, and the
 * Global Tasks backfill writes the already-finished quests. Local was never
 * empty, hydration always skipped, and the account was left with the bootstrap
 * alone — 1 chunk instead of 15, observed on 2026-07-21.
 *
 * <p>A union fixes it independently of ordering, which is the point: three
 * separate bugs this session came from assuming startup order.
 */
@ExtendWith(MockitoExtension.class)
class ServerStateMergeTest
{
	// SeaShantyBoy's real server record when a clean profile reduced him to one.
	private static final List<Integer> SERVER_CHUNKS = Arrays.asList(
		12084, 12083, 12340, 12341, 12085, 12339, 11827, 11828,
		12850, 12597, 12598, 12596, 12852, 12853, 11829);

	private static final int LUMBRIDGE = 12850;

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

	private void mergeRegions(PlayerLoginResponse.PlayerData pdata) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod(
			"mergeUnlockedRegionsFromServer", PlayerLoginResponse.PlayerData.class);
		m.setAccessible(true);
		m.invoke(plugin, pdata);
	}

	private void mergeTasks(PlayerLoginResponse.PlayerData pdata) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod(
			"mergeCompletedTasksFromServer", PlayerLoginResponse.PlayerData.class);
		m.setAccessible(true);
		m.invoke(plugin, pdata);
	}

	private PlayerLoginResponse.PlayerData pdata(List<Integer> regions, List<String> tasks)
	{
		PlayerLoginResponse.PlayerData d = new PlayerLoginResponse.PlayerData();
		d.setUnlockedRegions(regions);
		d.setCompletedTasks(tasks);
		return d;
	}

	private Set<String> capturedCsv(String key)
	{
		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq("chunkblazer"), eq(key), written.capture());
		return new LinkedHashSet<>(Arrays.asList(written.getValue().split(",")));
	}

	/**
	 * THE incident: a clean profile has bootstrapped Lumbridge only. The old
	 * rule saw non-empty local and skipped, leaving 1 chunk; the destructive
	 * sync then pushed that over the server's 15.
	 */
	@Test
	void bootstrapOnlyProfileRecoversEveryChunkFromTheServer() throws Exception
	{
		when(config.unlockedChunks()).thenReturn(String.valueOf(LUMBRIDGE));

		mergeRegions(pdata(SERVER_CHUNKS, null));

		Set<String> merged = capturedCsv("unlockedChunks");
		assertEquals(15, merged.size(), "all 15 server chunks must come back, not just the bootstrap");
		for (Integer region : SERVER_CHUNKS)
		{
			assertTrue(merged.contains(String.valueOf(region)), "missing region " + region);
		}
	}

	/** A true reinstall: nothing local at all. */
	@Test
	void emptyProfileRecoversEveryChunkFromTheServer() throws Exception
	{
		when(config.unlockedChunks()).thenReturn("");

		mergeRegions(pdata(SERVER_CHUNKS, null));

		assertEquals(15, capturedCsv("unlockedChunks").size());
	}

	/**
	 * The other direction, and why this is a union rather than "server wins":
	 * a chunk unlocked offline (or after a failed sync) must survive the login
	 * that restores the server's chunks.
	 */
	@Test
	void localOnlyProgressIsNotLostByTheMerge() throws Exception
	{
		when(config.unlockedChunks()).thenReturn(LUMBRIDGE + ",99999");

		mergeRegions(pdata(SERVER_CHUNKS, null));

		Set<String> merged = capturedCsv("unlockedChunks");
		assertTrue(merged.contains("99999"), "a locally-unlocked chunk must survive the merge");
		assertEquals(16, merged.size(), "15 server chunks plus the local-only one");
	}

	/** No difference means no config write — merging must be idempotent. */
	@Test
	void anAlreadyCompleteLocalStateIsNotRewritten() throws Exception
	{
		StringBuilder all = new StringBuilder();
		for (Integer region : SERVER_CHUNKS)
		{
			if (all.length() > 0)
			{
				all.append(',');
			}
			all.append(region);
		}
		when(config.unlockedChunks()).thenReturn(all.toString());

		mergeRegions(pdata(SERVER_CHUNKS, null));

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("unlockedChunks"), any());
	}

	@Test
	void completedTasksAreUnionedNotReplaced() throws Exception
	{
		// Local holds the quest backfill; the server also knows the chunk tasks.
		when(config.completedTasks()).thenReturn("quest_cooks_assistant,defeat_mugger");

		mergeTasks(pdata(null, Arrays.asList(
			"quest_cooks_assistant", "mine_iron_ore", "burn_oak_logs")));

		Set<String> merged = capturedCsv("completedTasks");
		assertTrue(merged.contains("defeat_mugger"), "local-only task must survive");
		assertTrue(merged.contains("mine_iron_ore"), "server-only task must be restored");
		assertEquals(4, merged.size(), "union, with the shared id counted once");
	}

	@Test
	void anEmptyServerRecordChangesNothing() throws Exception
	{
		mergeRegions(pdata(Collections.emptyList(), null));
		mergeTasks(pdata(null, Collections.emptyList()));

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("unlockedChunks"), any());
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("completedTasks"), any());
	}

	/**
	 * The safety interlock. Sync is destructive and client-authoritative, so it
	 * must refuse to run until the server's record has been merged — otherwise
	 * a periodic sync racing the login response pushes bootstrap-only state.
	 */
	@Test
	void syncIsBlockedUntilTheServerStateHasBeenMerged() throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField("serverStateMerged");
		f.setAccessible(true);

		assertFalse((Boolean) f.get(plugin),
			"a fresh session must start un-merged so no sync can fire before login is processed");
	}
}
