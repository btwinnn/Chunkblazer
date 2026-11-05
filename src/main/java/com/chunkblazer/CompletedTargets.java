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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks which completed tasks' rolled target quantities to send with a sync.
 *
 * <p>The completed list only grows at the end, so one "sent through" index marks
 * progress. The plugin moves it only once the server accepts. A run from 0 is the
 * backfill.
 */
final class CompletedTargets
{
	static final int BATCH = 200;

	private CompletedTargets()
	{
	}

	/** The quantities to send, and the index to store once the server accepts them. */
	static final class Batch
	{
		final Map<String, Integer> targets;
		final int sentThrough;

		Batch(Map<String, Integer> targets, int sentThrough)
		{
			this.targets = targets;
			this.sentThrough = sentThrough;
		}
	}

	/**
	 * @param completed completed task IDs in completion order
	 * @param sentThrough stored index; past the end means the list shrank, so restart
	 * @param targets rolled target per task ID (from taskProgressData)
	 */
	static Batch next(List<String> completed, int sentThrough, Map<String, Integer> targets)
	{
		int i = sentThrough < 0 || sentThrough > completed.size() ? 0 : sentThrough;
		Map<String, Integer> out = new LinkedHashMap<>();
		while (i < completed.size() && out.size() < BATCH)
		{
			String id = completed.get(i++);
			Integer target = targets.get(id);
			if (target != null && target > 1)
			{
				out.put(id, target);
			}
		}
		return new Batch(out, i);
	}

	/** Parses taskProgressData ("taskId:progress:target,...") into taskId -> target. */
	static Map<String, Integer> parseTargets(String data)
	{
		Map<String, Integer> out = new HashMap<>();
		if (data == null || data.isEmpty())
		{
			return out;
		}
		for (String entry : data.split(","))
		{
			String[] parts = entry.split(":");
			if (parts.length >= 3)
			{
				try
				{
					out.put(parts[0].trim(), Integer.parseInt(parts[2].trim()));
				}
				catch (NumberFormatException ignored)
				{
					// malformed entry: no target to report
				}
			}
		}
		return out;
	}
}
