package net.runelite.client.plugins.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
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
 * The points model: EARNED is derived, SPENT is tracked, BALANCE is the
 * difference.
 *
 * <p>These exist because of a live incident (2026-07-21, account "Cruk"). The
 * client's {@code totalPoints} is a spendable BALANCE — unlocking a chunk
 * decrements it, and it gates affordability. The server's {@code total_points}
 * is a different quantity: lifetime EARNED, recomputed from the completed task
 * list with no notion of spending. Login hydration wrote the latter into the
 * former, erasing every chunk purchase the player had made and handing them 939
 * phantom points. It could not self-correct, because the server holds no
 * balance to correct it from.
 *
 * <p>Real numbers from that account are used below deliberately: 1386 earned
 * over 208 tasks, 939 spent on 66 paid chunks, 447 actually spendable.
 */
@ExtendWith(MockitoExtension.class)
class PointsBalanceTest
{
	private static final int CRUK_EARNED = 1386;
	private static final int CRUK_SPENT = 939;
	private static final int CRUK_BALANCE = 447;

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

	private Object invoke(String method, Class<?>[] types, Object... args) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod(method, types);
		m.setAccessible(true);
		return m.invoke(plugin, args);
	}

	/**
	 * Seed the task index so {@code computeEarnedPoints()} has base_points to sum,
	 * and point completedTasks at those ids.
	 */
	@SuppressWarnings("unchecked")
	private void seedEarned(int totalPoints) throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField("tasksById");
		f.setAccessible(true);
		Map<String, NuzlockeTask> index = (Map<String, NuzlockeTask>) f.get(plugin);
		index.clear();

		StringBuilder ids = new StringBuilder();
		for (int i = 0; i < totalPoints; i++)
		{
			// One point per task keeps the arithmetic obvious.
			String id = "task_" + i;
			NuzlockeTask t = new NuzlockeTask();
			t.setTaskId(id);
			t.setBasePoints(1);
			index.put(id, t);
			if (ids.length() > 0)
			{
				ids.append(',');
			}
			ids.append(id);
		}
		lenient().when(config.completedTasks()).thenReturn(ids.toString());
	}

	private int capturedInt(String key)
	{
		ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
		verify(configManager).setConfiguration(eq("chunkblazer"), eq(key), written.capture());
		return ((Number) written.getValue()).intValue();
	}

	// --- Derived balance --------------------------------------------------

	@Test
	void balanceIsEarnedMinusSpent() throws Exception
	{
		seedEarned(CRUK_EARNED);
		when(config.pointsSpent()).thenReturn(CRUK_SPENT);
		when(config.totalPoints()).thenReturn(0);

		invoke("recomputePointsBalance", new Class<?>[]{});

		assertEquals(CRUK_BALANCE, capturedInt("totalPoints"),
			"1386 earned minus 939 spent is 447 spendable");
	}

	/**
	 * THE incident. The server's EARNED total had been written into the balance,
	 * inflating it by exactly what the player had spent. A recompute must pull it
	 * back down — the old max(local, server) rule could only ever ratchet up.
	 */
	@Test
	void anInflatedBalanceIsCorrectedDownward() throws Exception
	{
		seedEarned(CRUK_EARNED);
		when(config.pointsSpent()).thenReturn(CRUK_SPENT);
		when(config.totalPoints()).thenReturn(CRUK_EARNED); // the bug's output

		invoke("recomputePointsBalance", new Class<?>[]{});

		assertEquals(CRUK_BALANCE, capturedInt("totalPoints"),
			"an inflated balance must come back down, not persist");
	}

	@Test
	void balanceNeverGoesNegative() throws Exception
	{
		seedEarned(10);
		when(config.pointsSpent()).thenReturn(999);
		when(config.totalPoints()).thenReturn(5);

		invoke("recomputePointsBalance", new Class<?>[]{});

		assertEquals(0, capturedInt("totalPoints"));
	}

	@Test
	void anAlreadyCorrectBalanceIsNotRewritten() throws Exception
	{
		seedEarned(CRUK_EARNED);
		when(config.pointsSpent()).thenReturn(CRUK_SPENT);
		when(config.totalPoints()).thenReturn(CRUK_BALANCE);

		invoke("recomputePointsBalance", new Class<?>[]{});

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("totalPoints"), any());
	}

	// --- Spending ---------------------------------------------------------

	@Test
	void unlockingAChunkRecordsSpendRatherThanEditingTheBalance() throws Exception
	{
		seedEarned(100);
		when(config.pointsSpent()).thenReturn(10);
		lenient().when(config.totalPoints()).thenReturn(90);

		invoke("recordPointsSpent", new Class<?>[]{ int.class }, 15);

		ArgumentCaptor<Object> spent = ArgumentCaptor.forClass(Object.class);
		verify(configManager).setConfiguration(eq("chunkblazer"), eq("pointsSpent"), spent.capture());
		assertEquals(25, ((Number) spent.getValue()).intValue(), "spend accumulates");
	}

	@Test
	void freeUnlocksRecordNoSpend() throws Exception
	{
		invoke("recordPointsSpent", new Class<?>[]{ int.class }, 0);

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("pointsSpent"), any());
	}

	// --- One-time derivation for pre-existing accounts ---------------------

	/**
	 * An account from before the counter existed already has a balance that
	 * reflects everything it ever spent, so the spend recovers exactly — no
	 * guessing which chunks were granted free.
	 */
	@Test
	void spendIsDerivedFromAnExistingBalance() throws Exception
	{
		seedEarned(CRUK_EARNED);
		when(config.pointsSpent()).thenReturn(0);
		when(config.totalPoints()).thenReturn(CRUK_BALANCE);

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		assertEquals(CRUK_SPENT, capturedInt("pointsSpent"),
			"1386 earned with 447 spendable means 939 was spent");
	}

	@Test
	void derivationIsSkippedOnceSpendIsKnown() throws Exception
	{
		when(config.pointsSpent()).thenReturn(500);

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("pointsSpent"), any());
	}

	/**
	 * On a profile whose balance was inflated by the bug, balance >= earned, so
	 * there is nothing trustworthy to derive from. Recording 0 is correct: the
	 * real figure then arrives from the server, which holds the higher monotonic
	 * value.
	 */
	@Test
	void derivationRefusesWhenTheBalanceIsNotTrustworthy() throws Exception
	{
		seedEarned(CRUK_EARNED);
		when(config.pointsSpent()).thenReturn(0);
		when(config.totalPoints()).thenReturn(CRUK_EARNED);

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("pointsSpent"), any());
	}

	@Test
	void derivationIsSkippedForAnAccountThatHasEarnedNothing() throws Exception
	{
		seedEarned(0);
		when(config.pointsSpent()).thenReturn(0);
		lenient().when(config.totalPoints()).thenReturn(0);

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("pointsSpent"), any());
	}

	// --- Catalog drift ----------------------------------------------------

	/**
	 * Unknown ids are skipped, exactly as the server's Recompute does, so a
	 * plugin/server catalog difference moves both sides identically instead of
	 * silently diverging the balance.
	 */
	@Test
	void unknownTaskIdsAreIgnoredWhenSummingEarned() throws Exception
	{
		seedEarned(10);
		when(config.completedTasks()).thenReturn("task_0,task_1,not_in_catalog");
		when(config.pointsSpent()).thenReturn(0);
		when(config.totalPoints()).thenReturn(0);

		invoke("recomputePointsBalance", new Class<?>[]{});

		assertEquals(2, capturedInt("totalPoints"), "only the two known tasks count");
	}
}
