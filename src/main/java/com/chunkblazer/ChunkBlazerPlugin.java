/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer;

import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import java.util.Collection;
import java.util.Collections;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.KeyManager;
import lombok.Setter;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.api.events.NpcDespawned;
import com.chunkblazer.api.ApiOutcome;
import com.chunkblazer.api.ChunkBlazerApiClient;
import com.chunkblazer.api.EligibilitySnapshot;
import com.chunkblazer.api.HcimDeathReport;
import com.chunkblazer.api.PlayerLoginResponse;
import com.chunkblazer.api.PlayerSyncRequest;
import com.chunkblazer.api.ServerBackoff;
import com.chunkblazer.modules.TaskModuleManager;
import com.chunkblazer.modules.VarbitCheckModule;

@Slf4j
@PluginDescriptor(
	name = "ChunkBlazer",
	description = "ChunkBlazer is a Randomized, Automated, Task rolling, Area-Restricted, Plugin / Game Mode.",
	tags = {"chunk", "chunkblazer", "nuzlocke", "challenge", "task"},
	// Roots getPluginDirectory() at .runelite/plugin-data/chunkblazer. legacy dir
	// moves the old .runelite/chunkblazer cache over on first run, so existing
	// users keep their downloaded catalog + audio.
	internalName = "chunkblazer",
	legacyDataDirectory = "chunkblazer"
)
public class ChunkBlazerPlugin extends Plugin
{
	// Shown on the Plugin Hub (runelite-plugin.properties) and sent to the server.
	// Bump it there and in build.gradle too; PluginVersionTest catches a mismatch.
	public static final String VERSION = "1.0.10";

	// --- Injected Dependencies ---
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChunkBlazerConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private Gson gson;

	@Inject
	private ChunkBlazerWorldMapOverlay worldMapOverlay;

	@Inject
	private ChunkBlazerMinimapOverlay minimapOverlay;

	@Inject
	private ChunkBlazerSceneOverlay sceneOverlay;

	@Inject
	private ChunkBlazerOrbWidget orbWidget;

	@Inject
	private TaskCompletionAnimationOverlay taskCompletionAnimationOverlay;

	@Inject
	private TaskModuleManager taskModuleManager;

	@Inject
	private TaskCompletionSoundManager soundManager;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ChunkBlazerApiClient apiClient;

	@Inject
	private com.chunkblazer.api.AssetStore assetStore;

	@Inject
	private com.chunkblazer.api.CatalogStore catalogStore;

	@Inject
	private ScheduledExecutorService executorService;

	@Inject
	private KeyManager keyManager;

	@Inject
	private net.runelite.client.input.MouseManager mouseManager;

	@Inject
	private ChunkBlazerInput inputListener;

	@Inject
	private TaskCardOverlay taskCardOverlay;

	@Inject
	private SelectedTaskOverlay selectedTaskOverlay;

	@Inject
	private TaskTargetHighlighter taskTargetHighlighter;

	@Inject
	private SelectedTaskOverlayInput selectedTaskOverlayInput;

	@Inject
	private net.runelite.client.eventbus.EventBus eventBus;

	@Inject
	private TaskCardInput taskCardInput;

	// True while the configured world-map unlock key is held. Set by
	// ChunkBlazerInput (KeyListener); read in unlockFromWorldMap so a click on
	// the world map while the key is down unlocks the hovered chunk.
	@Setter
	private boolean worldMapUnlockKeyPressed;

	// --- Plugin State ---

	@Getter
	private NuzlockeTask activeTask; // Legacy single task for backward compatibility

	@Getter
	private final List<NuzlockeTask> activeTasks = new CopyOnWriteArrayList<>(); // All active tasks for current region (thread-safe)

	// The "Global Tasks" pool: quest tasks that belong to no chunk, are free for
	// every account, and are available from the moment the plugin loads. Kept
	// OUT of activeTasks on purpose — activeTasks is the per-chunk rolled set
	// that the Active Tasks panel section renders, and ~200 quest rows would
	// swamp it. These get their own panel section and are registered with the
	// module manager separately in registerGlobalTasks().
	@Getter
	private final List<NuzlockeTask> globalTasks = new ArrayList<>();

	// loadActiveTasks() runs from ~9 places and the quest backfill is deferred
	// to the client thread, so without this a burst of reloads queues a pile of
	// overlapping backfills.
	private volatile boolean questBackfillInFlight;

	private final List<NuzlockeChunk> allChunks = new ArrayList<>();
	private final Map<Integer, NuzlockeChunk> chunksByRegionId = new HashMap<>();

	// Area/chunk label used for tasks that belong to no chunk by design. Kept in
	// one place because it appears in the Completed Tasks area filter, the chunk
	// column of the completed cards, and the area-bucket lookup.
	public static final String GLOBAL_AREA_NAME = "Global";

	// taskIDs belonging to the Global Tasks pool. Lets region-oriented code tell
	// "this task has no chunk by design" apart from "this task's chunk is
	// missing", which otherwise both surface as region -1.
	private final Set<String> globalTaskIds = new HashSet<>();

	// taskId -> task across every chunk plus the Global Tasks pool, rebuilt by
	// rebuildTaskIndex(). Don't scan allChunks instead: the panel looks up every
	// completed id on each refresh, which hard-locks the client at ~200 completed tasks.
	private final Map<String, NuzlockeTask> tasksById = new HashMap<>();
	// Region IDs listed in Free_Chunks.json: 0-cost, unlock-on-demand chunks that
	// behave exactly like charter ports (yellow-unlockable, unlock by walking in /
	// clicking) EXCEPT they have no tasks, so nothing rolls on unlock. NOTE:
	// always-accessible dungeon regions are handled separately by the coordinate
	// rule in isFreeRegion, NOT by this list.
	private final Set<Integer> freeUnlockableRegionIds = new HashSet<>();
	// Regions we've already warned have no owning chunk (e.g. manually-unlocked
	// underground/instanced spots). rollTasksForRegion runs on several threads and
	// fires repeatedly, so we log each missing region once per client run instead
	// of every attempt. Thread-safe because the roll can come from Client + EDT.
	private final Set<Integer> warnedMissingChunkRegions = ConcurrentHashMap.newKeySet();
	// Region id -> Friendly_Name for free chunks, used in the unlock message.
	private final Map<Integer, String> freeUnlockableNames = new HashMap<>();
	// Region id -> neighbor_ids for free chunks. Free chunks live only in
	// Free_Chunks.json (no task-chunk entry), so without this an unlocked free
	// chunk contributed NO neighbors — a connectivity dead end that made areas
	// behind it (e.g. the Rellekka islands) unreachable without routing around.
	private final Map<Integer, List<Integer>> freeUnlockableNeighbors = new HashMap<>();
	private final Map<String, NuzlockeTask> completedTaskCache = new HashMap<>(); // Cache completed tasks for lookup
	@Getter
	private ChunkBlazerPanel panel;
	private NavigationButton navButton;
	private volatile int lastRegionId = -1;
	// Where the player stands before a mode is locked: the Casual start chunk. lastRegionId
	// waits for a mode, so it was still -1 then and the start chunk was silently skipped.
	private volatile int preModeRegionId = -1;
	private final Random random = new Random();

	/** Handle for the periodic save-state sync; cancelled on shutDown(). */
	private ScheduledFuture<?> syncFuture;
	private static final long SYNC_INTERVAL_SECONDS = 30;

	/**
	 * Verification handshake state. Non-null when we have an outstanding nonce
	 * waiting to be typed in public chat. Cleared on success, on logout, or
	 * implicitly via 5-min server-side expiry. The chat listener checks if
	 * the message *contains* this digit nonce (no prefix required).
	 */
	private volatile String pendingVerificationNonce;
	// Set once an account passes the Full Nuzlocke eligibility pre-check and is
	// completing the chat-code handshake. Non-null means "commit a NUZLOCKE lock
	// (with this snapshot) the moment verification succeeds". Cleared on logout.
	private volatile EligibilitySnapshot pendingNuzlockeSnapshot;
	// Set when the player picks Competitive from the panel with sync OFF and accepts
	// the "enable Server Sync?" prompt. The lock can't run until we've logged in (the
	// eligibility + verify calls need the api_key), so it's deferred to loginToServer's
	// success branch. Cleared on logout.
	private volatile boolean pendingCompetitiveLock;

	// --- Plugin Lifecycle ---

