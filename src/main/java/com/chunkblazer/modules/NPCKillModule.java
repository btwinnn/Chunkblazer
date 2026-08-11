package com.chunkblazer.modules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Hitsplat;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import com.chunkblazer.ChunkBlazerConfig;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TargetNpc;
import com.chunkblazer.TaskConstraints;
import com.chunkblazer.api.NpcKillReport;
import com.chunkblazer.verification.VarPlayerVerificationService;

/**
 * Module for handling NPC_KILL completion type tasks.
 * Tracks NPC kills and verifies them with the server.
 */
@Slf4j
@Singleton
public class NPCKillModule extends AbstractTaskModule
{
	// Handles NPC_KILL, COMBAT, and SLAYER task types
	private static final String COMPLETION_TYPE = "NPC_KILL";
	private static final String COMBAT_TYPE = "COMBAT";
	private static final String SLAYER_TYPE = "SLAYER";

	// Slayer task VarPlayer IDs (from RuneLite's SlayerPlugin)
	private static final int SLAYER_TASK_COUNT_VARP = 394;  // VarPlayerID.SLAYER_COUNT

	// On-task slayer kills award Slayer XP; off-task kills award none. So a Slayer
	// XP gain in the same tick window as a kill means the dead NPC was the player's
	// ASSIGNED monster — a name-agnostic "on the right task" signal.
	//
	// TIMING (Mike's goblin, session_2026-07-16 14:57:04): the XP arrives AFTER the
	// death, not during its tick. The log is unambiguous — the kill is confirmed and
	// the verdict sent at 14:57:04, and the Slayer XP / SLAYER_COUNT decrement land at
	// 14:57:05. An earlier version of this comment claimed the gain was "already
	// recorded" by end-of-tick and the gate only looked BACKWARDS, so a genuinely
	// on-task kill was refused every time. No backwards window can fix that — widening
	// it only reaches further into the past, where the evidence still isn't. So
	// on-task-gated deaths are HELD (see heldDeaths) until the XP arrives or the wait
	// expires. The window then covers skew in either direction.
	//
	// HOW LATE (Cruk's goblin task, session_2026-07-30 16:17–16:20): the delay is not
	// a fixed one tick — it VARIES kill to kill, and a 2-tick wait loses the race
	// roughly a third of the time. Of 17 verifiably on-task goblin kills (the in-game
	// counter ran 17 → 0), only 11 were credited; 6 were refused with "Not on a slayer
	// task for this monster" and the SLAYER_COUNT decrement is right there in the log
	// AFTER the verdict, one to two ticks too late. The wait is now generous, which
	// costs nothing when the evidence is prompt: the hold resolves the moment it
	// arrives, so only genuinely off-task kills ever wait the full duration.
	private static final int SLAYER_XP_WINDOW_TICKS = 6;
	// How long a gated death waits for its on-task evidence before we rule it off-task.
	private static final int ON_TASK_WAIT_TICKS = 6;
	private int previousSlayerXp = -1;
	private int lastSlayerXpGainTick = -1;

	// Second, independent on-task signal: SLAYER_COUNT (varp 394) counts DOWN by one
	// per kill of the assigned monster and moves for nothing else. Slayer XP can in
	// principle arrive from another source, so a decrement here is the more precise
	// evidence of the two — and the two do not always land on the same tick, so
	// accepting EITHER wins races that either alone would lose.
	//
	// Caveat, deliberately accepted: cancelling a task also drops the counter (to 0).
	// That would have to coincide with a gated kill of the matching monster inside the
	// window to matter, and it errs toward crediting the player.
	private int previousSlayerCount = -1;
	private int lastSlayerCountDropTick = -1;

	// ── On-task evidence, as a CONSUMABLE queue of tick numbers ───────────
	// One on-task kill produces exactly one entry, and a gated death CLAIMS one.
	// A bare "was there a signal near this death" timestamp check can't survive
	// fast kills once the window is six ticks wide: killing rats or cows in quick
	// succession puts several deaths inside one signal's window, and every one of
	// them would read that single signal as its own evidence. Two ways that pays
	// out credit nobody earned — kills landing in the 3.6s after a slayer task
	// FINISHES (the last on-task signal is still in range), and a gated task for
	// monster X being credited by the evidence from your actual assignment to Y
	// when both die close together. Claiming makes evidence one-kill-one-credit.
	//
	// Pairing: the counter decrement and the Slayer XP for the SAME kill land on
	// the same tick, so whichever arrives first creates the entry and the other
	// tops up rather than duplicating. Multi-kills are handled honestly because
	// the counter drops by the number killed, not by one.
	private final List<Integer> onTaskSignalTicks = new ArrayList<>();

	// ── Cannon detection for RESTRICTED kills ────────────────────────────
	// VarPlayer 3 is the remaining cannonball count — the same value RuneLite's own
	// CannonPlugin reads as `cballsLeft` (verified by disassembly, 2026-07-16). A
	// DECREASE means our cannon just fired. That is the only clean, first-party
	// signal available: a cannonball's hitsplat is an ordinary DAMAGE_ME splat with
	// nothing to distinguish it from a whip hit.
	//
	// Why a rule is needed at all, when the fight timer now starts at the cannon's
	// first hit: a cannon fires up to 4 balls in ONE tick, so it genuinely one-shots
	// small NPCs. Cruk's scorpion (session_2026-07-16_20-00-46) took 17 damage and
	// died in the same tick it was first hit — a real 0-tick kill that satisfied
	// "Defeat a Scorpion in the First Hit" honestly. No timing logic can refuse that;
	// only an explicit "no cannon" rule can.
	//
	// NOTE: this REPLACES the `varbit_id: 57` constraint that 35 tasks carried with
	// the message "Cannon use is prohibited for timed combat tasks". Varbit 57 is
	// BOARDGAMES_DRAUGHTS_MUSTTAKE — a draughts board game — so it read 0 forever and
	// the constraint never once blocked a cannon. Those dead constraints have been
	// removed from the task JSON; the rule now lives here and covers EVERY restricted
	// task automatically rather than the 35 that happened to be authored with it.
	private static final int CANNONBALL_VARP = 3;
	private int previousCannonballs = -1;
	private int lastCannonFiredTick = -1;

	// A ball is FIRED a tick or so before its hitsplat lands, so the shot that opens
	// a cannon fight can precede combatStartTick. Look back this far past the fight's
	// start when deciding whether a cannon was involved.
	private static final int CANNON_FIRE_LOOKBACK_TICKS = 2;

	@Inject
	private VarPlayerVerificationService varPlayerService;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ChunkBlazerConfig config;

	// ── Fights we have a stake in, keyed by NPC index ────────────────────
	// One record per NPC we have personally damaged. This REPLACED a single
	// currentTarget/damageDealtToTarget/combatStartTick triple that was only ever
	// populated from onInteractingChanged — i.e. it required the player to have
	// manually clicked the NPC. Two consequences, both reported by testers on
	// 2026-07-16 and both visible in session_2026-07-16_14-49-35:
	//
	//   1. A CANNON kill involves no interaction, so no target was ever set, every
	//      hitsplat was dropped, and the death was rejected for having 0 damage.
	//      Cannon kills produced no [NPCKILL-DEBUG] line at all — the plugin never
	//      saw them (Mike: "killed some goblins with a cannon — did not get [any]
	//      message from these"). Cannon-only kills credited nothing.
	//   2. Worse, cannon damage did not start the clock. Cruk chunked a scorpion to
	//      10% with a cannon and finished it with a whip: combat "started" at the
	//      whip hit, so a 1-tick "in the First Hit" task (that is how those are
	//      authored — see defeat_scorpion_first_hit, max allowed 1 tick) passed.
	//      Cannon-softening laundered every timed task.
	//
	// Keying by index rather than holding one target is what makes cannon safe: a
	// cannon hits several NPCs per tick, and a single slot would thrash between them
	// and mis-attribute damage. A record is created by the FIRST hitsplat WE land,
	// whatever the source (melee, cannon, thrall, poison), so the fight timer starts
	// at the first damage we are responsible for.
	private final Map<Integer, FightRecord> fights = new ConcurrentHashMap<>();

	// Records with no hit for this long are dropped so the map can't grow without
	// bound in a long session. Abandoning a fight for ~60s and coming back starts a
	// NEW record — which fails the startedFresh check below, so this can't be used
	// to launder a partly-damaged NPC.
	private static final int FIGHT_RECORD_TTL_TICKS = 100; // ~60s

