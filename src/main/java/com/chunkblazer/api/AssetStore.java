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

package com.chunkblazer.api;

import com.chunkblazer.ChunkBlazerConfig;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches, caches, and serves ChunkBlazer media assets from the server, so heavy media
 * lives out of the shipped jar. See {@code Chunkblazer-Server/docs/MEDIA-PIPELINE-PLAN.md}.
 *
 * <ol>
 *   <li><b>Load order cache, network, bundled seed.</b> The network only upgrades the
 *       on-disk copy.</li>
 *   <li><b>Flip "loaded" only on a real success.</b> An offline/error/empty response
 *       keeps the last-good manifest and retries. An empty manifest is a failure,
 *       never "zero assets".</li>
 *   <li><b>Revalidate, don't re-download.</b> The manifest ETag is persisted and sent
 *       as {@code If-None-Match}.</li>
 *   <li><b>The play/render path never touches the network.</b>
 *       {@link #isPresent(AudioAsset)} is a pure disk lookup. Downloads happen only on
 *       the single warm thread, guarded against duplicate in-flight fetches.</li>
 *   <li><b>Content-addressed + verified.</b> A download goes to a temp file and is
 *       atomically renamed into place only if its SHA-256 matches the manifest.</li>
 * </ol>
 */
@Slf4j
@Singleton
public class AssetStore
{
	private static final String MANIFEST_URL_PATH = "/assets/manifest.json";
	private static final String ASSET_URL_PREFIX = "assets/"; // manifest paths are /assets-rooted

	// Hard ceiling on the on-disk audio cache. The full corpus is ~9.4 MB, so
	// 40 MB leaves comfortable headroom for future icons/anim while still being
	// a firm bound — the plugin can never silently eat the user's disk.
	private static final long CACHE_CAP_BYTES = 40L * 1024 * 1024;

	// Hard ceilings on a single HTTP body, enforced DURING the read because a
	// hostile or compromised server can lie about or omit Content-Length. These
	// bound peak memory so the server can never OOM the client; the on-disk total
	// is separately bounded by CACHE_CAP_BYTES. Set well above the largest
	// legitimate payload (audio seen ~220KB), so real growth never trips them.
	private static final long MAX_ASSET_BYTES = 4L * 1024 * 1024;
	private static final long MAX_MANIFEST_BYTES = 8L * 1024 * 1024;

	private final OkHttpClient httpClient;
	private final ChunkBlazerConfig config;
	private final Gson gson;

	// Single dedicated warm thread: downloads are serialized and can never
	// starve gameplay calls (/sync, /heartbeat) on the main executor. NOT final:
	// this is a singleton, and disabling the plugin runs shutdown() which
	// terminates the pool, so a re-enable in the same session must build a fresh
	// one or every submit throws RejectedExecutionException. init() rebuilds it.
	private ExecutorService warmExecutor;

	private static ExecutorService newWarmExecutor()
	{
		return Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "chunkblazer-asset-warm");
			t.setDaemon(true);
			return t;
		});
	}

	// Guards against enqueuing the same asset twice while a download is in
	// flight (the render-path storm guard).
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	// Sandboxed assets dir + the two cache files under it, set in init() from the
	// plugin's Filepath. Null when no plugin dir is available (rare) — the store
	// then serves the bundled fallback audio only.
	private volatile Filepath cacheDir;
	private volatile Filepath manifestFile;
	private volatile Filepath etagFile;

	// Written on the warm thread, read on the game thread — volatile publish.
	private volatile AssetManifest manifest;
	private volatile boolean loaded;

	@Inject
	public AssetStore(OkHttpClient sharedClient, ChunkBlazerConfig config, Gson gson)
	{
		this.config = config;
		this.gson = gson;

		// Derive from RuneLite's shared client (connection pool reuse) but give
		// asset traffic its own dispatcher: at most 2 concurrent downloads so a
		// warm-up burst never crowds out the API calls on the shared pool.
		Dispatcher dispatcher = new Dispatcher();
		dispatcher.setMaxRequests(2);
		dispatcher.setMaxRequestsPerHost(2);
		this.httpClient = sharedClient.newBuilder()
			.dispatcher(dispatcher)
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			// We only ever fetch from our own API and never need a redirect; refuse
			// them so a rogue 3xx can't send the request to another host.
			.followRedirects(false)
			.followSslRedirects(false)
			.build();
	}

	/**
	 * Load the last-good manifest from disk immediately (instant, offline-safe),
	 * then kick an async server check that only ever upgrades it. Safe to call
	 * once on plugin start.
	 *
	 * @param pluginDir the plugin's sandboxed data directory (from
	 *                  {@code getPluginDirectory()}), or null to run cache-less
	 */
	public void init(Filepath pluginDir)
	{
		// Build (or rebuild, after a disable/enable) the warm pool before anything
		// submits to it, or a re-enable hits a terminated executor.
		if (warmExecutor == null || warmExecutor.isShutdown())
		{
			warmExecutor = newWarmExecutor();
		}

		// Assets live in a sandboxed "assets" subfolder of the plugin dir (Filepath).
		// Null => no disk cache; isPresent stays false and the bundled fallback plays.
		this.cacheDir = pluginDir != null ? pluginDir.joinSegment("assets") : null;
		this.manifestFile = cacheDir != null ? cacheDir.joinSegment("manifest.json") : null;
		this.etagFile = cacheDir != null ? cacheDir.joinSegment("manifest.etag") : null;
		if (cacheDir != null)
		{
			try
			{
				cacheDir.createDirectories();
			}
			catch (IOException e)
			{
				log.warn("Could not create asset cache dir: {}", e.getMessage());
			}
		}

		// cache -> memory (instant)
		if (manifestFile != null && manifestFile.exists())
		{
			try (InputStream is = manifestFile.openInputStream())
			{
				AssetManifest disk = gson.fromJson(
					new String(is.readAllBytes(), StandardCharsets.UTF_8),
					AssetManifest.class);
				if (isUsable(disk))
				{
					this.manifest = disk;
					this.loaded = true;
				}
			}
			catch (Exception e)
			{
				log.warn("Cached manifest unreadable: {}", e.getMessage());
			}
		}

		// -> network (async; never blocks startup)
		warmExecutor.execute(this::refreshManifest);

		// Pre-warm everything we already know about so completions play the real
		// regional jingle, not the seed fallback. Idempotent (skips cached), and
		// if we're offline this still warms from the cached manifest. A fresh
		// network manifest triggers its own warmAll() in refreshManifest().
		if (this.manifest != null)
		{
			warmExecutor.execute(this::warmAll);
		}
	}

	/** True once a non-empty manifest has been loaded from cache or network. */
	public boolean isLoaded()
	{
		return loaded;
	}

	/**
	 * The jingles available for an area folder (e.g. {@code Misthalin_Sounds}),
	 * or an empty list if the manifest hasn't loaded or has no such area.
	 */
	public List<AudioAsset> audioForArea(String folder)
	{
		AssetManifest m = this.manifest;
		if (m == null || m.getAudio() == null || folder == null)
		{
			return Collections.emptyList();
		}
		List<AudioAsset> list = m.getAudio().get(folder);
		return list != null ? list : Collections.emptyList();
	}

	/**
	 * Render/play-path-safe presence check: is this asset cached on disk? Does
	 * <b>no</b> network I/O and never enqueues a download — a caller on the game
	 * thread can hit this every frame safely.
	 */
	public boolean isPresent(AudioAsset asset)
	{
		if (asset == null || asset.getPath() == null)
		{
			return false;
		}
		try
		{
			Filepath f = cacheFilepathFor(asset);
			return f != null && f.isFile() && f.size() > 0;
		}
		catch (Exception e)
		{
			return false;
		}
	}

	/**
	 * The cached bytes for this asset if present on disk, else {@code null}. Does
	 * <b>no</b> network I/O. A {@code null} return means "play the bundled fallback";
	 * pair it with {@link #warm(AudioAsset)} to fetch it for next time.
	 */
	public byte[] readIfPresent(AudioAsset asset)
	{
		if (asset == null || asset.getPath() == null)
		{
			return null;
		}
		try
		{
			Filepath f = cacheFilepathFor(asset);
			if (f == null || !f.isFile() || f.size() <= 0)
			{
				return null;
			}
			try (InputStream is = f.openInputStream())
			{
				return is.readAllBytes();
			}
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * Ensure this asset is on disk for next time. No-op if already cached or
	 * already downloading. Runs on the dedicated warm thread; safe to call from
	 * the game thread.
	 */
	public void warm(AudioAsset asset)
	{
		if (asset == null || asset.getPath() == null || !config.apiEnabled())
		{
			return;
		}
		if (isPresent(asset))
		{
			return;
		}
		if (!inFlight.add(asset.getPath()))
		{
			return; // already downloading
		}
		warmExecutor.execute(() ->
		{
			try
			{
				download(asset);
			}
			catch (Exception e)
			{
				// Best-effort: a failed warm just means we fall back to bundled
				// audio and try again next time it's requested.
			}
			finally
			{
				inFlight.remove(asset.getPath());
			}
		});
	}

	/** Warm every asset for an area (e.g. on region unlock, before first play). */
	public void warmArea(String folder)
	{
		for (AudioAsset a : audioForArea(folder))
		{
			warm(a);
		}
	}

	/**
	 * Warm the entire manifest in the background so completions play real
	 * regional jingles instead of the seed fallback. Idempotent — {@link #warm}
	 * skips anything already cached or in flight, so calling this every login is
	 * a near-zero-cost re-check once the ~9.4 MB corpus is on disk. The whole set
	 * is one-time and edge-cached, so origin cost is negligible.
	 */
	public void warmAll()
	{
		AssetManifest m = this.manifest;
		if (m == null || m.getAudio() == null)
		{
			return;
		}
		for (List<AudioAsset> list : m.getAudio().values())
		{
			for (AudioAsset a : list)
			{
				warm(a);
			}
		}
	}

	public void shutdown()
	{
		if (warmExecutor != null)
		{
			warmExecutor.shutdown();
		}
	}

	// ==================== internals ====================

	/**
	 * The single gated chokepoint for every synchronous networking call: nothing reaches
	 * the network unless {@code serverSyncEnabled} is true (Plugin Hub 3rd-party-networking
	 * rule). Throwing when disabled folds into callers' offline handling.
	 */
	private Response executeGated(Request req) throws IOException
	{
		if (!config.apiEnabled())
		{
			throw new IOException("server sync disabled");
		}
		return httpClient.newCall(req).execute();
	}

	private void refreshManifest()
	{
		if (!config.apiEnabled())
		{
			return;
		}
		String url = config.apiBaseUrl() + MANIFEST_URL_PATH;
		String etag = readEtag();

		Request.Builder rb = new Request.Builder().url(url).get();
		if (etag != null)
		{
			rb.addHeader("If-None-Match", etag);
		}

		try (Response resp = executeGated(rb.build()))
		{
			if (resp.code() == 304)
			{
				// Unchanged — keep the cached copy, no body, no re-parse. Still
				// warm, in case the cache was cleared while the manifest wasn't.
				warmAll();
				return;
			}
			if (!resp.isSuccessful())
			{
				return; // keep last-good
			}

			ResponseBody body = resp.body();
			String json = body != null
				? new String(HttpBodies.readBounded(body, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8) : "";
			if (json.isEmpty())
			{
				return; // empty body is a failure, never "zero assets"
			}

			AssetManifest fresh = gson.fromJson(json, AssetManifest.class);
			if (!isUsable(fresh))
			{
				// An empty/blank manifest must not blank good local data.
				log.warn("Empty asset manifest ignored");
				return;
			}

			// Only a real 200 with usable content rewrites cache + memory.
			writeAtomic(manifestFile, json.getBytes(StandardCharsets.UTF_8));
			String newEtag = resp.header("ETag");
			if (newEtag != null)
			{
				writeAtomic(etagFile, newEtag.getBytes(StandardCharsets.UTF_8));
			}
			this.manifest = fresh;
			this.loaded = true;
			warmAll();
		}
		catch (IOException e)
		{
			// Offline / server down: a non-event. Keep whatever we already have.
		}
	}

	private void download(AudioAsset asset) throws IOException
	{
		Filepath dest = cacheFilepathFor(asset);
		if (dest == null)
		{
			return; // cache-less mode: nothing to download to
		}
		if (dest.isFile() && dest.size() > 0)
		{
			return;
		}
		Filepath parent = dest.getParent();
		if (parent != null)
		{
			parent.createDirectories();
		}

		String url = config.apiBaseUrl() + "/" + asset.getPath();
		Request req = new Request.Builder().url(url).get().build();
		try (Response resp = executeGated(req))
		{
			if (!resp.isSuccessful() || resp.body() == null)
			{
				throw new IOException("HTTP " + resp.code());
			}
			byte[] bytes = HttpBodies.readBounded(resp.body(), MAX_ASSET_BYTES);

			// Content-addressed: verify before trusting. A mismatch means a
			// corrupt/truncated transfer or a stale url — drop it.
			String actual = sha256Hex(bytes);
			if (asset.getSha256() != null && !actual.equalsIgnoreCase(asset.getSha256()))
			{
				throw new IOException("sha256 mismatch for " + asset.getPath());
			}

			writeAtomic(dest, bytes);
			enforceCap();
		}
	}

	/**
	 * Maps a server-provided manifest path to a Filepath under the cache dir.
	 * {@code cacheDir.join(rel)} normalizes it and throws on anything escaping the plugin's
	 * sandbox, so a malicious path can't land outside the assets folder. Returns null in
	 * cache-less mode.
	 */
	private Filepath cacheFilepathFor(AudioAsset asset)
	{
		Filepath dir = cacheDir;
		if (dir == null)
		{
			return null;
		}
		String rel = asset.getPath();
		if (rel.startsWith(ASSET_URL_PREFIX))
		{
			rel = rel.substring(ASSET_URL_PREFIX.length());
		}
		return dir.join(rel);
	}

	private static boolean isUsable(AssetManifest m)
	{
		return m != null && m.getAudio() != null && !m.getAudio().isEmpty();
	}

	private String readEtag()
	{
		Filepath f = etagFile;
		if (f == null || !f.exists())
		{
			return null;
		}
		try (InputStream is = f.openInputStream())
		{
			String s = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
			return s.isEmpty() ? null : s;
		}
		catch (IOException e)
		{
			return null;
		}
	}

	private void writeAtomic(Filepath dest, byte[] bytes) throws IOException
	{
		if (dest == null)
		{
			return; // cache-less mode: nothing to persist
		}
		Filepath dir = dest.getParent();
		if (dir == null)
		{
			return;
		}
		Filepath tmp = dir.createTempFile("asset", ".tmp");
		try
		{
			tmp.write(bytes);
			try
			{
				tmp.moveTo(dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException atomicUnsupported)
			{
				// Some filesystems don't support ATOMIC_MOVE; fall back to a plain replace.
				tmp.moveTo(dest, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			tmp.deleteIfExists();
			throw e;
		}
	}

	private static String sha256Hex(byte[] bytes)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest)
			{
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			return "";
		}
	}

	/**
	 * Enforce the hard disk cap with a simple LRU: while over budget, delete the
	 * oldest cached file. Content addressing means a deleted asset is simply
	 * re-fetched on next demand — eviction is never destructive.
	 */
	private void enforceCap()
	{
		Filepath dir = cacheDir;
		if (dir == null)
		{
			return;
		}
		try
		{
			Filepath audioDir = dir.joinSegment("audio");
			if (!audioDir.isDirectory())
			{
				return;
			}
			java.util.List<Filepath> files = new java.util.ArrayList<>();
			long total = 0;
			try (java.util.stream.Stream<Filepath> walk = audioDir.walk())
			{
				java.util.Iterator<Filepath> it = walk.iterator();
				while (it.hasNext())
				{
					Filepath fp = it.next();
					if (fp.isFile() && !fp.getFileName().endsWith(".tmp"))
					{
						files.add(fp);
						total += sizeOf(fp);
					}
				}
			}
			if (total <= CACHE_CAP_BYTES)
			{
				return;
			}
			files.sort(java.util.Comparator.comparingLong(AssetStore::mtimeOf)); // oldest first
			for (Filepath fp : files)
			{
				if (total <= CACHE_CAP_BYTES)
				{
					break;
				}
				long len = sizeOf(fp);
				try
				{
					fp.deleteIfExists();
					total -= len;
				}
				catch (IOException ignored)
				{
					// couldn't delete this one; move on
				}
			}
		}
		catch (Exception e)
		{
			// Trimming the cache is best effort; it retries on the next write.
		}
	}

	private static long sizeOf(Filepath fp)
	{
		try
		{
			return fp.size();
		}
		catch (IOException e)
		{
			return 0;
		}
	}

	private static long mtimeOf(Filepath fp)
	{
		try
		{
			return fp.getLastModifiedTime().toMillis();
		}
		catch (IOException e)
		{
			return 0;
		}
	}
}
