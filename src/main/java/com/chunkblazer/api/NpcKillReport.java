/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer.api;

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