	// Group encounters (raids, Nex) have long mechanic phases — shielded bosses,
	// forced movement, a support role — where a player legitimately lands no hits
	// for minutes. At the solo TTL their fight record would be evicted and the
	// eventual kill would credit nothing, since processNpcDeath needs damage > 0.
	// Applied only while a group_content task is active, so solo eviction (and the
	// laundering it prevents) is untouched.
	private static final int GROUP_FIGHT_RECORD_TTL_TICKS = 1000; // ~10 min

	/**
	 * Everything we know about one fight: our damage, when it started, whether it
	 * stayed solo, and whether the NPC was untouched when we got to it. Snapshotted
	 * out of {@link #fights} at death so a late decision (the on-task hold) can't be
	 * corrupted by index reuse.
	 */
	private static class FightRecord
	{
		final int npcIndex;
		int damage;
		int combatStartTick = -1;
		int lastHitTick = -1;
		int killingBlowAnimation;

		// True once ANOTHER player has damaged this NPC (a Hitsplat.isOthers() splat).
		// Restricted (time/equipment) kills require EXCLUSIVE damage: closes the
		// shared-spawn / friend-softens-it variant of the relog cheat. isOthers()
		// matches only the DAMAGE_OTHER* family, so our OWN cannon (renders DAMAGE_ME)
		// and our own poison/venom/burn (distinct types) never trip it. Does NOT
		// affect plain "defeat X" kills.
		boolean contested;

		// True if the NPC was at full health (or had never shown a health bar) when we
		// landed our FIRST hit on it. See wasAtFullHealth().
		boolean startedFresh = true;

		// Equip-constrained tasks whose constraint was violated at ANY point during
		// this fight. Checked on every hitsplat we land, not just the killing blow —
		// "fight in full gear, unequip for the last hit" must not pass.
		final Set<String> equipViolatedTaskIds = new HashSet<>();

		FightRecord(int npcIndex)
		{
			this.npcIndex = npcIndex;
		}
	}

	// ── Health of each NPC as of the END of the previous tick ─────────────
	// Read by wasAtFullHealth() when we land our first hit. It must be a snapshot,
	// not a live read: hitsplat and health-bar packets both arrive within a tick with
	// no guaranteed order, so reading getHealthRatio() inside onHitsplatApplied could
	// see our OWN hit already applied and call a genuinely fresh NPC pre-damaged —
	// which would reject every legitimate speed kill. Sampling at GameTick (after
	// that tick's hitsplats, before the next tick's) gives a clean "before we hit it"
	// reading. Only maintained while a restricted task is active.
	private final Map<Integer, HealthSample> healthAtPreviousTick = new ConcurrentHashMap<>();

	private static class HealthSample
	{
		final int ratio;
		final int scale;

		HealthSample(int ratio, int scale)
		{
			this.ratio = ratio;
			this.scale = scale;
		}
	}

	// ── Fight-integrity sensors for RESTRICTED kills (time/equipment) ─────
	// A relog or world hop wipes client-side combat tracking, so a fight that
	// resumes right after logging back in is only measured from its post-relog
	// tail: pre-soften the NPC, relog, finish it "in 2.4s" or "with nothing
	// equipped" (internal tester report, 2026-07-16). Restricted kills
	// therefore require the fight to have STARTED at least a grace period
	// after the session began.
	private int lastLoginTick = -1;
	private static final int FRESH_FIGHT_GRACE_TICKS = 50; // ~30s

	// The grace must also cover the task's OWN time limit. With a flat 30s, a task
	// allowing 60s could be satisfied by a fight that started 31s after login — i.e.
	// entirely inside the window the gate is supposed to protect (Mike, 2026-07-16:
	// "some timed tasks are longer than 30s"). Scaling to the limit means a restricted
	// fight always starts after any pre-login damage could still be counted as ours.
	private static int freshFightGraceTicks(TaskConstraints constraints)
	{
		int limit = (constraints != null && constraints.hasTimeLimit()) ? constraints.getTimeInTicks() : 0;
		return Math.max(FRESH_FIGHT_GRACE_TICKS, limit);
	}

	// Armed by any pre-login state (LOGIN_SCREEN / LOGGING_IN / HOPPING /
	// CONNECTION_LOST) and consumed by the next LOGGED_IN. This is how a real
	// session start is told apart from a region crossing: a REAL login goes
	// LOGIN_SCREEN → LOGGING_IN → LOADING → LOGGED_IN (Cruk's relog log,
	// session_2026-07-16), so the naive "previous state != LOADING" guard
	// classified every genuine login as a region crossing and never armed the
	// fresh-fight gate. Region crossings (LOGGED_IN → LOADING → LOGGED_IN)
	// never pass through a pre-login state, so they can't arm this flag.
	private boolean sessionStartPending = false;

	// For boss KC verification
	private int baselineKc = -1;
	private String currentBossName;

	// Pending drop-based kills: tasks waiting for a specific item to drop
	// Key: task ID, Value: pending kill info
	// Using ConcurrentHashMap for thread safety (accessed from multiple event handlers)
	private final Map<String, PendingDropKill> pendingDropKills = new ConcurrentHashMap<>();
	// How many ticks after NPC death we'll still credit a drop. Needs to cover
	// the full death animation + server loot delay (observed at ~7 ticks for
	// goblins, can be higher for larger NPCs). Set generously — false-positives
	// are blocked by ownership/distance checks anyway.
	private static final int PENDING_DROP_TIMEOUT_TICKS = 20; // ~12s
	// Per-event freshness check inside onItemSpawned. Must be >= the longest
	// realistic death-animation-to-loot delay in OSRS. 5 was too tight (goblins
	// alone hit 7); 15 covers all known cases including larger boss death anims.
	private static final int DROP_SPAWN_FRESHNESS_TICKS = 15; // ~9s

	// NPC deaths queued for end-of-tick processing.
	// Why: ActorDeath can fire BEFORE the killing blow's HitsplatApplied on same-tick
	// kills (one-shots, low-HP NPCs like Highwayman/Man). At ActorDeath time the fight
	// record may not exist yet, so the kill would be rejected for 0 damage and the time
	// constraint couldn't be evaluated. Draining this list in onGameTick ensures all
	// same-tick hitsplats have been processed before we decide.
	private final List<NPC> pendingDeaths = new ArrayList<>();

	// Deaths whose credit decision needs the on-task slayer gate, parked until their
	// Slayer XP arrives (it lands the tick AFTER the death — see SLAYER_XP_WINDOW_TICKS)
	// or ON_TASK_WAIT_TICKS expires. Only gated deaths wait; everything else is decided
	// at end-of-tick as before.
	private final List<DeathRecord> heldDeaths = new ArrayList<>();

	/**
	 * A death with its fight already resolved out of {@link #fights}, plus the tick it
	 * happened on. Carrying deathTick matters for held deaths: elapsed fight time must
	 * be measured to the DEATH, not to whenever we get around to deciding, or the wait
	 * for Slayer XP would inflate every speed kill by the length of the wait.
	 *
	 * <p>The NPC's IDENTITY is snapshotted here too, and every downstream consumer must
	 * read it from this record rather than from {@link #npc}. An {@code NPC} handle is
	 * only valid while the actor is in the scene: once it despawns at the end of its
	 * death animation the client clears it, and {@code getId()} starts returning -1
	 * with {@code getName()} null. A decision deferred past that point matches no task
	 * at all and the kill vanishes silently — Cruk's black bear, session_2026-07-31
	 * 19:38:27, logged as {@code confirmed kill: id=-1 name='null' damage=25} in the
	 * same second RuneLite's own loot tracker recorded {@code npc=2839}. The window
	 * between death and despawn is only a handful of ticks, so ANY hold can straddle
	 * it; snapshotting is what makes the hold length a free parameter.
	 */
	private static class DeathRecord
	{
		final NPC npc;
		final FightRecord fight;
		final int deathTick;
		// Captured while the NPC is still in the scene — see the class comment.
		final int npcId;
		final String npcName;
		final int npcCombatLevel;
		final WorldPoint deathLocation;

		DeathRecord(NPC npc, FightRecord fight, int deathTick)
		{
			this.npc = npc;
			this.fight = fight;
			this.deathTick = deathTick;
			this.npcId = npc.getId();
			this.npcName = npc.getName();
			this.npcCombatLevel = npc.getCombatLevel();
			this.deathLocation = npc.getWorldLocation();
		}
	}

