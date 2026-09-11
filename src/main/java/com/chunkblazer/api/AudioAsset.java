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

import lombok.Data;

/**
 * One servable audio asset as listed in the server's asset manifest.
 *
 * <p>The {@code path} is content-addressed ({@code assets/audio/<sha256[:12]>/name}),
 * which is what makes the whole cache safe: the url IS the identity, so a
 * changed asset is a new url and every downloaded file can be trusted forever
 * once its bytes hash to the {@code sha256} recorded here. See
 * {@code Chunkblazer-Server/docs/MEDIA-PIPELINE-PLAN.md}.
 */
@Data
public class AudioAsset
{
	/** Original file name, e.g. {@code Quest_Complete_1.wav}. */
	private String name;

	/** /assets-rooted url path, e.g. {@code assets/audio/ab12cd34ef56/Quest_Complete_1.wav}. */
	private String path;

	/** Full SHA-256 of the served bytes. The cache verifies against this before trusting a download. */
	private String sha256;

	/** Size of the served bytes, for budgeting/telemetry. */
	private long bytes;
}
