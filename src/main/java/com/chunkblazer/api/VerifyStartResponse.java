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

/**
 * Response from POST /api/player/verify/start. The server either:
 *   - says the player is already verified (alreadyVerified=true, rest null)
 *   - hands out a 6-char nonce + the exact chat phrase to type
 *
 * The chatPhrase is "chunkblazer ABC123" — the plugin's chat listener watches
 * for the local player saying exactly this in public chat, then POSTs verify.
 */
@Data
public class VerifyStartResponse
{
	@SerializedName("alreadyVerified")
	private boolean alreadyVerified;

	private String nonce;

	@SerializedName("expiresAt")
	private String expiresAt;

	@SerializedName("chatPhrase")
	private String chatPhrase;

	/**
	 * Offline fallback used when the API is disabled or unreachable.
	 */
	public static VerifyStartResponse offline()
	{
		VerifyStartResponse r = new VerifyStartResponse();
		r.setAlreadyVerified(false);
		return r;
	}
}