	/**
	 * Tracks a kill that's pending verification via dropped item.
	 */
	private static class PendingDropKill
	{
		final NuzlockeTask task;
		// The whole death, snapshotted. This waits up to PENDING_DROP_TIMEOUT_TICKS
		// (~12s) for the drop, by which point the NPC has certainly despawned — so it
		// must never have held a live NPC handle in the first place.
		final DeathRecord death;
		final List<Integer> requiredItemIds;
		final int requiredQuantity;
		int collectedQuantity = 0;

		PendingDropKill(NuzlockeTask task, DeathRecord death,
						List<Integer> requiredItemIds, int requiredQuantity)
		{
			this.task = task;
			this.death = death;
			this.requiredItemIds = requiredItemIds;
			this.requiredQuantity = requiredQuantity;
		}
	}

	@Inject
	public NPCKillModule()
	{
	}

	@Override
	public String getCompletionType()
	{
		return COMPLETION_TYPE;
	}

	@Override
	public boolean canHandle(NuzlockeTask task)
	{
		// Check completion_type first
		String type = task.getCompletionType();
		if (type != null)
		{
			// Handle NPC_KILL, COMBAT, and SLAYER completion types
			if (COMPLETION_TYPE.equalsIgnoreCase(type) ||
				COMBAT_TYPE.equalsIgnoreCase(type) ||
				SLAYER_TYPE.equalsIgnoreCase(type))
			{
				return true;
			}
		}

		// Also check category field for "combat" or "slayer" tasks
		String category = task.getCategory();
		if (category != null &&
			(COMBAT_TYPE.equalsIgnoreCase(category) || SLAYER_TYPE.equalsIgnoreCase(category)))
		{
			return true;
		}

		return false;
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
		fights.clear();
		healthAtPreviousTick.clear();
		previousSlayerXp = -1;
		lastSlayerXpGainTick = -1;
		previousSlayerCount = -1;
		lastSlayerCountDropTick = -1;
		onTaskSignalTicks.clear();
		previousCannonballs = -1;
		lastCannonFiredTick = -1;
		pendingDropKills.clear();
		pendingDeaths.clear();
		heldDeaths.clear();
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		super.addActiveTask(task);
		// Seed the Slayer XP baseline on the MULTI-task registration path too
		// (registerActiveTask → addActiveTask — onTaskAssigned below is only
		// the legacy single-task path). Without this, a (re)registered task
		// starts with previousSlayerXp = -1 and the first Slayer XP event gets
		// swallowed as the baseline instead of arming the on-task gate.
		clientThread.invokeLater(() ->
		{
			if (previousSlayerXp < 0 && client.getLocalPlayer() != null)
			{
				previousSlayerXp = client.getSkillExperience(Skill.SLAYER);
			}
		});
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		super.onTaskAssigned(task);

		// Baseline Slayer XP so the first on-task kill after assignment registers a
		// gain (rather than being swallowed as the baseline). Guarded for tests/off-thread.
		if (client.getLocalPlayer() != null)
		{
			previousSlayerXp = client.getSkillExperience(Skill.SLAYER);
		}

		// For boss tasks, get baseline KC from VarPlayer (instant server-side)
		TargetNpc targetNpc = task.getTargetNpc();
		if (targetNpc != null)
		{
			String bossName = targetNpc.getName();
			if (bossName != null && varPlayerService.isBossTracked(bossName))
			{
				currentBossName = bossName;
				baselineKc = varPlayerService.getBossKillCount(bossName);
			}
			else
			{
				currentBossName = null;
				baselineKc = -1;
			}
		}
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		baselineKc = -1;
		currentBossName = null;
		pendingDropKills.clear();
		pendingDeaths.clear();
		// IMPORTANT: do NOT clear `heldDeaths` here. onTaskCleared() fires on every
		// loadActiveTasks() — chunk unlocks, task rolls, region changes, and the
		// re-register that follows each progress save — so a kill waiting for its
		// Slayer evidence would be dropped mid-hold: no credit, no failure message,
		// no log line. It was survivable at a 2-tick hold and is not at six. A held
		// death carries no task reference (see DeathRecord); processNpcDeath resolves
		// matching tasks from activeTasks when it decides, so surviving a refresh
		// credits the freshly-registered task objects correctly.
		//
		// IMPORTANT: do NOT clear `fights` here, for the same reason the Slayer XP
		// sensor below survives. onTaskCleared() fires on ROUTINE refreshes, and a
		// fight in progress is player-state, not task-state: wiping it mid-fight
		// would restart the combat timer (handing out free speed kills) and forgive
		// an equipment violation already recorded for this fight. Hard reset lives
		// in shutDown() and on session start.
		//
		// IMPORTANT: do NOT reset previousSlayerXp / lastSlayerXpGainTick here.
		// onTaskCleared() fires on ROUTINE task-list refreshes (chunk unlocks,
		// task rolls, region changes — constantly during play), and resetting
		// the Slayer XP sensor made onStatChanged swallow the NEXT Slayer XP
		// gain as a "first sighting" baseline. A single on-task kill produces
		// exactly ONE Slayer XP event, so the on-task gate refused genuinely
		// on-task kills every time (Mike's goblin, session_2026-07-15). The
		// sensor is player-state, not task-state — same lesson as the
		// ConstructionModule spawn sensor. Hard reset lives in shutDown().
	}

	@Override
	public void checkProgress()
	{
		// For NPC kills, progress is tracked via events.
		// Could add a sync check with hiscores here for verification.
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		int currentTick = client.getTickCount();

		// Deaths held for their Slayer XP. Decide as soon as the XP lands; give up
		// (and rule off-task) once the wait expires.
		if (!heldDeaths.isEmpty())
		{
			Iterator<DeathRecord> held = heldDeaths.iterator();
			while (held.hasNext())
			{
				DeathRecord death = held.next();
				// Either signal ends the wait — they don't reliably land on the same
				// tick, and waiting for a specific one is what lost Cruk's kills.
				boolean evidenceArrived = hasSignalAtOrAfter(death.deathTick);
				boolean waitExpired = currentTick - death.deathTick >= ON_TASK_WAIT_TICKS;
				if (evidenceArrived || waitExpired)
				{
					held.remove();
					processNpcDeath(death);
				}
			}
		}

		// Drain deaths queued from this tick's ActorDeath events. By now any same-tick
		// HitsplatApplied for the killing blow has been processed, so the fight record
		// is complete. Resolve the record HERE rather than at ActorDeath (the killing
		// blow may not have landed yet then) but before anything else can reuse the
		// index for a new NPC.
		if (!pendingDeaths.isEmpty())
		{
			List<NPC> toProcess = new ArrayList<>(pendingDeaths);
			pendingDeaths.clear();
			for (NPC deadNpc : toProcess)
			{
				FightRecord fight = fights.remove(deadNpc.getIndex());

				// STRICT CHECK: only credit kills where we damaged THIS NPC instance.
				// A record exists only because one of our own hitsplats created it.
				if (fight == null || fight.damage <= 0)
				{
					continue;
				}

				DeathRecord death = new DeathRecord(deadNpc, fight, currentTick);

				// The on-task gate needs Slayer XP that has not arrived yet — park it.
				if (needsOnTaskWait(death))
				{
					heldDeaths.add(death);
				}
				else
				{
					processNpcDeath(death);
				}
			}
		}

		// Retire on-task evidence nothing can still claim. A death held from tick D is
		// decided by D + ON_TASK_WAIT_TICKS at the latest and reaches back
		// SLAYER_XP_WINDOW_TICKS, so a signal stays claimable for the sum of the two.
		// Anything older is unclaimed evidence for a kill that was never gated.
		if (!onTaskSignalTicks.isEmpty())
		{
			onTaskSignalTicks.removeIf(
				t -> currentTick - t > SLAYER_XP_WINDOW_TICKS + ON_TASK_WAIT_TICKS);
		}

		// Drop fights we have not touched in a while so the map can't grow unbounded.
		// The window widens while a group_content task is active — see
		// GROUP_FIGHT_RECORD_TTL_TICKS. It's keyed on having such a task at all
		// rather than per-NPC because the record is evicted before we know which
		// task the eventual kill would credit.
		if (!fights.isEmpty())
		{
			final int ttl = hasGroupContentTask() ? GROUP_FIGHT_RECORD_TTL_TICKS : FIGHT_RECORD_TTL_TICKS;
			fights.values().removeIf(f -> currentTick - f.lastHitTick > ttl);
		}

		// Sample NPC health for the NEXT tick's first-hit freshness check. Done last,
		// so the sample reflects the end of this tick — i.e. the state of the world
		// BEFORE any hit we land next tick. Only needed while a restricted task is up.
		sampleNpcHealth();

		// Check for expired pending drop kills
		if (!pendingDropKills.isEmpty())
		{
			Iterator<Map.Entry<String, PendingDropKill>> it = pendingDropKills.entrySet().iterator();
			while (it.hasNext())
			{
				Map.Entry<String, PendingDropKill> entry = it.next();
				PendingDropKill pending = entry.getValue();
				int elapsed = currentTick - pending.death.deathTick;

				if (elapsed > PENDING_DROP_TIMEOUT_TICKS)
				{
					String dropName = pending.task.getConstraints().getDroppedItem();
					String reason = String.format("Required drop '%s' was not received (collected %d/%d)",
						dropName, pending.collectedQuantity, pending.requiredQuantity);

					sendTaskFailure(pending.task, reason);

					it.remove();
				}
			}
		}
	}

