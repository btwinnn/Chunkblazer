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
import com.chunkblazer.GameMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Response from the server when a player logs in.
 * Contains the player's full state including mode, points, unlocked regions, etc.
 */
@Data
public class PlayerLoginResponse
{
	/**
	 * Status of the login: "ok", "created", or "error"
	 */
	private String status;

	/**
	 * Error code if status is "error"
	 */
	private String error;

	/**
	 * Human-readable message
	 */
	private String message;

	/**
	 * The player's data
	 */
	private PlayerData player;

	/**
	 * API key for this player (only returned on first registration)
	 */
	@SerializedName("api_key")
	private String apiKey;

	/**
	 * Check if the login was successful.
	 */
	public boolean isSuccess()
	{
		return "ok".equals(status) || "created".equals(status);
	}

	/**
	 * Check if the player's game mode is locked.
	 */
	public boolean isModeLocked()
	{
		return player != null && player.isModeLocked();
	}

	/**
	 * Get the player's game mode, or null if not set.
	 */
	public GameMode getGameMode()
	{
		if (player == null || player.getGameMode() == null)
		{
			return null;
		}
		try
		{
			return GameMode.valueOf(player.getGameMode());
		}
		catch (IllegalArgumentException e)
		{
			return GameMode.CASUAL;
		}
	}

	/**
	 * Create an offline/error response for when API is disabled or unreachable.
	 */
	public static PlayerLoginResponse offline()
	{
		PlayerLoginResponse response = new PlayerLoginResponse();
		response.setStatus("offline");
		response.setMessage("API is disabled or unreachable");
		return response;
	}

	/**
	 * Player data nested object.
	 */
	@Data
	public static class PlayerData
	{
		private String rsn;

		@SerializedName("game_mode")
		private String gameMode;

		@SerializedName("mode_locked")
		private boolean modeLocked;

		@SerializedName("locked_at")
		private String lockedAt;

		/** Lifetime points EARNED, recomputed server-side from the task list. */
		@SerializedName("total_points")
		private int totalPoints;

		/**
		 * Points spent unlocking chunks. Client-reported and monotonic, so it is
		 * reconciled by taking the maximum. The spendable balance is never sent —
		 * it is derived on the client as earned minus this.
		 */
		@SerializedName("points_spent")
		private int pointsSpent;

		@SerializedName("unlocked_regions")
		private List<Integer> unlockedRegions = new ArrayList<>();

		@SerializedName("completed_tasks")
		private List<String> completedTasks = new ArrayList<>();

		/**
		 * The account's per-region task roll, stored verbatim as the plugin's own
		 * config string ("regionId:task1,task2|regionId2:task3"). The server never
		 * interprets it — it round-trips the blob so the roll (and, with it, which
		 * cards are still face-down) survives an account switch, a reinstall, or a
		 * new machine, instead of being regenerated wholesale (the 572-card dump).
		 * Empty for accounts that have not synced a roll yet.
		 */
		@SerializedName("region_rolled_tasks")
		private String regionRolledTasks = "";

		/**
		 * The account's still-face-down reveal cards, stored verbatim as the plugin's
		 * config string ("task1,task2"). Restored alongside {@link #regionRolledTasks}
		 * so flipped stays flipped across profiles/devices.
		 */
		@SerializedName("unrevealed_tasks")
		private String unrevealedTasks = "";

		/**
		 * Whether this player has completed RSN ownership verification via the
		 * in-game chat handshake. If false, the plugin should kick off the
		 * verification flow on first login.
		 */
		private boolean verified;

		@SerializedName("verified_at")
		private String verifiedAt;

		/**
		 * Whether the server marks this account as a ChunkBlazer dev/tester.
		 * Drives the {@code [Dev]} badge in the roster and player overlay.
		 * Server-issued on purpose so it can't be self-granted from local
		 * config; defaults false, so an old or malformed response leaves the
		 * account unmarked.
		 */
		@SerializedName("is_dev")
		private boolean isDev;
	}
}
