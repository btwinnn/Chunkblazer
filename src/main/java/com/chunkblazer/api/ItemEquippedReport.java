package com.chunkblazer.api;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Report for item equipped events sent to server for verification.
 * Contains comprehensive data for server-side anti-cheat validation.
 */
@Data
@Builder
public class ItemEquippedReport
{
	/** Player RSN hash (SHA-256, first 16 chars) */
	private String playerHash;

	/** Task ID this equipment event is for */
	private String taskId;

	/** Item ID that was equipped */
	private int itemId;

	/** Item name for logging/display */
	private String itemName;

	/** Equipment slot index where item was equipped (see EquipmentInventorySlot) */
	private int equipmentSlot;

	/** Equipment slot name (HEAD, WEAPON, etc.) for readability */
	private String equipmentSlotName;

	/** Region ID where item was equipped */
	private int regionId;

	/** World location when equipped */
	private int worldX;
	private int worldY;
	private int plane;

	/** Game tick when equipped */
	private int gameTick;

	/** Client timestamp (milliseconds since epoch) */
	private long timestamp;

	/** GE value of equipped item */
	private int geValue;

	/** Player's current combat level */
	private int playerCombatLevel;

	// ==================== SKILL LEVEL VERIFICATION ====================
	// These are tracked for server-side level requirement validation

	/** Player's Attack level (for weapon requirements) */
	private int attackLevel;

	/** Player's Strength level (for some weapon requirements) */
	private int strengthLevel;

	/** Player's Defence level (for armor requirements) */
	private int defenceLevel;

	/** Player's Ranged level (for ranged equipment) */
	private int rangedLevel;

	/** Player's Magic level (for magic equipment) */
	private int magicLevel;

	/** Player's Prayer level (for prayer equipment) */
	private int prayerLevel;

	// ==================== EQUIPMENT STATE VERIFICATION ====================

	/** All currently equipped item IDs (full equipment snapshot) */
	private List<Integer> allEquippedItemIds;

	/** Item ID that was previously in this slot (for swap detection) */
	private int previousItemInSlot;

	/** Whether this was an equip from inventory (true) or a swap (false) */
	private boolean fromInventory;

	// ==================== CONSTRAINT TRACKING ====================

	/** Whether task has level requirements that need server verification */
	private boolean hasLevelRequirements;

	/** The required level from task constraints (if any) */
	private int requiredLevel;

	/** The skill name for level requirement (Attack, Defence, etc.) */
	private String requiredSkill;

	// ==================== ANTI-CHEAT FLAGS ====================

	/** Hash of the inventory state before equipping */
	private String inventoryStateHash;

	/** Whether the item was already in the player's inventory before equip */
	private boolean itemWasInInventory;

	/** World type (members, f2p, pvp, etc.) */
	private int worldType;
}
