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

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/**
 * Saves a screenshot when tasks complete and serves thumbnails of them to the
 * Completed Tasks panel.
 *
 * <p>Files land where RuneLite's own Screenshot plugin puts things,
 * {@code .runelite/screenshots/<RSN>/ChunkBlazer/}, but are written here rather
 * than through {@link ImageCapture#saveScreenshot}: that method doesn't say
 * where the file went, and the panel needs the path to link each task to its
 * picture. The path is handed to {@link #setOnSaved} for the plugin to store.
 *
 * <p>One screenshot per completion batch: a boss-chunk grant can settle a whole
 * stack of tasks in one tick, and N identical frames of that tick are useless.
 * Every task in the batch is linked to the same file. The capture is delayed
 * until the completion popup has finished sliding in so the picture actually
 * shows the task that was completed.
 */
@Slf4j
@Singleton
public class TaskScreenshotManager
{
	static final String SUB_DIR = "ChunkBlazer";

	// A little past the popup's reveal so we catch it fully settled, not mid-slide.
	private static final long POPUP_SETTLE_MS = TaskCompletionAnimationOverlay.PHASE_3_START + 300;

	// Characters Windows refuses in file names; the other platforms are a subset.
	private static final String ILLEGAL_FILENAME_CHARS = "[\\\\/:*?\"<>|]";

	// Thumbnails are tiny but a long-lived account can rack up hundreds of them;
	// keep the most recently drawn ones and re-read the rest from disk.
	private static final int THUMBNAIL_CACHE_SIZE = 64;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private Notifier notifier;

	@Inject
	private ChunkBlazerConfig config;

	@Inject
	private ScheduledExecutorService executorService;

	/** Told which task ids a saved screenshot belongs to, and where the file went. */
	@Setter
	private BiConsumer<List<String>, File> onSaved;

	private ScheduledFuture<?> pendingCapture;
	private List<String> pendingTaskIds = Collections.emptyList();

	// Thumbnail reads in progress, keyed like the cache. EDT-only.
	private final Map<String, List<Consumer<BufferedImage>>> inFlight = new HashMap<>();

