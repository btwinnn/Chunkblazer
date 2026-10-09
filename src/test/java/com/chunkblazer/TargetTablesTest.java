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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chunkblazer.TaskTargetHighlighter.Entrance;
import com.chunkblazer.TaskTargetHighlighter.Tables;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** The boss entrance and fishing tables read from target_tables.json. */
class TargetTablesTest
{
	private final Tables tables = TaskTargetHighlighter.loadTables(new Gson());

	private static NuzlockeTask task(String id)
	{
		NuzlockeTask task = mock(NuzlockeTask.class);
		when(task.getTaskId()).thenReturn(id);
		return task;
	}

	@Test
	void bossEntrancesLoad()
	{
		assertEquals(38, tables.bossEntrances.size());
		assertNull(tables.bossEntrances.get(0));

		Entrance brutus = tables.bossEntrances.get(60760);
		assertEquals(12851, brutus.region);
		assertTrue(brutus.taskIdKeywords.isEmpty());

		Entrance scurrius = tables.bossEntrances.get(14203);
		assertEquals(12854, scurrius.region);
		assertEquals(new HashSet<>(Arrays.asList("scurrius", "bone_mace", "bone_shortbow", "bone_staff")),
			scurrius.taskIdKeywords);

		assertEquals(3831, tables.bossEntrances.keySet().stream().mapToInt(Integer::intValue).min().getAsInt());
		assertEquals(10042, tables.bossEntrances.get(3831).region);
		assertEquals(11578, tables.bossEntrances.get(42934).region);
		assertEquals(Collections.singleton("gwd_nex_"), tables.bossEntrances.get(42934).taskIdKeywords);
		assertEquals(4919, tables.bossEntrances.get(29777).region);
		assertEquals(8751, tables.bossEntrances.get(10068).region);
	}

	@Test
	void entranceKeywordsFilterTasks()
	{
		Entrance brutus = tables.bossEntrances.get(60760);
		assertTrue(brutus.includes(task("anything")));
		assertTrue(brutus.includes(task(null)));

		Entrance graardor = tables.bossEntrances.get(26503);
		assertTrue(graardor.includes(task("GWD_Graardor_Kill")));
		assertFalse(graardor.includes(task("gwd_zilyana_kill")));
		assertFalse(graardor.includes(task(null)));
	}

	@Test
	void fishLoadInOrder()
	{
		assertEquals(Arrays.asList("leaping", "dark crab", "lobster", "karambwanji", "karambwan", "shrimp", "anchov",
			"monkfish", "sardine", "herring", "anglerfish", "eel", "pike", "trout", "salmon", "rainbow fish",
			"mackerel", "cod", "bass", "shark", "tuna", "swordfish"), new ArrayList<>(tables.fish.keySet()));
		assertArrayEquals(new String[]{"use-rod"}, tables.fish.get("leaping"));
		assertArrayEquals(new String[]{"net", "small net"}, tables.fish.get("karambwanji"));
		assertArrayEquals(new String[]{"big net"}, tables.fish.get("cod"));
		assertArrayEquals(new String[]{"harpoon"}, tables.fish.get("swordfish"));
	}
}
