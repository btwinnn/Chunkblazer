package com.chunkblazer.modules;

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RaidChallenge;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Hitsplat;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemStats;
import net.runelite.http.api.item.ItemEquipmentStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RaidChallengeModule} — the data-driven raid/boss challenge
 * engine that powers all ToA and CoX "how you did the fight" tasks.
 *
 * <p>Every test drives the REAL event methods (onHitsplatApplied → onGameTick →
 * onActorDeath, or onChatMessage) exactly as the client would, and asserts on the
 * observable outcome: {@code task.isCompleted()}. Each primitive we built for the
 * two raids has a passing case and a violating case, so a regression in any one of
 * them turns this suite red.
 *
 * <p>Fight model reminder: a {@code defeat_npc} task's "window" is the ENCOUNTER —
 * it opens on the first hit to a target NPC and closes when that NPC dies. Sustained
 * conditions (no-run, weapon, prayers, gear, worn/held items) are sampled every tick
 * while the encounter is live, and a single violation taints the attempt.
 */
@ExtendWith(MockitoExtension.class)
class RaidChallengeModuleTest extends AbstractTaskModuleTest
{
	// ── CoX / ToA npc + item ids used across the cases ───────────────────────
	private static final int ICE_DEMON = 7584;
	private static final int SHAMAN = 7573;
	private static final int VASA = 7566;
	private static final int OLM_HEAD = 7551;
	private static final int RANGED_VANGUARD = 7528;
	private static final int MYSTIC_A = 7604, MYSTIC_B = 7605;
	private static final int TEKTON = 7545;

	private static final int DRAGON_SCIMITAR = 4587;
	private static final int DRAGON_WARHAMMER = 13576;
	private static final int BAG_OF_SALT = 4161;
	private static final int PRIEST_TOP = 426, PRIEST_BOTTOM = 428;

	// Equipment slot indices.
	private static final int HEAD = 0, WEAPON = 3, BODY = 4, LEGS = 7, BOOTS = 10;

	// Prayer bits inside the ACTIVE_PRAYERS varbit (4101).
	private static final int VARBIT_ACTIVE_PRAYERS = 4101;
	private static final int VARP_RUN = 173;
	private static final int PROT_MAGIC = 12, PROT_MELEE = 14;

	// Weapon-type varbit + attack-style varp (for style resolution).
	private static final int VARBIT_WEAPON_TYPE = 357;
	private static final int VARP_ATTACK_STYLE = 43;

	@Mock
	private ChatMessageManager chatMessageManager;
	@Mock
	private ItemManager itemManager;

	@InjectMocks
	private RaidChallengeModule module;

	private TobModeTracker tobMode;

	private final Map<Integer, NPC> npcs = new HashMap<>();

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();
		injectField(module, "client", client);
		injectField(module, "clientThread", clientThread);
		injectField(module, "eventBus", eventBus);
		injectField(module, "config", config);
		injectField(module, "itemManager", itemManager);
		injectField(module, "chatMessageManager", chatMessageManager);
		tobMode = new TobModeTracker();
		injectField(module, "tobMode", tobMode);
		module.setCompletionCallback(completionCallback);

