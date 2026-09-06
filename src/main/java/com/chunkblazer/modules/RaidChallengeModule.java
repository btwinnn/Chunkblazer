package com.chunkblazer.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemStats;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.RaidChallenge;

/**
 * Generic, data-driven module for raid/boss "challenge" tasks — completions gated
 * on HOW the fight was done. One evaluator interprets every condition from the
 * task's {@link RaidChallenge} block (authored server-side), so adding challenges
 * for new bosses/raids is JSON-only, keeping the plugin small as content grows.
 *
 * <p>Two completion styles:
 * <ul>
 *   <li><b>Message-triggered</b> — on the completion GAMEMESSAGE (per-room
 *       "Challenge complete: X" or the raid KC "count is"), if the gates pass, no
 *       sustained condition was violated during the fight window, and the
 *       point-in-time reads hold, the attempt qualifies. Supports a rolled quantity.</li>
 *   <li><b>Satisfy-triggered</b> — completes the instant an in-window accumulator
 *       reaches its target: {@code no_damage_ticks} (Butterfly), {@code survive_ticks}
 *       (enrage timers), or a {@code defence_reduce} landing (Limp Pillar).</li>
 * </ul>
 *
 * <p>Fight window: for per-room challenges it is the time inside {@code room_regions}
 * (instanced region, via {@link WorldPoint#fromLocalInstance}); for whole-raid
 * challenges (no room_regions) it is any time the raid-level varbit is &gt; 0.
 * Leaving the window resets the attempt so a later run starts clean.
 *
 * <p>NOTE: several numeric inputs must be captured in-game before their tasks can
 * pass — arena split coords, the enrage phase varbit, per-weapon attack-style varp
 * values, and defence-reducing animation ids. They are authored as data and flagged
 * in docs/BOSS-CHUNKS.md; the module logic is complete and generic.
 */
@Slf4j
@Singleton
public class RaidChallengeModule extends AbstractTaskModule
{
	private static final String TYPE = "RAID_CHALLENGE";

	// ToA defaults, overridable per-task in the challenge block.
	private static final int DEFAULT_RAID_LEVEL_VARBIT = 14380;
	private static final int DEFAULT_RUN_VARP = 173;
	private static final int[] DEFAULT_PARTY_VARBITS = {14346, 14347, 14348, 14349, 14350, 14351, 14352, 14353};

	private static final String COLOR_BLUE = "3366ff";        // [ChunkBlazer] branding
	private static final String COLOR_DARK_BLUE = "1a5276";   // Challenge Complete
	private static final String COLOR_DARK_GREEN = "228b22";  // Challenge Progress
	private static final String COLOR_RED = "ff3333";         // Challenge Failed
	private static final String COLOR_BLACK = "000000";       // task name text

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private TobModeTracker tobMode;

	private final Random random = new Random();

	/** Per-attempt runtime state for one active challenge task. */
	private static final class State
	{
		boolean windowOpen;
		boolean violated;       // a sustained condition was broken this attempt
		int damageFreeTicks;    // consecutive in-window ticks without taking damage
		int surviveTicks;       // in-window ticks with the phase active
		int progress;           // qualifying completions so far
		int target = 1;         // completions required (rolled from quantity range)
		boolean satisfyFailTold; // satisfy-triggered gate failure already announced this attempt
		final Set<Integer> obtainedGroups = new HashSet<>();       // obtain_all: group indices completed this window
		final Map<Integer, Integer> obtainSnapshot = new HashMap<>(); // item id -> last-seen inventory count
		boolean encounterActive;  // defeat_npc: true from first hit on the target until it dies
		boolean arenaHpGateLatched; // arena_hp_gate: the watched NPC has hit the HP threshold this attempt
		int defeatCount;          // defeat_count: counted kills so far this fight window
		int hitStreak;            // consecutive_hitsplat_value: back-to-back matching hits so far
	}

	private final Map<String, State> states = new ConcurrentHashMap<>();

	// ── ToA Wardens enrage detection ─────────────────────────────────────────
	// There is NO varbit for the Wardens enrage/"final lightning" phase (confirmed
	// against RuneLite + the official ToA plugin). The final Warden is NPC id
	// 11761/11762 (Damaged==Enraged) or 11763/11764 (brief Invulnerable) — the id
	// does NOT change when enrage begins, so we detect enrage separately:
	//   1. the lightning GraphicsObject (enrage-only, most precise) — CAPTURED below;
	//   2. the HP heal-spike at enrage start (near-death -> heals ~20%) — fallback.
	// The phase key "toa_wardens_enrage" on a survive_ticks task gates on this.
	private static final String PHASE_TOA_WARDENS_ENRAGE = "toa_wardens_enrage";
	// Enrage lightning GraphicsObject ids, captured live from the [WARDEN-GFX] logs
	// (session 2026-08-23): the general P3 lightning (2220-2223) stops the instant
	// the warden enrages and these take over for the rest of the fight, so their
	// appearance — while the final Warden is present — marks enrage start precisely.
	private static final Set<Integer> WARDEN_LIGHTNING_GFX_IDS =
		new HashSet<>(java.util.Arrays.asList(2197, 1446));

	private NPC finalWarden;         // the P3+ Warden NPC while present
	private boolean wardenEnraged;   // true once the enrage/lightning phase has begun
	private int wardenLowestRatio = Integer.MAX_VALUE;

	// Chat-spam guard: a task's failure line is announced AT MOST ONCE per raid.
	// Without this, re-entering a room (per-room challenges reset their attempt each
	// time the window reopens) or the every-tick re-check would re-print the same
	// "Challenge Failed" line over and over within a single raid. The set of already-
	// announced taskIds is cleared only when a NEW raid begins — detected as the
	// raid-level going from "no raid" to "in a raid" — so it survives room changes
	// and task-state rebuilds mid-raid but resets cleanly for the next attempt.
	private final Set<String> failureAnnouncedThisRaid = new HashSet<>();
	private boolean wasInRaid;

	// obtain_all "made it" gating: which skills gained XP THIS tick (recomputed at the
	// top of onGameTick, which fires AFTER the tick's Stat/Item events — so the deltas
	// are exact and immune to Stat-vs-Item event order). lastSkillXp holds the prior xp.
	private final Map<Skill, Integer> lastSkillXp = new EnumMap<>(Skill.class);
	private final Set<Skill> gainedSkillsThisTick = EnumSet.noneOf(Skill.class);

	// defeat_simultaneous: recent target-NPC death ticks per task, to detect "kill N
	// within a few ticks of each other" (e.g. two Skeletal Mystics at once).
	private final Map<String, List<Integer>> simDeathTicks = new HashMap<>();

	// final_blow_vengeance: the target died and passed every other check — its
	// completion is HELD until end-of-tick (onGameTick), when both this death and the
	// Vengeance-rebound varbit change have been processed, so their order can't race.
	private int vengeancePrev;               // VENGEANCE_REBOUND at end of last tick
	private NuzlockeTask pendingVengeanceKill;
	private int pendingVengeanceKillTick = Integer.MIN_VALUE;


	private static boolean isFinalWarden(int npcId)
	{
		return npcId >= 11761 && npcId <= 11764; // contiguous: both wardens, Damaged/Enraged/Invuln
	}

	@Inject
	public RaidChallengeModule()
	{
	}

