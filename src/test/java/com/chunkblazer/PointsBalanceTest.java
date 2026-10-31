package com.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Points model: earned is derived from completed tasks, spent is tracked, and the
 * balance is earned minus spent. Numbers are Cruk's (2026-07-21): 1386 earned,
 * 939 spent, 447 spendable.
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
	private Map<String, String> writes;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);
		// Per-account state, backed by a map that falls back to the config stubs.
		writes = RsProfileTestSupport.install(configManager, config);
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

	/** Seeds the task index with 1-point tasks and marks them all completed. */
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
		assertTrue(writes.containsKey(key), "expected a per-account write to '" + key + "'");
		return Integer.parseInt(writes.get(key));
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

	/** The server's earned total was written into the balance; a recompute brings it back down. */
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

		assertNoWriteTo("totalPoints");
	}

	// --- Spending ---------------------------------------------------------

	@Test
	void chunkUnlockRecordsSpend() throws Exception
	{
		seedEarned(100);
		when(config.pointsSpent()).thenReturn(10);
		lenient().when(config.totalPoints()).thenReturn(90);

		invoke("recordPointsSpent", new Class<?>[]{ int.class }, 15);

		assertEquals(25, capturedInt("pointsSpent"), "spend accumulates");
	}

	@Test
	void freeUnlocksRecordNoSpend() throws Exception
	{
		invoke("recordPointsSpent", new Class<?>[]{ int.class }, 0);

		assertNoWriteTo("pointsSpent");
	}

	// --- One-time derivation for pre-existing accounts ---------------------

	/** A pre-counter account's stored balance gives its spend exactly. */
	@Test
	void spendIsDerivedFromAnExistingBalance() throws Exception
	{
		seedEarned(CRUK_EARNED);
		when(config.pointsSpent()).thenReturn(0);
		balanceStoredAs(String.valueOf(CRUK_BALANCE));

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		assertEquals(CRUK_SPENT, capturedInt("pointsSpent"),
			"1386 earned with 447 spendable means 939 was spent");
	}

	@Test
	void derivationIsSkippedOnceSpendIsKnown() throws Exception
	{
		when(config.pointsSpent()).thenReturn(500);

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		assertNoWriteTo("pointsSpent");
	}

	/** A balance at or above earned can't be trusted; the server supplies the real spend. */
	@Test
	void derivationRefusesUntrustedBalance() throws Exception
	{
		seedEarned(CRUK_EARNED);
		when(config.pointsSpent()).thenReturn(0);
		balanceStoredAs(String.valueOf(CRUK_EARNED));

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		assertNoWriteTo("pointsSpent");
	}

	@Test
	void derivationSkippedWithNoEarnings() throws Exception
	{
		seedEarned(0);
		when(config.pointsSpent()).thenReturn(0);
		lenient().when(config.totalPoints()).thenReturn(0);

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		assertNoWriteTo("pointsSpent");
	}

	// --- Catalog drift ----------------------------------------------------

	/** Unknown ids are skipped, as the server's Recompute does. */
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

	// ---- Account switching (2026-08-01) ------------------------------------
	// A cleared balance reads as the default 0, which must not count as spending.

	private static final int SWITCH_EARNED = 414;   // Cruk, after the server merge
	private static final int SWITCH_SERVER_SPENT = 453;

	private void balanceStoredAs(String raw)
	{
		// null or blank means nothing stored
		if (raw == null || raw.trim().isEmpty())
		{
			writes.remove("totalPoints");
		}
		else
		{
			writes.put("totalPoints", raw);
		}
	}

	private void ownsChunks(int count) throws Exception
	{
		StringBuilder ids = new StringBuilder();
		for (int i = 0; i < count; i++)
		{
			if (ids.length() > 0)
			{
				ids.append(',');
			}
			ids.append(12800 + i);
			paidChunk(12800 + i);
		}
		lenient().when(config.unlockedChunks()).thenReturn(ids.toString());
	}

	/** The granted start chunk (12850, never sold) plus {@code paid} paid chunks. */
	private void ownsStartChunkPlusPaid(int paid) throws Exception
	{
		StringBuilder ids = new StringBuilder("12850"); // DEFAULT_START_REGION
		for (int i = 0; i < paid; i++)
		{
			ids.append(',').append(12800 + i);
			paidChunk(12800 + i);
		}
		lenient().when(config.unlockedChunks()).thenReturn(ids.toString());
	}

	/** A default-cost chunk, so it counts toward the paid rank. */
	@SuppressWarnings("unchecked")
	private void paidChunk(int regionId) throws Exception
	{
		NuzlockeChunk c = new NuzlockeChunk();
		setField(c, "regionIds", java.util.Arrays.asList(regionId));
		Field f = ChunkBlazerPlugin.class.getDeclaredField("chunksByRegionId");
		f.setAccessible(true);
		((Map<Integer, NuzlockeChunk>) f.get(plugin)).put(regionId, c);
	}

	/** No per-account write to this key. */
	private void assertNoWriteTo(String key)
	{
		assertFalse(writes.containsKey(key), "expected no per-account write to '" + key + "'");
	}

	/** After a switch the balance is absent; deriving from its default 0 charged Cruk all 414 points. */
	@Test
	void accountSwitchDoesNotChargeLifetimeEarnings() throws Exception
	{
		seedEarned(SWITCH_EARNED);
		when(config.pointsSpent()).thenReturn(0);
		balanceStoredAs(null);          // clearAccountState() unset it

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		assertNoWriteTo("pointsSpent");
	}

	/** A blank stored value is as absent as a missing one. */
	@Test
	void blankStoredBalanceAlsoSkipsDerivation() throws Exception
	{
		seedEarned(SWITCH_EARNED);
		when(config.pointsSpent()).thenReturn(0);
		balanceStoredAs("   ");

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		assertNoWriteTo("pointsSpent");
	}

	/** A legacy account with a real stored balance still derives its spend. */
	@Test
	void legacyAccountWithARealBalanceStillDerives() throws Exception
	{
		seedEarned(SWITCH_EARNED);
		when(config.pointsSpent()).thenReturn(0);
		balanceStoredAs("100");         // genuinely stored, not a default

		invoke("deriveInitialPointsSpent", new Class<?>[]{});

		assertEquals(314, capturedInt("pointsSpent"), "earned 414 - balance 100");
	}

	/** Spent 453 against 414 earned is impossible; it's rebuilt from the chunks owned. */
	@Test
	void impossibleSpendIsRebuiltFromChunksOwned() throws Exception
	{
		seedEarned(SWITCH_EARNED);
		when(config.pointsSpent()).thenReturn(SWITCH_SERVER_SPENT);
		// 39 paid chunks cost at most the legacy curve's sum (no refund for the price cut).
		ownsStartChunkPlusPaid(39);

		invoke("migrateRepairImpossiblePointsSpent", new Class<?>[]{});

		int expected = ChunkBlazerPlugin.legacyCurveLedgerTotal(39); // 109
		assertEquals(expected, capturedInt("pointsSpent"),
			"spend must be rebuilt from the curve cost of the chunks actually owned");
	}

	/** Spend under the owned-chunk ledger is left alone. */
	@Test
	void repairLeavesAHealthyAccountAlone() throws Exception
	{
		seedEarned(SWITCH_EARNED);
		when(config.pointsSpent()).thenReturn(40);   // under the ledger
		ownsChunks(40);

		invoke("migrateRepairImpossiblePointsSpent", new Class<?>[]{});

		assertNoWriteTo("pointsSpent");
	}

	/** Spend under earned but over the owned-chunk ledger is still repaired (Bobby Blazer: 23 earned, 22 spent, 3 chunks). */
	@Test
	void impossibleSpendBelowEarnedIsStillRepaired() throws Exception
	{
		seedEarned(23);
		when(config.pointsSpent()).thenReturn(22);
		ownsStartChunkPlusPaid(3);

		invoke("migrateRepairImpossiblePointsSpent", new Class<?>[]{});

		int expected = ChunkBlazerPlugin.legacyCurveLedgerTotal(3);
		assertEquals(expected, capturedInt("pointsSpent"),
			"spend above the owned-chunk ledger is repaired even when it is under lifetime earned");
	}

	/** The repair only ever lowers spend. */
	@Test
	void repairNeverRaisesTheSpendCounter() throws Exception
	{
		seedEarned(10);
		when(config.pointsSpent()).thenReturn(12);   // impossible, but...
		ownsChunks(80);                              // ...ledger is far higher

		invoke("migrateRepairImpossiblePointsSpent", new Class<?>[]{});

		assertNoWriteTo("pointsSpent");
	}

	/** In login merge order, a switched-in account ends with a spendable balance, not zero. */
	@Test
	void switchedAccountEndsWithASpendableBalance() throws Exception
	{
		seedEarned(SWITCH_EARNED);          // earned 414
		balanceStoredAs(null);              // freshly switched in
		ownsChunks(40);

		// Unknown balance: nothing derived.
		invoke("deriveInitialPointsSpent", new Class<?>[]{});
		assertNoWriteTo("pointsSpent");

		// The server's inflated spend arrives through the login merge.
		writes.put("pointsSpent", String.valueOf(SWITCH_SERVER_SPENT)); // 453

		// The repair lowers it to the owned-chunk ledger.
		invoke("migrateRepairImpossiblePointsSpent", new Class<?>[]{});
		int repaired = ChunkBlazerPlugin.legacyCurveLedgerTotal(40);
		assertEquals(repaired, capturedInt("pointsSpent"),
			"spend rebuilt from the curve cost of owned chunks, not the corrupt counter");

		invoke("recomputePointsBalance", new Class<?>[]{});
		assertEquals(SWITCH_EARNED - repaired, capturedInt("totalPoints"),
			"earned 414 minus the repaired curve spend; the old code pinned this at 0");
	}
}
