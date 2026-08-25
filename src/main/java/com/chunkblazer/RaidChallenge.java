package com.chunkblazer;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import lombok.Data;

/**
 * Data-driven definition of a raid/boss "challenge" — a completion conditioned on
 * how the fight was done (no running, gear cap, weapon used, arena half, …).
 *
 * <p>Every field here is authored in the SERVER task catalog (which does not count
 * against the plugin's reviewer token budget) and interpreted generically by
 * {@link com.chunkblazer.modules.RaidChallengeModule}. Adding a new boss/raid
 * challenge is therefore JSON-only — no new plugin code — which is what keeps the
 * plugin small as more boss chunks and raids are added.
 *
 * <p>All fields are optional; a challenge enforces only the ones present. Absent =
 * not checked. Two evaluation phases:
 * <ul>
 *   <li><b>Sustained</b> (tracked every tick / on events while the player is inside
 *       {@link #roomRegions}): {@code noRun}, {@code arenaAxis}, {@code noNpcDeathIds},
 *       {@code forbiddenItemIds}, {@code emptySlots}, {@code weaponIds},
 *       {@code noDamageTicks}, {@code surviveTicks}, {@code attackStyleVarp},
 *       {@code defenceReduceNpcIds}. A single violation fails the attempt for that run.</li>
 *   <li><b>At completion</b> (when {@link #completeMessage} appears): the gates
 *       ({@code minRaidLevel}, {@code solo}) and point-in-time reads
 *       ({@code minWeightKg}, {@code maxWeightKg}, {@code maxGearValue}), plus a final
 *       re-check of the sustained flags.</li>
 * </ul>
 */
@Data
public class RaidChallenge
{
	/**
	 * Substring of the GAMEMESSAGE that signals the relevant completion. Per-room
	 * challenges use the room banner (e.g. "Challenge complete: Kephri"); whole-raid
	 * challenges use the KC line (e.g. "count is"). Case-insensitive match.
	 */
	@SerializedName("complete_message")
	private String completeMessage;

	/**
	 * Instanced region IDs that define the fight "window" for sustained conditions.
	 * The window opens when the player enters any of these and closes at
	 * {@link #completeMessage}. Absent → whole-raid scope (window opens on the first
	 * raid region seen; use for raid-wide challenges like "no supplies").
	 */
	@SerializedName("room_regions")
	private List<Integer> roomRegions;

	// ── Gates (checked once, at completion) ──────────────────────────────────
	/** Minimum ToA raid/invocation level (varbit {@link #raidLevelVarbit}). */
	@SerializedName("min_raid_level")
	private Integer minRaidLevel;

	/** Require the raid be solo (team size == 1). */
	@SerializedName("solo")
	private Boolean solo;

	// ── Sustained conditions (any violation fails the run) ───────────────────
	/** Player must never have run enabled (varp {@link #runVarp}) during the window. */
	@SerializedName("no_run")
	private Boolean noRun;

	/** Weapon (equipment slot 3) must always be one of these while attacking. */
	@SerializedName("weapon_ids")
	private List<Integer> weaponIds;

	/** These equipment slot indices must stay empty for the whole window. */
	@SerializedName("empty_slots")
	private List<Integer> emptySlots;

	/** None of these NPCs may die during the window (e.g. Wardens' energy siphons). */
	@SerializedName("no_npc_death_ids")
	private List<Integer> noNpcDeathIds;

	/** None of these item IDs may ever enter the inventory (e.g. raid-supplied items). */
	@SerializedName("forbidden_item_ids")
	private List<Integer> forbiddenItemIds;

	/**
	 * Arena bounding box in REGION-LOCAL tile coords (0–63) of {@link #roomRegions}
	 * — the same regionX/regionY a RuneLite ground marker records. The player must
	 * stay within [min,max] on both axes for the whole window; a step outside fails
	 * the attempt. Only the bounds that are present are enforced.
	 */
	@SerializedName("arena_min_x")
	private Integer arenaMinX;
	@SerializedName("arena_max_x")
	private Integer arenaMaxX;
	@SerializedName("arena_min_y")
	private Integer arenaMinY;
	@SerializedName("arena_max_y")
	private Integer arenaMaxY;

