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

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Response containing the current player's rank and nearby players.
 */
@Data
public class PlayerRankResponse
{
	/**
	 * The current player's rank info
	 */
	private PlayerRank player;

	/**
	 * Players near the current player's rank
	 */
	private List<NearbyPlayer> nearby = new ArrayList<>();

	/**
	 * Current player's rank details.
	 */
	@Data
	public static class PlayerRank
	{
		private String rsn;
		private int rank;

		@SerializedName("total_points")
		private int totalPoints;

		/**
		 * Percentile (e.g., 97.3 means top 2.7%)
		 */
		private double percentile;
	}

	/**
	 * A nearby player on the leaderboard.
	 */
	@Data
	public static class NearbyPlayer
	{
		private int rank;
		private String rsn;

		@SerializedName("total_points")
		private int totalPoints;
	}

	/**
	 * Create an unranked response for offline mode or new players.
	 */
	public static PlayerRankResponse unranked()
	{
		PlayerRankResponse response = new PlayerRankResponse();
		PlayerRank player = new PlayerRank();
		player.setRank(-1);
		player.setTotalPoints(0);
		player.setPercentile(0);
		response.setPlayer(player);
		return response;
	}
}
