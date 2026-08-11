package com.chunkblazer.api;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Response from GET /api/players/online — the live roster of ChunkBlazer
 * players whose heartbeat is fresh (within the server's window).
 *
 * Field names match the server's camelCase JSON exactly so Gson binds them
 * with no @SerializedName gymnastics. Unknown server fields (limit, offset,
 * fetchedAt) are simply ignored by Gson.
 */
@Data
public class OnlinePlayersResponse
{
	/** Players currently online (heartbeat within {@link #windowSeconds}). */
	private List<OnlinePlayer> players = new ArrayList<>();

	/** Total online count (may exceed players.size() when paginated). */
	private int total;

	/** Server-reported freshness window, in seconds. */
	private int windowSeconds;

	/**
	 * Empty response used for offline mode / failed requests.
	 */
	public static OnlinePlayersResponse empty()
	{
		return new OnlinePlayersResponse();
	}

	/**
	 * A single online player as returned by the server. {@code gameMode} and
	 * {@code rank} are nullable — a player with no locked game mode is unranked.
	 */
	@Data
	public static class OnlinePlayer
	{
		private String rsn;
		/** True if this is a ChunkBlazer dev/tester account (server is_dev flag). */
		private boolean isDev;
		private String accountType;
		private String gameMode;
		private Integer currentWorld;
		private Integer currentRegionId;
		private String lastHeartbeatAt;
		private int totalPoints;
		private Integer rank;
	}
}
