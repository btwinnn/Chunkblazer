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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The shared text helpers, checked against the per-overlay copies they replace. */
class DrawTest
{
	/** Every character 10px wide, ascent 10. */
	private static final FontMetrics FM = new FontMetrics(new Font(Font.DIALOG, Font.PLAIN, 12))
	{
		@Override
		public int stringWidth(String s)
		{
			return s.length() * 10;
		}

		@Override
		public int getAscent()
		{
			return 10;
		}
	};

	@Test
	void fitLeavesShortTextAlone()
	{
		assertEquals("abc", Draw.fit(FM, "abc", 30));
		assertEquals("", Draw.fit(FM, "", 0));
	}

	@Test
	void fitTrimsToTheWidthWithEllipsis()
	{
		assertEquals("ab...", Draw.fit(FM, "abcdef", 50));
		assertEquals("ab...", Draw.fit(FM, "abcdef", 59));
		assertEquals("...", Draw.fit(FM, "abcdef", 30));
		assertEquals("...", Draw.fit(FM, "abcdef", 0));
		assertEquals("...", Draw.fit(FM, "abcdef", -5));
	}

	@Test
	void fitMatchesTheOldCopies()
	{
		Random random = new Random(7);
		for (int i = 0; i < 2000; i++)
		{
			String text = randomText(random);
			int width = random.nextInt(140) - 10;
			assertEquals(oldTruncateText(text, width), Draw.fit(FM, text, width), text + " @ " + width);
		}
	}

	@Test
	void wrapBreaksBetweenWords()
	{
		assertEquals(Arrays.asList("aa bb", "cc"), Draw.wrap(FM, "aa bb cc", 50));
		// A word longer than the width still gets its own line rather than vanishing.
		assertEquals(Arrays.asList("a", "bbbbbbbb", "c"), Draw.wrap(FM, "a bbbbbbbb c", 30));
		assertEquals(Collections.emptyList(), Draw.wrap(FM, "", 50));
		assertEquals(Collections.emptyList(), Draw.wrap(FM, null, 50));
		assertEquals(Collections.singletonList("one"), Draw.wrap(FM, "one", 0));
	}

	@Test
	void wrapMatchesTheOldCopy()
	{
		Random random = new Random(11);
		for (int i = 0; i < 2000; i++)
		{
			String text = randomText(random);
			int width = random.nextInt(120);
			assertEquals(oldWrap(text, width), Draw.wrap(FM, text, width), text + " @ " + width);
		}
	}

	@Test
	void textYCentresTheBaseline()
	{
		assertEquals(100 + (20 + 10) / 2 - 2, Draw.textY(FM, 100, 20));
		assertTrue(Draw.textY(FM, 0, 21) >= 0);
	}

	private static String randomText(Random random)
	{
		StringBuilder text = new StringBuilder();
		int length = random.nextInt(14);
		for (int i = 0; i < length; i++)
		{
			text.append(random.nextInt(4) == 0 ? ' ' : (char) ('a' + random.nextInt(26)));
		}
		return text.toString();
	}

	/** TaskCompletionAnimationOverlay.truncateText as it was. */
	private static String oldTruncateText(String text, int maxWidth)
	{
		if (FM.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		for (int i = text.length() - 1; i > 0; i--)
		{
			String t = text.substring(0, i) + "...";
			if (FM.stringWidth(t) <= maxWidth)
			{
				return t;
			}
		}
		return "...";
	}

	/** TaskCardOverlay.wrap as it was. */
	private static List<String> oldWrap(String text, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		if (text == null || text.isEmpty())
		{
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (FM.stringWidth(candidate) > maxWidth && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}
}
