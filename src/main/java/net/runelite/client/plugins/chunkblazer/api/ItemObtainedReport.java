package net.runelite.client.plugins.chunkblazer.api;

import lombok.Builder;
import lombok.Data;

/**
 * Report for item obtained events sent to server for verification.
 */
@Data
@Builder
public class ItemObtainedReport
{
	/** Player RSN hash */
	private String playerHash;

	/** Task ID this item is for */
	private String taskId;

	/** Item ID obtained */
	private int itemId;

	/** Item name */
	private String itemName;

	/** Quantity obtained */
	private int quantity;

	/** Source of the item (NPC_DROP, GROUND_SPAWN, SKILLING, SHOP, etc.) */
	private String source;

	/** Source entity ID (NPC ID if from NPC, object ID if from skilling, etc.) */
	private int sourceId;

	/** Region ID where item was obtained */
	private int regionId;

	/** World location */
	private int worldX;
	private int worldY;
	private int plane;

	/** Game tick when obtained */
	private int gameTick;

	/** Client timestamp */
	private long timestamp;

	/** GE value of item (for tracking) */
	private int geValue;

	/** Whether item went to inventory or bank */
	private String destination;
}
