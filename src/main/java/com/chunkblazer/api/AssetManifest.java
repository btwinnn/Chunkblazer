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

	/** area folder name (e.g. {@code Misthalin_Sounds}) -> its available jingles. */
	private Map<String, List<AudioAsset>> audio;
}
