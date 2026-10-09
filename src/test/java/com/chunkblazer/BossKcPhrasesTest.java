package com.chunkblazer;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The boss-token chat signals live in boss_kc_phrases.json as an ordered rule list. The
 * reference below is the old hand-written if/else chain, kept verbatim so the data-driven
 * lookup is checked against it: same key, same first-match order, same case handling.
 */
class BossKcPhrasesTest
{
	private static final String[][] RULES = ChunkBlazerPlugin.loadBossPhrases(new Gson());

	/** Verbatim copy of the old handleBossCompletionChat branches, returning the key. */
	private static String oldKey(String msg)
	{
		String plain = msg.toLowerCase();
		if (plain.contains("tombs of amascut") && plain.contains("count is"))
		{
			return "toa";
		}
		else if (plain.contains("scurrius") && plain.contains("kill count is"))
		{
			return "scurrius";
		}
		else if (plain.contains("bryophyta") && plain.contains("kill count is"))
		{
			return "bryophyta";
		}
		else if (plain.contains("brutus") && plain.contains("kill count is"))
		{
			return "brutus";
		}
		else if (plain.contains("royal titans") && plain.contains("kill count is"))
		{
			return "royal_titans";
		}
		else if (plain.contains("barrows chest count is"))
		{
			return "barrows";
		}
		else if ((plain.contains("chambers of xeric") && plain.contains("count is"))
			|| plain.contains("your raid is complete"))
		{
			return "cox";
		}
		else if (plain.contains("zulrah") && plain.contains("kill count is"))
		{
			return "zulrah";
		}
		else if (plain.contains("lunar chest count is"))
		{
			return "moons_of_peril";
		}
		else if (plain.contains("nex") && plain.contains("kill count is"))
		{
			return "nex";
		}
		else if (plain.contains("hueycoatl") && plain.contains("kill count is"))
		{
			return "hueycoatl";
		}
		else if (plain.contains("deep delves completed"))
		{
			return "doom_of_mokhaiotl";
		}
		else if (plain.contains("phantom muspah") && plain.contains("kill count is"))
		{
			return "phantom_muspah";
		}
		else if (plain.contains("vorkath") && plain.contains("kill count is"))
		{
			return "vorkath";
		}
		else if (plain.contains("phosani's nightmare") && plain.contains("kill count is"))
		{
			return "phosani_nightmare";
		}
		else if (plain.contains("yama") && plain.contains("kill count is"))
		{
			return "yama";
		}
		else if (plain.contains("tzkal-zuk") && plain.contains("kill count is"))
		{
			return "zuk";
		}
		else if (plain.contains("leviathan") && plain.contains("kill count is"))
		{
			return "leviathan";
		}
		else if (plain.contains("whisperer") && plain.contains("kill count is"))
		{
			return "whisperer";
		}
		else if (plain.contains("duke sucellus") && plain.contains("kill count is"))
		{
			return "duke_sucellus";
		}
		else if (plain.contains("vardorvis") && plain.contains("kill count is"))
		{
			return "vardorvis";
		}
		else if (plain.contains("sol heredit") && plain.contains("kill count is"))
		{
			return "sol_heredit";
		}
		return null;
	}

	private static final String[][] CASES = {
		{"Your completed Tombs of Amascut count is: 4.", "toa"},
		{"Your completed Tombs of Amascut: Expert Mode count is: 1.", "toa"},
		{"Your completed Tombs of Amascut: Entry Mode count is: 2.", "toa"},
		{"Your Scurrius kill count is: 1.", "scurrius"},
		{"Your Bryophyta kill count is: 3.", "bryophyta"},
		{"Your Brutus kill count is: 1.", "brutus"},
		{"Your Royal Titans kill count is: 7.", "royal_titans"},
		{"Your Barrows chest count is: 12.", "barrows"},
		{"Your completed Chambers of Xeric count is: 2.", "cox"},
		{"Your completed Chambers of Xeric Challenge Mode count is: 1.", "cox"},
		{"Congratulations - your raid is complete!", "cox"},
		{"Your Zulrah kill count is: 50.", "zulrah"},
		{"Your Lunar Chest count is: 9.", "moons_of_peril"},
		{"Your Nex kill count is: 1.", "nex"},
		{"Your Hueycoatl kill count is: 2.", "hueycoatl"},
		{"Deep delves completed: 8", "doom_of_mokhaiotl"},
		{"Your Phantom Muspah kill count is: 4.", "phantom_muspah"},
		{"Your Vorkath kill count is: 100.", "vorkath"},
		{"Your Phosani's Nightmare kill count is: 1.", "phosani_nightmare"},
		{"Your Yama kill count is: 1.", "yama"},
		{"Your TzKal-Zuk kill count is: 1.", "zuk"},
		{"Your Leviathan kill count is: 3.", "leviathan"},
		{"Your Whisperer kill count is: 3.", "whisperer"},
		{"Your Duke Sucellus kill count is: 3.", "duke_sucellus"},
		{"Your Vardorvis kill count is: 3.", "vardorvis"},
		{"Your Sol Heredit kill count is: 1.", "sol_heredit"},
		// case handling
		{"YOUR SCURRIUS KILL COUNT IS: 1.", "scurrius"},
		// the regular Nightmare must not mint the Phosani token
		{"Your Nightmare kill count is: 3.", null},
		// non-signals
		{"Your Theatre of Blood count is: 1.", null},
		{"Your Zulrah kill count: 5", null},
		{"You have defeated Scurrius.", null},
		{"Fight duration: 1:23", null},
		// order-sensitive overlaps: the earlier rule wins
		{"Your raid is complete! Your Zulrah kill count is: 1.", "cox"},
		{"Your Tombs of Amascut count is: 1, your raid is complete", "toa"},
		{"Nex hueycoatl kill count is: 1", "nex"},
		{"Phosani's Nightmare yama kill count is: 1", "phosani_nightmare"},
		{"Vardorvis whisperer kill count is: 1", "whisperer"},
		{"Lunar chest count is, nex kill count is", "moons_of_peril"},
		{"Deep delves completed: 9, vorkath kill count is", "doom_of_mokhaiotl"},
	};

	@Test
	void referenceMatchesExpectedKeys()
	{
		for (String[] c : CASES)
		{
			assertEquals(c[1], oldKey(c[0]), c[0]);
		}
	}

	@Test
	void dataDrivenLookupMatchesExpectedKeys()
	{
		for (String[] c : CASES)
		{
			assertEquals(c[1], ChunkBlazerPlugin.bossKey(c[0], RULES), c[0]);
		}
	}

	/** Every pair of signal fragments, in both orders, resolves the same as the old chain. */
	@Test
	void allFragmentPairsMatchReference()
	{
		List<String> frags = new ArrayList<>(Arrays.asList("", "nightmare", "Kill Count Is", "count is",
			"chest count is", "Your raid is complete"));
		for (String[] c : CASES)
		{
			frags.add(c[0]);
		}
		for (String[] rule : RULES)
		{
			frags.addAll(Arrays.asList(rule).subList(0, rule.length - 1));
		}
		for (String a : frags)
		{
			for (String b : frags)
			{
				String line = a + " " + b;
				assertEquals(oldKey(line), ChunkBlazerPlugin.bossKey(line, RULES), line);
			}
		}
	}

	@Test
	void rulesLoadFromResource()
	{
		assertEquals(23, RULES.length);
		assertArrayEquals(new String[]{"tombs of amascut", "count is", "toa"}, RULES[0]);
	}
}