	private final Map<String, BufferedImage> thumbnailCache = Collections.synchronizedMap(
		new LinkedHashMap<String, BufferedImage>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
			{
				return size() > THUMBNAIL_CACHE_SIZE;
			}
		});

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
		final List<String> taskIds = new ArrayList<>(batch.size());
		for (NuzlockeTask task : batch)
		{
			if (task.getTaskId() != null)
			{
				taskIds.add(task.getTaskId());
			}
		}
		final long delay = config.showTaskCompletionPopup() ? POPUP_SETTLE_MS : 0;

		// A second batch inside the delay window would capture nearly the same
		// frame. Rather than drop either, it supersedes the first and carries
		// both batches' ids so every task still gets linked to the picture.
		if (pendingCapture != null && pendingCapture.cancel(false))
		{
			taskIds.addAll(0, pendingTaskIds);
		}
		pendingTaskIds = taskIds;
		pendingCapture = executorService.schedule(
			() -> clientThread.invokeLater(() -> capture(fileName, taskIds)),
			delay, TimeUnit.MILLISECONDS);
	}

	public void shutDown()
	{
		if (pendingCapture != null)
		{
			pendingCapture.cancel(false);
			pendingCapture = null;
		}
		thumbnailCache.clear();
	}

	/** Runs on the client thread, where the player name and profile type can be read. */
	private void capture(String fileName, List<String> taskIds)
	{
		pendingCapture = null;
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final File folder = screenshotFolder();
		final boolean includeFrame = config.screenshotIncludeFrame();
		final boolean notify = config.screenshotNotify();
		final boolean copy = config.screenshotCopyToClipboard();

		drawManager.requestNextFrameListener(frame ->
			// Frame callbacks run on the client thread; PNG encoding is too slow for it.
			executorService.submit(() ->
			{
				BufferedImage image = includeFrame
					? imageCapture.addClientFrame(frame)
					: ImageUtil.bufferedImageFromImage(frame);
				File file = save(image, folder, fileName);
				if (file == null)
				{
					return;
				}
				if (copy)
				{
					Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(image), null);
				}
				if (notify)
				{
					notifier.notify("ChunkBlazer screenshot saved: " + file.getName());
				}
				if (onSaved != null)
				{
					onSaved.accept(taskIds, file);
				}
			}));
	}

	/** Same layout as ImageCapture: {@code screenshots/<RSN>[-<world type>]/ChunkBlazer}. */
	private File screenshotFolder()
	{
		Player player = client.getLocalPlayer();
		if (player == null || player.getName() == null)
		{
			return new File(RuneLite.SCREENSHOT_DIR, SUB_DIR);
		}
		String playerDir = player.getName();
		RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
		if (profileType != RuneScapeProfileType.STANDARD)
		{
			playerDir += "-" + Text.titleCase(profileType);
		}
		return new File(new File(RuneLite.SCREENSHOT_DIR, playerDir), SUB_DIR);
	}

	private static File save(BufferedImage image, File folder, String fileName)
	{
		if (!folder.isDirectory() && !folder.mkdirs())
		{
			log.warn("Could not create screenshot folder {}", folder);
			return null;
		}

		String base = fileName + " " + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
		File file = new File(folder, base + ".png");
		for (int i = 1; file.exists(); i++)
		{
			file = new File(folder, base + "(" + i + ").png");
		}

		try
		{
			ImageIO.write(image, "PNG", file);
			return file;
		}
		catch (IOException ex)
		{
			log.warn("Could not write task screenshot {}", file, ex);
			return null;
		}
	}

	/**
	 * Hand {@code callback} a thumbnail of {@code file} scaled to fit inside
	 * {@code width} x {@code height}, on the EDT. Decoding a full-size PNG is far
	 * too slow for the EDT, so cache misses are read on the executor, and
	 * requests for an image that's already being read join that read instead of
	 * starting another. Nothing is called back if the file can't be read.
	 * Call on the EDT.
	 */
	public void loadThumbnail(File file, int width, int height, Consumer<BufferedImage> callback)
	{
		final String key = file.getAbsolutePath() + "@" + width + "x" + height;
		BufferedImage cached = thumbnailCache.get(key);
		if (cached != null)
		{
			callback.accept(cached);
			return;
		}

		List<Consumer<BufferedImage>> waiting = inFlight.get(key);
		if (waiting != null)
		{
			waiting.add(callback);
			return;
		}
		waiting = new ArrayList<>();
		waiting.add(callback);
		inFlight.put(key, waiting);

		executorService.submit(() ->
		{
			BufferedImage thumb = null;
			try
			{
				BufferedImage full = ImageIO.read(file);
				if (full != null)
				{
					double scale = Math.min((double) width / full.getWidth(), (double) height / full.getHeight());
					thumb = ImageUtil.resizeImage(full,
						Math.max(1, (int) (full.getWidth() * scale)),
						Math.max(1, (int) (full.getHeight() * scale)));
					thumbnailCache.put(key, thumb);
				}
			}
			catch (IOException ex)
			{
				log.debug("Could not read task screenshot {}", file, ex);
			}

			final BufferedImage result = thumb;
			SwingUtilities.invokeLater(() ->
			{
				List<Consumer<BufferedImage>> callbacks = inFlight.remove(key);
				if (result != null && callbacks != null)
				{
					callbacks.forEach(cb -> cb.accept(result));
				}
			});
		});
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

	/** ImageCapture's own clipboard wrapper is package-private, hence this one. */
	private static final class ImageSelection implements Transferable
	{
		private final Image image;

		ImageSelection(Image image)
		{
			this.image = image;
		}

		@Override
		public DataFlavor[] getTransferDataFlavors()
		{
			return new DataFlavor[]{DataFlavor.imageFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor)
		{
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor)
		{
			return image;
		}
	}
}
