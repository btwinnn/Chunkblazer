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

	/** Set by the api client, not the server. */
	private transient ApiOutcome outcome;
	private transient long retryAfterMs;

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
	 * True when the key the client sent belongs to a DIFFERENT account than the
	 * one logging in (the cross-account key leak). The server declined to
	 * authenticate as the key's owner and served this account by name instead;
	 * the client drops the mismatched key on seeing this.
	 */
	@SerializedName("key_mismatch")
	private boolean keyMismatch;

	/**
	 * True when this login was authenticated by the api_key the client sent
	 * resolving to THIS account (not the name-based fallback). Lets the client
	 * confirm a freshly pasted recovery key really belongs to this account before
	 * it persists it, so a wrong or unknown key can never overwrite a working one.
	 */
	@SerializedName("authed_by_key")
	private boolean authedByKey;

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

		/**
		 * Goes up each time an admin corrects points_spent by hand. A computer that hasn't
		 * seen this number yet takes points_spent as given, even when it's lower. 0 from
		 * older servers.
		 */
		@SerializedName("spend_reset")
		private int spendReset;

		/** Spendable Boss Tokens as the server works them out. Null from older servers. */
		@SerializedName("boss_tokens")
		private Integer bossTokens;

		@SerializedName("unlocked_regions")
		private List<Integer> unlockedRegions = new ArrayList<>();

		@SerializedName("completed_tasks")
		private List<String> completedTasks = new ArrayList<>();

		/**
		 * The account's per-region task roll, stored verbatim as the plugin's own config
		 * string ("regionId:task1,task2|regionId2:task3"). The server never interprets it;
		 * round-tripping the blob lets the roll and its face-down cards survive an account
		 * switch, reinstall or new machine. Empty until a roll has synced.
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
		 * The roll's ETag: bumps when a new region is committed server-side. Stored by
		 * the client (cleared on reset) so it can tell when the committed roll changed
		 * under it (e.g. another device unlocked a chunk). 0 when no roll is committed.
		 */
		@SerializedName("roll_version")
		private long rollVersion;

		/** Backed-up partial progress per task, same format as taskProgressData. */
		@SerializedName("task_progress")
		private String taskProgress = "";

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
		 * Server-issued on purpose so it can't be self-granted from local
		 * config; defaults false, so an old or malformed response leaves the
		 * account unmarked.
		 */
		@SerializedName("is_dev")
		private boolean isDev;
	}
}
