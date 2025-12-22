package net.runelite.client.plugins.chunkblazer.verification;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.client.hiscore.HiscoreClient;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreResult;
import net.runelite.client.hiscore.HiscoreSkill;

/**
 * Verification service that uses Jagex's official Hiscores API.
 * This provides server-side verification without needing a custom backend.
 */
@Slf4j
@Singleton
public class HiscoreVerificationService
{
    @Inject
    private HiscoreClient hiscoreClient;

    @Inject
    private Client client;

    // Cache to avoid spamming the API
    private HiscoreResult cachedResult;
    private long cacheTimestamp;
    private static final long CACHE_DURATION_MS = 60000; // 1 minute cache

    /**
     * Fetch the player's hiscores data from Jagex's API.
     * This is the official source of truth for skill levels.
     */
    public CompletableFuture<HiscoreResult> fetchHiscores()
    {
        return CompletableFuture.supplyAsync(() -> {
            Player player = client.getLocalPlayer();
            if (player == null || player.getName() == null)
            {
                return null;
            }

            // Check cache first
            if (cachedResult != null && System.currentTimeMillis() - cacheTimestamp < CACHE_DURATION_MS)
            {
                return cachedResult;
            }

            try
            {
                // Determine the correct endpoint based on account type
                HiscoreEndpoint endpoint = getHiscoreEndpoint();
                HiscoreResult result = hiscoreClient.lookup(player.getName(), endpoint);

                // Update cache
                cachedResult = result;
                cacheTimestamp = System.currentTimeMillis();

                log.info("Fetched hiscores for {}", player.getName());
                return result;
            }
            catch (IOException e)
            {
                log.error("Failed to fetch hiscores: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * Verify a skill level against the official hiscores.
     */
    public CompletableFuture<SkillVerificationResult> verifySkillLevel(Skill skill, int expectedLevel)
    {
        return fetchHiscores().thenApply(result -> {
            if (result == null)
            {
                return new SkillVerificationResult(false, -1, "Could not fetch hiscores");
            }

            HiscoreSkill hiscoreSkill = mapToHiscoreSkill(skill);
            if (hiscoreSkill == null)
            {
                return new SkillVerificationResult(false, -1, "Unknown skill");
            }

            net.runelite.client.hiscore.Skill skillData = result.getSkill(hiscoreSkill);
            if (skillData == null)
            {
                return new SkillVerificationResult(false, -1, "Skill not found in hiscores");
            }

            int actualLevel = skillData.getLevel();
            boolean verified = actualLevel >= expectedLevel;

            log.info("Skill verification: {} level {} (expected {}): {}",
                skill.getName(), actualLevel, expectedLevel, verified ? "VERIFIED" : "FAILED");

            return new SkillVerificationResult(verified, actualLevel,
                verified ? "Verified via Jagex Hiscores" : "Level mismatch");
        });
    }

    /**
     * Verify total XP in a skill against hiscores.
     */
    public CompletableFuture<SkillVerificationResult> verifySkillXp(Skill skill, int expectedXp)
    {
        return fetchHiscores().thenApply(result -> {
            if (result == null)
            {
                return new SkillVerificationResult(false, -1, "Could not fetch hiscores");
            }

            HiscoreSkill hiscoreSkill = mapToHiscoreSkill(skill);
            if (hiscoreSkill == null)
            {
                return new SkillVerificationResult(false, -1, "Unknown skill");
            }

            net.runelite.client.hiscore.Skill skillData = result.getSkill(hiscoreSkill);
            if (skillData == null)
            {
                return new SkillVerificationResult(false, -1, "Skill not found in hiscores");
            }

            long actualXp = skillData.getExperience();
            boolean verified = actualXp >= expectedXp;

            log.info("XP verification: {} XP {} (expected {}): {}",
                skill.getName(), actualXp, expectedXp, verified ? "VERIFIED" : "FAILED");

            return new SkillVerificationResult(verified, (int) actualXp,
                verified ? "Verified via Jagex Hiscores" : "XP mismatch");
        });
    }

    /**
     * Get the appropriate hiscore endpoint based on account type.
     */
    private HiscoreEndpoint getHiscoreEndpoint()
    {
        // Could detect ironman status from client
        // For now, default to normal hiscores
        return HiscoreEndpoint.NORMAL;
    }

    /**
     * Map RuneLite Skill enum to Hiscore Skill enum.
     */
    private HiscoreSkill mapToHiscoreSkill(Skill skill)
    {
        switch (skill)
        {
            case ATTACK: return HiscoreSkill.ATTACK;
            case DEFENCE: return HiscoreSkill.DEFENCE;
            case STRENGTH: return HiscoreSkill.STRENGTH;
            case HITPOINTS: return HiscoreSkill.HITPOINTS;
            case RANGED: return HiscoreSkill.RANGED;
            case PRAYER: return HiscoreSkill.PRAYER;
            case MAGIC: return HiscoreSkill.MAGIC;
            case COOKING: return HiscoreSkill.COOKING;
            case WOODCUTTING: return HiscoreSkill.WOODCUTTING;
            case FLETCHING: return HiscoreSkill.FLETCHING;
            case FISHING: return HiscoreSkill.FISHING;
            case FIREMAKING: return HiscoreSkill.FIREMAKING;
            case CRAFTING: return HiscoreSkill.CRAFTING;
            case SMITHING: return HiscoreSkill.SMITHING;
            case MINING: return HiscoreSkill.MINING;
            case HERBLORE: return HiscoreSkill.HERBLORE;
            case AGILITY: return HiscoreSkill.AGILITY;
            case THIEVING: return HiscoreSkill.THIEVING;
            case SLAYER: return HiscoreSkill.SLAYER;
            case FARMING: return HiscoreSkill.FARMING;
            case RUNECRAFT: return HiscoreSkill.RUNECRAFT;
            case HUNTER: return HiscoreSkill.HUNTER;
            case CONSTRUCTION: return HiscoreSkill.CONSTRUCTION;
            default: return null;
        }
    }

    /**
     * Verify a boss kill count increased (for boss NPC kills).
     * Call this BEFORE the kill to get baseline KC, then after to verify increment.
     *
     * @param bossName The boss name (must match HiscoreSkill enum name)
     * @param expectedMinKc The minimum KC expected after the kill
     * @return Verification result with actual KC
     */
    public CompletableFuture<BossVerificationResult> verifyBossKillCount(String bossName, int expectedMinKc)
    {
        return fetchHiscores().thenApply(result -> {
            if (result == null)
            {
                return new BossVerificationResult(false, -1, "Could not fetch hiscores", false);
            }

            HiscoreSkill bossSkill = mapBossNameToHiscoreSkill(bossName);
            if (bossSkill == null)
            {
                // Not a tracked boss - can't verify via hiscores
                return new BossVerificationResult(false, -1, "Boss not tracked in hiscores", false);
            }

            net.runelite.client.hiscore.Skill bossData = result.getSkill(bossSkill);
            if (bossData == null || bossData.getLevel() < 0)
            {
                // Player hasn't killed this boss enough to be on hiscores
                return new BossVerificationResult(false, 0, "No KC on hiscores yet", true);
            }

            int actualKc = bossData.getLevel(); // For bosses, "level" is the KC
            boolean verified = actualKc >= expectedMinKc;

            log.info("Boss KC verification: {} KC {} (expected >= {}): {}",
                bossName, actualKc, expectedMinKc, verified ? "VERIFIED" : "PENDING");

            return new BossVerificationResult(verified, actualKc,
                verified ? "Verified via Jagex Hiscores" : "KC not yet updated", true);
        });
    }

    /**
     * Get the current boss KC from hiscores (for baseline before a kill).
     */
    public CompletableFuture<Integer> getBossKillCount(String bossName)
    {
        return fetchHiscores().thenApply(result -> {
            if (result == null)
            {
                return -1;
            }

            HiscoreSkill bossSkill = mapBossNameToHiscoreSkill(bossName);
            if (bossSkill == null)
            {
                return -1;
            }

            net.runelite.client.hiscore.Skill bossData = result.getSkill(bossSkill);
            if (bossData == null || bossData.getLevel() < 0)
            {
                return 0;
            }

            return bossData.getLevel();
        });
    }

    /**
     * Check if a boss is trackable via hiscores.
     */
    public boolean isBossTrackable(String bossName)
    {
        return mapBossNameToHiscoreSkill(bossName) != null;
    }

    /**
     * Map boss name to HiscoreSkill enum.
     * Supports various name formats (e.g., "Zulrah", "ZULRAH", "zulrah").
     */
    private HiscoreSkill mapBossNameToHiscoreSkill(String bossName)
    {
        if (bossName == null)
        {
            return null;
        }

        String normalized = bossName.toUpperCase().trim()
            .replace(" ", "_")
            .replace("'", "")
            .replace("-", "_");

        // Direct mapping for common bosses
        switch (normalized)
        {
            case "ZULRAH": return HiscoreSkill.ZULRAH;
            case "VORKATH": return HiscoreSkill.VORKATH;
            case "CORPOREAL_BEAST":
            case "CORP": return HiscoreSkill.CORPOREAL_BEAST;
            case "CERBERUS": return HiscoreSkill.CERBERUS;
            case "ALCHEMICAL_HYDRA":
            case "HYDRA": return HiscoreSkill.ALCHEMICAL_HYDRA;
            case "ABYSSAL_SIRE":
            case "SIRE": return HiscoreSkill.ABYSSAL_SIRE;
            case "KRAKEN": return HiscoreSkill.KRAKEN;
            case "THERMONUCLEAR_SMOKE_DEVIL":
            case "THERMY": return HiscoreSkill.THERMONUCLEAR_SMOKE_DEVIL;
            case "GIANT_MOLE":
            case "MOLE": return HiscoreSkill.GIANT_MOLE;
            case "KING_BLACK_DRAGON":
            case "KBD": return HiscoreSkill.KING_BLACK_DRAGON;
            case "KALPHITE_QUEEN":
            case "KQ": return HiscoreSkill.KALPHITE_QUEEN;
            case "CHAOS_ELEMENTAL": return HiscoreSkill.CHAOS_ELEMENTAL;
            case "CHAOS_FANATIC": return HiscoreSkill.CHAOS_FANATIC;
            case "CRAZY_ARCHAEOLOGIST": return HiscoreSkill.CRAZY_ARCHAEOLOGIST;
            case "SCORPIA": return HiscoreSkill.SCORPIA;
            case "CALLISTO": return HiscoreSkill.CALLISTO;
            case "ARTIO": return HiscoreSkill.ARTIO;
            case "VETION":
            case "VET_ION": return HiscoreSkill.VETION;
            case "CALVARION": return HiscoreSkill.CALVARION;
            case "VENENATIS": return HiscoreSkill.VENENATIS;
            case "SPINDEL": return HiscoreSkill.SPINDEL;
            case "SARACHNIS": return HiscoreSkill.SARACHNIS;
            case "DAGANNOTH_PRIME":
            case "PRIME": return HiscoreSkill.DAGANNOTH_PRIME;
            case "DAGANNOTH_REX":
            case "REX": return HiscoreSkill.DAGANNOTH_REX;
            case "DAGANNOTH_SUPREME":
            case "SUPREME": return HiscoreSkill.DAGANNOTH_SUPREME;
            case "GENERAL_GRAARDOR":
            case "GRAARDOR":
            case "BANDOS": return HiscoreSkill.GENERAL_GRAARDOR;
            case "COMMANDER_ZILYANA":
            case "ZILYANA":
            case "SARA":
            case "SARADOMIN": return HiscoreSkill.COMMANDER_ZILYANA;
            case "KREEARRA":
            case "KREE_ARRA":
            case "ARMA":
            case "ARMADYL": return HiscoreSkill.KREEARRA;
            case "KRIL_TSUTSAROTH":
            case "KRIL":
            case "ZAMMY":
            case "ZAMORAK": return HiscoreSkill.KRIL_TSUTSAROTH;
            case "NEX": return HiscoreSkill.NEX;
            case "NIGHTMARE":
            case "THE_NIGHTMARE": return HiscoreSkill.NIGHTMARE;
            case "PHOSANIS_NIGHTMARE": return HiscoreSkill.PHOSANIS_NIGHTMARE;
            case "TEMPOROSS": return HiscoreSkill.TEMPOROSS;
            case "WINTERTODT": return HiscoreSkill.WINTERTODT;
            case "ZALCANO": return HiscoreSkill.ZALCANO;
            case "HESPORI": return HiscoreSkill.HESPORI;
            case "SKOTIZO": return HiscoreSkill.SKOTIZO;
            case "GROTESQUE_GUARDIANS":
            case "GGS": return HiscoreSkill.GROTESQUE_GUARDIANS;
            case "OBOR": return HiscoreSkill.OBOR;
            case "BRYOPHYTA": return HiscoreSkill.BRYOPHYTA;
            case "SCURRIUS": return HiscoreSkill.SCURRIUS;
            case "MIMIC": return HiscoreSkill.MIMIC;
            case "THE_GAUNTLET":
            case "GAUNTLET": return HiscoreSkill.THE_GAUNTLET;
            case "THE_CORRUPTED_GAUNTLET":
            case "CORRUPTED_GAUNTLET":
            case "CG": return HiscoreSkill.THE_CORRUPTED_GAUNTLET;
            case "CHAMBERS_OF_XERIC":
            case "COX":
            case "RAIDS": return HiscoreSkill.CHAMBERS_OF_XERIC;
            case "CHAMBERS_OF_XERIC_CHALLENGE_MODE":
            case "COX_CM": return HiscoreSkill.CHAMBERS_OF_XERIC_CHALLENGE_MODE;
            case "THEATRE_OF_BLOOD":
            case "TOB": return HiscoreSkill.THEATRE_OF_BLOOD;
            case "THEATRE_OF_BLOOD_HARD_MODE":
            case "TOB_HM": return HiscoreSkill.THEATRE_OF_BLOOD_HARD_MODE;
            case "TOMBS_OF_AMASCUT":
            case "TOA": return HiscoreSkill.TOMBS_OF_AMASCUT;
            case "TOMBS_OF_AMASCUT_EXPERT":
            case "TOA_EXPERT": return HiscoreSkill.TOMBS_OF_AMASCUT_EXPERT;
            case "TZTOK_JAD":
            case "JAD":
            case "FIGHT_CAVES": return HiscoreSkill.TZTOK_JAD;
            case "TZKAL_ZUK":
            case "ZUK":
            case "INFERNO": return HiscoreSkill.TZKAL_ZUK;
            case "PHANTOM_MUSPAH":
            case "MUSPAH": return HiscoreSkill.PHANTOM_MUSPAH;
            case "DUKE_SUCELLUS":
            case "DUKE": return HiscoreSkill.DUKE_SUCELLUS;
            case "THE_LEVIATHAN":
            case "LEVIATHAN": return HiscoreSkill.THE_LEVIATHAN;
            case "THE_WHISPERER":
            case "WHISPERER": return HiscoreSkill.THE_WHISPERER;
            case "VARDORVIS": return HiscoreSkill.VARDORVIS;
            case "ARAXXOR": return HiscoreSkill.ARAXXOR;
            case "BARROWS":
            case "BARROWS_CHESTS": return HiscoreSkill.BARROWS_CHESTS;
            case "SOL_HEREDIT":
            case "COLOSSEUM": return HiscoreSkill.SOL_HEREDIT;
            default:
                log.debug("Unknown boss for hiscore lookup: {}", bossName);
                return null;
        }
    }

    /**
     * Clear the cache (call when player logs out or changes).
     */
    public void clearCache()
    {
        cachedResult = null;
        cacheTimestamp = 0;
    }

    /**
     * Result of a boss kill count verification.
     */
    public static class BossVerificationResult
    {
        public final boolean verified;
        public final int actualKc;
        public final String message;
        public final boolean isTrackedBoss; // true if this boss has hiscore tracking

        public BossVerificationResult(boolean verified, int actualKc, String message, boolean isTrackedBoss)
        {
            this.verified = verified;
            this.actualKc = actualKc;
            this.message = message;
            this.isTrackedBoss = isTrackedBoss;
        }
    }

    /**
     * Result of a skill verification check.
     */
    public static class SkillVerificationResult
    {
        public final boolean verified;
        public final int actualValue;
        public final String message;

        public SkillVerificationResult(boolean verified, int actualValue, String message)
        {
            this.verified = verified;
            this.actualValue = actualValue;
            this.message = message;
        }
    }
}
