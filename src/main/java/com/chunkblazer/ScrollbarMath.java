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

/** The pixel maths for the overlay's scrollbars: thumb size and place, dragging, and track clicks. */
final class ScrollbarMath
{
	private ScrollbarMath()
	{
	}

	// A track click pages by a viewport less this, so the row at the edge stays in view.
	private static final int PAGE_OVERLAP = 40;
	private static final int MIN_PAGE = 40;

	static int thumbHeight(int viewport, int content)
	{
		return Math.max(20, viewport * viewport / content);
	}

	static int thumbY(int areaY, int viewport, int content, int scroll)
	{
		return areaY + (viewport - thumbHeight(viewport, content)) * scroll / Math.max(1, content - viewport);
	}

	/** The scroll offset for a thumb whose top is at {@code thumbY}, clamped to the content. */
	static int scrollForThumbY(int thumbY, int areaY, int viewport, int content)
	{
		int travel = Math.max(1, viewport - thumbHeight(viewport, content));
		long scroll = (long) (thumbY - areaY) * (content - viewport) / travel;
		return (int) Math.max(0, Math.min(scroll, content - viewport));
	}

	/** Where a click on the track at {@code clickY} scrolls to: a page up or down, toward the click. */
	static int pageTarget(int scroll, int clickY, int thumbY, int viewport, int content)
	{
		int page = Math.max(MIN_PAGE, viewport - PAGE_OVERLAP);
		int target = clickY < thumbY ? scroll - page : scroll + page;
		return Math.max(0, Math.min(target, content - viewport));
	}
}