	/**
	 * Watch the cannonball counter (VarPlayer 3). A drop means our cannon fired.
	 * VarbitChanged carries varp changes too — getVarpId() identifies them.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Slayer task counter — see previousSlayerCount. A DECREASE means this kill
		// counted toward the assigned task, which is exactly what the on-task gate
		// is trying to establish.
		if (event.getVarpId() == SLAYER_TASK_COUNT_VARP)
		{
			int remaining = event.getValue();
			// First sighting is a baseline, not a kill — the varp is re-sent on login.
			if (previousSlayerCount >= 0 && remaining < previousSlayerCount)
			{
				int tick = client.getTickCount();
				// The counter is authoritative on HOW MANY died: a multi-kill drops it
				// by more than one. Top up rather than add, in case this kill's Slayer
				// XP already registered a signal on this tick.
				int killed = previousSlayerCount - remaining;
				recordOnTaskSignals(tick, killed - countSignalsAt(tick));
				lastSlayerCountDropTick = tick;
			}
			previousSlayerCount = remaining;
			return;
		}

		if (event.getVarpId() != CANNONBALL_VARP)
		{
			return;
		}

		int balls = event.getValue();
		// First sighting is a baseline, not a shot — otherwise loading the cannon or
		// logging in would look like firing. Same swallowed-baseline discipline as
		// the Slayer XP sensor.
		if (previousCannonballs >= 0 && balls < previousCannonballs)
		{
			lastCannonFiredTick = client.getTickCount();
		}
		previousCannonballs = balls;
	}

	/**
	 * Did our cannon fire during this fight? True if a ball left the barrel between
	 * the fight's start (less a little lookback for shot-to-hitsplat travel) and the
	 * kill. Another player's cannon can't trip this — it doesn't touch our varp.
	 */
	private boolean cannonFiredDuring(FightRecord fight, int deathTick)
	{
		if (lastCannonFiredTick < 0 || fight.combatStartTick < 0)
		{
			return false;
		}
		return lastCannonFiredTick >= fight.combatStartTick - CANNON_FIRE_LOOKBACK_TICKS
			&& lastCannonFiredTick <= deathTick;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Track Slayer XP gains so we can tell on-task kills (XP awarded) from
		// off-task kills (no XP) — see wasOnTaskKill().
		if (event.getSkill() != Skill.SLAYER)
		{
			return;
		}
		int xp = event.getXp();
		if (previousSlayerXp < 0)
		{
			previousSlayerXp = xp;
			return;
		}
		if (xp > previousSlayerXp)
		{
			int tick = client.getTickCount();
			// Only if the counter hasn't already recorded this kill on this tick —
			// they arrive together and describe ONE kill.
			if (countSignalsAt(tick) == 0)
			{
				recordOnTaskSignals(tick, 1);
			}
			lastSlayerXpGainTick = tick;
		}
		previousSlayerXp = xp;
	}

	private void recordOnTaskSignals(int tick, int count)
	{
		for (int i = 0; i < count; i++)
		{
			onTaskSignalTicks.add(tick);
		}
	}

	private int countSignalsAt(int tick)
	{
		int n = 0;
		for (Integer t : onTaskSignalTicks)
		{
			if (t == tick)
			{
				n++;
			}
		}
		return n;
	}

