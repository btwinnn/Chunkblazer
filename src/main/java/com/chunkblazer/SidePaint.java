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

import java.util.function.BiPredicate;

/**
 * ChunkBlazer UI's hand in the side panel. Without it, the panel is ChunkBlazer's own look.
 * With it, the UI styles the panel's parts and draws its headings, cards, badges and stat
 * boxes in whatever theme it has, so no theme lives in ChunkBlazer.
 *
 * <p>Parts asked for: "restyle" (the panel root, after anything's added), "background",
 * "header", "card", "badge", "stats" and "stat". The painter returns true if it drew it.
 */
final class SidePaint
{
	// Set by ChunkBlazer UI (through the bridge), null without it.
	static volatile BiPredicate<String, Object[]> painter;
	// Moves on each restyle, so cached side panel rows are drawn again.
	static volatile int version;

	private SidePaint()
	{
	}

	/** Ask ChunkBlazer UI to draw {@code part}: true if it did. */
	static boolean draw(String part, Object... args)
	{
		BiPredicate<String, Object[]> p = painter;
		try
		{
			return p != null && p.test(part, args);
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}
}
