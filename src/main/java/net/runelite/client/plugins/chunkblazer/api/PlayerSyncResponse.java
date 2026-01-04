package net.runelite.client.plugins.chunkblazer.api;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from player sync API.
 * Contains server-authoritative state that should override client state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerSyncResponse
{
	/** Whether sync was successful */
	private boolean success;

	/** Server-authoritative points */
	private Integer serverPoints;

	/** Server-authoritative unlocked regions */
	private List<Integer> serverUnlockedRegions;

	/** Server-authoritative completed tasks */
	private List<String> serverCompletedTasks;

	/** Current active task from server */
	private ServerTask activeTask;

	/** Any messages from server (announcements, warnings) */
	private List<String> messages;

	/** Whether account is flagged for suspicious activity */
	private boolean flagged;

	/** Flag reason if flagged */
	private String flagReason;

	/** Leaderboard rank (if in nuzlocke mode) */
	private Integer leaderboardRank;

	/** Server timestamp */
	private long serverTimestamp;

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ServerTask
	{
		private String taskId;
		private String name;
		private int progress;
		private int target;
		private int basePoints;
	}
}
