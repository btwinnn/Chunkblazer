package net.runelite.client.plugins.chunkblazer.api;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Report for NPC kill events sent to server for verification.
 */
@Data
@Builder
public class NpcKillReport
{
	/** Player RSN hash */
	private String playerHash;

	/** Task ID this kill is for */
	private String taskId;

	/** NPC ID that was killed */
	private int npcId;

	/** NPC name */
	private String npcName;

	/** NPC combat level */
	private int npcCombatLevel;

	/** World location where kill occurred */
	private int worldX;
	private int worldY;
	private int plane;

	/** Region ID where kill occurred */
	private int regionId;

	/** Game tick when kill occurred */
	private int gameTick;

	/** Client timestamp */
	private long timestamp;

	/** Player's combat level at time of kill */
	private int playerCombatLevel;

	/** Player's current HP after kill */
	private int playerCurrentHp;

	/** Equipment worn during kill (item IDs) */
	private List<Integer> equipmentIds;

	/** Items received from the kill (loot) */
	private List<LootItem> lootReceived;

	/** Animation ID of the killing blow (for verification) */
	private int killingBlowAnimationId;

	/** How much damage player dealt (if tracked) */
	private int damageDealt;

	@Data
	@Builder
	public static class LootItem
	{
		private int itemId;
		private int quantity;
	}
}
