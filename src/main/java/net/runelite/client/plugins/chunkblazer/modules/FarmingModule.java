package net.runelite.client.plugins.chunkblazer.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;
import net.runelite.client.util.Text;

/**
 * Module for FARMING completion type tasks.
 *
 * <p>The authored farming tasks are ACTIONS, not item counts: "Plant a
 * Sweetcorn Seed", "Plant an Apple Tree Sapling", "Rake a Farming Patch".
 * The verification signal is a farming ACTION landing on the same game
 * tick as a change in the task's watched item count in the INVENTORY:
 *
 * <ul>
 *   <li><b>Plant tasks</b> — the watched seed/sapling count DECREASES
 *       (the patch consumes it). Allotments consume 3 seeds for one
 *       planting, so progress is credited per action-tick, NOT per item:
 *       a -3 seed delta in one tick is +1 progress.</li>
 *   <li><b>Rake tasks</b> — the watched item (Weeds) count INCREASES.
 *       Same rule, opposite direction; each rake tick is one action.</li>
 * </ul>
 *
 * <p>The action is signalled by two markers:
 * <ul>
 *   <li>A Farming XP rise, same tick as the item change — covers raking
 *       (weeds + XP land together). A game update moved PLANTING XP to
 *       harvest/check-health, so XP never fires on a plant tick anymore
 *       (proven in both Cruk's dragonfruit log and bao's guam/onion log).</li>
 *   <li>The server's "You plant ..." game message. Planting consumes the
 *       seed at the START of the animation and sends this message at the
 *       END — 3 ticks apart in bao's onion log — so the message may NOT
 *       share a tick with the item change. An unarmed watched-item change
 *       is therefore held as a PENDING change for {@link #PENDING_WINDOW_TICKS}
 *       ticks, and a plant message credits a pending DECREASE from that
 *       window.</li>
 * </ul>
 *
 * <p>Why the pending window is message-only and decrease-only: letting the
 * XP marker consume pendings would credit "bank the seeds, water a patch
 * seconds later"; letting increases through would credit "withdraw the
 * seeds, plant a different crop". Watering, composting and harvesting
 * never touch the watched item, so they never credit. Unwatched changes
 * expire out of the window at {@link #onGameTick} — the same gate model as
 * ObtainModule's skilling path, widened just enough for the plant
 * animation gap (unlike FiremakingModule's unbounded buffer).
 */
@Slf4j
@Singleton
public class FarmingModule extends AbstractTaskModule
{
	private static final String COMPLETION_TYPE = "FARMING";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_DARK_GREEN = "228b22";
	private static final String COLOR_BLACK = "000000";

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	// Per-task watched item IDs (union of the task's required_items variants).
	private final Map<String, Set<Integer>> taskWatchedItems = new ConcurrentHashMap<>();

	// Per-task per-item INVENTORY-only count baseline. A same-tick Farming XP
	// drop plus any deviation from this baseline is one credited action; the
	// baseline then rolls to current. GameTick slides it past non-farming
	// inventory changes (banking, GE, drops) so they can't credit later.
	private final Map<String, Map<Integer, Integer>> inventorySnapshot = new ConcurrentHashMap<>();

	// Union of all watched item IDs across active tasks (fast relevance check).
	private final Set<Integer> watchedItemIds = ConcurrentHashMap.newKeySet();

	// The plant confirmation the server sends at the END of the planting
	// animation, e.g. "You plant the dragonfruit tree sapling in the fruit
	// tree patch." / "You plant 3 onion seeds in the allotment." Arrives as
	// SPAM (or GAMEMESSAGE when the player has filtered messages shown).
	private static final String PLANT_MESSAGE_PREFIX = "You plant ";

	// How many ticks a marker-less watched-item change stays creditable.
	// The seed leaves the inventory when the plant animation starts and the
	// "You plant" message arrives when it ends — observed 3 ticks apart.
	private static final int PENDING_WINDOW_TICKS = 5;

	/**
	 * A watched-item change seen WITHOUT an action marker on its tick, held
	 * until a plant message claims it or it ages out of the window.
	 */
	private static final class PendingChange
	{
		final int tick;
		final boolean decrease;
		final String details;

		PendingChange(int tick, boolean decrease, String details)
		{
			this.tick = tick;
			this.decrease = decrease;
			this.details = details;
		}
	}

	/**
	 * A live deviation of watched-item counts from the snapshot.
	 */
	private static final class DeltaResult
	{
		final boolean decrease;
		final String details;

		DeltaResult(boolean decrease, String details)
		{
			this.decrease = decrease;
			this.details = details;
		}
	}

	// Per-task pending change (taskId -> change awaiting its plant message).
	private final Map<String, PendingChange> pendingChanges = new ConcurrentHashMap<>();

	// Last observed Farming XP; -1 until the first sighting seeds the baseline.
	private int previousFarmingXp = -1;

