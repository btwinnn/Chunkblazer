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

package com.chunkblazer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import com.chunkblazer.api.OnlinePlayersResponse;
import net.runelite.client.util.Text;

/**
 * In-memory index of currently-online ChunkBlazer players, refreshed
 * periodically from GET /api/players/online.
 *
 * Keyed by {@link Text#standardize(String)} so a name coming off a chat event
 * (which may carry icon tags and non-breaking spaces) and the plain in-scene
 * player name both resolve to the same entry — the same normalization that
 * fixed the verification handshake.
 *
 * Thread model: written from the background poll thread, read from the client /
 * overlay render threads. The backing map is immutable and swapped atomically
 * through a volatile reference, so readers never see a half-built map and need
 * no locking.
 */
@Singleton
public class ChunkBlazerRoster
{
	private volatile Map<String, Entry> index = Collections.emptyMap();

	/**
	 * Replace the roster with the players from a fresh server response.
	 */
	public void update(OnlinePlayersResponse response)
	{
		if (response == null || response.getPlayers() == null)
		{
			return;
		}
		Map<String, Entry> next = new HashMap<>();
		for (OnlinePlayersResponse.OnlinePlayer p : response.getPlayers())
		{
			if (p == null || p.getRsn() == null)
			{
				continue;
			}
			String key = Text.standardize(p.getRsn());
			if (key.isEmpty())
			{
				continue;
			}
			next.put(key, Entry.from(p));
		}
		index = Collections.unmodifiableMap(next);
	}

	/**
	 * Drop all entries (e.g. on logout or plugin shutdown).
	 */
	public void clear()
	{
		index = Collections.emptyMap();
	}

	/**
	 * @return true if {@code rsn} belongs to an online ChunkBlazer player.
	 */
	public boolean isMember(String rsn)
	{
		return rsn != null && index.containsKey(Text.standardize(rsn));
	}

	/**
	 * @return the roster entry for {@code rsn}, or null if not a known player.
	 */
	public Entry get(String rsn)
	{
		return rsn == null ? null : index.get(Text.standardize(rsn));
	}

	public Collection<Entry> all()
	{
		return index.values();
	}

	public int size()
	{
		return index.size();
	}

	public boolean isEmpty()
	{
		return index.isEmpty();
	}

	/**
	 * Short, human display label for a server account_type string.
	 */
	public static String formatAccountType(String accountType)
	{
		if (accountType == null)
		{
			return "";
		}
		switch (accountType)
		{
			case "STANDARD":
				return "Main";
			case "IRONMAN":
				return "Ironman";
			case "HCIM":
				return "HC Ironman";
			case "UIM":
				return "UIM";
			case "SKILLER_3":
				return "Skiller";
			default:
				return accountType;
		}
	}

	/**
	 * Immutable snapshot of one online ChunkBlazer player.
	 */
	@Getter
	@RequiredArgsConstructor
	public static class Entry
	{
		private final String rsn;
		private final String accountType;
		/** Locked game mode, or null if the player hasn't picked one. */
		private final GameMode gameMode;
		private final int totalPoints;
		/** Leaderboard rank within the player's bucket; 0 == unranked. */
		private final int rank;
		/** True for ChunkBlazer dev/tester accounts (drives the [Dev] recognition tag). */
		private final boolean dev;

		static Entry from(OnlinePlayersResponse.OnlinePlayer p)
		{
			GameMode mode = null;
			if (p.getGameMode() != null)
			{
				try
				{
					mode = GameMode.valueOf(p.getGameMode());
				}
				catch (IllegalArgumentException ignored)
				{
					// Unknown / future mode — leave null (treated as unranked).
				}
			}
			int rank = p.getRank() != null ? p.getRank() : 0;
			return new Entry(p.getRsn(), p.getAccountType(), mode, p.getTotalPoints(), rank, p.isDev());
		}

		/**
		 * @return short display label for this player's account type.
		 */
		public String getAccountLabel()
		{
			return formatAccountType(accountType);
		}
	}
}
