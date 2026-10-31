/*
 * Copyright (c) 2026, Vani-Lab
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Region Locker GPU greys out the chunks Region Locker doesn't list as unlocked. While this
 * account plays ChunkBlazer, its unlocked chunks are written into Region Locker's list, and
 * Region Locker's own map overlay, grid, ids and borders are turned off so they don't cover
 * ChunkBlazer's. The player's own Region Locker settings are kept first and put back when
 * ChunkBlazer stops (or the account isn't a ChunkBlazer one). Nothing else depends on it.
 */
@Singleton
class RegionLockerSync
{
	private static final String GROUP = "regionlocker";
	private static final String CONFIG_GROUP = "chunkblazer";
	private static final String BACKUP = "regionLockerBackup";
	// Tutorial Island's Gielinor Guide's house: kept clear while the tutorial shows the island
	// (Region Locker greys whole chunks only).
	private static final int START_ROOM = 12336;
	private static final int[] ISLAND = {12079, 12080, 12335, 12336, 12436, 12592};
	// Region Locker's settings this changes, and the values it sets them to.
	private static final Map<String, String> SETTINGS = new LinkedHashMap<>();

	static
	{
		SETTINGS.put("unlockableRegions", "");
		SETTINGS.put("blacklistedRegions", "");
		SETTINGS.put("drawMapOverlay", "false");
		SETTINGS.put("drawMapGrid", "false");
		SETTINGS.put("drawRegionId", "false");
		SETTINGS.put("renderRegionBorders", "false");
		SETTINGS.put("unlockUnderground", "true");
		SETTINGS.put("renderLockedRegions", "true");
	}

	private final Client client;
	private final ConfigManager configManager;
	private final ChunkBlazerPlugin plugin;
	private final EventBus eventBus;
	@Inject
	private CompanionPrompt plugins;

	@Inject
	RegionLockerSync(Client client, ConfigManager configManager, ChunkBlazerPlugin plugin, EventBus eventBus)
	{
		this.client = client;
		this.configManager = configManager;
		this.plugin = plugin;
		this.eventBus = eventBus;
	}

	void start()
	{
		eventBus.register(this);
	}

	void stop()
	{
		eventBus.unregister(this);
		restore();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		boolean island = plugin.isTutorialPreview();
		if (!plugins.isInstalled("Region Locker"))
		{
			return;
		}
		if (!island && !plugin.isModeLocked())
		{
			// No ChunkBlazer mode on this account (yet): the player's own Region Locker settings.
			restore();
			return;
		}
		takeOver();
		Set<Integer> open = new TreeSet<>();
		if (island)
		{
			open.add(START_ROOM);
			for (int region : ISLAND)
			{
				if (plugin.isPracticeUnlocked(region))
				{
					open.add(region);
				}
			}
		}
		else
		{
			for (String id : plugin.unlockedRegionIdsView())
			{
				try
				{
					open.add(Integer.parseInt(id.trim()));
				}
				catch (NumberFormatException ignored)
				{
					// Not a region id.
				}
			}
		}
		StringBuilder csv = new StringBuilder();
		for (int region : open)
		{
			csv.append(csv.length() == 0 ? "" : ",").append(region);
		}
		set("unlockedRegions", csv.toString());
	}

	/** The first time: keep the player's own Region Locker settings, then set ours. */
	private void takeOver()
	{
		if (configManager.getConfiguration(CONFIG_GROUP, BACKUP) == null)
		{
			StringBuilder saved = new StringBuilder();
			for (String key : keys())
			{
				String value = configManager.getConfiguration(GROUP, key);
				saved.append(key).append('=').append(value == null ? "" : value.replace("\n", " ")).append('\n');
			}
			configManager.setConfiguration(CONFIG_GROUP, BACKUP, saved.toString());
		}
		for (Map.Entry<String, String> setting : SETTINGS.entrySet())
		{
			set(setting.getKey(), setting.getValue());
		}
	}

	/** The player's own Region Locker settings back, if they were changed. */
	private void restore()
	{
		String saved = configManager.getConfiguration(CONFIG_GROUP, BACKUP);
		if (saved == null)
		{
			return;
		}
		for (String line : saved.split("\n"))
		{
			int eq = line.indexOf('=');
			if (eq > 0)
			{
				set(line.substring(0, eq), line.substring(eq + 1));
			}
		}
		configManager.unsetConfiguration(CONFIG_GROUP, BACKUP);
	}

	private static Set<String> keys()
	{
		Set<String> keys = new TreeSet<>(SETTINGS.keySet());
		keys.add("unlockedRegions");
		return keys;
	}

	/** Only written when it changes: each write makes Region Locker read its settings again. */
	private void set(String key, String value)
	{
		if (!value.equals(configManager.getConfiguration(GROUP, key)))
		{
			configManager.setConfiguration(GROUP, key, value);
		}
	}
}
