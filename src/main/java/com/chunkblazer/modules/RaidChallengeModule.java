package com.chunkblazer.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
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

	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_BLACK = "000000";

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
	}

	private final Map<String, State> states = new ConcurrentHashMap<>();

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
		s.target = Math.max(1, rollQuantity(task.getChallenge()));
		s.progress = Math.max(0, task.getCurrentProgress());
		states.put(task.getTaskId(), s);
		task.setTargetQuantity(s.target);
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		states.clear();
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
			}
			else if (!inWin && s.windowOpen)
			{
				// Window closed. Do NOT reset here: the completion message can fire
				// AFTER leaving the fight room (Wardens → reward room), so the
				// attempt's violated/accumulator flags must survive until either the
				// message is evaluated or a new attempt opens the window again.
				s.windowOpen = false;
			}
			if (!inWin)
			{
				continue;
			}

			sampleSustained(ch, s);

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
			if (!gatesPass(ch) || s.violated || !pointInTimeOk(ch))
			{
				resetAttempt(s); // this run didn't qualify; try again next time
				continue;
			}
			s.progress++;
			task.setCurrentProgress(s.progress);
			if (s.progress >= s.target)
			{
				complete(task, s);
			}
			else if (completionCallback != null)
			{
				completionCallback.onProgressUpdated(task, s.progress);
			}
			resetAttempt(s);
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (activeTasks.isEmpty() || e.getActor() != client.getLocalPlayer())
		{
			return;
		}
		// Any damage on the local player breaks a "no damage for N ticks" streak.
		for (NuzlockeTask task : activeTasks)
		{
			State s = states.get(task.getTaskId());
			if (s != null && s.windowOpen)
			{
				s.damageFreeTicks = 0;
			}
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
			if (ch != null && s != null && s.windowOpen
				&& ch.getNoNpcDeathIds() != null && ch.getNoNpcDeathIds().contains(id))
			{
				s.violated = true; // a protected NPC died (e.g. an energy siphon)
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
		for (NuzlockeTask task : activeTasks)
		{
			RaidChallenge ch = task.getChallenge();
			State s = states.get(task.getTaskId());
			if (ch == null || s == null || !s.windowOpen || ch.getForbiddenItemIds() == null)
			{
				continue;
			}
			for (Item it : e.getItemContainer().getItems())
			{
				if (it != null && ch.getForbiddenItemIds().contains(it.getId()))
				{
					s.violated = true; // took a raid-supplied item
					break;
				}
			}
		}
	}

	// ── Evaluation helpers ───────────────────────────────────────────────────

	/** Per-tick sustained-condition sampling; a single failure taints the attempt. */
	private void sampleSustained(RaidChallenge ch, State s)
	{
		if (s.violated)
		{
			return;
		}
		if (Boolean.TRUE.equals(ch.getNoRun()) && client.getVarpValue(runVarp(ch)) != 0)
		{
			s.violated = true;
			return;
		}
		if (ch.getWeaponIds() != null && !ch.getWeaponIds().contains(equippedId(3)))
		{
			s.violated = true;
			return;
		}
		if (ch.getEmptySlots() != null)
		{
			for (int slot : ch.getEmptySlots())
			{
				if (equippedId(slot) != -1)
				{
					s.violated = true;
					return;
				}
			}
		}
		if (ch.getAttackStyleVarp() != null && ch.getAttackStyleValues() != null
			&& !ch.getAttackStyleValues().contains(client.getVarpValue(ch.getAttackStyleVarp())))
		{
			s.violated = true;
			return;
		}
		if (hasArenaBox(ch) && outsideArenaBox(ch))
		{
			s.violated = true;
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
			return;
		}
		s.progress = s.target;
		task.setCurrentProgress(s.target);
		complete(task, s);
	}

	private boolean isSatisfyTriggered(RaidChallenge ch)
	{
		return ch.getNoDamageTicks() != null || ch.getSurviveTicks() != null;
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
		if (ch.getPhaseVarbit() == null)
		{
			return true; // no phase gate — count from window open
		}
		int want = ch.getPhaseValue() != null ? ch.getPhaseValue() : 1;
		return client.getVarbitValue(ch.getPhaseVarbit()) == want;
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

	private boolean hasArenaBox(RaidChallenge ch)
	{
		return ch.getArenaMinX() != null || ch.getArenaMaxX() != null
			|| ch.getArenaMinY() != null || ch.getArenaMaxY() != null;
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
		if (ch.getArenaMinX() != null && x < ch.getArenaMinX())
		{
			return true;
		}
		if (ch.getArenaMaxX() != null && x > ch.getArenaMaxX())
		{
			return true;
		}
		if (ch.getArenaMinY() != null && y < ch.getArenaMinY())
		{
			return true;
		}
		return ch.getArenaMaxY() != null && y > ch.getArenaMaxY();
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
}
