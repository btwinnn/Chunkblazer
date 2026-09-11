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

/**
 * Response from the server when locking a player's game mode.
 */
@Data
public class LockModeResponse
{
	/**
	 * Status: "ok" or "error"
	 */
	private String status;

	/**
	 * Error code if status is "error" (e.g., "MODE_ALREADY_LOCKED")
	 */
	private String error;

	/**
	 * Human-readable message
	 */
	private String message;

	/**
	 * The locked game mode
	 */
	@SerializedName("game_mode")
	private String gameMode;

	/**
	 * Whether mode is now locked
	 */
	@SerializedName("mode_locked")
	private boolean modeLocked;

	/**
	 * Timestamp when mode was locked
	 */
	@SerializedName("locked_at")
	private String lockedAt;

	/**
	 * Check if the lock was successful.
	 */
	public boolean isSuccess()
	{
		return "ok".equals(status);
	}

	/**
	 * Check if the mode was already locked.
	 */
	public boolean isAlreadyLocked()
	{
		return "MODE_ALREADY_LOCKED".equals(error);
	}

	/**
	 * Get the GameMode enum value.
	 */
	public GameMode getGameModeEnum()
	{
		if (gameMode == null)
		{
			return null;
		}
		try
		{
			return GameMode.valueOf(gameMode);
		}
		catch (IllegalArgumentException e)
		{
			return GameMode.CASUAL;
		}
	}

	/**
	 * Create an offline response for when API is disabled.
	 */
	public static LockModeResponse offline(GameMode mode)
	{
		LockModeResponse response = new LockModeResponse();
		response.setStatus("offline");
		response.setGameMode(mode.name());
		response.setModeLocked(true);
		response.setMessage("Mode locked locally (API offline)");
		return response;
	}

	/**
	 * Create an error response.
	 */
	public static LockModeResponse error(String message)
	{
		LockModeResponse response = new LockModeResponse();
		response.setStatus("error");
		response.setError("CLIENT_ERROR");
		response.setMessage(message);
		return response;
	}
}
