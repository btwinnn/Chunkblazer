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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.Text;

/**
 * Saves a screenshot when tasks complete, through RuneLite's {@link ImageCapture}
 * (the same path the core Screenshot plugin uses). Files land in a ChunkBlazer
 * folder inside RuneLite's screenshots folder for the character, named after the
 * task, with ImageCapture appending the timestamp.
 *
 * <p>One screenshot per completion batch: a boss-chunk grant can settle a whole
 * stack of tasks in one tick, and N identical frames of that tick are useless.
 * A newer batch supersedes one whose screenshot is still pending.
 *
 * <p>Everything runs on the client thread: the capture is a {@link ClientThread}
 * task re-run every client cycle until it is done. It waits for the completion
 * popup to finish sliding in so the picture shows the task, rides out a brief
 * load or world hop, and gives up once the popup starts fading out. Shutting
 * down (or disabling the toggle) cancels a pending capture.
 */
@Singleton
public class TaskScreenshotManager
{
	static final String SUB_DIR = "ChunkBlazer";

	// A little past the popup's reveal so we catch it fully settled, not mid-slide.
	static final long POPUP_SETTLE_MS = TaskCompletionAnimationOverlay.PHASE_3_START + 300;

	// Past this the popup is fading out, so a late frame would no longer show it.
	static final long GIVE_UP_MS = TaskCompletionAnimationOverlay.PHASE_4_START;

	// Path separators (both), the rest of what Windows refuses, and control characters.
	private static final String ILLEGAL_FILENAME_CHARS = "[\\\\/:*?\"<>|\\p{Cntrl}]";

	private static final int MAX_NAME_LENGTH = 80;

	private final Client client;
	private final ClientThread clientThread;
	private final ImageCapture imageCapture;
	private final ChunkBlazerConfig config;

	// Swappable so tests can drive time.
	LongSupplier clock = System::currentTimeMillis;

	// Bumped by every new batch and by shutDown; a pending capture whose
	// generation is stale is dropped.
	private final AtomicInteger generation = new AtomicInteger();

	@Inject
	TaskScreenshotManager(Client client, ClientThread clientThread, ImageCapture imageCapture, ChunkBlazerConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.imageCapture = imageCapture;
		this.config = config;
	}

	/**
	 * Queue a screenshot for a batch of just-completed tasks. Call on the client
	 * thread, after the completion popup has been triggered.
	 *
	 * @param featured the task the popup is showing, or null to use the first in the batch
	 */
	public void onTasksCompleted(List<NuzlockeTask> batch, NuzlockeTask featured)
	{
		if (!config.screenshotOnTaskComplete() || batch == null || batch.isEmpty())
		{
			return;
		}

		final int gen = generation.incrementAndGet();
		final String fileName = buildFileName(batch, featured);
		final long start = clock.getAsLong();
		final long captureAt = start + (config.showTaskCompletionPopup() ? POPUP_SETTLE_MS : 0);
		final long giveUpAt = start + GIVE_UP_MS;

		clientThread.invokeLater((BooleanSupplier) () -> attempt(gen, fileName, captureAt, giveUpAt));
	}

	/** Cancel any pending capture. Safe from any thread. */
	public void shutDown()
	{
		generation.incrementAndGet();
	}

	/** One client-cycle attempt; true when finished (taken or abandoned), false to retry. */
	private boolean attempt(int gen, String fileName, long captureAt, long giveUpAt)
	{
		if (gen != generation.get() || !config.screenshotOnTaskComplete())
		{
			return true;
		}

		long now = clock.getAsLong();
		if (now >= giveUpAt)
		{
			return true;
		}

		GameState state = client.getGameState();
		if (state == GameState.LOADING || state == GameState.HOPPING)
		{
			return false;
		}
		if (state != GameState.LOGGED_IN)
		{
			return true;
		}
		if (now < captureAt)
		{
			return false;
		}

		imageCapture.takeScreenshot(
			SUB_DIR,
			fileName,
			config.screenshotIncludeFrame(),
			config.screenshotNotify(),
			config.screenshotCopyToClipboard());
		return true;
	}

	static String buildFileName(List<NuzlockeTask> batch, NuzlockeTask featured)
	{
		NuzlockeTask task = featured != null ? featured : batch.get(0);
		String name = cleanName(task.getName());
		if (name.isEmpty())
		{
			name = cleanName(task.getTaskId());
		}
		if (name.isEmpty())
		{
			name = "Task";
		}

		StringBuilder sb = new StringBuilder("Task Complete - ").append(name);
		if (batch.size() > 1)
		{
			sb.append(" (+").append(batch.size() - 1).append(" more)");
		}
		return sb.toString();
	}

	// Task names come from the server catalog: treat them as untrusted path input.
	private static String cleanName(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		// Whitespace becomes a space before the control-character strip, so a tab or
		// newline still separates words; collapse again for gaps the strip leaves.
		String name = Text.removeTags(raw)
			.replaceAll("\\s+", " ")
			.replaceAll(ILLEGAL_FILENAME_CHARS, "")
			.replaceAll("\\s+", " ")
			.trim();
		if (name.length() > MAX_NAME_LENGTH)
		{
			name = name.substring(0, MAX_NAME_LENGTH).trim();
		}
		return name;
	}
}
