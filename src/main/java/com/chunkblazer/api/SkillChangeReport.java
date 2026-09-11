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

package com.chunkblazer.api;

import lombok.Builder;
import lombok.Data;

/**
 * Report for skill level/XP changes sent to server for verification.
 */
@Data
@Builder
public class SkillChangeReport
{
	/** Player RSN hash */
	private String playerHash;

	/** Task ID this skill change is for */
	private String taskId;

	/** Skill ID (0=Attack, 1=Defence, etc.) */
	private int skillId;

	/** Skill name */
	private String skillName;

	/** Previous level */
	private int previousLevel;

	/** New level */
	private int newLevel;

	/** Previous XP */
	private int previousXp;

	/** New XP */
	private int newXp;

	/** XP gained in this event */
	private int xpGained;

	/** Region ID where XP was gained */
	private int regionId;

	/** Game tick when event occurred */
	private int gameTick;

	/** Client timestamp */
	private long timestamp;

	/** Total level after this change */
	private int totalLevel;

	/** Action that caused the XP gain (if known) */
	private String action;
}
