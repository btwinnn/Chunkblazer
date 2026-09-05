package com.chunkblazer.modules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link TobModeTracker} — the ToB raid-mode latch fed by the entry banner. */
class TobModeTrackerTest
{
	@Test
	void latchesEachModeFromTheEntryBanner()
	{
		TobModeTracker t = new TobModeTracker();
		assertEquals(TobModeTracker.Mode.UNKNOWN, t.getMode(), "starts unknown");

		t.observeChat("You enter the Theatre of Blood (Entry Mode)");
		assertEquals(TobModeTracker.Mode.ENTRY, t.getMode());
		assertTrue(t.isEntry());

		t.observeChat("You enter the Theatre of Blood (Normal Mode)");
		assertEquals(TobModeTracker.Mode.NORMAL, t.getMode());
		assertFalse(t.isEntry());

		t.observeChat("You enter the Theatre of Blood (Hard Mode)");
		assertEquals(TobModeTracker.Mode.HARD, t.getMode());
		assertFalse(t.isEntry());
	}

	@Test
	void ignoresUnrelatedMessagesAndClears()
	{
		TobModeTracker t = new TobModeTracker();
		t.observeChat("You enter the Theatre of Blood (Entry Mode)");
		t.observeChat("Oh dear, you are dead!");
		assertEquals(TobModeTracker.Mode.ENTRY, t.getMode(), "an unrelated line must not change the latched mode");
		t.observeChat(null);
		assertEquals(TobModeTracker.Mode.ENTRY, t.getMode(), "null is ignored");

		t.clear();
		assertEquals(TobModeTracker.Mode.UNKNOWN, t.getMode());
		assertFalse(t.isEntry(), "cleared (unknown) fails open");
	}
}
