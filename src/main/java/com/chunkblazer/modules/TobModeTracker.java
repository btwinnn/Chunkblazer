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

package com.chunkblazer.modules;

import javax.inject.Singleton;

/**
 * Shared, thread-safe holder for the current Theatre of Blood raid MODE, latched from the
 * "You enter the Theatre of Blood (X Mode)" GAMEMESSAGE, so tasks can refuse completion
 * in Entry / Story mode.
 *
 * <p>RaidChallengeModule feeds this, as its chat handler runs whichever tasks are active.
 * It and NPCKillModule read it to gate {@code forbid_entry_mode} tasks. No eventBus
 * subscription, so one {@code @Singleton} instance is shared by injection.
 *
 * <p>Mode is UNKNOWN after a mid-raid relog (the entry message does not replay). Callers
 * fail open (allow).
 */
@Singleton
public class TobModeTracker
{
	public enum Mode
	{
		UNKNOWN, ENTRY, NORMAL, HARD
	}

	private volatile Mode mode = Mode.UNKNOWN;

	/**
	 * Latch the mode from a ToB entry chat line; ignores every unrelated message. Safe to call
	 * on every chat message — the substring is distinctive to the raid-entry banner.
	 */
	public void observeChat(String message)
	{
		if (message == null)
		{
			return;
		}
		String m = message.toLowerCase();
		if (!m.contains("you enter the theatre of blood"))
		{
			return;
		}
		if (m.contains("(entry mode)"))
		{
			mode = Mode.ENTRY;
		}
		else if (m.contains("(normal mode)"))
		{
			mode = Mode.NORMAL;
		}
		else if (m.contains("(hard mode)"))
		{
			mode = Mode.HARD;
		}
	}

	public Mode getMode()
	{
		return mode;
	}

	/** True only when we KNOW the current raid is Entry mode. UNKNOWN returns false (fail-open). */
	public boolean isEntry()
	{
		return mode == Mode.ENTRY;
	}

	/** Reset to UNKNOWN — e.g. on logout, where the next raid must re-announce its mode. */
	public void clear()
	{
		mode = Mode.UNKNOWN;
	}
}
