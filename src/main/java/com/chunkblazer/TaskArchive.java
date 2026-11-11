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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Tasks the player has archived. They still make progress if done, but they're hidden
 * from the Active and Saved tabs, outlines and the right-click Tasks menu. Stored per
 * account. Shared so the task window, task box and highlighter agree; also keeps the
 * saved (starred) list.
 */
@Singleton
public class TaskArchive
{
	private static final String CONFIG_GROUP = "chunkblazer";
	private static final String ARCHIVE_KEY = "archivedTasks";
	private static final String SAVED_KEY = "savedTasks";

	private final ConfigManager configManager;

	// Parsed form of each stored list, rebuilt only when the stored string changes.
	// The highlighter asks about every task each tick, so re-splitting would be waste.
	private final Map<String, String> cachedRaw = new HashMap<>();
	private final Map<String, Set<String>> cachedIds = new HashMap<>();

	@Inject
	TaskArchive(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/** Archived task ids for the current account (empty when no account is loaded). */
	public Set<String> ids()
	{
		return read(ARCHIVE_KEY);
	}

	/** Saved (starred) task ids for the current account, in the order they were saved. */
	public Set<String> savedIds()
	{
		return read(SAVED_KEY);
	}

	/** Save the task, or unsave it if it's saved. */
	public void toggleSaved(String taskId)
	{
		write(SAVED_KEY, taskId, !savedIds().contains(taskId));
	}

	private synchronized Set<String> read(String key)
	{
		String raw = configManager.getRSProfileConfiguration(CONFIG_GROUP, key);
		if (raw == null)
		{
			raw = "";
		}
		if (!raw.equals(cachedRaw.get(key)))
		{
			Set<String> parsed = new LinkedHashSet<>();
			for (String id : raw.split(","))
			{
				if (!id.trim().isEmpty())
				{
					parsed.add(id.trim());
				}
			}
			cachedIds.put(key, Collections.unmodifiableSet(parsed));
			cachedRaw.put(key, raw);
		}
		return cachedIds.get(key);
	}

	public boolean isArchived(NuzlockeTask task)
	{
		return task != null && task.getTaskId() != null && ids().contains(task.getTaskId());
	}

	public void archive(String taskId)
	{
		write(ARCHIVE_KEY, taskId, true);
	}

	public void toggle(String taskId)
	{
		write(ARCHIVE_KEY, taskId, !ids().contains(taskId));
	}

	private synchronized void write(String key, String taskId, boolean add)
	{
		if (taskId == null || configManager.getRSProfileKey() == null)
		{
			return;
		}
		Set<String> updated = new LinkedHashSet<>(read(key));
		boolean changed = add ? updated.add(taskId) : updated.remove(taskId);
		if (changed)
		{
			configManager.setRSProfileConfiguration(CONFIG_GROUP, key, String.join(",", updated));
		}
	}
}
