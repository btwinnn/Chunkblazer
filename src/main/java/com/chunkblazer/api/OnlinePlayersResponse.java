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

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Response from GET /api/players/online — the live roster of ChunkBlazer
 * players whose heartbeat is fresh (within the server's window).
 *
 * Field names match the server's camelCase JSON exactly so Gson binds them
 * with no @SerializedName gymnastics. Unknown server fields (limit, offset,
 * fetchedAt) are simply ignored by Gson.
 */
@Data
public class OnlinePlayersResponse
{
	/** Players currently online (heartbeat within {@link #windowSeconds}). */
	private List<OnlinePlayer> players = new ArrayList<>();

	/** Total online count (may exceed players.size() when paginated). */
	private int total;

	/** Server-reported freshness window, in seconds. */
	private int windowSeconds;

	/**
	 * Empty response used for offline mode / failed requests.
	 */
	public static OnlinePlayersResponse empty()
	{
		return new OnlinePlayersResponse();
	}

	/**
	 * A single online player as returned by the server. {@code gameMode} and
	 * {@code rank} are nullable — a player with no locked game mode is unranked.
	 */
	@Data
	public static class OnlinePlayer
	{
		private String rsn;
		/** True if this is a ChunkBlazer dev/tester account (server is_dev flag). */
		private boolean isDev;
		private String accountType;
		private String gameMode;
		private Integer currentWorld;
		private Integer currentRegionId;
		private String lastHeartbeatAt;
		private int totalPoints;
		private Integer rank;
	}
}