	@Override
	public String getCompletionType()
	{
		return TYPE;
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		states.clear();
		failureAnnouncedThisRaid.clear();
		wasInRaid = false;
		pendingVengeanceKill = null;
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		super.onTaskAssigned(task);
		addActiveTask(task);
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		super.addActiveTask(task);
		if (task.getChallenge() == null)
		{
			log.warn("RAID_CHALLENGE task {} has no challenge block", task.getTaskId());
			return;
		}
		State s = new State();
		// Roll the quantity target ONCE, then keep it stable. This module's state is
		// cleared and re-created on every loadActiveTasks (which runs several times a
		// session), so re-rolling here each time made the target wander — and a re-roll
		// landing on 1 completed a "do it N times" task after a single clear (Taylor's
		// "assigned 2, finished after 1"). A persisted multi-count target arrives via
		// task.getTargetQuantity() (initializeTask loads it from saved progress); reuse
		// it instead of re-rolling. Only roll when it hasn't been rolled yet (<= 1).
		int persistedTarget = task.getTargetQuantity();
		s.target = persistedTarget > 1 ? persistedTarget : Math.max(1, rollQuantity(task.getChallenge()));
		s.progress = Math.max(0, task.getCurrentProgress());
		// obtain_all: target = number of groups to complete; progress lives only within
		// a single raid window (supplies vanish on exit), so it never persists.
		List<List<Integer>> groups = obtainGroups(task.getChallenge());
		if (groups != null)
		{
			s.target = groups.size();
			s.progress = 0;
		}
		states.put(task.getTaskId(), s);
		task.setTargetQuantity(s.target);

		RaidChallenge ch = task.getChallenge();
		log.debug("[RAIDCHALLENGE-DEBUG] tracking {} (msg='{}', rooms={}, raidVarbit={}={}, minRaid={}, solo={}, forbidden={}, obtainAll={}, target={}, satisfyTriggered={})",
			task.getTaskId(), ch.getCompleteMessage(), ch.getRoomRegions(),
			(ch.getRaidLevelVarbit() != null ? ch.getRaidLevelVarbit() : DEFAULT_RAID_LEVEL_VARBIT), raidLevel(ch),
			ch.getMinRaidLevel(), ch.getSolo(),
			ch.getForbiddenItemIds() == null ? 0 : ch.getForbiddenItemIds().size(),
			obtainGroups(ch) == null ? 0 : obtainGroups(ch).size(),
			s.target, isSatisfyTriggered(ch));
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		states.clear();
		// Deliberately do NOT touch failureAnnouncedThisRaid / wasInRaid here.
		// onTaskCleared fires on every loadActiveTasks (varbit storms, region changes,
		// syncs — many times per raid), and clearing the once-per-raid guard here made
		// the same "Challenge Failed" line re-announce after each reload. The guard is
		// reset only on a genuine new-raid transition (see onGameTick) and on shutDown.
	}

	@Override
	public void checkProgress()
	{
		// Progress is event/tick driven; nothing to force here.
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		// Leaving the game world abandons any in-progress attempt.
		if (e.getGameState() != GameState.LOGGED_IN)
		{
			for (State s : states.values())
			{
				resetAttempt(s);
				s.windowOpen = false;
			}
			// The ToB entry banner won't replay on the next login, so forget the mode
			// (callers fail-open on UNKNOWN rather than punish a relog).
			tobMode.clear();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}
		updateWardenEnrage(); // refresh the Wardens enrage flag before phase checks

		// New-raid edge: when the player goes from not-in-a-raid to in-a-raid, forget
		// which failures were announced so the next raid can report fresh (see
		// failureAnnouncedThisRaid). Stays set for the whole raid across room changes.
		boolean inRaid = anyActiveTaskInRaid();
		if (inRaid && !wasInRaid)
		{
			failureAnnouncedThisRaid.clear();
		}
		wasInRaid = inRaid;

		recomputeSkillGains(); // which required skills gained XP this tick (obtain_all)

		// Vengeance rebound detection: the armed varbit going 1→0 this tick means you
		// took a hit and it reflected. Compared against last tick's value; onGameTick
		// runs at END of tick, so both the death (onActorDeath) and this varbit change
		// are already in when we resolve a held final_blow_vengeance kill below.
		int veng = client.isClientThread() ? client.getVarbitValue(VENGEANCE_REBOUND_VARBIT) : 0;
		boolean vengeanceReboundedThisTick = vengeancePrev == 1 && veng == 0;
		vengeancePrev = veng;
		resolvePendingVengeanceKill(vengeanceReboundedThisTick);

		int region = currentInstancedRegion();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null)
			{
				continue;
			}

			// defeat_npc tasks have no region window — their "window" is the encounter
			// (first hit on the target -> its death, driven by onHitsplat/onActorDeath).
			// Sample sustained conditions (no-run, weapon) only while that fight is live.
			if (ch.getDefeatNpcIds() != null && !ch.getDefeatNpcIds().isEmpty())
			{
				if (s.encounterActive)
				{
					sampleSustained(task, ch, s);
				}
				continue;
			}

			boolean inWin = isInWindow(ch, region);
			if (inWin && !s.windowOpen)
			{
				s.windowOpen = true;
				resetAttempt(s); // fresh attempt each time the window (re)opens
				seedObtainSnapshot(ch, s); // baseline counts so items held at entry don't count
				log.debug("[RAIDCHALLENGE-DEBUG] {} window OPEN (region={}, rooms={}, raidLevel={}, team={})",
					task.getTaskId(), region, ch.getRoomRegions(), raidLevel(ch), teamSize(ch));
			}
			else if (!inWin && s.windowOpen)
			{
				// Window closed. Do NOT reset here: the completion message can fire
				// AFTER leaving the fight room (Wardens → reward room), so the
				// attempt's violated/accumulator flags must survive until either the
				// message is evaluated or a new attempt opens the window again.
				s.windowOpen = false;
				log.debug("[RAIDCHALLENGE-DEBUG] {} window CLOSED (region={}, violated={})",
					task.getTaskId(), region, s.violated);
			}
			if (!inWin)
			{
				continue;
			}

			sampleSustained(task, ch, s);

			// obtain_all: credit each potion GROUP made (count up + required-skill XP) this tick.
			if (obtainGroups(ch) != null)
			{
				creditObtainAll(task, ch, s);
			}

			// Satisfy-triggered accumulators.
			if (ch.getNoDamageTicks() != null)
			{
				s.damageFreeTicks++;
				if (s.damageFreeTicks >= ch.getNoDamageTicks())
				{
					trySatisfy(task, ch, s);
				}
			}
			if (ch.getSurviveTicks() != null && phaseActive(ch))
			{
				s.surviveTicks++;
				if (s.surviveTicks % 25 == 0 || s.surviveTicks >= ch.getSurviveTicks())
				{
					log.debug("[RAIDCHALLENGE-DEBUG] {} survive {}/{} ticks (phase active)",
						task.getTaskId(), s.surviveTicks, ch.getSurviveTicks());
				}
				if (s.surviveTicks >= ch.getSurviveTicks())
				{
					trySatisfy(task, ch, s);
				}
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		// Latch the ToB raid mode from the "You enter the Theatre of Blood (X Mode)" banner.
		// Runs BEFORE the active-task early-return so it works even when only NPC_KILL ToB
		// tasks are assigned (those are gated in NPCKillModule off this same shared tracker).
		tobMode.observeChat(e.getMessage());

		if (activeTasks.isEmpty())
		{
			return;
		}
		ChatMessageType t = e.getType();
		if (t != ChatMessageType.GAMEMESSAGE && t != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = e.getMessage();
		if (msg == null)
		{
			return;
		}
		String lower = msg.toLowerCase();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null || ch.getCompleteMessage() == null)
			{
				continue;
			}
			// Satisfy-triggered challenges complete off their accumulator, not the message.
			if (isSatisfyTriggered(ch))
			{
				continue;
			}
			if (!lower.contains(ch.getCompleteMessage().toLowerCase()))
			{
				continue;
			}
			// Every raid prints "... count is: N" on completion (ToA AND CoX both do),
			// so a CoX kill-count line would otherwise match a ToA task's "count is"
			// trigger and fail it. Only honor a completion message when THIS task's raid
			// is actually active — its raid-level varbit reads > 0. Finishing one raid can
			// no longer fail another raid's challenges. (Off-thread raidLevel() returns 0,
			// but onChatMessage runs on the client thread, so the read is live here.)
			// This guard is ONLY for tasks that opt into raid-level gating (min_raid_level
			// or an explicit raid_level_varbit); a boss with a SPECIFIC completion message
			// and no raid-level gate — Royal Titans' "royal titans kill count is" — can't
			// collide with another boss, so it is trusted directly rather than blocked by
			// the default ToA varbit reading 0.
			if (usesRaidLevelGate(ch) && raidLevel(ch) <= 0)
			{
				continue;
			}
			boolean gates = gatesPass(ch);
			boolean pit = pointInTimeOk(ch);
			log.debug("[RAIDCHALLENGE-DEBUG] {} complete-msg matched; gatesPass={} (raidLevel={} min={} team={} solo={}) violated={} pointInTime={} progress={}/{}",
				task.getTaskId(), gates, raidLevel(ch), ch.getMinRaidLevel(), teamSize(ch), ch.getSolo(),
				s.violated, pit, s.progress, s.target);
			if (!gates || s.violated || !pit)
			{
				log.debug("[RAIDCHALLENGE-DEBUG] {} did NOT qualify this run — resetting attempt", task.getTaskId());
				// If a rule was broken mid-fight we already told the player why; only
				// announce the point-in-time / gate reason when nothing was flagged yet.
				if (!s.violated)
				{
					announceFailure(task, gateFailReason(ch, pit));
				}
				resetAttempt(s); // this run didn't qualify; try again next time
				continue;
			}
			s.progress++;
			task.setCurrentProgress(s.progress);
			if (s.progress >= s.target)
			{
				log.debug("[RAIDCHALLENGE-DEBUG] {} COMPLETE ({}/{})", task.getTaskId(), s.progress, s.target);
				complete(task, s);
			}
			else if (completionCallback != null)
			{
				log.debug("[RAIDCHALLENGE-DEBUG] {} progressed to {}/{}", task.getTaskId(), s.progress, s.target);
				completionCallback.onProgressUpdated(task, s.progress);
				sendProgress(task, s.progress, s.target);
			}
			resetAttempt(s);
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}

