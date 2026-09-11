package com.chunkblazer;

import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Test harness for the RSProfile-backed per-account store (migration step 3b).
 *
 * <p>Production reads now go through {@code configManager.getRSProfileConfiguration}
 * and writes through {@code setRSProfileConfiguration}, gated on a non-null
 * {@code getRSProfileKey()}. Rather than rewrite every {@code when(config.X())} stub
 * across the suite, this wires the {@code configManager} mock so that:
 * <ul>
 *   <li>{@code getRSProfileKey()} returns a fixed non-null profile (account is "logged in"),</li>
 *   <li>RSProfile reads return anything WRITTEN during the test first, else delegate to the
 *       existing {@code config.X()} typed-accessor stub — so a test's existing setup stubs
 *       still define initial state, exactly as before the store moved,</li>
 *   <li>RSProfile writes land in the returned map, so a test can assert on what was written
 *       (and later reads see it).</li>
 * </ul>
 * Returns the writes map; keep it in a field and read it in place of the old
 * {@code verify(configManager).setConfiguration(...)} assertions.
 */
final class RsProfileTestSupport
{
	private RsProfileTestSupport()
	{
	}

	static final String GROUP = "chunkblazer";

	static Map<String, String> install(ConfigManager cm, ChunkBlazerConfig config)
	{
		Map<String, String> writes = new HashMap<>();
		lenient().when(cm.getRSProfileKey()).thenReturn("test-profile");
		lenient().when(cm.getRSProfileConfiguration(eq(GROUP), anyString())).thenAnswer(inv ->
		{
			String key = inv.getArgument(1);
			if (writes.containsKey(key))
			{
				return writes.get(key);
			}
			return readFromConfig(config, key);
		});
		lenient().doAnswer(inv ->
		{
			String key = inv.getArgument(1);
			Object val = inv.getArgument(2);
			writes.put(key, val == null ? null : String.valueOf(val));
			return null;
		}).when(cm).setRSProfileConfiguration(eq(GROUP), anyString(), any());
		lenient().doAnswer(inv ->
		{
			writes.remove(inv.getArgument(1));
			return null;
		}).when(cm).unsetRSProfileConfiguration(eq(GROUP), anyString());
		return writes;
	}

	// Mirror what acStr/acInt read: delegate to the typed accessor stub so an unstubbed
	// mock yields the same null/0 the old switch-based helpers saw, and acStr/acInt then
	// apply their defaults identically.
	private static String readFromConfig(ChunkBlazerConfig c, String key)
	{
		switch (key)
		{
			case "unlockedChunks":      return c.unlockedChunks();
			case "completedTasks":      return c.completedTasks();
			case "assignedTasks":       return c.assignedTasks();
			case "regionRolledTasks":   return c.regionRolledTasks();
			case "currentTaskId":       return c.currentTaskId();
			case "taskProgressData":    return c.taskProgressData();
			case "progressionBaseline": return c.progressionBaseline();
			case "unrevealedTasks":     return c.unrevealedTasks();
			case "accountModeHash":     return c.accountModeHash();
			case "gameMode":            return c.gameMode() == null ? null : c.gameMode().name();
			// totalPoints is raw-presence-checked (isPointsBalancePersisted): a mock's
			// default 0 must read as ABSENT (null), not a stored "0", or the derivation
			// treats "nothing known" as "spent everything". A real stored 0 is set via the
			// writes map (balanceStoredAs), which takes precedence over this delegation.
			case "totalPoints":         return c.totalPoints() == 0 ? null : String.valueOf(c.totalPoints());
			case "pointsSpent":         return String.valueOf(c.pointsSpent());
			case "bossTokens":          return String.valueOf(c.bossTokens());
			case "currentTaskQuantity": return String.valueOf(c.currentTaskQuantity());
			case "currentTaskProgress": return String.valueOf(c.currentTaskProgress());
			default:                    return null;
		}
	}
}
