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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The pixel maths behind dragging and clicking the overlay scrollbars. */
class ScrollbarMathTest
{
	// A 200px viewport over 1000px of content: scrolls 0..800, thumb is 40px, travels 160px.

	@Test
	void thumbSizeAndPosition()
	{
		assertEquals(40, ScrollbarMath.thumbHeight(200, 1000));
		assertEquals(20, ScrollbarMath.thumbHeight(200, 100000), "never thinner than 20px");
		assertEquals(50, ScrollbarMath.thumbY(50, 200, 1000, 0));
		assertEquals(130, ScrollbarMath.thumbY(50, 200, 1000, 400));
		assertEquals(210, ScrollbarMath.thumbY(50, 200, 1000, 800));
	}

	@Test
	void draggingMapsPixelsToScroll()
	{
		assertEquals(0, ScrollbarMath.scrollForThumbY(50, 50, 200, 1000));
		assertEquals(400, ScrollbarMath.scrollForThumbY(130, 50, 200, 1000));
		assertEquals(800, ScrollbarMath.scrollForThumbY(210, 50, 200, 1000));
	}

	@Test
	void draggingPastEitherEndClamps()
	{
		assertEquals(0, ScrollbarMath.scrollForThumbY(-500, 50, 200, 1000));
		assertEquals(800, ScrollbarMath.scrollForThumbY(5000, 50, 200, 1000));
	}

	@Test
	void draggingRoundTripsThumbPosition()
	{
		for (int scroll = 0; scroll <= 800; scroll += 40)
		{
			int y = ScrollbarMath.thumbY(0, 200, 1000, scroll);
			int back = ScrollbarMath.scrollForThumbY(y, 0, 200, 1000);
			assertEquals(y, ScrollbarMath.thumbY(0, 200, 1000, back), "scroll " + scroll);
		}
	}

	@Test
	void clickingAboveThumbPagesUp()
	{
		// A page is the viewport less 40px, so the row at the edge stays in view.
		assertEquals(240, ScrollbarMath.pageTarget(400, 60, 130, 200, 1000));
		assertEquals(0, ScrollbarMath.pageTarget(100, 60, 130, 200, 1000), "stops at the top");
	}

	@Test
	void clickingBelowThumbPagesDown()
	{
		assertEquals(560, ScrollbarMath.pageTarget(400, 240, 130, 200, 1000));
		assertEquals(800, ScrollbarMath.pageTarget(700, 240, 130, 200, 1000), "stops at the bottom");
	}

	@Test
	void shortViewportStillPages()
	{
		assertEquals(40, ScrollbarMath.pageTarget(0, 90, 0, 30, 1000), "at least one row");
	}
}
