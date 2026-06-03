package net.runelite.client.plugins.chunkblazer;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Visual style the ChunkBlazer GPU plugin applies to locked chunks.
 *
 * <p>The enum {@code ordinal()} is passed verbatim into the shader uniform
 * {@code chunkblazer_shadingLevel} (see chunkblazer/frag.glsl), so the
 * declaration order here is load-bearing: 0 = LIGHT, 1 = HEAVY, 2 = SILHOUETTE.
 * If you reorder these, update the branches in chunkblazer/frag.glsl to match.
 */
@Getter
@AllArgsConstructor
public enum ShadingLevel
{
	/** Partial desaturation + soft-light tint — the original subtle wash. */
	LIGHT("Light wash"),
	/** Full desaturation, darkened and tinted — clearly "off limits". */
	HEAVY("Heavy shade"),
	/** Near-black; only baked terrain relief (hills, cliffs, mountains) shows. */
	SILHOUETTE("Silhouette");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
