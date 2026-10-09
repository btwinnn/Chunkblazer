/*
 * Copyright (c) 2026, Vani-Lab
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Saved-id parsing and the outline colour rules shared by the NPC, object and item outlines. */
class SharedTaskHelpersTest
{
	private static final Color DOABLE = Color.GREEN;
	private static final Color UNAVAILABLE = Color.RED;

	@Test
	void parseIdsTrimsDropsBlanksAndKeepsOrder()
	{
		assertEquals(Arrays.asList("b", "a", "c"), Arrays.asList(ChunkBlazerPlugin.parseIds(" b,a,,  ,c ,a").toArray()));
		assertTrue(ChunkBlazerPlugin.parseIds(null).isEmpty());
		assertTrue(ChunkBlazerPlugin.parseIds("").isEmpty());
	}

	@Test
	void parseIdsRoundTripsThroughJoin()
	{
		Set<String> ids = ChunkBlazerPlugin.parseIds("x1,y2,z3");
		assertEquals("x1,y2,z3", String.join(",", ids));
		assertEquals(ids, ChunkBlazerPlugin.parseIds(String.join(",", ids)));
		ids.add("w4");
		assertEquals("x1,y2,z3,w4", String.join(",", ids));
	}

	@Test
	void outlineColourRules()
	{
		NuzlockeTask ready = task("ready");
		NuzlockeTask locked = task("locked");
		ChunkBlazerPlugin plugin = mock(ChunkBlazerPlugin.class);
		when(plugin.canDo(ready)).thenReturn(true);
		when(plugin.canDo(locked)).thenReturn(false);
		when(plugin.savedTaskIds()).thenReturn(new HashSet<>(Collections.singletonList("locked")));

		List<NuzlockeTask> both = Arrays.asList(locked, ready);
		List<NuzlockeTask> onlyLocked = Collections.singletonList(locked);
		List<NuzlockeTask> onlyReady = Collections.singletonList(ready);
		List<NuzlockeTask> none = Collections.emptyList();

		for (OutlineMode mode : OutlineMode.values())
		{
			assertNull(mode.color(none, plugin, DOABLE, UNAVAILABLE), mode.name());
		}

		assertEquals(DOABLE, OutlineMode.ALL.color(both, plugin, DOABLE, UNAVAILABLE));
		assertEquals(UNAVAILABLE, OutlineMode.ALL.color(onlyLocked, plugin, DOABLE, UNAVAILABLE));

		assertEquals(DOABLE, OutlineMode.CAN_DO.color(both, plugin, DOABLE, UNAVAILABLE));
		assertNull(OutlineMode.CAN_DO.color(onlyLocked, plugin, DOABLE, UNAVAILABLE));

		// Only "locked" is saved: the doable task doesn't count in Saved mode.
		assertEquals(UNAVAILABLE, OutlineMode.SAVED.color(both, plugin, DOABLE, UNAVAILABLE));
		assertNull(OutlineMode.SAVED.color(onlyReady, plugin, DOABLE, UNAVAILABLE));

		assertNull(OutlineMode.OFF.color(both, plugin, DOABLE, UNAVAILABLE));
	}

	private static NuzlockeTask task(String id)
	{
		NuzlockeTask t = new NuzlockeTask();
		t.setTaskId(id);
		return t;
	}
}
