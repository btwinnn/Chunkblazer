package com.chunkblazer.api;

import com.chunkblazer.ChunkBlazerConfig;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches, caches, and serves ChunkBlazer media assets (task-completion jingles
 * today; icons/animations later) from the server, so heavy media lives out of
 * the shipped jar. Deliberately built to mirror the plugin's already-hardened
 * login/sync outage discipline (see
 * {@code Chunkblazer-Server/docs/MEDIA-PIPELINE-PLAN.md} and
 * {@code TASK-CATALOG-MIGRATION-PLAN.md}):
 *
 * <ol>
 *   <li><b>Load order cache -&gt; network -&gt; bundled seed.</b> The newest
 *       on-disk copy loads instantly and offline; the network only ever
 *       upgrades it; the caller's bundled resource is the final fallback.</li>
 *   <li><b>Flip "loaded" only on a real success.</b> An offline/error/empty
 *       response leaves the last-good manifest in place and retries. An empty
 *       manifest is treated as a failure, never as "zero assets" — that is the
 *       exact bug the sync union-merge exists to kill, ported here.</li>
 *   <li><b>Revalidate, don't re-download.</b> The manifest ETag is persisted and
 *       sent as {@code If-None-Match}; steady state is a ~0-byte 304.</li>
 *   <li><b>The play/render path never touches the network.</b>
 *       {@link #getIfPresent(AudioAsset)} is a pure disk lookup. Downloads
 *       happen only on the single warm thread, guarded against duplicate
 *       in-flight fetches — so a 50fps overlay can't turn into a download
 *       storm (the TCG failure mode).</li>
 *   <li><b>Content-addressed + verified.</b> A download is written to a temp
 *       file, hashed, and only atomically renamed into place if its SHA-256
 *       matches the manifest. A truncated fetch can never become a permanent
 *       corrupt asset.</li>
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

	private final OkHttpClient httpClient;
	private final ChunkBlazerConfig config;
	private final Gson gson;

	// Single dedicated warm thread: downloads are serialized and can never
	// starve gameplay calls (/sync, /heartbeat) on the main executor.
	private final ExecutorService warmExecutor =
		Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "chunkblazer-asset-warm");
			t.setDaemon(true);
			return t;
		});

	// Guards against enqueuing the same asset twice while a download is in
	// flight (the render-path storm guard).
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	private final File cacheDir;
	private final File manifestFile;
	private final File etagFile;

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
			.build();

		this.cacheDir = new File(RuneLite.RUNELITE_DIR, "chunkblazer/assets");
		this.manifestFile = new File(cacheDir, "manifest.json");
		this.etagFile = new File(cacheDir, "manifest.etag");
	}

	/**
	 * Load the last-good manifest from disk immediately (instant, offline-safe),
	 * then kick an async server check that only ever upgrades it. Safe to call
	 * once on plugin start.
	 */
	public void init()
	{
		//noinspection ResultOfMethodCallIgnored
		cacheDir.mkdirs();

		// cache -> memory (instant)
		if (manifestFile.exists())
		{
			try
			{
				AssetManifest disk = gson.fromJson(
					new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8),
					AssetManifest.class);
				if (isUsable(disk))
				{
					this.manifest = disk;
					this.loaded = true;
					log.debug("Loaded cached asset manifest ({} areas)", disk.getAudio().size());
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to read cached asset manifest, will refetch: {}", e.getMessage());
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
	 * Render/play-path-safe lookup: returns the cached file for this asset if it
	 * is present on disk, else {@code null}. Does <b>no</b> network I/O and never
	 * enqueues a download — a caller on the game thread can hit this every frame
	 * safely. A {@code null} return means "play the bundled fallback for now";
	 * pair it with {@link #warm(AudioAsset)} to fetch it for next time.
	 */
	public File getIfPresent(AudioAsset asset)
	{
		if (asset == null || asset.getPath() == null)
		{
			return null;
		}
		File f = cacheFileFor(asset);
		if (f.isFile() && f.length() > 0)
		{
			//noinspection ResultOfMethodCallIgnored
			f.setLastModified(System.currentTimeMillis()); // LRU touch
			return f;
		}
		return null;
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
		if (getIfPresent(asset) != null)
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
				log.debug("Asset warm failed for {}: {}", asset.getPath(), e.getMessage());
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
		warmExecutor.shutdownNow();
	}

	// ==================== internals ====================

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

		try (Response resp = httpClient.newCall(rb.build()).execute())
		{
			if (resp.code() == 304)
			{
				// Unchanged — keep the cached copy, no body, no re-parse. Still
				// warm, in case the cache was cleared while the manifest wasn't.
				log.debug("Asset manifest unchanged (304)");
				warmAll();
				return;
			}
			if (!resp.isSuccessful())
			{
				log.debug("Asset manifest fetch returned {}", resp.code());
				return; // keep last-good
			}

			ResponseBody body = resp.body();
			String json = body != null ? body.string() : "";
			if (json.isEmpty())
			{
				return; // empty body is a failure, never "zero assets"
			}

			AssetManifest fresh = gson.fromJson(json, AssetManifest.class);
			if (!isUsable(fresh))
			{
				// An empty/blank manifest must not blank good local data.
				log.warn("Ignoring empty asset manifest from server");
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
			log.debug("Refreshed asset manifest ({} areas)", fresh.getAudio().size());
			warmAll();
		}
		catch (IOException e)
		{
			// Offline / server down: a non-event. Keep whatever we already have.
			log.debug("Asset manifest fetch failed (offline?): {}", e.getMessage());
		}
	}

	private void download(AudioAsset asset) throws IOException
	{
		File dest = cacheFileFor(asset);
		if (dest.isFile() && dest.length() > 0)
		{
			return;
		}
		//noinspection ResultOfMethodCallIgnored
		dest.getParentFile().mkdirs();

		String url = config.apiBaseUrl() + "/" + asset.getPath();
		Request req = new Request.Builder().url(url).get().build();
		try (Response resp = httpClient.newCall(req).execute())
		{
			if (!resp.isSuccessful() || resp.body() == null)
			{
				throw new IOException("HTTP " + resp.code());
			}
			byte[] bytes = resp.body().bytes();

			// Content-addressed: verify before trusting. A mismatch means a
			// corrupt/truncated transfer or a stale url — drop it.
			String actual = sha256Hex(bytes);
			if (asset.getSha256() != null && !actual.equalsIgnoreCase(asset.getSha256()))
			{
				throw new IOException("sha256 mismatch for " + asset.getPath());
			}

			writeAtomic(dest, bytes);
			enforceCap();
			log.debug("Cached asset {} ({} bytes)", asset.getPath(), bytes.length);
		}
	}

	/** Maps an /assets-rooted manifest path to its location under the cache dir. */
	private File cacheFileFor(AudioAsset asset)
	{
		String rel = asset.getPath();
		if (rel.startsWith(ASSET_URL_PREFIX))
		{
			rel = rel.substring(ASSET_URL_PREFIX.length());
		}
		return new File(cacheDir, rel);
	}

	private static boolean isUsable(AssetManifest m)
	{
		return m != null && m.getAudio() != null && !m.getAudio().isEmpty();
	}

	private String readEtag()
	{
		if (!etagFile.exists())
		{
			return null;
		}
		try
		{
			String s = new String(Files.readAllBytes(etagFile.toPath()), StandardCharsets.UTF_8).trim();
			return s.isEmpty() ? null : s;
		}
		catch (IOException e)
		{
			return null;
		}
	}

	private static void writeAtomic(File dest, byte[] bytes) throws IOException
	{
		File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
		Files.write(tmp.toPath(), bytes);
		try
		{
			Files.move(tmp.toPath(), dest.toPath(),
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException atomicUnsupported)
		{
			// Some filesystems don't support ATOMIC_MOVE across the temp+dest;
			// fall back to a plain replace.
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
	 * least-recently-touched cached file. Content addressing means a deleted
	 * asset is simply re-fetched on next demand — eviction is never destructive.
	 */
	private void enforceCap()
	{
		try
		{
			File audioDir = new File(cacheDir, "audio");
			if (!audioDir.isDirectory())
			{
				return;
			}
			java.util.List<File> files = new java.util.ArrayList<>();
			collectFiles(audioDir, files);
			long total = 0;
			for (File f : files)
			{
				total += f.length();
			}
			if (total <= CACHE_CAP_BYTES)
			{
				return;
			}
			files.sort(java.util.Comparator.comparingLong(File::lastModified)); // oldest first
			for (File f : files)
			{
				if (total <= CACHE_CAP_BYTES)
				{
					break;
				}
				long len = f.length();
				if (f.delete())
				{
					total -= len;
					File parent = f.getParentFile();
					if (parent != null && parent.isDirectory())
					{
						String[] kids = parent.list();
						if (kids != null && kids.length == 0)
						{
							//noinspection ResultOfMethodCallIgnored
							parent.delete();
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			log.debug("Cache cap enforcement skipped: {}", e.getMessage());
		}
	}

	private static void collectFiles(File dir, java.util.List<File> out)
	{
		File[] kids = dir.listFiles();
		if (kids == null)
		{
			return;
		}
		for (File k : kids)
		{
			if (k.isDirectory())
			{
				collectFiles(k, out);
			}
			else if (k.isFile() && !k.getName().endsWith(".tmp"))
			{
				out.add(k);
			}
		}
	}
}
