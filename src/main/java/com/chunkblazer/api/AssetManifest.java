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
import java.util.Map;
import lombok.Data;

/**
 * The server's media manifest ({@code /assets/manifest.json}) — the single
 * mutable entry point into the asset system. The plugin may only fetch asset
 * paths that appear in a manifest it has already downloaded; it never builds an
 * asset url from game data. That rule bounds the set of fetchable urls to a
 * finite, known list and is the structural fix for the "plugin pulls unbounded
 * media and bricks itself" failure mode.
 *
 * <p>Shape mirrors {@code build-audio-assets.ps1} output:
 * <pre>
 * { "schema":1, "profile":"mono/22050/pcm_mulaw+trim",
 *   "audio": { "Misthalin_Sounds": [ {AudioAsset}, ... ], ... } }
 * </pre>
 */
@Data
public class AssetManifest
{
	private int schema;
	private String profile;

	/** Area folder name (e.g. {@code Misthalin_Sounds}) mapped to its available jingles. */
	private Map<String, List<AudioAsset>> audio;
}