	// True once a farming-action marker (Farming XP rise OR "You plant ..."
	// message) has fired during the CURRENT game tick. Makes the credit
	// order-independent within the tick: planting fires an inventory change
	// and its marker on the same tick, in either order. Cleared in onGameTick.
	private boolean farmingActionThisTick = false;

	@Inject
	public FarmingModule()
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
		String type = task.getCompletionType();
		String category = task.getCategory();
		return (type != null && type.equalsIgnoreCase(COMPLETION_TYPE)) ||
			(category != null && category.equalsIgnoreCase(COMPLETION_TYPE));
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
		taskWatchedItems.clear();
		inventorySnapshot.clear();
		pendingChanges.clear();
		watchedItemIds.clear();
		previousFarmingXp = -1;
		farmingActionThisTick = false;
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);

			// Union all required_items variants into one watched set. Every
			// authored farming task has a single required item at quantity 1
			// (the seed/sapling to plant, or Weeds for rake tasks), but stay
			// general: any watched variant changing counts as the action.
			Set<Integer> watched = new HashSet<>();
			List<RequiredItem> requiredItems = task.getRequiredItems();
			if (requiredItems != null)
			{
				for (RequiredItem item : requiredItems)
				{
					List<Integer> itemIds = item.getItemIds();
					if (itemIds != null)
					{
						watched.addAll(itemIds);
					}
				}
			}
			if (watched.isEmpty())
			{
				log.warn("FARMING task '{}' ({}) has no required_items — cannot track it",
					task.getName(), task.getTaskId());
				return;
			}

			taskWatchedItems.put(task.getTaskId(), watched);
			watchedItemIds.addAll(watched);

			// Seed the inventory baseline and the XP baseline on the client
			// thread (item counts require it). Seeding XP here means the FIRST
			// planting after a (re)load produces a real delta instead of being
			// swallowed as a first-sighting baseline.
			clientThread.invokeLater(() ->
			{
				Map<Integer, Integer> seeded = snapshotInventoryCounts(watched);
				inventorySnapshot.put(task.getTaskId(), seeded);
				if (previousFarmingXp < 0 && client.getLocalPlayer() != null)
				{
					previousFarmingXp = client.getSkillExperience(Skill.FARMING);
				}
				log.info("[FARMING-DEBUG] tracking '{}' ({}) watched={} snapshot={} target={} xpBaseline={}",
					task.getName(), task.getTaskId(), watched, seeded, task.getTargetQuantity(), previousFarmingXp);
			});
		}
		catch (Exception e)
		{
			log.error("FarmingModule.addActiveTask() EXCEPTION: ", e);
		}
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		// For legacy single-task support
		super.onTaskAssigned(task);
		addActiveTask(task);
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		taskWatchedItems.clear();
		inventorySnapshot.clear();
		pendingChanges.clear();
		watchedItemIds.clear();
	}

	@Override
	public void checkProgress()
	{
		// Farming progress is action-based (a seed planted is gone); it cannot
		// be re-derived from current possessions. Present for interface parity.
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.FARMING)
		{
			return;
		}

		int newXp = event.getXp();
		int prevXp = previousFarmingXp;
		previousFarmingXp = newXp;

		// First sighting (login/initial sync) records the baseline only, and
		// level/boost recalcs without an XP gain don't count as farming.
		if (prevXp < 0 || newXp <= prevXp)
		{
			return;
		}

		farmingActionThisTick = true;
		log.info("[FARMING-DEBUG] farming xp +{} ({} -> {}) tick={}", newXp - prevXp, prevXp, newXp, getGameTick());

		// XP is a SAME-TICK marker only (raking). It must not claim pending
		// changes from earlier ticks — that would credit "bank the seeds,
		// then water a patch".
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			creditOnMarker(task, false);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Tree and fruit-tree saplings grant NO Farming XP on the plant tick,
		// so the XP marker never fires for them. The server's plant
		// confirmation message is the action marker instead.
		if (event.getType() != ChatMessageType.SPAM && event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		if (activeTasks.isEmpty())
		{
			return;
		}
		if (!Text.removeTags(event.getMessage()).startsWith(PLANT_MESSAGE_PREFIX))
		{
			return;
		}

		farmingActionThisTick = true;
		log.info("[FARMING-DEBUG] plant message tick={} type={} msg='{}'",
			getGameTick(), event.getType(), Text.removeTags(event.getMessage()));
		// The plant message arrives at the END of the planting animation; the
		// seed left the inventory at its START, ticks earlier. Allow claiming
		// a pending DECREASE from the window.
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			creditOnMarker(task, true);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (activeTasks.isEmpty() || watchedItemIds.isEmpty())
		{
			return;
		}
		if (event.getContainerId() != InventoryID.INVENTORY.getId())
		{
			return;
		}
		if (!farmingActionThisTick)
		{
			// No marker (yet) this tick. Consume the change into a pending
			// entry — the plant message arrives ticks later, after the
			// animation, and will claim it from the window. Rolling the
			// snapshot here also stops onGameTick's slide from silently
			// erasing the evidence (the original guam/onion failure).
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				DeltaResult delta = detectWatchedDelta(task);
				if (delta != null)
				{
					pendingChanges.put(task.getTaskId(),
						new PendingChange(getGameTick(), delta.decrease, delta.details));
					log.info("[FARMING-DEBUG] tick={} pending change recorded for '{}': {} (awaiting marker)",
						getGameTick(), task.getTaskId(), delta.details);
				}
			}
			return;
		}
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			creditOnMarker(task, false);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// End-of-tick reconciliation: GameTick fires after this tick's
		// StatChanged / ItemContainerChanged, so all crediting has happened.
		// Slide every snapshot to "now" so non-farming inventory changes
		// (banking, GE, drops) can't be miscredited on a later XP drop.
		if (!activeTasks.isEmpty())
		{
			for (NuzlockeTask task : new HashSet<>(activeTasks))
			{
				Set<Integer> watched = taskWatchedItems.get(task.getTaskId());
				if (watched == null)
				{
					continue;
				}
				Map<Integer, Integer> next = snapshotInventoryCounts(watched);
				Map<Integer, Integer> old = inventorySnapshot.put(task.getTaskId(), next);
				// Watched changes are normally consumed into pendingChanges by
				// onItemContainerChanged before this slide runs; a change dying
				// here means a container event was missed entirely.
				if (old != null && !old.equals(next))
				{
					log.info("[FARMING-DEBUG] tick={} slide consumed uncredited change for '{}': {} -> {} (markerThisTick={})",
						getGameTick(), task.getTaskId(), old, next, farmingActionThisTick);
				}
			}
		}

		// Age pending changes out of the credit window so a banked/dropped
		// seed can't be claimed by a much later plant message.
		pendingChanges.entrySet().removeIf(e ->
		{
			boolean expired = getGameTick() - e.getValue().tick > PENDING_WINDOW_TICKS;
			if (expired)
			{
				log.info("[FARMING-DEBUG] tick={} pending change for '{}' expired unclaimed: {}",
					getGameTick(), e.getKey(), e.getValue().details);
			}
			return expired;
		});

		farmingActionThisTick = false;
	}

	/**
	 * Credit ONE farming action on an action-marker tick. First preference is
	 * a LIVE watched-item delta (item change and marker on the same tick —
	 * raking, and the marker-before-item event order). When the plant message
	 * is the marker ({@code allowPending}), a pending DECREASE recorded within
	 * the last {@link #PENDING_WINDOW_TICKS} ticks is claimed instead — the
	 * seed leaves the inventory at the start of the plant animation, the
	 * message arrives at its end. One action per marker regardless of the
	 * delta magnitude — planting an allotment consumes 3 seeds but is one
	 * "Plant" (and every authored task needs just 1 action anyway).
	 */
	private void creditOnMarker(NuzlockeTask task, boolean allowPending)
	{
		DeltaResult delta = detectWatchedDelta(task);
		if (delta != null)
		{
			// A live delta supersedes any stale pending for this task.
			pendingChanges.remove(task.getTaskId());
			log.info("[FARMING-DEBUG] tick={} crediting '{}' from live delta: {}",
				getGameTick(), task.getTaskId(), delta.details);
			applyCredit(task, delta.details);
			return;
		}

		PendingChange pending = pendingChanges.get(task.getTaskId());
		if (pending != null)
		{
			// A pending from THIS tick is the item-change-before-marker event
			// order (raking's weeds+XP, or message-on-the-plant-tick) — any
			// marker claims it, same as the original same-tick gate. OLDER
			// pendings are the plant-animation gap: only the plant message
			// claims them, and only when the item count went DOWN.
			boolean sameTick = pending.tick == getGameTick();
			boolean inWindow = getGameTick() - pending.tick <= PENDING_WINDOW_TICKS;
			if (sameTick || (allowPending && inWindow && pending.decrease))
			{
				pendingChanges.remove(task.getTaskId());
				log.info("[FARMING-DEBUG] tick={} crediting '{}' from pending change (tick={}): {}",
					getGameTick(), task.getTaskId(), pending.tick, pending.details);
				applyCredit(task, pending.details);
				return;
			}
		}

		log.info("[FARMING-DEBUG] tick={} marker fired but nothing to credit for '{}' (allowPending={}, pending={})",
			getGameTick(), task.getTaskId(), allowPending,
			pendingChanges.containsKey(task.getTaskId()));
	}

	/**
	 * Diff the watched-item counts against the task's snapshot and roll the
	 * snapshot forward. Returns null when nothing changed.
	 */
	private DeltaResult detectWatchedDelta(NuzlockeTask task)
	{
		Set<Integer> watched = taskWatchedItems.get(task.getTaskId());
		if (watched == null || watched.isEmpty())
		{
			return null;
		}
		Map<Integer, Integer> snapshot = inventorySnapshot.get(task.getTaskId());
		if (snapshot == null)
		{
			// Deferred init in addActiveTask hasn't run yet — skip rather than
			// treat the whole inventory as a fresh delta.
			return null;
		}

		boolean changed = false;
		boolean decrease = false;
		StringBuilder details = new StringBuilder();
		Map<Integer, Integer> nextSnapshot = new HashMap<>();
		for (Integer itemId : watched)
		{
			int prev = snapshot.getOrDefault(itemId, 0);
			int curr = countInInventory(itemId);
			nextSnapshot.put(itemId, curr);
			if (curr == prev)
			{
				continue;
			}
			changed = true;
			decrease |= curr < prev;
			if (details.length() > 0)
			{
				details.append(", ");
			}
			// Consumed = planted into a patch; gained = raked up (Weeds).
			details.append(curr < prev ? "Planted " : "Collected ")
				.append(Math.abs(curr - prev)).append(" x ").append(getItemName(itemId));
		}
		inventorySnapshot.put(task.getTaskId(), nextSnapshot);

		return changed ? new DeltaResult(decrease, details.toString()) : null;
	}

	/**
	 * Apply one action of progress, fire chat + callbacks, and clean up on
	 * completion.
	 */
	private void applyCredit(NuzlockeTask task, String details)
	{
		int totalRequired = Math.max(1, task.getTargetQuantity());
		int previousProgress = task.getCurrentProgress();
		int newProgress = Math.min(totalRequired, previousProgress + 1);
		if (newProgress <= previousProgress)
		{
			return;
		}
		task.setCurrentProgress(newProgress);

		sendTaskProgress(task, details, newProgress, totalRequired);
		if (completionCallback != null)
		{
			completionCallback.onProgressUpdated(task, newProgress);
		}

		if (newProgress >= totalRequired && !task.isCompleted())
		{
			task.setCompleted(true);
			sendTaskSuccess(task, details);
			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, newProgress);
			}

			// Clean up task tracking
			taskWatchedItems.remove(task.getTaskId());
			inventorySnapshot.remove(task.getTaskId());
			pendingChanges.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatchedItems();
		}
	}

	/**
	 * Snapshot the current INVENTORY-only count of every watched item. Must
	 * run on the client thread.
	 */
	private Map<Integer, Integer> snapshotInventoryCounts(Set<Integer> watched)
	{
		Map<Integer, Integer> snap = new HashMap<>();
		for (Integer itemId : watched)
		{
			snap.put(itemId, countInInventory(itemId));
		}
		return snap;
	}

	/**
	 * Count of an item in the INVENTORY only. Planting happens from the
	 * inventory; bank and equipment are irrelevant to the action signal.
	 * Uses canonicalized IDs (noted stacks can't be planted, but the
	 * canonical count keeps the baseline arithmetic consistent).
	 */
	private int countInInventory(int itemId)
	{
		ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
		if (container == null)
		{
			return 0;
		}
		int count = 0;
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0)
			{
				int canonicalId = itemManager.canonicalize(item.getId());
				if (canonicalId == itemId)
				{
					count += item.getQuantity();
				}
			}
		}
		return count;
	}

	/**
	 * Rebuild the union of watched item IDs from all remaining tasks.
	 */
	private void rebuildWatchedItems()
	{
		watchedItemIds.clear();
		for (Set<Integer> watched : taskWatchedItems.values())
		{
			watchedItemIds.addAll(watched);
		}
	}

	/**
	 * Get item name from ID.
	 * Note: This may not work on non-client threads, so we fall back to just the ID.
	 */
	private String getItemName(int itemId)
	{
		try
		{
			if (client.isClientThread())
			{
				return itemManager.getItemComposition(itemId).getName();
			}
			return "Item#" + itemId;
		}
		catch (Exception e)
		{
			return "Item#" + itemId;
		}
	}

	// ==================== CHAT MESSAGE METHODS ====================

	private void sendTaskProgress(NuzlockeTask task, String details, int current, int total)
	{
		if (!config.showChatProgress())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_GREEN + ">Task Progress:</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col> " +
			"(" + current + "/" + total + ")";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

		if (details != null && !details.isEmpty())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value("  - " + details)
				.build());
		}
	}

	private void sendTaskSuccess(NuzlockeTask task, String details)
	{
		if (!config.showChatSuccess())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_BLUE + ">Task Complete!</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col>";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

		if (details != null && !details.isEmpty())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.value("  - " + details)
				.build());
		}
	}
}
