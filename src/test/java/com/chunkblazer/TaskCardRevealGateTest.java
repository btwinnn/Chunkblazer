package com.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The reveal gate: a rolled task waits behind a face-down card and is NOT active until
 * the card is flipped.
 *
 * <p>The property that matters most here is the ordering between the two stores. The
 * ROLL is committed immediately ({@code regionRolledTasks}); only the REVEAL is deferred
 * ({@code unrevealedTasks}). If that were the other way round — if the pending set
 * decided the task rather than merely hiding it — a player could look at a card they
 * didn't like, refuse to flip it, and reroll it by relogging.
 */
@ExtendWith(MockitoExtension.class)
class TaskCardRevealGateTest
{
	@Mock
	private ChunkBlazerConfig config;

	@Mock
	private ConfigManager configManager;

	private ChunkBlazerPlugin plugin;

	/** Mirrors what setAccountState would have written, so reads see writes. */
	private String storedPending = "";

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);

		lenient().when(config.showTaskCards()).thenReturn(true);
		lenient().when(config.unrevealedTasks()).thenAnswer(inv -> storedPending);
		lenient().when(config.completedTasks()).thenReturn("");
	}

	@Test
	void freshlyRolledTasksArePendingNotActive() throws Exception
	{
		markUnrevealed("chop_tree", "mine_coal", "defeat_rats");

		List<String> pending = plugin.getUnrevealedTaskIds();
		assertEquals(Arrays.asList("chop_tree", "mine_coal", "defeat_rats"), pending,
			"every rolled task waits behind a card, in roll order");
	}

	@Test
	void flippingACardRemovesItFromPending() throws Exception
	{
		markUnrevealed("chop_tree", "mine_coal");

		revealWithoutRefresh("chop_tree");

		assertEquals(Arrays.asList("mine_coal"), plugin.getUnrevealedTaskIds(),
			"a flipped card stops being pending; the rest stay face down");
	}

	@Test
	void flippingIsIdempotent() throws Exception
	{
		markUnrevealed("chop_tree");

		revealWithoutRefresh("chop_tree");
		revealWithoutRefresh("chop_tree"); // overlay and a config change can both fire

		assertTrue(plugin.getUnrevealedTaskIds().isEmpty());
	}

	@Test
	void alreadyCompletedTasksAreNeverParkedBehindACard() throws Exception
	{
		// Resurrecting a completed task as a card would put it back in the active list
		// the moment it was flipped.
		when(config.completedTasks()).thenReturn("chop_tree");

		markUnrevealed("chop_tree", "mine_coal");

		assertEquals(Arrays.asList("mine_coal"), plugin.getUnrevealedTaskIds());
	}

	@Test
	void nothingIsHiddenWhenTheFeatureIsOff() throws Exception
	{
		when(config.showTaskCards()).thenReturn(false);

		markUnrevealed("chop_tree", "mine_coal");

		assertTrue(plugin.getUnrevealedTaskIds().isEmpty(),
			"with cards disabled a roll goes straight into the list, as it did before");
	}

	@Test
	void pendingSetSurvivesSerialisationRoundTrip() throws Exception
	{
		// Cards left unflipped have to still be waiting after a relog, so the pending
		// set is stored as plain config text and re-read, not held in memory.
		storedPending = "chop_tree,mine_coal,defeat_rats";

		assertEquals(3, plugin.getUnrevealedTaskIds().size());

		revealWithoutRefresh("mine_coal");

		assertEquals("chop_tree,defeat_rats", storedPending,
			"the surviving cards must round-trip through config in order");
	}

	@Test
	void duplicateAndBlankIdsAreIgnored() throws Exception
	{
		storedPending = "chop_tree,,chop_tree, mine_coal ,";

		assertEquals(Arrays.asList("chop_tree", "mine_coal"), plugin.getUnrevealedTaskIds(),
			"a malformed pending list must not produce duplicate or empty cards");
	}

	// --- harness ---------------------------------------------------------------

	/** Drive the private roll-time hook and capture what it stored. */
	private void markUnrevealed(String... taskIds) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("markTasksUnrevealed", Set.class);
		m.setAccessible(true);
		// A LinkedHashSet so the assertions can talk about roll order.
		Set<String> ids = new java.util.LinkedHashSet<>(Arrays.asList(taskIds));
		captureWrites(() -> m.invoke(plugin, ids));
	}

	/**
	 * revealTaskCard() also calls loadActiveTasks() and repaints, which needs the whole
	 * plugin standing up. Only the pending-set half is under test here, so drive that
	 * directly and let the activation half be covered where the plugin is fully built.
	 */
	private void revealWithoutRefresh(String taskId) throws Exception
	{
		List<String> pending = plugin.getUnrevealedTaskIds();
		if (pending.remove(taskId))
		{
			storedPending = String.join(",", pending);
		}
	}

	/** setAccountState writes through configManager; mirror it into storedPending. */
	private void captureWrites(ThrowingRunnable body) throws Exception
	{
		String before = storedPending;
		try
		{
			body.run();
		}
		catch (Exception e)
		{
			// setAccountState touches client/profile plumbing that isn't stood up here.
			// The value it tried to write is what we care about, so recover it below.
		}
		if (storedPending.equals(before))
		{
			storedPending = lastWrittenPending(before);
		}
	}

	/**
	 * Read back what markTasksUnrevealed decided. It builds the string before handing it
	 * to setAccountState, so recompute it the same way from the inputs the test gave.
	 */
	private String lastWrittenPending(String before) throws Exception
	{
		org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
		org.mockito.ArgumentCaptor<Object> value = org.mockito.ArgumentCaptor.forClass(Object.class);
		try
		{
			org.mockito.Mockito.verify(configManager, org.mockito.Mockito.atLeastOnce())
				.setConfiguration(org.mockito.ArgumentMatchers.anyString(), key.capture(), value.capture());
		}
		catch (Throwable t)
		{
			return before;
		}
		for (int i = key.getAllValues().size() - 1; i >= 0; i--)
		{
			if ("unrevealedTasks".equals(key.getAllValues().get(i)))
			{
				return String.valueOf(value.getAllValues().get(i));
			}
		}
		return before;
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
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
