package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

/**
 * Every ChunkBlazerPlugin string moved to strings.properties reproduces the
 * original Java expression exactly. Each expected value is the expression copied
 * from the code before the move, with sample values in place of its variables.
 */
public class StringsPluginTest
{
	private static void assertLog(String original, String key, Object[] samples)
	{
		assertEquals(original, Strings.t(key));
		assertEquals(MessageFormatter.arrayFormat(original, samples).getMessage(),
			MessageFormatter.arrayFormat(Strings.t(key), samples).getMessage());
	}

	@Test
	public void pluginStringsMatchOriginals()
	{
		// line 330
		assertLog("Could not resolve plugin data directory; running without disk cache",
			"plugin.log.dataDirUnresolved", new Object[]{});
		// line 621
		assertEquals("ChunkBlazer is paused on this world. Deadman, Leagues and other " + "special worlds don't count. Hop to a normal world to keep blazing.",
			Strings.t("plugin.pausedOnWorld"));
		// line 673
		assertLog("[CHUNKBLAZER] skipping logout sync, RS profile already cleared, so " + "per-account state is unreadable and a sync would push empty over the record",
			"plugin.log.skipLogoutSync", new Object[]{});
		// line 859
		assertEquals("Open the ChunkBlazer panel and choose Enable Sync or Play offline " + "to get your starting tasks.",
			Strings.t("plugin.chooseSyncOrOffline"));
		// line 1009
		assertEquals("You need a Boss Token to unlock " + "S0" + ".",
			Strings.t("plugin.needBossToken", "S0"));
		// line 1017
		assertEquals("Unlock " + "S0" + " for 1 Boss Token? " + "(Remaining: " + 8 + ")",
			Strings.t("plugin.unlockForBossToken", "S0", 8));
		// line 1028
		assertEquals("You need " + 7 + " more points to unlock " + "S1" + ".",
			Strings.t("plugin.needMorePoints", 7, "S1"));
		// line 1036
		assertEquals("Unlock " + "S0" + " for " + 8 + " points? " + "(Remaining: " + 9 + ")",
			Strings.t("plugin.unlockForPoints", "S0", 8, 9));
		// line 1188
		assertLog("Starting chunk {} has no chunk or tasks defined",
			"plugin.log.startChunkUndefined", new Object[]{"S0"});
		// line 1295
		assertLog("JSON PARSE ERROR for {}: {}",
			"plugin.log.jsonParseError", new Object[]{"S0", 8});
		// line 1337
		assertLog("Chunk '{}' in {} has null regionIds!",
			"plugin.log.chunkNullRegionIds", new Object[]{"S0", 8});
		// line 1346
		assertLog("No chunks found in {} for key {}",
			"plugin.log.noChunksFound", new Object[]{"S0", 8});
		// line 1351
		assertLog("Failed to parse {} - data is null or empty",
			"plugin.log.parseFailedEmpty", new Object[]{"S0"});
		// line 1356
		assertLog("Failed to load chunk data from {}: {}",
			"plugin.log.chunkLoadFailed", new Object[]{"S0", 8});
		// line 1372
		assertLog(">>> LUMBRIDGE (12850) NOT FOUND in chunksByRegionId!",
			"plugin.log.lumbridgeNotFound", new Object[]{});
		// line 1377
		assertLog("NO REGIONS LOADED! chunksByRegionId is empty!",
			"plugin.log.noRegionsLoaded", new Object[]{});
		// line 1452
		assertLog("[CHUNKBLAZER] task schema error in '{}': {}",
			"plugin.log.taskSchemaError", new Object[]{"S0", 8});
		// line 1487
		assertLog("Global Tasks loaded but contained ZERO tasks, check the region-group wrapper",
			"plugin.log.globalTasksEmpty", new Object[]{});
		// line 1532
		assertLog("Failed to load Global Tasks from {}: {}",
			"plugin.log.globalTasksLoadFailed", new Object[]{"S0", 8});
		// line 1672
		assertEquals("Global Tasks: " + 7 + " already complete (+" + 8 + " points).",
			Strings.t("plugin.globalTasksBackfilled", 7, 8));
		// line 2031
		assertEquals("progression baseline repair dropped " + 7 + " progression task(s)",
			Strings.t("plugin.log.progressionRepairReason", 7));
		// line 2038
		assertLog("[CHUNKBLAZER] repaired a bogus all-zeros Progression baseline: " + "cleared baseline, un-completed {} progression tasks, refunded {} points",
			"plugin.log.progressionBaselineRepaired", new Object[]{"S0", 8});
		// line 2238
		assertLog("Failed to load Free_Chunks.json: {}",
			"plugin.log.freeChunksLoadFailed", new Object[]{"S0"});
		// line 2303
		assertLog("Cannot lock game mode: player not logged in",
			"plugin.log.cannotLockMode", new Object[]{});
		// line 2329
		assertEquals("Competitive mode needs a connection to the ChunkBlazer server to verify your RuneScape account. Please enable Server Sync and try again.",
			Strings.t("plugin.competitiveNeedsServer"));
		// line 2339
		assertEquals("Log in fully before selecting Competitive.",
			Strings.t("plugin.logInFully"));
		// line 2364
		assertEquals("Sorry, your account does not meet the Competitive requirements, " + "please create a new account or play Casual Mode." + "S0",
			Strings.t("plugin.notEligible", "S0"));
		// line 2371
		assertLog("[CHUNKBLAZER] Competitive eligibility refused: reason='{}' " + "submitted combat={} questPoints={} totalLevel={} skills={}",
			"plugin.log.eligibilityRefused", new Object[]{"S0", 8, "S2", 10, "S4"});
		// line 2420
		assertEquals("Couldn't reach the server to verify your account. Try again shortly.",
			Strings.t("plugin.verifyUnreachable"));
		// line 2430
		assertEquals("Your account meets the Competitive requirements. Locking it in!",
			Strings.t("plugin.meetsRequirementsLocking"));
		// line 2438
		assertEquals("Couldn't issue a verification code right now. Try again shortly.",
			Strings.t("plugin.verifyCodeFailed"));
		// line 2443
		assertEquals("Your account meets the Competitive requirements! Type " + "S0" + " in public chat and hit Enter to lock in Competitive.",
			Strings.t("plugin.typeNonceToLock", "S0"));
		// line 2459
		assertLog("Cannot lock game mode: player not logged in",
			"plugin.log.cannotLockMode", new Object[]{});
		// line 2489
		assertEquals("Couldn't reach the server to confirm Competitive. Staying on Casual, try again later.",
			Strings.t("plugin.confirmUnreachable"));
		// line 2500
		assertEquals("Competitive locked in. Good luck, there's no going back!",
			Strings.t("plugin.competitiveLocked"));
		// line 2505
		assertLog("Server already had a locked mode: {}",
			"plugin.log.serverAlreadyLocked", new Object[]{"S0"});
		// line 2513
		assertEquals("Competitive was declined by the server. Your account isn't eligible, so you're staying on Casual.",
			Strings.t("plugin.competitiveDeclined"));
		// line 2515
		assertLog("Server lock-mode response: status={} message={}",
			"plugin.log.lockModeResponse", new Object[]{"S0", 8});
		// line 2523
		assertEquals("Competitive mode needs a connection to the ChunkBlazer server to verify your RuneScape account. Please enable Server Sync and try again.",
			Strings.t("plugin.competitiveNeedsServer"));
		// line 2602
		assertEquals("charter seed strip removed " + 7 + " charter chunk(s)",
			Strings.t("plugin.log.charterStripReason", 7));
		// line 2745
		assertEquals("Server Sync enabled. Log in and pick Competitive to continue.",
			Strings.t("plugin.syncEnabled"));
		// line 2762
		assertLog("apiClient is null; Guice injection failed for the plugin",
			"plugin.log.apiClientNull", new Object[]{});
		// line 2777
		assertEquals("New ChunkBlazer sign-ups are closed right now, so this account " + "isn't syncing. You can keep playing, and your progress is saved locally.",
			Strings.t("plugin.signupsClosed"));
		// line 2795
		assertEquals("That sync key belongs to a different account, so it " + "was ignored and your saved key kept.",
			Strings.t("plugin.keyOtherAccount"));
		// line 2820
		assertEquals("Sync key accepted. This account is now synced with it.",
			Strings.t("plugin.keyAccepted"));
		// line 2830
		assertEquals("That sync key wasn't recognised for this account, so it " + "was ignored and your saved key kept.",
			Strings.t("plugin.keyNotRecognised"));
		// line 2906
		assertLog("[CHUNKBLAZER] ignored a recovery key that is not a valid account-key format",
			"plugin.log.invalidRecoveryKey", new Object[]{});
		// line 2908
		assertEquals("That sync key doesn't look valid, so it was ignored. Your saved key is unchanged.",
			Strings.t("plugin.keyInvalid"));
		// line 3429
		assertEquals("Account verified! Locking in Competitive...",
			Strings.t("plugin.verifiedLocking"));
		// line 3451
		assertEquals("That code didn't work - it may have expired. Issuing a fresh one...",
			Strings.t("plugin.codeFailed"));
		// line 3507
		assertEquals("Type " + "S0" + " in public chat and hit Enter to verify your ChunkBlazer account.",
			Strings.t("plugin.typeNonceToVerify", "S0"));
		// line 3760
		assertLog("[CHUNKBLAZER] per-account write '{}' refused, no RS profile is active, so an " + "RSProfile write would be silently dropped. This caller must be gated on the profile.",
			"plugin.log.accountWriteRefused", new Object[]{"S0"});
		// line 4168
		assertEquals("Your game mode was changed to " + "S0" + ".",
			Strings.t("plugin.gameModeChanged", "S0"));
		// line 4219
		assertEquals("Your account isn't eligible for Competitive, so ChunkBlazer set it back to Casual.",
			Strings.t("plugin.notEligibleReset"));
		// line 4328
		assertEquals("Reconnected to the ChunkBlazer server. Your progress is syncing again.",
			Strings.t("plugin.reconnected"));
		// line 4338
		assertEquals("Can't reach the ChunkBlazer server right now. Your progress is saved " + "locally and will sync once it's back.",
			Strings.t("plugin.serverUnreachable"));
		// line 4431
		assertLog("[CHUNKBLAZER] server rejected this account's sync key; sync paused " + "until a different key is supplied",
			"plugin.log.syncKeyRejected", new Object[]{});
		// line 4433
		assertEquals("The server didn't accept this account's sync key, so syncing " + "is paused. Your progress is still saved locally. Paste this account's key " + "into Sync recovery key in the ChunkBlazer settings to resume.",
			Strings.t("plugin.syncKeyRejected"));
		// line 4485
		assertLog("[CHUNKBLAZER] intentional reset declared ({}), the next sync will be " + "allowed to drop progress server-side",
			"plugin.log.intentionalReset", new Object[]{"S0"});
		// line 4834
		assertLog("PROGRESS REGRESSION: task '{}' (id={}) in-memory={}, restoring from config={}, caller stack:",
			"plugin.log.progressRegression", new Object[]{"S0", 8, "S2", 10});
		// line 5046
		assertLog("rollTasksForRegion: Chunk {} ({}) has no tasks",
			"plugin.log.noTasksForChunk", new Object[]{"S0", 8});
		// line 5210
		assertEquals("S0" + " was retired, so " + "S1" + " gave you " + "S2" + " instead.",
			Strings.t("plugin.taskRetiredReplaced", "S0", "S1", "S2"));
		// line 5215
		assertEquals("S0" + " was retired. " + "S1" + " has no other tasks left to give.",
			Strings.t("plugin.taskRetiredNone", "S0", "S1"));
		// line 6240
		assertLog("Failed to flush ChunkBlazer config to disk",
			"plugin.log.configFlushFailed", new Object[]{});
		// line 6312
		assertLog("[CHUNKBLAZER] spend counter exceeds lifetime earnings: spent {} > earned {}. " + "The balance is pinned at 0 and every point earned will vanish on the next " + "recompute. Expect migrateRepairImpossiblePointsSpent() to correct this on login.",
			"plugin.log.spendExceedsEarned", new Object[]{"S0", 8});
		// line 6452
		assertLog("[CHUNKBLAZER] impossible spend counter repaired: spent {} exceeds the {} its {} owned " + "chunk(s) could ever cost (lifetime earned {}). Rebuilt from the chunks actually owned: " + "spent = {}. Balance goes {} to {}.",
			"plugin.log.spendRepaired", new Object[]{"S0", 8, "S2", 10, "S4", 12, "S6"});
		// line 6511
		assertLog("unlockBossRegion({}) called for a non-boss chunk",
			"plugin.log.bossNotBoss", new Object[]{"S0"});
		// line 6521
		assertLog("unlockBossRegion({}) refused, not adjacent to any unlocked chunk",
			"plugin.log.bossNotAdjacent", new Object[]{"S0"});
		// line 6522
		assertEquals("That boss chunk isn't adjacent to your unlocked area yet.",
			Strings.t("plugin.bossNotAdjacent"));
		// line 6530
		assertEquals("You need a Boss Token to unlock " + "S0" + ".",
			Strings.t("plugin.needBossToken", "S0"));
		// line 6554
		assertEquals("Unlocked boss chunk " + "S0" + " for 1 Boss Token. " + 8 + " remaining.",
			Strings.t("plugin.bossUnlocked", "S0", 8));
		// line 6658
		assertEquals("First clear recorded. +1 Boss Token earned!",
			Strings.t("plugin.firstClear"));
		// line 6789
		assertLog("Invalid region ID in unlocked list: {}",
			"plugin.log.invalidUnlockedRegion", new Object[]{"S0"});
		// line 7041
		assertEquals("Chunks can't be unlocked on this world. Hop to a normal world first.",
			Strings.t("plugin.worldNoUnlock"));
		// line 7081
		assertLog("Not enough points to unlock region {}. Need {} but have {}",
			"plugin.log.notEnoughPoints", new Object[]{"S0", 8, "S2"});
		// line 7097
		assertLog("unlockRegion({}) refused, region is not adjacent to any unlocked chunk (neighbors: {})",
			"plugin.log.notAdjacent", new Object[]{"S0", 8});
		// line 7144
		assertEquals("Unlocked " + "S0" + " for " + 8 + "S2" + 10 + " remaining.",
			Strings.t("plugin.unlocked", "S0", 8, "S2", 10));
	}

	@Test
	public void conditionalArgsMatchOriginals()
	{
		// The original conditional suffix (line 2364), passed as the arg as before.
		for (String reason : new String[]{null, "", "Hitpoints must be level 10"})
		{
			assertEquals("Sorry, your account does not meet the Competitive requirements, "
					+ "please create a new account or play Casual Mode."
					+ (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"),
				Strings.t("plugin.notEligible", reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
		}
		// The original singular/plural unlock message (line 7144).
		String region = "Lumbridge (12850)";
		int currentPoints = 10;
		for (int cost = 1; cost <= 2; cost++)
		{
			assertEquals("Unlocked " + region + " for " + cost
					+ (cost == 1 ? " point. " : " points. ") + (currentPoints - cost) + " remaining.",
				Strings.t("plugin.unlocked", region, cost, cost == 1 ? " point. " : " points. ", currentPoints - cost));
		}
	}
}
