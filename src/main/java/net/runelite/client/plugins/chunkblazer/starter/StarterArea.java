package net.runelite.client.plugins.chunkblazer.starter;

/**
 * Single source of truth for the ChunkBlazer starter area.
 *
 * <p>The starter area is a 3x3 grid of OSRS regions centered on Lumbridge.
 * Only the {@link #CENTER} chunk is auto-unlocked at game start; the other
 * eight are visible on the world map but locked behind unlock costs, so a
 * new player can immediately spend earned points to expand outward.
 *
 * <p>OSRS region IDs are encoded as {@code (regionX << 8) | regionY}, where
 * regionX = tileX/64 and regionY = tileY/64. Lumbridge sits at (50, 50) =
 * 12850, so the surrounding 3x3 grid is:
 *
 * <pre>
 *   NW (49,51)=12595   N (50,51)=12851   NE (51,51)=13107
 *   W  (49,50)=12594   C (50,50)=12850   E  (51,50)=13106
 *   SW (49,49)=12593   S (50,49)=12849   SE (51,49)=13105
 * </pre>
 *
 * <p><b>Auto-unlock vs. scope:</b> {@link #CENTER} is the single chunk
 * unlocked for free at game start. {@link #REGIONS} is the full 3x3 scope —
 * what the JSON defines and what the UI shows on the world map. The eight
 * non-center regions are locked at start; players unlock them with points
 * earned by completing tasks in already-unlocked chunks.
 *
 * <p><b>Maintenance rule:</b> every place that needs the starter region set
 * must read from this class. Do not duplicate region IDs in
 * {@code ChunkBlazerPlugin}. Do not auto-unlock any region other than
 * {@link #CENTER} without changing this class first.
 *
 * <p>The matching chunk and task definitions live in
 * {@code Starter_Area_Tasks.json}; if you add or remove a region here you
 * must also update that JSON (and vice versa).
 */
public final class StarterArea
{
	/** Lumbridge center region — the canonical "free starting chunk". */
	public static final int CENTER = 12850;

	/**
	 * The full 3x3 grid of starter regions. Order is row-major top-to-bottom,
	 * left-to-right (NW, N, NE, W, C, E, SW, S, SE) so the array reads like
	 * the grid drawing in the class doc.
	 */
	public static final int[] REGIONS = {
		12595, 12851, 13107,
		12594, 12850, 13106,
		12593, 12849, 13105,
	};

	private StarterArea()
	{
	}

	/**
	 * @return true if {@code regionId} is one of the 9 starter regions.
	 */
	public static boolean contains(int regionId)
	{
		for (int r : REGIONS)
		{
			if (r == regionId)
			{
				return true;
			}
		}
		return false;
	}

}
