/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.chunkblazer.api.PlayerLoginResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Refill: a chunk keeps TASKS_PER_CHUNK open tasks, dealing a new one for each finished. */
@ExtendWith(MockitoExtension.class)
class RefillTest
{
	private static final int LUMBRIDGE = 12850;
	private static final int SWAMP = 12849;

	@Mock
	private ChunkBlazerConfig config;
	@Mock
	private ConfigManager configManager;

	private ChunkBlazerPlugin plugin;
	private Map<String, String> state;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		set(plugin, "config", config);
		set(plugin, "configManager", configManager);
		state = RsProfileTestSupport.install(configManager, config);
		chunks().put(LUMBRIDGE, chunk("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"));
	}

	/** Two finished out of a dealt five: two more are dealt, back to five open. */
	@Test
	void topsUpToFive() throws Exception
	{
		state.put("regionRolledTasks", LUMBRIDGE + ":t1,t2,t3,t4,t5");
		state.put("completedTasks", "t1,t2");
		assertEquals(2, refill().get(LUMBRIDGE).size());
		assertEquals(7, rolled(LUMBRIDGE).size());
		assertEquals(5, open(LUMBRIDGE));
	}

	/** Nothing finished, nothing dealt. */
	@Test
	void noneWhileFull() throws Exception
	{
		state.put("regionRolledTasks", LUMBRIDGE + ":t1,t2,t3,t4,t5");
		assertTrue(refill().isEmpty());
		assertEquals(5, rolled(LUMBRIDGE).size());
	}

	/** A chunk an old 4-task roll dealt gets its fifth. */
	@Test
	void oldRollToppedUp() throws Exception
	{
		state.put("regionRolledTasks", LUMBRIDGE + ":t1,t2,t3,t4");
		assertEquals(1, refill().get(LUMBRIDGE).size());
		assertEquals(5, open(LUMBRIDGE));
	}

	/** A chunk with nothing left to deal just shrinks. */
	@Test
	void runsDry() throws Exception
	{
		chunks().put(LUMBRIDGE, chunk("t1", "t2", "t3", "t4", "t5", "t6"));
		state.put("regionRolledTasks", LUMBRIDGE + ":t1,t2,t3,t4,t5");
		state.put("completedTasks", "t1,t2,t3");
		assertEquals(Collections.singletonList("t6"), ids(refill().get(LUMBRIDGE)));
		assertEquals(3, open(LUMBRIDGE));
		assertTrue(refill().isEmpty());
	}

	/** Never a task out in another chunk, and never a retired one. */
	@Test
	void skipsTakenAndRetired() throws Exception
	{
		NuzlockeChunk lumbridge = chunk("t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8");
		lumbridge.getTasks().get(7).setIsUnlocked(false);
		chunks().put(LUMBRIDGE, lumbridge);
		chunks().put(SWAMP, chunk("t6"));
		for (int i = 0; i < 20; i++)
		{
			state.put("regionRolledTasks", LUMBRIDGE + ":t1,t2,t3,t4,t5|" + SWAMP + ":t6");
			state.put("completedTasks", "t1");
			assertEquals(Collections.singletonList("t7"), ids(refill().get(LUMBRIDGE)));
		}
	}

	/** A boss chunk already has every task; it's never refilled. */
	@Test
	void skipsBossChunks() throws Exception
	{
		NuzlockeChunk boss = chunk("t1", "t2", "t3", "t4", "t5", "t6");
		set(boss, "chunkType", "BOSS");
		chunks().put(LUMBRIDGE, boss);
		state.put("regionRolledTasks", LUMBRIDGE + ":t1");
		assertTrue(refill().isEmpty());
	}

	/** Nothing is dealt while the server's roll may still be on its way. */
	@Test
	void waitsForServerRoll() throws Exception
	{
		when(config.apiEnabled()).thenReturn(true);
		state.put("regionRolledTasks", LUMBRIDGE + ":t1,t2,t3,t4,t5");
		state.put("completedTasks", "t1,t2");
		assertTrue(refill().isEmpty());
	}

	/** Login keeps refills the server hasn't heard of yet: the local roll holds all of its own. */
	@Test
	void loginKeepsRefills() throws Exception
	{
		state.put("regionRolledTasks", LUMBRIDGE + ":t1,t2,t3,t4,t5,t6,t7");
		restore(LUMBRIDGE + ":t1,t2,t3,t4,t5");
		assertEquals(LUMBRIDGE + ":t1,t2,t3,t4,t5,t6,t7", state.get("regionRolledTasks"));
	}

	/** A local roll that isn't a superset is stale or re-rolled: the server's stands. */
	@Test
	void loginServerWins() throws Exception
	{
		state.put("regionRolledTasks", LUMBRIDGE + ":t1,t2,t9");
		restore(LUMBRIDGE + ":t1,t2,t3,t4,t5");
		assertEquals(LUMBRIDGE + ":t1,t2,t3,t4,t5", state.get("regionRolledTasks"));
	}

	@SuppressWarnings("unchecked")
	private Map<Integer, List<NuzlockeTask>> refill() throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("refillChunks", Collection.class);
		m.setAccessible(true);
		return (Map<Integer, List<NuzlockeTask>>) m.invoke(plugin, Arrays.asList(LUMBRIDGE, SWAMP));
	}

	private void restore(String serverRoll) throws Exception
	{
		PlayerLoginResponse.PlayerData d = new PlayerLoginResponse.PlayerData();
		d.setRegionRolledTasks(serverRoll);
		d.setUnrevealedTasks("");
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("restoreRollStateFromServer", PlayerLoginResponse.PlayerData.class);
		m.setAccessible(true);
		m.invoke(plugin, d);
	}

	private Set<String> rolled(int region)
	{
		for (String entry : state.get("regionRolledTasks").split("\\|"))
		{
			if (entry.startsWith(region + ":"))
			{
				return new HashSet<>(Arrays.asList(entry.substring(entry.indexOf(':') + 1).split(",")));
			}
		}
		return new HashSet<>();
	}

	private int open(int region)
	{
		Set<String> open = rolled(region);
		String done = state.get("completedTasks");
		if (done != null)
		{
			open.removeAll(Arrays.asList(done.split(",")));
		}
		assertFalse(open.isEmpty() && rolled(region).isEmpty(), "region " + region + " has no roll");
		return open.size();
	}

	private static List<String> ids(List<NuzlockeTask> tasks)
	{
		List<String> out = new ArrayList<>();
		for (NuzlockeTask t : tasks)
		{
			out.add(t.getTaskId());
		}
		return out;
	}

	private static NuzlockeChunk chunk(String... taskIds) throws Exception
	{
		List<NuzlockeTask> tasks = new ArrayList<>();
		for (String id : taskIds)
		{
			NuzlockeTask t = new NuzlockeTask();
			t.setTaskId(id);
			t.setName(id);
			set(t, "assignmentWeight", 8);
			tasks.add(t);
		}
		NuzlockeChunk c = new NuzlockeChunk();
		set(c, "tasks", tasks);
		return c;
	}

	@SuppressWarnings("unchecked")
	private Map<Integer, NuzlockeChunk> chunks() throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField("chunksByRegionId");
		f.setAccessible(true);
		return (Map<Integer, NuzlockeChunk>) f.get(plugin);
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, value);
	}
}
