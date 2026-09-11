package com.chunkblazer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * The one-off heal for Bobby Blazer's rerolled tasks: rebuild each owned NON-boss region's
 * rolled set to exactly the tasks he has completed there, gated to his account and run once.
 */
@ExtendWith(MockitoExtension.class)
class BobbyRollHealTest
{
	@Mock
	private ChunkBlazerConfig config;
	@Mock
	private ConfigManager configManager;
	@Mock
	private Client client;

	private ChunkBlazerPlugin plugin;
	private Map<String, String> rsProfile;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		setField(plugin, "config", config);
		setField(plugin, "configManager", configManager);
		setField(plugin, "client", client);
		rsProfile = RsProfileTestSupport.install(configManager, config);
	}

	@Test
	void rebuildsEachOwnedRegionRollToHisCompletedTasks() throws Exception
	{
		loggedInAs("Bobby Blazer");
		// Region 100 has 4 tasks; he completed A and B, but the reroll left C,D (un-done).
		chunk(100, false, "task_A", "task_B", "task_C", "task_D");
		owns("100");
		completed("task_A", "task_B");
		currentRoll("100:task_C,task_D");

		heal();

		assertEquals(Set.of("task_A", "task_B"), rolledFor(100),
			"the roll is rebuilt to his completed tasks; the reroll's new tasks are dropped");
		assertEquals("true", rsProfile.get(bobbyKey()), "the fix marks itself done");
	}

	@Test
	void aBossRegionIsLeftAlone() throws Exception
	{
		loggedInAs("Bobby Blazer");
		// A boss chunk grants every task; rebuilding it to completed-only would hide the rest.
		chunk(200, true, "boss_defeat", "boss_pet", "boss_ca");
		owns("200");
		completed("boss_defeat");
		currentRoll("200:boss_defeat,boss_pet,boss_ca");

		heal();

		assertEquals(Set.of("boss_defeat", "boss_pet", "boss_ca"), rolledFor(200),
			"a boss chunk keeps its full granted stack");
	}

	@Test
	void anotherAccountIsNeverTouchedAndTheFixStaysArmed() throws Exception
	{
		loggedInAs("Someone Else");
		chunk(100, false, "task_A", "task_B", "task_C");
		owns("100");
		completed("task_A");
		currentRoll("100:task_B,task_C");

		heal();

		assertEquals(Set.of("task_B", "task_C"), rolledFor(100), "a different account is untouched");
		assertFalse(rsProfile.containsKey(bobbyKey()), "the fix must stay armed for the real account");
	}

	@Test
	void itRunsOnlyOnce() throws Exception
	{
		loggedInAs("Bobby Blazer");
		rsProfile.put(bobbyKey(), "true"); // already applied
		chunk(100, false, "task_A", "task_B");
		owns("100");
		completed("task_A");
		currentRoll("100:task_B");

		heal();

		assertEquals(Set.of("task_B"), rolledFor(100), "an already-applied fix does nothing");
	}

	// --- helpers -----------------------------------------------------------

	private void heal() throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("migrateHealBobbyRerolledTasks");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	@SuppressWarnings("unchecked")
	private Set<String> rolledFor(int regionId) throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("getRolledTasksForRegion", int.class);
		m.setAccessible(true);
		return (Set<String>) m.invoke(plugin, regionId);
	}

	private String bobbyKey() throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField("BOBBY_ROLL_HEAL_KEY");
		f.setAccessible(true);
		return (String) f.get(null);
	}

	@SuppressWarnings("unchecked")
	private void chunk(int regionId, boolean boss, String... taskIds) throws Exception
	{
		NuzlockeChunk c = new NuzlockeChunk();
		setField(c, "chunkType", boss ? "BOSS" : "REGION");
		setField(c, "regionIds", Arrays.asList(regionId));
		List<NuzlockeTask> tasks = new java.util.ArrayList<>();
		for (String id : taskIds)
		{
			NuzlockeTask t = new NuzlockeTask();
			t.setTaskId(id);
			tasks.add(t);
		}
		setField(c, "tasks", tasks);

		Field f = ChunkBlazerPlugin.class.getDeclaredField("chunksByRegionId");
		f.setAccessible(true);
		((Map<Integer, NuzlockeChunk>) f.get(plugin)).put(regionId, c);
	}

	private void owns(String csv)
	{
		lenient().when(config.unlockedChunks()).thenReturn(csv);
	}

	private void completed(String... ids)
	{
		lenient().when(config.completedTasks()).thenReturn(String.join(",", ids));
	}

	private void currentRoll(String value)
	{
		lenient().when(config.regionRolledTasks()).thenReturn(value);
	}

	private void loggedInAs(String rsn)
	{
		Player p = org.mockito.Mockito.mock(Player.class);
		lenient().when(p.getName()).thenReturn(rsn);
		lenient().when(client.getLocalPlayer()).thenReturn(p);
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
