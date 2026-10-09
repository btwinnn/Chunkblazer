package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

/**
 * Proves each api text moved to strings.properties renders exactly as the
 * original Java expression did.
 */
public class StringsApiTest
{
	private static String log(String key, Object... args)
	{
		return MessageFormatter.arrayFormat(Strings.t(key), args).getMessage();
	}

	private static String orig(String pattern, Object... args)
	{
		return MessageFormatter.arrayFormat(pattern, args).getMessage();
	}

	@Test
	public void text()
	{
		long seedV = 20260101120000L;
		long cacheV = 20250101120000L;
		assertEquals("seed (v" + seedV + " newer than stale cache v" + cacheV + ")",
			Strings.t("api.seedSource", seedV, cacheV));
		assertEquals("Not logged in to server", Strings.t("api.noLogin"));
	}

	@Test
	public void catalogLogs()
	{
		String msg = "disk full";
		String p = "/com/chunkblazer/tasks_catalog.json.gz";
		int code = 503;
		assertEquals(orig("Could not create catalog cache dir: {}", msg),
			log("api.log.cacheDir", msg));
		assertEquals(orig("Task catalog: no cache and no seed available"),
			log("api.log.noCatalog"));
		assertEquals(orig("Failed to read cached task catalog, will use seed: {}", msg),
			log("api.log.cacheRead", msg));
		assertEquals(orig("Failed to load bundled task seed {}: {}", p, msg),
			log("api.log.seedFailed", p, msg));
		assertEquals(orig("Task catalog fetch returned HTTP {}, keeping last-good; server sync may be stale", code),
			log("api.log.fetchHttp", code));
		assertEquals(orig("Rejecting incomplete task catalog from server"),
			log("api.log.incomplete"));
		assertEquals(orig("Task catalog refresh crashed, server sync is down this session"),
			log("api.log.refreshCrashed"));
	}

	@Test
	public void clientLogs()
	{
		int code = 500;
		String body = "{\"error\":\"boom\"}";
		assertEquals(orig("Cannot lock mode: no API key (neither login-issued nor configured)"),
			log("api.log.noKey"));
		assertEquals(orig("Player sync returned an empty body"),
			log("api.log.syncEmpty"));
		assertEquals(orig("Player sync returned error {}: {}", code, body),
			log("api.log.syncError", code, body));
	}
}
