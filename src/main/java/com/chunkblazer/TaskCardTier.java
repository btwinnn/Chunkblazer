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

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Card rarity for the task-reveal cards, and the colour each tier is shown in.
 *
 * <p>Task JSON has no difficulty field ({@code level} is a skill-level requirement), so
 * the tier is derived from {@code base_points}, authored 1..5 across every task file. If
 * a real {@code tier} field is ever authored, only {@link #fromTask} has to change.
 */
@Getter
@RequiredArgsConstructor
public enum TaskCardTier
{
	EASY("Easy", new Color(88, 166, 216)),
	MEDIUM("Medium", new Color(106, 168, 79)),
	HARD("Hard", new Color(152, 108, 190)),
	ELITE("Elite", new Color(214, 158, 46)),
	MASTER("Master", new Color(196, 68, 62));

	/** Shown on the card. */
	private final String displayName;

	/** The tier's colour, wherever tasks are shown. */
	private final Color accent;

	/**
	 * Tier for a task, from its {@code base_points}. Anything outside 1..5 — including
	 * an unauthored 0 — clamps into range rather than throwing, because a card with no
	 * art is far worse than a card of a slightly wrong rarity.
	 */
	public static TaskCardTier fromTask(NuzlockeTask task)
	{
		if (task == null)
		{
			return EASY;
		}
		return fromPoints(task.getBasePoints());
	}

	public static TaskCardTier fromPoints(int basePoints)
	{
		TaskCardTier[] tiers = values();
		int index = Math.max(1, Math.min(tiers.length, basePoints)) - 1;
		return tiers[index];
	}
}