		// (a) Damage on the LOCAL PLAYER breaks a "no damage for N ticks" streak.
		if (e.getActor() == client.getLocalPlayer())
		{
			for (NuzlockeTask task : activeTasks)
			{
				State s = states.get(task.getTaskId());
				if (s != null && s.windowOpen)
				{
					s.damageFreeTicks = 0;
				}
			}
			return;
		}

		if (!(e.getActor() instanceof NPC) || e.getHitsplat() == null || e.getHitsplat().isOthers())
		{
			return; // not our splat (someone else's / non-player source)
		}
		int npcId = ((NPC) e.getActor()).getId();

		// (b) First hit on a defeat_npc target opens its encounter BEFORE the style check
		// below, so the opening hit is already subject to the weapon/style/no-run rules
		// (HM06's "Tekton, crush only" must judge the very first hit).
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null || s.encounterActive
				|| ch.getDefeatNpcIds() == null || !ch.getDefeatNpcIds().contains(npcId))
			{
				continue;
			}
			resetAttempt(s);          // fresh fight — clear any stale violation/flags
			s.encounterActive = true;
			log.debug("[RAIDCHALLENGE-DEBUG] {} encounter START (npc={})", task.getTaskId(), npcId);
		}

		// (c) Damage the LOCAL PLAYER deals to a style-gated NPC. Every hit on such an
		// NPC must use the task's required attack style (STAB/SLASH/CRUSH/…) or the run
		// fails. Active in a region window OR a defeat_npc encounter. Scoped to the listed
		// NPCs, so swapping weapons for a side target (a Zebak jug) is fine.
		CombatStyle current = currentCombatStyle();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			List<Integer> targets = styleTargetIds(ch);
			if (ch == null || s == null || !(s.windowOpen || s.encounterActive) || s.violated
				|| targets == null || !targets.contains(npcId))
			{
				continue;
			}
			CombatStyle required = requiredCombatStyle(ch);
			// Lenient on UNKNOWN: an unmapped weapon type is given the benefit of the
			// doubt (pass) rather than failing a possibly-valid attack. MELEE matches any
			// of STAB/SLASH/CRUSH (see styleMatches).
			if (!styleMatches(current, required))
			{
				s.violated = true;
				log.debug("[RAIDCHALLENGE-DEBUG] {} VIOLATED: hit NPC {} with {} (needs {})",
					task.getTaskId(), npcId, current, required);
				announceFailure(task, "You hit this boss with a " + current.label()
					+ " attack — " + required.label() + " only.");
			}
		}

		// (d) min_hitsplat: a single big enough hit on a target NPC completes the task —
		// satisfy-triggered, no kill needed ("Ratsplosion: hit Scurrius for 20+"). Only
		// OUR damage reaches here (others' splats were filtered at the top).
		int amount = e.getHitsplat().getAmount();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null || ch.getMinHitsplat() == null
				|| ch.getDefeatNpcIds() == null || !ch.getDefeatNpcIds().contains(npcId))
			{
				continue;
			}
			if (amount >= ch.getMinHitsplat())
			{
				log.debug("[RAIDCHALLENGE-DEBUG] {} min_hitsplat {} met (hit {})",
					task.getTaskId(), ch.getMinHitsplat(), amount);
				complete(task, s);
			}
		}

		// (e) hitsplat_values: a single hit of an EXACT allowed amount on a target NPC
		// completes the task — satisfy-triggered, no kill needed ("Prime Number: hit
		// Dagannoth Prime for a prime damage value"). Only OUR damage reaches here.
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null || ch.getHitsplatValues() == null
				|| ch.getDefeatNpcIds() == null || !ch.getDefeatNpcIds().contains(npcId))
			{
				continue;
			}
			if (ch.getHitsplatValues().contains(amount))
			{
				log.debug("[RAIDCHALLENGE-DEBUG] {} hitsplat_values matched (hit {})",
					task.getTaskId(), amount);
				complete(task, s);
			}
		}

		// (f) consecutive_hitsplat_value: N back-to-back hits of an exact amount on a target
		// NPC complete the task ("Snake Eyes: two 1s in a row on Zulrah"). Any other amount
		// resets the streak. Only OUR damage reaches here.
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null || ch.getConsecutiveHitsplatValue() == null
				|| ch.getDefeatNpcIds() == null || !ch.getDefeatNpcIds().contains(npcId))
			{
				continue;
			}
			if (amount == ch.getConsecutiveHitsplatValue())
			{
				s.hitStreak++;
				int need = ch.getConsecutiveHitsplatCount() == null ? 2 : ch.getConsecutiveHitsplatCount();
				log.debug("[RAIDCHALLENGE-DEBUG] {} consecutive_hitsplat {}/{} (hit {})",
					task.getTaskId(), s.hitStreak, need, amount);
				if (s.hitStreak >= need)
				{
					complete(task, s);
				}
			}
			else
			{
				s.hitStreak = 0; // a different amount breaks the run
			}
		}
	}

	// ── Combat-style resolution ──────────────────────────────────────────────
	// The active damage style is derived from EQUIPPED_WEAPON_TYPE varbit (357) +
	// ATTACK_STYLE varp (43): neither alone is enough — the varp is only a 0–3 index
	// whose meaning depends on the weapon type. This is the general version of the
	// old stab-only check, so tasks can now gate on any style via JSON
	// (required_attack_style). New weapon families are added to WEAPON_STYLES; an
	// unmapped type resolves to UNKNOWN and is treated leniently (passes).
	private static final int EQUIPPED_WEAPON_TYPE_VARBIT = 357;
	private static final int ATTACK_STYLE_VARP = 43;
	/** Bitmap varbit: 1 bit per active prayer (overheads at 12=magic/13=missiles/14=melee). */
	private static final int ACTIVE_PRAYERS_VARBIT = 4101;
	/** 1 while Vengeance is armed; flips to 0 the tick it rebounds (you took a hit). */
	private static final int VENGEANCE_REBOUND_VARBIT = 2450;

	enum CombatStyle
	{
		STAB, SLASH, CRUSH, RANGED, MAGIC,
		// MELEE is a REQUIRED-only meta-style: a weapon never resolves to it (they map to
		// STAB/SLASH/CRUSH), but a task can require "any melee" and have all three match.
		MELEE,
		UNKNOWN;

		String label()
		{
			return this == UNKNOWN ? "different" : name().toLowerCase();
		}
	}

	/**
	 * Does the player's current damage style satisfy the required one? UNKNOWN passes
	 * (lenient, an unmapped weapon), and the MELEE meta-style matches any of STAB/SLASH/
	 * CRUSH so a task can require "with melee" without naming a single sub-style.
	 */
	private static boolean styleMatches(CombatStyle current, CombatStyle required)
	{
		if (current == CombatStyle.UNKNOWN)
		{
			return true;
		}
		if (required == CombatStyle.MELEE)
		{
			return current == CombatStyle.STAB || current == CombatStyle.SLASH
				|| current == CombatStyle.CRUSH;
		}
		return current == required;
	}

	// weaponType (EQUIPPED_WEAPON_TYPE ordinal) -> the damage style of each of its
	// attack-style options, indexed by the ATTACK_STYLE varp (0..3). Verified live
	// where noted; the rest are the standard OSRS combat options. Anything absent
	// resolves to UNKNOWN (logged), never a wrong guess.
	private static final Map<Integer, CombatStyle[]> WEAPON_STYLES = buildWeaponStyles();

	private static Map<Integer, CombatStyle[]> buildWeaponStyles()
	{
		CombatStyle STAB = CombatStyle.STAB, SLASH = CombatStyle.SLASH,
			CRUSH = CombatStyle.CRUSH, RANGED = CombatStyle.RANGED, MAGIC = CombatStyle.MAGIC;
		Map<Integer, CombatStyle[]> m = new java.util.HashMap<>();
		m.put(0,  new CombatStyle[]{CRUSH, CRUSH, CRUSH});                 // UNARMED
		m.put(1,  new CombatStyle[]{SLASH, SLASH, CRUSH, SLASH});          // AXE
		m.put(2,  new CombatStyle[]{CRUSH, CRUSH, CRUSH});                 // BLUNT (mace/warhammer)
		m.put(3,  new CombatStyle[]{RANGED, RANGED, RANGED});             // BOW
		m.put(4,  new CombatStyle[]{SLASH, SLASH, STAB, SLASH});           // CLAW
		m.put(5,  new CombatStyle[]{RANGED, RANGED, RANGED});             // CROSSBOW
		m.put(7,  new CombatStyle[]{RANGED, RANGED, RANGED});             // CHINCHOMPA
		m.put(9,  new CombatStyle[]{SLASH, SLASH, STAB, SLASH});           // SLASH_SWORD (scimitar/longsword)
		m.put(10, new CombatStyle[]{SLASH, SLASH, CRUSH, SLASH});          // TWO_HANDED_SWORD
		m.put(11, new CombatStyle[]{STAB, STAB, CRUSH, STAB});            // PICKAXE
		m.put(12, new CombatStyle[]{STAB, SLASH, STAB});                  // POLEARM (halberd: Jab/Swipe/Fend)
		m.put(13, new CombatStyle[]{CRUSH, CRUSH, CRUSH});               // POLESTAFF
		m.put(14, new CombatStyle[]{SLASH, SLASH, CRUSH, SLASH});          // SCYTHE
		m.put(15, new CombatStyle[]{STAB, SLASH, CRUSH, STAB});           // SPEAR / hasta
		m.put(16, new CombatStyle[]{CRUSH, CRUSH, STAB, CRUSH});          // SPIKED (e.g. some maces)
		m.put(17, new CombatStyle[]{STAB, STAB, SLASH, STAB});            // STAB_SWORD (dagger/rapier/Fang) — style 1 verified live
		m.put(19, new CombatStyle[]{SLASH, SLASH, SLASH});               // WHIP
		m.put(22, new CombatStyle[]{CRUSH, CRUSH, CRUSH});               // BLUDGEON
		return m;
	}

	/** The player's current damage style, or UNKNOWN for an unmapped weapon type. */
	private CombatStyle currentCombatStyle()
	{
		int weaponType = client.getVarbitValue(EQUIPPED_WEAPON_TYPE_VARBIT);
		int style = client.getVarpValue(ATTACK_STYLE_VARP);
		CombatStyle[] styles = WEAPON_STYLES.get(weaponType);
		if (styles == null || style < 0 || style >= styles.length)
		{
			return CombatStyle.UNKNOWN;
		}
		return styles[style];
	}

	/** NPCs whose hits are style-gated ({@code style_target_ids}, legacy {@code stab_target_ids}). */
	private static List<Integer> styleTargetIds(RaidChallenge ch)
	{
		if (ch == null)
		{
			return null;
		}
		if (ch.getStyleTargetIds() != null && !ch.getStyleTargetIds().isEmpty())
		{
			return ch.getStyleTargetIds();
		}
		return ch.getStabTargetIds(); // legacy alias
	}

	/** Required style for a style-gated task; defaults to STAB when unset. */
	private static CombatStyle requiredCombatStyle(RaidChallenge ch)
	{
		String s = ch == null ? null : ch.getRequiredAttackStyle();
		if (s == null || s.trim().isEmpty())
		{
			return CombatStyle.STAB; // back-compat: stab_target_ids without a style = STAB
		}
		try
		{
			return CombatStyle.valueOf(s.trim().toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			return CombatStyle.STAB;
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		if (activeTasks.isEmpty() || !(e.getActor() instanceof NPC))
		{
			return;
		}
		int id = ((NPC) e.getActor()).getId();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null)
			{
				continue;
			}
			if (s.windowOpen && !s.violated
				&& ch.getNoNpcDeathIds() != null && ch.getNoNpcDeathIds().contains(id))
			{
				s.violated = true; // a protected NPC died (e.g. an energy siphon)
				log.debug("[RAIDCHALLENGE-DEBUG] {} VIOLATED: protected NPC {} died", task.getTaskId(), id);
				announceFailure(task, "A protected NPC was killed — this run no longer counts.");
			}
			// defeat_count: tally a counted add's death within the fight window.
			if (ch.getDefeatCount() != null && ch.getDefeatCountNpcIds() != null
				&& ch.getDefeatCountNpcIds().contains(id))
			{
				tallyDefeatCount(task, ch, s);
			}
			if (ch.getDefeatNpcIds() != null && ch.getDefeatNpcIds().contains(id))
			{
				if (ch.getMinHitsplat() != null || ch.getHitsplatValues() != null
					|| ch.getConsecutiveHitsplatValue() != null)
				{
					// Satisfy-triggered on the hitsplat (onHitsplatApplied), not the kill — a
					// normal death must NOT complete a min_hitsplat / hitsplat_values /
					// consecutive_hitsplat_value task.
				}
				else if (Boolean.TRUE.equals(ch.getFinalBlowVengeance()))
				{
					// The kill must be a Vengeance rebound. Everything but "how it died"
					// is checked here; the vengeance-timing decision is HELD to end-of-tick
					// (resolvePendingVengeanceKill) so death-vs-varbit order can't race.
					boolean gates = gatesPass(ch);
					boolean pit = pointInTimeOk(ch);
					log.debug("[RAIDCHALLENGE-DEBUG] {} defeat_npc {} died (venge finish pending); violated={} gates={} pit={}",
						task.getTaskId(), id, s.violated, gates, pit);
					s.encounterActive = false;
					if (!s.violated && gates && pit)
					{
						pendingVengeanceKill = task;
						pendingVengeanceKillTick = client.getTickCount();
					}
					else
					{
						if (!s.violated)
						{
							announceFailure(task, gateFailReason(ch, pit));
						}
						resetAttempt(s);
					}
				}
				else if (ch.getDefeatSimultaneous() != null)
				{
					// "Kill N together": count target deaths within a tick window. The
					// encounter (opened on first hit) carries any sustained conditions —
					// e.g. the Vanguards' no-prayer-restore — so a violation blocks it.
					int tick = client.getTickCount();
					int window = ch.getDefeatWithinTicks() != null ? ch.getDefeatWithinTicks() : 2;
					List<Integer> ticks = simDeathTicks.computeIfAbsent(task.getTaskId(), k -> new ArrayList<>());
					ticks.add(tick);
					final int cut = tick - window;
					ticks.removeIf(t -> t < cut);
					log.debug("[RAIDCHALLENGE-DEBUG] {} simultaneous kill: {} death(s) within {} ticks (violated={})",
						task.getTaskId(), ticks.size(), window, s.violated);
					if (ticks.size() >= ch.getDefeatSimultaneous() && s.encounterActive
						&& !s.violated && gatesPass(ch) && pointInTimeOk(ch))
					{
						ticks.clear();
						s.encounterActive = false;
						complete(task, s);
					}
				}
				// defeat_npc: the target dying is the completion signal. Qualifies if the
				// encounter had no sustained violation and the gates/point-in-time hold.
				else if (s.encounterActive)
				{
					boolean gates = gatesPass(ch);
					boolean pit = pointInTimeOk(ch);
					log.debug("[RAIDCHALLENGE-DEBUG] {} defeat_npc {} died; violated={} gates={} pointInTime={}",
						task.getTaskId(), id, s.violated, gates, pit);
					s.encounterActive = false;
					if (!s.violated && gates && pit)
					{
						complete(task, s);
					}
					else
					{
						if (!s.violated)
						{
							announceFailure(task, gateFailReason(ch, pit));
						}
						resetAttempt(s);
					}
				}
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (activeTasks.isEmpty() || e.getItemContainer() != client.getItemContainer(InventoryID.INVENTORY))
		{
			return;
		}
		Item[] items = e.getItemContainer().getItems();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null || !s.windowOpen || s.violated)
			{
				continue;
			}

			// Forbidden items -> the run no longer counts.
			if (ch.getForbiddenItemIds() != null)
			{
				for (Item it : items)
				{
					if (it != null && ch.getForbiddenItemIds().contains(it.getId()))
					{
						s.violated = true; // took a raid-supplied item
						log.debug("[RAIDCHALLENGE-DEBUG] {} VIOLATED: forbidden item {} in inventory", task.getTaskId(), it.getId());
						announceFailure(task, "You picked up an item that isn't allowed for this challenge.");
						break;
					}
				}
			}
			// obtain_all is handled per-tick in onGameTick (creditObtainAll) so it can
			// pair item-count deltas with same-tick skill XP regardless of event order.
		}
	}

	// ── ToA Wardens enrage tracking (for phase "toa_wardens_enrage") ──────────

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		if (isFinalWarden(e.getNpc().getId()))
		{
			finalWarden = e.getNpc();
			wardenEnraged = false;
			wardenLowestRatio = Integer.MAX_VALUE;
		}
	}

	@Subscribe
	public void onNpcChanged(NpcChanged e)
	{
		if (isFinalWarden(e.getNpc().getId()))
		{
			finalWarden = e.getNpc();
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		if (finalWarden != null && e.getNpc() == finalWarden)
		{
			finalWarden = null;
			wardenEnraged = false;
			wardenLowestRatio = Integer.MAX_VALUE;
		}
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated e)
	{
		if (finalWarden == null)
		{
			return;
		}
		int id = e.getGraphicsObject().getId();
		if (!wardenEnraged && WARDEN_LIGHTNING_GFX_IDS.contains(id))
		{
			wardenEnraged = true;
		}
	}

	/**
	 * Tally one counted kill toward a defeat_count task, completing once the target N is
	 * reached in a single fight window. Only counts while the window is open and the
	 * attempt is clean; the tally resets when the window reopens (see resetAttempt), which
	 * is what makes it "in one fight". Counts every matching death in the room, not strictly
	 * the local player's — attribution per add is impractical in group content.
	 */
	private void tallyDefeatCount(NuzlockeTask task, RaidChallenge ch, State s)
	{
		if (!s.windowOpen || s.violated || ch.getDefeatCount() == null)
		{
			return;
		}
		s.defeatCount++;
		log.debug("[RAIDCHALLENGE-DEBUG] {} defeat_count {}/{}", task.getTaskId(), s.defeatCount, ch.getDefeatCount());
		if (s.defeatCount >= ch.getDefeatCount())
		{
			trySatisfy(task, ch, s);
		}
		else if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, s.defeatCount);
			sendProgress(task, s.defeatCount, ch.getDefeatCount());
		}
	}

	/**
	 * Detect enrage from the final Warden's HP heal-spike (near-death then heals
	 * ~20%). Works today; the gfx-object trigger above is more precise once its id
	 * is captured. Called each tick from onGameTick.
	 */
	private void updateWardenEnrage()
	{
		if (finalWarden == null)
		{
			return;
		}
		int ratio = finalWarden.getHealthRatio();
		int scale = finalWarden.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return;
		}
		wardenLowestRatio = Math.min(wardenLowestRatio, ratio);
		double pct = (double) ratio / scale;
		double lowPct = (double) wardenLowestRatio / scale;
		if (!wardenEnraged && lowPct <= 0.06 && (pct - lowPct) >= 0.10)
		{
			wardenEnraged = true;
		}
	}

	// ── Evaluation helpers ───────────────────────────────────────────────────

	/** Per-tick sustained-condition sampling; a single failure taints the attempt. */
	private void sampleSustained(NuzlockeTask task, RaidChallenge ch, State s)
	{
		if (s.violated)
		{
			return;
		}
		// `why` is the debug detail (ids/varps for the log); `reason` is the plain-
		// English line the player sees so they know what broke the run.
		String why = null;
		String reason = null;
		if (Boolean.TRUE.equals(ch.getNoRun()) && client.getVarpValue(runVarp(ch)) != 0)
		{
			why = "run enabled (varp " + runVarp(ch) + ")";
			reason = "Run was on — this challenge must be done with run disabled.";
		}
		else if (ch.getWeaponIds() != null && !ch.getWeaponIds().contains(equippedId(3)))
		{
			why = "weapon " + equippedId(3) + " not one of " + ch.getWeaponIds();
			reason = "You weren't using a required weapon for this challenge.";
		}
		else if (ch.getEmptySlots() != null)
		{
			for (int slot : ch.getEmptySlots())
			{
				if (equippedId(slot) != -1)
				{
					why = "slot " + slot + " occupied (item " + equippedId(slot) + ")";
					reason = "An equipment slot that must stay empty is filled.";
					break;
				}
			}
		}
		if (why == null && ch.getRequiredInventoryIds() != null)
		{
			Map<Integer, Integer> counts = inventoryCounts();
			for (int reqId : ch.getRequiredInventoryIds())
			{
				if (counts.getOrDefault(reqId, 0) <= 0)
				{
					why = "required inventory item " + reqId + " missing";
					reason = "You must keep the required item in your inventory for this challenge.";
					break;
				}
			}
		}
		if (why == null && Boolean.TRUE.equals(ch.getEmptyInventory()) && !inventoryCounts().isEmpty())
		{
			why = "inventory not empty";
			reason = "Your inventory must be completely empty for this challenge.";
		}
		if (why == null && ch.getRequiredEquippedGroups() != null)
		{
			for (List<Integer> group : ch.getRequiredEquippedGroups())
			{
				boolean worn = false;
				for (int id : group)
				{
					if (isEquipped(id))
					{
						worn = true;
						break;
					}
				}
				if (!worn)
				{
					why = "required equipped group " + group + " not satisfied";
					reason = "You must be wearing the full required set for this challenge.";
					break;
				}
			}
		}
		if (why == null && ch.getForbiddenPrayerBits() != null)
		{
			int prayers = client.getVarbitValue(ACTIVE_PRAYERS_VARBIT);
			for (int bit : ch.getForbiddenPrayerBits())
			{
				if ((prayers & (1 << bit)) != 0)
				{
					why = "forbidden prayer bit " + bit + " active";
					reason = "You used a prayer that isn't allowed for this challenge.";
					break;
				}
			}
		}
		if (why == null && ch.getRequiredEquippedIds() != null)
		{
			for (int reqId : ch.getRequiredEquippedIds())
			{
				if (!isEquipped(reqId))
				{
					why = "required equipped item " + reqId + " not worn";
					reason = "You must be wearing the required gear for this challenge.";
					break;
				}
			}
		}
		if (why == null && ch.getAttackStyleVarp() != null && ch.getAttackStyleValues() != null
			&& !ch.getAttackStyleValues().contains(client.getVarpValue(ch.getAttackStyleVarp())))
		{
			why = "attack style varp=" + client.getVarpValue(ch.getAttackStyleVarp())
				+ " not one of " + ch.getAttackStyleValues();
			reason = "Wrong attack style for this challenge.";
		}
		if (why == null && hasArenaBox(ch))
		{
			// An HP-gated arena (e.g. Maiden's Red Carpet from 50% down) is only enforced
			// once the watched boss reaches the threshold; latch it, then check position.
			updateArenaHpLatch(ch, s);
			if (arenaGateOpen(ch, s) && outsideArenaBox(ch))
			{
				why = "outside arena box";
				reason = "You left the area this challenge must be done in.";
			}
		}
		// Gear-value budget: fail the instant the window opens if you walk in over
		// budget, instead of staying silent until the completion message. Checked
		// every tick so re-equipping something pricey mid-fight also trips it.
		if (why == null && ch.getMaxGearValue() != null && equippedGearValue() >= ch.getMaxGearValue())
		{
			why = "gear value " + equippedGearValue() + " >= max " + ch.getMaxGearValue();
			reason = "Your equipped gear is worth too much — must be under "
				+ formatGp(ch.getMaxGearValue()) + " (you have " + formatGp(equippedGearValue()) + ").";
		}
		if (why == null && ch.getMinGearValue() != null && equippedGearValue() < ch.getMinGearValue())
		{
			why = "gear value " + equippedGearValue() + " < min " + ch.getMinGearValue();
			reason = "Your equipped gear isn't worth enough — need at least "
				+ formatGp(ch.getMinGearValue()) + " (you have " + formatGp(equippedGearValue()) + ").";
		}
		if (why == null && ch.getMinPrayerBonus() != null && equippedPrayerBonus() < ch.getMinPrayerBonus())
		{
			why = "prayer bonus " + equippedPrayerBonus() + " < min " + ch.getMinPrayerBonus();
			reason = "Your equipped Prayer bonus is too low — need at least +"
				+ ch.getMinPrayerBonus() + " (you have +" + equippedPrayerBonus() + ").";
		}
		if (why == null && ch.getMinCrushDefence() != null && equippedCrushDefence() < ch.getMinCrushDefence())
		{
			why = "crush defence " + equippedCrushDefence() + " < min " + ch.getMinCrushDefence();
			reason = "Your equipped Crush defence is too low — need at least "
				+ ch.getMinCrushDefence() + " (you have " + equippedCrushDefence() + ").";
		}
		if (why != null)
		{
			s.violated = true;
			log.debug("[RAIDCHALLENGE-DEBUG] {} VIOLATED: {}", task.getTaskId(), why);
			announceFailure(task, reason);
		}
	}

	/** Point-in-time reads at the completion message (weight / gear value). */
	private boolean pointInTimeOk(RaidChallenge ch)
	{
		int weight = client.getWeight();
		if (ch.getMinWeightKg() != null && weight < ch.getMinWeightKg())
		{
			return false;
		}
		if (ch.getMaxWeightKg() != null && weight > ch.getMaxWeightKg())
		{
			return false;
		}
		if (ch.getMaxGearValue() != null && equippedGearValue() >= ch.getMaxGearValue())
		{
			return false;
		}
		if (ch.getMinGearValue() != null && equippedGearValue() < ch.getMinGearValue())
		{
			return false;
		}
		if (ch.getForbiddenAliveNpcIds() != null && anyForbiddenNpcAlive(ch.getForbiddenAliveNpcIds()))
		{
			return false;
		}
		return true;
	}

	/** True if any NPC with a forbidden id is currently alive in the scene (see forbidden_alive_npc_ids). */
	private boolean anyForbiddenNpcAlive(List<Integer> ids)
	{
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && !npc.isDead() && ids.contains(npc.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * True if this challenge opts into raid-level gating — ToA's {@code min_raid_level} or an
	 * explicit {@code raid_level_varbit} (CoX authors 5432). The cross-raid "count is" guard
	 * applies only to these; a boss with a specific completion message and no raid-level gate
	 * is trusted directly (its message can't collide with another raid's).
	 */
	private static boolean usesRaidLevelGate(RaidChallenge ch)
	{
		return ch.getMinRaidLevel() != null || ch.getRaidLevelVarbit() != null;
	}

	private boolean gatesPass(RaidChallenge ch)
	{
		if (ch.getMinRaidLevel() != null && raidLevel(ch) < ch.getMinRaidLevel())
		{
			return false;
		}
		return !Boolean.TRUE.equals(ch.getSolo()) || teamSize(ch) == 1;
	}

	/** Satisfy-triggered completion (accumulator or defence-reduce reached its goal). */
	private void trySatisfy(NuzlockeTask task, RaidChallenge ch, State s)
	{
		if (s.violated || !gatesPass(ch) || !pointInTimeOk(ch))
		{
			boolean pit = pointInTimeOk(ch);
			log.debug("[RAIDCHALLENGE-DEBUG] {} reached its target but did NOT qualify (violated={} gates={} pointInTime={})",
				task.getTaskId(), s.violated, gatesPass(ch), pit);
			// This runs every tick while the accumulator is maxed but the gates fail;
			// announce the reason once so it doesn't spam the chatbox each tick.
			if (!s.violated && !s.satisfyFailTold)
			{
				s.satisfyFailTold = true;
				announceFailure(task, gateFailReason(ch, pit));
			}
			return;
		}
		s.progress = s.target;
		task.setCurrentProgress(s.target);
		log.debug("[RAIDCHALLENGE-DEBUG] {} SATISFIED -> complete", task.getTaskId());
		complete(task, s);
	}

	private boolean isSatisfyTriggered(RaidChallenge ch)
	{
		return ch.getNoDamageTicks() != null || ch.getSurviveTicks() != null
			|| obtainGroups(ch) != null || ch.getMinHitsplat() != null
			|| ch.getHitsplatValues() != null
			|| ch.getConsecutiveHitsplatValue() != null
			|| ch.getDefeatCount() != null;
	}

	// ── obtain_all helpers ───────────────────────────────────────────────────

	/**
	 * Item groups for an obtain_all task: {@code obtain_all_item_groups} if set, else
	 * each id in the legacy flat {@code obtain_all_item_ids} as its own group. null when
	 * the task isn't an obtain_all. Any one item from a group satisfies that group.
	 */
	private static List<List<Integer>> obtainGroups(RaidChallenge ch)
	{
		if (ch.getObtainAllItemGroups() != null && !ch.getObtainAllItemGroups().isEmpty())
		{
			return ch.getObtainAllItemGroups();
		}
		if (ch.getObtainAllItemIds() != null && !ch.getObtainAllItemIds().isEmpty())
		{
			List<List<Integer>> g = new ArrayList<>();
			for (Integer id : ch.getObtainAllItemIds())
			{
				g.add(Collections.singletonList(id));
			}
			return g;
		}
		return null;
	}

	/** Skill an obtain_all item must be MADE with ({@code obtain_all_require_skill}), or null. */
	private static Skill requiredObtainSkill(RaidChallenge ch)
	{
		String s = ch.getObtainAllRequireSkill();
		if (s == null || s.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return Skill.valueOf(s.trim().toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** Recompute which required obtain-skills gained XP this tick (client thread only). */
	private void recomputeSkillGains()
	{
		gainedSkillsThisTick.clear();
		Set<Skill> required = EnumSet.noneOf(Skill.class);
		for (NuzlockeTask task : activeTasks)
		{
			RaidChallenge ch = task.getChallenge();
			Skill sk = ch == null ? null : requiredObtainSkill(ch);
			if (sk != null)
			{
				required.add(sk);
			}
		}
		for (Skill sk : required)
		{
			int xp = client.getSkillExperience(sk);
			Integer prev = lastSkillXp.put(sk, xp);
			if (prev != null && xp > prev)
			{
				gainedSkillsThisTick.add(sk);
			}
		}
	}

	/** Baseline the obtain_all item counts at window open so items held at entry don't count. */
	private void seedObtainSnapshot(RaidChallenge ch, State s)
	{
		List<List<Integer>> groups = obtainGroups(ch);
		if (groups == null)
		{
			return;
		}
		Map<Integer, Integer> counts = inventoryCounts();
		for (List<Integer> group : groups)
		{
			for (int id : group)
			{
				s.obtainSnapshot.put(id, counts.getOrDefault(id, 0));
			}
		}
	}

	/**
	 * Credit each obtain_all GROUP the player MADE this tick: an item in the group whose
	 * inventory count rose since last tick, paired with a required-skill XP gain this
	 * tick — so MIXING counts, picking one up does not. Completes when every group is made.
	 */
	private void creditObtainAll(NuzlockeTask task, RaidChallenge ch, State s)
	{
		if (s.violated)
		{
			return;
		}
		List<List<Integer>> groups = obtainGroups(ch);
		if (groups == null)
		{
			return;
		}
		Skill req = requiredObtainSkill(ch);
		boolean madeThisTick = req == null || gainedSkillsThisTick.contains(req);
		Map<Integer, Integer> counts = inventoryCounts();

		boolean changed = false;
		for (int g = 0; g < groups.size(); g++)
		{
			boolean groupMade = false;
			for (int id : groups.get(g))
			{
				int now = counts.getOrDefault(id, 0);
				Integer prev = s.obtainSnapshot.get(id);
				int before = prev == null ? 0 : prev;
				if (now > before && madeThisTick)
				{
					groupMade = true;
				}
				s.obtainSnapshot.put(id, now); // slide regardless — a pickup just moves the baseline
			}
			if (groupMade && s.obtainedGroups.add(g))
			{
				changed = true;
			}
		}
		if (!changed)
		{
			return;
		}
		s.progress = s.obtainedGroups.size();
		task.setCurrentProgress(s.progress);
		log.debug("[RAIDCHALLENGE-DEBUG] {} obtain-all(made) {}/{}", task.getTaskId(), s.progress, groups.size());
		if (s.obtainedGroups.size() >= groups.size())
		{
			trySatisfy(task, ch, s);
		}
		else if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, s.progress);
			sendProgress(task, s.progress, s.target);
		}
	}

	/** Inventory item id -> total quantity. */
	private Map<Integer, Integer> inventoryCounts()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				if (it != null && it.getId() > 0)
				{
					counts.merge(it.getId(), Math.max(1, it.getQuantity()), Integer::sum);
				}
			}
		}
		return counts;
	}

	private void complete(NuzlockeTask task, State s)
	{
		if (task.isCompleted())
		{
			return;
		}
		// ToB Entry-mode gate: the single funnel every completion path passes through, so
		// one check covers defeat_npc, satisfy-triggered and defeat_count alike. Fail-open on
		// UNKNOWN mode (a relog): only a KNOWN Entry raid blocks a forbid_entry_mode task.
		if (task.isForbidEntryMode() && tobMode.isEntry())
		{
			log.debug("[RAIDCHALLENGE-DEBUG] {} blocked — Theatre of Blood Entry mode does not count", task.getTaskId());
			return;
		}
		task.setCompleted(true);
		sendSuccess(task);
		if (completionCallback != null)
		{
			completionCallback.onTaskCompleted(task, s.progress);
		}
		states.remove(task.getTaskId());
		activeTasks.remove(task);
	}

	private void resetAttempt(State s)
	{
		s.violated = false;
		s.damageFreeTicks = 0;
		s.surviveTicks = 0;
		s.satisfyFailTold = false;
		s.obtainedGroups.clear();
		s.obtainSnapshot.clear();
		s.encounterActive = false;
		s.arenaHpGateLatched = false;
		s.defeatCount = 0;
		s.hitStreak = 0;
	}

	/**
	 * Finish a held final_blow_vengeance kill at end-of-tick: complete it only if the
	 * target died THIS tick AND Vengeance rebounded this tick (the death coincided with
	 * the reflected hit). Any other end — a normal killing blow, or the tick advancing
	 * without a rebound — means the finish wasn't Vengeance, so the attempt is failed.
	 */
	private void resolvePendingVengeanceKill(boolean vengeanceReboundedThisTick)
	{
		if (pendingVengeanceKill == null)
		{
			return;
		}
		NuzlockeTask task = pendingVengeanceKill;
		pendingVengeanceKill = null;
		State s = states.get(task.getTaskId());
		if (s == null)
		{
			return;
		}
		if (pendingVengeanceKillTick == client.getTickCount() && vengeanceReboundedThisTick)
		{
			log.debug("[RAIDCHALLENGE-DEBUG] {} vengeance finish CONFIRMED", task.getTaskId());
			complete(task, s);
		}
		else
		{
			log.debug("[RAIDCHALLENGE-DEBUG] {} died without a vengeance rebound this tick", task.getTaskId());
			announceFailure(task, "The finishing blow wasn't a Vengeance rebound — Vengeance must land the kill.");
			resetAttempt(s);
		}
	}

	// ── Raw reads ────────────────────────────────────────────────────────────

	private boolean isInWindow(RaidChallenge ch, int region)
	{
		if (ch.getRoomRegions() != null && !ch.getRoomRegions().isEmpty())
		{
			return ch.getRoomRegions().contains(region);
		}
		return raidLevel(ch) > 0; // whole-raid scope
	}

	private int raidLevel(RaidChallenge ch)
	{
		// getVarbitValue asserts the CLIENT thread. addActiveTask() can run OFF it —
		// unlockRegion() -> loadActiveTasks() fires from a minimap-unlock popup callback
		// on the EDT — and reading the varbit there threw AssertionError, which aborted
		// loadActiveTasks mid-registration and left an unlock half-applied until relog
		// (Travis: "unlocked underground, then couldn't unlock upstairs"). Off the client
		// thread there is no live raid anyway, so 0 is the correct, safe answer. The real
		// window/gate checks all run in onGameTick/onChatMessage on the client thread.
		if (!client.isClientThread())
		{
			return 0;
		}
		int v = ch.getRaidLevelVarbit() != null ? ch.getRaidLevelVarbit() : DEFAULT_RAID_LEVEL_VARBIT;
		return client.getVarbitValue(v);
	}

	private int runVarp(RaidChallenge ch)
	{
		return ch.getRunVarp() != null ? ch.getRunVarp() : DEFAULT_RUN_VARP;
	}

	private boolean phaseActive(RaidChallenge ch)
	{
		// Named phase gates first (for phases with no varbit, like the Wardens enrage).
		String phase = ch.getPhase();
		if (phase != null && !phase.isEmpty())
		{
			if (PHASE_TOA_WARDENS_ENRAGE.equalsIgnoreCase(phase))
			{
				return finalWarden != null && wardenEnraged;
			}
			return false; // unknown phase key — never active (safe)
		}
		Integer pv = ch.getPhaseVarbit();
		if (pv == null)
		{
			return true; // no phase gate — count from window open
		}
		if (pv <= 0)
		{
			// UNVERIFIED sentinel: the real phase varbit hasn't been captured yet.
			// Treat the phase as never active so a survive-timer can't false-complete
			// on general room time (Mike's 150 solo completed "Stay Angry" while just
			// in the Wardens room, because varbit 0 read nonzero). The enrage/"final
			// lightning" phase varbit must be captured in-game and authored here.
			return false;
		}
		int want = ch.getPhaseValue() != null ? ch.getPhaseValue() : 1;
		return client.getVarbitValue(pv) == want;
	}

	private int teamSize(RaidChallenge ch)
	{
		int[] varbits = DEFAULT_PARTY_VARBITS;
		List<Integer> override = ch.getPartySizeVarbits();
		int size = 0;
		if (override != null && !override.isEmpty())
		{
			for (int v : override)
			{
				size += Math.min(client.getVarbitValue(v), 1);
			}
		}
		else
		{
			for (int v : varbits)
			{
				size += Math.min(client.getVarbitValue(v), 1);
			}
		}
		return size;
	}

	private int currentInstancedRegion()
	{
		Player p = client.getLocalPlayer();
		if (p == null)
		{
			return -1;
		}
		LocalPoint lp = p.getLocalLocation();
		if (lp == null)
		{
			return -1;
		}
		WorldPoint wp = WorldPoint.fromLocalInstance(client, lp);
		return wp == null ? -1 : wp.getRegionID();
	}

	private int equippedId(int slot)
	{
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null)
		{
			return -1;
		}
		Item it = eq.getItem(slot);
		return it == null ? -1 : it.getId();
	}

	/** True if the given item id is currently worn in any equipment slot. */
	private boolean isEquipped(int itemId)
	{
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null)
		{
			return false;
		}
		for (Item it : eq.getItems())
		{
			if (it != null && it.getId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	private long equippedGearValue()
	{
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null)
		{
			return 0;
		}
		long total = 0;
		for (Item it : eq.getItems())
		{
			if (it != null && it.getId() > 0)
			{
				total += (long) itemManager.getItemPrice(it.getId()) * Math.max(1, it.getQuantity());
			}
		}
		return total;
	}

	/** Summed Prayer bonus of every equipped item (from its equipment stats). */
	private int equippedPrayerBonus()
	{
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null)
		{
			return 0;
		}
		int total = 0;
		for (Item it : eq.getItems())
		{
			if (it == null || it.getId() <= 0)
			{
				continue;
			}
			ItemStats stats = itemManager.getItemStats(it.getId(), false);
			if (stats != null && stats.getEquipment() != null)
			{
				total += stats.getEquipment().getPrayer();
			}
		}
		return total;
	}

	/** Summed Crush defence bonus of every equipped item (from its equipment stats). */
	private int equippedCrushDefence()
	{
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq == null)
		{
			return 0;
		}
		int total = 0;
		for (Item it : eq.getItems())
		{
			if (it == null || it.getId() <= 0)
			{
				continue;
			}
			ItemStats stats = itemManager.getItemStats(it.getId(), false);
			if (stats != null && stats.getEquipment() != null)
			{
				total += stats.getEquipment().getDcrush();
			}
		}
		return total;
	}

	/** Compact gp for chat feedback: 12345678 → "12m". */
	private static String formatGp(long gp)
	{
		if (gp >= 1_000_000_000L)
		{
			return (gp / 1_000_000_000L) + "b";
		}
		if (gp >= 1_000_000L)
		{
			return (gp / 1_000_000L) + "m";
		}
		if (gp >= 1_000L)
		{
			return (gp / 1_000L) + "k";
		}
		return String.valueOf(gp);
	}

	private boolean hasArenaBox(RaidChallenge ch)
	{
		return ch.getArenaMinX() != null || ch.getArenaMaxX() != null
			|| ch.getArenaMinY() != null || ch.getArenaMaxY() != null
			|| (ch.getArenaBoxes() != null && !ch.getArenaBoxes().isEmpty());
	}

	/**
	 * True if the player is OUTSIDE the authored arena box. Coordinates are region-
	 * local (worldX/Y &amp; 63 of the instance's template location), matching the
	 * regionX/regionY of a RuneLite ground marker.
	 */
	private boolean outsideArenaBox(RaidChallenge ch)
	{
		Player p = client.getLocalPlayer();
		if (p == null || p.getLocalLocation() == null)
		{
			return false;
		}
		WorldPoint wp = WorldPoint.fromLocalInstance(client, p.getLocalLocation());
		if (wp == null)
		{
			return false;
		}
		int x = wp.getX() & 63;
		int y = wp.getY() & 63;

		// The valid area is the UNION of the legacy single box and every arena_boxes
		// entry. Inside ANY box → OK; outside ALL of them → violated. This lets an
		// L-shaped / split legal area (e.g. Ba-Ba's floor plus the platform you stand
		// on) be described as a couple of rectangles instead of one that wrongly
		// clips out the platform.
		boolean anyBox = false;
		if (ch.getArenaMinX() != null || ch.getArenaMaxX() != null
			|| ch.getArenaMinY() != null || ch.getArenaMaxY() != null)
		{
			anyBox = true;
			if (inBox(x, y, ch.getArenaMinX(), ch.getArenaMaxX(), ch.getArenaMinY(), ch.getArenaMaxY()))
			{
				return false;
			}
		}
		if (ch.getArenaBoxes() != null)
		{
			for (RaidChallenge.ArenaBox b : ch.getArenaBoxes())
			{
				if (b == null)
				{
					continue;
				}
				anyBox = true;
				if (inBox(x, y, b.getMinX(), b.getMaxX(), b.getMinY(), b.getMaxY()))
				{
					return false;
				}
			}
		}
		// Boxes defined but the player is in none → outside. No boxes at all → nothing
		// to enforce, so never "outside".
		return anyBox;
	}

	/** True if (x,y) is within a box's bounds; an absent bound is unbounded on that side. */
	private static boolean inBox(int x, int y, Integer minX, Integer maxX, Integer minY, Integer maxY)
	{
		if (minX != null && x < minX)
		{
			return false;
		}
		if (maxX != null && x > maxX)
		{
			return false;
		}
		if (minY != null && y < minY)
		{
			return false;
		}
		return maxY == null || y <= maxY;
	}

	/**
	 * Latch the arena's HP gate ON the first time a watched NPC is at or below the
	 * configured health percent. Once latched it stays on for the rest of the attempt
	 * (reset in resetAttempt), so a boss that heals back up — Maiden feeding on blood
	 * spawns — does not reopen free movement. No-op when no HP gate is configured.
	 */
	private void updateArenaHpLatch(RaidChallenge ch, State s)
	{
		if (s.arenaHpGateLatched)
		{
			return;
		}
		List<Integer> ids = ch.getArenaHpGateNpcIds();
		Integer pct = ch.getArenaHpGateBelowPercent();
		if (ids == null || ids.isEmpty() || pct == null)
		{
			return;
		}
		List<NPC> npcs = client.getNpcs();
		if (npcs == null)
		{
			return;
		}
		for (NPC npc : npcs)
		{
			if (npc == null || !ids.contains(npc.getId()))
			{
				continue;
			}
			int ratio = npc.getHealthRatio();
			int scale = npc.getHealthScale();
			if (ratio < 0 || scale <= 0)
			{
				continue; // no health bar shown yet — can't judge, wait for the next tick
			}
			double healthPct = (double) ratio / scale * 100.0;
			if (healthPct <= pct)
			{
				s.arenaHpGateLatched = true;
				log.debug("[RAIDCHALLENGE-DEBUG] arena HP gate OPEN (npc {} at {}% <= {}%)",
					npc.getId(), Math.round(healthPct), pct);
				return;
			}
		}
	}

	/**
	 * Whether the arena is currently being enforced: always, unless an HP gate is
	 * configured, in which case only after it has latched (see updateArenaHpLatch).
	 */
	private boolean arenaGateOpen(RaidChallenge ch, State s)
	{
		if (ch.getArenaHpGateNpcIds() == null || ch.getArenaHpGateNpcIds().isEmpty()
			|| ch.getArenaHpGateBelowPercent() == null)
		{
			return true; // no HP gate — arena enforced for the whole window
		}
		return s.arenaHpGateLatched;
	}

	private int rollQuantity(RaidChallenge ch)
	{
		Integer min = ch.getMinQuantity();
		Integer max = ch.getMaxQuantity();
		if (min == null && max == null)
		{
			return 1;
		}
		int lo = min != null ? min : 1;
		int hi = max != null ? max : lo;
		if (hi <= lo)
		{
			return lo;
		}
		return lo + random.nextInt(hi - lo + 1);
	}

	private void sendSuccess(NuzlockeTask task)
	{
		if (!config.showChatSuccess())
		{
			return;
		}
		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> "
			+ "<col=" + COLOR_DARK_BLUE + ">Challenge Complete!</col> "
			+ "<col=" + COLOR_BLACK + ">" + task.getName() + "</col>";
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());
	}

	/**
	 * Progress toward a multi-count challenge (e.g. "beat this room 3 times").
	 * Mirrors the regular task modules' "Task Progress (n/N)" line.
	 */
	private void sendProgress(NuzlockeTask task, int progress, int target)
	{
		if (!config.showChatProgress())
		{
			return;
		}
		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> "
			+ "<col=" + COLOR_DARK_GREEN + ">Challenge Progress:</col> "
			+ "<col=" + COLOR_BLACK + ">" + task.getName() + "</col> "
			+ "(" + progress + "/" + target + ")";
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());
	}

	/** True if the player is currently inside a raid for any active challenge. */
	private boolean anyActiveTaskInRaid()
	{
		for (NuzlockeTask task : activeTasks)
		{
			if (task.getChallenge() != null && raidLevel(task.getChallenge()) > 0)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Announce a task's failure, but only ONCE per raid — repeat violations of the
	 * same task in the same raid (room re-entries, per-tick re-checks) stay silent so
	 * the chatbox isn't spammed. The set is cleared when a fresh raid starts.
	 */
	private void announceFailure(NuzlockeTask task, String reason)
	{
		if (failureAnnouncedThisRaid.add(task.getTaskId()))
		{
			sendFailure(task, reason);
		}
	}

	/**
	 * Tell the player their attempt did not count, and why — the raid-challenge
	 * equivalent of the regular modules' "Task Failed: ... - Reason:" feedback.
	 * Gated on the same showChatFailed toggle players already use.
	 */
	private void sendFailure(NuzlockeTask task, String reason)
	{
		if (!config.showChatFailed())
		{
			return;
		}
		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> "
			+ "<col=" + COLOR_RED + ">Challenge Failed:</col> "
			+ "<col=" + COLOR_BLACK + ">" + task.getName() + "</col>";
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());
		if (reason != null && !reason.isEmpty())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value("  - Reason: " + reason)
				.build());
		}
	}

	/**
	 * Plain-English reason a run failed its gates / point-in-time reads (raid level,
	 * solo, weight, gear value). Used when no sustained rule was broken mid-fight.
	 */
	private String gateFailReason(RaidChallenge ch, boolean pointInTimeOk)
	{
		if (ch.getMinRaidLevel() != null && raidLevel(ch) < ch.getMinRaidLevel())
		{
			return "Raid level was too low — need " + ch.getMinRaidLevel()
				+ "+ (was " + raidLevel(ch) + ").";
		}
		if (Boolean.TRUE.equals(ch.getSolo()) && teamSize(ch) != 1)
		{
			return "This challenge must be done solo (team size was " + teamSize(ch) + ").";
		}
		if (!pointInTimeOk)
		{
			int weight = client.getWeight();
			if (ch.getMinWeightKg() != null && weight < ch.getMinWeightKg())
			{
				return "Your weight was too low — need at least " + ch.getMinWeightKg() + "kg.";
			}
			if (ch.getMaxWeightKg() != null && weight > ch.getMaxWeightKg())
			{
				return "Your weight was too high — must be at most " + ch.getMaxWeightKg() + "kg.";
			}
			if (ch.getMaxGearValue() != null && equippedGearValue() >= ch.getMaxGearValue())
			{
				return "Your equipped gear was worth too much for this challenge.";
			}
		}
		return "The run didn't meet this challenge's requirements.";
	}
}
