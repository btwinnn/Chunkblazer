package com.chunkblazer.api;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * Request payload for task verification.
 */
@Data
@Builder
public class TaskVerificationRequest
{
	/** Player's RSN (hashed for privacy) */
	private String playerHash;

	/** The task ID being verified */
	private String taskId;

	/** Completion type (NPC_KILL, SKILL_LEVEL, ITEM_OBTAIN, etc.) */
	private String completionType;

	/** Current progress towards the task */
	private int currentProgress;

	/** Target quantity required */
	private int targetQuantity;

	/** Region ID where the event occurred */
	private int regionId;

	/** Timestamp of the event (client-side) */
	private long timestamp;

	/** Game tick when event occurred */
	private int gameTick;

	/** Additional evidence/context for verification */
	private Map<String, Object> evidence;

	/** Client-generated signature for anti-tamper */
	private String clientSignature;
}
