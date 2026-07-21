package net.runelite.client.plugins.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the Progression baseline — the mechanism that makes
 * Progression non-retroactive.
 *
 * <p>These exist because of a real incident (2026-07-21): the baseline was
 * captured on GameState.LOGGED_IN, which fires BEFORE the client's skill table
 * is populated, so it froze every skill at 0. A zero baseline makes every rung
 * "above baseline", and the already-reached backfill then completed all 239 of
 * them — handing a maxed account the entire 671-point ladder retroactively.
 *
 * <p>The invariant under test: a baseline must NEVER be frozen from skill data
 * that isn't loaded, because freezing is permanent.
 */
@ExtendWith(MockitoExtension.class)
class ProgressionBaselineTest
{
	@Mock
	private ChunkBlazerConfig config;

	@Mock
	private ConfigManager configManager;

	@Mock
	private Client client;

	private ChunkBlazerPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);
		setField(plugin, "client", client);
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

	private boolean isSkillDataReady() throws Exception
	{
		return (Boolean) invoke("isSkillDataReady", new Class<?>[]{});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> ensureBaseline() throws Exception
	{
		return (Map<String, Integer>) invoke("ensureProgressionBaseline", new Class<?>[]{});
	}

	// --- The race itself --------------------------------------------------

	/**
	 * The exact shape of the incident: logged in, but the skill table still
	 * reads 0 across the board. Hitpoints below 10 is impossible for a real
	 * account, so it's the tell that the data isn't there yet.
	 */
	@Test
	void skillDataIsNotReadyWhenHitpointsReadsZero()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(0);

		assertDoesNotThrow(() -> assertFalse(isSkillDataReady(),
			"a 0 Hitpoints reading means the skill table hasn't loaded"));
	}

	@Test
	void skillDataIsNotReadyWhenNotLoggedIn()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		assertDoesNotThrow(() -> assertFalse(isSkillDataReady()));
	}

	@Test
	void skillDataIsReadyAtTenHitpoints()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(10);

		assertDoesNotThrow(() -> assertTrue(isSkillDataReady(),
			"a freshly created account sits at exactly 10 Hitpoints and is valid"));
	}

	/**
	 * THE regression. Before the fix this wrote "ATTACK:0,...,HITPOINTS:0,..."
	 * to config and froze it forever.
	 */
	@Test
	void baselineIsNotCapturedFromUnloadedSkillData() throws Exception
	{
		when(config.progressionBaseline()).thenReturn("");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(0);

		Map<String, Integer> baseline = ensureBaseline();

		assertTrue(baseline.isEmpty(), "no baseline may be derived from an unloaded skill table");
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("progressionBaseline"), any());
	}

	@Test
	void baselineIsCapturedOnceSkillDataIsLoaded() throws Exception
	{
		when(config.progressionBaseline()).thenReturn("");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getRealSkillLevel(any(Skill.class))).thenReturn(70);
		when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(75);

		Map<String, Integer> baseline = ensureBaseline();

		assertEquals(75, baseline.get("HITPOINTS"));
		assertEquals(70, baseline.get("THIEVING"));

		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq("chunkblazer"), eq("progressionBaseline"), written.capture());
		assertTrue(written.getValue().contains("HITPOINTS:75"), written.getValue());
	}

	@Test
	void anExistingBaselineIsNeverRecaptured() throws Exception
	{
		when(config.progressionBaseline()).thenReturn("HITPOINTS:42,THIEVING:31");

		Map<String, Integer> baseline = ensureBaseline();

		assertEquals(42, baseline.get("HITPOINTS"));
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("progressionBaseline"), any());
		verify(client, never()).getRealSkillLevel(any(Skill.class));
	}

	// --- Eligibility ------------------------------------------------------

	private boolean eligible(String skill, int rungLevel, Map<String, Integer> baseline) throws Exception
	{
		NuzlockeTask task = new NuzlockeTask();
		TaskConstraints c = new TaskConstraints();
		c.setRequiredSkill(skill);
		c.setRequiredLevel(rungLevel);
		task.setConstraints(c);
		return (Boolean) invoke("isProgressionRungEligible",
			new Class<?>[]{ NuzlockeTask.class, Map.class }, task, baseline);
	}

	@Test
	void rungsAtOrBelowBaselineAreNotEligible() throws Exception
	{
		Map<String, Integer> baseline = new HashMap<>();
		baseline.put("THIEVING", 80);

		assertFalse(eligible("THIEVING", 10, baseline), "already cleared long ago");
		assertFalse(eligible("THIEVING", 80, baseline), "exactly at baseline — already cleared");
		assertTrue(eligible("THIEVING", 90, baseline), "above baseline — still to earn");
		assertTrue(eligible("THIEVING", 99, baseline));
	}

	/**
	 * With the zero baseline the incident produced, EVERY rung read as eligible.
	 * Pinning it so the blast radius of a bad baseline is visible in tests.
	 */
	@Test
	void aZeroBaselineWouldMakeEveryRungEligible() throws Exception
	{
		Map<String, Integer> zeroed = new HashMap<>();
		zeroed.put("THIEVING", 0);

		assertTrue(eligible("THIEVING", 10, zeroed),
			"documents WHY a zero baseline is catastrophic — it must never be frozen");
	}

	@Test
	void aSkillMissingFromTheBaselineIsNotEligible() throws Exception
	{
		assertFalse(eligible("THIEVING", 30, new HashMap<>()),
			"an uncaptured baseline must refuse to pay, not pay by default");
	}

	// --- Self-healing repair ----------------------------------------------

	@Test
	void repairClearsABogusZeroBaselineAndUncompletesProgressionTasks()
	{
		when(config.progressionBaseline()).thenReturn("HITPOINTS:0,THIEVING:0");
		when(config.completedTasks()).thenReturn(
			"progression_thieving_10,defeat_mugger,progression_attack_20");
		when(config.totalPoints()).thenReturn(100);

		plugin.migrateRepairBogusProgressionBaseline();

		verify(configManager).setConfiguration("chunkblazer", "progressionBaseline", "");

		ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
		verify(configManager).setConfiguration(eq("chunkblazer"), eq("completedTasks"), written.capture());
		assertEquals("defeat_mugger", written.getValue(),
			"every progression_* id must be dropped, non-progression tasks kept");
	}

	@Test
	void repairIsANoOpForASaneBaseline()
	{
		when(config.progressionBaseline()).thenReturn("HITPOINTS:99,THIEVING:80");

		plugin.migrateRepairBogusProgressionBaseline();

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("progressionBaseline"), any());
		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("completedTasks"), any());
	}

	@Test
	void repairIsANoOpWhenNoBaselineExists()
	{
		when(config.progressionBaseline()).thenReturn("");

		plugin.migrateRepairBogusProgressionBaseline();

		verify(configManager, never()).setConfiguration(eq("chunkblazer"), eq("progressionBaseline"), any());
	}
}
