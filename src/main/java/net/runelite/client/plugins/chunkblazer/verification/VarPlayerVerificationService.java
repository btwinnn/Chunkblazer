package net.runelite.client.plugins.chunkblazer.verification;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Server-side verification using VarPlayer values.
 * These values are synchronized directly from the game server and update immediately.
 * This is MORE reliable than Hiscores (which can have delays).
 */
@Slf4j
@Singleton
public class VarPlayerVerificationService
{
	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	// Map boss names to their VarPlayer KC IDs
	private static final Map<String, Integer> BOSS_KC_VARPS = new HashMap<>();

	// Callbacks for KC changes
	private KillCountChangeListener kcChangeListener;

	static
	{
		// GWD Bosses
		BOSS_KC_VARPS.put("KREEARRA", VarPlayerID.TOTAL_ARMADYL_KILLS);
		BOSS_KC_VARPS.put("KREE'ARRA", VarPlayerID.TOTAL_ARMADYL_KILLS);
		BOSS_KC_VARPS.put("ARMADYL", VarPlayerID.TOTAL_ARMADYL_KILLS);
		BOSS_KC_VARPS.put("GENERAL GRAARDOR", VarPlayerID.TOTAL_BANDOS_KILLS);
		BOSS_KC_VARPS.put("GRAARDOR", VarPlayerID.TOTAL_BANDOS_KILLS);
		BOSS_KC_VARPS.put("BANDOS", VarPlayerID.TOTAL_BANDOS_KILLS);
		BOSS_KC_VARPS.put("COMMANDER ZILYANA", VarPlayerID.TOTAL_SARADOMIN_KILLS);
		BOSS_KC_VARPS.put("ZILYANA", VarPlayerID.TOTAL_SARADOMIN_KILLS);
		BOSS_KC_VARPS.put("SARADOMIN", VarPlayerID.TOTAL_SARADOMIN_KILLS);
		BOSS_KC_VARPS.put("K'RIL TSUTSAROTH", VarPlayerID.TOTAL_ZAMORAK_KILLS);
		BOSS_KC_VARPS.put("KRIL TSUTSAROTH", VarPlayerID.TOTAL_ZAMORAK_KILLS);
		BOSS_KC_VARPS.put("KRIL", VarPlayerID.TOTAL_ZAMORAK_KILLS);
		BOSS_KC_VARPS.put("ZAMORAK", VarPlayerID.TOTAL_ZAMORAK_KILLS);

		// DKs
		BOSS_KC_VARPS.put("DAGANNOTH PRIME", VarPlayerID.TOTAL_PRIME_KILLS);
		BOSS_KC_VARPS.put("PRIME", VarPlayerID.TOTAL_PRIME_KILLS);
		BOSS_KC_VARPS.put("DAGANNOTH REX", VarPlayerID.TOTAL_REX_KILLS);
		BOSS_KC_VARPS.put("REX", VarPlayerID.TOTAL_REX_KILLS);
		BOSS_KC_VARPS.put("DAGANNOTH SUPREME", VarPlayerID.TOTAL_SUPREME_KILLS);
		BOSS_KC_VARPS.put("SUPREME", VarPlayerID.TOTAL_SUPREME_KILLS);

		// Wilderness bosses
		BOSS_KC_VARPS.put("CALLISTO", VarPlayerID.TOTAL_CALLISTO_KILLS);
		BOSS_KC_VARPS.put("VENENATIS", VarPlayerID.TOTAL_VENENATIS_KILLS);
		BOSS_KC_VARPS.put("VET'ION", VarPlayerID.TOTAL_VETION_KILLS);
		BOSS_KC_VARPS.put("VETION", VarPlayerID.TOTAL_VETION_KILLS);
		BOSS_KC_VARPS.put("CHAOS ELEMENTAL", VarPlayerID.TOTAL_CHAOSELE_KILLS);
		BOSS_KC_VARPS.put("CHAOS FANATIC", VarPlayerID.TOTAL_CHAOSFANATIC_KILLS);
		BOSS_KC_VARPS.put("SCORPIA", VarPlayerID.TOTAL_SCORPIA_KILLS);
		BOSS_KC_VARPS.put("CRAZY ARCHAEOLOGIST", VarPlayerID.TOTAL_CRAZYARCHAEOLOGIST_KILLS);
		BOSS_KC_VARPS.put("ARTIO", VarPlayerID.TOTAL_ARTIO_KILLS);
		BOSS_KC_VARPS.put("SPINDEL", VarPlayerID.TOTAL_SPINDEL_KILLS);
		BOSS_KC_VARPS.put("CALVAR'ION", VarPlayerID.TOTAL_CALVARION_KILLS);
		BOSS_KC_VARPS.put("CALVARION", VarPlayerID.TOTAL_CALVARION_KILLS);

		// Slayer bosses
		BOSS_KC_VARPS.put("KRAKEN", VarPlayerID.TOTAL_KRAKEN_BOSS_KILLS);
		BOSS_KC_VARPS.put("THERMONUCLEAR SMOKE DEVIL", VarPlayerID.TOTAL_THERMY_KILLS);
		BOSS_KC_VARPS.put("THERMY", VarPlayerID.TOTAL_THERMY_KILLS);
		BOSS_KC_VARPS.put("CERBERUS", VarPlayerID.TOTAL_CERBERUS_KILLS);
		BOSS_KC_VARPS.put("ABYSSAL SIRE", VarPlayerID.TOTAL_ABYSSALSIRE_KILLS);
		BOSS_KC_VARPS.put("SIRE", VarPlayerID.TOTAL_ABYSSALSIRE_KILLS);
		BOSS_KC_VARPS.put("GROTESQUE GUARDIANS", VarPlayerID.TOTAL_GARGBOSS_KILLS);
		BOSS_KC_VARPS.put("ALCHEMICAL HYDRA", VarPlayerID.TOTAL_HYDRABOSS_KILLS);
		BOSS_KC_VARPS.put("HYDRA", VarPlayerID.TOTAL_HYDRABOSS_KILLS);

		// Other bosses
		BOSS_KC_VARPS.put("KING BLACK DRAGON", VarPlayerID.TOTAL_KBD_KILLS);
		BOSS_KC_VARPS.put("KBD", VarPlayerID.TOTAL_KBD_KILLS);
		BOSS_KC_VARPS.put("GIANT MOLE", VarPlayerID.TOTAL_MOLE_KILLS);
		BOSS_KC_VARPS.put("MOLE", VarPlayerID.TOTAL_MOLE_KILLS);
		BOSS_KC_VARPS.put("KALPHITE QUEEN", VarPlayerID.TOTAL_KALPHITE_KILLS);
		BOSS_KC_VARPS.put("KQ", VarPlayerID.TOTAL_KALPHITE_KILLS);
		BOSS_KC_VARPS.put("CORPOREAL BEAST", VarPlayerID.TOTAL_CORP_KILLS);
		BOSS_KC_VARPS.put("CORP", VarPlayerID.TOTAL_CORP_KILLS);
		BOSS_KC_VARPS.put("ZULRAH", VarPlayerID.TOTAL_SNAKEBOSS_KILLS);
		BOSS_KC_VARPS.put("VORKATH", VarPlayerID.TOTAL_VORKATH_KILLS);
		BOSS_KC_VARPS.put("SKOTIZO", VarPlayerID.TOTAL_CATA_BOSS_KILLS);
		BOSS_KC_VARPS.put("HESPORI", VarPlayerID.TOTAL_HESPORI_KILLS);
		BOSS_KC_VARPS.put("MIMIC", VarPlayerID.TOTAL_MIMIC_KILLS);
		BOSS_KC_VARPS.put("SARACHNIS", VarPlayerID.TOTAL_SARACHNIS_KILLS);

		// F2P bosses
		BOSS_KC_VARPS.put("OBOR", VarPlayerID.TOTAL_HILLGIANT_BOSS_KILLS);
		BOSS_KC_VARPS.put("BRYOPHYTA", VarPlayerID.TOTAL_BRYOPHYTA_KILLS);

		// Raids/Minigames
		BOSS_KC_VARPS.put("WINTERTODT", VarPlayerID.TOTAL_WINTERTODT_KILLS);
		BOSS_KC_VARPS.put("ZALCANO", VarPlayerID.TOTAL_ZALCANO_KILLS);
		BOSS_KC_VARPS.put("TEMPOROSS", VarPlayerID.TOTAL_TEMPOROSS_KILLS);

		// Fight Caves / Inferno
		BOSS_KC_VARPS.put("TZTOK-JAD", VarPlayerID.TOTAL_JAD_KILLS);
		BOSS_KC_VARPS.put("JAD", VarPlayerID.TOTAL_JAD_KILLS);
		BOSS_KC_VARPS.put("TZKAL-ZUK", VarPlayerID.TOTAL_ZUK_KILLS);
		BOSS_KC_VARPS.put("ZUK", VarPlayerID.TOTAL_ZUK_KILLS);

		// Nightmare
		BOSS_KC_VARPS.put("THE NIGHTMARE", VarPlayerID.TOTAL_NIGHTMARE_KILLS);
		BOSS_KC_VARPS.put("NIGHTMARE", VarPlayerID.TOTAL_NIGHTMARE_KILLS);
		BOSS_KC_VARPS.put("PHOSANI'S NIGHTMARE", VarPlayerID.TOTAL_NIGHTMARE_CHALLENGE_KILLS);

		// Nex
		BOSS_KC_VARPS.put("NEX", VarPlayerID.TOTAL_NEX_KILLS);

		// DT2 Bosses
		BOSS_KC_VARPS.put("DUKE SUCELLUS", VarPlayerID.TOTAL_DUKE_SUCELLUS_KILLS);
		BOSS_KC_VARPS.put("DUKE", VarPlayerID.TOTAL_DUKE_SUCELLUS_KILLS);
		BOSS_KC_VARPS.put("THE LEVIATHAN", VarPlayerID.TOTAL_LEVIATHAN_KILLS);
		BOSS_KC_VARPS.put("LEVIATHAN", VarPlayerID.TOTAL_LEVIATHAN_KILLS);
		BOSS_KC_VARPS.put("THE WHISPERER", VarPlayerID.TOTAL_WHISPERER_KILLS);
		BOSS_KC_VARPS.put("WHISPERER", VarPlayerID.TOTAL_WHISPERER_KILLS);
		BOSS_KC_VARPS.put("VARDORVIS", VarPlayerID.TOTAL_VARDORVIS_KILLS);

		// Newer bosses
		BOSS_KC_VARPS.put("PHANTOM MUSPAH", VarPlayerID.TOTAL_MUSPAH_KILLS);
		BOSS_KC_VARPS.put("MUSPAH", VarPlayerID.TOTAL_MUSPAH_KILLS);
		BOSS_KC_VARPS.put("DERANGED ARCHAEOLOGIST", VarPlayerID.TOTAL_DERANGEDARCHAEOLOGIST_KILLS);
		BOSS_KC_VARPS.put("SCURRIUS", VarPlayerID.TOTAL_RAT_BOSS_KILLS);
		BOSS_KC_VARPS.put("SOL HEREDIT", VarPlayerID.TOTAL_SOL_KILLS);
		BOSS_KC_VARPS.put("COLOSSEUM", VarPlayerID.TOTAL_SOL_KILLS);
		BOSS_KC_VARPS.put("ARAXXOR", VarPlayerID.TOTAL_ARAXXOR_KILLS);
		BOSS_KC_VARPS.put("AMOXLIATL", VarPlayerID.TOTAL_AMOXLIATL_KILLS);
		BOSS_KC_VARPS.put("THE HUEYCOATL", VarPlayerID.TOTAL_HUEY_KILLS);
		BOSS_KC_VARPS.put("HUEYCOATL", VarPlayerID.TOTAL_HUEY_KILLS);
		BOSS_KC_VARPS.put("THE ROYAL TITANS", VarPlayerID.TOTAL_ROYAL_TITAN_KILLS);
		BOSS_KC_VARPS.put("YAMA", VarPlayerID.TOTAL_YAMA_KILLS);
	}

