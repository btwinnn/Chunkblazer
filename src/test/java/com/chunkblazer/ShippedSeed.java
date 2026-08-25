package com.chunkblazer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Test-only reader for the bundled task seed ({@code tasks_catalog.json.gz}).
 *
 * <p>Since the JSON→server migration, the individual {@code *_Tasks.json} /
 * {@code Free_Chunks.json} files no longer ship as standalone classpath resources —
 * they are combined into one gzipped seed keyed by filename, exactly what
 * {@code GET /api/tasks} returns and what {@code CatalogStore} reads offline. The
 * "shipped data" tests validate that bundled content, so they pull each file out of
 * the seed the same way the plugin does: gunzip → parse the combined object →
 * {@code value.toString()} for the requested filename.
 */
public final class ShippedSeed
{
	private static final String SEED_RESOURCE = "/com/chunkblazer/tasks_catalog.json.gz";

	private ShippedSeed()
	{
	}

	/**
	 * The original JSON of one file inside the bundled seed (e.g. "Quest_Tasks.json"),
	 * or {@code null} if the seed or that entry is absent.
	 */
	public static String fileContent(String filename) throws Exception
	{
		try (InputStream is = ShippedSeed.class.getResourceAsStream(SEED_RESOURCE))
		{
			if (is == null)
			{
				return null;
			}
			JsonObject combined = new Gson().fromJson(gunzip(is), JsonObject.class);
			if (combined == null)
			{
				return null;
			}
			JsonElement file = combined.get(filename);
			return file == null ? null : file.toString();
		}
	}

	private static String gunzip(InputStream is) throws Exception
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
}
