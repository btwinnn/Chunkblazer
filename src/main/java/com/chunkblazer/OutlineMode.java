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

import java.awt.Color;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Which NPCs and objects get a task outline (set from the task window's cogwheel). */
@Getter
@RequiredArgsConstructor
public enum OutlineMode
{
	/** Anything one of your active tasks needs. */
	ALL("All tasks"),
	/** Only things your saved (starred) tasks need. */
	SAVED("Saved tasks"),
	/** Only things with a task you have the level for. */
	CAN_DO("Have requirements"),
	/** No outlines. */
	OFF("Off");

	private final String name;

	/**
	 * The outline colour for a target with these tasks, or null for no outline (the same
	 * rules for NPCs, objects and items). All: any task, normal colour if one is doable,
	 * the "level too low" colour if not. Saved: only saved tasks count, coloured the same
	 * way. Have requirements: only when one is doable, in the normal colour. Off: never.
	 */
	Color color(List<NuzlockeTask> tasks, ChunkBlazerPlugin plugin, Color doable, Color unavailable)
	{
		if (this == OFF)
		{
			return null;
		}
		Set<String> saved = this == SAVED ? plugin.savedTaskIds() : null;
		boolean counted = false;
		for (NuzlockeTask task : tasks)
		{
			if (saved == null || saved.contains(task.getTaskId()))
			{
				if (plugin.canDo(task))
				{
					return doable;
				}
				counted = true;
			}
		}
		return counted && this != CAN_DO ? unavailable : null;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
