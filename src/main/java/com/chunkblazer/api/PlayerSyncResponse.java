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

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from player sync API.
 * Contains server-authoritative state that should override client state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerSyncResponse
{
	/** Whether sync was successful */
	private boolean success;

	/** Server-authoritative points */
	private Integer serverPoints;

	/** Server-authoritative unlocked regions */
	private List<Integer> serverUnlockedRegions;

	/** Server-authoritative completed tasks */
	private List<String> serverCompletedTasks;

	/**
	 * Server-authoritative spendable Boss Token balance = max(0, earned - spent).
	 * The client adopts this on sync so it survives reinstall / profile switch.
	 */
	private Integer serverBossTokens;

	/** Current active task from server */
	private ServerTask activeTask;

	/** Any messages from server (announcements, warnings) */
	private List<String> messages;

	/** Whether account is flagged for suspicious activity */
	private boolean flagged;

	/** Flag reason if flagged */
	private String flagReason;

	/** Leaderboard rank (if in nuzlocke mode) */
	private Integer leaderboardRank;

	/** Server timestamp */
	private long serverTimestamp;

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ServerTask
	{
		private String taskId;
		private String name;
		private int progress;
		private int target;
		private int basePoints;
	}
}
