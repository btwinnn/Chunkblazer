package com.chunkblazer.modules;

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
		final Set<Integer> obtainedIds = new HashSet<>(); // obtain_all: required items seen this window
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
		// obtain_all: the target is simply "how many distinct items to collect", and
		// progress lives only within a single raid window (supplies vanish on exit),
		// so it never carries a persisted count.
		if (task.getChallenge().getObtainAllItemIds() != null && !task.getChallenge().getObtainAllItemIds().isEmpty())
		{
			s.target = task.getChallenge().getObtainAllItemIds().size();
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
			ch.getObtainAllItemIds() == null ? 0 : ch.getObtainAllItemIds().size(),
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

		int region = currentInstancedRegion();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null)
			{
				continue;
			}

			boolean inWin = isInWindow(ch, region);
			if (inWin && !s.windowOpen)
			{
				s.windowOpen = true;
				resetAttempt(s); // fresh attempt each time the window (re)opens
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

		// (b) Damage the LOCAL PLAYER deals to a style-gated NPC. Every hit on such
		// an NPC must use the task's required attack style (STAB/SLASH/CRUSH/…) or the
		// run fails. Scoped to the listed NPCs only, so swapping weapons for a side
		// target (e.g. shooting a Zebak jug) is fine — that hit is on a different NPC.
		if (!(e.getActor() instanceof NPC) || e.getHitsplat() == null || e.getHitsplat().isOthers())
		{
			return; // not our splat (someone else's / non-player source)
		}
		int npcId = ((NPC) e.getActor()).getId();
		CombatStyle current = currentCombatStyle();
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			List<Integer> targets = styleTargetIds(ch);
			if (ch == null || s == null || !s.windowOpen || s.violated
				|| targets == null || !targets.contains(npcId))
			{
				continue;
			}
			CombatStyle required = requiredCombatStyle(ch);
			// Lenient on UNKNOWN: an unmapped weapon type is given the benefit of the
			// doubt (pass) rather than failing a possibly-valid attack.
			if (current != CombatStyle.UNKNOWN && current != required)
			{
				s.violated = true;
				log.debug("[RAIDCHALLENGE-DEBUG] {} VIOLATED: hit NPC {} with {} (needs {})",
					task.getTaskId(), npcId, current, required);
				announceFailure(task, "You hit this boss with a " + current.label()
					+ " attack — " + required.label() + " only.");
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

	enum CombatStyle
	{
		STAB, SLASH, CRUSH, RANGED, MAGIC, UNKNOWN;

		String label()
		{
			return this == UNKNOWN ? "different" : name().toLowerCase();
		}
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
		for (NuzlockeTask task : activeTasks)
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch != null && s != null && s.windowOpen && !s.violated
				&& ch.getNoNpcDeathIds() != null && ch.getNoNpcDeathIds().contains(id))
			{
				s.violated = true; // a protected NPC died (e.g. an energy siphon)
				log.debug("[RAIDCHALLENGE-DEBUG] {} VIOLATED: protected NPC {} died", task.getTaskId(), id);
				announceFailure(task, "A protected NPC was killed — this run no longer counts.");
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

			// obtain_all: mark each required item seen this window; complete when the
			// full set has been collected within the one raid.
			if (!s.violated && ch.getObtainAllItemIds() != null && !ch.getObtainAllItemIds().isEmpty())
			{
				boolean changed = false;
				for (Item it : items)
				{
					if (it != null && ch.getObtainAllItemIds().contains(it.getId()) && s.obtainedIds.add(it.getId()))
					{
						changed = true;
					}
				}
				if (changed)
				{
					s.progress = s.obtainedIds.size();
					task.setCurrentProgress(s.progress);
					log.debug("[RAIDCHALLENGE-DEBUG] {} obtain-all {}/{} (have {})",
						task.getTaskId(), s.obtainedIds.size(), ch.getObtainAllItemIds().size(), s.obtainedIds);
					if (s.obtainedIds.size() >= ch.getObtainAllItemIds().size())
					{
						trySatisfy(task, ch, s);
					}
					else if (completionCallback != null)
					{
						completionCallback.onProgressUpdated(task, s.progress);
						sendProgress(task, s.progress, s.target);
					}
				}
			}
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
		if (why == null && ch.getAttackStyleVarp() != null && ch.getAttackStyleValues() != null
			&& !ch.getAttackStyleValues().contains(client.getVarpValue(ch.getAttackStyleVarp())))
		{
			why = "attack style varp=" + client.getVarpValue(ch.getAttackStyleVarp())
				+ " not one of " + ch.getAttackStyleValues();
			reason = "Wrong attack style for this challenge.";
		}
		if (why == null && hasArenaBox(ch) && outsideArenaBox(ch))
		{
			why = "outside arena box";
			reason = "You left the area this challenge must be done in.";
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
		return true;
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
			|| (ch.getObtainAllItemIds() != null && !ch.getObtainAllItemIds().isEmpty());
	}

	private void complete(NuzlockeTask task, State s)
	{
		if (task.isCompleted())
		{
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
		s.obtainedIds.clear();
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
