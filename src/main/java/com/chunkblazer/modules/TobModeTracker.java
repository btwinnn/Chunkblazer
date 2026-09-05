package com.chunkblazer.modules;

import javax.inject.Singleton;

/**
 * Shared, thread-safe holder for the current Theatre of Blood raid MODE, latched from the
 * "You enter the Theatre of Blood (X Mode)" chat message (a GAMEMESSAGE shown on entry).
 * ToB is the one raid with an EASIER tier than normal (Entry / Story mode), so tasks must be
 * able to refuse completion there.
 *
 * <p>RaidChallengeModule feeds this — its chat handler runs for every message, independent of
 * which tasks are active — and both it and NPCKillModule read it to gate tasks flagged
 * {@code forbid_entry_mode}. It is a plain holder (no eventBus subscription) so a single
 * {@code @Singleton} instance is shared by injection.
 *
 * <p>Mode is UNKNOWN after a mid-raid relog (the entry message does not replay). Callers treat
 * UNKNOWN as fail-open (allow), so a legitimate relog is never punished; the only residual gap
 * is relogging specifically inside an Entry raid.
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