	/** True if unclaimed evidence has landed at or after this death — ends its hold. */
	private boolean hasSignalAtOrAfter(int deathTick)
	{
		for (Integer t : onTaskSignalTicks)
		{
			if (t >= deathTick)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Claim the on-task evidence belonging to a death at {@code deathTick}, nearest
	 * first, and CONSUME it so no other kill can be credited by the same signal.
	 *
	 * @return true if this kill was on task
	 */
	private boolean claimOnTaskSignal(int deathTick)
	{
		int bestIdx = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < onTaskSignalTicks.size(); i++)
		{
			int distance = Math.abs(onTaskSignalTicks.get(i) - deathTick);
			if (distance <= SLAYER_XP_WINDOW_TICKS && distance < bestDistance)
			{
				bestDistance = distance;
				bestIdx = i;
			}
		}
		if (bestIdx < 0)
		{
			return false;
		}
		onTaskSignalTicks.remove(bestIdx);
		return true;
	}

	// NOTE: there is deliberately no onInteractingChanged handler any more. Tracking
	// used to hang off it, which meant a fight only existed if the player had CLICKED
	// the NPC — the root of both cannon bugs (see the `fights` field). Damage is the
	// signal now: if one of our hitsplats landed on it, we have a stake in it, however
	// it was delivered.

	/**
	 * Record every NPC's health as of the end of this tick, for next tick's
	 * first-hit freshness check. Skipped entirely unless a restricted (time or
	 * equipment) task is active, since nothing else consults it.
	 */
	private void sampleNpcHealth()
	{
		if (!hasRestrictedTask())
		{
			if (!healthAtPreviousTick.isEmpty())
			{
				healthAtPreviousTick.clear();
			}
			return;
		}

		healthAtPreviousTick.clear();
		for (NPC npc : client.getNpcs())
		{
			if (npc != null)
			{
				healthAtPreviousTick.put(npc.getIndex(),
					new HealthSample(npc.getHealthRatio(), npc.getHealthScale()));
			}
		}
	}

	/** True if any active task is group content — see GROUP_FIGHT_RECORD_TTL_TICKS. */
	private boolean hasGroupContentTask()
	{
		for (NuzlockeTask task : activeTasks)
		{
			if (task.isGroupContent())
			{
				return true;
			}
		}
		return false;
	}

	private boolean hasRestrictedTask()
	{
		for (NuzlockeTask task : activeTasks)
		{
			TaskConstraints c = task.getConstraints();
			if (c != null && (c.hasTimeLimit() || c.hasEquipmentConstraints()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Was this NPC undamaged when we first hit it? Consults the END-OF-PREVIOUS-TICK
	 * health sample, never a live read — our own hit may already be reflected in the
	 * live value by the time HitsplatApplied fires, which would make every legitimate
	 * speed kill look pre-softened.
	 *
	 * A ratio of -1 means no health bar is being shown, i.e. nobody has touched it.
	 * No sample at all (just spawned, or the first tick a restricted task is active)
	 * is treated as fresh: this gate exists to catch a specific cheat, and it must
	 * fail OPEN on missing evidence rather than refuse honest kills.
	 */
	private boolean wasAtFullHealth(NPC npc)
	{
		HealthSample sample = healthAtPreviousTick.get(npc.getIndex());
		if (sample == null || sample.ratio < 0)
		{
			return true;
		}
		return sample.scale <= 0 || sample.ratio >= sample.scale;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
			case LOGGING_IN:
			case HOPPING:
			case CONNECTION_LOST:
				// The session ended or is restarting — the NEXT LOGGED_IN is a
				// fresh session, no matter how many LOADING states come between.
				sessionStartPending = true;
				break;
			case LOGGED_IN:
				if (sessionStartPending)
				{
					sessionStartPending = false;
					lastLoginTick = client.getTickCount();
					fights.clear();
					healthAtPreviousTick.clear();
					pendingDeaths.clear();
					heldDeaths.clear();
					// Re-baseline the cannonball sensor: the varp is re-sent on login,
					// and treating that first value as a shot would fail the next
					// restricted kill for a cannon that never fired.
					previousCannonballs = -1;
					lastCannonFiredTick = -1;
					// Same for the slayer counter: it is re-sent on login, and a
					// lower value than last session would otherwise read as a kill.
					previousSlayerCount = -1;
					lastSlayerCountDropTick = -1;
					// heldDeaths was just cleared, so nothing can claim these.
					onTaskSignalTicks.clear();
				}
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) event.getActor();
		Hitsplat hitsplat = event.getHitsplat();
		int index = npc.getIndex();

		// Another player damaging an NPC we're fighting contests it — restricted
		// (time/equipment) kills require EXCLUSIVE damage. isOthers() is the
		// DAMAGE_OTHER* family only, so our own cannon/poison never trips it. Only
		// meaningful for NPCs we have a stake in; if they hit it BEFORE we ever did,
		// the fight simply won't start fresh and the freshness gate refuses it.
		if (hitsplat.isOthers())
		{
			FightRecord fight = fights.get(index);
			if (fight != null && !fight.contested)
			{
				fight.contested = true;
				log.info("[NPCKILL-DEBUG] fight contested by another player's damage (index={})", index);
			}
			return;
		}

		if (!hitsplat.isMine())
		{
			return;
		}

		// OUR damage — however it was delivered. A cannonball, a thrall, or a whip all
		// give us a stake in this NPC and all start the clock. No prior interaction
		// required: that requirement is exactly what made cannon kills invisible.
		int tick = client.getTickCount();
		FightRecord fight = fights.get(index);
		if (fight == null)
		{
			fight = new FightRecord(index);
			// Freshness is decided ONCE, on the first hit we land, from the previous
			// tick's health sample. Later hits obviously find it damaged — by us.
			fight.startedFresh = wasAtFullHealth(npc);
			fight.combatStartTick = tick;
			fights.put(index, fight);

			if (!fight.startedFresh)
			{
				log.info("[NPCKILL-DEBUG] fight started on an already-damaged NPC (index={} name='{}')",
					index, npc.getName());
			}
		}

		fight.damage += hitsplat.getAmount();
		fight.lastHitTick = tick;

		// Validate equipment constraints on EVERY hit we land, not just the killing
		// blow — otherwise "fight in full gear, unequip for the last hit" passes the
		// kill-time check. A violation taints the task for the rest of THIS fight.
		for (NuzlockeTask task : activeTasks)
		{
			TaskConstraints c = task.getConstraints();
			if (c != null && c.hasEquipmentConstraints()
				&& !fight.equipViolatedTaskIds.contains(task.getTaskId()))
			{
				String violation = validateEquipmentForTask(task);
				if (violation != null)
				{
					fight.equipViolatedTaskIds.add(task.getTaskId());
					log.info("[NPCKILL-DEBUG] equipment violated mid-fight for '{}': {}",
						task.getTaskId(), violation);
				}
			}
		}

		// Track animation for verification
		Player player = client.getLocalPlayer();
		if (player != null)
		{
			fight.killingBlowAnimation = player.getAnimation();
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		if (pendingDropKills.isEmpty())
		{
			return;
		}

		TileItem item = event.getItem();
		WorldPoint itemLocation = event.getTile().getWorldLocation();
		int itemId = item.getId();
		int quantity = item.getQuantity();
		int ownership = item.getOwnership();
		int currentTick = client.getTickCount();

		// Check all pending drop kills
		Iterator<Map.Entry<String, PendingDropKill>> it = pendingDropKills.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, PendingDropKill> entry = it.next();
			PendingDropKill pending = entry.getValue();

			// OWNERSHIP CHECK: Must be our drop (OWNERSHIP_SELF only - no group ironmen in this mode)
			// OWNERSHIP_NONE (0) = public, OWNERSHIP_SELF (1) = ours, OWNERSHIP_OTHER (2) = someone else's
			if (ownership != TileItem.OWNERSHIP_SELF)
			{
				continue;
			}

			// TIMING CHECK: drops can lag the death event by the full death
			// animation + server loot delay. Goblins observed at ~7 ticks;
			// larger NPCs are higher. Use the constant rather than a literal.
			int ticksSinceDeath = currentTick - pending.death.deathTick;
			if (ticksSinceDeath > DROP_SPAWN_FRESHNESS_TICKS)
			{
				continue;
			}

			// LOCATION CHECK: Item must spawn at or very near the death location (within 1 tile)
			// NPC drops spawn at the NPC's location, so this should be exact or 1 tile away
			int distance = itemLocation.distanceTo(pending.death.deathLocation);
			if (distance > 1)
			{
				continue;
			}

			// Check if this is one of the required items
			if (!pending.requiredItemIds.contains(itemId))
			{
				continue;
			}

			// Found a matching drop that belongs to us!
			pending.collectedQuantity += quantity;

			// Check if we've collected enough
			if (pending.collectedQuantity >= pending.requiredQuantity)
			{
				String dropName = pending.task.getConstraints().getDroppedItem();

				// Send progress to chatbox
				String details = String.format("Killed %s and received %s drop",
					pending.death.npcName, dropName);
				sendTaskProgress(pending.task, details);

				// Credit the kill
				sendKillReport(pending.death, pending.task);
				incrementTaskProgress(pending.task, 1);

				it.remove();
			}
		}
	}

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";        // [ChunkBlazer] branding
	private static final String COLOR_RED = "ff3333";         // Task Failed
	private static final String COLOR_DARK_BLUE = "1a5276";   // Task Success (dark blue, readable)
	private static final String COLOR_DARK_GREEN = "228b22";  // Task Progress
	private static final String COLOR_BLACK = "000000";       // Task name text

	/**
	 * Send a task success message to the player's chatbox.
	 * Used when a task is fully completed.
	 */
	private void sendTaskSuccess(NuzlockeTask task, String details)
	{
		// Check config - if showChatSuccess is disabled, don't send
		if (!config.showChatSuccess())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_BLUE + ">Task Success:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col> " +
			"(" + task.getCurrentProgress() + "/" + task.getTargetQuantity() + ")";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

		if (details != null && !details.isEmpty())
		{
			String detailMessage = "  - " + details;

			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value(detailMessage)
				.build());
		}
	}

	/**
	 * Send a task progress message to the player's chatbox.
	 * Used when progress is made but task is not yet complete.
	 */
	private void sendTaskProgress(NuzlockeTask task, String details)
	{
		// Check config - if showChatProgress is disabled, don't send
		if (!config.showChatProgress())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_GREEN + ">Task Progress:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col> " +
			"(" + (task.getCurrentProgress() + 1) + "/" + task.getTargetQuantity() + ")";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

		if (details != null && !details.isEmpty())
		{
			String detailMessage = "  - " + details;

			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value(detailMessage)
				.build());
		}
	}

	/**
	 * Send a task failure message to the player's chatbox.
	 */
	private void sendTaskFailure(NuzlockeTask task, String reason)
	{
		// Check config - if showChatFailed is disabled, don't send
		if (!config.showChatFailed())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_RED + ">Task Failed:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col>";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

		String reasonMessage = "  - Reason: " + reason;

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(reasonMessage)
			.build());
	}

	/**
	 * Check ground items at a specific location for matching item IDs.
	 * Only counts items that belong to us (OWNERSHIP_SELF).
	 *
	 * @param location The world location to check
	 * @param requiredItemIds List of item IDs we're looking for
	 * @return Total quantity of matching items found that belong to us
	 */
	private int checkGroundItemsAtLocation(WorldPoint location, List<Integer> requiredItemIds)
	{
		int totalFound = 0;

		// Convert world point to local point for tile lookup
		LocalPoint localPoint = LocalPoint.fromWorld(client, location);
		if (localPoint == null)
		{
			return 0;
		}

		// Get the scene and tile
		Scene scene = client.getScene();
		Tile[][][] tiles = scene.getTiles();
		int plane = location.getPlane();

		// Convert local point to scene coordinates
		int sceneX = localPoint.getSceneX();
		int sceneY = localPoint.getSceneY();

		// Bounds check
		if (sceneX < 0 || sceneX >= tiles[plane].length ||
			sceneY < 0 || sceneY >= tiles[plane][sceneX].length)
		{
			return 0;
		}

		Tile tile = tiles[plane][sceneX][sceneY];
		if (tile == null)
		{
			return 0;
		}

		// Check ground items on this tile
		List<TileItem> groundItems = tile.getGroundItems();
		if (groundItems == null || groundItems.isEmpty())
		{
			return 0;
		}

		for (TileItem item : groundItems)
		{
			int itemId = item.getId();
			int ownership = item.getOwnership();
			int quantity = item.getQuantity();

			// Only count items that belong to us
			if (ownership != TileItem.OWNERSHIP_SELF)
			{
				continue;
			}

			// Check if this is one of the required items
			if (requiredItemIds.contains(itemId))
			{
				totalFound += quantity;
			}
		}

		return totalFound;
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) actor;

		// Defer to onGameTick. ActorDeath can fire BEFORE the killing blow's
		// HitsplatApplied on same-tick kills (one-shots, low-HP NPCs), so the fight
		// record may not exist yet. Processing on the GameTick drain ensures hitsplats
		// from this tick are counted first.
		pendingDeaths.add(npc);
	}