	@Override
	protected void startUp()
	{
		// @Singleton survives disable/enable.
		serverBackoff.reset();
		syncRejectedKey = null;
		lastSyncSuccessMs = 0;
		registrationClosedRsn = null;

		// Resolve this plugin's OWN sandboxed data directory as a Filepath, rooted
		// under .runelite/plugin-data/chunkblazer. All disk I/O in the stores goes
		// through it, so a path can never escape the plugin's folder (RuneLite's
		// Filepath enforces that on every join). Null on the rare resolution
		// failure — the stores then run cache-less off the bundled seed / network.
		Filepath pluginDir = null;
		try
		{
			pluginDir = getPluginDirectory();
		}
		catch (IOException e)
		{
			log.warn("Could not resolve plugin data directory; running without disk cache", e);
		}

		// Load the media asset manifest: cached copy loads instantly, then an
		// async server check upgrades it. Never blocks startup; degrades to the
		// bundled seed audio when offline. See AssetStore.
		if (assetStore != null)
		{
			assetStore.init(pluginDir);
		}

		// Task catalog: load synchronously (disk cache → bundled gzipped seed)
		// BEFORE the loaders below read it. Falls back to bundled raw JSON if the
		// store has nothing.
		if (catalogStore != null)
		{
			catalogStore.init(pluginDir);
		}

		// Load task data — sourced from the catalog store (server/cache/seed),
		// with a bundled-resource fallback during the migration transition.
		loadChunkData();
		// Then ask the server for anything newer; it applies at the login screen.
		checkCatalog();

		// The starting-chunk bootstrap must NOT run here: at client launch no account is
		// known, so the write would corrupt across accounts or not land at all. It runs
		// from onRuneScapeProfileChanged().

		// Initialize task module manager
		taskModuleManager.initialize();
		taskModuleManager.setCompletionHandler(new TaskModuleManager.TaskCompletionHandler()
		{
			@Override
			public void onTaskCompleted(NuzlockeTask task, int progress)
			{
				if (!countedWorld)
				{
					return;
				}
				if (task != null && inPracticeArena())
				{
					heldInArena.add(task);
					return;
				}
				// Coalesce: flushPendingCompletions() (onGameTick) settles the whole
				// batch once. One at a time (see completeTasks()) freezes the client when
				// a boss-chunk grant settles a maxed account's stack at login. Animation
				// and sound move into the flush too, capped.
				if (task != null)
				{
					synchronized (pendingCompletions)
					{
						pendingCompletions.add(task);
					}
				}
			}

			@Override
			public void onServerVerified(NuzlockeTask task, int pointsAwarded)
			{
			}

			@Override
			public void onProgressUpdated(NuzlockeTask task, int newProgress)
			{
				if (!countedWorld || inPracticeArena())
				{
					return;
				}
				// Pass the live task's target directly. The 2-arg overload re-looks
				// up the task via findTaskById, which scans allChunks and can return
				// a different instance for taskIDs that appear in multiple chunks
				// (e.g. cook_tuna lives in both Mistrock and Fishing Guild) — that
				// stale instance has the default targetQuantity=1 and corrupts the
				// saved rolled value.
				saveTaskProgress(task.getTaskId(), newProgress, task.getTargetQuantity());
			}
		});
		taskModuleManager.startUp();

		// Schedule periodic server save-state sync. First run is delayed by one
		// interval so we don't race the login flow on plugin startup.
		syncFuture = executorService.scheduleAtFixedRate(
			this::syncToServer, SYNC_INTERVAL_SECONDS, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);

		// Create and register the sidebar panel
		panel = new ChunkBlazerPanel();
		panel.init(this);

		// Load the ChunkBlazer icon from resources (icon.png lives alongside this class).
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

		navButton = NavigationButton.builder()
			.tooltip("ChunkBlazer")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		// The in-game UI starts once the account has a mode; see setModeReady.
		// Load or assign a task if player is logged in
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			countedWorld = onCountedWorld();
			if (countedWorld)
			{
				loadOrAssignTask();
			}
			panel.updatePanel();
			// The plugin was enabled (or hot-reloaded) while already logged in,
			// so there's no LOGGED_IN transition coming to kick off the server
			// login. Queue it here; onGameTick fires it once the local player's
			// name is readable. Without this we'd never obtain an api_key.
			if (!serverLoginDone)
			{
				pendingServerLogin = true;
			}
		}

	}

	@Override
	protected void shutDown()
	{
		if (syncFuture != null)
		{
			syncFuture.cancel(false);
			syncFuture = null;
		}
		clientToolbar.removeNavigation(navButton);
		setModeReady(false);
		taskModuleManager.shutDown();
		if (soundManager != null)
		{
			soundManager.shutdown();
		}
		if (assetStore != null)
		{
			assetStore.shutdown();
		}
		if (catalogStore != null)
		{
			catalogStore.shutdown();
		}
		activeTask = null;
	}

	/**
	 * Start or stop everything the player sees and clicks in-game as one unit: overlays,
	 * minimap orbs, the task window and their input listeners. It only runs while the
	 * account has a mode, so until one is picked the side panel's mode picker is all
	 * there is. Anything new that draws or takes input in-game belongs in here.
	 */
	private void setModeReady(boolean ready)
	{
		if (ready == modeReady)
		{
			return;
		}
		modeReady = ready;
		if (ready)
		{
			overlayManager.add(worldMapOverlay);

			// Region Locker-style world-map unlock: track the hold-to-unlock key.
			keyManager.registerKeyListener(inputListener);
			eventBus.register(unlockInputs);

			// Register minimap overlay for chunk visualization and click-to-unlock
			overlayManager.add(minimapOverlay);

			// Register scene overlay that draws chunk borders + locked-chunk wash on the gameplay screen
			overlayManager.add(sceneOverlay);

			// Register animated task completion overlay
			overlayManager.add(taskCompletionAnimationOverlay);

			// Task reveal cards. The mouse listener has to be registered alongside the
			// overlay: it is what turns a click on a card into a flip (and swallows that
			// click so it doesn't also walk the player into the middle of the screen).
			overlayManager.add(taskCardOverlay);
			mouseManager.registerMouseListener(taskCardInput);

			overlayManager.add(selectedTaskOverlay);
			overlayManager.add(taskTargetHighlighter);
			eventBus.register(taskTargetHighlighter);
			mouseManager.registerMouseListener(selectedTaskOverlayInput);

			clientThread.invoke(orbWidget::startUp);
			return;
		}
		overlayManager.remove(worldMapOverlay);
		keyManager.unregisterKeyListener(inputListener);
		eventBus.unregister(unlockInputs);
		worldMapUnlockKeyPressed = false;
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(selectedTaskOverlay);
		eventBus.unregister(taskTargetHighlighter);
		mouseManager.unregisterMouseListener(selectedTaskOverlayInput);
		overlayManager.remove(taskTargetHighlighter);
		taskTargetHighlighter.reset();
		overlayManager.remove(taskCompletionAnimationOverlay);
		overlayManager.remove(taskCardOverlay);
		clientThread.invoke(orbWidget::shutDown);
		mouseManager.unregisterMouseListener(taskCardInput);
		// Drop cards WITHOUT revealing: the pending set is durable, so they are still
		// waiting on the next startup. Revealing here would flip them for free.
		taskCardOverlay.clear();
	}

	@Provides
	ChunkBlazerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChunkBlazerConfig.class);
	}

	// --- Event Handlers ---

	private volatile boolean pendingServerLogin = false;

	// Whether the world we're logged into counts for ChunkBlazer; see onCountedWorld().
	private volatile boolean countedWorld = true;

	/**
	 * Deadman, Leagues, beta, quest-speedrunning, PvP Arena, tournament and LMS worlds
	 * give boosted or throwaway progress, so nothing there counts. The first five have
	 * their own RuneLite profile type; tournament and LMS are checked by world type.
	 */
	// Tasks a module reported in LMS or the PvP tutorial, handed back once the player
	// leaves because the module stopped tracking them.
	private final Set<NuzlockeTask> heldInArena = ConcurrentHashMap.newKeySet();

	/**
	 * LMS and Pete Kayer's PvP tutorial lend 99 stats, prayers and gear, so nothing done
	 * there counts. The tutorial has no varbit of its own: its instance NPCs give it away.
	 */
	boolean inPracticeArena()
	{
		if (client.getVarbitValue(VarbitID.BR_INGAME) != 0)
		{
			return true;
		}
		// PVPTUT_TUTOR_INSTANCE (16577) to PVPTUT_SA_NPC_PARTNER_6 (16630), newer than our API.
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc.getId() >= 16577 && npc.getId() <= 16630)
			{
				return true;
			}
		}
		return false;
	}

	boolean onCountedWorld()
	{
		java.util.Set<net.runelite.api.WorldType> types = client.getWorldType();
		return net.runelite.client.config.RuneScapeProfileType.getCurrent(client)
			== net.runelite.client.config.RuneScapeProfileType.STANDARD
			&& !types.contains(net.runelite.api.WorldType.TOURNAMENT_WORLD)
			&& !types.contains(net.runelite.api.WorldType.LAST_MAN_STANDING);
	}

	// The RS profile becomes available (account HASH known) before the display name
	// loads (the LOGGED_IN race), so name-gated bootstrap steps — the legacy→RSProfile
	// migration and any per-account heal — skip when onRuneScapeProfileChanged fires.
	// This defers them to the first onGameTick where getName() is non-null. Reset on logout.
	private volatile boolean pendingProfileBootstrap = false;

	/**
	 * Set to true once a server login response comes back successfully, reset on
	 * LOGIN_SCREEN. Guards against world hops / fairy rings / brief connection
	 * blips that re-fire GameState.LOGGED_IN — we don't want to re-call
	 * /api/player/login for the same session, both to be a polite client and to
	 * stay well under the server's per-IP rate cap on /login.
	 */
	private volatile boolean serverLoginDone = false;

	private final ServerBackoff serverBackoff = new ServerBackoff();

	// Key the server refused on sync (401). Sync halts until the key changes.
	private volatile String syncRejectedKey;

	// When this account's last sync was accepted (0 = not this session). The reset link
	// is only safe while this is recent: the server copy is what a reset restores from.
	private volatile long lastSyncSuccessMs;
	private static final long RESET_SYNC_FRESHNESS_MS = 2 * 60_000L;

	// RSN refused by closed registration. Skipped by the periodic login retry.
	private volatile String registrationClosedRsn;

	// A recovery key the player pasted, being validated on the CURRENT login attempt.
	// It is only a candidate: it is never written over the stored key until the server
	// confirms it authenticates THIS account (login response authed_by_key), so a wrong
	// or unknown key can never brick a working account. Cleared once the login resolves.
	private String pendingPastedKey;

	// A first-login sync key that couldn't be saved yet, and the hashed account id it was
	// issued to (see persistApiKey / retryUnsavedApiKey).
	private volatile String unsavedApiKey;
	private volatile String unsavedApiKeyAccount;

	// Last RS profile key onProfileChanged acted on. A world hop (and some no-op
	// ProfilePanel actions) re-activate the config profile without changing the
	// account, re-firing ProfileChanged; acting again revokes sync and reseeds off
	// config that reads null mid-switch. Dedupe on the account key so only a real
	// switch does the work.
	private volatile String lastProfileSwitchKey;

	// Last confirmed mode-lock verdict for the current account. getLocalPlayer() is
	// briefly null right after a hop's LOGGED_IN, so isModeLocked() can't read the
	// RSN and would report "unlocked" — flashing the mode picker every hop. During
	// that window we trust this cached verdict instead. Reset on real logout.
	private volatile boolean modeLockConfirmed;

	// Whether the in-game UI is running for an account with a mode; see setModeReady.
	private volatile boolean modeReady;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.HOPPING || event.getGameState() == GameState.LOGIN_SCREEN)
		{
			bossEngagement.reset();
		}
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			countedWorld = onCountedWorld();
			// Small delay to ensure client is ready
			clientThread.invokeLater(() ->
			{
				if (countedWorld)
				{
					loadOrAssignTask();
				}
				else
				{
					// Nothing done here may count: drop the loaded tasks so no module credits them.
					activeTasks.clear();
					taskModuleManager.clearTask();
					addPluginChatMessage("ChunkBlazer is paused on special worlds like Deadman and Leagues. Hop to a normal world to keep blazing.");
				}
				panel.updatePanel();
			});
			// Defer the server login until onGameTick sees a non-null player name;
			// at LOGGED_IN time getLocalPlayer().getName() can still be null.
			// Skip if we've already logged in this session — world hops and
			// brief reconnects re-fire LOGGED_IN but the same api_key is fine.
			if (!serverLoginDone)
			{
				pendingServerLogin = true;
			}
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			hcimDeathWatcher.reset();
			syncChoiceNoticeShown = false;
			modeChoiceNoticeShown = false;
			// Last-chance sync before localPlayer becomes inaccessible: build the request
			// here, then fire-and-forget the HTTP call.
			//
			// Skipped if the server's record was never merged this session (local holds
			// only the bootstrap) or rsProfileKey is already null
			// (AccountHashChanged/WorldChanged are unordered vs this, and
			// buildSyncRequest would read EMPTY). This sync is destructive: a skip loses
			// at most one session, re-synced next login, but erasing the server copy is
			// unrecoverable.
			PlayerSyncRequest finalSync = (serverStateMerged && countedWorld && isAccountStateAvailable())
				? buildSyncRequest() : null;
			if (finalSync != null && config.apiEnabled())
			{
				apiClient.syncPlayerState(finalSync)
					.thenAccept(resp ->
					{
						recordServerOutcome(resp == null ? ApiOutcome.TRANSIENT : resp.getOutcome(),
							resp == null ? 0 : resp.getRetryAfterMs());
					});
			}
			else if (!serverStateMerged)
			{
				// Expected on any session that logs out before a server merge (e.g. the
				// cold login screen), so this is normal bookkeeping, not a fault: debug.
			}
			else
			{
				log.warn("Skipped logout sync: RS profile already cleared");
			}
			activeTask = null;
			lastRegionId = -1;
			pendingServerLogin = false;
			pendingProfileBootstrap = false;
			// Same reasoning for the cached Progression baseline: it is parsed
			// for one account, and the next one to log in must re-read (and, if
			// the stored baseline isn't theirs, capture their own).
			cachedProgressionBaseline = null;
			cachedBaselineOwner = null;
			lastSkillSample = null;
			stableSkillSamples = 0;
			// Every session must re-merge before it is allowed to sync.
			serverStateMerged = false;
			serverRollRestoreResolved = false;
			// Real logout — reset the dedupe flag so the NEXT LOGGED_IN
			// (which is a fresh game session) re-runs loginToServer.
			serverLoginDone = false;
			syncRejectedKey = null;
			// Re-determine mode-lock from scratch next login: the cached verdict is
			// per-account, and a different account may log in next.
			modeLockConfirmed = false;
			setModeReady(false);
			// And clear any pending verification — the nonce is tied to the
			// pre-logout session. Also drop any in-flight Nuzlocke lock.
			pendingVerificationNonce = null;
			pendingNuzlockeSnapshot = null;
			pendingCompetitiveLock = false;
			panel.hideVerificationPrompt();
			// After the logout sync above has been built from the old task data.
			reloadCatalogIfNewer();
			// And check the server again, so a fix deployed since launch arrives with
			// this relog instead of waiting for a RuneLite restart.
			checkCatalog();
			// Refresh the side panel into its logged-out state (gates the
			// gameplay sections behind being in-game).
			panel.updatePanel();
		}
	}

	/**
	 * A RuneLite config PROFILE switch swaps all local state with no login or GameState
	 * change. {@code serverStateMerged} still holds from the previous profile, so the
	 * 30-second sync would push the new state over the server record. A switch therefore
	 * revokes the merge and re-runs the server login; sync is blocked until that
	 * completes.
	 */
	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// A world hop (and some no-op ProfilePanel actions) re-fire this event without
		// changing the account. Reacting revokes sync authority and reseeds the start
		// chunk off config that reads null mid-switch. Only act on a real account change;
		// a null key is handled by the LOGIN_SCREEN path.
		String key = configManager != null ? configManager.getRSProfileKey() : null;
		if (key == null || key.equals(lastProfileSwitchKey))
		{
			return;
		}
		lastProfileSwitchKey = key;

		revokeSyncAuthorityForProfileSwitch();

		loadActiveTasks();
		if (panel != null)
		{
			panel.updatePanel();
		}
	}

	/**
	 * Fires whenever the RS profile key changes, including null to real at login and real
	 * to null at logout. Triggers the starting-chunk bootstrap, since {@code startUp()}
	 * has no account, and rebuilds the panel for the new account. Guarded because
	 * bootstrapping on the logout edge would be an account-less write.
	 */
	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		if (!isAccountStateAvailable())
		{
			pendingProfileBootstrap = false;
			loadActiveTasks(); // clears, since state is unavailable
			if (panel != null)
			{
				panel.updatePanel();
			}
			return;
		}

		// The display name may not be loaded yet (LOGGED_IN race). Arm a deferred re-run on
		// onGameTick so the name-gated steps (migration, per-account heals) run once it is,
		// even if the immediate bootstrap below runs against a null name.
		pendingProfileBootstrap = true;

		// One-time move of any legacy profile-global progress into this account's RSProfile,
		// BEFORE the bootstrap, so ensureStartingChunkUnlocked sees the migrated unlocks.
		// loadActiveTasks runs that bootstrap, once the account has a mode.
		migrateLegacyGlobalStateToRSProfile();
		loadActiveTasks();
		if (panel != null)
		{
			panel.updatePanel();
		}
	}

	/**
	 * The safety-critical half of a profile switch, kept separate from the
	 * reload so it cannot be broken by a failure in it — and so it is testable
	 * without standing up the whole plugin.
	 */
	void revokeSyncAuthorityForProfileSwitch()
	{

		serverStateMerged = false;
		serverRollRestoreResolved = false;
		serverLoginDone = false;
		cachedProgressionBaseline = null;
		cachedBaselineOwner = null;

		// Re-login so hydration merges the server record into THIS profile.
		// Consumed on the next GameTick once the RSN is readable; harmless when
		// logged out, since that tick never comes until there is a player.
		pendingServerLogin = true;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}

		// Settle any completions detected since the last tick in ONE batch. A
		// boss-chunk grant can complete a whole stack of already-satisfied tasks at
		// once, and the per-task settle-up path freezes the client at that volume
		// (see completeTasks()). Coalescing keeps it to a single settle-up per tick.
		flushPendingCompletions();

		if (!heldInArena.isEmpty() && !inPracticeArena())
		{
			for (NuzlockeTask task : heldInArena)
			{
				if (!task.isCompleted())
				{
					taskModuleManager.retrack(task);
				}
			}
			heldInArena.clear();
		}
		if (unsavedApiKey != null)
		{
			retryUnsavedApiKey(hashedAccountId());
		}

		// Pick up a mode being locked (panel, server-confirmed Competitive, adopted at
		// login): start the in-game UI and load the tasks. A pending bootstrap below
		// reloads the tasks anyway. An account's mode only goes from unset to set, so
		// once it's set this stops checking until logout, when another account may follow.
		if (!modeReady && !isModeLocked())
		{
			grandfatherCasual();
		}
		if (!modeReady && isModeLocked())
		{
			setModeReady(true);
			if (!pendingProfileBootstrap)
			{
				loadActiveTasks();
				if (panel != null)
				{
					panel.updatePanel();
				}
			}
		}

		if (pendingServerLogin && player.getName() != null && countedWorld)
		{
			pendingServerLogin = false;
			loginToServer();
		}

		// Name-gated bootstrap: onRuneScapeProfileChanged fires when the account HASH is
		// known but getName() can still be null, so the legacy→RSProfile migration and any
		// per-account heal skip there. Re-run them the moment the name is loaded. Idempotent:
		// the migration no-ops once its destination exists, and heals are run-once flagged.
		if (pendingProfileBootstrap && player.getName() != null)
		{
			pendingProfileBootstrap = false;
			migrateLegacyGlobalStateToRSProfile();
			loadActiveTasks();
			if (panel != null)
			{
				panel.updatePanel();
			}
		}

		// Starting tasks wait on the sync choice (canRollMissing); say so once per session.
		if (!syncChoiceNoticeShown && !config.apiEnabled() && !canRollMissing())
		{
			syncChoiceNoticeShown = true;
			addPluginChatMessage("Choose Enable Sync or Play offline in the ChunkBlazer panel to get your starting tasks.");
		}
		// Then the mode, once per login, until one is locked. canRollMissing also means a
		// server lock (new device) has already been adopted, so this can't misfire.
		else if (!modeChoiceNoticeShown && player.getName() != null && canRollMissing() && !isModeLocked())
		{
			modeChoiceNoticeShown = true;
			addPluginChatMessage(SELECT_MODE_MESSAGE);
		}

		// Walking into chunks (free unlocks, unlock popups) waits for a mode too.
		if (!modeReady)
		{
			if (!client.isInInstancedRegion())
			{
				preModeRegionId = player.getWorldLocation().getRegionID();
			}
			return;
		}

		WorldPoint wp = player.getWorldLocation();
		int currentRegionId = wp.getRegionID();

		// In an instance getWorldLocation() returns a TEMPLATE region that isn't a chunk,
		// which would show "Unknown (13454)" and could misfire the walk-in unlock
		// prompts. Freeze on the last overworld region until the player steps out.
		if (!client.isInInstancedRegion() && currentRegionId != lastRegionId)
		{
			lastRegionId = currentRegionId;

			// Charter ports AND free-list chunks unlock the instant you set foot in
			// them: both are 0-cost and aren't adjacent neighbours, so the normal
			// neighbour-based auto-unlock/popup paths below don't cover them.
			// unlockRegionFree is 0-cost; it rolls tasks for charter ports but skips
			// rolling for free-list chunks (they have none).
			if ((isCharterRegion(currentRegionId) || isFreeUnlockableRegion(currentRegionId))
				&& !isRegionUnlocked(currentRegionId))
			{
				unlockRegionFree(currentRegionId);
				loadActiveTasks();
				panel.updatePanel();
			}
			// Deliberate-unlock only: entering an unlockable neighbour offers a
			// prompt; nothing auto-unlocks (the old free-exploration mode is gone).
			else if (config.showUnlockPopup())
			{
				// Show unlock popup if entering an unlockable neighbor region
				showUnlockPopupIfNeeded(currentRegionId);
			}

			panel.updateRegionDisplay();
		}
	}

	private void addMinimapUnlockEntry(MenuEntryAdded event)
	{
		// Add unlock option to minimap when hovering over unlockable region
		if (!config.showMinimapChunks())
		{
			return;
		}

		// Check if this is a minimap-related menu
		int componentId = event.getActionParam1();
		if (componentId != InterfaceID.Toplevel.MINIMAP &&
			componentId != InterfaceID.ToplevelOsrsStretch.MINIMAP &&
			componentId != InterfaceID.ToplevelPreEoc.MINIMAP)
		{
			return;
		}

		// Get the hovered region from the minimap overlay
		int hoveredRegion = minimapOverlay.getHoveredRegionId();
		if (hoveredRegion <= 0)
		{
			return;
		}

		// Check if it's unlockable (neighbour or charter port)
		if (!isUnlockableRegion(hoveredRegion))
		{
			return;
		}

		// Add unlock menu entry
		String regionName = getRegionName(hoveredRegion);
		int cost = getRegionUnlockCost(hoveredRegion);

		client.createMenuEntry(-1)
			.setOption("Unlock chunk")
			.setTarget("<col=ffff00>" + regionName + "</col> (" + cost + " pts)")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> showChatboxUnlockPopup(hoveredRegion));
	}

	/**
	 * World-map unlock (Region Locker model): click a neighbour chunk with the map-unlock
	 * key held. A keybind because the world map doesn't reliably surface custom menu
	 * entries.
	 */
	private void unlockFromWorldMap(MenuOptionClicked event)
	{
		if (!worldMapUnlockKeyPressed)
		{
			return;
		}
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null)
		{
			return; // world map not open
		}
		int regionId = worldMapOverlay.getHoveredRegionId();
		if (regionId <= 0 || !isUnlockableRegion(regionId))
		{
			return; // not hovering an unlockable chunk
		}
		// Swallow the default world-map click so it doesn't also pan/select.
		event.consume();
		showChatboxUnlockPopup(regionId);
	}

	private void releaseUnlockKey(FocusChanged event)
	{
		if (!event.isFocused())
		{
			// Don't leave the key "stuck on" if focus is lost mid-hold.
			worldMapUnlockKeyPressed = false;
		}
	}

	/**
	 * An account with progress from before a mode was required, that can't qualify for
	 * Competitive anyway, is set to Casual so it carries on instead of stopping until a
	 * mode is picked. One that could still go Competitive keeps the choice. Waits for the
	 * server's state when syncing, since that may carry the account's mode.
	 */
	private void grandfatherCasual()
	{
		if (!isAccountStateAvailable() || (config.apiEnabled() && !serverStateMerged)
			|| pendingCompetitiveLock || pendingNuzlockeSnapshot != null || pendingVerificationNonce != null
			|| acStr("completedTasks", "").isEmpty())
		{
			return;
		}
		String rsn = getPlayerName();
		EligibilitySnapshot snapshot = buildEligibilitySnapshot();
		if (rsn == null || snapshot == null || couldStillGoCompetitive(snapshot))
		{
			return;
		}
		// Only the lock, not commitModeLock: picking Casual there also unlocks the chunk the
		// player stands in as a free start, which an account with progress mustn't get. The
		// next sync records the mode on the server.
		setAccountState("accountModeHash", hashRsn(rsn) + ":" + GameMode.CASUAL.name());
		setAccountState("gameMode", GameMode.CASUAL);
		addPluginChatMessage("ChunkBlazer now needs a game mode. This account has progress, so it's been set to Casual.");
	}

	private static final Set<String> COMBAT_SKILLS = new HashSet<>(Arrays.asList(
		"ATTACK", "STRENGTH", "DEFENCE", "RANGED", "MAGIC", "PRAYER", "HITPOINTS"));

	/**
	 * A loose mirror of the server's Competitive rule (nuzlocke.go): could this account
	 * still pass it? An ironman type, combat 9 or lower, Hitpoints 15 or lower, other
	 * skills 3 or lower, no quests past Tutorial Island. Unknown data leaves the choice.
	 */
	static boolean couldStillGoCompetitive(EligibilitySnapshot s)
	{
		Integer type = s.getAccountType();
		if ((type != null && (type < 1 || type > 6)) || s.getCombatLevel() > 9 || s.getQuestPoints() > 1)
		{
			return false;
		}
		Map<String, Integer> skills = s.getSkills();
		if (skills == null)
		{
			return true;
		}
		Integer hp = skills.get("HITPOINTS");
		if (hp != null && hp > 15)
		{
			return false;
		}
		for (Map.Entry<String, Integer> e : skills.entrySet())
		{
			if (!COMBAT_SKILLS.contains(e.getKey()) && e.getValue() != null && e.getValue() > 3)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The minimap and world-map unlock inputs. Registered with the rest of the game UI
	 * (see setModeReady), so they only exist once the account has picked a mode.
	 */
	private class UnlockInputs
	{
		@Subscribe
		public void onMenuEntryAdded(MenuEntryAdded event)
		{
			addMinimapUnlockEntry(event);
		}

		@Subscribe
		public void onMenuOptionClicked(MenuOptionClicked event)
		{
			unlockFromWorldMap(event);
		}

		@Subscribe
		public void onFocusChanged(FocusChanged event)
		{
			releaseUnlockKey(event);
		}
	}

	private final UnlockInputs unlockInputs = new UnlockInputs();

	private void showChatboxUnlockPopup(int regionId)
	{
		// Null-safe: charter ports and free-list chunks are unlockable but live in
		// freeUnlockableNames, not chunksByRegionId, so a direct .get().getName()
		// would NPE on them. getRegionName() handles every case.
		NuzlockeChunk popupChunk = chunksByRegionId.get(regionId);
		String chunkName = popupChunk != null ? popupChunk.getName() : getRegionName(regionId);
		int cost = getRegionUnlockCost(regionId);
		int currentPoints = getTotalPoints();
		int currentTokens = getBossTokens();
		clientThread.invokeLater(() ->
		{
			// Boss chunks cost a Boss Token, not points — show a token prompt and route
			// to the token unlock path instead of the points one below.
			if (isBossRegion(regionId))
			{
				if (currentTokens <= 0)
				{
					// Not enough tokens - show info message
					chatboxPanelManager.openTextMenuInput(
									"You need a Boss Token to unlock " + chunkName + ".")
							.option("OK", () -> { })
							.build();
				}
				else
				{
					// Can afford - show unlock confirmation
					chatboxPanelManager.openTextMenuInput(
									"Unlock " + chunkName + " for 1 Boss Token? " +
											"(Remaining: " + (currentTokens - 1) + ")")
							.option("Yes, unlock!", () -> unlockRegion(regionId))
							.option("No, not yet", () -> { })
							.build();
				}
			}
			else if (currentPoints < cost)
			{
				// Not enough points - show info message
				chatboxPanelManager.openTextMenuInput(
								"You need " + (cost - currentPoints) + " more points to unlock " + chunkName + ".")
						.option("OK", () -> { })
						.build();
			}
			else
			{
				// Can afford - show unlock confirmation
				chatboxPanelManager.openTextMenuInput(
								"Unlock " + chunkName + " for " + cost + " points? " +
										"(Remaining: " + (currentPoints - cost) + ")")
						.option("Yes, unlock!", () -> unlockRegion(regionId))
						.option("No, not yet", () -> { })
						.build();
			}
		});
	}

	/**
	 * Show an in-game popup to unlock a region if the player enters an unlockable neighbor.
	 */
	private void showUnlockPopupIfNeeded(int regionId)
	{
		// Skip if already unlocked
		if (isRegionUnlocked(regionId))
		{
			return;
		}

		// Check if this region has tasks defined
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk == null)
		{
			return; // No tasks for this region
		}

		// Check if it's a neighbor of any unlocked region
		Set<Integer> neighbors = getNeighborRegionIds();
		if (!neighbors.contains(regionId))
		{
			return; // Not a neighbor
		}

		showChatboxUnlockPopup(regionId);
	}

	/**
	 * Unlock a region without spending points (for free/exploration mode).
	 */
	public void unlockRegionFree(int regionId)
	{
		// Snapshot before mutating so we know whether this is the first-time
		// unlock (only the first unlock should fire the jingle).
		boolean wasAlreadyUnlocked = getUnlockedRegionIds().contains(String.valueOf(regionId));

		// Add to unlocked list without deducting points
		String unlocked = acStr("unlockedChunks", "12850");
		if (unlocked == null || unlocked.isEmpty())
		{
			unlocked = String.valueOf(regionId);
		}
		else if (!wasAlreadyUnlocked)
		{
			unlocked = unlocked + "," + regionId;
		}
		setAccountState("unlockedChunks", unlocked);
		// Persist immediately — see persistUnlockNow(). An unlock that only
		// lives in memory is lost if the client doesn't shut down cleanly.
		persistUnlockNow();


		if (!wasAlreadyUnlocked)
		{
			playRegionUnlockJingle(regionId);
			// Confirm in chat so the player doesn't have to watch the side panel.
			addPluginChatMessage("Unlocked " + getRegionName(regionId) + ".");
		}

		// Roll tasks for this region if it has a chunk defined. Free-list chunks
		// (Free_Chunks.json) never roll — they have no tasks by design.
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (!isFreeUnlockableRegion(regionId) && chunk != null && chunk.getTasks() != null && !chunk.getTasks().isEmpty())
		{
			Set<String> existingRolled = getRolledTasksForRegion(regionId);
			if (existingRolled.isEmpty())
			{
				Set<String> newTasks = rollTasksForRegion(regionId);
			}
		}

	}

	/**
	 * Ensure the default start chunk ({@link #DEFAULT_START_REGION}) is unlocked.
	 * Idempotent. Also guarantees {@code unlockedChunks} EXISTS on disk, not merely that
	 * it reads as containing the start region; see {@link #isUnlockedChunksPersisted()}.
	 */
	public void ensureStartingChunkUnlocked()
	{
		Set<String> currentlyUnlocked = getUnlockedRegionIds();
		StringBuilder newUnlocked = new StringBuilder();
		boolean needsUpdate = false;

		// Start with current unlocked regions
		String existing = acStr("unlockedChunks", "12850");
		if (existing != null && !existing.isEmpty())
		{
			newUnlocked.append(existing);
		}

		// Auto-unlock the free starting chunk only. Neighbouring chunks are
		// visible on the world map but must be unlocked with points.
		if (!currentlyUnlocked.contains(String.valueOf(DEFAULT_START_REGION)))
		{
			if (newUnlocked.length() > 0)
			{
				newUnlocked.append(",");
			}
			newUnlocked.append(DEFAULT_START_REGION);
			needsUpdate = true;
		}
		else if (!isUnlockedChunksPersisted())
		{
			// The start region reads as unlocked ONLY because the config
			// interface default supplied it. Nothing is on disk, so external
			// readers see an empty unlock set. Force the write.
			needsUpdate = true;
		}

		if (needsUpdate)
		{
			setAccountState("unlockedChunks", newUnlocked.toString());
		}

		// Pre-roll the starting chunk; others roll lazily on unlock.
		//
		// Gated like the reroll in loadActiveTasks: with the server restore pending, an
		// empty roll usually means it hasn't landed, and rolling now races it and poisons
		// the reveal-card set. loadActiveTasks rolls any genuine gap once it resolves.
		// With sync off this waits for Play offline (canRollMissing).
		if (canRollMissing() && getRolledTasksForRegion(DEFAULT_START_REGION).isEmpty())
		{
			NuzlockeChunk chunk = chunksByRegionId.get(DEFAULT_START_REGION);
			if (chunk != null && chunk.getTasks() != null && !chunk.getTasks().isEmpty())
			{
				rollTasksForRegion(DEFAULT_START_REGION);
			}
			else
			{
				log.warn("Start chunk {} has no tasks", DEFAULT_START_REGION);
			}
		}
	}


	// --- Data Loading ---

	// List of all task JSON files to load. One file per OSRS area; chunks are
	// attributed to an area by filename. The former Lumbridge starter chunks now
	// live at the top of Misthalin_Tasks.json like any other Misthalin chunk.
	private static final String[] TASK_JSON_FILES = {
		"Misthalin_Tasks.json",
		"Asgarnia_Tasks.json",
		"Kandarin_Tasks.json",
		"Karamja_Tasks.json",
		"Desert_Tasks.json",
		"Varlamore_Tasks.json",
		"Zeah_Tasks.json",
		"Fremennik_Tasks.json",
		"Tirannwn_Tasks.json",
		"Morytania_Tasks.json",
		"Wilderness_Tasks.json",
		// Charter ports. Authored per-port in Tasks_JSON/Charter_Tasks_Folder and
		// aggregated into this one file by build-charter-tasks.ps1. They're free +
		// auto-unlocked via Free_Chunks.json (also generated by that script).
		"Charter_Tasks.json",
		// Boss chunks (raids / bosses). Authored per-boss in
		// task-authoring/Boss_Task_Folder and aggregated into this file by
		// build-task-catalog.ps1. Unlocked with Boss Tokens (not points); unlocking
		// grants EVERY task at once. See docs/BOSS-CHUNKS.md.
		"Boss_Tasks.json"
	};

	// The single free chunk every new game starts with (Lumbridge). Auto-unlocked
	// by ensureStartingChunkUnlocked(); every other chunk costs points.
	private static final int DEFAULT_START_REGION = 12850;


	/**
	 * Read a task JSON file's content by name from the catalog store
	 * (server-fetched → disk cache → bundled gzipped seed). Returns null if the
	 * store has no such file; callers log and skip. Post-migration there is no
	 * bundled raw JSON — the gzipped seed is the offline floor (see CatalogStore).
	 */
	private String readTaskFileContent(String filename)
	{
		return catalogStore != null ? catalogStore.getFileContent(filename) : null;
	}

	// Catalog version the task data below was parsed from; see reloadCatalogIfNewer().
	private volatile long parsedCatalogVersion;

	/**
	 * Re-read the task data if the catalog store now holds a newer catalog than the
	 * one parsed (a background refresh downloaded a fix). Only called from the login
	 * screen: no account is loaded there, and the next login rebuilds the active tasks
	 * from the fresh data, so a catalog fix lands after a logout instead of a restart.
	 */
	boolean reloadCatalogIfNewer()
	{
		long available = catalogStore != null ? catalogStore.getCatalogVersion() : 0;
		if (available <= parsedCatalogVersion)
		{
			return false;
		}
		loadChunkData();
		return true;
	}

	/** Check the server for a newer task catalog in the background; see applyCatalogIfLoggedOut(). */
	private void checkCatalog()
	{
		if (catalogStore != null)
		{
			catalogStore.refresh(() -> clientThread.invokeLater(this::applyCatalogIfLoggedOut));
		}
	}

	/**
	 * Apply a catalog the background check just downloaded, if nobody is logged in.
	 * A check that finishes after the player is back in game waits for the next
	 * logout, so the task data is never swapped mid-session.
	 */
	void applyCatalogIfLoggedOut()
	{
		if (client.getGameState() == GameState.LOGIN_SCREEN)
		{
			reloadCatalogIfNewer();
		}
	}

	private void loadChunkData()
	{
		// Read the version BEFORE the files: if a refresh lands mid-parse, the older
		// number just means the next login screen reloads again.
		parsedCatalogVersion = catalogStore != null ? catalogStore.getCatalogVersion() : 0;
		allChunks.clear();
		chunksByRegionId.clear();
		TaskTargetExtras.load(readTaskFileContent("_Target_Extras.json"), gson);


		Type mapType = new TypeToken<Map<String, List<NuzlockeChunk>>>()
		{
		}.getType();
		int totalChunksLoaded = 0;
		int totalRegionMappings = 0;

		for (String jsonFile : TASK_JSON_FILES)
		{
			try
			{

				// Sourced from the catalog store (server → cache → bundled seed),
				// with a bundled raw-resource fallback during the migration.
				String jsonContent = readTaskFileContent(jsonFile);
				if (jsonContent == null)
				{
					log.error("Missing task file: {}", jsonFile);
					continue;
				}

				Map<String, List<NuzlockeChunk>> data = null;
				try
				{
					data = gson.fromJson(jsonContent, mapType);
				}
				catch (Exception parseEx)
				{
					log.error("Bad JSON in {}: {}", jsonFile, parseEx.getMessage(), parseEx);
					continue;
				}

				if (data != null && !data.isEmpty())
				{
					// Get the first (and typically only) key from the JSON
					String rootKey = data.keySet().iterator().next();
					List<NuzlockeChunk> chunks = data.get(rootKey);


					if (chunks != null && !chunks.isEmpty())
					{
						int chunkCount = chunks.size();
						int regionCount = 0;

						// Extract area name from filename (e.g., "Misthalin_Tasks.json" -> "Misthalin")
						String areaName = jsonFile.replace("_Tasks.json", "")
							.replace("_", " ");

						// Add chunks and build mappings
						for (NuzlockeChunk chunk : chunks)
						{
							// Area is normally derived from the filename, but a chunk may
							// declare its own "area" in JSON to file it under a real region
							// instead of its source file — e.g. the ToA boss chunk lives in
							// Boss_Tasks.json but belongs to the Desert for the area filter.
							if (chunk.getArea() == null || chunk.getArea().isEmpty())
							{
								chunk.setArea(areaName);
							}
							allChunks.add(chunk);
							if (chunk.getRegionIds() != null)
							{
								for (Integer regionId : chunk.getRegionIds())
								{
									chunksByRegionId.put(regionId, chunk);
									regionCount++;
								}
							}
							else
							{
								log.warn("Chunk '{}' in {}: no regionIds", chunk.getName(), jsonFile);
							}
						}

						totalChunksLoaded += chunkCount;
						totalRegionMappings += regionCount;
					}
					else
					{
						log.warn("No chunks found in {} for key {}", jsonFile, rootKey);
					}
				}
				else
				{
					log.warn("Failed to parse {} - data is null or empty", jsonFile);
				}
			}
			catch (Exception e)
			{
				log.error("Failed to load chunk data from {}: {}", jsonFile, e.getMessage(), e);
			}
		}


		// Debug: Log all loaded region IDs
		if (!chunksByRegionId.isEmpty())
		{

			// Specifically check for Lumbridge (12850)
			if (chunksByRegionId.containsKey(12850))
			{
				NuzlockeChunk lumbridge = chunksByRegionId.get(12850);
			}
			else
			{
				log.error("Lumbridge chunk missing");
			}
		}
		else
		{
			log.error("No chunks loaded");
		}

		// Data-driven boss NPC-death detection: refresh the npc-id -> boss-key map from
		// the boss chunks' authored boss_npc_ids now that chunksByRegionId is populated.
		rebuildBossNpcKeys();

		// Free (0-cost) chunk registry — dungeons etc. that unlock for free.
		loadFreeChunks();

		// Global (chunk-independent) task pool — quests.
		loadGlobalTasks();

		// Both sources are loaded; index them for O(1) findTaskById.
		rebuildTaskIndex();
	}

	/**
	 * Rebuild the taskId index from allChunks + globalTasks. Call after ANY change to
	 * either: a stale index silently returns the wrong task. Duplicate taskIDs across
	 * chunks are expected; first wins.
	 */
	private void rebuildTaskIndex()
	{
		tasksById.clear();
		globalTaskIds.clear();

		for (NuzlockeChunk chunk : allChunks)
		{
			if (chunk.getTasks() == null)
			{
				continue;
			}
			for (NuzlockeTask task : chunk.getTasks())
			{
				if (task.getTaskId() != null)
				{
					warnOnSchemaError(task);
					tasksById.putIfAbsent(task.getTaskId(), task);
				}
			}
		}

		// Global tasks live in no chunk, so the old scan never found them —
		// meaning quest tasks could not resolve for the Completed Tasks panel.
		for (NuzlockeTask task : globalTasks)
		{
			if (task.getTaskId() != null)
			{
				warnOnSchemaError(task);
				tasksById.putIfAbsent(task.getTaskId(), task);
				globalTaskIds.add(task.getTaskId());
			}
		}
	}

	/**
	 * Surface authoring mistakes that would make a task silently uncompletable.
	 * Logged rather than thrown: one bad task must not stop the other ~2400 from
	 * loading, and the player still sees the task (it just can't be finished) —
	 * the point is that the mistake is visible in the log at startup instead of
	 * being discovered by whoever wastes an evening attempting it.
	 */
	private void warnOnSchemaError(NuzlockeTask task)
	{
		String problem = task.getGroupContentSchemaError();
		if (problem != null)
		{
			log.warn("Task '{}': {}", task.getTaskId(), problem);
		}
	}

	/**
	 * Load the Global Tasks pool (Quest_Tasks.json): QUEST_CHECK tasks free for every
	 * account, in no chunk.
	 *
	 * The file keeps the normal region-group shape with an EMPTY region_id because the Go
	 * catalog loader unmarshals every file as map[string][]regionGroup; a flat array
	 * would parse and silently load zero tasks. Kept out of allChunks and
	 * chunksByRegionId: with no region, findRegionForTask/getTaskArea would return
	 * garbage.
	 */
	private void loadGlobalTasks()
	{
		globalTasks.clear();

		// Quest_Tasks = the quest pool; Progression_Tasks = the per-skill level
		// ladder; Diary_Tasks = the achievement diaries. All are region-independent
		// and land in globalTasks; only completion_type tells them apart downstream.
		for (String file : new String[]{ "Quest_Tasks.json", "Progression_Tasks.json", "Diary_Tasks.json" })
		{
			loadGlobalTaskFile(file);
		}

		if (globalTasks.isEmpty())
		{
			// Loud, because the failure is otherwise invisible: the section
			// just renders empty and no global task ever awards a point.
			log.error("Global tasks file has no tasks");
		}
	}

	private void loadGlobalTaskFile(String file)
	{
		String json = readTaskFileContent(file);
		if (json == null)
		{
			log.error("Missing Global Tasks file: {}", file);
			return;
		}

		try
		{
			Type mapType = new TypeToken<Map<String, List<NuzlockeChunk>>>()
			{
			}.getType();
			Map<String, List<NuzlockeChunk>> data = gson.fromJson(json, mapType);

			if (data == null || data.isEmpty())
			{
				log.error("Global Tasks file {} parsed to nothing", file);
				return;
			}

			int before = globalTasks.size();
			for (List<NuzlockeChunk> groups : data.values())
			{
				if (groups == null)
				{
					continue;
				}
				for (NuzlockeChunk group : groups)
				{
					if (group == null || group.getTasks() == null)
					{
						continue;
					}
					globalTasks.addAll(group.getTasks());
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load Global Tasks from {}: {}", file, e.getMessage(), e);
		}
	}

	/**
	 * Register the Global Tasks pool with the module manager.
	 *
	 * MUST be called from loadActiveTasks(), after clearTask(), which wipes every
	 * module's active list: registered once at startup, the pool dies on the first chunk
	 * unlock. Completed quest tasks are skipped, or the module re-detects them on each
	 * reload, re-awarding base_points and duplicating ids in completedTasks.
	 */
	private void registerGlobalTasks(Set<String> completedTaskIds)
	{
		if (globalTasks.isEmpty() || questBackfillInFlight)
		{
			return;
		}

		questBackfillInFlight = true;

		// Quest state is read with a client script, so this has to be on the
		// client thread AND after login. Returning false reschedules for the
		// next tick, so this survives being called from the login flow.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return false;
			}

			// LOGGED_IN is NOT enough, nor is a single-skill probe: the table hydrates
			// skill by skill over several ticks. The baseline read from it is FROZEN, so
			// reading early records unloaded skills as 0 and pays out the ladder
			// retroactively.
			if (!isSkillDataSettled())
			{
				return false;
			}

			try
			{
				backfillAndRegisterGlobalTasks();
			}
			finally
			{
				questBackfillInFlight = false;
			}
			return true;
		});
	}

	/**
	 * Split the Global Tasks pool into "already finished before we ever looked" and
	 * "still to do", then settle the first group in ONE silent batch. Module detection
	 * would run the full single-task pipeline per task, quadratic and on the client
	 * thread, hard-locking a developed account on login. Only quests finished DURING play
	 * reach the module and get the normal celebration.
	 */
	private void backfillAndRegisterGlobalTasks()
	{
		Set<String> completedTaskIds = getCompletedTaskIds();

		// Freeze the Progression baseline before any SKILL_THRESHOLD task is
		// considered. First call captures live levels; later calls return what
		// was captured then.
		Map<String, Integer> progressionBaseline = ensureProgressionBaseline();

		List<NuzlockeTask> backfilled = new ArrayList<>();
		int backfilledPoints = 0;

		for (NuzlockeTask task : globalTasks)
		{
			String taskId = task.getTaskId();
			if (taskId == null || completedTaskIds.contains(taskId))
			{
				continue;
			}

			// Progression is NOT retroactive: a rung at or below the frozen
			// baseline was already cleared before this account was tracked, so
			// it is dropped entirely — never registered, never shown, never
			// scored. Only rungs above the baseline are live.
			if (isProgressionTask(task) && !isProgressionRungEligible(task, progressionBaseline))
			{
				continue;
			}

			// Global tasks are pass/fail, never counted. targetQuantity is a
			// transient defaulting to 0, and a 0 target reads as "already done"
			// to progress comparisons elsewhere, so pin it explicitly.
			task.setTargetQuantity(1);
			task.setCurrentProgress(0);
			task.setCompleted(false);

			// Already-satisfied global tasks settle in one batch. For quests and
			// diaries that means "finished before we looked"; for Progression it means
			// a rung ABOVE the baseline that was crossed while the plugin was off — real
			// forward progress we simply didn't witness, so it still pays.
			if (isQuestFinished(task) || isProgressionRungReached(task) || VarbitCheckModule.isSatisfied(client, task))
			{
				task.setCurrentProgress(1);
				task.setCompleted(true);
				backfilled.add(task);
				backfilledPoints += task.getBasePoints();
				continue;
			}

			taskModuleManager.registerActiveTask(task);
		}

		if (backfilled.isEmpty())
		{
			return;
		}

		// One batch: two config writes and one panel refresh for the whole set.
		completeTasks(backfilled);

		addPluginChatMessage("Global Tasks: " + backfilled.size()
			+ " already complete (+" + backfilledPoints + " points).");
	}

	// --- Progression baseline ---------------------------------------------

	private static final String PROGRESSION_TYPE = "SKILL_THRESHOLD";

	private boolean isProgressionTask(NuzlockeTask task)
	{
		return PROGRESSION_TYPE.equalsIgnoreCase(task.getCompletionType());
	}

	// Separates the owning account's RSN hash from the skill CSV. Plugin config
	// is per RuneLite PROFILE, not per account, so a stored baseline has to name
	// whose it is — see progressionBaselineOwner().
	private static final String BASELINE_OWNER_SEP = "|";

	// Parsed form of acStr("progressionBaseline", ""). Cached because the panel asks
	// about visibility once per task per repaint (239 progression rungs), and the
	// value is immutable once frozen. cachedBaselineOwner records which account
	// it was parsed for, so hopping accounts can't reuse the wrong one.
	private volatile Map<String, Integer> cachedProgressionBaseline;
	private volatile String cachedBaselineOwner;

	/** RSN hash of the account currently logged in, or null if not known yet. */
	private String currentAccountHash()
	{
		String rsn = getPlayerName();
		return (rsn == null || rsn.isEmpty()) ? null : hashRsn(rsn);
	}

	/**
	 * This account's frozen baseline, or empty when there isn't one FOR THIS ACCOUNT.
	 *
	 * <p>Config is shared by every account on the RuneLite profile, so without an owner
	 * tag a fresh level 3 would inherit a main's baseline and be locked out of the whole
	 * ladder permanently. An untagged value belongs to nobody and is re-captured for
	 * whoever is logged in, which can only be more restrictive.
	 */
	private Map<String, Integer> progressionBaselineForCurrentAccount()
	{
		String owner = currentAccountHash();
		if (owner == null)
		{
			return new HashMap<>();
		}

		if (owner.equals(cachedBaselineOwner) && cachedProgressionBaseline != null)
		{
			return cachedProgressionBaseline;
		}

		String raw = acStr("progressionBaseline", "");
		Map<String, Integer> parsed = new HashMap<>();
		if (raw != null && raw.contains(BASELINE_OWNER_SEP))
		{
			int sep = raw.indexOf(BASELINE_OWNER_SEP);
			if (owner.equals(raw.substring(0, sep)))
			{
				parsed = parseProgressionBaseline(raw.substring(sep + 1));
			}
		}

		cachedProgressionBaseline = parsed;
		cachedBaselineOwner = owner;
		return parsed;
	}

	/**
	 * The Global Tasks the UI should show, minus Progression rungs at or below the frozen
	 * baseline, which can never be earned. Visibility only:
	 * {@link #backfillAndRegisterGlobalTasks()} decides eligibility from the same
	 * baseline.
	 */
	public List<NuzlockeTask> getVisibleGlobalTasks()
	{
		Map<String, Integer> baseline = progressionBaselineForCurrentAccount();
		if (baseline.isEmpty())
		{
			// Baseline not captured yet (pre-login). Hiding on an empty baseline
			// would blank the whole Progression tier; show it and let the next
			// repaint after capture filter properly.
			return globalTasks;
		}

		List<NuzlockeTask> visible = new ArrayList<>(globalTasks.size());
		for (NuzlockeTask task : globalTasks)
		{
			if (isProgressionTask(task) && !isProgressionRungEligible(task, baseline))
			{
				continue;
			}
			visible.add(task);
		}
		return visible;
	}

	/**
	 * The frozen per-skill levels this account had when ChunkBlazer first saw it. Rungs
	 * at or below these never pay. Captured ONCE, never rewritten. If lost, the next
	 * login re-captures at CURRENT levels, which can cost rungs but never pays unearned
	 * points. The Hitpoints level-10 rung isn't in the task data.
	 *
	 * <p>Client thread only: reads live skill data.
	 */
	// Consecutive identical skill-table readings needed before we trust it. Completeness
	// is the real gate; this is the backstop for SAILING (see isSkillDataComplete()).
	private static final int REQUIRED_STABLE_SKILL_SAMPLES = 3;

	private int[] lastSkillSample;
	private int stableSkillSamples;

	/**
	 * Whether the client's skill table holds this account's real levels.
	 *
	 * <p>The table hydrates INCREMENTALLY, in {@link Skill} enum order, over several
	 * ticks after LOGGED_IN, so neither LOGGED_IN nor a Hitpoints-only probe is enough.
	 * No OSRS skill can be below 1, so a 0 anywhere means that entry hasn't loaded; no
	 * timer needed.
	 *
	 * <p>Sailing is excluded: if it isn't live in this client build it reads 0 forever.
	 * The stability test still covers it, so a merely LATE Sailing value blocks capture.
	 */
	private boolean isSkillDataComplete()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		if (client.getRealSkillLevel(Skill.HITPOINTS) < 10)
		{
			return false;
		}
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.SAILING)
			{
				continue;
			}
			if (client.getRealSkillLevel(skill) < 1)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Complete AND unchanged for {@link #REQUIRED_STABLE_SKILL_SAMPLES}
	 * consecutive readings. Advances internal state, so it must only be driven
	 * from the retry loop in {@link #registerGlobalTasks}.
	 */
	private boolean isSkillDataSettled()
	{
		if (!isSkillDataComplete())
		{
			lastSkillSample = null;
			stableSkillSamples = 0;
			return false;
		}

		Skill[] skills = Skill.values();
		int[] now = new int[skills.length];
		for (int i = 0; i < skills.length; i++)
		{
			now[i] = client.getRealSkillLevel(skills[i]);
		}

		if (java.util.Arrays.equals(now, lastSkillSample))
		{
			stableSkillSamples++;
		}
		else
		{
			stableSkillSamples = 0;
		}
		lastSkillSample = now;

		return stableSkillSamples >= REQUIRED_STABLE_SKILL_SAMPLES;
	}

	private Map<String, Integer> ensureProgressionBaseline()
	{
		Map<String, Integer> baseline = progressionBaselineForCurrentAccount();
		if (!baseline.isEmpty())
		{
			return baseline;
		}

		if (!isSkillDataComplete())
		{
			// Never freeze a baseline off a half-loaded skill table: that is permanent
			// and pays out the ladder. Empty means every rung is ineligible until a later
			// call captures. Should be unreachable since registerGlobalTasks reschedules,
			// but keep this second gate. Stateless check, so it can't disturb the
			// caller's stability counter.
			return baseline;
		}

		// Whose baseline this is. Without an RSN we cannot tag it, and an untagged
		// baseline would be silently inherited by the next account on this
		// profile — so wait rather than write one.
		String owner = currentAccountHash();
		if (owner == null)
		{
			return baseline;
		}

		StringBuilder sb = new StringBuilder();
		for (Skill skill : Skill.values())
		{
			int level = client.getRealSkillLevel(skill);
			baseline.put(skill.name(), level);
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(skill.name()).append(':').append(level);
		}

		setAccountState("progressionBaseline",
			owner + BASELINE_OWNER_SEP + sb);
		cachedProgressionBaseline = baseline;
		cachedBaselineOwner = owner;
		return baseline;
	}

	/**
	 * Undo a baseline frozen from a half-loaded skill table, where a 0 makes every rung
	 * of that skill pay out.
	 *
	 * <p>The tell is an impossible value, not a version marker: no skill below 1, no
	 * account below 10 Hitpoints (Sailing is exempt, it reads 0 when not live). Such a
	 * baseline is cleared and every progression task un-completed with its points
	 * refunded, so the next capture redoes it. Earned rungs go too, re-earned once the
	 * level-up is seen again. Self-healing: repairs any client when it next loads, then
	 * no-ops.
	 */
	public void migrateRepairBogusProgressionBaseline()
	{
		String raw = acStr("progressionBaseline", "");
		if (raw == null || raw.trim().isEmpty())
		{
			return;
		}

		// Split off the owner tag. Untagged values predate account tagging and
		// are still repaired (they belong to whoever is here); a value tagged for
		// a DIFFERENT account is left alone — it isn't ours to clean up, and that
		// account repairs its own on its next login.
		String csv = raw;
		int sep = raw.indexOf(BASELINE_OWNER_SEP);
		if (sep >= 0)
		{
			String owner = raw.substring(0, sep);
			String me = currentAccountHash();
			if (me != null && !me.equals(owner))
			{
				return;
			}
			csv = raw.substring(sep + 1);
		}

		Map<String, Integer> baseline = parseProgressionBaseline(csv);
		if (!isBaselineImpossible(baseline))
		{
			return; // sane baseline, nothing to do
		}

		// Drop every progression_* id from the completed set and refund its points.
		Set<String> completed = getCompletedTaskIds();
		int refunded = 0;
		int removed = 0;
		List<String> keep = new ArrayList<>();
		for (String id : completed)
		{
			if (id.startsWith("progression_"))
			{
				NuzlockeTask task = findTaskById(id);
				if (task != null)
				{
					refunded += task.getBasePoints();
				}
				removed++;
			}
			else
			{
				keep.add(id);
			}
		}

		setAccountState("progressionBaseline", "");
		cachedProgressionBaseline = null;
		cachedBaselineOwner = null;
		if (removed > 0)
		{
			// This is a repair, not a bug, but on the wire it is indistinguishable
			// from one — a large slice of completed tasks vanishing. Unflagged it
			// would be refused, the server would keep the bogus completions, and the
			// next login's union would restore them and re-trigger this repair on a
			// loop.
			declareIntentionalReset("progression baseline repair dropped "
				+ removed + " progression task(s)");
			setAccountState("completedTasks", String.join(",", keep));
			addPoints(-refunded);
			completedTaskCache.keySet().removeIf(id -> id.startsWith("progression_"));
		}

		log.warn("Repaired zero Progression baseline: {} tasks undone, {} points refunded", removed, refunded);
	}

	/**
	 * True if this baseline records a level no account can actually have, which
	 * means it was frozen from a partly-loaded skill table. Sailing is exempt: it
	 * reads 0 when the skill isn't live, which is legitimate. A skill missing
	 * entirely also counts as impossible — a complete capture writes all of them.
	 */
	private boolean isBaselineImpossible(Map<String, Integer> baseline)
	{
		if (baseline.isEmpty())
		{
			return false;
		}
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.SAILING)
			{
				continue;
			}
			Integer level = baseline.get(skill.name());
			int floor = skill == Skill.HITPOINTS ? 10 : 1;
			if (level == null || level < floor)
			{
				return true;
			}
		}
		return false;
	}

	private Map<String, Integer> parseProgressionBaseline(String csv)
	{
		Map<String, Integer> baseline = new HashMap<>();
		if (csv == null || csv.trim().isEmpty())
		{
			return baseline;
		}
		for (String entry : csv.split(","))
		{
			String[] parts = entry.trim().split(":");
			if (parts.length != 2)
			{
				continue;
			}
			try
			{
				baseline.put(parts[0].trim().toUpperCase(), Integer.parseInt(parts[1].trim()));
			}
			catch (NumberFormatException ignored)
			{
				// Malformed entry — treat that skill as unbaselined (ineligible).
			}
		}
		return baseline;
	}

	/**
	 * Is this Progression rung above the frozen baseline, i.e. still earnable?
	 * A skill missing from the baseline reads as NOT eligible: that only happens
	 * when the baseline hasn't been captured yet, and refusing to pay is the safe
	 * direction (the rung becomes live on the next login once the baseline exists).
	 */
	private boolean isProgressionRungEligible(NuzlockeTask task, Map<String, Integer> baseline)
	{
		TaskConstraints c = task.getConstraints();
		if (c == null || c.getRequiredSkill() == null)
		{
			return false;
		}
		Integer base = baseline.get(c.getRequiredSkill().toUpperCase());
		return base != null && c.getRequiredLevel() > base;
	}

	/**
	 * Whether an ELIGIBLE Progression rung has already been reached — the player
	 * levelled past it while the plugin was off. Callers must have filtered on
	 * {@link #isProgressionRungEligible} first; this only asks about the level.
	 * Real level, never boosted.
	 */
	private boolean isProgressionRungReached(NuzlockeTask task)
	{
		if (!isProgressionTask(task) || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		TaskConstraints c = task.getConstraints();
		if (c == null || c.getRequiredSkill() == null)
		{
			return false;
		}
		try
		{
			return client.getRealSkillLevel(Skill.valueOf(c.getRequiredSkill().toUpperCase()))
				>= c.getRequiredLevel();
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
	}

	/**
	 * Whether the quest named by a QUEST_CHECK task is already FINISHED.
	 * Client thread only — getState() runs a script.
	 */
	private boolean isQuestFinished(NuzlockeTask task)
	{
		TaskConstraints constraints = task.getConstraints();
		String questName = constraints != null ? constraints.getQuest() : null;
		if (questName == null || questName.isEmpty())
		{
			return false;
		}

		try
		{
			return net.runelite.api.Quest.valueOf(questName).getState(client)
				== net.runelite.api.QuestState.FINISHED;
		}
		catch (IllegalArgumentException e)
		{
			// Unknown constant — task data newer than the API we build against.
			// QuestCheckModule logs this by name; stay quiet here.
			return false;
		}
	}

	/**
	 * Load the free-chunk registry (Free_Chunks.json): region IDs that unlock for
	 * 0 points. The dungeon-as-free-chunk design lists dungeon regions here. Parsed
	 * leniently (extra keys like "_comment" are ignored). Client-only for now — the
	 * server doesn't yet validate unlocks, so when that lands it must read the same
	 * list (or this file should move server-side too).
	 */
	private void loadFreeChunks()
	{
		String json = readTaskFileContent("Free_Chunks.json");
		if (json == null)
		{
			return;
		}
		try
		{
			com.google.gson.JsonObject obj = gson.fromJson(json, com.google.gson.JsonObject.class);
			freeUnlockableRegionIds.clear();
			freeUnlockableNames.clear();
			freeUnlockableNeighbors.clear();
			// Accept "Free_chunks" (current schema: array of {region_id[], Friendly_Name,
			// neighbor_ids[], unlock_cost}) or lower-case "free_chunks" for forgiveness.
			com.google.gson.JsonArray arr = null;
			if (obj != null && obj.has("Free_chunks") && obj.get("Free_chunks").isJsonArray())
			{
				arr = obj.getAsJsonArray("Free_chunks");
			}
			else if (obj != null && obj.has("free_chunks") && obj.get("free_chunks").isJsonArray())
			{
				arr = obj.getAsJsonArray("free_chunks");
			}
			if (arr != null)
			{
				for (com.google.gson.JsonElement el : arr)
				{
					if (!el.isJsonObject())
					{
						continue;
					}
					com.google.gson.JsonObject entry = el.getAsJsonObject();
					String name = (entry.has("Friendly_Name") && !entry.get("Friendly_Name").isJsonNull())
						? entry.get("Friendly_Name").getAsString() : null;
					List<Integer> neighborIds = new ArrayList<>();
					if (entry.has("neighbor_ids") && entry.get("neighbor_ids").isJsonArray())
					{
						for (com.google.gson.JsonElement nEl : entry.getAsJsonArray("neighbor_ids"))
						{
							neighborIds.add(nEl.getAsInt());
						}
					}
					if (entry.has("region_id") && entry.get("region_id").isJsonArray())
					{
						for (com.google.gson.JsonElement idEl : entry.getAsJsonArray("region_id"))
						{
							int rid = idEl.getAsInt();
							freeUnlockableRegionIds.add(rid);
							if (name != null && !name.isEmpty())
							{
								freeUnlockableNames.put(rid, name);
							}
							if (!neighborIds.isEmpty())
							{
								freeUnlockableNeighbors.put(rid, neighborIds);
							}
						}
					}
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load Free_Chunks.json: {}", e.getMessage(), e);
		}
	}
	// --- Game Mode Methods ---

	public GameMode getGameMode()
	{
		String hash = acStr("accountModeHash", "");
		if (hash != null && !hash.isEmpty() && hash.contains(":"))
		{
			String modeName = hash.split(":")[1];
			try
			{
				return GameMode.valueOf(modeName);
			}
			catch (IllegalArgumentException e)
			{
				return GameMode.CASUAL;
			}
		}
		return acGameMode();
	}

	public boolean isLoggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	public boolean isModeLocked()
	{
		String hash = acStr("accountModeHash", "");
		if (hash == null || hash.isEmpty())
		{
			return false;
		}

		// Check if the hash is for the current account
		String currentRsn = getPlayerName();
		if (currentRsn == null)
		{
			// Player not loaded yet (right after a world hop's LOGGED_IN). Reporting
			// "unlocked" here flashes the mode picker on every hop, so trust the last
			// verdict. It resets on real logout.
			return modeLockConfirmed;
		}

		String expectedPrefix = hashRsn(currentRsn);
		boolean locked = hash.startsWith(expectedPrefix);
		modeLockConfirmed = locked;
		return locked;
	}

	/**
	 * Entry point from the side panel's "Confirm Mode" button. CASUAL locks
	 * immediately. Full Nuzlocke is gated: we first prove the account is a fresh
	 * "level 3" start and that the player owns the RSN (chat-code handshake)
	 * before committing the lock — see {@link #beginNuzlockeLock()}.
	 */
	public void lockGameMode(GameMode mode)
	{
		if (getPlayerName() == null)
		{
			log.warn("Cannot lock game mode: player not logged in");
			return;
		}

		if (mode == GameMode.NUZLOCKE)
		{
			beginNuzlockeLock();
			return;
		}

		commitModeLock(mode, null);
	}

	/**
	 * Kick off the Full Nuzlocke gate: read the live account on the client
	 * thread, ask the server whether it qualifies (a fresh level-3 start), then
	 * either start the verification handshake (eligible) or tell the player why
	 * they don't qualify (ineligible). The lock itself is only committed later,
	 * once verification succeeds.
	 */
	private void beginNuzlockeLock()
	{
		if (!config.apiEnabled() || apiClient == null)
		{
			// Without the server we can't authoritatively verify eligibility.
			// Fail closed rather than locking an unchecked account into Nuzlocke.
			addPluginChatMessage("Competitive needs Server Sync to verify your account. Enable it and try again.");
			return;
		}

		// Skill levels and quest points must be read on the client thread.
		clientThread.invoke(() ->
		{
			EligibilitySnapshot snapshot = buildEligibilitySnapshot();
			if (snapshot == null)
			{
				addPluginChatMessage("Log in fully before selecting Competitive.");
				return;
			}

			apiClient.checkNuzlockeEligibility(snapshot)
				.thenAccept(resp ->
				{
					if (resp != null && resp.isEligible())
					{
						// Stash the snapshot; the chat-code handshake will commit
						// the lock (re-sending it for the server to re-validate).
						pendingNuzlockeSnapshot = snapshot;
						startNuzlockeVerification();
					}
					else
					{
						pendingNuzlockeSnapshot = null;

						// Surface the server's specific reason. It already computes
						// one ("Hitpoints must be level 10", "combat level must be
						// 3 (yours is N)", …) and swallowing it left a genuinely
						// fresh account with no way to tell a real disqualification
						// from a bad reading — which is exactly the position we
						// were in on 2026-07-21 with the account "ChunkBlazer".
						String reason = resp != null ? resp.getReason() : null;
						addPluginChatMessage("Your account doesn't meet the Competitive requirements. Play Casual or start a new account."
							+ (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));

						// Log what we actually SENT as well. If the reason looks
						// wrong for the account, the snapshot is the thing to
						// distrust — client-side reads can be unhydrated.
						log.warn("Competitive refused: {} (combat {} qp {} total {} skills {})", reason, snapshot.getCombatLevel(), snapshot.getQuestPoints(), snapshot.getTotalLevel(), snapshot.getSkills());
					}
				});
		});
	}

	/**
	 * Read the local account's combat level, quest points, total level, and
	 * every skill's real level into a snapshot for the server to evaluate.
	 * MUST be called on the client thread. Returns null if not yet logged in.
	 */
	private EligibilitySnapshot buildEligibilitySnapshot()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}
		Map<String, Integer> skills = new HashMap<>();
		for (Skill skill : Skill.values())
		{
			skills.put(skill.name(), client.getRealSkillLevel(skill));
		}
		return EligibilitySnapshot.builder()
			.combatLevel(local.getCombatLevel())
			.questPoints(client.getVarpValue(VarPlayerID.QP))
			.totalLevel(client.getTotalLevel())
			.skills(skills)
			.accountType(client.getVarbitValue(VarbitID.IRONMAN))
			.build();
	}

	/**
	 * Start (or short-circuit) the verification handshake for a pending Nuzlocke
	 * lock. If the account is already verified we commit straight away; otherwise
	 * we issue a chat code and ask the player to type it — typing it is what
	 * commits the lock (see {@link #handleVerificationChat}).
	 */
	private void startNuzlockeVerification()
	{
		apiClient.verifyStart()
			.thenAccept(start ->
			{
				if (start == null)
				{
					pendingNuzlockeSnapshot = null;
					addPluginChatMessage("Couldn't reach the server to verify your account. Try again shortly.");
					return;
				}
				if (start.isAlreadyVerified())
				{
					// RSN ownership already proven — commit the Nuzlocke lock now.
					EligibilitySnapshot snap = pendingNuzlockeSnapshot;
					pendingNuzlockeSnapshot = null;
					if (snap != null)
					{
						addPluginChatMessage("Your account meets the Competitive requirements. Locking it in!");
						commitModeLock(GameMode.NUZLOCKE, snap);
					}
					return;
				}
				if (start.getNonce() == null || start.getChatPhrase() == null)
				{
					pendingNuzlockeSnapshot = null;
					addPluginChatMessage("Couldn't issue a verification code right now. Try again shortly.");
					return;
				}
				String nonce = start.getNonce();
				pendingVerificationNonce = nonce;
				addPluginChatMessage("You qualify for Competitive! Type " + nonce + " in public chat and press Enter to lock it in.");
				panel.showVerificationPrompt(nonce);
			});
	}

	/**
	 * Actually commit a mode lock: write local config, mirror to the server, and
	 * (for Casual) unlock the starting chunk. For NUZLOCKE the server re-checks
	 * the eligibility snapshot and the verified flag before accepting the lock.
	 */
	private void commitModeLock(GameMode mode, EligibilitySnapshot eligibility)
	{
		String rsn = getPlayerName();
		if (rsn == null)
		{
			log.warn("Cannot lock game mode: player not logged in");
			return;
		}

		String rsnHash = hashRsn(rsn);
		String modeKey = rsnHash + ":" + mode.name();

		// CASUAL persists immediately: the server never refuses it. NUZLOCKE persists
		// only in the success branch below: the server re-validates fresh-L3 eligibility,
		// and an early local lock leaves a rejected main stuck showing Competitive with
		// every lock/sync refused.
		if (mode != GameMode.NUZLOCKE)
		{
			setAccountState("accountModeHash", modeKey);
			setAccountState("gameMode", mode);
		}

		// Mirror the lock to the server. For Casual this just backs up the choice;
		// for Nuzlocke the server re-validates eligibility and is authoritative.
		if (config.apiEnabled() && apiClient != null)
		{
			apiClient.lockGameMode(mode, eligibility)
				.thenAccept(response ->
				{
					if (response == null)
					{
						if (mode == GameMode.NUZLOCKE)
						{
							addPluginChatMessage("Couldn't reach the server to confirm Competitive. Try again in a moment.");
						}
						return;
					}
					if (response.isSuccess())
					{
						if (mode == GameMode.NUZLOCKE)
						{
							// Server confirmed eligibility — now it is safe to persist.
							setAccountState("accountModeHash", modeKey);
							setAccountState("gameMode", mode);
							addPluginChatMessage("Competitive locked in. Good luck, there's no going back!");
						}
					}
					else if (response.isAlreadyLocked())
					{
						log.warn("Mode already locked: {}", response.getGameModeEnum());
					}
					else
					{
						// Rejected (e.g. eligibility_required for a non-fresh account).
						// Never leave a local Competitive lock the server won't honor.
						if (mode == GameMode.NUZLOCKE)
						{
							addPluginChatMessage("Competitive was declined: your account isn't eligible. Pick Casual in the side panel to play.");
						}
						log.warn("Lock mode: {} {}", response.getStatus(), response.getMessage());
					}
				});
		}
		else if (mode == GameMode.NUZLOCKE)
		{
			// No server to confirm eligibility — cannot safely lock Competitive.
			addPluginChatMessage("Competitive needs Server Sync to verify your account. Enable it and try again.");
			return;
		}

		// For Casual mode, unlock the current chunk the player is standing in
		if (mode == GameMode.CASUAL)
		{
			int currentRegion = preModeRegionId > 0 ? preModeRegionId : getCurrentRegionId();

			if (currentRegion > 0 && !isRegionUnlocked(currentRegion))
			{
				unlockRegionFree(currentRegion);

				// Roll tasks for the newly unlocked region
				Set<String> newTasks = rollTasksForRegion(currentRegion);

				// Reload active tasks
				loadActiveTasks();
			}
		}

		panel.updateModeDisplay();
		panel.updatePanel();
	}

	private static final String CHARTER_SEED_STRIPPED_KEY = "charterSeedStripped";

	/**
	 * One-time migration: strip charter regions earlier builds seeded into the unlocked
	 * set, now that ports are unlock-on-demand. A hidden flag guards it so a port
	 * unlocked AFTER the migration is never stripped. Cheap no-op once set.
	 */
	public void migrateStripSeededCharterChunks()
	{
		if ("true".equals(configManager.getConfiguration("chunkblazer", CHARTER_SEED_STRIPPED_KEY)))
		{
			return;
		}

		List<String> kept = new ArrayList<>();
		int removed = 0;
		for (String regionIdStr : getUnlockedRegionIds())
		{
			boolean charter;
			try
			{
				charter = isCharterRegion(Integer.parseInt(regionIdStr));
			}
			catch (NumberFormatException e)
			{
				charter = false;
			}
			if (charter)
			{
				removed++;
			}
			else
			{
				kept.add(regionIdStr);
			}
		}

		if (removed > 0)
		{
			// Declares itself because on the wire this looks like a wipe (27 charter
			// regions against a median account of 5 chunks). A refusal can't retry: the
			// flag below is set unconditionally, so the server keeps the chunks, the next
			// login's union restores them, and the strip undoes itself for good.
			declareIntentionalReset("charter seed strip removed "
				+ removed + " charter chunk(s)");
			setAccountState("unlockedChunks", String.join(",", kept));
		}
		configManager.setConfiguration("chunkblazer", CHARTER_SEED_STRIPPED_KEY, "true");
	}

	private static final String SOUND_VOLUME_MIGRATED_KEY = "soundVolumeReset3Pct";
	private static final int OLD_DEFAULT_SOUND_VOLUME = 25;

	/**
	 * One-time migration: clear a STORED {@value #OLD_DEFAULT_SOUND_VOLUME}% sound volume
	 * so it falls back to the quiet 3% default. RuneLite config sync can resurrect the
	 * stored value across installs. A hidden flag guards it so a value set AFTER the
	 * migration (even 25) is never touched.
	 */
	public void migrateResetStaleSoundVolume()
	{
		if ("true".equals(configManager.getConfiguration(CONFIG_GROUP, SOUND_VOLUME_MIGRATED_KEY)))
		{
			return;
		}
		Integer stored = configManager.getConfiguration(CONFIG_GROUP, "taskCompletionSoundVolume", Integer.class);
		if (stored != null && stored == OLD_DEFAULT_SOUND_VOLUME)
		{
			// Unset (not set-to-3): let it resolve to the code default so future
			// default changes carry through, and so this reads as "never chosen".
			configManager.unsetConfiguration(CONFIG_GROUP, "taskCompletionSoundVolume");
		}
		configManager.setConfiguration(CONFIG_GROUP, SOUND_VOLUME_MIGRATED_KEY, "true");
	}

	/** True if the region belongs to a charter-port chunk ({@code chunk_type:CHARTER}). */
	public boolean isCharterRegion(int regionId)
	{
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		return chunk != null && chunk.isCharter();
	}

	/**
	 * A region the player can unlock right now: a not-yet-unlocked neighbour of
	 * the unlocked set, OR any not-yet-unlocked charter port. Charter ports are
	 * reached by boat and aren't adjacent to the contiguous unlocked area, so
	 * they're unlockable from anywhere (and free — see {@link #getRegionUnlockCost}).
	 */
	public boolean isUnlockableRegion(int regionId)
	{
		if (isRegionUnlocked(regionId))
		{
			return false;
		}
		return isCharterRegion(regionId) || isFreeUnlockableRegion(regionId) || getNeighborRegionIds().contains(regionId);
	}

	private String hashRsn(String rsn)
	{
		return Hashing.sha256()
			.hashString(rsn.toLowerCase().trim(), StandardCharsets.UTF_8)
			.toString()
			.substring(0, 16);
	}

	/**
	 * SHA-256 of RuneLite's per-account id, or null while no account is logged in (the
	 * client reports -1 then). Unlike the name it survives a rename, which is how the
	 * server tells a name change from a different account holding this account's key.
	 */
	private String hashedAccountId()
	{
		long id = client.getAccountHash();
		return id == -1 ? null : Hashing.sha256().hashString(Long.toString(id), StandardCharsets.UTF_8).toString();
	}

	/**
	 * Full 64-char SHA-256 of the lowercase RSN. Used as the server-side
	 * stable identity (rsn_hash) when calling /api/player/login. The truncated
	 * 16-char form from hashRsn() is for in-game/local audit fields only.
	 */
	private String fullHashRsn(String rsn)
	{
		return Hashing.sha256()
			.hashString(rsn.toLowerCase().trim(), StandardCharsets.UTF_8)
			.toString();
	}

	/**
	 * POST /api/player/login on game-login. Server upserts the player and
	 * returns an api_key plus persisted state (game mode, points, etc).
	 * We use the response to hydrate locally-cached config so the mode lock
	 * survives wiping the RuneLite profile.
	 */
	/** Whether the player has opted into server sync. Read by the panel to show the enable prompt vs the synced notice. */
	public boolean isServerSyncEnabled()
	{
		return config.apiEnabled();
	}

	/**
	 * Turn server sync on from the panel's first-run prompt, logging in right away if
	 * in-game. Offline progress uploads on the first sync after login; that push is pure
	 * growth, so the drop guard never fires.
	 */
	public void enableServerSync()
	{
		if (config.apiEnabled())
		{
			return;
		}
		configManager.setConfiguration(CONFIG_GROUP, "serverSyncEnabled", true);
		serverBackoff.reset();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			loginToServer();
		}
		if (panel != null)
		{
			panel.updatePanel();
		}
	}

	/**
	 * From the panel's "enable Server Sync?" prompt (player picked Competitive with
	 * sync off and chose Yes): turn sync on, then start the Competitive lock as soon
	 * as we're logged in — deferred because the eligibility + verification calls need
	 * the api_key that login provides. If sync is already on, start the lock now.
	 */
	public void enableServerSyncAndLockCompetitive()
	{
		if (config.apiEnabled())
		{
			lockGameMode(GameMode.NUZLOCKE);
			return;
		}
		pendingCompetitiveLock = true;
		enableServerSync(); // flips the toggle and logs in (if in-game)
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			addPluginChatMessage("Server Sync enabled. Log in and pick Competitive to continue.");
		}
	}

	private void loginToServer()
	{
		if (!config.apiEnabled())
		{
			return;
		}
		String rsn = getPlayerName();
		if (rsn == null)
		{
			return;
		}
		if (apiClient == null)
		{
			log.warn("apiClient is null; Guice injection failed for the plugin");
			return;
		}
		// Load this account's stored API key BEFORE login. The server now discloses the
		// key only on first claim (security C1), so a returning account authenticates
		// with the copy it persisted, not one re-fetched every login.
		loadPersistedApiKey();
		lastSyncSuccessMs = 0;
		String accountId = hashedAccountId();
		apiClient.login(rsn, fullHashRsn(rsn), accountId)
			.thenAccept(resp ->
			{
				ApiOutcome outcome = resp == null ? ApiOutcome.TRANSIENT : resp.getOutcome();
				recordServerOutcome(outcome, resp == null ? 0 : resp.getRetryAfterMs());
				if (outcome == ApiOutcome.REGISTRATION_CLOSED && !rsn.equals(registrationClosedRsn))
				{
					registrationClosedRsn = rsn;
					addPluginChatMessage("ChunkBlazer sign-ups are closed, so this account isn't syncing. Your progress is saved locally.");
				}
				// Only mark complete on a real OK / created response. Offline
				// or error responses leave the flag false so we retry next
				// LOGGED_IN tick instead of pretending we're done.
				if (resp != null && resp.isSuccess())
				{
					serverLoginDone = true;
					if (resp.isKeyMismatch())
					{
						if (pendingPastedKey != null)
						{
							// A pasted candidate that belongs to a DIFFERENT account. Discard
							// it and keep the stored key untouched — a bad paste must never
							// wipe a working key.
							restoreStoredApiKey();
							configManager.setConfiguration(CONFIG_GROUP, "apiKey", "");
							addPluginChatMessage("That sync key is another account's, so it was ignored. Your saved key is unchanged.");
						}
						else
						{
							// The STORED key belongs to another account (the cross-account
							// leak). Drop it so we stop impersonating; the server served THIS
							// account by name. Sync re-enables once this account's own key is
							// present (RuneLite config-sync carries it, else a one-time paste).
							if (isAccountStateAvailable())
							{
								setAccountState("apiKey", "");
							}
							apiClient.setPlayerApiKey(null);
						}
					}
					else if (pendingPastedKey != null)
					{
						if (resp.isAuthedByKey())
						{
							// The pasted key key-authenticated as THIS account: confirmed
							// correct, so NOW persist it (replacing any old key) and wipe
							// the shared recovery field.
							persistApiKey(pendingPastedKey, accountId);
							configManager.setConfiguration(CONFIG_GROUP, "apiKey", "");
							addPluginChatMessage("Sync key accepted. This account is now synced with it.");
						}
						else
						{
							// The pasted key did NOT authenticate this account (unknown or
							// wrong; the server served us by name). Discard it WITHOUT
							// touching the stored key, so a bad paste can never brick a
							// working account.
							restoreStoredApiKey();
							configManager.setConfiguration(CONFIG_GROUP, "apiKey", "");
							addPluginChatMessage("That sync key wasn't recognised, so it was ignored. Your saved key is unchanged.");
						}
					}
					else
					{
						// First-claim logins return a fresh key; capture it per-account.
						persistApiKey(apiClient.getPlayerApiKey(), accountId);
					}
					pendingPastedKey = null;
					clientThread.invoke(this::sendPendingHcimDeath);
					// A Competitive lock the player asked for while sync was off:
					// now that we're logged in (api_key is set), start it. beginNuzlockeLock
					// handles the eligibility check + verification handshake from here.
					if (pendingCompetitiveLock)
					{
						pendingCompetitiveLock = false;
						beginNuzlockeLock();
					}
				}
				hydrateFromLoginResponse(resp);
				if (resp == null || !resp.isSuccess())
				{
					// Offline / error: no server roll is coming. Resolve the restore so
					// loadActiveTasks stops deferring and reconstructs from the local roll
					// (or backfills) instead of leaving the account task-less.
					serverRollRestoreResolved = true;
					clientThread.invokeLater(this::loadActiveTasks);
				}
				maybeStartVerification(resp);
			})
			.exceptionally(e ->
			{
				// Expected when the server is unreachable or sync is mid-toggle; not an error.
				recordServerOutcome(ApiOutcome.TRANSIENT, 0);
				serverRollRestoreResolved = true;
				clientThread.invokeLater(this::loadActiveTasks);
				return null;
			});
	}

	/**
	 * Load the current account's API key into the api client before login, from the
	 * per-account RSProfile store (a settings Reset never clears it).
	 *
	 * <p>A key pasted into "Sync recovery key" is only a CANDIDATE: used for this login,
	 * and persisted by {@code onLoginResponse} only once the server confirms it
	 * (authed_by_key), so a bad paste can't overwrite a working key and brick the
	 * account. With no key the client's key is cleared so a previous account's is never
	 * reused.
	 */
	private void loadPersistedApiKey()
	{
		if (apiClient == null)
		{
			return;
		}
		pendingPastedKey = null;
		String stored = acStr("apiKey", "");
		String field = config.apiKey() == null ? "" : config.apiKey().trim();
		if (!field.isEmpty())
		{
			if (isValidApiKeyFormat(field))
			{
				// Try the pasted key on this login WITHOUT overwriting the stored one.
				// The login response tells us whether it really authenticated this
				// account; only then do we keep it (see the login callback).
				pendingPastedKey = field;
				apiClient.setPlayerApiKey(field);
				return;
			}
			// Garbage in the recovery box (typo, partial paste, stray text). Never adopt
			// it and wipe it, so it cannot override a good key or be retried every login.
			log.warn("Ignored invalid recovery key");
			configManager.setConfiguration(CONFIG_GROUP, "apiKey", "");
			addPluginChatMessage("That sync key isn't valid, so it was ignored. Your saved key is unchanged.");
		}
		if (stored != null && !stored.isEmpty())
		{
			apiClient.setPlayerApiKey(stored);
		}
		else
		{
			// No key for this account. apiClient is a singleton reused across account
			// switches, so clear any previous key or this account authenticates as them.
			// The server then name-authenticates.
			apiClient.setPlayerApiKey(null);
		}
	}

	/**
	 * Whether a string is shaped like a ChunkBlazer account key (a canonical UUID, which is
	 * what the server issues). A cheap format gate so a mistyped or partial paste is rejected
	 * before it can be tried, let alone stored.
	 */
	private static boolean isValidApiKeyFormat(String s)
	{
		return s != null && s.trim().matches(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	}

	/**
	 * Point the api client back at this account's stored key (or null if it has none). Undoes
	 * a pasted candidate the server did not accept, so the stored key is never lost to a bad
	 * paste.
	 */
	private void restoreStoredApiKey()
	{
		if (apiClient == null)
		{
			return;
		}
		String stored = acStr("apiKey", "");
		apiClient.setPlayerApiKey(stored == null || stored.isEmpty() ? null : stored);
	}

	/**
	 * Persist the account's API key in the per-account RSProfile store only. Never mirror
	 * it into the shared config.apiKey() field: that global slot leaks it to the next
	 * account.
	 */
	void persistApiKey(String key, String accountId)
	{
		if (key == null || key.isEmpty())
		{
			return;
		}
		if (!isAccountStateAvailable())
		{
			// The server sends a key only on an account's first login, so dropping it here
			// strands the account: every later session's sync is refused (HevansBlazer).
			// Hold it until this account's store is ready; onGameTick retries.
			if (!key.equals(unsavedApiKey))
			{
				log.warn("[CHUNKBLAZER] account settings not ready; holding the new sync key until they are");
			}
			unsavedApiKey = key;
			unsavedApiKeyAccount = accountId;
			return;
		}
		if (!key.equals(acStr("apiKey", "")))
		{
			setAccountState("apiKey", key);
			flushConfigToDisk();
		}
		unsavedApiKey = null;
		unsavedApiKeyAccount = null;
	}

	/**
	 * Save a held sync key once account settings are ready, but only into the account it
	 * was issued to. Saving it into whichever account is logged in now would hand one
	 * account's key to another (the cross-account leak), so a key whose account has gone
	 * is dropped instead.
	 */
	void retryUnsavedApiKey(String currentAccountId)
	{
		String key = unsavedApiKey;
		if (key == null || !isAccountStateAvailable())
		{
			return;
		}
		String owner = unsavedApiKeyAccount;
		if (owner == null || !owner.equals(currentAccountId))
		{
			log.warn("Dropped held sync key: account logged out");
			unsavedApiKey = null;
			unsavedApiKeyAccount = null;
			return;
		}
		persistApiKey(key, owner);
	}

	/**
	 * Wipe every per-account value for the logged-in account so it re-restores from the
	 * server on the next fresh start. Repairs contaminated local state a RuneLite profile
	 * switch can't clear. RSProfile-scoped: other accounts and the SERVER record are
	 * untouched. The api key goes too, so a stale logout sync can't push the cleared data
	 * back. In-memory state only rebuilds on a fresh plugin start, so callers must tell
	 * the player to restart the client and log back in.
	 */
	/**
	 * Why resetting this account's local data now would erase progress the server doesn't
	 * have, or null when safe. A reset restores from the server, so syncing must work and
	 * have just succeeded.
	 */
	String resetBlockedReason(long nowMs)
	{
		if (!isServerSyncEnabled())
		{
			return "Server sync is off, so this account's progress only exists on this computer. "
				+ "Resetting would erase it for good.";
		}
		if (syncRejectedKey != null || apiClient == null || apiClient.currentApiKey() == null)
		{
			return "This account isn't syncing right now, so your recent progress hasn't reached the "
				+ "server. Resetting would erase it. Relog to reconnect, or paste this account's key "
				+ "into Sync recovery key in the ChunkBlazer settings, then try again.";
		}
		if (lastSyncSuccessMs == 0 || nowMs - lastSyncSuccessMs > RESET_SYNC_FRESHNESS_MS)
		{
			return "This account hasn't synced in the last couple of minutes, so the server may be "
				+ "missing your recent progress. Stay logged in for a minute so it can sync, then try again.";
		}
		return null;
	}

	public void resetAccountLocalData()
	{
		if (configManager == null || !isAccountStateAvailable())
		{
			return;
		}
		for (String key : MIGRATION_KEYS)
		{
			configManager.unsetRSProfileConfiguration(CONFIG_GROUP, key);
		}
		configManager.unsetRSProfileConfiguration(CONFIG_GROUP, "apiKey");
		configManager.setConfiguration(CONFIG_GROUP, "apiKey", "");
		unsavedApiKey = null;
		unsavedApiKeyAccount = null;
		if (apiClient != null)
		{
			apiClient.setPlayerApiKey(null);
		}
		serverLoginDone = false;
		serverStateMerged = false;
		serverRollRestoreResolved = false;
	}

	/**
	 * The current account's sync key, read from the authoritative per-account store so it works
	 * even right after a settings Reset (Reset clears the visible field but not the RSProfile
	 * copy). Lets the panel reveal and copy it on request. Empty until sync is on and login runs.
	 */
	public String getSyncRecoveryKey()
	{
		String key = acStr("apiKey", "");
		if ((key == null || key.isEmpty()) && apiClient != null && apiClient.getPlayerApiKey() != null)
		{
			key = apiClient.getPlayerApiKey();
		}
		return key == null ? "" : key;
	}

	/**
	 * Chat hook with two jobs: tag fellow ChunkBlazer players' names with our
	 * chat icon, and watch for the local player typing the verification phrase.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Run the verification handshake check (no-ops unless a nonce is outstanding).
		handleVerificationChat(event);
		handleBossCompletionChat(event);
	}

	/**
	 * Boss NPC id to boss key, for bosses detected by the NPC's DEATH rather than a
	 * kill-count chat line; some (Bryophyta) print none. recordBossCompletion is
	 * once-per-boss, so two signals never double-grant. Raids stay chat-only: their
	 * bosses despawn. Built from the catalog's {@code boss_npc_ids} (see
	 * {@link #rebuildBossNpcKeys()}), so a new world boss needs no plugin change.
	 */
	private volatile Map<Integer, String> bossNpcKeys = java.util.Collections.emptyMap();

	// Bosses the LOCAL player is damaging right now — so an NPC-death token grant is
	// "your kill", matching the chat path's safety (a teammate's kill can't mint yours).
	private final BossEngagement bossEngagement = new BossEngagement();

	/** Rebuild the npc-id -> boss-key lookup from the loaded chunks' boss_npc_ids. */
	private void rebuildBossNpcKeys()
	{
		Map<Integer, String> m = new HashMap<>();
		for (NuzlockeChunk chunk : new HashSet<>(chunksByRegionId.values()))
		{
			if (chunk == null || chunk.getBossNpcIds() == null)
			{
				continue;
			}
			for (Map.Entry<String, List<Integer>> e : chunk.getBossNpcIds().entrySet())
			{
				if (e.getValue() == null)
				{
					continue;
				}
				for (Integer id : e.getValue())
				{
					if (id != null)
					{
						m.put(id, e.getKey());
					}
				}
			}
		}
		bossNpcKeys = m;
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!(event.getActor() instanceof NPC) || event.getHitsplat() == null)
		{
			return;
		}
		// isMine() rules out other players' hits AND the boss's own heal splats.
		bossEngagement.onHitsplat(bossNpcKeys.get(((NPC) event.getActor()).getId()),
			event.getHitsplat().isMine(), event.getHitsplat().getAmount());
	}

	// --- Hardcore Ironman deaths (the site's "HC Deaths" feed) ---

	private static final String PENDING_DEATH_KEY = "pendingHcimDeath";
	private final HcimDeathWatcher hcimDeathWatcher = new HcimDeathWatcher();
	private String deathKiller;
	private long deathKillerAt;

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == VarbitID.IRONMAN && hcimDeathWatcher.onAccountType(event.getValue()))
		{
			onHardcoreLost();
		}
	}

	private void onHardcoreLost()
	{
		Player me = client.getLocalPlayer();
		// The status can drop before or after the death animation, so take whichever killer is fresh.
		String killer = System.currentTimeMillis() - deathKillerAt < 30_000 ? deathKiller : findKiller();
		HcimDeathReport report = HcimDeathReport.builder()
			.regionId(me == null ? 0 : WorldPoint.fromLocalInstance(client, me.getLocalLocation()).getRegionID())
			.killer(killer)
			.totalLevel(client.getTotalLevel())
			.build();
		// Stored until the server confirms, so a dropped report retries on the next login.
		setAccountState(PENDING_DEATH_KEY, gson.toJson(report));
		sendPendingHcimDeath();
	}

	/** The NPC fighting the local player right now, or null. */
	private String findKiller()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		if (me.getInteracting() instanceof NPC)
		{
			return me.getInteracting().getName();
		}
		for (NPC npc : client.getNpcs())
		{
			if (npc.getInteracting() == me)
			{
				return npc.getName();
			}
		}
		return null;
	}

	private void sendPendingHcimDeath()
	{
		String json = acStr(PENDING_DEATH_KEY, "");
		if (json.isEmpty() || !config.apiEnabled() || apiClient == null)
		{
			return;
		}
		apiClient.reportHcimDeath(gson.fromJson(json, HcimDeathReport.class))
			.thenAccept(ok ->
			{
				if (ok)
				{
					clientThread.invoke(() -> setAccountState(PENDING_DEATH_KEY, ""));
				}
			});
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			deathKiller = findKiller();
			deathKillerAt = System.currentTimeMillis();
			bossEngagement.reset();
			return;
		}
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		reportedDeaths.add((NPC) event.getActor());
		String key = bossNpcKeys.get(((NPC) event.getActor()).getId());
		if (bossEngagement.onBossDeath(key))
		{
			recordBossCompletion(key);
		}
	}

	@Inject
	private NpcUtil npcUtil;

	// NPCs the client reported dying, so the despawn fallback never counts one twice.
	private final Set<NPC> reportedDeaths = Collections.newSetFromMap(new WeakHashMap<>());

	/**
	 * Amoxliatl's health bar never sets the dead flag, so the client sends no ActorDeath
	 * for it. RuneLite's isDying still knows it died: replay the death to our listeners.
	 */
	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		if (!reportedDeaths.remove(npc) && npcUtil.isDying(npc))
		{
			ActorDeath death = new ActorDeath(npc);
			onActorDeath(death);
			taskModuleManager.onMissedDeath(death);
		}
	}

	/**
	 * React to the Server Sync toggle while the plugin is already open. The
	 * sync-only panel controls (the sync header, "Show my sync key" and "Reset
	 * this account's sync data") are gated on sync being on, and without this
	 * their visibility only refreshed on a login — so flipping the toggle mid
	 * session left them hidden until a relog. Refresh the panel on the change.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("chunkblazer".equals(event.getGroup()) && "serverSyncEnabled".equals(event.getKey()))
		{
			// Turning sync off is choosing to play offline.
			if ("false".equals(event.getNewValue()))
			{
				configManager.setConfiguration(CONFIG_GROUP, PLAY_OFFLINE_KEY, true);
			}
			if (panel != null)
			{
				panel.updatePanel();
			}
		}
	}

	/** boss_kc_phrases.json rules, loaded on first use. */
	private String[][] bossPhrases;

	/**
	 * Watch for a boss/raid completion message and award the Boss Token via
	 * {@link #recordBossCompletion}.
	 *
	 * <p>ToA bosses despawn rather than die, so ActorDeath is unreliable; the
	 * completion-count GAMEMESSAGE is the signal. Every difficulty contains "Tombs of
	 * Amascut" and "count is", so a substring match covers them. Phrases live in
	 * boss_kc_phrases.json, checked in order (see {@link #bossKey}). See
	 * docs/BOSS-CHUNKS.md.
	 */
	private void handleBossCompletionChat(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = event.getMessage();
		if (msg == null)
		{
			return;
		}
		if (bossPhrases == null)
		{
			bossPhrases = loadBossPhrases(gson);
		}
		String key = bossKey(msg, bossPhrases);
		if (key != null)
		{
			recordBossCompletion(key);
		}
	}

	/**
	 * The boss key of the FIRST rule whose phrases all occur in the lower-cased
	 * message, or null. Each rule is its phrases followed by the key.
	 */
	static String bossKey(String msg, String[][] rules)
	{
		String plain = msg.toLowerCase();
		for (String[] rule : rules)
		{
			int i = 0;
			while (i < rule.length - 1 && plain.contains(rule[i]))
			{
				i++;
			}
			if (i == rule.length - 1)
			{
				return rule[i];
			}
		}
		return null;
	}

	/** Reads boss_kc_phrases.json. Missing or broken data just means no chat-gated tokens. */
	static String[][] loadBossPhrases(Gson gson)
	{
		try (Reader reader = new InputStreamReader(
			ChunkBlazerPlugin.class.getResourceAsStream("boss_kc_phrases.json"), StandardCharsets.UTF_8))
		{
			return gson.fromJson(reader, String[][].class);
		}
		catch (Exception e)
		{
			log.warn("Could not read boss_kc_phrases.json", e);
			return new String[0][];
		}
	}

	/**
	 * Watch for the local player typing the verification phrase in public chat.
	 * When matched, POST verify to consume the nonce and flip verified=true.
	 */
	private void handleVerificationChat(ChatMessage event)
	{
		String pending = pendingVerificationNonce;
		if (pending == null)
		{
			return;
		}
		// Only public chat — that's what carries Jagex-attributed messages.
		if (event.getType() != ChatMessageType.PUBLICCHAT && event.getType() != ChatMessageType.MODCHAT)
		{
			return;
		}
		// Chat-event names can carry icon tags and use non-breaking spaces;
		// the local-player name does not. Normalize both with Text.standardize
		// (strips tags, swaps  ->space, lowercases) before comparing.
		String localName = getPlayerName();
		String eventName = event.getName();
		if (localName == null || eventName == null
			|| !Text.standardize(localName).equals(Text.standardize(eventName)))
		{
			return;
		}
		// Just look for the nonce anywhere in the message. The nonce is 6
		// digits (~1M keyspace) so casual chat won't false-positive, and
		// it sidesteps the entire "OSRS auto-capitalizes the first letter"
		// + "font lowercase-n vs uppercase-N" mess we had with a word prefix.
		String msg = event.getMessage();
		if (msg == null || !msg.contains(pending))
		{
			return;
		}

		// Consume locally first so we don't double-fire if the server is slow.
		pendingVerificationNonce = null;
		apiClient.verify(pending)
			.thenAccept(resp ->
			{
				if (resp != null && resp.isVerified())
				{
					panel.hideVerificationPrompt();
					// If this handshake was the final step of a Full Nuzlocke
					// lock, commit it now (the server re-validates the snapshot
					// and the verified flag before accepting).
					EligibilitySnapshot snap = pendingNuzlockeSnapshot;
					if (snap != null)
					{
						pendingNuzlockeSnapshot = null;
						addPluginChatMessage("Account verified! Locking in Competitive...");
						commitModeLock(GameMode.NUZLOCKE, snap);
					}
					else
					{
						// Players read "all set" as done and never picked a mode, so point them at it,
						// and open the ChunkBlazer panel so the mode picker is already in front of them.
						clientThread.invoke(() ->
						{
							boolean locked = isModeLocked();
							addPluginChatMessage(locked ? "Account verified! You're all set." : "Account verified! " + SELECT_MODE_MESSAGE);
							if (!locked && navButton != null)
							{
								javax.swing.SwingUtilities.invokeLater(() -> clientToolbar.openPanel(navButton));
							}
						});
					}
				}
				else
				{
					log.warn("Verification rejected: {}", resp != null ? resp.getMessage() : "null response");
					addPluginChatMessage("That code didn't work - it may have expired. Issuing a fresh one...");
					// Likely an expired code. Issue a new one so the player can
					// retry. Use the Nuzlocke-aware kickoff if a lock is pending.
					if (pendingNuzlockeSnapshot != null)
					{
						startNuzlockeVerification();
					}
					else
					{
						requestAndShowVerification();
					}
				}
			});
	}

	/**
	 * If the server's login response says the player isn't verified yet, kick
	 * off the chat-handshake flow and tell them what to type in chat.
	 */
	private void maybeStartVerification(PlayerLoginResponse response)
	{
		if (response == null || !response.isSuccess())
		{
			return;
		}
		PlayerLoginResponse.PlayerData pdata = response.getPlayer();
		if (pdata == null || pdata.isVerified())
		{
			// Already verified — make sure no stale prompt lingers in the panel.
			panel.hideVerificationPrompt();
			return;
		}
		requestAndShowVerification();
	}

	/**
	 * Ask the server for a verification code, then surface it in both the chat
	 * box and a persistent banner in the side panel. Called on login when the
	 * account is unverified, and again if a verify attempt fails (expired code)
	 * so the player always has a live code to type.
	 */
	private void requestAndShowVerification()
	{
		apiClient.verifyStart()
			.thenAccept(start ->
			{
				if (start == null || start.isAlreadyVerified())
				{
					return; // race: server says we got verified between calls
				}
				if (start.getNonce() == null || start.getChatPhrase() == null)
				{
					return; // offline / failed response
				}
				String nonce = start.getNonce();
				pendingVerificationNonce = nonce;
				addPluginChatMessage("Type " + nonce
					+ " in public chat and hit Enter to verify your ChunkBlazer account.");
				panel.showVerificationPrompt(nonce);
			});
	}

	/**
	 * Post a chat-box message in the player's UI so they don't have to alt-tab
	 * to the side panel to see verification prompts.
	 */
	private void addPluginChatMessage(String message)
	{
		clientThread.invoke(() ->
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"[ChunkBlazer] " + message, null);
		});
	}

	// False until the server's record for this session has been merged into local config.
	// Sync is DESTRUCTIVE and client-authoritative, so pushing before the merge
	// overwrites the server with half-bootstrapped state. Reset on logout.
	private volatile boolean serverStateMerged;

	// True once this login's server-roll restore has RESOLVED: restored or given up
	// (offline / server has no roll). Until then, with sync on, loadActiveTasks must NOT
	// reconstruct a roll for an empty region, or it races the restore and re-rolls the
	// account. Reset on logout.
	private volatile boolean serverRollRestoreResolved;

	// Profile-level, like the sync toggle: set when the player picks Play offline or turns sync off.
	private static final String PLAY_OFFLINE_KEY = "playOffline";

	// One "choose sync or offline" chat nudge per session.
	private boolean syncChoiceNoticeShown;

	// One "select your game mode" chat reminder per session while no mode is locked.
	private boolean modeChoiceNoticeShown;
	static final String SELECT_MODE_MESSAGE = "Please select your game mode in the ChunkBlazer panel! "
		+ "Competitive mode requires a new ironman account (Group included) with combat level 9 or lower and no skill above level 3.";

	/**
	 * Whether an unlocked region with no roll may be rolled now. Needs sync on with this
	 * login's restore resolved, or the player choosing to play offline; earlier would
	 * deal a returning player a fresh Lumbridge. An existing roll means they've been
	 * playing offline, which counts as the choice.
	 */
	boolean canRollMissing()
	{
		if (config.apiEnabled())
		{
			return serverRollRestoreResolved;
		}
		return isPlayOffline() || !acStr("regionRolledTasks", "").isEmpty();
	}

	public boolean isPlayOffline()
	{
		return "true".equals(configManager.getConfiguration(CONFIG_GROUP, PLAY_OFFLINE_KEY));
	}

	/** From the panel's sync prompt: play without sync, and deal the starting tasks that waited on it. */
	public void choosePlayOffline()
	{
		configManager.setConfiguration(CONFIG_GROUP, PLAY_OFFLINE_KEY, true);
		clientThread.invoke(() ->
		{
			loadActiveTasks();
			if (panel != null)
			{
				panel.updatePanel();
			}
		});
	}

	private static final String CONFIG_GROUP = "chunkblazer";

	// Every per-account key copied from the legacy profile-global store into RSProfile,
	// once, by its owning account (see migrateLegacyGlobalStateToRSProfile). Also the full
	// set of keys the RSProfile store holds. apiKey is excluded on purpose (a user-typed
	// @ConfigItem, profile-scoped by construction).
	private static final String[] MIGRATION_KEYS = {
		"unlockedChunks", "completedTasks", "assignedTasks", "regionRolledTasks",
		"currentTaskId", "currentTaskQuantity", "currentTaskProgress",
		"totalPoints", "pointsSpent", "spendReset", "bossTokens", "taskProgressData",
		"progressionBaseline", "bossCompletions",
		"unrevealedTasks", "gameMode", "accountModeHash", "rollVersion",
	};

	// --- Per-account state accessors ---------------------------------------

	/**
	 * Whether RuneLite can currently tell us WHICH account is playing.
	 * {@code setRSProfileConfiguration} SILENTLY DISCARDS writes while
	 * {@code getRSProfileKey()} is null.
	 *
	 * <p>It goes null on LOGOUT too, driven by
	 * {@code AccountHashChanged}/{@code WorldChanged}, unordered against our
	 * {@code GameStateChanged} handling. Anything reading per-account state on the way
	 * out (notably the destructive logout sync) must not assume it is available.
	 */
	private boolean isAccountStateAvailable()
	{
		return configManager != null && configManager.getRSProfileKey() != null;
	}

	/**
	 * Read one per-account value.
	 *
	 * <p>Still profile-scoped storage at this stage — the point of routing every
	 * caller through here first is that switching the backing store to
	 * {@code getRSProfileConfiguration} becomes a one-line change in this method
	 * rather than an edit to ~50 scattered call sites.
	 */
	private String getAccountState(String key)
	{
		return configManager.getRSProfileConfiguration(CONFIG_GROUP, key);
	}

	/**
	 * Typed per-account reads via {@link #getAccountState}. With no active account the
	 * read is null and yields the default: a read before login must never be mistaken for
	 * real, empty data.
	 */
	private String acStr(String key, String def)
	{
		String v = getAccountState(key);
		return v != null ? v : def;
	}

	private int acInt(String key, int def)
	{
		String v = getAccountState(key);
		if (v == null || v.isEmpty())
		{
			return def;
		}
		try
		{
			return Integer.parseInt(v.trim());
		}
		catch (NumberFormatException e)
		{
			return def;
		}
	}

	/** Current per-account game mode, read from the RSProfile store (default Casual). */
	private GameMode acGameMode()
	{
		String v = getAccountState("gameMode");
		if (v == null || v.isEmpty())
		{
			return GameMode.CASUAL;
		}
		try
		{
			return GameMode.valueOf(v);
		}
		catch (IllegalArgumentException e)
		{
			return GameMode.CASUAL;
		}
	}

	/**
	 * Raw stored value of the pending (unrevealed) task list. The card overlay reads this
	 * for cheap change-detection; exposed so that read goes through the per-account
	 * abstraction rather than the global typed accessor.
	 */
	public String getUnrevealedTasksRaw()
	{
		return acStr("unrevealedTasks", "");
	}

	/**
	 * Whether {@code unlockedChunks} actually EXISTS in stored config, rather than coming
	 * from {@link ChunkBlazerConfig#unlockedChunks()}'s default of {@code "12850"}.
	 *
	 * <p>The key must be ON DISK: the standalone GPU addon reads it raw across the repo
	 * boundary, gets {@code null} if absent, and greys the entire world including
	 * Lumbridge. {@link #mergeUnlockedRegionsFromServer} writes nothing for an account
	 * with no server regions, so {@link #ensureStartingChunkUnlocked} must.
	 */
	private boolean isUnlockedChunksPersisted()
	{
		String raw = getAccountState("unlockedChunks");
		return raw != null && !raw.trim().isEmpty();
	}

	/**
	 * Write one per-account value into the RSProfile store. Refused when no account is
	 * active, since {@code setRSProfileConfiguration} would silently drop it. Gate the
	 * bootstrap on {@link #isAccountStateAvailable()}.
	 */
	private void setAccountState(String key, Object value)
	{
		if (!isAccountStateAvailable())
		{
			log.warn("Dropped per-account write '{}': no RS profile", key);
			return;
		}
		// Skip no-op writes: a single completion can re-run the same save path
		// several times per tick with identical data. Rewriting the stored value
		// fires a redundant ConfigChanged and disk write for nothing.
		String newVal = value == null ? null : String.valueOf(value);
		if (newVal != null && newVal.equals(getAccountState(key)))
		{
			return;
		}
		configManager.setRSProfileConfiguration(CONFIG_GROUP, key, value);
	}

	/**
	 * One-time move of a legacy account's progress from the profile-global store into its
	 * RSProfile store, from {@link #onRuneScapeProfileChanged}. See
	 * docs/RSPROFILE-MIGRATION-PLAN.md section 4.
	 *
	 * <p>The destination's PRESENCE is the completion marker, never a flag that could be
	 * set without the data moving. Only a blob this account OWNS migrates
	 * (accountModeHash / accountStateOwner tag). Copy only: the global originals stay as
	 * the sole rollback.
	 */
	private void migrateLegacyGlobalStateToRSProfile()
	{
		if (!isAccountStateAvailable())
		{
			return;
		}
		// (1) Destination already populated: this account migrated already, or was born
		// under RSProfile. Never run again.
		if (configManager.getRSProfileConfiguration(CONFIG_GROUP, "unlockedChunks") != null
			|| configManager.getRSProfileConfiguration(CONFIG_GROUP, "completedTasks") != null)
		{
			return;
		}
		// (2) Nothing in the legacy store: nothing to move.
		boolean anyLegacy = false;
		for (String key : MIGRATION_KEYS)
		{
			if (configManager.getConfiguration(CONFIG_GROUP, key) != null)
			{
				anyLegacy = true;
				break;
			}
		}
		if (!anyLegacy)
		{
			return;
		}
		// (3) Ownership evidence: migrate only a blob that belongs to THIS account.
		String rsn = getPlayerName();
		if (rsn == null)
		{
			return; // name not loaded yet; this fires again once it is
		}
		String owner = hashRsn(rsn);
		String modeHash = configManager.getConfiguration(CONFIG_GROUP, "accountModeHash");   // "<rsnHash>:<MODE>"
		String storedOwner = configManager.getConfiguration(CONFIG_GROUP, "accountStateOwner");
		boolean mine;
		if (modeHash != null && modeHash.contains(":"))
		{
			mine = modeHash.startsWith(owner);
		}
		else if (storedOwner != null && !storedOwner.isEmpty())
		{
			mine = owner.equals(storedOwner);
		}
		else
		{
			mine = true; // no tag: the overwhelmingly common single-account case
		}
		if (!mine)
		{
			return;
		}
		// (4) Copy into RSProfile; never clobber a value already there. Keep the originals.
		int copied = 0;
		for (String key : MIGRATION_KEYS)
		{
			String legacy = configManager.getConfiguration(CONFIG_GROUP, key);
			if (legacy != null && configManager.getRSProfileConfiguration(CONFIG_GROUP, key) == null)
			{
				configManager.setRSProfileConfiguration(CONFIG_GROUP, key, legacy);
				copied++;
			}
		}
	}

	/**
	 * Union the server's unlocked regions into local config. Order-independent
	 * and lossless in both directions — see the call site for why that matters.
	 */
	private void mergeUnlockedRegionsFromServer(PlayerLoginResponse.PlayerData pdata)
	{
		if (pdata == null || pdata.getUnlockedRegions() == null || pdata.getUnlockedRegions().isEmpty())
		{
			return;
		}

		// LinkedHashSet: stable order so an unchanged merge doesn't rewrite config.
		Set<String> merged = new LinkedHashSet<>(getUnlockedRegionIds());
		int before = merged.size();
		for (Integer region : pdata.getUnlockedRegions())
		{
			merged.add(String.valueOf(region));
		}
		if (merged.size() == before)
		{
			return;
		}

		setAccountState("unlockedChunks", String.join(",", merged));
	}

	/**
	 * Take the server's Boss Token balance at login, or a new device shows the starting 2
	 * until its first sync. Older servers send null, so the local value stays.
	 */
	/**
	 * Take the server's spend when higher: spend only grows, so the maximum is the right
	 * merge. The exception is an admin correction bumping spend_reset: a computer that
	 * hasn't seen this reset takes the server's number as it stands, since nothing else
	 * can lower a spend.
	 */
	private void adoptServerSpend(PlayerLoginResponse.PlayerData pdata)
	{
		if (pdata == null)
		{
			return;
		}
		if (pdata.getSpendReset() > acInt("spendReset", 0))
		{
			setAccountState("spendReset", pdata.getSpendReset());
			setAccountState("pointsSpent", pdata.getPointsSpent());
		}
		else if (pdata.getPointsSpent() > acInt("pointsSpent", 0))
		{
			setAccountState("pointsSpent", pdata.getPointsSpent());
		}
	}

	private void adoptServerBossTokens(PlayerLoginResponse.PlayerData pdata)
	{
		if (pdata != null && pdata.getBossTokens() != null)
		{
			setAccountState("bossTokens", pdata.getBossTokens());
		}
	}

	/** Union the server's completed tasks into local config. */
	private void mergeCompletedTasksFromServer(PlayerLoginResponse.PlayerData pdata)
	{
		// Before the union, deliberately. Derivation reads earned minus balance, and that
		// only means anything while both come from this computer. After the union it took
		// every task this computer never saw as points spent, leaving a player who moved
		// to a new computer with just what they'd earned there.
		deriveInitialPointsSpent();

		if (pdata == null || pdata.getCompletedTasks() == null || pdata.getCompletedTasks().isEmpty())
		{
			return;
		}

		Set<String> merged = new LinkedHashSet<>(getCompletedTaskIds());
		int before = merged.size();
		Set<String> server = new HashSet<>(pdata.getCompletedTasks());
		// Both sides had tasks the other lacked: this account was played on two computers
		// without sync. A task done on both counts once, which can read as lost points.
		// Quests are skipped: every login fills them in locally before this merge.
		boolean apart = merged.stream().anyMatch(t -> !t.startsWith("quest_") && !server.contains(t));
		merged.addAll(pdata.getCompletedTasks());
		if (merged.size() == before)
		{
			return;
		}
		if (apart)
		{
			addPluginChatMessage("Synced with progress from another computer. Tasks done on both only count once, "
				+ "so your points may be lower than this computer showed.");
		}

		setAccountState("completedTasks", String.join(",", merged));
	}

	/**
	 * Restore the per-region task roll and face-down card state from the server. On a
	 * fresh load of the account on this profile (account switch, reinstall, new machine)
	 * {@link #loadActiveTasks} would otherwise regenerate a roll for every unlocked
	 * region at once. Accounts that never synced a roll fall through to its bulk
	 * backfill.
	 */
	private void restoreRollStateFromServer(PlayerLoginResponse.PlayerData pdata)
	{
		if (pdata == null)
		{
			return;
		}
		// Server-authoritative reconcile: the server owns each region's committed roll,
		// so adopt every one it holds. A stale or re-rolled local must not win and push
		// back. KEEP a region only the client has (a fresh or offline unlock): the next
		// sync commits it. A region NEITHER side has is left for loadActiveTasks to roll,
		// gated on the roll-restore resolving.
		String localRoll = acStr("regionRolledTasks", "");
		String serverRoll = pdata.getRegionRolledTasks();

		Map<String, String> reconciled = parseRollBlob(serverRoll);
		for (Map.Entry<String, String> e : parseRollBlob(localRoll).entrySet())
		{
			reconciled.putIfAbsent(e.getKey(), e.getValue());
		}
		String merged = serializeRollBlob(reconciled);
		if (!merged.equals(localRoll))
		{
			setAccountState("regionRolledTasks", merged);
		}

		// Cards are client-updatable, so restore the server's set only when the local set
		// is empty. Either way, drop cards whose task isn't in the reconciled roll, or
		// flipping them activates tasks the account never rolled.
		String localCards = acStr("unrevealedTasks", "");
		String cards = localCards;
		if (cards.isEmpty())
		{
			cards = pdata.getUnrevealedTasks() == null ? "" : pdata.getUnrevealedTasks();
		}
		String kept = keepRolledCards(cards, reconciled);
		if (!kept.equals(localCards))
		{
			setAccountState("unrevealedTasks", kept);
		}

		// Track the server's roll ETag (cleared on reset via MIGRATION_KEYS) so a later
		// login can tell when the committed roll changed under us — e.g. another device
		// unlocked a chunk. The reconcile above is always safe on its own; this is the
		// change signal Kalin's model hangs on.
		setAccountState("rollVersion", pdata.getRollVersion());
	}

	/**
	 * Merge the server's backup of partial task progress into the local copy, so
	 * progress lost to a crash, power-off or a new device comes back. Must run before
	 * the post-hydrate loadActiveTasks, which re-reads progress from config.
	 */
	private void mergeTaskProgressFromServer(PlayerLoginResponse.PlayerData pdata)
	{
		String local = acStr("taskProgressData", "");
		String merged = mergeProgressBlobs(local, pdata.getTaskProgress());
		if (!merged.equals(local))
		{
			setAccountState("taskProgressData", merged);
		}
	}

	/**
	 * Combine two "taskId:progress:target" blobs: per task the higher progress wins, and
	 * the local target is kept when there is one (it's the one the player is looking at).
	 */
	static String mergeProgressBlobs(String local, String server)
	{
		Map<String, int[]> merged = new LinkedHashMap<>(parseProgressBlob(local));
		for (Map.Entry<String, int[]> e : parseProgressBlob(server).entrySet())
		{
			int[] mine = merged.get(e.getKey());
			if (mine == null)
			{
				merged.put(e.getKey(), e.getValue());
				continue;
			}
			mine[0] = Math.max(mine[0], e.getValue()[0]);
			if (mine[1] <= 0)
			{
				mine[1] = e.getValue()[1];
			}
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, int[]> e : merged.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.getKey()).append(':').append(e.getValue()[0]).append(':').append(e.getValue()[1]);
		}
		return sb.toString();
	}

	private static Map<String, int[]> parseProgressBlob(String blob)
	{
		Map<String, int[]> out = new LinkedHashMap<>();
		if (blob == null || blob.isEmpty())
		{
			return out;
		}
		for (String entry : blob.split(","))
		{
			String[] parts = entry.split(":");
			if (parts.length < 2 || parts[0].isEmpty())
			{
				continue;
			}
			try
			{
				int progress = Integer.parseInt(parts[1]);
				int target = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
				out.put(parts[0], new int[]{progress, target});
			}
			catch (NumberFormatException ignored)
			{
				// skip a malformed entry
			}
		}
		return out;
	}

	/** The cards (CSV) whose task is in some region's roll, in their original order. */
	static String keepRolledCards(String cards, Map<String, String> roll)
	{
		Set<String> rolled = new HashSet<>();
		for (String tasks : roll.values())
		{
			for (String t : tasks.split(","))
			{
				rolled.add(t.trim());
			}
		}
		Set<String> kept = new LinkedHashSet<>();
		for (String t : cards.split(","))
		{
			String id = t.trim();
			if (rolled.contains(id))
			{
				kept.add(id);
			}
		}
		return String.join(",", kept);
	}

	/**
	 * Split a roll blob ("regionId:task1,task2|regionId2:task3") into region id -> tasks-CSV,
	 * mirroring the server's merge (see rollmerge.go). Empty or delimiter-less entries skip.
	 */
	private static Map<String, String> parseRollBlob(String blob)
	{
		Map<String, String> out = new HashMap<>();
		if (blob == null || blob.isEmpty())
		{
			return out;
		}
		for (String entry : blob.split("\\|"))
		{
			if (entry.isEmpty())
			{
				continue;
			}
			int i = entry.indexOf(':');
			if (i <= 0)
			{
				continue;
			}
			out.put(entry.substring(0, i), entry.substring(i + 1));
		}
		return out;
	}

	/**
	 * Render region -> tasks back into the blob format, ordered by numeric region id
	 * (lexical fallback) so the stored roll is stable and matches the server's ordering.
	 */
	private static String serializeRollBlob(Map<String, String> regions)
	{
		if (regions.isEmpty())
		{
			return "";
		}
		List<String> ids = new ArrayList<>(regions.keySet());
		ids.sort((a, b) ->
		{
			try
			{
				return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
			}
			catch (NumberFormatException ex)
			{
				return a.compareTo(b);
			}
		});
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < ids.size(); i++)
		{
			if (i > 0)
			{
				sb.append('|');
			}
			sb.append(ids.get(i)).append(':').append(regions.get(ids.get(i)));
		}
		return sb.toString();
	}

	/**
	 * Adopts the server's mode lock when we have none (e.g. a new machine) or a
	 * different one. The server wins a mismatch: Competitive is only kept locally
	 * once the server confirms it, and Casual locks are always mirrored, so a
	 * mismatch means the mode was changed server-side on purpose (a manual fix).
	 */
	void adoptServerModeLock(PlayerLoginResponse response, String rsn)
	{
		GameMode serverMode = response.isModeLocked() ? response.getGameMode() : null;
		if (serverMode == null)
		{
			return;
		}
		boolean locked = isModeLocked();
		if (locked && getGameMode() == serverMode)
		{
			return;
		}
		setAccountState("accountModeHash", hashRsn(rsn) + ":" + serverMode.name());
		setAccountState("gameMode", serverMode);
		if (locked)
		{
			addPluginChatMessage("Your game mode was changed to " + serverMode.getName() + ".");
		}
	}

	private void hydrateFromLoginResponse(PlayerLoginResponse response)
	{
		if (response == null || !response.isSuccess())
		{
			return;
		}
		PlayerLoginResponse.PlayerData pdata = response.getPlayer();

		clientThread.invokeLater(() ->
		{
			String rsn = getPlayerName();
			if (rsn == null)
			{
				return;
			}

			// Account isolation is structural: a different account reads a different
			// RSProfile store, so there is nothing to reconcile or wipe.

			// 1. Mode lock reconciliation, both directions.
			adoptServerModeLock(response, rsn);
			if (isModeLocked() && !response.isModeLocked())
			{
				// Locked locally but not on the server: re-push so a dropped mirror can
				// catch up. "eligibility_required" means this Competitive lock was never
				// valid, so clear it and fall back to Casual instead of 400-looping every
				// login. Only that explicit verdict clears it, so a truly locked account
				// is never demoted.
				GameMode localMode = getGameMode(); // authoritative locked mode, not the raw dropdown
				if (localMode != null && config.apiEnabled() && apiClient != null)
				{
					apiClient.lockGameMode(localMode)
						.thenAccept(resp ->
						{
							if (resp != null && "eligibility_required".equals(resp.getError()))
							{
								setAccountState("accountModeHash", "");
								setAccountState("gameMode", GameMode.CASUAL);
								modeLockConfirmed = false;
								addPluginChatMessage("Your account isn't eligible for Competitive, so its lock was removed.");
							}
						});
				}
			}

			// 2 & 3. Unlocked regions and completed tasks: MERGED, never "overwrite only
			// when local is empty". The bootstrap has already written local by login
			// response time, so that rule would keep only the bootstrap and the
			// destructive sync would push it over the server record. A union can't lose
			// either side, whatever the ordering.
			mergeUnlockedRegionsFromServer(pdata);
			mergeCompletedTasksFromServer(pdata);
			restoreRollStateFromServer(pdata);
			mergeTaskProgressFromServer(pdata);
			// The server-roll restore is now resolved (either the stored roll was written
			// back, or the server had none). The loadActiveTasks() below may now reconstruct
			// a roll for any region the server genuinely had none for, without racing this.
			serverRollRestoreResolved = true;

			// 4. Points.
			//
			// Server total_points is lifetime EARNED; client totalPoints is a spendable
			// BALANCE. Never copy one into the other: that erases chunk purchases and
			// can't self-correct. Earned is recomputed from the merged tasks, spend takes
			// the higher of the two (it only grows), and the balance falls out.
			adoptServerSpend(pdata);
			adoptServerBossTokens(pdata);
			// AFTER the monotonic merge, deliberately. The corrupt figure this
			// repairs is already stored server-side, so running it any earlier
			// would just see the server's copy restored over the top of it. The
			// corrected value then syncs upward and settles the server too.
			migrateRepairImpossiblePointsSpent();
			recomputePointsBalance();

			// Rebuild the active-task list from the just-hydrated config: hydration only
			// updates config, so a fresh install or post-reset login would show an EMPTY
			// list until the plugin is toggled.
			loadActiveTasks();

			if (panel != null)
			{
				panel.updateModeDisplay();
				panel.updatePanel();
			}

			// Set LAST, and only on this path: local now contains everything the
			// server knew about, so it is finally safe to let a destructive sync
			// push local state back. Until this flips, syncToServer() no-ops.
			serverStateMerged = true;
		});
	}

	/** Per-account index into completedTasks: targets before it have been sent. */
	private static final String TARGETS_SENT_KEY = "targetsSentThrough";

	/** The next batch of rolled targets to send, or null when every one is already sent. */
	private CompletedTargets.Batch nextTargetsBatch(List<String> completed)
	{
		int sent = acInt(TARGETS_SENT_KEY, 0);
		if (completed == null || sent == completed.size())
		{
			return null; // steady state: skip parsing taskProgressData
		}
		return CompletedTargets.next(completed, sent,
			CompletedTargets.parseTargets(acStr("taskProgressData", "")));
	}

	/**
	 * Feed a login/sync result into the backoff. Key and registration refusals
	 * have their own halts, so they skip it.
	 */
	private void recordServerOutcome(ApiOutcome outcome, long retryAfterMs)
	{
		if (outcome == null)
		{
			return;
		}
		if (outcome == ApiOutcome.SUCCESS)
		{
			if (serverBackoff.recordSuccess())
			{
				addPluginChatMessage("Reconnected to the ChunkBlazer server. Your progress is syncing again.");
			}
			return;
		}
		if (outcome == ApiOutcome.AUTH_REJECTED || outcome == ApiOutcome.REGISTRATION_CLOSED)
		{
			return;
		}
		if (serverBackoff.recordFailure(outcome, retryAfterMs, System.currentTimeMillis()))
		{
			addPluginChatMessage("Can't reach the ChunkBlazer server. Your progress is saved and will sync when it's back.");
		}
	}

	/** Periodic login retry; skips an RSN refused by closed registration. Client thread. */
	private void retryServerLogin()
	{
		String rsn = getPlayerName();
		if (rsn != null && rsn.equals(registrationClosedRsn))
		{
			return;
		}
		loginToServer();
	}

	/**
	 * Periodic save-state sync. Runs on the executor thread; client state is
	 * read by hopping to the client thread first.
	 */
	private void syncToServer()
	{
		if (!config.apiEnabled() || !countedWorld)
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!serverBackoff.isReady(System.currentTimeMillis()))
		{
			return;
		}
		if (!serverLoginDone)
		{
			// Self-heal a server login that never completed (plugin enabled while already
			// in-game, or the server was down on the first attempt) rather than waiting for a
			// fresh LOGGED_IN event. loginToServer reads the player name, so hop to the client
			// thread; it no-ops if the name isn't ready yet and this retries next interval.
			clientThread.invoke(this::retryServerLogin);
			return;
		}
		if (!isAccountStateAvailable())
		{
			// No RS profile active: per-account reads return null, so a sync would push
			// empty state over the server record. Refuse outright (RSPROFILE plan section 3).
			return;
		}
		if (!serverStateMerged)
		{
			// The login response hasn't been merged yet. Syncing now would push
			// bootstrap-only local state (1 chunk, backfilled quests) over the
			// account's real server record. Skip; the next tick retries.
			return;
		}
		String rejectedKey = syncRejectedKey;
		if (rejectedKey != null)
		{
			if (rejectedKey.equals(apiClient.currentApiKey()))
			{
				// A newly pasted key gets validated by re-login.
				String pasted = config.apiKey() == null ? "" : config.apiKey().trim();
				if (!pasted.isEmpty() && !pasted.equals(rejectedKey))
				{
					serverLoginDone = false;
				}
				return;
			}
			syncRejectedKey = null;
		}
		clientThread.invoke(() ->
		{
			PlayerSyncRequest req = buildSyncRequest();
			if (req == null)
			{
				return;
			}
			boolean declaredReset = req.isIntentionalReset();
			String sentKey = apiClient.currentApiKey();
			CompletedTargets.Batch targets = nextTargetsBatch(req.getCompletedTasks());
			if (targets != null)
			{
				req.setCompletedTargets(targets.targets);
			}
			apiClient.syncPlayerState(req)
				.thenAccept(resp ->
				{
					ApiOutcome outcome = resp == null ? ApiOutcome.TRANSIENT : resp.getOutcome();
					recordServerOutcome(outcome, resp == null ? 0 : resp.getRetryAfterMs());
					if (outcome == ApiOutcome.AUTH_REJECTED && syncRejectedKey == null)
					{
						syncRejectedKey = sentKey;
						log.warn("[CHUNKBLAZER] server rejected this account's sync key; sync paused "
							+ "until a different key is supplied");
						addPluginChatMessage("The server rejected this account's sync key, so syncing is paused. Progress is saved locally. "
							+ "Relog, or paste your key into Sync recovery key in the ChunkBlazer settings.");
					}
					// Only retire the reset declaration once the server has actually
					// accepted it. Clearing it on send would strand a reset behind one
					// dropped request: the retry would arrive without the flag, be
					// refused as a destructive drop, and the next login's union would
					// restore everything the player asked to clear.
					if (resp != null && resp.isSuccess())
					{
						lastSyncSuccessMs = System.currentTimeMillis();
					}
					if (resp != null && resp.isSuccess() && targets != null)
					{
						setAccountState(TARGETS_SENT_KEY, targets.sentThrough);
					}
					if (resp != null && resp.isSuccess() && declaredReset)
					{
						pendingIntentionalReset = false;
					}
					// Adopt the server's authoritative Boss Token balance, recomputed
					// from its monotonic records. Recovers the balance after a reinstall
					// or profile switch and heals client/server divergence.
					if (resp != null && resp.isSuccess() && resp.getServerBossTokens() != null)
					{
						setAccountState("bossTokens", resp.getServerBossTokens());
					}
				});
		});
	}

	/**
	 * Set when the player deliberately destroys their own progress, and carried until a
	 * sync carrying it succeeds. Sticky because that sync comes minutes after the reset,
	 * and without this the server can't tell a deliberate reset from the client bugs its
	 * drop guard stops.
	 */
	private volatile boolean pendingIntentionalReset;

	/**
	 * Declare that progress about to be destroyed is being destroyed ON PURPOSE.
	 *
	 * <p>Call this from operations the player asked for, never to force a refused
	 * sync through — a refusal means the guard is doing its job.
	 */
	private void declareIntentionalReset(String reason)
	{
		pendingIntentionalReset = true;
		log.warn("Reset declared ({}): next sync may drop progress", reason);
	}

	/**
	 * Snapshot current local plugin/client state into a sync request.
	 * Must run on the client thread (reads client.getLocalPlayer()).
	 * Returns null if the snapshot can't be built (e.g. no local player yet).
	 */
	private PlayerSyncRequest buildSyncRequest()
	{
		String rsn = getPlayerName();
		if (rsn == null)
		{
			return null;
		}
		Player local = client.getLocalPlayer();

		List<Integer> unlocked = new ArrayList<>();
		for (String id : getUnlockedRegionIds())
		{
			try
			{
				unlocked.add(Integer.parseInt(id.trim()));
			}
			catch (NumberFormatException ignored)
			{
			}
		}

		List<String> completed = getCompletedTaskIdsOrdered();

		GameMode mode = getGameMode(); // authoritative: locked mode wins over the raw dropdown
		String modeName = (mode != null && isModeLocked()) ? mode.name() : null;

		return PlayerSyncRequest.builder()
			.playerHash(hashRsn(rsn))
			.accountHash(hashedAccountId())
			.displayName(rsn)
			.accountType(String.valueOf(client.getVarbitValue(VarbitID.IRONMAN)))
			.gameMode(modeName)
			.combatLevel(local != null ? local.getCombatLevel() : 0)
			.totalLevel(client.getTotalLevel())
			.currentRegionId(lastRegionId)
			.unlockedRegions(unlocked)
			.activeTaskId(activeTask != null ? activeTask.getTaskId() : null)
			.activeTaskProgress(0)
			// clientPoints is the BALANCE, kept for the Tier-0 mismatch check;
			// pointsSpent is what actually needs preserving server-side, since
			// the balance is derived and the server has no concept of spending.
			.clientPoints(acInt("totalPoints", 0))
			.pointsSpent(acInt("pointsSpent", 0))
			.completedTasks(completed)
			.completedOrdered(true)
			.bossCompletions(new ArrayList<>(getCompletedBossKeys()))
			// The roll + reveal state, verbatim, so it survives a profile switch or
			// reinstall instead of being regenerated wholesale on the next login.
			.regionRolledTasks(acStr("regionRolledTasks", ""))
			.unrevealedTasks(acStr("unrevealedTasks", ""))
			.taskProgress(acStr("taskProgressData", ""))
			.intentionalReset(pendingIntentionalReset)
			.timestamp(System.currentTimeMillis())
			.clientVersion(VERSION)
			.build();
	}

	// --- Region Methods ---

	public int getCurrentRegionId()
	{
		// Cached value updated on the client thread in onGameTick — getWorldLocation() asserts client-thread, panel callers run on EDT.
		return lastRegionId;
	}

	public String getCurrentRegionName()
	{
		int regionId = getCurrentRegionId();
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk != null)
		{
			return chunk.getName();
		}
		return "Unknown";
	}

	/** A fresh, mutable copy of the unlocked region IDs. */
	public Set<String> getUnlockedRegionIds()
	{
		return new HashSet<>(unlockedRegionIdsView());
	}

	// Parsed unlockedChunks, rebuilt only when the stored string changes. Overlays
	// read it every frame, so re-splitting the CSV on each call was wasted work.
	private final Object unlockedViewLock = new Object();
	private String unlockedViewRaw;
	private Set<String> unlockedView = Collections.emptySet();

	/** Read-only unlocked region IDs, cached until unlockedChunks changes. */
	Set<String> unlockedRegionIdsView()
	{
		String raw = acStr("unlockedChunks", "12850");
		synchronized (unlockedViewLock)
		{
			if (!java.util.Objects.equals(raw, unlockedViewRaw))
			{
				unlockedView = Collections.unmodifiableSet(csvSet(raw));
				unlockedViewRaw = raw;
			}
			return unlockedView;
		}
	}

	public boolean isRegionUnlocked(int regionId)
	{
		// Dungeon / non-overworld regions are ALWAYS freely accessible — never
		// locked or greyed, no unlock needed. They aren't part of the chunk
		// challenge. This single check makes them read as "unlocked" everywhere
		// (greyscale, prompts, map borders).
		if (isFreeRegion(regionId))
		{
			return true;
		}
		Set<String> unlocked = unlockedRegionIdsView();
		if (unlocked.contains(String.valueOf(regionId)))
		{
			return true;
		}
		// Multi-region chunk (e.g. a surface area + its dungeon, which have
		// different region IDs): the whole chunk counts as unlocked if ANY of its
		// regions is unlocked. Keeps surface and dungeon in lockstep, even for
		// legacy / hydrated / hand-entered unlock lists that only hold one of them.
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk != null && chunk.getRegionIds() != null)
		{
			for (Integer r : chunk.getRegionIds())
			{
				if (unlocked.contains(String.valueOf(r)))
				{
					return true;
				}
			}
		}
		return false;
	}

	// --- Task Methods ---


	private void loadOrAssignTask()
	{
		// Load all active tasks for the current region
		loadActiveTasks();
	}

	/**
	 * Load and activate all rolled tasks for unlocked regions.
	 * Every rolled task per region is active simultaneously.
	 */
	private void loadActiveTasks()
	{
		// Nothing may be loaded, and above all WRITTEN, until RuneLite can say whose
		// account this is, or progress gets attributed to whoever logs in next. In-memory
		// state is cleared so the previous account's tasks can't linger in the panel.
		// Same for an account with no mode: the tick that sees the mode locked calls this
		// again.
		if (!isAccountStateAvailable() || !isModeLocked())
		{
			activeTasks.clear();
			activeTask = null;
			taskModuleManager.clearTask();
			return;
		}

		// Lumbridge (12850) is unlocked with tasks rolled for EVERY account; casual's
		// chosen chunk never replaces it. Enforced on this single (re)load path so the
		// start chunk can never be locked or task-less.
		ensureStartingChunkUnlocked();
		migrateStripSeededCharterChunks();
		migrateRepairBogusProgressionBaseline();
		migrateResetStaleSoundVolume();
		ensureBossChunkTasksGranted();
		replaceRetiredTasks();

		activeTasks.clear();
		taskModuleManager.clearTask(); // Clear module state to prevent duplicates

		Set<String> completedTaskIds = getCompletedTaskIds();
		Set<String> addedTaskIds = new HashSet<>(); // Track added tasks to prevent duplicates

		// Tasks still behind a face-down card are NOT active and must not be registered
		// with any module — that is the whole point of the reveal gate. They stay in
		// regionRolledTasks (the roll is committed) and rejoin here once flipped.
		Set<String> unrevealed = new HashSet<>(getUnrevealedTaskIds());

		Set<String> unlockedRegions = getUnlockedRegionIds();

		// Satisfied tasks the load settles, batched into one completedTasks write: each
		// markTaskCompleted rebuilds the whole CSV, and a bulk backfill on a maxed
		// account can satisfy hundreds.
		Set<String> settledIds = new LinkedHashSet<>();
		int settledPoints = 0;

		for (String regionIdStr : unlockedRegions)
		{
			try
			{
				int regionId = Integer.parseInt(regionIdStr);
				Set<String> rolledTaskIds = getRolledTasksForRegion(regionId);

				// Free-list chunks have no tasks BY DESIGN; skipping them keeps the "No
				// chunk found for region N" warning meaningful.
				//
				// A region with no roll here means RECONSTRUCTION (account switch, fresh
				// install, new machine). Roll WITHOUT carding: reveal cards are for a
				// LIVE chunk unlock. Hold off while a server restore is pending: rolling
				// would race it and permanently replace the real roll.
				boolean rerollAllowed = canRollMissing();
				if (rolledTaskIds.isEmpty() && !isFreeUnlockableRegion(regionId) && rerollAllowed)
				{
					rolledTaskIds = rollTasksForRegion(regionId, false, false);
				}

				NuzlockeChunk chunk = chunksByRegionId.get(regionId);
				if (chunk != null && chunk.getTasks() != null)
				{
					for (NuzlockeTask task : chunk.getTasks())
					{
						String taskId = task.getTaskId();
						if (rolledTaskIds.contains(taskId) &&
							!completedTaskIds.contains(taskId) &&
							!unrevealed.contains(taskId) && // still face-down
							!addedTaskIds.contains(taskId) && // Prevent duplicates
							!task.isLocked())
						{
							// Initialize task
							initializeTask(task);

							// Self-heal stuck tasks where progress saved but completion never fired (e.g. throw in popup code between onProgressUpdated and onTaskCompleted). Batched — see settledIds.
							if (task.getCurrentProgress() >= task.getTargetQuantity())
							{
								completedTaskCache.put(taskId, task);
								settledIds.add(taskId);
								settledPoints += task.getBasePoints();
								addedTaskIds.add(taskId);
								continue;
							}

							activeTasks.add(task);
							addedTaskIds.add(taskId);
						}
					}
				}
			}
			catch (NumberFormatException e)
			{
				log.warn("Invalid region ID: {}", regionIdStr);
			}
		}

		// Commit every settled task in ONE completedTasks write + ONE points add,
		// rather than rewriting the whole CSV per task inside the loop. Done before
		// the carding below so the completed set is current when markTasksUnrevealed
		// runs its own completed-check.
		if (!settledIds.isEmpty())
		{
			markTasksCompleted(settledIds);
			addPoints(settledPoints);
		}

		// Register all active tasks with modules for tracking
		for (NuzlockeTask task : activeTasks)
		{
			taskModuleManager.registerActiveTask(task);
		}

		// Re-register the chunk-independent Global Tasks pool. This has to happen
		// on every load, not once at startup, because clearTask() above emptied
		// every module's active list — see registerGlobalTasks().
		registerGlobalTasks(completedTaskIds);

		// Set first task as "active" for backward compatibility
		activeTask = activeTasks.isEmpty() ? null : activeTasks.get(0);

		saveActiveTasks();
	}

	/**
	 * Initialize a task with proper quantity and progress.
	 * Target quantity is saved and loaded to prevent re-rolling random ranges.
	 */
	private void initializeTask(NuzlockeTask task)
	{
		// Load saved progress and target quantity
		int[] savedData = loadTaskProgressAndTarget(task.getTaskId());
		int savedProgress = savedData[0];
		int savedTargetQty = savedData[1];

		// Regression alarm: if in-memory progress is higher than what we're about to restore,
		// we're losing real progress (probably because a progress event hasn't been flushed
		// to config yet). Log a stack so we can find the trigger.
		int inMemoryProgress = task.getCurrentProgress();
		if (inMemoryProgress > savedProgress)
		{
			log.warn("Progress went back on '{}' ({}): {} in memory, {} saved", task.getName(), task.getTaskId(), inMemoryProgress, savedProgress, new Throwable());
		}

		int targetQty;
		if (savedTargetQty > 0)
		{
			// Use saved target quantity to prevent re-rolling
			targetQty = savedTargetQty;
			// Pin the persisted value into the per-instance roll cache so any
			// subsequent caller (e.g. ObtainModule.addActiveTask) reading
			// getRequiredQuantity() sees the same number we just restored.
			// Otherwise modules re-roll, the panel says (1/37) and the chatbox
			// says (1/18) for the same task.
			if (task.getTargetNpc() != null)
			{
				task.getTargetNpc().setRolledQuantity(savedTargetQty);
			}
			else if (task.getRequiredItems() != null && !task.getRequiredItems().isEmpty())
			{
				List<RequiredItem> requiredItems = task.getRequiredItems();
				if (requiredItems.size() == 1)
				{
					requiredItems.get(0).setRolledQuantity(savedTargetQty);
				}
				else
				{
					// Multi-item (set) task: the saved target is the SUM across items.
					// Pinning it on the first item inflates that slot (x5 for a 5-piece
					// set), letting duplicates of one piece count. Per-item quantities
					// are authored fixed values, so clear stale caches and let each
					// report its own.
					for (RequiredItem item : requiredItems)
					{
						item.clearRolledQuantity();
					}
				}
			}
			else if (task.getRequiredObjects() != null && !task.getRequiredObjects().isEmpty())
			{
				// Mirror the RequiredItem pin-back: keep the cached roll on the
				// first required_object so modules calling getRequiredQuantity()
				// see the persisted value, not a fresh random one.
				task.getRequiredObjects().get(0).setRolledQuantity(savedTargetQty);
			}
		}
		else
		{
			// First time: roll the target quantity, clearing any cached rolledQuantity
			// first. devResetAll / devResetTasks / rerollTask wipe the config but not the
			// cache on NuzlockeTask instances, so a corrupted value would survive and be
			// reused. The roll caches itself so later getRequiredQuantity() calls get the
			// same value.
			targetQty = rollTargetQuantity(task);
			// Save the rolled target quantity
			saveTaskProgress(task.getTaskId(), savedProgress, targetQty);
		}

		task.setTargetQuantity(targetQty);
		task.setCurrentProgress(savedProgress);
		task.setCompleted(false);
	}

	/**
	 * Roll a task's target quantity fresh (clearing any cached roll first) and cache it
	 * on the task's npc / item / object definition. Callers save the result.
	 */
	private int rollTargetQuantity(NuzlockeTask task)
	{
		int targetQty = 1;
		if (task.getTargetNpc() != null)
		{
			task.getTargetNpc().clearRolledQuantity();
			targetQty = task.getTargetNpc().getRequiredQuantity();
		}
		else if (task.getRequiredItems() != null && !task.getRequiredItems().isEmpty())
		{
			// Sum across all required items so a multi-item task (e.g. the
			// Forestry Set, 4 items × qty 1) shows 1/4 in the panel — same
			// total the modules use when summing per-item required. Clear
			// every item's cache, not just the first — the sum uses all of
			// them.
			int sum = 0;
			for (RequiredItem item : task.getRequiredItems())
			{
				item.clearRolledQuantity();
				sum += item.getRequiredQuantity();
			}
			targetQty = sum > 0 ? sum : 1;
		}
		else if (task.getRequiredObjects() != null && !task.getRequiredObjects().isEmpty())
		{
			// Rooftop laps / chest steals: quantity comes from the
			// required_object block (e.g. [1, 20] rolls a random target).
			// Use the first object's roll — matches the first-item pattern
			// above. RequiredObject.getRequiredQuantity caches the roll.
			task.getRequiredObjects().get(0).clearRolledQuantity();
			targetQty = task.getRequiredObjects().get(0).getRequiredQuantity();
		}
		return targetQty;
	}

	/**
	 * The amount a task asks for, deciding and saving it now if it isn't yet, so a
	 * face-down card shows the same number the task gets when it's revealed.
	 */
	public int getTaskTargetQuantity(String taskId)
	{
		int saved = loadTaskProgressAndTarget(taskId)[1];
		if (saved > 0)
		{
			return saved;
		}
		NuzlockeTask task = findTaskById(taskId);
		if (task == null)
		{
			return 1;
		}
		int target = rollTargetQuantity(task);
		saveTaskProgress(taskId, 0, target);
		return target;
	}

	private NuzlockeTask findTaskById(String taskId)
	{
		// Indexed lookup over allChunks + globalTasks (the old primary source).
		NuzlockeTask indexed = tasksById.get(taskId);
		if (indexed != null)
		{
			return indexed;
		}

		// Fallback to completed task cache
		if (completedTaskCache.containsKey(taskId))
		{
			return completedTaskCache.get(taskId);
		}

		// Fallback to active tasks
		for (NuzlockeTask task : activeTasks)
		{
			if (taskId.equals(task.getTaskId()))
			{
				return task;
			}
		}

		return null;
	}


	// --- Rolled Tasks Management ---

	// A chunk deals this many tasks when it's unlocked. The curve is sized against it
	// (dealPerChunk in the server's economy_test.go mirrors it).
	static final int TASKS_PER_CHUNK = 5;

	/**
	 * Roll TASKS_PER_CHUNK random tasks for a region using weighted selection.
	 * Tasks are globally unique - once a task is assigned anywhere, it cannot be rolled again.
	 */
	private Set<String> rollTasksForRegion(int regionId)
	{
		return rollTasksForRegion(regionId, false, true);
	}

	private Set<String> rollTasksForRegion(int regionId, boolean rollAll)
	{
		return rollTasksForRegion(regionId, rollAll, true);
	}

	/**
	 * Roll tasks for a region. With {@code rollAll} (boss chunks) EVERY eligible task is
	 * granted and made active immediately, not parked behind reveal cards.
	 *
	 * <p>{@code parkBehindCards} hides fresh rolls behind face-down cards. FALSE only for
	 * the bulk backfill in {@link #loadActiveTasks}, which handles each task itself;
	 * carding there would dump hundreds of cards on an account switch.
	 */
	private Set<String> rollTasksForRegion(int regionId, boolean rollAll, boolean parkBehindCards)
	{
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk == null)
		{
			// Log each missing region once per client run — see warnedMissingChunkRegions.
			if (warnedMissingChunkRegions.add(regionId))
			{
				// Expected for any region outside the overworld chunk grid — underground
				// areas, dungeons, instances. They carry no tasks and are not lockable, so
				// this is normal, not an error. Debug, deduped per region for a clean log.
			}
			return new HashSet<>();
		}
		if (chunk.getTasks() == null || chunk.getTasks().isEmpty())
		{
			log.warn("Chunk {} ({}) has no tasks", regionId, chunk.getName());
			return new HashSet<>();
		}

		// Get tasks already rolled for this region
		Set<String> alreadyRolledForThisRegion = getRolledTasksForRegion(regionId);

		// Tasks already out in ANY region (active or face-down) can't be rolled again:
		// many tasks share an id across chunks, and a second roll merges into the
		// existing task, leaving that chunk a task short forever. Completed tasks are
		// excluded below.
		Set<String> globallyAssignedTasks = new HashSet<>(getAssignedTaskIds());
		for (String tasks : parseRollBlob(acStr("regionRolledTasks", "")).values())
		{
			for (String id : tasks.split(","))
			{
				globallyAssignedTasks.add(id.trim());
			}
		}

		// Get all completed task IDs - completed tasks must never re-roll into the active list
		Set<String> completedTaskIds = getCompletedTaskIds();

		// Filter available tasks: not locked, not already rolled for this region,
		// not globally assigned, and not previously completed.
		List<NuzlockeTask> availableTasks = chunk.getTasks().stream()
			.filter(t -> !t.isLocked())
			.filter(t -> !alreadyRolledForThisRegion.contains(t.getTaskId()))
			.filter(t -> !globallyAssignedTasks.contains(t.getTaskId()))
			.filter(t -> !completedTaskIds.contains(t.getTaskId()))
			.collect(Collectors.toList());

		if (availableTasks.isEmpty())
		{
			return new HashSet<>();
		}

		Set<String> rolledIds = new HashSet<>();

		// Boss chunks grant EVERY eligible task at once — no weighted subset.
		if (rollAll)
		{
			for (NuzlockeTask t : availableTasks)
			{
				rolledIds.add(t.getTaskId());
			}
			saveRolledTasksForRegion(regionId, rolledIds);
			// Boss chunks present their whole stack as a card "pack" the player opens
			// one at a time (see TaskCardOverlay). This also spreads completions out
			// (each card activates its task only when flipped) instead of settling a
			// maxed account's entire satisfied stack at once. markTasksUnrevealed
			// no-ops when the player has cards disabled — then they go straight active.
			if (parkBehindCards)
			{
				markTasksUnrevealed(rolledIds);
			}
			return rolledIds;
		}

		// Deal TASKS_PER_CHUNK, or all if fewer are available
		int numToRoll = Math.min(TASKS_PER_CHUNK, availableTasks.size());

		// Use weighted random selection based on assignment_weight
		List<NuzlockeTask> remainingTasks = new ArrayList<>(availableTasks);

		for (int i = 0; i < numToRoll && !remainingTasks.isEmpty(); i++)
		{
			NuzlockeTask selected = selectWeightedRandom(remainingTasks);
			if (selected != null)
			{
				rolledIds.add(selected.getTaskId());
				remainingTasks.remove(selected);

				// Do NOT mark rolled tasks "assigned" here: the panel treats assigned as
				// done/unavailable, so fresh rolls would render greyed with a "(0/N)"
				// header.
			}
		}

		// Save to config. The roll is committed here and never revisited — see
		// markTasksUnrevealed for why that has to happen BEFORE the reveal gate.
		saveRolledTasksForRegion(regionId, rolledIds);

		// Park them behind cards. loadActiveTasks skips anything still pending, so
		// these do not become active until the player flips them. Skipped for the
		// bulk backfill, which cards per-task itself (see parkBehindCards).
		if (parkBehindCards)
		{
			markTasksUnrevealed(rolledIds);
		}

		return rolledIds;
	}

	/** Pick a random task, weighted by assignment_weight. */
	/**
	 * Swap any retired task ("is_unlocked": false) the player never finished for another
	 * from the same chunk, or drop it if the chunk has nothing left. The server accepts
	 * exactly this swap into a committed roll (acceptRetiredSwap).
	 */
	private void replaceRetiredTasks()
	{
		if (!canRollMissing())
		{
			return; // a server roll may still be on its way
		}
		Map<String, String> roll = parseRollBlob(acStr("regionRolledTasks", ""));
		Set<String> completed = getCompletedTaskIds();
		Set<String> rolledAnywhere = new HashSet<>();
		for (String tasks : roll.values())
		{
			for (String id : tasks.split(","))
			{
				rolledAnywhere.add(id.trim());
			}
		}

		List<String> retiredIds = new ArrayList<>();
		for (Map.Entry<String, String> entry : roll.entrySet())
		{
			NuzlockeChunk chunk;
			try
			{
				chunk = chunksByRegionId.get(Integer.parseInt(entry.getKey()));
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			if (chunk == null || chunk.getTasks() == null)
			{
				continue;
			}
			List<String> ids = new ArrayList<>();
			boolean regionChanged = false;
			for (String raw : entry.getValue().split(","))
			{
				String id = raw.trim();
				NuzlockeTask task = chunk.getTasks().stream()
					.filter(t -> id.equals(t.getTaskId())).findFirst().orElse(null);
				if (task == null || !task.isLocked() || completed.contains(id))
				{
					ids.add(id);
					continue;
				}
				List<NuzlockeTask> pool = chunk.getTasks().stream()
					.filter(t -> !t.isLocked() && !completed.contains(t.getTaskId())
						&& !rolledAnywhere.contains(t.getTaskId()))
					.collect(Collectors.toList());
				NuzlockeTask pick = selectWeightedRandom(pool);
				if (pick != null)
				{
					ids.add(pick.getTaskId());
					rolledAnywhere.add(pick.getTaskId());
					addPluginChatMessage(task.getName() + " was retired, so " + chunk.getName()
						+ " gave you " + pick.getName() + " instead.");
				}
				else
				{
					addPluginChatMessage(task.getName() + " was retired. " + chunk.getName()
						+ " has no other tasks left to give.");
				}
				retiredIds.add(id);
				regionChanged = true;
			}
			if (regionChanged)
			{
				entry.setValue(String.join(",", ids));
			}
		}

		if (!retiredIds.isEmpty())
		{
			setAccountState("regionRolledTasks", serializeRollBlob(roll));
			List<String> cards = getUnrevealedTaskIds();
			if (cards.removeAll(retiredIds))
			{
				setAccountState("unrevealedTasks", String.join(",", cards));
			}
		}
	}

	/** A comma-separated list as an ordered set (trimmed, blanks and repeats dropped). */
	private static Set<String> csvSet(String csv)
	{
		Set<String> out = new LinkedHashSet<>();
		if (csv == null)
		{
			return out;
		}
		for (String id : csv.split(","))
		{
			if (!id.trim().isEmpty())
			{
				out.add(id.trim());
			}
		}
		return out;
	}

	private NuzlockeTask selectWeightedRandom(List<NuzlockeTask> tasks)
	{
		if (tasks.isEmpty())
		{
			return null;
		}

		// Calculate total weight
		int totalWeight = 0;
		for (NuzlockeTask task : tasks)
		{
			int weight = task.getAssignmentWeight();
			// Default weight of 1 if not specified or 0
			totalWeight += (weight > 0) ? weight : 1;
		}

		if (totalWeight <= 0)
		{
			// Fallback to simple random if all weights are 0
			return tasks.get(random.nextInt(tasks.size()));
		}

		// Pick a random point in the total weight
		int randomPoint = random.nextInt(totalWeight);

		// Find which task that point falls into
		int currentWeight = 0;
		for (NuzlockeTask task : tasks)
		{
			int weight = task.getAssignmentWeight();
			currentWeight += (weight > 0) ? weight : 1;

			if (randomPoint < currentWeight)
			{
				return task;
			}
		}

		// Fallback (shouldn't happen)
		return tasks.get(tasks.size() - 1);
	}

	/** Get the rolled task IDs for a region from config. */
	// --- Task reveal cards -------------------------------------------------------
	//
	// A rolled task waits behind a face-down card until flipped (see TaskCardOverlay);
	// until then its id sits in `unrevealedTasks` and no module tracks it.
	//
	// The ROLL commits immediately (rollTasksForRegion) and only the reveal is deferred.
	// Otherwise a player could leave bad cards unflipped and reroll them by relogging.

	/** Task ids rolled but not yet flipped, in a stable order for a stable card layout. */
	public List<String> getUnrevealedTaskIds()
	{
		return new ArrayList<>(csvSet(acStr("unrevealedTasks", "")));
	}

	/**
	 * Park freshly rolled tasks behind cards. No-op when the feature is off, which is
	 * what keeps the old "straight into the list" behaviour available.
	 */
	private void markTasksUnrevealed(Set<String> taskIds)
	{
		if (!config.showTaskCards() || taskIds == null || taskIds.isEmpty())
		{
			return;
		}
		List<String> pending = getUnrevealedTaskIds();
		boolean changed = false;
		for (String taskId : taskIds)
		{
			// Never re-hide something already completed — that would resurrect a done
			// task as a card and, on flip, put it back in the active list.
			if (!pending.contains(taskId) && !getCompletedTaskIds().contains(taskId))
			{
				pending.add(taskId);
				changed = true;
			}
		}
		if (changed)
		{
			setAccountState("unrevealedTasks", String.join(",", pending));
		}
	}

	/**
	 * Flip a card: the task stops being pending and enters the active list on the spot.
	 * Idempotent, because the overlay and a config change can both reach it.
	 */
	public void revealTaskCard(String taskId)
	{
		List<String> pending = getUnrevealedTaskIds();
		if (!pending.remove(taskId))
		{
			return;
		}
		setAccountState("unrevealedTasks", String.join(",", pending));

		// Incremental activation via the O(1) index (see activateTask): a 30-card boss
		// pack must not re-run a full loadActiveTasks 30 times.
		// A card whose task left the roll (replaced by the server's) just goes away.
		NuzlockeTask task = findTaskById(taskId);
		boolean rolled = !keepRolledCards(taskId, parseRollBlob(acStr("regionRolledTasks", ""))).isEmpty();
		if (task != null && rolled && !task.isLocked()
			&& !getCompletedTaskIds().contains(taskId) && !isTaskActive(taskId))
		{
			activateTask(task);
		}

		if (panel != null)
		{
			panel.updatePanel();
		}
	}

	/**
	 * Bring one rolled task into play without a full loadActiveTasks, which re-registers
	 * EVERY active task and rewrites the whole progress blob once per task.
	 */
	private void activateTask(NuzlockeTask task)
	{
		initializeTask(task);
		if (task.getCurrentProgress() >= task.getTargetQuantity())
		{
			// Already satisfied when granted — settle through the batched flush
			// like every other completion (never the per-task freeze path).
			synchronized (pendingCompletions)
			{
				pendingCompletions.add(task);
			}
			return;
		}
		activeTasks.add(task);
		if (activeTask == null)
		{
			activeTask = task;
		}
		taskModuleManager.registerActiveTask(task);
		saveTaskProgress(task.getTaskId(), task.getCurrentProgress(), task.getTargetQuantity());
	}

	/** True if a task with this id is already in the active list. */

	/** For the panel: has the player completed this task? */
	public boolean isTaskCompleted(String taskId)
	{
		return getCompletedTaskIds().contains(taskId);
	}

	// Real (unboosted) skill levels, kept current from StatChanged, for the panel's level filter.
	private final Map<Skill, Integer> realLevels = new ConcurrentHashMap<>();

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		realLevels.put(event.getSkill(), event.getLevel());
	}

	/**
	 * Whether the player has the skill level a task asks for. True when the task names no
	 * skill or level, or the player's level isn't known yet, so nothing is hidden by mistake.
	 */
	public boolean meetsLevelRequirement(NuzlockeTask task)
	{
		Skill skill = skillForCategory(task.getCategory());
		if (skill == null || task.getLevelRequirement() <= 1)
		{
			return true;
		}
		Integer have = realLevels.get(skill);
		return have == null || have >= task.getLevelRequirement();
	}

	/** The skill a task category names ("Mining", "Runecrafting"), or null for Combat, Quest, etc. */
	static Skill skillForCategory(String category)
	{
		if (category == null)
		{
			return null;
		}
		String name = category.trim().toUpperCase();
		if (name.equals("RUNECRAFTING"))
		{
			name = "RUNECRAFT";
		}
		try
		{
			return Skill.valueOf(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private boolean isTaskActive(String taskId)
	{
		for (NuzlockeTask t : activeTasks)
		{
			if (taskId.equals(t.getTaskId()))
			{
				return true;
			}
		}
		return false;
	}

	/** Exposed for the card overlay, which only has ids to work with. */
	public NuzlockeTask getTaskById(String taskId)
	{
		return findTaskById(taskId);
	}

	/**
	 * The task selected in the side panel, as the LIVE instance the modules update.
	 * Looks in activeTasks / globalTasks rather than getTaskById: a taskID can exist
	 * in several chunks, and the index returns whichever copy loaded first, which may
	 * be an untracked copy stuck at 0/1.
	 */
	public NuzlockeTask getSelectedTask()
	{
		NuzlockeTask selected = selectedTask;
		if (selected == null || selected.getTaskId() == null)
		{
			return selected;
		}
		String id = selected.getTaskId();
		for (NuzlockeTask task : activeTasks)
		{
			if (id.equals(task.getTaskId()))
			{
				return task;
			}
		}
		for (NuzlockeTask task : globalTasks)
		{
			if (id.equals(task.getTaskId()))
			{
				return task;
			}
		}
		return selected;
	}

	// The task the player is tracking (task window, right-click Tasks menu). In memory only.
	private volatile NuzlockeTask selectedTask;

	/** Track a task: from the task window or the in-game "Tasks" right-click submenu. Quests aren't tracked. */
	public void selectTaskFromGame(NuzlockeTask task)
	{
		if (task != null && !QuestRequirements.isQuestTask(task))
		{
			selectedTask = task;
		}
	}


	/** Called from the overlay's right-click menu. */
	public void clearSelectedTask()
	{
		selectedTask = null;
	}

	public Set<String> getRolledTasksForRegion(int regionId)
	{
		return csvSet(parseRollBlob(acStr("regionRolledTasks", "")).get(String.valueOf(regionId)));
	}

	private void saveRolledTasksForRegion(int regionId, Set<String> taskIds)
	{
		Map<String, String> roll = parseRollBlob(acStr("regionRolledTasks", ""));
		roll.put(String.valueOf(regionId), String.join(",", taskIds));
		setAccountState("regionRolledTasks", serializeRollBlob(roll));
	}

	// --- Assigned Tasks Management ---

	private Set<String> getAssignedTaskIds()
	{
		return csvSet(acStr("assignedTasks", ""));
	}




	/** Cap on completion popups shown per batch, so a login storm doesn't queue dozens. */
	private static final int COMPLETION_ANIM_CAP = 5;

	/**
	 * Tasks whose completion was detected since the last tick, awaiting a single
	 * batched settle-up in {@link #flushPendingCompletions()}. Modules fire
	 * onTaskCompleted one at a time; a boss-chunk grant can settle a maxed
	 * account's whole stack at once, and the per-task settle-up path freezes the
	 * client at that volume (see {@link #completeTasks}).
	 */
	private final java.util.LinkedHashSet<NuzlockeTask> pendingCompletions = new java.util.LinkedHashSet<>();

	/**
	 * Settle every completion queued since the last tick in ONE batch: cap the
	 * animation popups, play a single sound, and do the expensive config/panel
	 * settle-up exactly once. Called from onGameTick.
	 */
	private void flushPendingCompletions()
	{
		List<NuzlockeTask> batch;
		synchronized (pendingCompletions)
		{
			if (pendingCompletions.isEmpty())
			{
				return;
			}
			batch = new ArrayList<>(pendingCompletions);
			pendingCompletions.clear();
		}

		int shown = 0;
		for (NuzlockeTask task : batch)
		{
			if (taskCompletionAnimationOverlay != null && shown < COMPLETION_ANIM_CAP)
			{
				taskCompletionAnimationOverlay.showTaskCompletion(
					task, task.getBasePoints(), getTaskCompletionLabel(task));
				shown++;
			}
		}
		// One sound for the whole batch — N clips at once on a login storm is both a
		// cacophony and needless load.
		if (config.playTaskCompletionSound() && soundManager != null)
		{
			playAreaSoundAsync(getTaskArea(batch.get(0)));
		}

		// The expensive part, ONCE regardless of batch size.
		completeTasks(batch);
	}



	/**
	 * Complete one or many tasks, doing the expensive settle-up work ONCE: looping
	 * completeTask() costs 2N config writes and 5N panel rebuilds and freezes the client.
	 * Any source that can satisfy many tasks at once must come through here.
	 */
	private void completeTasks(Collection<NuzlockeTask> tasks)
	{
		if (tasks == null || tasks.isEmpty())
		{
			return;
		}

		List<String> completedIds = new ArrayList<>(tasks.size());
		int totalPoints = 0;

		for (NuzlockeTask task : tasks)
		{
			if (task == null || task.getTaskId() == null)
			{
				continue;
			}

			// Cache the task before removing from active list (for completed tasks lookup)
			completedTaskCache.put(task.getTaskId(), task);
			completedIds.add(task.getTaskId());
			totalPoints += task.getBasePoints();

			// Remove from active tasks
			activeTasks.remove(task);
			if (activeTask == task)
			{
				activeTask = activeTasks.isEmpty() ? null : activeTasks.get(0);
			}

			if (selectedTask != null && task.getTaskId() != null && task.getTaskId().equals(selectedTask.getTaskId()))
			{
				selectedTask = null;
			}
		}

		if (completedIds.isEmpty())
		{
			return;
		}

		// --- settle up once, regardless of batch size ---
		addPoints(totalPoints);
		markTasksCompleted(completedIds);

		// Clear progress data for these tasks
		saveActiveTasks();

		// Points + completed list just changed; get them onto disk.
		flushConfigToDisk();

	}


	/**
	 * Append many task ids to the completed list in a SINGLE config write.
	 * markTaskCompleted() per task means one read + one write each, which is
	 * what made the Global Tasks backfill unusable — see
	 * backfillAndRegisterGlobalTasks().
	 */
	private void markTasksCompleted(Collection<String> taskIds)
	{
		Set<String> completed = getCompletedTaskIds();
		completed.addAll(taskIds);
		setAccountState("completedTasks", String.join(",", completed));
	}


	/**
	 * Public view of the persisted completed-task id set, for the panel. Global
	 * Tasks are not held in activeTasks, so the panel can't infer their done
	 * state from the active list the way the rolled sections do.
	 */
	public Set<String> getCompletedTaskIdSet()
	{
		return getCompletedTaskIds();
	}

	private Set<String> getCompletedTaskIds()
	{
		return csvSet(acStr("completedTasks", ""));
	}

	/** Completed task IDs in the order they were completed (the stored CSV order), deduplicated. */
	private List<String> getCompletedTaskIdsOrdered()
	{
		return new ArrayList<>(getCompletedTaskIds());
	}

	/**
	 * Get list of completed tasks with their details.
	 */
	public List<NuzlockeTask> getCompletedTasks()
	{
		Set<String> completedIds = getCompletedTaskIds();
		List<NuzlockeTask> completedTasks = new ArrayList<>();

		for (String taskId : completedIds)
		{
			NuzlockeTask task = findTaskById(taskId);
			if (task != null)
			{
				completedTasks.add(task);
			}
		}

		return completedTasks;
	}


	/**
	 * Get list of completed tasks with full info including region.
	 */
	public List<CompletedTaskInfo> getCompletedTasksWithInfo()
	{
		Set<String> completedIds = getCompletedTaskIds();
		List<CompletedTaskInfo> completedTasks = new ArrayList<>();

		for (String taskId : completedIds)
		{
			NuzlockeTask task = findTaskById(taskId);
			if (task != null)
			{
				int regionId = findRegionForTask(taskId);
				// Global tasks belong to no chunk, so findRegionForTask returns
				// -1 and getRegionName renders "Unknown Region (-1)". Label them
				// for what they are instead of showing a broken region. Same
				// string as the area bucket so the Area and Chunk filters agree.
				String regionName = globalTaskIds.contains(taskId)
					? GLOBAL_AREA_NAME
					: getRegionName(regionId);
				completedTasks.add(new CompletedTaskInfo(taskId, regionId, regionName, task));
			}
		}

		return completedTasks;
	}

	/**
	 * Find which region a task was rolled in.
	 */
	public int findRegionForTask(String taskId)
	{
		String data = acStr("regionRolledTasks", "");
		if (data != null && !data.isEmpty())
		{
			Integer rolled = rolledRegionIndex(data).get(taskId);
			if (rolled != null)
			{
				return rolled;
			}
		}

		// Fallback: locate the chunk that defines this task, for the popup name and area
		// jingle. A taskId can live in several chunks, so prefer one the player has
		// UNLOCKED; the first defining chunk is used only if they own none.
		int firstDefiningRegion = -1;
		for (NuzlockeChunk chunk : allChunks)
		{
			if (chunk.getTasks() == null || chunk.getRegionIds() == null || chunk.getRegionIds().isEmpty())
			{
				continue;
			}
			for (NuzlockeTask task : chunk.getTasks())
			{
				if (taskId.equals(task.getTaskId()))
				{
					int region = chunk.getRegionIds().get(0);
					if (firstDefiningRegion == -1)
					{
						firstDefiningRegion = region;
					}
					if (isRegionUnlocked(region))
					{
						return region;
					}
					break; // this chunk defines the task; try the next chunk
				}
			}
		}

		return firstDefiningRegion;
	}


	// taskId -> region from the regionRolledTasks string, rebuilt only when that string changes.
	// The task window asks for every task's region when it opens; parsing the whole string
	// once per task there was a visible lag spike.
	private String rolledIndexSource;
	private Map<String, Integer> rolledIndex = Collections.emptyMap();

	private synchronized Map<String, Integer> rolledRegionIndex(String data)
	{
		if (!data.equals(rolledIndexSource))
		{
			Map<String, Integer> index = new HashMap<>();
			for (String regionEntry : data.split("\\|"))
			{
				String[] parts = regionEntry.split(":");
				if (parts.length != 2)
				{
					continue;
				}
				try
				{
					int regionId = Integer.parseInt(parts[0]);
					for (String tid : parts[1].split(","))
					{
						index.putIfAbsent(tid, regionId); // first region wins, as before
					}
				}
				catch (NumberFormatException e)
				{
					// Skip invalid entries
				}
			}
			rolledIndex = index;
			rolledIndexSource = data;
		}
		return rolledIndex;
	}

	/** Whether this taskID belongs to the chunk-independent Global Tasks pool. */
	public boolean isGlobalTask(String taskId)
	{
		return taskId != null && globalTaskIds.contains(taskId);
	}







	/**
	 * Get the region name for a specific task.
	 */
	public String getTaskRegionName(NuzlockeTask task)
	{
		if (task == null || task.getTaskId() == null)
		{
			return null;
		}
		int regionId = findRegionForTask(task.getTaskId());
		if (regionId > 0)
		{
			return getRegionName(regionId);
		}
		return null;
	}

	/**
	 * Label for the completion popup's "Region Assigned" slot: a Global Task reports its
	 * pool ("Quest", "Progression"). Separate from getTaskRegionName(), which must keep
	 * returning null for chunkless tasks or the chunk filters would list "Quest" as a
	 * chunk.
	 */
	public String getTaskCompletionLabel(NuzlockeTask task)
	{
		String regionName = getTaskRegionName(task);
		if (regionName != null)
		{
			return regionName;
		}
		if (task != null && isGlobalTask(task.getTaskId())
			&& task.getCategory() != null && !task.getCategory().isEmpty())
		{
			return task.getCategory();
		}
		return null;
	}

	/**
	 * Get the area name for a specific task (e.g., "Misthalin", "Asgarnia").
	 */
	public String getTaskArea(NuzlockeTask task)
	{
		if (task == null || task.getTaskId() == null)
		{
			return null;
		}
		int regionId = findRegionForTask(task.getTaskId());
		if (regionId > 0)
		{
			NuzlockeChunk chunk = chunksByRegionId.get(regionId);
			if (chunk != null)
			{
				return chunk.getArea();
			}
		}
		return null;
	}

	/**
	 * @return true if this region is (part of) a boss chunk. Used by the side panel
	 * to render the Boss-Token unlock UI instead of the points one.
	 */
	public boolean isBossRegion(int regionId)
	{
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		return chunk != null && chunk.isBoss();
	}

	/**
	 * @return true if this task belongs to a boss chunk (chunk_type BOSS). Used by
	 * the side panel's "Boss chunks only" task filter.
	 */
	public boolean isBossTask(NuzlockeTask task)
	{
		if (task == null || task.getTaskId() == null)
		{
			return false;
		}
		int regionId = findRegionForTask(task.getTaskId());
		if (regionId > 0)
		{
			NuzlockeChunk chunk = chunksByRegionId.get(regionId);
			return chunk != null && chunk.isBoss();
		}
		return false;
	}

	// --- Task Progress Persistence ---

	/**
	 * Load task progress and target quantity.
	 * Format: "taskId:progress:targetQty,taskId2:progress2:targetQty2"
	 * Old format "taskId:progress" is also supported for backward compatibility.
	 * @return int[2] where [0] = progress, [1] = targetQty (0 if not saved)
	 */
	private int[] loadTaskProgressAndTarget(String taskId)
	{
		String data = acStr("taskProgressData", "");
		if (data == null || data.isEmpty())
		{
			return new int[]{0, 0};
		}
		for (String entry : data.split(","))
		{
			String[] parts = entry.split(":");
			if (parts.length >= 2 && parts[0].equals(taskId))
			{
				try
				{
					int progress = Integer.parseInt(parts[1]);
					int targetQty = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
					return new int[]{progress, targetQty};
				}
				catch (NumberFormatException e)
				{
					return new int[]{0, 0};
				}
			}
		}
		return new int[]{0, 0};
	}

	/**
	 * Save task progress and target quantity.
	 * Format: "taskId:progress:targetQty,taskId2:progress2:targetQty2"
	 *
	 * Callers MUST pass targetQty from the live activeTasks instance. findTaskById
	 * returns the first chunk's instance of a shared taskID, often with the default
	 * targetQuantity=1 that wipes the rolled value.
	 */
	public void saveTaskProgress(String taskId, int progress, int targetQty)
	{
		String data = acStr("taskProgressData", "");
		Map<String, int[]> progressMap = new HashMap<>();

		if (data != null && !data.isEmpty())
		{
			for (String entry : data.split(","))
			{
				String[] parts = entry.split(":");
				if (parts.length >= 2)
				{
					try
					{
						int existingProgress = Integer.parseInt(parts[1]);
						int existingTarget = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
						progressMap.put(parts[0], new int[]{existingProgress, existingTarget});
					}
					catch (NumberFormatException e)
					{
						// Skip invalid entries
					}
				}
			}
		}

		// A target=0 write must not replace a real persisted target: a progress event can
		// fire before initializeTask sets targetQuantity, and the next loadActiveTasks
		// would re-roll a fresh random number.
		if (targetQty <= 0 && progressMap.containsKey(taskId))
		{
			int existingTarget = progressMap.get(taskId)[1];
			if (existingTarget > 0)
			{
				targetQty = existingTarget;
			}
		}

		progressMap.put(taskId, new int[]{progress, targetQty});

		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, int[]> entry : progressMap.entrySet())
		{
			if (sb.length() > 0) sb.append(',');
			sb.append(entry.getKey()).append(':').append(entry.getValue()[0]).append(':').append(entry.getValue()[1]);
		}
		setAccountState("taskProgressData", sb.toString());
	}

	private void saveActiveTasks()
	{
		// Pass each task's live target: the 2-arg saveTaskProgress re-looks up via
		// findTaskById, whose first-match instance can overwrite the rolled target for a
		// taskID in several chunks.
		for (NuzlockeTask task : activeTasks)
		{
			saveTaskProgress(task.getTaskId(), task.getCurrentProgress(), task.getTargetQuantity());
		}
	}

	// --- Stats Methods ---

	public int getTotalPoints()
	{
		return acInt("totalPoints", 0);
	}


	/**
	 * Force RuneLite to write config to DISK now. ConfigManager only persists on a timer
	 * (first flush at 30-330s, then every 5 minutes) and on a clean ClientShutdown, so a
	 * killed or crashed client loses recent writes with no error. Flush progress-losing
	 * state (unlocks, points, completed tasks) explicitly.
	 */
	/**
	 * Make a just-happened chunk unlock durable in BOTH places, immediately. Otherwise a
	 * crash inside RuneLite's flush window and our 30s sync loses it, and the next sync
	 * overwrites the server's copy, since unlock sync is DELETE-then-INSERT with the
	 * client authoritative. syncToServer() self-guards and is async, so this is safe from
	 * an unlock handler.
	 */
	private void persistUnlockNow()
	{
		flushConfigToDisk();
		syncToServer();
	}

	private void flushConfigToDisk()
	{
		// sendConfig writes to disk and, for players signed into a RuneLite account,
		// waits on an upload, so it must never run on the client thread. RuneLite's own
		// 5-minute save runs on this same executor.
		executorService.execute(() ->
		{
			try
			{
				configManager.sendConfig();
			}
			catch (Exception e)
			{
				// Never let a persistence hiccup break the unlock/completion flow.
				log.warn("Failed to flush ChunkBlazer config to disk", e);
			}
		});
	}

	private void addPoints(int points)
	{
		int current = acInt("totalPoints", 0);
		setAccountState("totalPoints", current + points);
	}

	// --- Points: earned, spent, balance ------------------------------------
	//
	// `totalPoints` is the SPENDABLE BALANCE. The SERVER's total_points is lifetime EARNED,
	// recomputed from completed tasks (tasks.Catalog#Recompute) with no notion of spending.
	// Never write one into the other. The balance is derived:
	//
	//     balance = earned(completed tasks) - spent
	//
	// `spent` is the only stored quantity. It is monotonic, so profiles reconcile by taking
	// the maximum. It can't be derived from the unlocked-chunk list: the starting chunk and
	// Casual first-pick are free (unlockRegionFree()) yet have a non-zero unlock_cost, and
	// nothing records which chunks were paid for.

	/** Lifetime points earned, summed over completed tasks. Mirrors the server. */
	private int computeEarnedPoints()
	{
		int earned = 0;
		for (String taskId : getCompletedTaskIds())
		{
			NuzlockeTask task = findTaskById(taskId);
			if (task != null)
			{
				// Unknown ids are skipped, exactly as the server's Recompute does,
				// so catalog drift moves both sides the same way.
				earned += task.getBasePoints();
			}
		}
		return earned;
	}

	/**
	 * Recompute and persist the spendable balance. Safe to call any time; it is
	 * a pure function of the completed task list and the spend counter.
	 */
	private void recomputePointsBalance()
	{
		int earned = computeEarnedPoints();
		int spent = acInt("pointsSpent", 0);
		int balance = Math.max(0, earned - spent);

		// max(0, ...) silently absorbs a state that play cannot produce, and that
		// silence is why the derivation bug ran for weeks looking like "unlocking
		// a chunk spends all my points": the balance was pinned at zero and every
		// recompute just re-confirmed it without complaint. Say so instead.
		if (spent > earned)
		{
			log.warn("Spent {} > earned {}", spent, earned);
		}

		if (balance != acInt("totalPoints", 0))
		{
			setAccountState("totalPoints", balance);
		}
	}

	/** Record a paid chunk unlock and refresh the balance. */
	private void recordPointsSpent(int cost)
	{
		if (cost <= 0)
		{
			return;
		}
		setAccountState("pointsSpent", acInt("pointsSpent", 0) + cost);
		recomputePointsBalance();
	}

	/**
	 * One-time derivation of the spend counter for accounts that predate it: their balance
	 * reflects all spending, so {@code spent = earned - balance}. Runs only when no spend
	 * is recorded. With the balance at or above earned, 0 is the right start and the real
	 * value arrives from the server.
	 */
	private void deriveInitialPointsSpent()
	{
		if (acInt("pointsSpent", 0) > 0)
		{
			return;
		}

		// The derivation needs the local balance to be a REAL record of past spending.
		// With the key absent, acInt("totalPoints", 0) answers 0, which reads as SPENT
		// EVERYTHING when it means NOTHING IS KNOWN (a fresh install hydrating from the
		// server lands here). The counter is monotonic and syncs upward, so a wrong value
		// permanently pins the balance at zero.
		if (!isPointsBalancePersisted())
		{
			return;
		}

		int earned = computeEarnedPoints();
		int balance = acInt("totalPoints", 0);
		if (earned <= 0 || balance >= earned)
		{
			return;
		}

		int spent = earned - balance;
		setAccountState("pointsSpent", spent);
	}

	/**
	 * Whether the balance actually EXISTS in stored config, rather than being
	 * {@link ChunkBlazerConfig#totalPoints()}'s default of 0. Same shape as
	 * {@link #isUnlockedChunksPersisted()}. Decides whether a zero balance is evidence
	 * of spending or of nothing at all.
	 */
	private boolean isPointsBalancePersisted()
	{
		String raw = getAccountState("totalPoints");
		return raw != null && !raw.trim().isEmpty();
	}

	/**
	 * Undo an impossible spend counter left by the derivation bug above. Fixing the
	 * derivation can't clear it: the counter only moves up and the inflated figure is
	 * already on the server.
	 *
	 * <p>Only ever LOWERS the counter, and only to a total derived from owned chunks.
	 * Other free grants (casual mode's one-time pick) make that total slightly high, which
	 * errs toward the player keeping fewer points than owed. Balance stays
	 * {@code earned - spent}, so it can never exceed earned, which is what the server's
	 * Tier-0 check compares against.
	 */
	private void migrateRepairImpossiblePointsSpent()
	{
		int spent = acInt("pointsSpent", 0);
		if (spent <= 0)
		{
			return;
		}

		// The chunks a player owns ARE the spend ledger. legacyCurveLedgerTotal uses the
		// first curve, which every later price is at or under, so it's the most n paid
		// chunks can have cost. Not the current curve: a price cut must not "repair" anyone
		// down to new prices. Flat-1 economy accounts paid less, so the sum errs HIGH,
		// which only makes this lower-only repair more conservative.
		//
		// The ledger, NOT lifetime earned, is the ceiling: a corrupt counter can sit below
		// earned, so gating on "spent > earned" lets it through.
		int ledger = legacyCurveLedgerTotal(countPayableUnlockedChunks());
		if (ledger >= spent)
		{
			return;
		}

		int earned = computeEarnedPoints();
		log.warn("Spent repaired: {} > {} for {} chunks (earned {}), now {}, balance {} -> {}", spent, ledger, getUnlockedRegionIds().size(), earned, ledger, Math.max(0, earned - spent), Math.max(0, earned - ledger));
		setAccountState("pointsSpent", ledger);
	}

	// --- Boss Tokens (secondary currency) ---

	/** Current Boss Token balance. New players start with 2 (config default). */
	public int getBossTokens()
	{
		return acInt("bossTokens", 2);
	}

	/**
	 * Add (or, with a negative amount, remove) Boss Tokens; clamped at 0. Earned
	 * by defeating a boss in its boss chunk, or rarely from superior slayer
	 * monsters. (Earning triggers are wired separately once boss chunks exist /
	 * superior detection lands.)
	 */
	public void addBossTokens(int amount)
	{
		int updated = Math.max(0, acInt("bossTokens", 2) + amount);
		setAccountState("bossTokens", updated);
	}

	/**
	 * Spend one Boss Token (e.g. unlocking a boss chunk). Returns false without
	 * mutating if the player has none. Wire into boss-chunk unlock once boss
	 * chunks are defined.
	 */
	public boolean spendBossToken()
	{
		int current = acInt("bossTokens", 2);
		if (current <= 0)
		{
			return false;
		}
		setAccountState("bossTokens", current - 1);
		return true;
	}

	/**
	 * Unlock a boss chunk: spend one Boss Token (not points), keep the adjacency
	 * gate, and grant EVERY task on the chunk at once. Permanent unlock. No-op with
	 * a chat notice if the player has no token or the chunk isn't adjacent. Routed
	 * to from {@link #unlockRegion} once a chunk is identified as a boss chunk.
	 */
	public void unlockBossRegion(int regionId)
	{
		if (!countedWorld || isRegionUnlocked(regionId))
		{
			return;
		}
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk == null || !chunk.isBoss())
		{
			log.warn("Boss unlock {} refused: not a boss chunk", regionId);
			return;
		}

		// Adjacency gate — same as points unlocks. The boss bridge in
		// getNeighborRegionIds() is what makes a boss chunk eligible in the first
		// place (its surrounding chunks don't list it, so it bridges in reverse).
		Set<Integer> neighbors = getNeighborRegionIds();
		if (!neighbors.contains(regionId))
		{
			log.warn("Boss unlock {} refused: not adjacent", regionId);
			addPluginChatMessage("That boss chunk isn't adjacent to your unlocked area yet.");
			return;
		}

		// Spend the token BEFORE granting anything, so a failure never hands out the
		// chunk for free. spendBossToken() is a no-op returning false when empty.
		if (!spendBossToken())
		{
			addPluginChatMessage("You need a Boss Token to unlock " + getRegionName(regionId) + ".");
			return;
		}

		// Unlock every region of the chunk (surface + any sub-regions).
		List<Integer> toUnlock = (chunk.getRegionIds() != null && !chunk.getRegionIds().isEmpty())
			? chunk.getRegionIds()
			: java.util.Collections.singletonList(regionId);
		java.util.LinkedHashSet<String> unlockedSet = new java.util.LinkedHashSet<>(getUnlockedRegionIds());
		for (Integer r : toUnlock)
		{
			unlockedSet.add(String.valueOf(r));
		}
		setAccountState("unlockedChunks", String.join(",", unlockedSet));
		persistUnlockNow();

		// Grant ALL tasks — active immediately, not parked behind reveal cards.
		rollTasksForRegion(regionId, true);
		loadActiveTasks();
		if (panel != null)
		{
			panel.updatePanel();
		}

		addPluginChatMessage("Unlocked boss chunk " + getRegionName(regionId)
			+ " for 1 Boss Token. " + getBossTokens() + " remaining.");
		playRegionUnlockJingle(regionId);
	}

	/**
	 * The boss/raid keys the player has completed at least once after unlocking
	 * that boss chunk (comma-separated in account state). Drives the once-per-boss
	 * token grant and the sync's bossCompletions list.
	 */
	public Set<String> getCompletedBossKeys()
	{
		Set<String> keys = new java.util.LinkedHashSet<>();
		String raw = getAccountState("bossCompletions");
		if (raw != null && !raw.isEmpty())
		{
			for (String k : raw.split(","))
			{
				String t = k.trim();
				if (!t.isEmpty())
				{
					keys.add(t);
				}
			}
		}
		return keys;
	}

	/**
	 * @return true if the player has unlocked the boss chunk carrying this boss_key
	 * (any of its regions is unlocked).
	 */
	private boolean isBossChunkUnlocked(String bossKey)
	{
		if (bossKey == null || bossKey.isEmpty())
		{
			return false;
		}
		for (NuzlockeChunk chunk : new HashSet<>(chunksByRegionId.values()))
		{
			// A boss chunk may carry one boss key (ToA/CoX) or several on one region
			// (Scurrius + Bryophyta) — match against the whole set so each boss's first
			// clear can mint its own token.
			if (chunk == null || !chunk.isBoss() || chunk.getRegionIds() == null
				|| !containsIgnoreCase(chunk.getBossKeys(), bossKey))
			{
				continue;
			}
			for (Integer r : chunk.getRegionIds())
			{
				if (isRegionUnlocked(r))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Case-insensitive membership test — a chunk may carry one boss key or several. */
	private static boolean containsIgnoreCase(java.util.List<String> keys, String key)
	{
		if (keys == null)
		{
			return false;
		}
		for (String k : keys)
		{
			if (key.equalsIgnoreCase(k))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Record the first completion of a boss/raid (by boss_key) AFTER its chunk was
	 * unlocked, granting +1 Boss Token. Non-retroactive and once per boss. The server
	 * recomputes the authoritative balance from the synced set. Safe to call on every
	 * observed completion.
	 */
	public void recordBossCompletion(String bossKey)
	{
		if (bossKey == null || bossKey.isEmpty())
		{
			return;
		}
		// Only earns a token if the player actually unlocked this boss chunk —
		// the token gates the unlock, so an un-unlocked boss can't mint one.
		if (!isBossChunkUnlocked(bossKey))
		{
			return;
		}
		Set<String> done = getCompletedBossKeys();
		if (done.contains(bossKey))
		{
			return; // once per boss, non-retroactive
		}
		done.add(bossKey);
		setAccountState("bossCompletions", String.join(",", done));
		addBossTokens(1);
		persistUnlockNow();
		addPluginChatMessage("First clear recorded. +1 Boss Token earned!");
	}

	/**
	 * Self-heal for unlocked boss chunks with no tasks rolled, such as one unlocked before
	 * {@code Boss_Tasks.json} loaded. Idempotent: grants only tasks not already rolled or
	 * completed.
	 *
	 * <p>This is RECONSTRUCTION, so the tasks are NOT carded: reveal cards are only for
	 * spending a Boss Token ({@link #unlockBossRegion}). Rebuilding an account must never
	 * produce a wall of cards. {@link #loadActiveTasks} calls this and lists them directly.
	 */
	private void ensureBossChunkTasksGranted()
	{
		Set<String> completed = getCompletedTaskIds();

		for (NuzlockeChunk chunk : new HashSet<>(chunksByRegionId.values()))
		{
			if (chunk == null || !chunk.isBoss() || chunk.getRegionIds() == null
				|| chunk.getRegionIds().isEmpty() || chunk.getTasks() == null)
			{
				continue;
			}
			boolean anyUnlocked = false;
			for (Integer r : chunk.getRegionIds())
			{
				if (isRegionUnlocked(r))
				{
					anyUnlocked = true;
					break;
				}
			}
			if (!anyUnlocked)
			{
				continue;
			}

			int primary = chunk.getRegionIds().get(0);
			// A boss chunk grants EVERY task, so grant any catalog task not yet
			// rolled or completed. This also repairs partial subsets. The roll is
			// committed here; not carded (see the method doc).
			Set<String> rolled = new HashSet<>(getRolledTasksForRegion(primary));
			Set<String> missing = new HashSet<>();
			for (NuzlockeTask t : chunk.getTasks())
			{
				String id = t.getTaskId();
				if (id != null && !t.isLocked() && !completed.contains(id) && !rolled.contains(id))
				{
					missing.add(id);
				}
			}
			if (!missing.isEmpty())
			{
				Set<String> full = new HashSet<>(rolled);
				full.addAll(missing);
				saveRolledTasksForRegion(primary, full);
			}
		}
	}

	// --- Helper Methods ---

	String getPlayerName()
	{
		Player player = client.getLocalPlayer();
		if (player != null)
		{
			return player.getName();
		}
		return null;
	}

	// --- World Map Helper Methods ---

	public Set<Integer> getNeighborRegionIds()
	{
		Set<Integer> neighbors = new HashSet<>();
		Set<String> unlocked = unlockedRegionIdsView();

		for (String regionIdStr : unlocked)
		{
			try
			{
				int regionId = Integer.parseInt(regionIdStr);
				NuzlockeChunk chunk = chunksByRegionId.get(regionId);
				if (chunk != null && chunk.getNeighborIds() != null)
				{
					for (Integer neighborId : chunk.getNeighborIds())
					{
						// Only add if not already unlocked
						if (!unlocked.contains(String.valueOf(neighborId)))
						{
							neighbors.add(neighborId);
						}
					}
				}
				// Free chunks have no task-chunk entry; their neighbours come from
				// Free_Chunks.json. INVARIANT: a free chunk ALWAYS opens its 4 cardinal
				// neighbours. With no neighbor_ids, derive them from the region grid
				// (regionId ±1 = N/S, ±256 = E/W). Authored ones are a curation override.
				List<Integer> freeNeighbors = freeUnlockableNeighbors.get(regionId);
				if (freeNeighbors == null && freeUnlockableRegionIds.contains(regionId))
				{
					freeNeighbors = Arrays.asList(
						regionId + 1, regionId - 1, regionId + 256, regionId - 256);
				}
				if (freeNeighbors != null)
				{
					for (Integer neighborId : freeNeighbors)
					{
						if (!unlocked.contains(String.valueOf(neighborId)))
						{
							neighbors.add(neighborId);
						}
					}
				}
			}
			catch (NumberFormatException e)
			{
				log.warn("Invalid region ID in unlocked list: {}", regionIdStr);
			}
		}

		// Prifddinas bridge: the city's real regions live in instance coordinates,
		// so they can never appear in a surface chunk's neighbor_ids. If any gate
		// chunk around the city is unlocked, all four city chunks become unlockable.
		for (Integer gate : PRIF_GATE_REGIONS)
		{
			if (unlocked.contains(String.valueOf(gate)))
			{
				for (Integer city : PRIF_CITY_REGIONS)
				{
					if (!unlocked.contains(String.valueOf(city)))
					{
						neighbors.add(city);
					}
				}
				break;
			}
		}

		// Boss-chunk bridge: boss chunks sit off the task-area grid, so the chunks around
		// them don't list them in neighbor_ids. Expose one once ANY of its OWN neighbor_ids
		// is unlocked. The Boss-Token spend still gates the actual unlock (unlockRegion).
		for (NuzlockeChunk chunk : new HashSet<>(chunksByRegionId.values()))
		{
			if (chunk == null || !chunk.isBoss()
				|| chunk.getNeighborIds() == null || chunk.getRegionIds() == null)
			{
				continue;
			}
			boolean bordersUnlocked = false;
			for (Integer n : chunk.getNeighborIds())
			{
				if (unlocked.contains(String.valueOf(n)))
				{
					bordersUnlocked = true;
					break;
				}
			}
			if (!bordersUnlocked)
			{
				continue;
			}
			for (Integer r : chunk.getRegionIds())
			{
				if (!unlocked.contains(String.valueOf(r)))
				{
					neighbors.add(r);
				}
			}
		}

		// Free chunks open their 4 grid neighbours, which can land on a region that is
		// in neither the task catalog nor the free list (open ocean, map edges). Those
		// have no tasks and were being sold at the full curve price (Chunk Fil A,
		// 2026-10-03), so only known regions are ever offered.
		neighbors.removeIf(id -> !isKnownRegion(id));
		return neighbors;
	}

	/** True if the region is a task chunk (any type) or a Free_Chunks.json chunk. */
	public boolean isKnownRegion(int regionId)
	{
		return chunksByRegionId.containsKey(regionId) || freeUnlockableRegionIds.contains(regionId);
	}

	/** True if the task is authored on the chunk at this region (a task can sit on several). */
	public boolean regionHasTask(int regionId, String taskId)
	{
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		return chunk != null && chunk.getTasks() != null
			&& chunk.getTasks().stream().anyMatch(t -> taskId.equals(t.getTaskId()));
	}

	public String getRegionName(int regionId)
	{
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk != null)
		{
			// Return friendly format: "ChunkName (regionId)"
			return chunk.getName() + " (" + regionId + ")";
		}
		String freeName = freeUnlockableNames.get(regionId);
		if (freeName != null)
		{
			return freeName + " (" + regionId + ")";
		}
		return "Unknown Region (" + regionId + ")";
	}

	// ── Chunk unlock cost curve ─────────────────────────────────────────────
	// The r-th PAID unlock costs 1 + CURVE_B * r up to CURVE_KNEE, then climbs faster at
	// CURVE_LATE_B, rounded, never below 1. Up to the knee (half of the 384 payable chunks)
	// prices are exactly 1.0.9's; the last chunk reaches 31. The whole map costs about 80%
	// of every point the game deals, boss chunk tasks included, so a player who never
	// bosses tops out around 93% of the map. r is the paid chunk count + 1, so unlocked
	// chunks are GRANDFATHERED (pointsSpent is never recomputed here). UnlockCostCurveTest
	// checks the share against the shipped catalog. spendfloor.go (server) and route.js
	// (website) have their own copies: change them together. Any change reprices every
	// player's next unlock.
	static final double CURVE_B = 0.059;
	static final int CURVE_KNEE = 192;
	static final double CURVE_LATE_B = 0.0998;
	// The first curve (2026-09). It was sized against every task in a chunk while a
	// chunk only dealt 4 or 5, so the whole map was out of reach. Chunks bought on it
	// keep the price paid (no refund): the spend repair's ceiling is its ledger.
	static final double LEGACY_CURVE_B = 0.0913;

	/** Points cost of the r-th paid chunk unlock (r &gt;= 1). Whole number, minimum 1. */
	public static int curveUnlockCost(int rank)
	{
		int r = Math.max(rank, 1);
		return price(CURVE_B * Math.min(r, CURVE_KNEE) + CURVE_LATE_B * Math.max(0, r - CURVE_KNEE));
	}

	/** Sum of curveUnlockCost(1..n): the total paid to own n paid chunks under the curve. */
	public static int curveLedgerTotal(int n)
	{
		int total = 0;
		for (int k = 1; k <= n; k++)
		{
			total += curveUnlockCost(k);
		}
		return total;
	}

	/** The total n paid chunks cost on the legacy curve: the most anyone can have paid for them. */
	public static int legacyCurveLedgerTotal(int n)
	{
		int total = 0;
		for (int k = 1; k <= n; k++)
		{
			total += price(LEGACY_CURVE_B * k);
		}
		return total;
	}

	private static int price(double climb)
	{
		return (int) Math.max(1L, Math.round(1.0 + climb));
	}

	/**
	 * Number of PAID chunks the account currently owns, counted per chunk (not per
	 * region id, so a multi-region chunk counts once). Free / charter / boss chunks
	 * and the granted starting chunk are excluded, because none of them are priced by
	 * the points curve. This is the "rank" the next paid unlock is charged at.
	 */
	 int countPayableUnlockedChunks()
	{
		java.util.Set<Integer> seenChunks = new java.util.HashSet<>();
		for (String s : getUnlockedRegionIds())
		{
			int id;
			try
			{
				id = Integer.parseInt(s.trim());
			}
			catch (NumberFormatException ignored)
			{
				continue;
			}
			if (id == DEFAULT_START_REGION || freeUnlockableRegionIds.contains(id))
			{
				continue;
			}
			NuzlockeChunk c = chunksByRegionId.get(id);
			if (c == null)
			{
				continue; // unknown region: never sold now, so an old purchase doesn't raise the price
			}
			if (c.isCharter() || c.isBoss() || c.getUnlockCostValue() == 0)
			{
				continue;
			}
			// Dedupe multi-region chunks by their first region id.
			Integer key = (c.getRegionIds() != null && !c.getRegionIds().isEmpty())
				? c.getRegionIds().get(0) : id;
			seenChunks.add(key);
		}
		return seenChunks.size();
	}

	public int getRegionUnlockCost(int regionId)
	{
		// Free chunks (Free_Chunks.json) cost nothing to unlock.
		if (freeUnlockableRegionIds.contains(regionId))
		{
			return 0;
		}
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		// Charter ports are always free to unlock.
		if (chunk != null && chunk.isCharter())
		{
			return 0;
		}
		// Boss chunks cost a Boss Token, not points — never curve-priced.
		if (chunk != null && chunk.isBoss())
		{
			return 0;
		}
		int base = (chunk != null) ? chunk.getUnlockCostValue() : 1;
		if (base == 0)
		{
			return 0; // explicitly free-in-points chunk
		}
		// Payable geographic chunk: charge the scaling curve at the account's current
		// rank. Reading the count BEFORE the region is added (see unlockRegion) makes
		// this unlock the (count + 1)-th paid chunk.
		return curveUnlockCost(countPayableUnlockedChunks() + 1);
	}

	// Overworld surface lives in regionY 39..64 (world y 2496..4159). Everything
	// outside that band is off-map storage — underground dungeons, relocated
	// cities (Prifddinas), instanced content — which we treat as free dungeons.
	private static final int SURFACE_MIN_REGION_Y = 39;
	private static final int SURFACE_MAX_REGION_Y = 64;

	/**
	 * @return true if this region is a free dungeon (always accessible, never part of the
	 * chunk challenge): outside the overworld surface band (regionY 39..64).
	 * Free_Chunks.json regions are NOT always-free; see {@link #isFreeUnlockableRegion}.
	 */
	// Prifddinas sits above the surface band (regionY 94-95) but is a surface city, not a
	// dungeon: exempt it so the four city chunks start LOCKED.
	private static final Set<Integer> PRIF_CITY_REGIONS = new HashSet<>(Arrays.asList(
		12894, 12895, 13150, 13151));
	// The Tirannwn surface chunks surrounding the city. The city regions aren't
	// adjacent to them in region-id space (instance coordinates), so unlocking
	// ANY of these bridges all four city chunks into the unlockable set.
	private static final Set<Integer> PRIF_GATE_REGIONS = new HashSet<>(Arrays.asList(
		8757, 9013, 9268, 9267, 9010, 8754, 8500, 8499));

	public boolean isFreeRegion(int regionId)
	{
		if (PRIF_CITY_REGIONS.contains(regionId))
		{
			return false; // real surface city despite its out-of-band regionY
		}
		int regionY = regionId & 0xFF;
		return regionY < SURFACE_MIN_REGION_Y || regionY > SURFACE_MAX_REGION_Y;
	}

	/**
	 * True if the region is listed in Free_Chunks.json: a 0-cost, unlock-on-demand
	 * chunk that behaves like a charter port (yellow-unlockable, unlock by walking
	 * in / clicking) but has NO tasks — nothing rolls on unlock.
	 */
	public boolean isFreeUnlockableRegion(int regionId)
	{
		return freeUnlockableRegionIds.contains(regionId);
	}

	public void unlockRegion(int regionId)
	{
		if (!countedWorld)
		{
			addPluginChatMessage("Chunks can't be unlocked on this world. Hop to a normal world first.");
			return;
		}
		// Idempotency guard: if the region is already unlocked, do not deduct
		// points or re-append the regionId. Without this, two near-simultaneous
		// unlock paths (e.g. side-panel "Yes" + the still-open chatbox popup)
		// double-charge the player. Mike reported "spent 2 points to unlock 1
		// region" — that's this race.
		if (isRegionUnlocked(regionId))
		{
			return;
		}

		// Charter ports and free-list chunks are 0-cost and aren't adjacent to the
		// unlocked area, so they bypass the points + adjacency path below. Routing
		// through here means every unlock entry point (minimap, world map, side
		// panel) handles them correctly.
		if (isCharterRegion(regionId) || isFreeUnlockableRegion(regionId))
		{
			unlockRegionFree(regionId);
			loadActiveTasks();
			panel.updatePanel();
			return;
		}

		// Boss chunks (raids / bosses): unlocked with a Boss Token instead of
		// points, still adjacency-gated, and granting EVERY task at once. Routed
		// here (before the points path) so every unlock entry point handles them.
		NuzlockeChunk bossChunk = chunksByRegionId.get(regionId);
		if (bossChunk != null && bossChunk.isBoss())
		{
			unlockBossRegion(regionId);
			return;
		}

		int cost = getRegionUnlockCost(regionId);
		int currentPoints = getTotalPoints();

		if (currentPoints < cost)
		{
			log.warn("Unlock {} refused: need {}, have {}", regionId, cost, currentPoints);
			return;
		}

		// Adjacency gate: a points-purchased unlock must neighbor an unlocked chunk.
		// Otherwise the side-panel "Unlock" button (or any direct unlockRegion
		// caller) buys any locked chunk the player walked into. The Casual
		// first-pick goes through unlockRegionFree(...), which bypasses this.
		Set<Integer> neighbors = getNeighborRegionIds();
		if (!neighbors.contains(regionId))
		{
			log.warn("Unlock {} refused: not adjacent ({})", regionId, neighbors);
			return;
		}

		// Snapshot before mutating so we can tell whether THIS call is the
		// first-time unlock (and therefore should play the jingle). Always
		// false here given the guard above, but kept for symmetry with
		// unlockRegionFree and the jingle-playback path that reads it.
		boolean wasAlreadyUnlocked = false;

		// Charge for the unlock. Recorded as SPEND rather than written straight
		// into the balance, so the balance stays derivable (earned - spent) and
		// survives a reinstall or a profile switch — see recomputePointsBalance().
		recordPointsSpent(cost);

		// Add to unlocked list. Unlock EVERY region of this chunk, not just the
		// clicked one — a chunk can span multiple regions (e.g. a surface area AND
		// its dungeon, which have different region IDs). Adding only one leaves the
		// other half showing as locked when you walk into it: the H.A.M. Hideout
		// "go down to the dungeon, come back up, surface is locked" bug.
		NuzlockeChunk unlockedChunk = chunksByRegionId.get(regionId);
		List<Integer> toUnlock = (unlockedChunk != null && unlockedChunk.getRegionIds() != null
			&& !unlockedChunk.getRegionIds().isEmpty())
			? unlockedChunk.getRegionIds()
			: java.util.Collections.singletonList(regionId);

		java.util.LinkedHashSet<String> unlockedSet = new java.util.LinkedHashSet<>(getUnlockedRegionIds());
		for (Integer r : toUnlock)
		{
			unlockedSet.add(String.valueOf(r));
		}
		setAccountState("unlockedChunks", String.join(",", unlockedSet));
		// Points were just spent for this — never let it evaporate on a crash.
		persistUnlockNow();


		// Auto-roll tasks for the new region
		Set<String> newTasks = rollTasksForRegion(regionId);

		// Reload all active tasks (includes the new region's tasks)
		loadActiveTasks();

		// Update panel
		panel.updatePanel();

		// Confirm in chat so the player doesn't have to watch the side panel.
		addPluginChatMessage("Unlocked " + getRegionName(regionId) + " for " + cost
			+ (cost == 1 ? " point. " : " points. ") + (currentPoints - cost) + " remaining.");

		if (!wasAlreadyUnlocked)
		{
			playRegionUnlockJingle(regionId);
		}
	}

	/**
	 * Play a random region-specific jingle for the chunk's area (Misthalin,
	 * Asgarnia, Kandarin, etc.) using the same per-area sound pool that drives
	 * task-completion sounds. Silent if the chunk has no area mapping or the
	 * config toggle is off. Should only be called on the FIRST unlock of a
	 * region — callers are responsible for the wasAlreadyUnlocked check.
	 */
	private void playRegionUnlockJingle(int regionId)
	{
		if (!config.playRegionUnlockSound())
		{
			return;
		}
		if (soundManager == null)
		{
			return;
		}
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk == null)
		{
			return;
		}
		String area = chunk.getArea();
		if (area == null || area.isEmpty())
		{
			return;
		}
		playAreaSoundAsync(area);
	}

	/**
	 * Plays an area sound off the client thread: picking the clip stats and reads
	 * cached audio files, and the Hub forbids disk I/O on the client thread.
	 */
	private void playAreaSoundAsync(String area)
	{
		TaskCompletionSoundManager sm = soundManager;
		if (sm != null)
		{
			executorService.execute(() -> sm.playRandomSoundForArea(area));
		}
	}

}
