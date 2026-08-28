package com.chunkblazer;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * getBossKeys() is what the once-per-boss token logic keys off, so a chunk can host
 * more than one token-boss on one region (the Varrock chunk carries both Scurrius and
 * Bryophyta). It must prefer the boss_keys LIST, fall back to the single boss_key for
 * every existing one-boss chunk (ToA/CoX), and be empty for non-boss chunks.
 */
class NuzlockeChunkBossKeysTest
{
	private static final Gson GSON = new Gson();

	private NuzlockeChunk parse(String json)
	{
		return GSON.fromJson(json, NuzlockeChunk.class);
	}

	@Test
	void bossKeysList_isReturnedWhenPresent()
	{
		NuzlockeChunk c = parse("{\"region_id\":[12854],\"chunk_type\":\"BOSS\","
			+ "\"boss_keys\":[\"scurrius\",\"bryophyta\"]}");
		assertEquals(java.util.Arrays.asList("scurrius", "bryophyta"), c.getBossKeys());
	}

	@Test
	void singleBossKey_fallsBackToOneElementList()
	{
		NuzlockeChunk c = parse("{\"region_id\":[13354],\"chunk_type\":\"BOSS\",\"boss_key\":\"toa\"}");
		assertEquals(java.util.Collections.singletonList("toa"), c.getBossKeys());
	}

	@Test
	void bossKeysListWins_overSingleKey()
	{
		NuzlockeChunk c = parse("{\"chunk_type\":\"BOSS\",\"boss_key\":\"scurrius\","
			+ "\"boss_keys\":[\"scurrius\",\"bryophyta\"]}");
		assertEquals(2, c.getBossKeys().size(), "the list supersedes the single key");
		assertTrue(c.getBossKeys().contains("bryophyta"));
	}

	@Test
	void noBossKeys_isEmpty()
	{
		NuzlockeChunk c = parse("{\"region_id\":[12345],\"chunk_type\":\"Standard\"}");
		assertTrue(c.getBossKeys().isEmpty(), "a non-boss chunk has no boss keys");
	}

	@Test
	void bossNpcIds_parsePerBossKey()
	{
		// The data-driven NPC-death detection: each boss key maps to the npc ids whose
		// death completes it. Drives the plugin's id -> key lookup (no hardcoded map).
		NuzlockeChunk c = parse("{\"chunk_type\":\"BOSS\",\"boss_keys\":[\"scurrius\",\"bryophyta\"],"
			+ "\"boss_npc_ids\":{\"scurrius\":[7221,7222],\"bryophyta\":[8195]}}");
		assertNotNull(c.getBossNpcIds());
		assertEquals(java.util.Arrays.asList(7221, 7222), c.getBossNpcIds().get("scurrius"));
		assertEquals(java.util.Arrays.asList(8195), c.getBossNpcIds().get("bryophyta"));
	}

	@Test
	void bossNpcIds_absentIsNull()
	{
		NuzlockeChunk c = parse("{\"chunk_type\":\"BOSS\",\"boss_key\":\"toa\"}");
		assertNull(c.getBossNpcIds(), "raids leave boss_npc_ids unset (chat-detected)");
	}
}
