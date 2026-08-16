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