	/**
	 * Additional valid arena rectangles (region-local tile coords, 0–63), for when the
	 * legal fight area isn't one clean rectangle — e.g. Ba-Ba's floor PLUS the raised
	 * platform you stand on. The valid area is the UNION of the single box above and
	 * every box here: the player is only "outside the arena" when they're outside ALL
	 * of them. Each box is just four bounds (min/max x/y) — no per-tile lists — and
	 * enforces only the bounds it sets (absent bound = unbounded on that side).
	 */
	@SerializedName("arena_boxes")
	private List<ArenaBox> arenaBoxes;

	@lombok.Data
	public static class ArenaBox
	{
		@SerializedName("min_x")
		private Integer minX;
		@SerializedName("max_x")
		private Integer maxX;
		@SerializedName("min_y")
		private Integer minY;
		@SerializedName("max_y")
		private Integer maxY;
	}

	/** Reach this many CONSECUTIVE damage-free ticks in the window (Butterfly: 50 = 30s). */
	@SerializedName("no_damage_ticks")
	private Integer noDamageTicks;

	/**
	 * Survive this many ticks while a phase is active, to complete (enrage timers).
	 * The phase is "active" when varbit {@code phaseVarbit} == {@code phaseValue}
	 * (both authored + verified in-game); if no phase varbit is given, counts from
	 * window open. 100 ticks = 60s.
	 */
	@SerializedName("survive_ticks")
	private Integer surviveTicks;
	/**
	 * Named phase gate for {@code survive_ticks} (interpreted by RaidChallengeModule),
	 * e.g. "toa_wardens_enrage" for the Wardens' final lightning phase. Preferred over
	 * {@code phase_varbit} for phases that have no varbit (the Wardens enrage is one —
	 * it's an NPC/HP state, not a varbit). An unknown key is treated as never-active.
	 */
	@SerializedName("phase")
	private String phase;
	@SerializedName("phase_varbit")
	private Integer phaseVarbit;
	@SerializedName("phase_value")
	private Integer phaseValue;

	/**
	 * Attack-style purity: the combat-style varp ({@code attackStyleVarp}, default 43)
	 * must be one of {@code attackStyleValues} whenever the player attacks. The
	 * varp→style mapping is weapon-dependent, so the allowed value(s) are authored +
	 * verified in-game (e.g. stab for a given weapon). Best-effort.
	 */
	@SerializedName("attack_style_varp")
	private Integer attackStyleVarp;
	@SerializedName("attack_style_values")
	private List<Integer> attackStyleValues;

	/**
	 * NPCs whose hits from the LOCAL player are gated on {@link #requiredAttackStyle}.
	 * Every hit you land on one of these NPCs must use that damage style, or the run
	 * fails. Evaluated ONLY on hitsplats to these NPCs — so switching weapons for a
	 * side target (e.g. shooting a Zebak jug with a ranged weapon) never trips it.
	 * The active style is derived from the equipped-weapon-type varbit + attack-style
	 * varp; the module logs the resolved style ([STYLE-DEBUG]) so the weapon table
	 * can be verified/extended.
	 */
	@SerializedName("style_target_ids")
	private List<Integer> styleTargetIds;

	/**
	 * Damage style every hit on {@link #styleTargetIds} must use: {@code STAB},
	 * {@code SLASH}, {@code CRUSH}, {@code RANGED}, or {@code MAGIC}. Defaults to
	 * {@code STAB} when a target list is present but this is unset.
	 */
	@SerializedName("required_attack_style")
	private String requiredAttackStyle;

	/** @deprecated legacy name for {@link #styleTargetIds}; still parsed so old catalogs load. */
	@Deprecated
	@SerializedName("stab_target_ids")
	private List<Integer> stabTargetIds;

	/**
	 * "Obtain ALL of these item ids within a single raid." Satisfy-triggered: the
	 * task completes the moment every listed item has been seen in the inventory
	 * during one fight window, and progress resets when a new raid begins. Use for
	 * in-raid crafting goals whose supplies are destroyed on exit — e.g. CoX's
	 * "mix these four (+) potions in the same raid" — where the same-raid constraint
	 * can't be met by a plain OBTAIN set (which counts across raids).
	 */
	@SerializedName("obtain_all_item_ids")
	private List<Integer> obtainAllItemIds;

