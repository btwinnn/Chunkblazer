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

import static com.chunkblazer.Strings.t;
import com.chunkblazer.ChunkBlazerConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the ChunkBlazer task catalog from the server so the plugin can stop
 * bundling ~2.2M tokens of raw {@code *_Tasks.json}. Serves the same combined
 * document {@code GET /api/tasks} returns — a JSON object mapping each source
 * filename to that file's original content — so the three task loaders
 * (region / global / free-chunks) only change their BYTE SOURCE, not their parse.
 *
 * <p>Mirrors {@code AssetStore}'s outage discipline, with two catalog-specific
 * differences that matter:
 * <ol>
 *   <li><b>The cache/seed load is SYNCHRONOUS.</b> {@link #init(Filepath)} must populate
 *       the in-memory catalog before {@code loadChunkData()} runs, so it loads
 *       from disk cache (or the bundled gzipped seed) on the calling thread, then
 *       schedules only the network refresh in the background. The running session
 *       uses whatever loaded at startup; a newer server catalog applies on the
 *       NEXT launch (no mid-session task reload = zero risk to a live game).</li>
 *   <li><b>Completeness check.</b> A server response is accepted only if it
 *       contains every file the current floor (cache/seed) has and no empty
 *       values — see {@link #isComplete}. This is the guard against a partial
 *       catalog silently dropping tasks (the plan's "1 chunk instead of 15"
 *       failure). Empty/short/error responses keep the last-good copy.</li>
 * </ol>
 * The bundled gzipped seed is a hard floor: {@link #getFileContent} can always
 * return the seed's copy, so tasks can never fully fail to load.
 */
@Slf4j
@Singleton
public class CatalogStore
{
	private static final String CATALOG_URL_PATH = "/api/tasks";

	// Hard ceiling on the catalog body, enforced DURING the read (a hostile or
	// compromised server can lie about or omit Content-Length). Bounds peak memory
	// so the server can never OOM the client. Set well above the real catalog
	// (~1.4MB today) to leave room for growth without ever tripping legitimately.
	private static final long MAX_CATALOG_BYTES = 16L * 1024 * 1024;
	// Bundled gzipped combined catalog, in com/chunkblazer/ (built by
	// build-task-seed.ps1). Offline/first-run floor.
	private static final String SEED_RESOURCE = "tasks_catalog.json.gz";

	private final OkHttpClient httpClient;
	private final ChunkBlazerConfig config;
	private final Gson gson;

	// NOT final: this is a singleton, and disabling the plugin runs shutdown() which
	// terminates the pool, so a re-enable in the same session must build a fresh one
	// or every submit throws RejectedExecutionException. init() rebuilds it.
	private ExecutorService refreshExecutor;

	private static ExecutorService newRefreshExecutor()
	{
		return Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "chunkblazer-catalog-refresh");
			t.setDaemon(true);
			return t;
		});
	}

	// Sandboxed plugin data dir + the two cache files under it, set in init() from
	// the Filepath the plugin resolves via getPluginDirectory(). Null when no plugin
	// dir is available (rare), in which case we run cache-less off the bundled seed.
	private volatile Filepath cacheDir;
	private volatile Filepath catalogFile;
	private volatile Filepath etagFile;

	// filename -> that file's JSON content. Written on init()/refresh, read on the
	// game thread by the loaders — volatile publish of an immutable snapshot.
	private volatile Map<String, String> files = Collections.emptyMap();
	private volatile boolean loaded;
	// Monotonic build version of the currently-loaded catalog (from _meta.json), 0 if
	// unversioned. Drives the seed-vs-cache choice and is logged so drift is visible.
	private volatile long loadedVersion;
	// Lazily-loaded bundled seed, used by getFileContent as a per-file floor for
	// anything the active catalog (stale cache / not-yet-redeployed server) lacks.
	private volatile Map<String, String> seedFiles;

	@Inject
	public CatalogStore(OkHttpClient sharedClient, ChunkBlazerConfig config, Gson gson)
	{
		this.config = config;
		this.gson = gson;
		// Derive from RuneLite's injected client; the catalog is a rare, small
		// fetch so default pooling is fine — just give it sane timeouts.
		this.httpClient = sharedClient.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			// We only ever fetch from our own API and never need a redirect; refuse
			// them so a rogue 3xx can't send the request to another host.
			.followRedirects(false)
			.followSslRedirects(false)
			.build();
	}

	/**
	 * SYNCHRONOUS load order cache → bundled seed, so the task loaders can read
	 * the catalog immediately, then an async server refresh for next launch. MUST
	 * be called before {@code loadChunkData()}/{@code loadGlobalTasks()}/
	 * {@code loadFreeChunks()}.
	 *
	 * @param pluginDir the plugin's sandboxed data directory (from
	 *                  {@code getPluginDirectory()}), or null to run cache-less
	 */
	public void init(Filepath pluginDir)
	{
		// Build (or rebuild, after a disable/enable) the refresh pool before anything
		// submits to it, or a re-enable hits a terminated executor.
		if (refreshExecutor == null || refreshExecutor.isShutdown())
		{
			refreshExecutor = newRefreshExecutor();
		}

		// Point the cache at the plugin's sandboxed dir (Filepath). Null => no disk
		// cache; the store serves the bundled seed and the network refresh only.
		this.cacheDir = pluginDir;
		this.catalogFile = pluginDir != null ? pluginDir.joinSegment("tasks_catalog.json") : null;
		this.etagFile = pluginDir != null ? pluginDir.joinSegment("tasks_catalog.etag") : null;
		if (pluginDir != null)
		{
			try
			{
				pluginDir.createDirectories();
			}
			catch (IOException e)
			{
				log.warn(t("api.log.cacheDir"), e.getMessage());
			}
		}

		Map<String, String> cache = loadFromDiskCache();
		Map<String, String> seed = loadFromSeed();
		this.seedFiles = seed != null ? seed : Collections.emptyMap(); // reuse for getFileContent
		long cacheV = versionOf(cache);
		long seedV = versionOf(seed);

		Map<String, String> loadedFiles;
		String source;
		if (cache == null || cache.isEmpty())
		{
			loadedFiles = seed;
			source = "seed";
		}
		else if (seed != null && !seed.isEmpty() && seedV > cacheV)
		{
			// The bundled seed is NEWER than the disk cache — a freshly-built/updated
			// plugin whose cache hasn't caught up (e.g. a RuneLite Hub update, or a
			// local rebuild before the server redeploy). Prefer the seed and drop the
			// stale cache so it can't keep winning; the async refresh re-populates it.
			// This kills the "rebuilt the plugin but it still shows old content" trap.
			loadedFiles = seed;
			source = t("api.seedSource", seedV, cacheV);
			deleteStaleCache();
		}
		else
		{
			loadedFiles = cache;
			source = "cache";
		}

		if (loadedFiles != null && !loadedFiles.isEmpty())
		{
			this.files = loadedFiles;
			this.loaded = true;
			this.loadedVersion = versionOf(loadedFiles);
		}
		else
		{
			// Loaders will fall back to bundled raw JSON (still present during the
			// migration transition); once that's deleted this would be fatal, which
			// is why the seed must always ship.
			log.error(t("api.log.noCatalog"));
		}

		// Async refresh. Never blocks startup and never re-parses the running task
		// set itself; the plugin picks up a newer catalog at the login screen.
		refreshExecutor.execute(this::refreshCatalog);
	}

	/**
	 * Build version of the catalog this store now serves (from _meta.json), 0 if
	 * unversioned. A successful refresh raises it, which is how the plugin knows to
	 * re-read its task data.
	 */
	public long getCatalogVersion()
	{
		return loadedVersion;
	}

	/**
	 * Parse the monotonic build version out of a catalog's {@code _meta.json}
	 * ({@code {"catalog_version":"yyyyMMddHHmmss"}}). Returns 0 when absent or bad —
	 * so any versioned catalog always beats an old unversioned one.
	 */
	private long versionOf(Map<String, String> catalog)
	{
		if (catalog == null)
		{
			return 0L;
		}
		String meta = catalog.get("_meta.json");
		if (meta == null || meta.isEmpty())
		{
			return 0L;
		}
		try
		{
			com.google.gson.JsonObject o = gson.fromJson(meta, com.google.gson.JsonObject.class);
			if (o != null && o.has("catalog_version"))
			{
				return Long.parseLong(o.get("catalog_version").getAsString().trim());
			}
		}
		catch (RuntimeException e)
		{
			// Unreadable version: treat the catalog as unversioned (0).
		}
		return 0L;
	}

	/** Best-effort removal of a stale disk cache so a newer seed can't lose to it again. */
	private void deleteStaleCache()
	{
		try
		{
			if (catalogFile != null)
			{
				catalogFile.deleteIfExists();
			}
			if (etagFile != null)
			{
				etagFile.deleteIfExists();
			}
		}
		catch (IOException e)
		{
			// A stale cache that can't be deleted is overwritten by the next refresh.
		}
	}

	/** True once a non-empty catalog has been loaded from cache, seed, or network. */
	public boolean isLoaded()
	{
		return loaded;
	}

	/** The JSON content for a catalog file (e.g. {@code "Misthalin_Tasks.json"}), or null. */
	public String getFileContent(String filename)
	{
		String v = files.get(filename);
		if (v != null)
		{
			return v;
		}
		// Per-file fallback to the bundled seed. The active catalog can be a stale
		// on-disk cache (or a server not yet redeployed) that predates a newly added
		// file — e.g. Boss_Tasks.json. Without this, that file is silently missing
		// until the server serves it, and its chunks never load (a boss chunk then
		// looks like an ordinary region and can be unlocked with points). The seed
		// ships every file, so it is the correct floor for anything the cache lacks.
		if (seedFiles == null)
		{
			Map<String, String> s = loadFromSeed();
			seedFiles = s != null ? s : java.util.Collections.emptyMap();
		}
		return seedFiles.get(filename);
	}

	public void shutdown()
	{
		if (refreshExecutor != null)
		{
			refreshExecutor.shutdown();
		}
	}

	// ==================== internals ====================

	private Map<String, String> loadFromDiskCache()
	{
		Filepath f = catalogFile;
		if (f == null || !f.isFile())
		{
			return null;
		}
		try (InputStream is = f.openInputStream())
		{
			return parseCombined(new String(is.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.warn(t("api.log.cacheRead"), e.getMessage());
			return null;
		}
	}

	private Map<String, String> loadFromSeed()
	{
		String[] paths = { SEED_RESOURCE, "/com/chunkblazer/" + SEED_RESOURCE };
		for (String p : paths)
		{
			try (InputStream is = getClass().getResourceAsStream(p))
			{
				if (is != null)
				{
					return parseCombined(gunzipToString(is));
				}
			}
			catch (Exception e)
			{
				log.error(t("api.log.seedFailed"), p, e.getMessage());
			}
		}
		return null;
	}

	/**
	 * The single gated chokepoint for every synchronous networking call. Its body
	 * is only the opt-in check and the call, so no request can reach the network
	 * without {@code serverSyncEnabled} being true (Plugin Hub 3rd-party-networking
	 * rule). Throwing when disabled folds into the caller's existing offline
	 * handling (it keeps last-good on any IOException).
	 */
	private Response executeGated(Request req) throws IOException
	{
		if (!config.apiEnabled())
		{
			throw new IOException("server sync disabled");
		}
		return httpClient.newCall(req).execute();
	}

	private void refreshCatalog()
	{
		if (!config.apiEnabled())
		{
			return;
		}
		String url = config.apiBaseUrl() + CATALOG_URL_PATH;
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
				return;
			}
			if (!resp.isSuccessful())
			{
				log.warn(t("api.log.fetchHttp"), resp.code());
				return; // keep last-good
			}

			ResponseBody body = resp.body();
			String json = body != null
				? new String(HttpBodies.readBounded(body, MAX_CATALOG_BYTES), StandardCharsets.UTF_8) : "";
			if (json.isEmpty())
			{
				return; // empty body is a failure, never "zero tasks"
			}

			Map<String, String> fresh = parseCombined(json);
			if (!isComplete(fresh))
			{
				// Never let a short/partial catalog blank good local data.
				log.warn(t("api.log.incomplete"));
				return;
			}

			// Only a valid, complete 200 rewrites the cache. The plugin re-reads it at
			// the next login screen (or next launch).
			writeAtomic(catalogFile, json.getBytes(StandardCharsets.UTF_8));
			String newEtag = resp.header("ETag");
			if (newEtag != null)
			{
				writeAtomic(etagFile, newEtag.getBytes(StandardCharsets.UTF_8));
			}
			this.files = fresh;
			this.loaded = true;
			this.loadedVersion = versionOf(fresh);
		}
		catch (IOException e)
		{
			// Genuinely offline / network hiccup — benign and common, keep it quiet.
		}
		catch (RuntimeException e)
		{
			// A code/config bug in the refresh path (e.g. the apiEnabled proxy throw)
			// would otherwise surface only as a generic RuneLite "uncaught exception".
			// Attribute it clearly so a dead sync layer never hides again.
			log.warn(t("api.log.refreshCrashed"), e);
		}
	}

	/**
	 * Completeness guard — the key defense against a partial catalog silently
	 * dropping tasks. The fetched catalog must contain EVERY file the current
	 * floor (cache/seed) has, and no value may be empty. A short catalog is
	 * rejected so we keep last-good.
	 */
	private boolean isComplete(Map<String, String> fresh)
	{
		if (fresh == null || fresh.isEmpty())
		{
			return false;
		}
		Map<String, String> current = this.files;
		if (current != null && !current.isEmpty())
		{
			for (String key : current.keySet())
			{
				String v = fresh.get(key);
				if (v == null || v.isEmpty())
				{
					return false;
				}
			}
		}
		for (String v : fresh.values())
		{
			if (v == null || v.isEmpty())
			{
				return false;
			}
		}
		return true;
	}

	private Map<String, String> parseCombined(String json)
	{
		JsonObject obj = gson.fromJson(json, JsonObject.class);
		if (obj == null)
		{
			return null;
		}
		Map<String, String> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : obj.entrySet())
		{
			// Each value is a file's original JSON object; toString() gives the
			// compact JSON the loaders parse via gson.fromJson.
			out.put(e.getKey(), e.getValue().toString());
		}
		return out;
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

	private static String gunzipToString(InputStream is) throws IOException
	{
		try (GZIPInputStream gz = new GZIPInputStream(is))
		{
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = gz.read(buf)) != -1)
			{
				bos.write(buf, 0, n);
			}
			return new String(bos.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private void writeAtomic(Filepath dest, byte[] bytes) throws IOException
	{
		Filepath dir = cacheDir;
		if (dir == null || dest == null)
		{
			return; // cache-less mode: nothing to persist
		}
		Filepath tmp = dir.createTempFile("catalog", ".tmp");
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
}