	public void startUp()
	{
		eventBus.register(this);
	}

	public void shutDown()
	{
		eventBus.unregister(this);
	}

	/**
	 * Set a listener for KC changes (called when VarPlayer updates).
	 */
	public void setKillCountChangeListener(KillCountChangeListener listener)
	{
		this.kcChangeListener = listener;
	}

	/**
	 * Get the current KC for a boss from VarPlayer (server-side value).
	 */
	public int getBossKillCount(String bossName)
	{
		Integer varpId = getVarpIdForBoss(bossName);
		if (varpId == null)
		{
			return -1;
		}
		return client.getVarpValue(varpId);
	}

	/**
	 * Check if a boss has VarPlayer KC tracking.
	 */
	public boolean isBossTracked(String bossName)
	{
		return getVarpIdForBoss(bossName) != null;
	}

	/**
	 * Get current slayer task count (server-side).
	 */
	public int getSlayerTaskCount()
	{
		return client.getVarpValue(VarPlayerID.SLAYER_COUNT);
	}

	/**
	 * Check if player is on a slayer task.
	 */
	public boolean isOnSlayerTask()
	{
		return getSlayerTaskCount() > 0;
	}

	private Integer getVarpIdForBoss(String bossName)
	{
		if (bossName == null)
		{
			return null;
		}
		return BOSS_KC_VARPS.get(bossName.toUpperCase().trim());
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varpId = event.getVarpId();

		// Check if this is a boss KC varp
		for (Map.Entry<String, Integer> entry : BOSS_KC_VARPS.entrySet())
		{
			if (entry.getValue() == varpId)
			{
				int newKc = client.getVarpValue(varpId);

				if (kcChangeListener != null)
				{
					kcChangeListener.onBossKillCountChanged(entry.getKey(), newKc);
				}
				return;
			}
		}

		// Check slayer count
		if (varpId == VarPlayerID.SLAYER_COUNT)
		{
			int remaining = event.getValue();

			if (kcChangeListener != null)
			{
				kcChangeListener.onSlayerTaskCountChanged(remaining);
			}
		}
	}

	/**
	 * Listener interface for KC changes.
	 */
	public interface KillCountChangeListener
	{
		void onBossKillCountChanged(String bossName, int newKc);
		void onSlayerTaskCountChanged(int remaining);
	}

	/**
	 * Verification result for boss kills.
	 */
	public static class BossKcVerificationResult
	{
		public final boolean verified;
		public final int previousKc;
		public final int currentKc;
		public final String bossName;
		public final String message;

		public BossKcVerificationResult(boolean verified, int previousKc, int currentKc, String bossName, String message)
		{
			this.verified = verified;
			this.previousKc = previousKc;
			this.currentKc = currentKc;
			this.bossName = bossName;
			this.message = message;
		}
	}
}