	/**
	 * Like {@link #obtainAllItemIds} but grouped: complete when you've obtained one
	 * item from EVERY group within a single raid. Each group is a set of interchangeable
	 * variants (e.g. all four doses of one potion), so any dose satisfies that group.
	 * Preferred over the flat id list when items come in doses/variants.
	 */
	@SerializedName("obtain_all_item_groups")
	private List<List<Integer>> obtainAllItemGroups;

	/**
	 * When set (a {@link net.runelite.api.Skill} name, e.g. {@code "HERBLORE"}), an
	 * obtain_all item only counts if you MADE it — its inventory count rose on the same
	 * tick you gained XP in that skill. This turns "have the item" into "skill it up in
	 * the raid" (CoX Homebrew must MIX the potions, not just pick one up).
	 */
	@SerializedName("obtain_all_require_skill")
	private String obtainAllRequireSkill;

	// ── Point-in-time reads (checked at completion) ──────────────────────────
	/** Equipped + carried weight (kg) must be at least this. */
	@SerializedName("min_weight_kg")
	private Integer minWeightKg;
	/** Equipped + carried weight (kg) must be at most this. */
	@SerializedName("max_weight_kg")
	private Integer maxWeightKg;
	/** Total GE value of equipped gear must be below this. */
	@SerializedName("max_gear_value")
	private Long maxGearValue;

	/** Total GE value of equipped gear must be AT LEAST this (high-gear challenges). */
	@SerializedName("min_gear_value")
	private Long minGearValue;

	/**
	 * "Defeat this NPC." Completes when one of these NPCs dies while you were engaged.
	 * For raids with no per-room completion chat (Chambers of Xeric), the boss's death
	 * is the completion signal. The fight window for sustained conditions ({@code noRun},
	 * {@code weaponIds}, …) is the ENCOUNTER — from your first hit on the target until it
	 * dies — so "defeat X without running / with weapon Y" is scoped to that fight, not
	 * the whole raid.
	 */
	@SerializedName("defeat_npc_ids")
	private List<Integer> defeatNpcIds;

	/**
	 * With {@link #defeatNpcIds}: require this many of the target NPCs to die within a
	 * short window of each other ("kill two at the same time"). The window defaults to
	 * {@code defeat_within_ticks} = 2 game ticks. Absent → a single kill completes.
	 */
	@SerializedName("defeat_simultaneous")
	private Integer defeatSimultaneous;
	@SerializedName("defeat_within_ticks")
	private Integer defeatWithinTicks;

	/** These item ids must ALL be equipped for the whole fight (e.g. Priest gown set). */
	@SerializedName("required_equipped_ids")
	private List<Integer> requiredEquippedIds;

	/**
	 * Prayer points must NEVER go UP during the fight — restoring prayer (potion, altar,
	 * etc.) fails the run. Restore BEFORE engaging. Used for the CoX Vanguards.
	 */
	@SerializedName("no_prayer_restore")
	private Boolean noPrayerRestore;

	// ── Per-raid source overrides (default to ToA) ───────────────────────────
	/** Varbit holding the raid/invocation level. Default 14380 (TOA_CLIENT_RAID_LEVEL). */
	@SerializedName("raid_level_varbit")
	private Integer raidLevelVarbit;
	/** Party-slot varbits summed (min(v,1)) for team size. Default = ToA P0..P7. */
	@SerializedName("party_size_varbits")
	private List<Integer> partySizeVarbits;
	/** Varp read for the run toggle. Default 173. */
	@SerializedName("run_varp")
	private Integer runVarp;

	// ── Quantity (repeatable completions, e.g. "Defeat ToA (150+) 1-5 times") ──
	/** Minimum qualifying completions required; rolled with {@link #maxQuantity}. */
	@SerializedName("min_quantity")
	private Integer minQuantity;
	/** Maximum qualifying completions; the target is rolled in [min,max]. Default 1. */
	@SerializedName("max_quantity")
	private Integer maxQuantity;
}
