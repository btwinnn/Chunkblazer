package com.chunkblazer;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card row layout. A roll is 4-5 tasks and the client window is whatever size the player
 * made it, so the arrangement has to stay readable across both — five in a line at full
 * card size wants ~1250px, which most windows don't have.
 */
class TaskCardLayoutTest
{
	/** A comfortable resizable client. */
	private static final int WIDE = 1240;
	/** Fixed-mode viewport, the tightest real case. */
	private static final int FIXED = 512 - 40;

	@Test
	void fiveCardsSplitThreeThenTwo()
	{
		assertArrayEquals(new int[]{3, 2}, TaskCardOverlay.rowSizes(5, WIDE),
			"a five-task roll lays out 3+2");
	}

	@Test
	void fourCardsSplitEvenly()
	{
		assertArrayEquals(new int[]{2, 2}, TaskCardOverlay.rowSizes(4, WIDE),
			"four cards balance as 2+2, never 3+1");
	}

	@Test
	void threeOrFewerStayOnOneRow()
	{
		assertArrayEquals(new int[]{3}, TaskCardOverlay.rowSizes(3, WIDE));
		assertArrayEquals(new int[]{2}, TaskCardOverlay.rowSizes(2, WIDE));
		assertArrayEquals(new int[]{1}, TaskCardOverlay.rowSizes(1, WIDE));
	}

	@Test
	void neverExceedsThreeAcrossEvenOnAHugeViewport()
	{
		// The cap is about card SIZE, not available space: given room for five the
		// layout still prefers two big rows over one row of five.
		for (int size : TaskCardOverlay.rowSizes(5, 4000))
		{
			assertTrue(size <= 3);
		}
	}

	/**
	 * Cards accumulate if a second chunk is unlocked before the first set is dismissed,
	 * so counts above a single roll are reachable in normal play — and seven laid out
	 * 3+3+1 leaves a stranded single that reads as a bug.
	 */
	@Test
	void remainderSpreadsAcrossRowsInsteadOfStranding()
	{
		assertArrayEquals(new int[]{3, 2, 2}, TaskCardOverlay.rowSizes(7, WIDE));
		assertArrayEquals(new int[]{3, 3}, TaskCardOverlay.rowSizes(6, WIDE));
		assertArrayEquals(new int[]{3, 3, 2}, TaskCardOverlay.rowSizes(8, WIDE));
		assertArrayEquals(new int[]{3, 3, 3}, TaskCardOverlay.rowSizes(9, WIDE));
	}

	@Test
	void narrowViewportsNarrowFurtherRatherThanShrinkCards()
	{
		int[] sizes = TaskCardOverlay.rowSizes(5, FIXED);
		// 472px of usable width can hold at most two 150px cards plus a gap.
		for (int size : sizes)
		{
			assertTrue(size <= 2,
				"at fixed-mode width, three across would be illegible: " + Arrays.toString(sizes));
			assertTrue(size >= 1);
		}
		assertEquals(5, total(sizes), "every card must be placed");
	}

	@Test
	void degeneratesSafelyOnAbsurdInputs()
	{
		// layout() divides by the row width, so none of these may produce a zero.
		assertArrayEquals(new int[]{0}, TaskCardOverlay.rowSizes(0, WIDE));
		assertArrayEquals(new int[]{0}, TaskCardOverlay.rowSizes(-1, WIDE));
		for (int size : TaskCardOverlay.rowSizes(5, 0))
		{
			assertTrue(size >= 1);
		}
		for (int size : TaskCardOverlay.rowSizes(5, -100))
		{
			assertTrue(size >= 1);
		}
	}

	@Test
	void everyCardIsPlacedAndNoRowIsLopsided()
	{
		for (int count = 1; count <= 12; count++)
		{
			int[] sizes = TaskCardOverlay.rowSizes(count, WIDE);
			assertEquals(count, total(sizes),
				"count " + count + " lost or duplicated cards: " + Arrays.toString(sizes));

			int min = Integer.MAX_VALUE;
			int max = 0;
			for (int size : sizes)
			{
				min = Math.min(min, size);
				max = Math.max(max, size);
			}
			assertTrue(max - min <= 1,
				"count " + count + " is unbalanced: " + Arrays.toString(sizes));
		}
	}

	private static int total(int[] sizes)
	{
		int sum = 0;
		for (int size : sizes)
		{
			sum += size;
		}
		return sum;
	}
}