		// raidLevel()/gatesPass() only read varbits on the client thread.
		lenient().when(client.isClientThread()).thenReturn(true);
		lenient().when(client.getTickCount()).thenReturn(100);
	}

	// ── defeat_npc: the base "defeat X" completion ───────────────────────────

	@Test
	void defeatNpc_killCompletesTheTask()
	{
		NuzlockeTask t = addTask("cox_ice_carving", c -> c.setDefeatNpcIds(Arrays.asList(ICE_DEMON)));
		encounterKill(ICE_DEMON);
		assertTrue(t.isCompleted(), "a plain defeat_npc task completes when the target dies");
	}

	@Test
	void defeatNpc_deathWithNoEncounterDoesNotComplete()
	{
		NuzlockeTask t = addTask("cox_ice_carving", c -> c.setDefeatNpcIds(Arrays.asList(ICE_DEMON)));
		fireDeath(ICE_DEMON); // it dies but we never engaged it (no hit → no encounter)
		assertFalse(t.isCompleted(), "a kill we took no part in must not credit");
	}

	// ── no_run (Don't Slip / Walking the Dog / Hotfoot) ──────────────────────

	@Test
	void noRun_walkingCompletes()
	{
		NuzlockeTask t = addTask("cox_dont_slip", c -> {
			c.setDefeatNpcIds(Arrays.asList(ICE_DEMON));
			c.setNoRun(true);
		});
		lenient().when(client.getVarpValue(VARP_RUN)).thenReturn(0); // run OFF
		encounterKill(ICE_DEMON);
		assertTrue(t.isCompleted(), "defeating the target while walking completes a no_run task");
	}

	@Test
	void noRun_runningFails()
	{
		NuzlockeTask t = addTask("cox_dont_slip", c -> {
			c.setDefeatNpcIds(Arrays.asList(ICE_DEMON));
			c.setNoRun(true);
		});
		lenient().when(client.getVarpValue(VARP_RUN)).thenReturn(1); // run ON mid-fight
		encounterKill(ICE_DEMON);
		assertFalse(t.isCompleted(), "running during the encounter taints a no_run attempt");
	}

	// ── weapon_ids (Ice Carving / Shaman Slammed / Curse You Bayle) ───────────

	@Test
	void weaponIds_requiredWeaponCompletes()
	{
		NuzlockeTask t = addTask("cox_shaman_slammed", c -> {
			c.setDefeatNpcIds(Arrays.asList(SHAMAN));
			c.setWeaponIds(Arrays.asList(DRAGON_WARHAMMER));
		});
		setEquipment(slot(WEAPON, DRAGON_WARHAMMER));
		encounterKill(SHAMAN);
		assertTrue(t.isCompleted(), "holding the required weapon the whole fight completes it");
	}

	@Test
	void weaponIds_wrongWeaponFails()
	{
		NuzlockeTask t = addTask("cox_shaman_slammed", c -> {
			c.setDefeatNpcIds(Arrays.asList(SHAMAN));
			c.setWeaponIds(Arrays.asList(DRAGON_WARHAMMER));
		});
		setEquipment(slot(WEAPON, DRAGON_SCIMITAR)); // not the warhammer
		encounterKill(SHAMAN);
		assertFalse(t.isCompleted(), "using a weapon outside the allowed set taints the run");
	}

	// ── empty_slots (Staredown = head, Hotfoot = boots) ──────────────────────

	@Test
	void emptySlots_emptyHeadCompletes()
	{
		NuzlockeTask t = addTask("cox_staredown", c -> {
			c.setDefeatNpcIds(Arrays.asList(OLM_HEAD));
			c.setEmptySlots(Arrays.asList(HEAD));
		});
		setEquipment(slot(BODY, 1234)); // something on, but head is empty
		encounterKill(OLM_HEAD);
		assertTrue(t.isCompleted(), "an empty head slot satisfies the empty_slots gate");
	}

	@Test
	void emptySlots_occupiedHeadFails()
	{
		NuzlockeTask t = addTask("cox_staredown", c -> {
			c.setDefeatNpcIds(Arrays.asList(OLM_HEAD));
			c.setEmptySlots(Arrays.asList(HEAD));
		});
		setEquipment(slot(HEAD, 1234)); // a helmet is worn
		encounterKill(OLM_HEAD);
		assertFalse(t.isCompleted(), "wearing anything in a must-be-empty slot fails");
	}

	// ── required_inventory_ids (Escargottem: Bag of Salt) ────────────────────

	@Test
	void requiredInventory_itemPresentCompletes()
	{
		NuzlockeTask t = addTask("cox_escargottem", c -> {
			c.setDefeatNpcIds(Arrays.asList(RANGED_VANGUARD));
			c.setRequiredInventoryIds(Arrays.asList(BAG_OF_SALT));
		});
		setInventory(BAG_OF_SALT);
		encounterKill(RANGED_VANGUARD);
		assertTrue(t.isCompleted(), "keeping the required item in the inventory completes it");
	}

	@Test
	void requiredInventory_itemMissingFails()
	{
		NuzlockeTask t = addTask("cox_escargottem", c -> {
			c.setDefeatNpcIds(Arrays.asList(RANGED_VANGUARD));
			c.setRequiredInventoryIds(Arrays.asList(BAG_OF_SALT));
		});
		setInventory(995); // some coins, but no bag of salt
		encounterKill(RANGED_VANGUARD);
		assertFalse(t.isCompleted(), "missing the required inventory item fails the run");
	}

	// ── empty_inventory (Scurrius "Forgot Lunch") ────────────────────────────

	@Test
	void emptyInventory_emptyCompletes()
	{
		NuzlockeTask t = addTask("scurrius_forgot_lunch", c -> {
			c.setDefeatNpcIds(Arrays.asList(ICE_DEMON));
			c.setEmptyInventory(true);
		});
		setInventory(); // nothing carried
		encounterKill(ICE_DEMON);
		assertTrue(t.isCompleted(), "an empty inventory completes a Forgot-Lunch task");
	}

	@Test
	void emptyInventory_carryingSomethingFails()
	{
		NuzlockeTask t = addTask("scurrius_forgot_lunch", c -> {
			c.setDefeatNpcIds(Arrays.asList(ICE_DEMON));
			c.setEmptyInventory(true);
		});
		setInventory(995); // a single coin ruins it
		encounterKill(ICE_DEMON);
		assertFalse(t.isCompleted(), "any item in the inventory fails an empty-inventory task");
	}

	// ── min_hitsplat (Scurrius "Ratsplosion": 20+) ───────────────────────────

	@Test
	void minHitsplat_bigHitCompletes()
	{
		NuzlockeTask t = addTask("scurrius_ratsplosion", c -> {
			c.setDefeatNpcIds(Arrays.asList(ICE_DEMON));
			c.setMinHitsplat(20);
		});
		fireHit(ICE_DEMON, 25); // a 25 landed on the target
		assertTrue(t.isCompleted(), "a hit at/over the threshold completes a min_hitsplat task");
	}

	@Test
	void minHitsplat_smallHitDoesNotComplete()
	{
		NuzlockeTask t = addTask("scurrius_ratsplosion", c -> {
			c.setDefeatNpcIds(Arrays.asList(ICE_DEMON));
			c.setMinHitsplat(20);
		});
		fireHit(ICE_DEMON, 12); // under the threshold
		assertFalse(t.isCompleted(), "a hit below the threshold must not complete it");
	}

	@Test
	void minHitsplat_plainKillDoesNotComplete()
	{
		NuzlockeTask t = addTask("scurrius_ratsplosion", c -> {
			c.setDefeatNpcIds(Arrays.asList(ICE_DEMON));
			c.setMinHitsplat(20);
		});
		fireHit(ICE_DEMON, 5); // small hits only
		fireDeath(ICE_DEMON);   // then it dies — but that's not the trigger
		assertFalse(t.isCompleted(), "a min_hitsplat task completes on the big hit, not on the kill");
	}

	// ── required_equipped_ids (Friendly Fire: Priest gown top + bottom) ──────

	@Test
	void requiredEquippedIds_fullSetCompletes()
	{
		NuzlockeTask t = addTask("cox_friendly_fire", c -> {
			c.setDefeatNpcIds(Arrays.asList(MYSTIC_A));
			c.setRequiredEquippedIds(Arrays.asList(PRIEST_TOP, PRIEST_BOTTOM));
		});
		setEquipment(slot(BODY, PRIEST_TOP), slot(LEGS, PRIEST_BOTTOM));
		encounterKill(MYSTIC_A);
		assertTrue(t.isCompleted(), "wearing every required item completes it");
	}

	@Test
	void requiredEquippedIds_missingPieceFails()
	{
		NuzlockeTask t = addTask("cox_friendly_fire", c -> {
			c.setDefeatNpcIds(Arrays.asList(MYSTIC_A));
			c.setRequiredEquippedIds(Arrays.asList(PRIEST_TOP, PRIEST_BOTTOM));
		});
		setEquipment(slot(BODY, PRIEST_TOP)); // bottom missing
		encounterKill(MYSTIC_A);
		assertFalse(t.isCompleted(), "missing one required equipped item fails");
	}

	// ── required_equipped_groups (Wrong Cave: full Prospector, per-slot variants)

	@Test
	void requiredEquippedGroups_oneVariantPerGroupCompletes()
	{
		NuzlockeTask t = addTask("cox_wrong_cave", c -> {
			c.setDefeatNpcIds(Arrays.asList(OLM_HEAD));
			c.setRequiredEquippedGroups(Arrays.asList(
				Arrays.asList(12013, 29472, 25549),  // helm variants
				Arrays.asList(12014, 29474, 25551),  // jacket variants
				Arrays.asList(12015, 29476, 25553),  // legs variants
				Arrays.asList(12016, 29478, 25555))); // boots variants
		});
		setEquipment(slot(HEAD, 29472), slot(BODY, 12014), slot(LEGS, 25553), slot(BOOTS, 12016));
		encounterKill(OLM_HEAD);
		assertTrue(t.isCompleted(), "one item from each group (any variant) completes the set gate");
	}

	@Test
	void requiredEquippedGroups_missingSlotFails()
	{
		NuzlockeTask t = addTask("cox_wrong_cave", c -> {
			c.setDefeatNpcIds(Arrays.asList(OLM_HEAD));
			c.setRequiredEquippedGroups(Arrays.asList(
				Arrays.asList(12013, 29472, 25549),
				Arrays.asList(12016, 29478, 25555)));
		});
		setEquipment(slot(HEAD, 29472)); // helm on, boots missing
		encounterKill(OLM_HEAD);
		assertFalse(t.isCompleted(), "an unsatisfied group (empty slot) fails the set gate");
	}

	// ── forbidden_prayer_bits (Rockglide: no overhead prayers) ───────────────

	@Test
	void forbiddenPrayerBits_noOverheadCompletes()
	{
		NuzlockeTask t = addTask("cox_rockglide", c -> {
			c.setDefeatNpcIds(Arrays.asList(VASA));
			c.setForbiddenPrayerBits(Arrays.asList(PROT_MAGIC, 13, PROT_MELEE));
		});
		lenient().when(client.getVarbitValue(VARBIT_ACTIVE_PRAYERS)).thenReturn(1 << 24); // Rigour on, no overhead
		encounterKill(VASA);
		assertTrue(t.isCompleted(), "no overhead prayer active → the run qualifies");
	}

	@Test
	void forbiddenPrayerBits_overheadActiveFails()
	{
		NuzlockeTask t = addTask("cox_rockglide", c -> {
			c.setDefeatNpcIds(Arrays.asList(VASA));
			c.setForbiddenPrayerBits(Arrays.asList(PROT_MAGIC, 13, PROT_MELEE));
		});
		lenient().when(client.getVarbitValue(VARBIT_ACTIVE_PRAYERS)).thenReturn(1 << PROT_MELEE); // Protect from Melee on
		encounterKill(VASA);
		assertFalse(t.isCompleted(), "a forbidden overhead prayer being active fails the run");
	}

	// ── defeat_simultaneous (Joint Execution: 2 mystics within 2 ticks) ──────

	@Test
	void defeatSimultaneous_twoWithinWindowCompletes()
	{
		NuzlockeTask t = addTask("cox_joint_execution", c -> {
			c.setDefeatNpcIds(Arrays.asList(MYSTIC_A, MYSTIC_B));
			c.setDefeatSimultaneous(2);
			c.setDefeatWithinTicks(2);
		});
		fireHit(MYSTIC_A); // opens the encounter
		when(client.getTickCount()).thenReturn(100);
		fireDeath(MYSTIC_A);
		when(client.getTickCount()).thenReturn(101); // 1 tick later — inside the 2-tick window
		fireDeath(MYSTIC_B);
		assertTrue(t.isCompleted(), "two targets dying within the window completes the task");
	}

	@Test
	void defeatSimultaneous_tooFarApartDoesNotComplete()
	{
		NuzlockeTask t = addTask("cox_joint_execution", c -> {
			c.setDefeatNpcIds(Arrays.asList(MYSTIC_A, MYSTIC_B));
			c.setDefeatSimultaneous(2);
			c.setDefeatWithinTicks(2);
		});
		fireHit(MYSTIC_A);
		when(client.getTickCount()).thenReturn(100);
		fireDeath(MYSTIC_A);
		when(client.getTickCount()).thenReturn(105); // 5 ticks later — outside the window
		fireDeath(MYSTIC_B);
		assertFalse(t.isCompleted(), "two kills spread beyond the window must not complete");
	}

	// ── final_blow_vengeance (Bite Back: finish the Muttadile with Vengeance) ─

	private static final int MUTTADILE = 7563;
	private static final int VENGEANCE_REBOUND = 2450;

	@Test
	void finalBlowVengeance_vengeanceKillCompletes()
	{
		NuzlockeTask t = addTask("cox_bite_back", c -> {
			c.setDefeatNpcIds(Arrays.asList(MUTTADILE));
			c.setFinalBlowVengeance(true);
		});
		// Tick 100: Vengeance armed. The tick baselines the varbit at 1.
		lenient().when(client.getVarbitValue(VENGEANCE_REBOUND)).thenReturn(1);
		fireHit(MUTTADILE);
		fireTick();
		// Tick 101: a hit rebounds Vengeance (2450 → 0) and the Muttadile dies the same tick.
		when(client.getTickCount()).thenReturn(101);
		lenient().when(client.getVarbitValue(VENGEANCE_REBOUND)).thenReturn(0);
		fireDeath(MUTTADILE);   // death held, pending the end-of-tick venge check
		fireTick();             // end of tick 101: rebound detected → completes
		assertTrue(t.isCompleted(), "a Muttadile finished by a Vengeance rebound completes Bite Back");
	}

	@Test
	void finalBlowVengeance_normalKillDoesNotComplete()
	{
		NuzlockeTask t = addTask("cox_bite_back", c -> {
			c.setDefeatNpcIds(Arrays.asList(MUTTADILE));
			c.setFinalBlowVengeance(true);
		});
		lenient().when(client.getVarbitValue(VENGEANCE_REBOUND)).thenReturn(0); // no vengeance
		fireHit(MUTTADILE);
		fireTick();
		when(client.getTickCount()).thenReturn(101);
		fireDeath(MUTTADILE);   // a normal killing blow
		fireTick();             // no rebound this tick → the finish fails
		assertFalse(t.isCompleted(), "a normal killing blow must not complete a vengeance-finish task");
	}

	// ── style_target + required_attack_style (HM06: Tekton, Crush only) ──────

	@Test
	void styleTarget_crushOnlyCompletes()
	{
		NuzlockeTask t = addTask("cox_hm06", c -> {
			c.setDefeatNpcIds(Arrays.asList(TEKTON));
			c.setStyleTargetIds(Arrays.asList(TEKTON));
			c.setRequiredAttackStyle("CRUSH");
		});
		setCombatStyle(2, 0); // weapon type 2 (blunt) → CRUSH
		encounterKill(TEKTON);
		assertTrue(t.isCompleted(), "hitting the target only with the required style completes it");
	}

	@Test
	void styleTarget_wrongStyleFails()
	{
		NuzlockeTask t = addTask("cox_hm06", c -> {
			c.setDefeatNpcIds(Arrays.asList(TEKTON));
			c.setStyleTargetIds(Arrays.asList(TEKTON));
			c.setRequiredAttackStyle("CRUSH");
		});
		setCombatStyle(1, 0); // weapon type 1 (axe), style 0 → SLASH, not CRUSH
		fireHit(TEKTON);       // the very first hit is the wrong style → violation
		fireDeath(TEKTON);
		assertFalse(t.isCompleted(), "a single wrong-style hit on the target fails the run");
	}

	// ── gear value (Please Carry Me / Pulling the Bootstraps) ────────────────

	@Test
	void maxGearValue_underBudgetCompletes()
	{
		NuzlockeTask t = addTask("cox_please_carry_me", c -> {
			c.setDefeatNpcIds(Arrays.asList(OLM_HEAD));
			c.setMaxGearValue(10_000_000L);
		});
		setEquipment(slot(WEAPON, 1)); // one item, cheap
		when(itemManager.getItemPrice(1)).thenReturn(5_000_000);
		encounterKill(OLM_HEAD);
		assertTrue(t.isCompleted(), "gear under the cap completes a max_gear_value task");
	}

	@Test
	void maxGearValue_overBudgetFails()
	{
		NuzlockeTask t = addTask("cox_please_carry_me", c -> {
			c.setDefeatNpcIds(Arrays.asList(OLM_HEAD));
			c.setMaxGearValue(10_000_000L);
		});
		setEquipment(slot(WEAPON, 1));
		when(itemManager.getItemPrice(1)).thenReturn(50_000_000); // over the cap
		encounterKill(OLM_HEAD);
		assertFalse(t.isCompleted(), "gear over the cap fails a max_gear_value task");
	}

	@Test
	void minGearValue_richEnoughCompletes()
	{
		NuzlockeTask t = addTask("cox_pulling_bootstraps", c -> {
			c.setDefeatNpcIds(Arrays.asList(OLM_HEAD));
			c.setMinGearValue(50_000_000L);
		});
		setEquipment(slot(WEAPON, 1));
		when(itemManager.getItemPrice(1)).thenReturn(80_000_000); // above the floor
		encounterKill(OLM_HEAD);
		assertTrue(t.isCompleted(), "gear above the floor completes a min_gear_value task");
	}

	@Test
	void minGearValue_tooCheapFails()
	{
		NuzlockeTask t = addTask("cox_pulling_bootstraps", c -> {
			c.setDefeatNpcIds(Arrays.asList(OLM_HEAD));
			c.setMinGearValue(50_000_000L);
		});
		setEquipment(slot(WEAPON, 1));
		when(itemManager.getItemPrice(1)).thenReturn(1_000_000); // below the floor
		encounterKill(OLM_HEAD);
		assertFalse(t.isCompleted(), "gear below the floor fails a min_gear_value task");
	}

	// ── cross-raid completion-message guard (the Mike CoX-fails-ToA bug) ──────

	@Test
	void crossRaidGuard_coxKillCountDoesNotFailToaTask()
	{
		NuzlockeTask toa = addTask("toa_defeat_150", c -> {
			c.setCompleteMessage("count is");
			c.setRaidLevelVarbit(14380);
			c.setMinRaidLevel(150);
		});
		// Player is in CoX, so the ToA raid-level varbit reads 0.
		lenient().when(client.getVarbitValue(14380)).thenReturn(0);

		fireChat("Your completed Chambers of Xeric count is: 5"); // CoX KC line

		assertFalse(toa.isCompleted(), "a CoX completion must not complete a ToA task");
		verify(chatMessageManager, never()).queue(any());
	}

	@Test
	void crossRaidGuard_toaKillCountCompletesToaTaskWhenInToa()
	{
		NuzlockeTask toa = addTask("toa_next_level", c -> {
			c.setCompleteMessage("count is");
			c.setRaidLevelVarbit(14380);
			c.setMinRaidLevel(150);
		});
		when(client.getVarbitValue(14380)).thenReturn(300); // genuinely in a 300-invocation ToA

		fireChat("Your completed Tombs of Amascut count is: 5");

		assertTrue(toa.isCompleted(), "a ToA completion at/above the required level completes the ToA task");
	}

	// ── plumbing ─────────────────────────────────────────────────────────────

	@Test
	void getCompletionType_isRaidChallenge()
	{
		assertEquals("RAID_CHALLENGE", module.getCompletionType());
	}

	@Test
	void addActiveTask_registersTaskWithChallenge()
	{
		NuzlockeTask t = addTask("cox_ice_carving", c -> c.setDefeatNpcIds(Arrays.asList(ICE_DEMON)));
		assertTrue(module.getActiveTasks().contains(t));
	}

	// ── min_prayer_bonus: Giant Mole "Holy Moley" (+20 Prayer bonus from gear) ──

	@Test
	void minPrayerBonus_metCompletesTheKill()
	{
		setEquipment(slot(HEAD, 12013));
		setPrayerBonus(12013, 25);
		NuzlockeTask t = addTask("giant_mole_holy_moley", c -> {
			c.setDefeatNpcIds(Arrays.asList(5779));
			c.setMinPrayerBonus(20);
		});
		encounterKill(5779);
		assertTrue(t.isCompleted(), "+25 prayer bonus meets the +20 requirement");
	}

	@Test
	void minPrayerBonus_belowThresholdFailsTheRun()
	{
		setEquipment(slot(HEAD, 12013));
		setPrayerBonus(12013, 5);
		NuzlockeTask t = addTask("giant_mole_holy_moley", c -> {
			c.setDefeatNpcIds(Arrays.asList(5779));
			c.setMinPrayerBonus(20);
		});
		encounterKill(5779);
		assertFalse(t.isCompleted(), "+5 prayer bonus is below the +20 requirement");
	}

	// ── chat completion: specific-message boss (Royal Titans) vs raid-gated (ToA/CoX) ──

	@Test
	void chatCompletion_specificMessageWithoutRaidGateCompletes()
	{
		// Royal Titans has a specific KC message and NO raid-level gate; the cross-raid
		// "count is" guard must not block it (that guard is only for raid-level-gated tasks).
		NuzlockeTask t = addTask("royal_titans_defeat", c ->
			c.setCompleteMessage("royal titans kill count is"));
		fireChat("Your Royal Titans kill count is: 26");
		assertTrue(t.isCompleted(), "a specific KC message with no raid-level gate completes");
	}

	@Test
	void chatCompletion_raidGatedTaskStillBlockedWhenRaidInactive()
	{
		// The ToA/CoX cross-raid protection: a raid-level-gated task must stay blocked when
		// its raid isn't active (raidLevel reads 0). Guards against the relaxation above.
		NuzlockeTask t = addTask("toa_style", c -> {
			c.setCompleteMessage("count is");
			c.setMinRaidLevel(150);
		});
		fireChat("Your Tombs of Amascut count is: 5");
		assertFalse(t.isCompleted(), "a raid-gated task stays blocked when its raid isn't active");
	}

	// ── forbid_entry_mode: ToB Entry-mode tasks must not complete (mode from chat) ──

	@Test
	void forbidEntryMode_blocksCompletionInEntryRaid()
	{
		tobMode.observeChat("You enter the Theatre of Blood (Entry Mode)");
		NuzlockeTask t = addTask("tob_two_fatasses", c -> c.setDefeatNpcIds(Arrays.asList(SHAMAN)));
		t.setForbidEntryMode(true);
		encounterKill(SHAMAN);
		assertFalse(t.isCompleted(), "a forbid_entry_mode task must not complete in a ToB Entry raid");
	}

	@Test
	void forbidEntryMode_allowsCompletionInNormalRaid()
	{
		tobMode.observeChat("You enter the Theatre of Blood (Normal Mode)");
		NuzlockeTask t = addTask("tob_two_fatasses", c -> c.setDefeatNpcIds(Arrays.asList(SHAMAN)));
		t.setForbidEntryMode(true);
		encounterKill(SHAMAN);
		assertTrue(t.isCompleted(), "a forbid_entry_mode task completes normally in Normal mode");
	}

	@Test
	void forbidEntryMode_modeLatchedViaChatHandler()
	{
		// The entry banner arriving through onChatMessage (before the active-task return)
		// is what latches the mode — this exercises that wiring end to end.
		fireChat("You enter the Theatre of Blood (Entry Mode)");
		NuzlockeTask t = addTask("tob_two_fatasses", c -> c.setDefeatNpcIds(Arrays.asList(SHAMAN)));
		t.setForbidEntryMode(true);
		encounterKill(SHAMAN);
		assertFalse(t.isCompleted(), "mode latched via the chat handler blocks Entry completion");
	}

	@Test
	void forbidEntryMode_unknownModeFailsOpen()
	{
		// No entry banner seen (e.g. a mid-raid relog) -> UNKNOWN -> allow, so a legit
		// relogger is never punished.
		NuzlockeTask t = addTask("tob_two_fatasses", c -> c.setDefeatNpcIds(Arrays.asList(SHAMAN)));
		t.setForbidEntryMode(true);
		encounterKill(SHAMAN);
		assertTrue(t.isCompleted(), "unknown mode fails open (completes)");
	}

	// ── arena_hp_gate: Maiden's "Red Carpet" (arena only enforced from 50% HP) ──

	@Test
	void arenaHpGate_completesOnKillWithoutSpuriousFailure()
	{
		// Red Carpet is a health-gated arena. With no player location mocked the arena
		// position check is inert (fromLocalInstance needs a live scene), so this guards
		// the new HP-gate wiring: it must not throw or falsely fail — the kill still credits.
		NuzlockeTask t = addTask("tob_red_carpet", c -> {
			c.setDefeatNpcIds(Arrays.asList(8360, 8362));
			RaidChallenge.ArenaBox box = new RaidChallenge.ArenaBox();
			box.setMinX(24);
			box.setMaxX(51);
			box.setMinY(28);
			box.setMaxY(34);
			c.setArenaBoxes(Arrays.asList(box));
			c.setArenaHpGateNpcIds(Arrays.asList(8360, 8362));
			c.setArenaHpGateBelowPercent(50);
		});
		encounterKill(8362);
		assertTrue(t.isCompleted(), "an HP-gated arena task still completes on the kill");
	}

	// ── defeat_count: count N kills in one fight window (Stainless, Bug Basher/…) ──

	@Test
	void defeatCount_reachesTargetInWindowCompletes() throws Exception
	{
		NuzlockeTask t = addTask("tob_bug_basher", c -> {
			c.setRoomRegions(Arrays.asList(13122));
			c.setDefeatCount(3);
			c.setDefeatCountNpcIds(Arrays.asList(8342, 8348));
		});
		forceWindowOpen(t); // stand in the Nylocas room (scene can't be mocked in a unit test)
		fireDeath(8342);
		fireDeath(8348);
		assertFalse(t.isCompleted(), "two counted deaths is short of the target of three");
		fireDeath(8342);
		assertTrue(t.isCompleted(), "3 counted nylo deaths in one window completes Bug Basher");
	}

	@Test
	void defeatCount_bloodSpawnNpcDeathsCompleteStainless() throws Exception
	{
		NuzlockeTask t = addTask("tob_stainless", c -> {
			c.setRoomRegions(Arrays.asList(12613, 12869));
			c.setDefeatCount(2);
			c.setDefeatCountNpcIds(Arrays.asList(8367, 10821, 10829));
		});
		forceWindowOpen(t);
		fireDeath(8367);
		assertFalse(t.isCompleted(), "one blood spawn is short of the target of two");
		fireDeath(10821);
		assertTrue(t.isCompleted(), "2 blood-spawn deaths in one window completes Stainless");
	}

	@Test
	void defeatCount_deathsOutsideWindowDoNotComplete()
	{
		// The tally is gated on the room window, so kills made outside the room can't
		// bank progress toward "in one fight". No window is opened here.
		NuzlockeTask t = addTask("tob_bug_basher", c -> {
			c.setRoomRegions(Arrays.asList(13122));
			c.setDefeatCount(3);
			c.setDefeatCountNpcIds(Arrays.asList(8342, 8348));
		});
		for (int i = 0; i < 5; i++)
		{
			fireDeath(8342);
		}
		assertFalse(t.isCompleted(), "counted deaths outside the room window must not credit");
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	/** Force a task's fight window open — the room check needs a live scene we can't mock. */
	private void forceWindowOpen(NuzlockeTask task) throws Exception
	{
		Field statesField = RaidChallengeModule.class.getDeclaredField("states");
		statesField.setAccessible(true);
		Map<?, ?> states = (Map<?, ?>) statesField.get(module);
		Object state = states.get(task.getTaskId());
		assertNotNull(state, "task should have registered a challenge state");
		Field win = state.getClass().getDeclaredField("windowOpen");
		win.setAccessible(true);
		win.setBoolean(state, true);
	}

	/** Build a RAID_CHALLENGE task, configure its challenge block, and register it. */
	private NuzlockeTask addTask(String id, java.util.function.Consumer<RaidChallenge> cfg)
	{
		NuzlockeTask t = createTestTask(id, id, "RAID_CHALLENGE", 1);
		RaidChallenge c = new RaidChallenge();
		cfg.accept(c);
		t.setChallenge(c);
		module.addActiveTask(t);
		return t;
	}

	/** The full "defeat with conditions held" path: engage, sample one tick, kill. */
	private void encounterKill(int npcId)
	{
		fireHit(npcId);
		fireTick();
		fireDeath(npcId);
	}

	private NPC npc(int id)
	{
		return npcs.computeIfAbsent(id, k -> {
			NPC n = mock(NPC.class);
			lenient().when(n.getId()).thenReturn(k);
			return n;
		});
	}

	private void fireHit(int npcId)
	{
		fireHit(npcId, 1);
	}

	private void fireHit(int npcId, int amount)
	{
		NPC target = npc(npcId); // build BEFORE the stubbing chain (nested when() is illegal)
		HitsplatApplied e = mock(HitsplatApplied.class);
		Hitsplat h = mock(Hitsplat.class);
		lenient().when(e.getActor()).thenReturn(target);
		lenient().when(e.getHitsplat()).thenReturn(h);
		lenient().when(h.isOthers()).thenReturn(false);
		lenient().when(h.getAmount()).thenReturn(amount);
		module.onHitsplatApplied(e);
	}

	private void fireDeath(int npcId)
	{
		NPC target = npc(npcId);
		ActorDeath e = mock(ActorDeath.class);
		lenient().when(e.getActor()).thenReturn(target);
		module.onActorDeath(e);
	}

	private void fireTick()
	{
		module.onGameTick(new GameTick());
	}

	private void fireChat(String msg)
	{
		ChatMessage e = mock(ChatMessage.class);
		lenient().when(e.getType()).thenReturn(ChatMessageType.GAMEMESSAGE);
		lenient().when(e.getMessage()).thenReturn(msg);
		module.onChatMessage(e);
	}

	/** A (slot, itemId) pair for {@link #setEquipment}. */
	private static int[] slot(int slot, int itemId)
	{
		return new int[]{slot, itemId};
	}

	private void setEquipment(int[]... slotItems)
	{
		ItemContainer eq = mock(ItemContainer.class);
		Item[] arr = new Item[14];
		for (int[] si : slotItems)
		{
			Item it = mock(Item.class);
			lenient().when(it.getId()).thenReturn(si[1]);
			lenient().when(it.getQuantity()).thenReturn(1);
			arr[si[0]] = it;
			lenient().when(eq.getItem(si[0])).thenReturn(it);
		}
		lenient().when(eq.getItems()).thenReturn(arr);
		lenient().when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(eq);
	}

	/** Stub an equipped item's Prayer bonus (from its equipment stats) for equippedPrayerBonus(). */
	private void setPrayerBonus(int itemId, int prayer)
	{
		ItemEquipmentStats eq = mock(ItemEquipmentStats.class);
		lenient().when(eq.getPrayer()).thenReturn(prayer);
		ItemStats stats = mock(ItemStats.class);
		lenient().when(stats.getEquipment()).thenReturn(eq);
		lenient().when(itemManager.getItemStats(itemId, false)).thenReturn(stats);
	}

	private void setInventory(int... itemIds)
	{
		ItemContainer inv = mock(ItemContainer.class);
		Item[] arr = new Item[itemIds.length];
		for (int i = 0; i < itemIds.length; i++)
		{
			Item it = mock(Item.class);
			lenient().when(it.getId()).thenReturn(itemIds[i]);
			lenient().when(it.getQuantity()).thenReturn(1);
			arr[i] = it;
		}
		lenient().when(inv.getItems()).thenReturn(arr);
		lenient().when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(inv);
	}

	/** Set the equipped-weapon-type varbit + attack-style varp used by style resolution. */
	private void setCombatStyle(int weaponType, int styleIndex)
	{
		lenient().when(client.getVarbitValue(VARBIT_WEAPON_TYPE)).thenReturn(weaponType);
		lenient().when(client.getVarpValue(VARP_ATTACK_STYLE)).thenReturn(styleIndex);
	}

	private void injectField(Object target, String name, Object value) throws Exception
	{
		Class<?> clazz = target.getClass();
		while (clazz != null)
		{
			try
			{
				Field f = clazz.getDeclaredField(name);
				f.setAccessible(true);
				f.set(target, value);
				return;
			}
			catch (NoSuchFieldException e)
			{
				clazz = clazz.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