	/**
	 * Does any task this death could credit need the on-task slayer gate? Such deaths
	 * wait for their Slayer evidence rather than being decided immediately. Reads the
	 * snapshot, so the answer can't change just because the NPC has despawned.
	 */
	private boolean needsOnTaskWait(DeathRecord death)
	{
		for (NuzlockeTask task : findMatchingTasks(death.npcId))
		{
			if (requiresOnTaskGate(task))
			{
				return true;
			}
		}
		return false;
	}

	private void processNpcDeath(DeathRecord death)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}

		FightRecord fight = death.fight;

		// Diagnostic for collecting real runtime NPC ids in-game: the wiki id often
		// differs from / is incomplete vs what the client reports (level/spawn/hue
		// variants). If a kill doesn't credit a task you expected, grep this line
		// for the actual id and add it to the task's npc_ids. Reads the SNAPSHOT: a
		// held death's NPC handle may already be a despawned husk reporting id=-1.
		log.info("[NPCKILL-DEBUG] confirmed kill: id={} name='{}' damage={} fresh={} contested={}",
			death.npcId, death.npcName, fight.damage, fight.startedFresh, fight.contested);

		// Check all active tasks for a match
		List<NuzlockeTask> matchingTasks = mostSpecificMatches(findMatchingTasks(death.npcId));

		if (matchingTasks.isEmpty())
		{
			return;
		}

		// Resolve the on-task verdict ONCE for this death, lazily. The evidence is
		// consumed when claimed, so asking per-task would make a kill that matches
		// two gated tasks eat two kills' worth of evidence and refuse the second.
		Boolean onTaskVerdict = null;

		// Update progress for all matching tasks
		for (NuzlockeTask task : matchingTasks)
		{
			// SLAYER task check — must be killing the ASSIGNED monster, not just
			// holding any slayer assignment. On-task kills award Slayer XP; off-task
			// kills don't, so a Slayer XP gain in this kill's tick window proves the
			// dead NPC was the assigned creature. (Old check was SLAYER_COUNT>0, which
			// credited the right monster even while assigned to a different one.)
			if (requiresOnTaskGate(task))
			{
				if (onTaskVerdict == null)
				{
					onTaskVerdict = wasOnTaskKill(death.deathTick);
					// Diagnostic for the next "I killed N but only got M" report: this
					// is the only place a genuine on-task kill can be silently thrown
					// away, and the signal ticks are what decide it. Without them a
					// refusal is indistinguishable from being genuinely off-task.
					log.info("[NPCKILL-DEBUG] on-task gate: task='{}' onTask={} deathTick={} slayerXpTick={} slayerCountTick={} unclaimedSignals={}",
						task.getTaskId(), onTaskVerdict, death.deathTick,
						lastSlayerXpGainTick, lastSlayerCountDropTick, onTaskSignalTicks);
				}
				if (!onTaskVerdict)
				{
					sendTaskFailure(task, "Not on a slayer task for this monster");
					continue; // Skip this task, don't credit the kill
				}
			}

			TaskConstraints constraints = task.getConstraints();
			boolean hasDropConstraint = constraints != null && constraints.hasDroppedItemConstraint();
			boolean hasTimeConstraint = constraints != null && constraints.hasTimeLimit();
			boolean hasEquipConstraint = constraints != null && constraints.hasEquipmentConstraints();
			boolean hasVarbitConstraint = constraints != null && constraints.hasVarbitConstraints();

			// Group content (raids, Nex, group bosses) is exempt from the solo-only
			// gates below. A teammate's hitsplat sets `contested`, and the boss is
			// only at full health for whoever lands the encounter's first hit, so
			// those gates would make a group task impossible rather than hard. Task
			// JSON carrying group_content alongside a time/equipment constraint is
			// rejected at load (NuzlockeTask#getGroupContentSchemaError), so this is
			// the belt to that braces — it keeps a task that slipped through
			// completable instead of silently unwinnable.
			boolean soloGated = (hasTimeConstraint || hasEquipConstraint) && !task.isGroupContent();

			// If task ONLY has dropped_item constraint (no time/equipment/varbit constraints),
			// skip time and equipment checks - only verify the drop
			boolean dropOnlyTask = hasDropConstraint && !hasTimeConstraint && !hasEquipConstraint && !hasVarbitConstraint;

			if (!dropOnlyTask)
			{
				// No-cannon gate for RESTRICTED kills. A cannon fires up to 4 balls
				// per tick and genuinely one-shots small NPCs, so a cannon speed kill
				// is fast for real — the timer can't tell it apart from skill. See
				// the CANNONBALL_VARP comment for why this is a code rule rather
				// than the (inert) per-task varbit constraint it replaces.
				if (soloGated && cannonFiredDuring(fight, death.deathTick))
				{
					sendTaskFailure(task, "Cannon use is prohibited for restricted tasks — kill it without your cannon firing");
					continue; // Skip this task, don't credit the kill
				}

				// Full-health gate for RESTRICTED kills: the fight must start on an
				// UNTOUCHED monster. This is what stops softening it with a cannon
				// (or letting anything else chip it) and then landing the "first
				// hit" that the timer measures from — Cruk's scorpion, 2026-07-16.
				if (soloGated && !fight.startedFresh)
				{
					sendTaskFailure(task,
						"Restricted kill must start from full health — this monster was already damaged when you first hit it");
					continue; // Skip this task, don't credit the kill
				}

				// Fight-integrity gate for RESTRICTED kills: a relog/world hop wipes
				// combat tracking, so a fight resumed right after logging back in is
				// only measured from its post-relog tail. Testers pre-softened an NPC,
				// relogged, and finished it "in 2.4s" / "with nothing equipped".
				// Restricted kills must belong to a fight that STARTED at least a grace
				// period after the session began.
				//
				// This is NOT made redundant by the full-health gate above: a relog
				// also clears the client's health bars, so a pre-softened NPC reports
				// ratio -1 ("untouched") on the first hit after logging back in and
				// would sail through freshness. The two gates cover different halves
				// of the same cheat — keep both.
				int grace = freshFightGraceTicks(constraints);
				if (soloGated
					&& lastLoginTick >= 0 && fight.combatStartTick >= 0
					&& fight.combatStartTick - lastLoginTick < grace)
				{
					sendTaskFailure(task, String.format(
						"Restricted kill must be a fresh fight — wait ~%.0fs after logging in, then fight it start to finish",
						grace * 0.6));
					continue; // Skip this task, don't credit the kill
				}

				// Exclusive-damage gate for RESTRICTED kills: another player
				// helping (a duo partner, or a busy-world passer-by softening a
				// shared spawn) invalidates a speed/equipment attempt — you must
				// solo it. Recorded per-fight in onHitsplatApplied via
				// Hitsplat.isOthers(). Plain "defeat X" kills are unaffected.
				if (soloGated && fight.contested)
				{
					sendTaskFailure(task,
						"Restricted kill must be solo — another player damaged this monster");
					continue; // Skip this task, don't credit the kill
				}

				// Varbit constraint check (e.g., no cannon during timed tasks)
				String varbitViolation = validateVarbitConstraintForTask(task);
				if (varbitViolation != null)
				{
					sendTaskFailure(task, varbitViolation);
					continue; // Skip this task, don't credit the kill
				}

				// Equipment violated at ANY point during this fight (recorded
				// per hitsplat) — unequipping before the killing blow doesn't
				// launder the earlier hits.
				if (hasEquipConstraint && fight.equipViolatedTaskIds.contains(task.getTaskId()))
				{
					sendTaskFailure(task, "Equipment: restricted gear was worn during the fight");
					continue; // Skip this task, don't credit the kill
				}

				// Per-task equipment constraint check - only check THIS task's constraints
				// (removed global flag check which was incorrectly blocking tasks without constraints)
				String equipViolation = validateEquipmentForTask(task);
				if (equipViolation != null)
				{
					sendTaskFailure(task, "Equipment: " + equipViolation);
					continue; // Skip this task, don't credit the kill
				}

				// Time constraint check - validate kill was fast enough
				String timeViolation = validateTimeConstraintForTask(task, fight, death.deathTick);
				if (timeViolation != null)
				{
					sendTaskFailure(task, "Time: " + timeViolation);
					continue; // Skip this task, don't credit the kill
				}
			}

			// If task has a dropped item constraint, check for the drop
			if (hasDropConstraint)
			{
				WorldPoint deathLocation = death.deathLocation;
				List<Integer> requiredItemIds = constraints.getDroppedItemIds();
				int requiredQuantity = constraints.getDroppedItemQuantity();
				String dropName = constraints.getDroppedItem();

				// IMMEDIATELY check if the item is already on the ground at the death location
				// (ItemSpawned might have fired BEFORE ActorDeath in the same tick)
				int foundQuantity = checkGroundItemsAtLocation(deathLocation, requiredItemIds);

				if (foundQuantity >= requiredQuantity)
				{
					// Send progress to chatbox
					String details = String.format("Killed %s and received %s drop", death.npcName, dropName)
						+ killTimeSuffix(fight, death.deathTick);
					sendTaskProgress(task, details);

					// Credit the kill immediately
					sendKillReport(death, task);
					incrementTaskProgress(task, 1);
					continue;
				}

				// Item not found yet - add to pending and wait for ItemSpawned event
				PendingDropKill pending = new PendingDropKill(
					task, death, requiredItemIds, requiredQuantity);
				pending.collectedQuantity = foundQuantity; // Track what we already found
				pendingDropKills.put(task.getTaskId(), pending);

				// Don't credit the kill yet - wait for the drop to appear
				continue;
			}

			// No drop constraint - credit the kill immediately
			// Send progress to chatbox
			String details = String.format("Killed %s", death.npcName) + killTimeSuffix(fight, death.deathTick);
			sendTaskProgress(task, details);

			// Send kill report to server
			sendKillReport(death, task);

			// Increment progress on the task
			incrementTaskProgress(task, 1);
		}

		// No tracking to reset: the fight record was removed from `fights` when the
		// death was drained, and dies with this DeathRecord.
	}

	/**
	 * Find all active tasks that match this NPC id.
	 *
	 * <p>Takes the id rather than the NPC deliberately: a despawned handle reports -1,
	 * which matches nothing and makes the kill disappear without a word. Callers
	 * deciding a death must pass {@link DeathRecord#npcId}, never {@code npc.getId()}.
	 */
	private List<NuzlockeTask> findMatchingTasks(int npcId)
	{
		List<NuzlockeTask> matches = new ArrayList<>();

		for (NuzlockeTask task : activeTasks)
		{
			TargetNpc targetNpc = task.getTargetNpc();
			if (targetNpc != null && targetNpc.matchesNpcId(npcId))
			{
				matches.add(task);
			}
		}

		return matches;
	}

	/**
	 * When a kill matches several tasks, credit only the MOST SPECIFIC — the
	 * task(s) with the smallest target-NPC id set. This stops a kill of a
	 * sub-monster (e.g. an Ogress, ids {7989-7992}) from also crediting a broader
	 * superset task (e.g. Ogre, a 28-id list that contains those). Tasks with
	 * equal-size id sets (genuine duplicates — e.g. two tasks both pinned to the
	 * single id 14704) can't be told apart, so they're all kept.
	 */
	private List<NuzlockeTask> mostSpecificMatches(List<NuzlockeTask> matches)
	{
		if (matches.size() <= 1)
		{
			return matches;
		}

		int minSize = Integer.MAX_VALUE;
		for (NuzlockeTask task : matches)
		{
			int size = npcIdSetSize(task);
			if (size > 0 && size < minSize)
			{
				minSize = size;
			}
		}

		List<NuzlockeTask> specific = new ArrayList<>();
		for (NuzlockeTask task : matches)
		{
			if (npcIdSetSize(task) == minSize)
			{
				specific.add(task);
			}
		}
		return specific;
	}

	private int npcIdSetSize(NuzlockeTask task)
	{
		TargetNpc targetNpc = task.getTargetNpc();
		return (targetNpc != null && targetNpc.getNpcIds() != null) ? targetNpc.getNpcIds().size() : 0;
	}

	/**
	 * Increment progress for a specific task.
	 */
	private void incrementTaskProgress(NuzlockeTask task, int amount)
	{
		int newProgress = task.getCurrentProgress() + amount;
		task.setCurrentProgress(newProgress);

		// Notify callback about progress update (to update UI and save)
		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		if (newProgress >= task.getTargetQuantity())
		{
			onTaskCompleted(task);
		}
	}

	/**
	 * Called when a specific task is completed.
	 */
	private void onTaskCompleted(NuzlockeTask task)
	{
		if (completionCallback != null)
		{
			// Send task completion message to chatbox
			sendTaskSuccess(task, "Task complete!");

			completionCallback.onTaskCompleted(task, task.getCurrentProgress());
			activeTasks.remove(task);
		}
	}

	/**
	 * Send a kill report to the server for verification.
	 */
	private void sendKillReport(DeathRecord death, NuzlockeTask task)
	{
		Player player = client.getLocalPlayer();
		if (player == null || task == null)
		{
			return;
		}

		FightRecord fight = death.fight;
		WorldPoint location = death.deathLocation;

		NpcKillReport.NpcKillReportBuilder builder = NpcKillReport.builder()
			.playerHash(getPlayerHash())
			.taskId(task.getTaskId())
			.npcId(death.npcId)
			.npcName(death.npcName)
			.npcCombatLevel(death.npcCombatLevel)
			.worldX(location == null ? 0 : location.getX())
			.worldY(location == null ? 0 : location.getY())
			.plane(location == null ? 0 : location.getPlane())
			.regionId(getCurrentRegionId())
			.gameTick(getGameTick())
			.timestamp(System.currentTimeMillis())
			.playerCombatLevel(player.getCombatLevel())
			.playerCurrentHp(client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS))
			.killingBlowAnimationId(fight.killingBlowAnimation)
			.damageDealt(fight.damage)
			.equipmentIds(getEquipmentIds())
			.lootReceived(new ArrayList<>());

		// The ack must be applied to THIS task — the one whose taskId went out in the
		// report above — not to whatever activeTask points at. See Cruk's highwayman.
		apiClient.reportNpcKill(builder.build())
			.thenAccept(response -> handleVerificationResponse(response, task));
	}

	/**
	 * Get list of equipped item IDs for verification.
	 */
	private List<Integer> getEquipmentIds()
	{
		List<Integer> ids = new ArrayList<>();
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);

		if (equipment == null)
		{
			return ids;
		}

		Item[] items = equipment.getItems();
		for (Item item : items)
		{
			if (item != null && item.getId() > 0)
			{
				ids.add(item.getId());
			}
		}

		return ids;
	}

	/**
	 * Get the item ID at a specific equipment slot.
	 * @param slotIndex The equipment slot index (see EquipmentInventorySlot)
	 * @return The item ID at that slot, or -1 if empty or invalid
	 */
	private int getItemAtSlot(int slotIndex)
	{
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null)
		{
			return -1;
		}

		Item[] items = equipment.getItems();
		if (slotIndex < 0 || slotIndex >= items.length)
		{
			return -1;
		}

		Item item = items[slotIndex];
		if (item == null || item.getId() <= 0)
		{
			return -1;
		}

		return item.getId();
	}

	/**
	 * Get a human-readable name for an equipment slot index.
	 */
	private String getSlotName(int slotIndex)
	{
		switch (slotIndex)
		{
			case 0: return "Head";
			case 1: return "Cape";
			case 2: return "Amulet";
			case 3: return "Weapon";
			case 4: return "Body";
			case 5: return "Shield";
			case 7: return "Legs";
			case 9: return "Gloves";
			case 10: return "Boots";
			case 12: return "Ring";
			case 13: return "Ammo";
			default: return "Slot " + slotIndex;
		}
	}

	/**
	 * The 11 valid equipment slot indices (some indices are skipped in the game).
	 * HEAD=0, CAPE=1, AMULET=2, WEAPON=3, BODY=4, SHIELD=5, LEGS=7, GLOVES=9, BOOTS=10, RING=12, AMMO=13
	 */
	private static final int[] VALID_EQUIPMENT_SLOTS = {0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13};

	/**
	 * Whether this task's kill must pass the on-task slayer gate. True for
	 * SLAYER-typed tasks, and ALSO for any task whose NAME promises "on Task" —
	 * an "on Task" task mistyped as NPC_Kill used to skip the gate entirely and
	 * credit off-task kills. The data has been cleaned (no mistypes remain as
	 * of 2026-07-14), but the name check makes a future authoring slip fail
	 * SAFE (gated) instead of fail OPEN (free credit).
	 */
	private static boolean requiresOnTaskGate(NuzlockeTask task)
	{
		if (SLAYER_TYPE.equalsIgnoreCase(task.getCompletionType()))
		{
			return true;
		}
		String name = task.getName();
		return name != null && name.toLowerCase().contains("on task");
	}

	/**
	 * True if the kill that happened on deathTick was an on-task slayer kill: it must
	 * CLAIM a piece of on-task evidence recorded within SLAYER_XP_WINDOW_TICKS of the
	 * DEATH, in either direction. Off-task kills produce no Slayer XP and no
	 * SLAYER_COUNT movement, so this distinguishes "killed my assigned monster" from
	 * "killed a monster that merely matches the task while assigned to something else".
	 *
	 * Compares against deathTick, NOT the current tick: gated deaths are held until
	 * the evidence arrives, so "now" drifts away from the kill while we wait.
	 */
	private boolean wasOnTaskKill(int deathTick)
	{
		return claimOnTaskSignal(deathTick);
	}

	/**
	 * Validate equipment against a task's constraints.
	 * @return null if valid, or a string describing the violation
	 */
	private String validateEquipmentForTask(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();

		if (constraints == null)
		{
			return null; // No equipment constraints
		}

		if (!constraints.hasEquipmentConstraints())
		{
			return null; // No equipment constraints
		}

		List<Integer> equippedIds = getEquipmentIds();

		// Check no_equipment constraint (must have nothing equipped)
		if (constraints.isNoEquipment())
		{
			if (!equippedIds.isEmpty())
			{
				return "Must have no equipment - currently have " + equippedIds.size() + " items equipped";
			}
		}

		// Check equip_nothing constraint (must have ZERO equipment - nothing equipped at all)
		if (constraints.isEquipNothing())
		{
			if (!equippedIds.isEmpty())
			{
				return "Equip nothing required - currently have " + equippedIds.size() + " items equipped";
			}
		}

		// Check required_equipment_ids (these items MUST be equipped)
		List<Integer> requiredIds = constraints.getRequiredEquipmentIds();
		if (requiredIds != null && !requiredIds.isEmpty())
		{
			for (Integer requiredId : requiredIds)
			{
				if (!equippedIds.contains(requiredId))
				{
					return "Missing required equipment: item ID " + requiredId;
				}
			}
		}

		// Check allowed_equipment_ids (ONLY these items can be equipped)
		List<Integer> allowedIds = constraints.getAllowedEquipmentIds();
		if (allowedIds != null && !allowedIds.isEmpty())
		{
			for (Integer equippedId : equippedIds)
			{
				if (!allowedIds.contains(equippedId))
				{
					return "Forbidden equipment detected: item ID " + equippedId + " is not in allowed list";
				}
			}
		}

		// Check forbidden_equipment_ids (these items must NOT be equipped)
		List<Integer> forbiddenIds = constraints.getForbiddenEquipmentIds();
		if (forbiddenIds != null && !forbiddenIds.isEmpty())
		{
			for (Integer forbiddenId : forbiddenIds)
			{
				if (equippedIds.contains(forbiddenId))
				{
					return "Forbidden equipment detected: item ID " + forbiddenId;
				}
			}
		}

		// Check must_be_empty slots (specific slots that MUST be empty)
		List<Integer> mustBeEmptySlots = constraints.getMustBeEmptySlots();
		if (mustBeEmptySlots != null && !mustBeEmptySlots.isEmpty())
		{
			for (Integer slotIndex : mustBeEmptySlots)
			{
				int itemId = getItemAtSlot(slotIndex);
				if (itemId > 0)
				{
					return getSlotName(slotIndex) + " slot must be empty (has item ID " + itemId + ")";
				}
			}
		}

		// Check equippable_slots (ONLY these slots can have equipment, all others must be empty)
		List<Integer> equippableSlots = constraints.getEquippableSlots();
		if (equippableSlots != null && !equippableSlots.isEmpty())
		{
			// Check all 11 valid equipment slots
			for (int slotIndex : VALID_EQUIPMENT_SLOTS)
			{
				int itemId = getItemAtSlot(slotIndex);
				boolean slotAllowed = equippableSlots.contains(slotIndex);

				if (itemId > 0 && !slotAllowed)
				{
					// Item in a slot that's not allowed
					return getSlotName(slotIndex) + " slot must be empty - only allowed slots: " +
						equippableSlots.stream()
							.map(this::getSlotName)
							.reduce((a, b) -> a + ", " + b)
							.orElse("none");
				}
			}
		}

		return null; // All constraints passed
	}

	/**
	 * Validate that no prohibited varbits are active.
	 * For example, varbit 57 controls cannon deployment - value 0 means no cannon.
	 * @param task The task with potential varbit constraints
	 * @return null if valid (or no constraint), or a string describing the violation
	 */
	private String validateVarbitConstraintForTask(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();

		if (constraints == null || !constraints.hasVarbitConstraints())
		{
			return null; // No varbit constraints
		}

		for (TaskConstraints.VarbitConstraint vc : constraints.getProhibitedActiveVarbits())
		{
			int currentValue = client.getVarbitValue(vc.getVarbitId());
			if (currentValue != vc.getMustBeValue())
			{
				String failMsg = vc.getFailMessage();
				if (failMsg != null && !failMsg.isEmpty())
				{
					return failMsg;
				}
				return "Varbit " + vc.getVarbitId() + " must be " + vc.getMustBeValue() + " but is " + currentValue;
			}
		}

		return null; // All varbit constraints passed
	}

	/**
	 * Validate that the kill was completed within the time limit.
	 * @param task The task with potential time constraints
	 * @return null if valid (or no constraint), or a string describing the violation
	 */
	private String validateTimeConstraintForTask(NuzlockeTask task, FightRecord fight, int deathTick)
	{
		TaskConstraints constraints = task.getConstraints();

		if (constraints == null || !constraints.hasTimeLimit())
		{
			return null; // No time constraint
		}

		int allowedTicks = constraints.getTimeInTicks();

		// Check if we have a valid combat start tick
		if (fight.combatStartTick < 0)
		{
			return "Time constraint failed - no combat start recorded";
		}

		// Measured to the DEATH, not to now: a death held for its Slayer XP is decided
		// a couple of ticks late, and charging the player for that wait would fail
		// speed kills that were actually in time.
		int elapsedTicks = deathTick - fight.combatStartTick;
		double elapsedSeconds = elapsedTicks * 0.6;

		if (elapsedTicks > allowedTicks)
		{
			return String.format("Kill took %d ticks (%.1f sec), max allowed is %d ticks (%.1f sec)",
				elapsedTicks, elapsedSeconds, allowedTicks, allowedTicks * 0.6);
		}

		return null; // Constraint satisfied
	}

	/**
	 * " in X.Xs" suffix describing how long the current target took to kill, or ""
	 * if no combat start was recorded (e.g. an instant/one-shot kill whose first
	 * hitsplat and death landed before timing began). Appended to the success
	 * message so players see the kill time on success too — mirroring the time the
	 * failure message already reports. 1 game tick = 0.6 seconds.
	 */
	private String killTimeSuffix(FightRecord fight, int deathTick)
	{
		if (fight.combatStartTick < 0)
		{
			return "";
		}
		int elapsedTicks = deathTick - fight.combatStartTick;
		if (elapsedTicks < 0)
		{
			return "";
		}
		return String.format(" in %.1fs", elapsedTicks * 0.6);
	}
}
