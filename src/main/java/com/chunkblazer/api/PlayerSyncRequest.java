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
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * Request to sync player state with server.
 * Sent on login and periodically to ensure state consistency.
 */
@Data
@Builder
public class PlayerSyncRequest
{
	/** Player RSN hash */
	private String playerHash;

	/** SHA-256 of RuneLite's per-account id; the server refuses a sync from a different account. */
	private String accountHash;

	/** Current RSN (for display, not verification) */
	private String displayName;

	/** The game's account-type value (VarbitID.IRONMAN); 4-6 are group ironmen. */
	private String accountType;

	/** ChunkBlazer game mode (CASUAL, NUZLOCKE) */
	private String gameMode;

	/** Combat level */
	private int combatLevel;

	/** Total level */
	private int totalLevel;

	/** All skill levels */
	private Map<String, Integer> skillLevels;

	/** All skill XP */
	private Map<String, Integer> skillXp;

	/** Current region ID */
	private int currentRegionId;

	/** List of unlocked region IDs */
	private List<Integer> unlockedRegions;

	/** Current active task ID */
	private String activeTaskId;

	/** Current task progress */
	private int activeTaskProgress;

	/** Client-side points (for verification) */
	private int clientPoints;

	/**
	 * Points spent unlocking chunks. Mirrored to the server purely so it survives
	 * a reinstall / new profile — the server does not spend anything itself. Its
	 * total_points remains lifetime EARNED; the spendable balance is derived on
	 * the client as earned minus this.
	 */
	private int pointsSpent;

	/** All task IDs the player has completed, in completion order (overwrite-sync on server). */
	private List<String> completedTasks;

	/** Tells the server {@link #completedTasks} is in completion order (older clients sent hash order). */
	private boolean completedOrdered;

	/** Rolled target quantity per completed task (targets above 1), each sent once in batches. */
	private Map<String, Integer> completedTargets;

	/**
	 * The per-region task roll, sent verbatim as the plugin's config string
	 * ("regionId:task1,task2|regionId2:task3"). The server stores it as an opaque
	 * blob (it grants nothing — points and completions are verified separately) so
	 * the roll and the face-down card state survive a profile switch / reinstall,
	 * instead of being regenerated en masse on the next login.
	 */
	private String regionRolledTasks;

	/** Still-face-down reveal cards, sent verbatim ("task1,task2"). Round-tripped with {@link #regionRolledTasks}. */
	private String unrevealedTasks;

	/**
	 * Partial progress per task ("taskId:progress:target,..."), backed up so a crash
	 * or a second device doesn't lose it. The server keeps the higher value per task.
	 */
	private String taskProgress;

	/**
	 * Boss/raid keys the player has completed at least once (e.g. "toa"). Additive
	 * on the server (never a wholesale replace); it recomputes earned Boss Tokens
	 * from the union. See docs/BOSS-CHUNKS.md.
	 */
	private List<String> bossCompletions;

	/**
	 * Declares that this sync is MEANT to destroy progress. The server refuses a sync that
	 * drops a large share of a player's chunks or tasks, and a deliberate reset looks the
	 * same by shape.
	 *
	 * <p>Only set by an operation the PLAYER asked for. Never set it to get a rejected sync
	 * through: the rejection is the guard working.
	 */
	private boolean intentionalReset;

	/** Client timestamp */
	private long timestamp;

	/** Client version */
	private String clientVersion;
}
