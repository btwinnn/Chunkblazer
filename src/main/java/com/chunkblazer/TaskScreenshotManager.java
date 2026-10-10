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

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageCapture;

/**
 * Saves a screenshot when tasks complete, through RuneLite's {@link ImageCapture}
 * (the same path the core Screenshot plugin uses). Files land in
 * {@code .runelite/screenshots/<RSN>/ChunkBlazer/}, named after the task, with
 * ImageCapture appending the timestamp.
 *
 * <p>One screenshot per completion batch: a boss-chunk grant can settle a whole
 * stack of tasks in one tick, and N identical frames of that tick are useless.
 * The capture is delayed until the completion popup has finished sliding in so
 * the picture actually shows the task that was completed.
 */
@Singleton
public class TaskScreenshotManager
{
	static final String SUB_DIR = "ChunkBlazer";

	// A little past the popup's reveal so we catch it fully settled, not mid-slide.
	private static final long POPUP_SETTLE_MS = TaskCompletionAnimationOverlay.PHASE_3_START + 300;

	// Characters Windows refuses in file names; the other platforms are a subset.
	private static final String ILLEGAL_FILENAME_CHARS = "[\\/:*?\"<>|]";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private ChunkBlazerConfig config;

	@Inject
	private ScheduledExecutorService executorService;

	private ScheduledFuture<?> pendingCapture;

	/**
	 * Queue a screenshot for a batch of just-completed tasks. Call on the client
	 * thread, after the completion popup has been triggered.
	 */
	public void onTasksCompleted(List<NuzlockeTask> batch)
	{
		if (!config.screenshotOnTaskComplete() || batch == null || batch.isEmpty())
		{
			return;
		}

		final String fileName = buildFileName(batch);
		final long delay = config.showTaskCompletionPopup() ? POPUP_SETTLE_MS : 0;

		// A second batch inside the delay window replaces the first: both would
		// capture nearly the same frame, and the later one names the newer task.
		cancelPending();
		pendingCapture = executorService.schedule(
			() -> clientThread.invokeLater(() -> capture(fileName)),
			delay, TimeUnit.MILLISECONDS);
	}

	public void shutDown()
	{
		cancelPending();
	}

	private void capture(String fileName)
	{
		pendingCapture = null;
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		imageCapture.takeScreenshot(
			SUB_DIR,
			fileName,
			config.screenshotIncludeFrame(),
			config.screenshotNotify(),
			config.screenshotCopyToClipboard());
	}

	private void cancelPending()
	{
		if (pendingCapture != null)
		{
			pendingCapture.cancel(false);
			pendingCapture = null;
		}
	}

	static String buildFileName(List<NuzlockeTask> batch)
	{
		NuzlockeTask first = batch.get(0);
		String name = first.getName();
		if (name == null || name.trim().isEmpty())
		{
			name = first.getTaskId() != null ? first.getTaskId() : "Task";
		}
		name = name.replaceAll(ILLEGAL_FILENAME_CHARS, "").trim();

		StringBuilder sb = new StringBuilder("Task Complete - ").append(name);
		if (batch.size() > 1)
		{
			sb.append(" (+").append(batch.size() - 1).append(" more)");
		}
		return sb.toString();
	}
}
