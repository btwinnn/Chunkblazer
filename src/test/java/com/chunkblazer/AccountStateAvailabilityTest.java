package com.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.runelite.client.config.ConfigManager;
import com.chunkblazer.modules.TaskModuleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Steps 1–2 of the RSProfile migration: knowing WHOSE account this is, before
 * writing anything that belongs to them.
 *
 * <p>Storage is still profile-scoped at this stage, so none of these tests are
 * about where data lands. They are about TIMING, which is the part that has to
 * be right before the store can move at all: RuneLite's
 * {@code setRSProfileConfiguration} silently discards writes issued while no
 * account is logged in, so any code path that writes progress before the RS
 * profile exists would lose that write outright once step 3 lands.
 *
 * <p>The three bugs on 2026-07-21 were all ordering bugs. These pin the order.
 */
@ExtendWith(MockitoExtension.class)
class AccountStateAvailabilityTest
{
	private static final String PROFILE_KEY = "1234abcd.STANDARD.Zezima";

	@Mock
	private ChunkBlazerConfig config;

	@Mock
	private ConfigManager configManager;

	// The gated path clears module state on its way out, so this has to exist.
	@Mock
	private TaskModuleManager taskModuleManager;

	private ChunkBlazerPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);
		setField(plugin, "taskModuleManager", taskModuleManager);
	}

	// --- isAccountStateAvailable ------------------------------------------

	@Test
	void stateIsUnavailableWhileNoAccountIsLoggedIn() throws Exception
	{
		when(configManager.getRSProfileKey()).thenReturn(null);
		assertFalse(available());
	}

	@Test
	void stateBecomesAvailableOnceRuneLiteKnowsTheAccount() throws Exception
	{
		when(configManager.getRSProfileKey()).thenReturn(PROFILE_KEY);
		assertTrue(available());
	}

	/**
	 * The logout edge, and the reason the destructive logout sync needs its own
	 * guard in step 3. rsProfileKey is driven by AccountHashChanged /
	 * WorldChanged, which are NOT ordered against our GameStateChanged handling,
	 * so it can already be null while we are still reacting to LOGIN_SCREEN.
	 */
	@Test
	void stateGoesUnavailableAgainOnLogout() throws Exception
	{
		when(configManager.getRSProfileKey()).thenReturn(PROFILE_KEY, (String) null);
		assertTrue(available());
		assertFalse(available());
	}

	// --- the accessors ----------------------------------------------------

	/**
	 * A read with no account must yield nothing — and, critically, the CALLER
	 * must not read that as "new account, seed it". Mistaking absence for
	 * emptiness is how a real player gets re-bootstrapped over.
	 */
	@Test
	void readsReturnNothingWhenNoAccountIsKnown() throws Exception
	{
		when(configManager.getConfiguration("chunkblazer", "unlockedChunks")).thenReturn(null);
		assertNull(getAccountState("unlockedChunks"));
	}

	/**
	 * Deliberate for THIS stage only: warn, but still write. Storage is
	 * profile-scoped so the write lands, and refusing here would lose data that
	 * currently survives. The warning is the deliverable — it names the pre-login
	 * writers in a real session's log so they can be gated before step 3 turns
	 * this into a refusal.
	 */
	@Test
	void writesStillLandWhenNoAccountIsKnown() throws Exception
	{
		when(configManager.getRSProfileKey()).thenReturn(null);

		setAccountState("totalPoints", 42);

		verify(configManager).setConfiguration("chunkblazer", "totalPoints", (Object) 42);
	}

	@Test
	void writesLandNormallyWhenTheAccountIsKnown() throws Exception
	{
		when(configManager.getRSProfileKey()).thenReturn(PROFILE_KEY);

		setAccountState("totalPoints", 42);

		verify(configManager).setConfiguration("chunkblazer", "totalPoints", (Object) 42);
	}

	// --- the bootstrap gate -----------------------------------------------

	/**
	 * THE headline behaviour of step 2. loadActiveTasks() both bootstraps
	 * (ensureStartingChunkUnlocked + two migrations) and rolls tasks; running it
	 * account-less writes progress belonging to nobody, which then gets
	 * attributed to whoever logs in next.
	 */
	@Test
	void loadActiveTasksWritesNothingWhileNoAccountIsKnown() throws Exception
	{
		when(configManager.getRSProfileKey()).thenReturn(null);

		loadActiveTasks();

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), anyString(), any());
	}

	/**
	 * It must not leave the previous account's tasks on screen either — and it
	 * must reach that state by RETURNING EARLY, not by falling through the normal
	 * path.
	 *
	 * <p>The distinction is not academic. Asserting only that activeTasks ends up
	 * empty passes with the gate deleted, because the normal path clears it too;
	 * that version of this test was verified to survive the mutation and was
	 * therefore guaranteeing nothing. Reading the charter-migration flag is the
	 * first config touch AFTER the gate, so its absence pins the early return
	 * specifically.
	 */
	@Test
	void loadActiveTasksReturnsEarlyAndDropsInMemoryStateWhileNoAccountIsKnown() throws Exception
	{
		when(configManager.getRSProfileKey()).thenReturn(null);

		List<NuzlockeTask> active = activeTasks();
		active.add(new NuzlockeTask());
		assertFalse(active.isEmpty());

		loadActiveTasks();

		verify(configManager, never()).getConfiguration("chunkblazer", "charterSeedStripped");
		verify(taskModuleManager).clearTask();
		verify(taskModuleManager, never()).registerActiveTask(any());

		assertTrue(activeTasks().isEmpty(),
			"a logout or account switch must not leave the previous account's tasks live");
		assertNull(getField(plugin, "activeTask"));
	}

	// --- reflection helpers ------------------------------------------------

	private boolean available() throws Exception
	{
		return (boolean) invoke("isAccountStateAvailable");
	}

	private String getAccountState(String key) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("getAccountState", String.class);
		m.setAccessible(true);
		return (String) m.invoke(plugin, key);
	}

	private void setAccountState(String key, Object value) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("setAccountState", String.class, Object.class);
		m.setAccessible(true);
		m.invoke(plugin, key, value);
	}

	private void loadActiveTasks() throws Exception
	{
		invoke("loadActiveTasks");
	}

	@SuppressWarnings("unchecked")
	private List<NuzlockeTask> activeTasks() throws Exception
	{
		return (List<NuzlockeTask>) getField(plugin, "activeTasks");
	}

	private Object invoke(String name) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod(name);
		m.setAccessible(true);
		return m.invoke(plugin);
	}

	private static Object getField(Object target, String name) throws Exception
	{
		Field f = field(target.getClass(), name);
		return f.get(target);
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		field(target.getClass(), name).set(target, value);
	}

	private static Field field(Class<?> c, String name) throws Exception
	{
		while (c != null)
		{
			try
			{
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			}
			catch (NoSuchFieldException ignored)
			{
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
