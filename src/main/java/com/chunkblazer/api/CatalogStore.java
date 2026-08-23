package com.chunkblazer.api;

import com.chunkblazer.ChunkBlazerConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import net.runelite.client.RuneLite;
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
 *   <li><b>The cache/seed load is SYNCHRONOUS.</b> {@link #init()} must populate
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
	// Bundled gzipped combined catalog, in com/chunkblazer/ (built by
	// build-task-seed.ps1). Offline/first-run floor.
	private static final String SEED_RESOURCE = "tasks_catalog.json.gz";

	private final OkHttpClient httpClient;
	private final ChunkBlazerConfig config;
	private final Gson gson;

	private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "chunkblazer-catalog-refresh");
		t.setDaemon(true);
		return t;
	});

	private final File cacheDir;
	private final File catalogFile;
	private final File etagFile;

	// filename -> that file's JSON content. Written on init()/refresh, read on the
	// game thread by the loaders — volatile publish of an immutable snapshot.
	private volatile Map<String, String> files = Collections.emptyMap();
	private volatile boolean loaded;
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
			.build();
		this.cacheDir = new File(RuneLite.RUNELITE_DIR, "chunkblazer");
		this.catalogFile = new File(cacheDir, "tasks_catalog.json");
		this.etagFile = new File(cacheDir, "tasks_catalog.etag");
	}

	/**
	 * SYNCHRONOUS load order cache → bundled seed, so the task loaders can read
	 * the catalog immediately, then an async server refresh for next launch. MUST
	 * be called before {@code loadChunkData()}/{@code loadGlobalTasks()}/
	 * {@code loadFreeChunks()}.
	 */
	public void init()
	{
		//noinspection ResultOfMethodCallIgnored
		cacheDir.mkdirs();

		Map<String, String> loadedFiles = loadFromDiskCache();
		String source = "cache";
		if (loadedFiles == null || loadedFiles.isEmpty())
		{
			loadedFiles = loadFromSeed();
			source = "seed";
		}
		if (loadedFiles != null && !loadedFiles.isEmpty())
		{
			this.files = loadedFiles;
			this.loaded = true;
			log.debug("Task catalog loaded from {} ({} files)", source, loadedFiles.size());
		}
		else
		{
			// Loaders will fall back to bundled raw JSON (still present during the
			// migration transition); once that's deleted this would be fatal, which
			// is why the seed must always ship.
			log.error("Task catalog: no cache and no seed available");
		}

		// Async refresh for NEXT launch. Never blocks startup; never re-parses the
		// running task set.
		refreshExecutor.execute(this::refreshCatalog);
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

	/** The set of catalog filenames currently loaded. */
	public Set<String> fileNames()
	{
		return files.keySet();
	}

	public void shutdown()
	{
		refreshExecutor.shutdownNow();
	}

	// ==================== internals ====================

	private Map<String, String> loadFromDiskCache()
	{
		if (!catalogFile.isFile())
		{
			return null;
		}
		try
		{
			byte[] b = Files.readAllBytes(catalogFile.toPath());
			return parseCombined(new String(b, StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.warn("Failed to read cached task catalog, will use seed: {}", e.getMessage());
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
				log.error("Failed to load bundled task seed {}: {}", p, e.getMessage());
			}
		}
		return null;
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

		try (Response resp = httpClient.newCall(rb.build()).execute())
		{
			if (resp.code() == 304)
			{
				log.debug("Task catalog unchanged (304)");
				return;
			}
			if (!resp.isSuccessful())
			{
				log.debug("Task catalog fetch returned {}", resp.code());
				return; // keep last-good
			}

			ResponseBody body = resp.body();
			String json = body != null ? body.string() : "";
			if (json.isEmpty())
			{
				return; // empty body is a failure, never "zero tasks"
			}

			Map<String, String> fresh = parseCombined(json);
			if (!isComplete(fresh))
			{
				// Never let a short/partial catalog blank good local data.
				log.warn("Rejecting incomplete task catalog from server");
				return;
			}

			// Only a valid, complete 200 rewrites the cache. Takes effect NEXT launch.
			writeAtomic(catalogFile, json.getBytes(StandardCharsets.UTF_8));
			String newEtag = resp.header("ETag");
			if (newEtag != null)
			{
				writeAtomic(etagFile, newEtag.getBytes(StandardCharsets.UTF_8));
			}
			this.files = fresh;
			this.loaded = true;
			log.debug("Task catalog refreshed from server ({} files)", fresh.size());
		}
		catch (IOException e)
		{
			log.debug("Task catalog fetch failed (offline?): {}", e.getMessage());
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
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
