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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.chunkblazer.api.CatalogStore;
import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** A catalog fix downloaded in the background is applied at the login screen, not on the next launch. */
@ExtendWith(MockitoExtension.class)
class CatalogReloadTest
{
	@Mock
	private CatalogStore catalogStore;

	@Mock
	private Client client;

	private ChunkBlazerPlugin plugin;

	@BeforeEach
	void setUp() throws Exception
	{
		plugin = new ChunkBlazerPlugin();
		set("catalogStore", catalogStore);
		set("client", client);
		set("gson", new Gson());
		lenient().when(catalogStore.getFileContent(anyString())).thenReturn(null);
	}

	@Test
	void newerCatalogIsReloaded() throws Exception
	{
		serve(1, "count is");
		loadChunkData();
		assertEquals("count is", trigger());

		serve(2, "completed tombs of amascut");
		assertTrue(plugin.reloadCatalogIfNewer());
		assertEquals("completed tombs of amascut", trigger());
	}

	@Test
	void sameCatalogIsNotReloaded() throws Exception
	{
		serve(5, "completed tombs of amascut");
		loadChunkData();
		assertFalse(plugin.reloadCatalogIfNewer());
	}

	/** A catalog the logout check downloads applies while the player is still on the login screen. */
	@Test
	void appliesLoggedOut() throws Exception
	{
		serve(1, "count is");
		loadChunkData();

		serve(2, "completed tombs of amascut");
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		plugin.applyCatalogIfLoggedOut();
		assertEquals("completed tombs of amascut", trigger());
	}

	/** One that lands after the player is back in game waits for the next logout. */
	@Test
	void waitsLoggedIn() throws Exception
	{
		serve(1, "count is");
		loadChunkData();

		when(catalogStore.getCatalogVersion()).thenReturn(2L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		plugin.applyCatalogIfLoggedOut();
		assertEquals("count is", trigger());
		assertTrue(plugin.reloadCatalogIfNewer(), "the newer catalog is still waiting");
	}

	private void serve(long version, String completeMessage)
	{
		when(catalogStore.getCatalogVersion()).thenReturn(version);
		when(catalogStore.getFileContent("Misthalin_Tasks.json")).thenReturn(
			"{\"Misthalin_Tasks\":[{\"region_id\":[13354],\"tasks\":[{\"name\":\"Next Level\","
			+ "\"taskID\":\"toa_next_level\",\"completion_type\":\"RAID_CHALLENGE\","
			+ "\"challenge\":{\"complete_message\":\"" + completeMessage + "\"}}]}]}");
	}

	private String trigger()
	{
		return plugin.getTaskById("toa_next_level").getChallenge().getCompleteMessage();
	}

	private void loadChunkData() throws Exception
	{
		Method m = ChunkBlazerPlugin.class.getDeclaredMethod("loadChunkData");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	private void set(String name, Object value) throws Exception
	{
		Field f = ChunkBlazerPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(plugin, value);
	}
}
