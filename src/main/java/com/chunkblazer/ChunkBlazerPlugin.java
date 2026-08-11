package com.chunkblazer;

import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import java.util.Collection;
import java.util.Collections;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.KeyManager;
import lombok.Setter;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import com.chunkblazer.api.ChunkBlazerApiClient;
import com.chunkblazer.api.EligibilitySnapshot;
import com.chunkblazer.api.PlayerLoginResponse;
import com.chunkblazer.api.PlayerSyncRequest;
import com.chunkblazer.modules.TaskModuleManager;
import com.chunkblazer.verification.VarPlayerVerificationService;

@Slf4j
@PluginDescriptor(
	name = "ChunkBlazer",
	description = "A Nuzlocke Chunk Unlocker Plugin with RNG Task Assignment",
	tags = {"chunk", "chunkblazer", "nuzlocke", "challenge", "task"}
)
public class ChunkBlazerPlugin extends Plugin
{
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
	private ChunkBlazerPlayerOverlay playerOverlay;

	@Inject
	private ChunkBlazerMinimapPlayerOverlay minimapPlayerOverlay;

	@Inject
	private ChunkBlazerOrbOverlay orbOverlay;

	@Inject
	private ChunkBlazerBossTokenOverlay bossTokenOverlay;

	@Inject
	private ChunkBlazerRoster roster;

	@Inject
	private ChatIconManager chatIconManager;

	@Inject
	private TaskCompletionAnimationOverlay taskCompletionAnimationOverlay;

	@Inject
	private TaskModuleManager taskModuleManager;

	@Inject
	private VarPlayerVerificationService varPlayerService;

	@Inject
	private TaskCompletionSoundManager soundManager;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ChunkBlazerApiClient apiClient;

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
	private TaskCardInput taskCardInput;

	// True while the configured world-map unlock key is held. Set by
	// ChunkBlazerInput (KeyListener); read in onMenuOptionClicked so a click on
	// the world map while the key is down unlocks the hovered chunk.
	@Setter
	private boolean worldMapUnlockKeyPressed;

	// --- Plugin State ---

	// Server verdict on whether this account may use the Dev Controls panel,
	// from the is_dev flag in the login response. Deliberately a transient field
	// and NOT a config entry: config is user-editable from the RuneLite profile
	// file, so persisting it there would recreate the very self-grant this
	// closes. Defaults false and resets on logout, so an unreachable server or
	// an old build denies the tools rather than opening them.
	@Getter
	private volatile boolean devAuthorized;

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

	// taskId -> task, across every chunk plus the Global Tasks pool. Rebuilt by
	// rebuildTaskIndex() whenever task data is (re)loaded.
	//
	// This exists because findTaskById() used to LINEAR SCAN allChunks — ~404
	// chunks x ~12 tasks = ~5,000 string compares per lookup, with the cache
	// checked only after the scan failed. getCompletedTasksWithInfo() calls it
	// once per completed id and the panel calls that on every refresh, so the
	// cost was completedCount x 5,000 per repaint. Survivable at 41 completed
	// tasks; it hard-locked the client at ~200 (see backfillAndRegisterGlobalTasks).
	private final Map<String, NuzlockeTask> tasksById = new HashMap<>();
	// Region IDs listed in Free_Chunks.json: 0-cost, unlock-on-demand chunks that
	// behave exactly like charter ports (yellow-unlockable, unlock by walking in /
	// clicking) EXCEPT they have no tasks, so nothing rolls on unlock. NOTE:
	// always-accessible dungeon regions are handled separately by the coordinate
	// rule in isFreeRegion, NOT by this list.
	private final Set<Integer> freeUnlockableRegionIds = new HashSet<>();
	// Region id -> Friendly_Name for free chunks, used in the unlock message.
	private final Map<Integer, String> freeUnlockableNames = new HashMap<>();
	// Region id -> neighbor_ids for free chunks. Free chunks live only in
	// Free_Chunks.json (no task-chunk entry), so without this an unlocked free
	// chunk contributed NO neighbors — a connectivity dead end that made areas
	// behind it (e.g. the Rellekka islands) unreachable without routing around.
	private final Map<Integer, List<Integer>> freeUnlockableNeighbors = new HashMap<>();
	private final Map<String, NuzlockeTask> completedTaskCache = new HashMap<>(); // Cache completed tasks for lookup
	private ChunkBlazerPanel panel;
	private NavigationButton navButton;
	private volatile int lastRegionId = -1;
	private final Random random = new Random();

	/** Handle for the periodic save-state sync; cancelled on shutDown(). */
	private ScheduledFuture<?> syncFuture;
	private static final long SYNC_INTERVAL_SECONDS = 30;

	/** Handle for the periodic presence heartbeat; cancelled on shutDown(). */
	private ScheduledFuture<?> heartbeatFuture;
	private static final long HEARTBEAT_INTERVAL_SECONDS = 30;

	/** Handle for the periodic online-roster refresh; cancelled on shutDown(). */
	private ScheduledFuture<?> rosterFuture;
	private static final long ROSTER_INTERVAL_SECONDS = 30;

	/**
	 * ChatIconManager handle for the ChunkBlazer chat icon. -1 until registered.
	 * The renderable index ({@code <img=N>}) is resolved lazily per message via
	 * {@link ChatIconManager#chatIconIndex(int)} since it isn't valid until the
	 * client has loaded mod icons after login.
	 */
	private int chatIconId = -1;

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

	// --- Constants ---
	private static final String DEV_MENU_OPTION = "DEBUG: Complete Task";
	private static final String DEV_MENU_TARGET = "ChunkBlazer";

	// --- Plugin Lifecycle ---

	@Override
	protected void startUp()
	{

		// Start verification service (registers for VarPlayer events)
		varPlayerService.startUp();

		// Load task data from JSON
		loadChunkData();

		// The starting-chunk bootstrap deliberately does NOT run here.
		//
		// startUp() happens at client launch, long before anyone has logged in,
		// so at this point RuneLite cannot say which account the write would
		// belong to. Writing progress then is the root cause of the cross-account
		// corruption this refactor exists to remove, and once the store moves to
		// RSProfile the write would not land at all. It now runs from
		// onRuneScapeProfileChanged(), which fires exactly when the account
		// becomes known — and again on every account switch.

		// Initialize task module manager
		taskModuleManager.initialize();
		taskModuleManager.setCompletionHandler(new TaskModuleManager.TaskCompletionHandler()
		{
			@Override
			public void onTaskCompleted(NuzlockeTask task, int progress)
			{
				// Task completed locally - request server verification

				// Show animated task completion popup
				if (taskCompletionAnimationOverlay != null && task != null)
				{
					String regionName = getTaskCompletionLabel(task);
					taskCompletionAnimationOverlay.showTaskCompletion(task, task.getBasePoints(), regionName);

					// Play region-specific completion sound
					if (config.playTaskCompletionSound())
					{
						// Pass the area through even when null: Global Tasks belong
						// to no chunk, so getTaskArea() is always null for them and
						// this guard silently muted every quest completion. The
						// sound manager falls back to a default folder instead.
						String area = getTaskArea(task);
						if (soundManager != null)
						{
							soundManager.playRandomSoundForArea(area);
						}
					}
				}
				else
				{
					log.error(">>>   CANNOT show animated popup - overlay or task is null!");
				}


				completeTask(task);
			}

			@Override
			public void onServerVerified(NuzlockeTask task, int pointsAwarded)
			{
				// Server verified completion
				// Points already awarded in completeTask, but this confirms server agreement
				panel.updateStats();
				panel.updateTaskDisplay();
			}

			@Override
			public void onProgressUpdated(NuzlockeTask task, int newProgress)
			{
				// Pass the live task's target directly. The 2-arg overload re-looks
				// up the task via findTaskById, which scans allChunks and can return
				// a different instance for taskIDs that appear in multiple chunks
				// (e.g. cook_tuna lives in both Mistrock and Fishing Guild) — that
				// stale instance has the default targetQuantity=1 and corrupts the
				// saved rolled value.
				saveTaskProgress(task.getTaskId(), newProgress, task.getTargetQuantity());

				// Progress updated - refresh UI on Swing thread
				javax.swing.SwingUtilities.invokeLater(() ->
				{
					panel.updateTaskDisplay();
				});
			}
		});
		taskModuleManager.startUp();

		// Schedule periodic server save-state sync. First run is delayed by one
		// interval so we don't race the login flow on plugin startup.
		syncFuture = executorService.scheduleAtFixedRate(
			this::syncToServer, SYNC_INTERVAL_SECONDS, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);

		// Presence heartbeat — updates players.last_heartbeat_at + current
		// world/region so the server knows who's currently online and where.
		heartbeatFuture = executorService.scheduleAtFixedRate(
			this::sendHeartbeatToServer, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

		// Online-roster refresh — pulls the live list of ChunkBlazer players so
		// the recognition surfaces (chat icon, minimap dot, overhead tag,
		// outline) know who to decorate. First run delayed one interval.
		rosterFuture = executorService.scheduleAtFixedRate(
			this::refreshRoster, ROSTER_INTERVAL_SECONDS, ROSTER_INTERVAL_SECONDS, TimeUnit.SECONDS);

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

		// Register world map overlay
		overlayManager.add(worldMapOverlay);

		// Region Locker-style world-map unlock: track the hold-to-unlock key.
		keyManager.registerKeyListener(inputListener);

		// Register minimap overlay for chunk visualization and click-to-unlock
		overlayManager.add(minimapOverlay);

		// Register scene overlay that draws chunk borders + locked-chunk wash on the gameplay screen
		overlayManager.add(sceneOverlay);

		// Legacy task completion popup overlay disabled - using animation overlay instead

		// Register animated task completion overlay
		overlayManager.add(taskCompletionAnimationOverlay);

		// Task reveal cards. The mouse listener has to be registered alongside the
		// overlay: it is what turns a click on a card into a flip (and swallows that
		// click so it doesn't also walk the player into the middle of the screen).
		overlayManager.add(taskCardOverlay);
		mouseManager.registerMouseListener(taskCardInput);

		// Player recognition surfaces: overhead tag + model outline (scene) and
		// minimap dots. Each render path is individually config-gated.
		overlayManager.add(playerOverlay);
		overlayManager.add(minimapPlayerOverlay);
		overlayManager.add(orbOverlay);
		overlayManager.add(bossTokenOverlay);

		// Register the ChunkBlazer chat icon shown next to other plugin users'
		// names in public chat. chat_icon.png is a purpose-built 16x16
		// ChunkBlazer glyph drawn to read crisply at chat size. Register it at
		// its native size — resizing here was anti-aliasing the edges into
		// faint, washed-out pixels.
		BufferedImage chatIcon = ImageUtil.loadImageResource(getClass(), "chat_icon.png");
		if (chatIcon != null && chatIconId < 0)
		{
			chatIconId = chatIconManager.registerChatIcon(chatIcon);
		}

		// Load or assign a task if player is logged in
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			loadOrAssignTask();
			panel.updatePanel();
			// The plugin was enabled (or hot-reloaded) while already logged in,
			// so there's no LOGGED_IN transition coming to kick off the server
			// login. Queue it here; onGameTick fires it once the local player's
			// name is readable. Without this we'd never obtain an api_key, so
			// heartbeats no-op and recognition never lights up.
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
		if (heartbeatFuture != null)
		{
			heartbeatFuture.cancel(false);
			heartbeatFuture = null;
		}
		if (rosterFuture != null)
		{
			rosterFuture.cancel(false);
			rosterFuture = null;
		}
		roster.clear();
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(worldMapOverlay);
		keyManager.unregisterKeyListener(inputListener);
		worldMapUnlockKeyPressed = false;
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(playerOverlay);
		overlayManager.remove(minimapPlayerOverlay);
		overlayManager.remove(orbOverlay);
		overlayManager.remove(bossTokenOverlay);
		overlayManager.remove(taskCompletionAnimationOverlay);
		overlayManager.remove(taskCardOverlay);
		mouseManager.unregisterMouseListener(taskCardInput);
		// Drop cards WITHOUT revealing: the pending set is durable, so they are still
		// waiting on the next startup. Revealing here would flip them for free.
		taskCardOverlay.clear();
		taskModuleManager.shutDown();
		varPlayerService.shutDown();
		if (soundManager != null)
		{
			soundManager.shutdown();
		}
		activeTask = null;
	}

	@Provides
	ChunkBlazerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChunkBlazerConfig.class);
	}

	// --- Event Handlers ---

	private volatile boolean pendingServerLogin = false;

	/**
	 * Set to true once a server login response comes back successfully, reset on
	 * LOGIN_SCREEN. Guards against world hops / fairy rings / brief connection
	 * blips that re-fire GameState.LOGGED_IN — we don't want to re-call
	 * /api/player/login for the same session, both to be a polite client and to
	 * stay well under the server's per-IP rate cap on /login.
	 */
	private volatile boolean serverLoginDone = false;

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Small delay to ensure client is ready
			clientThread.invokeLater(() ->
			{
				loadOrAssignTask();
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
			// Last-chance sync before localPlayer becomes inaccessible. Build the
			// request right here (still on the event-bus thread, client state
			// still readable) and fire-and-forget the HTTP call.
			//
			// Skipped entirely if the server's record was never merged this
			// session (login failed, or the player logged straight back out):
			// local would then hold only the bootstrap, and this sync is
			// destructive, so it would erase the account's real progress. Losing
			// one session's unsynced play is recoverable; erasing the server copy
			// is not.
			PlayerSyncRequest finalSync = serverStateMerged ? buildSyncRequest() : null;
			if (finalSync != null && config.apiEnabled())
			{
				apiClient.syncPlayerState(finalSync)
					.thenAccept(resp -> log.info("Logout sync: success={}",
						resp != null && resp.isSuccess()));
			}
			else if (!serverStateMerged)
			{
				log.warn("[CHUNKBLAZER] skipping logout sync — server state was never "
					+ "merged this session, so local progress is not authoritative");
			}
			// Logout beacon — tells the server we're offline now so it can snapshot
			// this just-ended session's hi-scores immediately instead of waiting for
			// heartbeats to go stale. Fire-and-forget; goOffline() no-ops when the
			// API is disabled or no api_key is set.
			apiClient.goOffline();
			activeTask = null;
			lastRegionId = -1;
			pendingServerLogin = false;
			// Drop dev authorization with the session. Without this it would carry
			// over to whichever account logs in next on this client, handing a
			// normal account the dev tools until its own login response arrived.
			devAuthorized = false;
			// Same reasoning for the cached Progression baseline: it is parsed
			// for one account, and the next one to log in must re-read (and, if
			// the stored baseline isn't theirs, capture their own).
			cachedProgressionBaseline = null;
			cachedBaselineOwner = null;
			lastSkillSample = null;
			stableSkillSamples = 0;
			// Every session must re-merge before it is allowed to sync.
			serverStateMerged = false;
			// Real logout — reset the dedupe flag so the NEXT LOGGED_IN
			// (which is a fresh game session) re-runs loginToServer.
			serverLoginDone = false;
			// And clear any pending verification — the nonce is tied to the
			// pre-logout session. Also drop any in-flight Nuzlocke lock.
			pendingVerificationNonce = null;
			pendingNuzlockeSnapshot = null;
			panel.hideVerificationPrompt();
			// Drop the recognition roster; it'll repopulate after next login.
			roster.clear();
			// Refresh the side panel into its logged-out state (gates the
			// gameplay sections behind being in-game).
			panel.updatePanel();
		}
	}

	/**
	 * A RuneLite config PROFILE switch swaps this plugin's entire local state —
	 * unlocked chunks, completed tasks, points, the Progression baseline — with
	 * no game login and no GameState change at all.
	 *
	 * <p>That makes it as significant as a login and it was being ignored. The
	 * damage (observed 2026-07-21): {@code serverStateMerged} had been earned by
	 * the PREVIOUS profile's login, so it stayed true across the switch, and the
	 * 30-second sync then pushed the newly-swapped-in profile's state — a
	 * bootstrap-only 1 chunk — straight over a server record holding 15.
	 * Switching profiles to compare them silently destroyed the good one.
	 *
	 * <p>So a switch revokes the merge and re-runs the server login, which
	 * re-merges for whatever profile is now active. Until that completes, sync
	 * is blocked.
	 */
	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		revokeSyncAuthorityForProfileSwitch();

		loadActiveTasks();
		if (panel != null)
		{
			panel.updatePanel();
		}
	}

	/**
	 * "We now know which account is playing" — and, on a switch, "we now know it
	 * is a different one". Posted by ConfigManager whenever the RS profile key
	 * changes, including the null → real transition at login and the real → null
	 * transition at logout.
	 *
	 * <p>This replaces {@code startUp()} as the trigger for the starting-chunk
	 * bootstrap. startUp() runs at client launch with no account available; this
	 * runs at the first moment a per-account write is meaningful, which is the
	 * whole point. It is also the switch signal, so the reload below rebuilds the
	 * panel against the newly-active account rather than leaving the previous
	 * one's tasks on screen.
	 *
	 * <p>Guards on {@code newProfile} rather than calling the bootstrap
	 * unconditionally: the logout edge fires this too, and bootstrapping into a
	 * null profile is exactly the account-less write being eliminated.
	 */
	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		if (!isAccountStateAvailable())
		{
			log.debug("[CHUNKBLAZER] RS profile cleared (logout) — dropping in-memory task state");
			loadActiveTasks(); // clears, since state is unavailable
			if (panel != null)
			{
				panel.updatePanel();
			}
			return;
		}

		log.info("[CHUNKBLAZER] RS profile available — bootstrapping account state");
		ensureStartingChunkUnlocked();
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
		log.info("[CHUNKBLAZER] RuneLite profile changed — revoking sync authority until "
			+ "this profile has merged the server's record");

		serverStateMerged = false;
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

		if (pendingServerLogin && player.getName() != null)
		{
			pendingServerLogin = false;
			loginToServer();
		}

		WorldPoint wp = player.getWorldLocation();
		int currentRegionId = wp.getRegionID();

		// Update panel if region changed
		if (currentRegionId != lastRegionId)
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
			// Auto-unlock region if enabled and player has enough points
			else if (config.autoUnlockRegions())
			{
				tryAutoUnlockCurrentRegion(currentRegionId);
			}
			else if (config.showUnlockPopup())
			{
				// Show unlock popup if entering an unlockable neighbor region
				showUnlockPopupIfNeeded(currentRegionId);
			}

			panel.updateRegionDisplay();
			panel.updateTaskList();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// Add unlock option to minimap when hovering over unlockable region
		if (!config.showMinimapChunks())
		{
			return;
		}

		// Check if this is a minimap-related menu
		int componentId = event.getActionParam1();
		if (componentId != ComponentID.FIXED_VIEWPORT_MINIMAP_DRAW_AREA &&
			componentId != ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA &&
			componentId != ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_MINIMAP_DRAW_AREA)
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
			.onClick(e -> showMinimapUnlockPopup(hoveredRegion));
	}

	/**
	 * World-map unlock (Region Locker model): if the map-unlock key is held and
	 * the player clicks a neighbour chunk on the open world map, unlock it. We act
	 * here rather than via a right-click menu entry because the world map doesn't
	 * reliably surface custom menu entries — the same reason Region Locker uses a
	 * keybind+click. The hovered region comes from the world-map overlay, which
	 * already hit-tests the cursor against each drawn chunk.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
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
		// Show the unlock confirm both as a chatbox Yes/No prompt and in the top-right
		// side panel, matching the walk-into-a-chunk experience. unlockRegion is
		// idempotent, so acting on either prompt is safe if both are open.
		showMinimapUnlockPopup(regionId);
		panel.promptUnlockForRegion(regionId);
	}

	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (!event.isFocused())
		{
			// Don't leave the key "stuck on" if focus is lost mid-hold.
			worldMapUnlockKeyPressed = false;
		}
	}

	private void showMinimapUnlockPopup(int regionId)
	{
		String regionName = getRegionName(regionId);
		int cost = getRegionUnlockCost(regionId);
		int currentPoints = getTotalPoints();

		clientThread.invokeLater(() ->
		{
			if (currentPoints < cost)
			{
				chatboxPanelManager.openTextMenuInput(
						"Cannot unlock " + regionName + "! " +
						"Need " + (cost - currentPoints) + " more points. " +
						"(Cost: " + cost + ", You have: " + currentPoints + ")")
					.option("OK", () ->
					{
					})
					.build();
			}
			else
			{
				chatboxPanelManager.openTextMenuInput(
						"Unlock " + regionName + " for " + cost + " points? " +
						"(Remaining: " + (currentPoints - cost) + " points)")
					.option("Yes, unlock!", () ->
					{
						unlockRegion(regionId);
					})
					.option("No", () ->
					{
					})
					.build();
			}
		});
	}

	/**
	 * Attempt to auto-unlock the current region.
	 * If autoUnlockFree is enabled, unlocks ANY region the player walks into.
	 * Otherwise, only unlocks neighbor regions if player has enough points.
	 */
	private void tryAutoUnlockCurrentRegion(int regionId)
	{
		// Skip if already unlocked
		if (isRegionUnlocked(regionId))
		{
			return;
		}

		// Check if this region is a defined chunk (has tasks)
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);

		// Check if free unlock mode is enabled - unlocks ANY region
		if (config.autoUnlockFree())
		{
			// Free unlock - don't spend points, unlock ANY region
			String chunkName = chunk != null ? chunk.getName() : "Unknown";
			unlockRegionFree(regionId);

			// Roll tasks for this region if it has tasks defined
			if (chunk != null && chunk.getTasks() != null && !chunk.getTasks().isEmpty())
			{
				Set<String> existingRolled = getRolledTasksForRegion(regionId);
				if (existingRolled.isEmpty())
				{
					rollTasksForRegion(regionId);
				}
			}

			// Reload tasks for the newly unlocked region
			loadActiveTasks();
			panel.updatePanel();
		}
		// Non-free mode: do NOT auto-spend points when the player walks into a
		// new region. Walking + points + autoUnlockRegions used to silently
		// drain the wallet — confusing especially for the dev "+10 / +100"
		// buttons, where freshly-granted points evaporated as soon as the
		// player took a step. Spending points to unlock now requires explicit
		// intent through the panel's "Unlock" button, the minimap right-click
		// menu, or the world-map click. The side-panel section visible while
		// standing in a locked region surfaces this prompt automatically.
		// The autoUnlockFree branch above still grants free exploration unlocks
		// for users who want that mode.
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

		// Show the unlock popup
		int cost = getRegionUnlockCost(regionId);
		int currentPoints = getTotalPoints();
		String regionName = chunk.getName();

		clientThread.invokeLater(() ->
		{
			if (currentPoints < cost)
			{
				// Not enough points - show info message
				chatboxPanelManager.openTextMenuInput(
						"New region: " + regionName + "! " +
						"Need " + (cost - currentPoints) + " more points to unlock. " +
						"(Cost: " + cost + ", You have: " + currentPoints + ")")
					.option("OK", () ->
					{
					})
					.build();
			}
			else
			{
				// Can afford - show unlock confirmation
				chatboxPanelManager.openTextMenuInput(
						"Unlock " + regionName + " for " + cost + " points? " +
						"(Remaining: " + (currentPoints - cost) + " points)")
					.option("Yes, unlock!", () ->
					{
						unlockRegion(regionId);
					})
					.option("No, not yet", () ->
					{
					})
					.build();
			}
		});
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
		String unlocked = config.unlockedChunks();
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

		// Update panel
		panel.updateStats();
	}

	/**
	 * Ensure the default starting chunk is unlocked. Every new game begins with
	 * one free chunk ({@link #DEFAULT_START_REGION}, Lumbridge); its neighbours
	 * are visible on the world map but must be unlocked with points like any
	 * other chunk. In casual mode players can also unlock the chunk they're
	 * standing on. Idempotent — safe to call on every startup.
	 *
	 * <p>This also guarantees {@code unlockedChunks} EXISTS on disk, not merely
	 * that it reads as containing the start region — see
	 * {@link #isUnlockedChunksPersisted()} for why those differ and what breaks.
	 */
	public void ensureStartingChunkUnlocked()
	{
		Set<String> currentlyUnlocked = getUnlockedRegionIds();
		StringBuilder newUnlocked = new StringBuilder();
		boolean needsUpdate = false;

		// Start with current unlocked regions
		String existing = config.unlockedChunks();
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
			log.debug("[CHUNKBLAZER] unlockedChunks absent from disk — seeding start region {}",
				DEFAULT_START_REGION);
			needsUpdate = true;
		}

		if (needsUpdate)
		{
			setAccountState("unlockedChunks", newUnlocked.toString());
		}

		// Pre-roll tasks for the starting chunk so they're ready immediately.
		// Other chunks get their tasks rolled lazily when unlocked.
		if (getRolledTasksForRegion(DEFAULT_START_REGION).isEmpty())
		{
			NuzlockeChunk chunk = chunksByRegionId.get(DEFAULT_START_REGION);
			if (chunk != null && chunk.getTasks() != null && !chunk.getTasks().isEmpty())
			{
				Set<String> newTasks = rollTasksForRegion(DEFAULT_START_REGION);
			}
			else
			{
				log.warn("Starting chunk {} has no chunk or tasks defined", DEFAULT_START_REGION);
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
		"Charter_Tasks.json"
	};

	// The single free chunk every new game starts with (Lumbridge). Auto-unlocked
	// by ensureStartingChunkUnlocked(); every other chunk costs points.
	private static final int DEFAULT_START_REGION = 12850;


	/**
	 * Returns the filename with .json/.JSON extension flipped to the opposite case,
	 * or unchanged if it doesn't end in either. Used to defensively try both case
	 * variants when reading bundled task JSON resources.
	 */
	private static String flipJsonExtensionCase(String filename)
	{
		if (filename.endsWith(".json"))
		{
			return filename.substring(0, filename.length() - 5) + ".JSON";
		}
		if (filename.endsWith(".JSON"))
		{
			return filename.substring(0, filename.length() - 5) + ".json";
		}
		return filename;
	}

	private void loadChunkData()
	{
		allChunks.clear();
		chunksByRegionId.clear();


		Type mapType = new TypeToken<Map<String, List<NuzlockeChunk>>>()
		{
		}.getType();
		int totalChunksLoaded = 0;
		int totalRegionMappings = 0;

		for (String jsonFile : TASK_JSON_FILES)
		{
			try
			{

				// Resource lookups inside a JAR are case-sensitive (unlike Windows fs),
				// so a file shipped as Foo.JSON breaks a getResourceAsStream("Foo.json")
				// caller and vice versa. We try both cases at both relative and absolute
				// classpath paths so the loader survives any case mismatch in the bundle.
				String altCase = flipJsonExtensionCase(jsonFile);
				String absPrefix = "/net/runelite/client/plugins/chunkblazer/";
				String[] candidates = { jsonFile, absPrefix + jsonFile, altCase, absPrefix + altCase };

				InputStream is = null;
				String foundVia = null;
				for (String path : candidates)
				{
					InputStream s = getClass().getResourceAsStream(path);
					if (s != null)
					{
						is = s;
						foundVia = path;
						break;
					}
				}
				if (is == null)
				{
					log.error("FAILED to find task file: {} (tried {})",
						jsonFile, java.util.Arrays.toString(candidates));
					continue;
				}

				String jsonContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);

				Map<String, List<NuzlockeChunk>> data = null;
				try
				{
					data = gson.fromJson(jsonContent, mapType);
				}
				catch (Exception parseEx)
				{
					log.error("JSON PARSE ERROR for {}: {}", jsonFile, parseEx.getMessage(), parseEx);
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
							chunk.setArea(areaName);
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
								log.warn("Chunk '{}' in {} has null regionIds!", chunk.getName(), jsonFile);
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

				is.close();
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
				log.error(">>> LUMBRIDGE (12850) NOT FOUND in chunksByRegionId!");
			}
		}
		else
		{
			log.error("NO REGIONS LOADED! chunksByRegionId is empty!");
		}

		// Free (0-cost) chunk registry — dungeons etc. that unlock for free.
		loadFreeChunks();

		// Global (chunk-independent) task pool — quests.
		loadGlobalTasks();

		// Both sources are loaded; index them for O(1) findTaskById.
		rebuildTaskIndex();
	}

	/**
	 * Rebuild the taskId -> task index from allChunks + globalTasks.
	 *
	 * Call after ANY change to either collection. Both are only populated by
	 * loadChunkData()/loadGlobalTasks(), which run once at startup, so this has
	 * a single call site today — but a stale index silently returns the wrong
	 * task, so keep them adjacent if that ever changes.
	 *
	 * Duplicate taskIDs across chunks are expected (e.g. cook_tuna exists in both
	 * Mistrock and the Fishing Guild). First-wins here matches what the old
	 * linear scan returned, so behaviour is unchanged.
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
			log.warn("[CHUNKBLAZER] task schema error in '{}': {}", task.getTaskId(), problem);
		}
	}

	/**
	 * Load the Global Tasks pool (Quest_Tasks.json): QUEST_CHECK tasks that are
	 * free for every account and belong to no chunk.
	 *
	 * The file is deliberately wrapped in the same region-group shape the normal
	 * task files use ({"Quest_Tasks":[{"region_id":[], "tasks":[...]}]}) with an
	 * EMPTY region_id, so the Go server's catalog loader — which unmarshals every
	 * data file as map[string][]regionGroup — can embed it with no server-side
	 * change. A flat {"quest_tasks":[...]} array would parse there without error
	 * and silently load zero tasks, which would make every quest award 0 points.
	 *
	 * Loaded into `globalTasks` only. These are NOT added to allChunks or
	 * chunksByRegionId: they have no region, and registering them there would
	 * make findRegionForTask/getTaskArea return garbage.
	 */
	private void loadGlobalTasks()
	{
		globalTasks.clear();

		// Quest_Tasks = the quest pool; Progression_Tasks = the per-skill level
		// ladder. Both are region-independent and both land in globalTasks; the
		// only thing that distinguishes them downstream is completion_type.
		for (String file : new String[]{ "Quest_Tasks.json", "Progression_Tasks.json" })
		{
			loadGlobalTaskFile(file);
		}

		if (globalTasks.isEmpty())
		{
			// Loud, because the failure is otherwise invisible: the section
			// just renders empty and no global task ever awards a point.
			log.error("Global Tasks loaded but contained ZERO tasks — check the region-group wrapper");
		}
	}

	private void loadGlobalTaskFile(String file)
	{
		String alt = flipJsonExtensionCase(file);
		String abs = "/net/runelite/client/plugins/chunkblazer/";
		String[] candidates = { file, abs + file, alt, abs + alt };

		InputStream is = null;
		for (String path : candidates)
		{
			is = getClass().getResourceAsStream(path);
			if (is != null)
			{
				break;
			}
		}
		if (is == null)
		{
			log.error("FAILED to find Global Tasks file: {} (tried {})",
				file, java.util.Arrays.toString(candidates));
			return;
		}

		try
		{
			String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
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
			log.debug("Global Tasks: loaded {} from {}", globalTasks.size() - before, file);
		}
		catch (Exception e)
		{
			log.error("Failed to load Global Tasks from {}: {}", file, e.getMessage(), e);
		}
		finally
		{
			try
			{
				is.close();
			}
			catch (Exception ignored)
			{
			}
		}
	}

	/**
	 * Register the Global Tasks pool with the module manager.
	 *
	 * MUST be called from loadActiveTasks(), after taskModuleManager.clearTask().
	 * clearTask() wipes EVERY module's active list, and loadActiveTasks() runs on
	 * login, chunk unlock, config change, reset and mode switch — so registering
	 * the pool once at startup would leave it silently dead after the player's
	 * first chunk unlock.
	 *
	 * Already-completed quest tasks are skipped here. Without that filter the
	 * module would re-detect every finished quest on each reload and fire
	 * completeTask() again, re-awarding base_points every login and appending a
	 * duplicate id to the completedTasks config string each time.
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

			// LOGGED_IN is NOT enough, and neither is a single-skill probe: the
			// table hydrates skill by skill over several ticks. The Progression
			// baseline is read from it and then FROZEN, so reading it early
			// doesn't merely delay a feature — it permanently records the
			// not-yet-loaded skills as 0, which makes their rungs look earnable
			// and pays out the ladder retroactively. Wait for the whole table.
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
	 * Split the Global Tasks pool into "already finished before we ever looked"
	 * and "still to do", then settle the first group in ONE batch.
	 *
	 * WHY THE BATCH EXISTS (2026-07-19, froze the client): letting the module
	 * detect these normally meant ~150 tasks firing completionCallback in a
	 * single sweep, and each one runs the full single-task pipeline —
	 * completeTask() does a config write for points, another for the completed
	 * list, then panel.updateCompletedTasks(), which calls
	 * getCompletedTasksWithInfo() and re-scans every completed id through
	 * findTaskById + findRegionForTask, plus a full Swing rebuild of the active
	 * task list. That is quadratic in the number of completions and it all runs
	 * on the client thread, so a developed account hard-locked on login.
	 *
	 * A player who already did the quests also does not want 150 popups.
	 * Backfilled quests are therefore silent: points are summed and written
	 * once, the completed list is written once, and the panel refreshes once.
	 * Only quests finished DURING play reach the module and get the normal
	 * per-task celebration.
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
		int progressionSkipped = 0;

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
				progressionSkipped++;
				continue;
			}

			// Global tasks are pass/fail, never counted. targetQuantity is a
			// transient defaulting to 0, and a 0 target reads as "already done"
			// to progress comparisons elsewhere, so pin it explicitly.
			task.setTargetQuantity(1);
			task.setCurrentProgress(0);
			task.setCompleted(false);

			// Already-satisfied global tasks settle in one batch. For quests that
			// means "finished before we looked"; for Progression it means a rung
			// ABOVE the baseline that was crossed while the plugin was off — real
			// forward progress we simply didn't witness, so it still pays.
			if (isQuestFinished(task) || isProgressionRungReached(task))
			{
				task.setCurrentProgress(1);
				task.setCompleted(true);
				backfilled.add(task);
				backfilledPoints += task.getBasePoints();
				continue;
			}

			taskModuleManager.registerActiveTask(task);
		}

		if (progressionSkipped > 0)
		{
			log.debug("Progression: {} rungs at or below the frozen baseline — not eligible", progressionSkipped);
		}

		if (backfilled.isEmpty())
		{
			// Nothing to settle, but the baseline may have been captured just
			// now — the panel was built before it existed and would still be
			// listing every unearnable rung. Repaint so they drop out.
			if (panel != null)
			{
				panel.updateGlobalTasks();
			}
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

	// Parsed form of config.progressionBaseline(). Cached because the panel asks
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
	 * This account's frozen baseline, or empty when there isn't one FOR THIS
	 * ACCOUNT.
	 *
	 * <p>ChunkBlazer config lives on the RuneLite profile, so every account
	 * signed in through the same profile reads the same string. Without an owner
	 * tag a maxed main's baseline would be inherited by the next account to log
	 * in — and since eligibility is {@code threshold > baseline}, a fresh level 3
	 * would inherit 99s and be locked out of the ENTIRE ladder permanently. That
	 * is the exact inverse of the original bug and just as silent. The existing
	 * {@code accountModeHash} solves the same problem the same way.
	 *
	 * <p>A value with no owner tag predates this and is treated as belonging to
	 * nobody, so it is re-captured for whoever is logged in — always the safe
	 * direction, since a re-capture can only ever be more restrictive.
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

		String raw = config.progressionBaseline();
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
	 * The Global Tasks the UI should show. Progression rungs at or below the
	 * frozen baseline are omitted: the account cleared those levels before
	 * ChunkBlazer ever saw it, so they can never be earned and listing ~200
	 * permanently-unearnable entries would bury the ones that are live.
	 *
	 * <p>Only visibility — eligibility is decided in
	 * {@link #backfillAndRegisterGlobalTasks()}, which is what actually stops
	 * them scoring. Both read the same baseline, so they can't disagree.
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
	 * The frozen per-skill levels this account had when ChunkBlazer first saw it.
	 * Progression rungs at or below these never pay — that is the whole
	 * "no retroactive points for an established account" rule.
	 *
	 * <p>Captured ONCE and then never rewritten. Two consequences worth knowing:
	 * <ul>
	 *   <li>A fresh account baselines at all 1s (Hitpoints 10), so the entire
	 *       ladder is live for it. The Hitpoints level-10 rung isn't in the task
	 *       data at all, since every account spawns having cleared it.</li>
	 *   <li>If the stored baseline is ever lost, the next login re-captures at
	 *       CURRENT levels. That is strictly more restrictive than the original —
	 *       it can cost a player rungs they were working toward, but it can never
	 *       hand out points that weren't earned. Failing in that direction is
	 *       deliberate.</li>
	 * </ul>
	 *
	 * <p>Client thread only — reads live skill data.
	 */
	// How many consecutive identical readings of the skill table before we trust
	// it. Completeness (below) is the real gate; this is the backstop for the one
	// skill completeness can't assert on — see SAILING in isSkillDataComplete().
	private static final int REQUIRED_STABLE_SKILL_SAMPLES = 3;

	private int[] lastSkillSample;
	private int stableSkillSamples;

	/**
	 * Whether the client's skill table holds this account's real levels.
	 *
	 * <p>The table hydrates INCREMENTALLY, in {@link Skill} enum order, over
	 * several ticks after GameState.LOGGED_IN. Both halves of this were learned
	 * the hard way on 2026-07-21:
	 * <ol>
	 *   <li>LOGGED_IN alone → the whole table read 0, baseline froze at all
	 *       zeros, and a maxed account was handed the entire 671-point ladder.</li>
	 *   <li>Hitpoints-only probe → Hitpoints is 4th in the enum, so it goes
	 *       valid while everything from Fishing (11th) onward is still 0. The
	 *       baseline froze half-real, and the late skills paid out retroactively.</li>
	 * </ol>
	 *
	 * <p>The correct invariant is not a timer: <b>no OSRS skill can be below 1</b>,
	 * so a 0 anywhere means that entry hasn't loaded. Checking EVERY skill is
	 * therefore complete and deterministic — it waits exactly as long as the
	 * client needs, which is what makes it safe on a slow machine or connection
	 * where a fixed delay would be a guess.
	 *
	 * <p>Sailing is the one exception: if it isn't live in this client build it
	 * reads 0 forever, and requiring it would stall capture permanently. It is
	 * excluded from the completeness test but still covered by the stability
	 * test, so a Sailing value that is merely LATE (it hydrates last) still
	 * blocks capture until it settles.
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
			// Don't freeze a baseline off a half-loaded skill table — that
			// mistake is permanent and pays out the ladder. Returning empty
			// means every rung reads as ineligible for now; a later call
			// captures for real. Belt to the caller's braces:
			// registerGlobalTasks already reschedules until the table settles,
			// so this should be unreachable — but this bug shipped twice, so
			// one gate is not enough. Uses the stateless completeness check so
			// calling it here can't disturb the caller's stability counter.
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
		log.info("[CHUNKBLAZER] Progression baseline captured for {}: {}", getPlayerName(), sb);
		return baseline;
	}

	/**
	 * Undo a baseline frozen from a half-loaded skill table (2026-07-21).
	 *
	 * <p>Two builds got this wrong in two different ways, and this repairs both:
	 * <ul>
	 *   <li>Captured on LOGGED_IN → every skill 0.</li>
	 *   <li>Captured on a Hitpoints-only probe → the first ten skills real, the
	 *       rest still 0 (the table hydrates in enum order).</li>
	 * </ul>
	 * Either way a 0 makes every rung of that skill "above baseline", and the
	 * already-reached check pays them all out — exactly what Progression exists
	 * to prevent.
	 *
	 * <p>The tell is an impossible value, not a version marker: <b>no OSRS skill
	 * can be below 1</b>, and no account below 10 Hitpoints. Sailing is exempt
	 * because it legitimately reads 0 when not live. Any baseline containing
	 * such a value is cleared, and every progression task is un-completed with
	 * its points refunded, so the next capture redoes it honestly. Legitimately
	 * earned rungs are lost with them — acceptable, because they were only
	 * earnable in the minutes since this shipped and re-earning one just needs
	 * the level-up to be seen again.
	 *
	 * <p>Self-healing rather than one-shot: keyed off the impossible value, so
	 * it repairs any client that ran either bad build whenever it next loads,
	 * then no-ops forever.
	 */
	public void migrateRepairBogusProgressionBaseline()
	{
		String raw = config.progressionBaseline();
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

		log.warn("[CHUNKBLAZER] repaired a bogus all-zeros Progression baseline: "
			+ "cleared baseline, un-completed {} progression tasks, refunded {} points", removed, refunded);
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
		String alt = flipJsonExtensionCase("Free_Chunks.json");
		String abs = "/net/runelite/client/plugins/chunkblazer/";
		String[] candidates = { "Free_Chunks.json", abs + "Free_Chunks.json", alt, abs + alt };
		InputStream is = null;
		for (String path : candidates)
		{
			is = getClass().getResourceAsStream(path);
			if (is != null)
			{
				break;
			}
		}
		if (is == null)
		{
			return;
		}
		try
		{
			String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
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
		String hash = config.accountModeHash();
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
		return config.gameMode();
	}

	public boolean isLoggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	public boolean isModeLocked()
	{
		String hash = config.accountModeHash();
		if (hash == null || hash.isEmpty())
		{
			return false;
		}

		// Check if the hash is for the current account
		String currentRsn = getPlayerName();
		if (currentRsn == null)
		{
			return false;
		}

		String expectedPrefix = hashRsn(currentRsn);
		return hash.startsWith(expectedPrefix);
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
			addPluginChatMessage("Competitive mode needs a connection to the ChunkBlazer server to verify your account. Try again when online.");
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
						addPluginChatMessage("Sorry, your account does not meet the Competitive requirements, "
							+ "please create a new account or play Casual Mode."
							+ (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));

						// Log what we actually SENT as well. If the reason looks
						// wrong for the account, the snapshot is the thing to
						// distrust — client-side reads can be unhydrated.
						log.warn("[CHUNKBLAZER] Competitive eligibility refused: reason='{}' "
								+ "submitted combat={} questPoints={} totalLevel={} skills={}",
							reason, snapshot.getCombatLevel(), snapshot.getQuestPoints(),
							snapshot.getTotalLevel(), snapshot.getSkills());
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
						addPluginChatMessage("Your account meets the Competitive requirements — locking it in!");
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
				addPluginChatMessage("Your account meets the Competitive requirements! Type " + nonce
					+ " in public chat and hit Enter to lock in Competitive.");
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

		setAccountState("accountModeHash", modeKey);
		setAccountState("gameMode", mode);


		// Mirror the lock to the server. Local config is the immediate source of
		// truth; the server call backs it up so the choice survives across
		// installs / machines (and, for Nuzlocke, re-validates eligibility).
		if (config.apiEnabled() && apiClient != null)
		{
			apiClient.lockGameMode(mode, eligibility)
				.thenAccept(response ->
				{
					if (response == null)
					{
						return;
					}
					if (response.isSuccess())
					{
						if (mode == GameMode.NUZLOCKE)
						{
							addPluginChatMessage("Competitive locked in. Good luck — there's no going back!");
						}
					}
					else if (response.isAlreadyLocked())
					{
						log.warn("Server already had a locked mode: {}", response.getGameModeEnum());
					}
					else
					{
						log.warn("Server lock-mode response: status={} message={}",
							response.getStatus(), response.getMessage());
					}
				});
		}

		// For Casual mode, unlock the current chunk the player is standing in
		if (mode == GameMode.CASUAL)
		{
			int currentRegion = getCurrentRegionId();

			if (currentRegion > 0 && !isRegionUnlocked(currentRegion))
			{
				unlockRegionFree(currentRegion);

				// Roll tasks for the newly unlocked region
				Set<String> newTasks = rollTasksForRegion(currentRegion);

				// Reload active tasks
				loadActiveTasks();
			}
			else if (currentRegion > 0)
			{
			}
		}

		panel.updateModeDisplay();
		panel.updatePanel();
	}

	private static final String CHARTER_SEED_STRIPPED_KEY = "charterSeedStripped";

	/**
	 * One-time migration. Earlier builds SEEDED every charter port into the
	 * player's unlocked set at startup. Charter ports are now unlock-on-demand
	 * (free, 0-cost, shown with a yellow "unlockable" outline), so strip any
	 * previously-seeded charter regions exactly once and let players re-unlock
	 * them by sailing in or clicking. Guarded by a hidden config flag so a
	 * charter port the player unlocks AFTER the migration is never stripped —
	 * safe because, pre-migration, every charter region in the unlocked set got
	 * there via seeding. Cheap no-op once the flag is set, so it's fine to keep
	 * calling on every task (re)load.
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
			// Declares itself for the same reason the progression repair does: on
			// the wire this is indistinguishable from a wipe, and there are 27
			// charter regions to shed against a median account holding 5 chunks.
			//
			// This one fails WORSE than the others if refused, because the
			// completion flag below is set unconditionally and the migration never
			// re-runs: the server would keep the charter chunks, the next login's
			// union would restore them locally, and the strip would silently undo
			// itself permanently rather than retrying.
			declareIntentionalReset("charter seed strip removed "
				+ removed + " charter chunk(s)");
			setAccountState("unlockedChunks", String.join(",", kept));
		}
		configManager.setConfiguration("chunkblazer", CHARTER_SEED_STRIPPED_KEY, "true");
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
			log.warn("[CB-DIAG] apiClient is NULL — Guice injection failed for plugin class");
			return;
		}
		apiClient.login(rsn, fullHashRsn(rsn))
			.thenAccept(resp ->
			{
				// Only mark complete on a real OK / created response. Offline
				// or error responses leave the flag false so we retry next
				// LOGGED_IN tick instead of pretending we're done.
				if (resp != null && resp.isSuccess())
				{
					serverLoginDone = true;
					// Recognition is roster-driven; don't make the player wait for
					// the next 30s poll. Announce presence now and, once the
					// heartbeat is committed, refresh the roster so our own chat
					// icon — and anyone already online — lights up within ~a second.
					kickPresence();
				}
				hydrateFromLoginResponse(resp);
				maybeStartVerification(resp);
			})
			.exceptionally(e ->
			{
				log.warn("[CB-DIAG] login failed: {}", e.toString());
				return null;
			});
	}

	/**
	 * Chat hook with two jobs: tag fellow ChunkBlazer players' names with our
	 * chat icon, and watch for the local player typing the verification phrase.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Decorate other ChunkBlazer players' names with our chat icon, then
		// run the verification handshake check (which no-ops unless a nonce is
		// outstanding).
		maybeAddChatIcon(event);
		handleVerificationChat(event);
	}

	/**
	 * If the message's sender is an online ChunkBlazer player, prepend our chat
	 * icon to their name so plugin users recognize each other in chat. Runs on
	 * the client thread (event-bus), so mutating the message node is safe.
	 */
	private void maybeAddChatIcon(ChatMessage event)
	{
		if (!config.showChatIcons() || chatIconId < 0)
		{
			return;
		}
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.PUBLICCHAT && type != ChatMessageType.MODCHAT
			&& type != ChatMessageType.FRIENDSCHAT && type != ChatMessageType.CLAN_CHAT
			&& type != ChatMessageType.CLAN_GUEST_CHAT)
		{
			return;
		}
		String name = event.getName();
		if (name == null || !roster.isMember(name))
		{
			return;
		}
		int index = chatIconManager.chatIconIndex(chatIconId);
		if (index < 0)
		{
			return; // icons not loaded into the client yet
		}
		MessageNode node = event.getMessageNode();
		if (node == null)
		{
			return;
		}
		String imgTag = "<img=" + index + ">";
		String currentName = node.getName();
		if (currentName == null || currentName.contains(imgTag))
		{
			return; // already tagged this message
		}
		// ChunkBlazer dev/tester accounts get an orange [Dev] tag ahead of the
		// chat icon so they're recognizable in chat. The img-tag guard above also
		// prevents the [Dev] tag from being re-applied on message re-render.
		ChunkBlazerRoster.Entry entry = roster.get(name);
		String devTag = (entry != null && entry.isDev()) ? "<col=ff9d3c>[Dev]</col>" : "";
		node.setName(devTag + imgTag + currentName);
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
		// Just look for the nonce anywhere in the message. The nonce is 8
		// digits (~100M keyspace) so casual chat won't false-positive, and
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
						addPluginChatMessage("Account verified! You're all set.");
					}
				}
				else
				{
					log.warn("Verification POST rejected: {}",
						resp != null ? resp.getMessage() : "null response");
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

	/**
	 * Mirror server-side state into local RuneLite config when local is empty
	 * (fresh install / new machine). Does not overwrite existing local state —
	 * the active machine wins until the user explicitly resets.
	 *
	 * Mode lock: written if server says locked and local has no hash for this RSN.
	 * Unlocked regions: written if server has any and local is empty.
	 * Completed tasks: same rule.
	 */
	/**
	 * Keys holding progress that belongs to ONE account. Cleared together when a
	 * different account logs in on this RuneLite profile.
	 *
	 * <p>Everything here except {@code progressionBaseline} is mirrored on the
	 * server and comes straight back via {@link #hydrateFromLoginResponse}. The
	 * baseline is client-only, so a switch costs the departing account its frozen
	 * baseline — it re-captures at whatever its levels are on return, which is
	 * more restrictive than the original and therefore the safe direction.
	 */
	// False until the server's record for this session has been merged into local
	// config. Sync is DESTRUCTIVE and client-authoritative, so pushing before the
	// merge would overwrite the server with whatever half-bootstrapped state the
	// client happens to hold — which is how a clean profile wiped 14 unlocked
	// chunks on 2026-07-21. Reset on logout so every session must earn it again.
	private volatile boolean serverStateMerged;

	private static final String CONFIG_GROUP = "chunkblazer";

	// pointsSpent was added by the points-balance work AFTER this array was
	// written, and was never added here. clearAccountState() therefore wiped an
	// alt's EARNED points while leaving the main's SPEND in place — and the
	// balance is (earned - spent), so the alt was clamped to 0 and could not
	// unlock anything until it had out-earned the main's total spending.
	private static final String[] ACCOUNT_STATE_KEYS = {
		"unlockedChunks", "completedTasks", "assignedTasks", "regionRolledTasks",
		"currentTaskId", "currentTaskQuantity", "currentTaskProgress",
		"totalPoints", "pointsSpent", "bossTokens", "taskProgressData",
		"progressionBaseline",
	};

	// --- Per-account state accessors ---------------------------------------

	/**
	 * Whether RuneLite can currently tell us WHICH account is playing.
	 *
	 * <p>Backed by {@code ConfigManager.getRSProfileKey()}, which is derived from
	 * {@code client.getAccountHash()} and is null whenever no account is logged
	 * in. This is the gate that makes per-account storage possible at all:
	 * {@code setRSProfileConfiguration} SILENTLY DISCARDS writes issued while it
	 * is null — no exception, no return value, the data simply never lands.
	 *
	 * <p>Note this goes null on LOGOUT too, not just before the first login, and
	 * it is driven by {@code AccountHashChanged}/{@code WorldChanged}, which are
	 * not ordered against our own {@code GameStateChanged} handling. Anything
	 * that reads per-account state on the way out (notably the destructive
	 * logout sync) must not assume it is still available.
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
		return configManager.getConfiguration(CONFIG_GROUP, key);
	}

	/**
	 * Whether {@code unlockedChunks} actually EXISTS in stored config, as opposed
	 * to being conjured by {@link ChunkBlazerConfig#unlockedChunks()}'s interface
	 * default of {@code "12850"}.
	 *
	 * <p>Those two states are indistinguishable through the typed accessor, and
	 * that gap shipped a bug. {@link #clearAccountState} unsets the key on an
	 * account switch; {@link #mergeUnlockedRegionsFromServer} then returns early
	 * for an account the server has no regions for (i.e. every brand-new one), so
	 * nothing rewrites it. {@code config.unlockedChunks()} still answered
	 * {@code "12850"} from the default, so {@link #ensureStartingChunkUnlocked}
	 * concluded there was nothing to do and never persisted anything.
	 *
	 * <p>Inside this plugin that was invisible — the default is a faithful stand-in
	 * for "only the start chunk". But the standalone ChunkBlazer GPU addon reads
	 * the key RAW across the repo boundary
	 * ({@code configManager.getConfiguration("chunkblazer", "unlockedChunks")}),
	 * gets {@code null}, and greys the entire world including Lumbridge. Bruh
	 * Blazer hit exactly this on 2026-07-27: cleared at 21:11:03, rendered fully
	 * grey, and only corrected at 21:16:47 when his first real unlock finally
	 * wrote a concrete value. A second account in the same session never saw it,
	 * because the server had regions for it and the merge wrote them immediately.
	 *
	 * <p>So the invariant is stronger than "the start region reads as unlocked":
	 * the key must be ON DISK for out-of-process readers. Anything that consumes
	 * this value without our config defaults needs that.
	 */
	private boolean isUnlockedChunksPersisted()
	{
		String raw = getAccountState("unlockedChunks");
		return raw != null && !raw.trim().isEmpty();
	}

	/**
	 * Write one per-account value.
	 *
	 * <p>Deliberately WARNS AND WRITES when no account is known, rather than
	 * refusing. Storage is still profile-scoped, so the write does land; the
	 * warning exists to enumerate — from a real session's log rather than from
	 * guesswork — every code path that writes progress before RuneLite can say
	 * whose it is. Each one of those is a write that would silently vanish the
	 * moment the backing store moves to RSProfile.
	 *
	 * <p>When the store moves, this becomes a hard refusal.
	 */
	private void setAccountState(String key, Object value)
	{
		if (!isAccountStateAvailable())
		{
			log.warn("[CHUNKBLAZER] per-account write '{}' issued with no RS profile available — "
				+ "harmless today (storage is still profile-scoped) but this write would be "
				+ "SILENTLY DISCARDED once the store moves to RSProfile. Gate this caller.", key);
		}
		configManager.setConfiguration(CONFIG_GROUP, key, value);
	}

	/**
	 * Stop one account's progress leaking into another's.
	 *
	 * <p>ChunkBlazer's progress lives in RuneLite config, which is scoped to the
	 * PROFILE, not the account. Before this, an alt logging in on the same
	 * profile would find the main's completed tasks still sitting there;
	 * {@link #hydrateFromLoginResponse} only fills state in when local is EMPTY,
	 * so it skipped, and the next sync — which is destructive and
	 * client-authoritative — wrote the main's progress over the alt's server
	 * record. Silent, and it destroyed the alt's real data.
	 *
	 * <p>So the profile records WHOSE progress it currently holds. On a
	 * mismatch, the old account's state is cleared and hydration (running
	 * immediately after this) repopulates from the server record of the account
	 * actually logging in.
	 *
	 * <p>Only ever called from a SUCCESSFUL login response, which matters: the
	 * wipe is safe precisely because the server has just told us the authoritative
	 * state for this account. It must never run off a failed or offline login,
	 * or it would discard progress with nothing to restore it from.
	 *
	 * <p>Not a substitute for per-account config (RuneLite's
	 * {@code setRSProfileConfiguration}) — that would let two accounts coexist on
	 * one profile. This keeps the plugin's existing one-account-at-a-time model
	 * and just stops it corrupting data.
	 */
	private void reconcileAccountState(String rsn)
	{
		String owner = hashRsn(rsn);
		String stored = config.accountStateOwner();

		if (owner.equals(stored))
		{
			return;
		}

		if (stored == null || stored.isEmpty())
		{
			// First login after this shipped — there is no owner tag yet, and the
			// local progress is almost always this player's own, so the default
			// is to ADOPT it. Wiping on sight would delete every existing
			// player's progress on upgrade.
			//
			// accountModeHash is the one pre-existing per-account tag (format
			// "<rsnHash>:<MODE>"), so when it IS present it settles the question
			// without guessing: a mismatch proves the resident state belongs to a
			// different account, and adopting it would let this account sync over
			// that account's server record — the exact corruption this method
			// exists to stop. Absent (mode never locked) → fall through to adopt.
			if (localStateBelongsToAnotherAccount(owner))
			{
				log.warn("[CHUNKBLAZER] untagged local progress belongs to a different account "
					+ "(per accountModeHash) — clearing it rather than letting {} adopt it", rsn);
				clearAccountState(owner);
				addPluginChatMessage("Different account detected — loading " + rsn + "'s progress.");
				return;
			}

			configManager.setConfiguration("chunkblazer", "accountStateOwner", owner);
			return;
		}

		log.warn("[CHUNKBLAZER] account switch detected on this RuneLite profile — "
			+ "clearing the previous account's local progress so {}'s own state can load "
			+ "from the server", rsn);

		clearAccountState(owner);
		addPluginChatMessage("Different account detected — loading " + rsn + "'s progress.");
	}

	/**
	 * Whether the progress sitting in this profile demonstrably belongs to some
	 * other account, judged by {@code accountModeHash} ("&lt;rsnHash&gt;:&lt;MODE&gt;").
	 * Only ever returns true on a POSITIVE mismatch — an absent or malformed tag
	 * yields false, so the caller adopts rather than destroys progress it can't
	 * prove is foreign.
	 */
	private boolean localStateBelongsToAnotherAccount(String owner)
	{
		String modeHash = config.accountModeHash();
		if (modeHash == null || !modeHash.contains(":"))
		{
			return false;
		}
		return !modeHash.startsWith(owner);
	}

	/** Drop every per-account key plus the in-memory mirrors, and take ownership. */
	private void clearAccountState(String owner)
	{
		for (String key : ACCOUNT_STATE_KEYS)
		{
			configManager.unsetConfiguration("chunkblazer", key);
		}
		configManager.setConfiguration("chunkblazer", "accountStateOwner", owner);

		activeTasks.clear();
		activeTask = null;
		completedTaskCache.clear();
		cachedProgressionBaseline = null;
		cachedBaselineOwner = null;
		taskModuleManager.clearTask();
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
		log.info("[CHUNKBLAZER] restored {} unlocked chunk(s) from the server (had {}, now {})",
			merged.size() - before, before, merged.size());
	}

	/** Union the server's completed tasks into local config. */
	private void mergeCompletedTasksFromServer(PlayerLoginResponse.PlayerData pdata)
	{
		if (pdata == null || pdata.getCompletedTasks() == null || pdata.getCompletedTasks().isEmpty())
		{
			return;
		}

		Set<String> merged = new LinkedHashSet<>(getCompletedTaskIds());
		int before = merged.size();
		merged.addAll(pdata.getCompletedTasks());
		if (merged.size() == before)
		{
			return;
		}

		setAccountState("completedTasks", String.join(",", merged));
		log.info("[CHUNKBLAZER] restored {} completed task(s) from the server (had {}, now {})",
			merged.size() - before, before, merged.size());
	}

	private void hydrateFromLoginResponse(PlayerLoginResponse response)
	{
		if (response == null || !response.isSuccess())
		{
			return;
		}
		PlayerLoginResponse.PlayerData pdata = response.getPlayer();

		// Dev-tool authorization, straight off the login response. Set outside the
		// clientThread hop so the panel's next repaint sees it, and always assigned
		// (not just when true) so a demoted account loses the tools on next login.
		devAuthorized = pdata != null && pdata.isDev();

		clientThread.invokeLater(() ->
		{
			String rsn = getPlayerName();
			if (rsn == null)
			{
				return;
			}

			// MUST run before the hydrate steps below. If this login is a
			// different account than the one whose state is sitting in config,
			// that state is cleared here so the "hydrate only when local is
			// empty" rules below actually fire and repopulate from THIS account's
			// server record.
			reconcileAccountState(rsn);

			// 1. Mode lock reconciliation, both directions.
			if (response.isModeLocked() && response.getGameMode() != null && !isModeLocked())
			{
				// Server has a lock we don't — adopt it (e.g. fresh install on a new machine).
				GameMode serverMode = response.getGameMode();
				String modeKey = hashRsn(rsn) + ":" + serverMode.name();
				setAccountState("accountModeHash", modeKey);
				setAccountState("gameMode", serverMode);
			}
			else if (isModeLocked() && !response.isModeLocked())
			{
				// We're locked locally but the server isn't — the original lock-mode
				// mirror call was dropped or sent to a different server (e.g. before the
				// API URL was corrected). Re-push so the server catches up; otherwise the
				// account stays permanently unranked. Fire-and-forget: if it fails, the
				// next login retries (local stays locked, server still unlocked).
				GameMode localMode = getGameMode(); // authoritative locked mode, not the raw dropdown
				if (localMode != null && config.apiEnabled() && apiClient != null)
				{
					apiClient.lockGameMode(localMode);
				}
			}

			// 2 & 3. Unlocked regions and completed tasks — MERGED, not
			// "overwrite only when local is empty".
			//
			// The old rule could never fire on a real reinstall, which is the
			// case it existed for. By the time a login response arrives, the
			// plugin has already bootstrapped local state: startUp() and every
			// loadActiveTasks() call ensureStartingChunkUnlocked() (writing the
			// Lumbridge region), and the Global Tasks backfill has written the
			// already-finished quests. Local was therefore never empty, hydration
			// always skipped, and the account was left holding ONLY the
			// bootstrap — 1 chunk instead of 15 (observed 2026-07-21 moving
			// SeaShantyBoy to a clean profile). The destructive sync then pushed
			// that back over the server record.
			//
			// A union is immune to that ordering entirely: it doesn't care
			// whether the bootstrap ran first, and it can't lose either side, so
			// progress made offline (or before a failed sync) survives a login
			// just as server-side progress survives a reinstall.
			mergeUnlockedRegionsFromServer(pdata);
			mergeCompletedTasksFromServer(pdata);

			// 4. Points.
			//
			// The server's total_points is lifetime EARNED; the client's
			// totalPoints is a spendable BALANCE. Writing one into the other is
			// what handed a real player 939 phantom points on 2026-07-21 — it
			// silently erased every chunk purchase they had made, and could
			// never self-correct because the server holds no balance.
			//
			// So nothing is copied. Earned is recomputed from the task list we
			// just merged, spend is reconciled by taking the higher of the two
			// (it only ever grows — you cannot un-unlock a chunk — so the
			// maximum is the correct merge and is safe across profiles), and
			// the balance falls out of the two.
			deriveInitialPointsSpent();
			if (pdata != null && pdata.getPointsSpent() > config.pointsSpent())
			{
				log.info("[CHUNKBLAZER] restored points spent from the server (had {}, now {})",
					config.pointsSpent(), pdata.getPointsSpent());
				setAccountState("pointsSpent", pdata.getPointsSpent());
			}
			// AFTER the monotonic merge, deliberately. The corrupt figure this
			// repairs is already stored server-side, so running it any earlier
			// would just see the server's copy restored over the top of it. The
			// corrected value then syncs upward and settles the server too.
			migrateRepairImpossiblePointsSpent();
			recomputePointsBalance();

			// Rebuild the in-memory active-task list from the just-hydrated config
			// (unlocked regions / completed tasks). Without this, a fresh install or
			// post-reset login shows an EMPTY task list until the plugin is toggled
			// off/on — because the earlier LOGGED_IN loadActiveTasks() ran against
			// the then-empty local config, and hydration only updated config, not
			// the live task list. Idempotent + cheap.
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

	/**
	 * Presence heartbeat. Reads world + lastRegionId on the client thread,
	 * then fires apiClient.sendHeartbeat which itself short-circuits if
	 * playerApiKey hasn't been set by login yet. Safe to fire blindly.
	 */
	private void sendHeartbeatToServer()
	{
		if (!config.apiEnabled())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			// Self-heal: if we're in-game but never completed the server login
			// (plugin enabled while already logged in, or the server was down
			// when we first tried), recover it here rather than waiting for a
			// fresh LOGGED_IN event that may never come. Heartbeats no-op until
			// login stores our api_key, so without this the player stays
			// invisible — no presence, no recognition icons. On success
			// loginToServer() calls kickPresence(), which heartbeats and
			// refreshes the roster, so recognition lights up within ~a second.
			if (!serverLoginDone)
			{
				loginToServer();
				return;
			}
			int world = client.getWorld();
			int region = lastRegionId;
			// Always visible: the "Visible to Others" toggle was removed (was broken).
			apiClient.sendHeartbeat(world, region, true);
		});
	}

	/**
	 * Refresh the online-player roster that powers the recognition surfaces.
	 * Skips the network call entirely when every recognition toggle is off, or
	 * when we're not in-game, so we stay polite to the server's rate caps.
	 */
	private void refreshRoster()
	{
		if (!config.apiEnabled() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!config.showOtherPlayers() && !config.showChatIcons()
			&& !config.showMinimapHighlight() && !config.showPlayerOutline())
		{
			return;
		}
		apiClient.getOnlinePlayers(-1)
			.thenAccept(roster::update)
			.exceptionally(e ->
			{
				return null;
			});
	}

	/**
	 * Immediate presence kick, fired once on login instead of waiting for the
	 * 30s scheduled heartbeat. Announces this player to the server now, and once
	 * the heartbeat is committed (we chain on the returned future) refreshes the
	 * roster so the player's own chat icon and anyone already online light up
	 * within roughly a second of logging in.
	 */
	private void kickPresence()
	{
		if (!config.apiEnabled() || apiClient == null)
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			int world = client.getWorld();
			int region = lastRegionId;
			// Always visible: the "Visible to Others" toggle was removed (was broken).
			apiClient.sendHeartbeat(world, region, true)
				.whenComplete((v, t) -> refreshRoster());
		});
	}

	/**
	 * Periodic save-state sync. Runs on the executor thread; client state is
	 * read by hopping to the client thread first.
	 */
	private void syncToServer()
	{
		if (!config.apiEnabled())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!serverStateMerged)
		{
			// The login response hasn't been merged yet. Syncing now would push
			// bootstrap-only local state (1 chunk, backfilled quests) over the
			// account's real server record. Skip; the next tick retries.
			return;
		}
		clientThread.invoke(() ->
		{
			PlayerSyncRequest req = buildSyncRequest();
			if (req == null)
			{
				return;
			}
			boolean declaredReset = req.isIntentionalReset();
			apiClient.syncPlayerState(req)
				.thenAccept(resp ->
				{
					// Only retire the reset declaration once the server has actually
					// accepted it. Clearing it on send would strand a reset behind one
					// dropped request: the retry would arrive without the flag, be
					// refused as a destructive drop, and the next login's union would
					// restore everything the player asked to clear.
					if (resp != null && resp.isSuccess() && declaredReset)
					{
						pendingIntentionalReset = false;
						log.info("[CHUNKBLAZER] intentional reset accepted by the server");
					}
				});
		});
	}

	/**
	 * Set when the player deliberately destroys their own progress, and carried
	 * until a sync carrying it succeeds.
	 *
	 * <p>Sticky because the destruction and the sync are minutes apart: a reset
	 * edits local config, and the next periodic sync (or the logout sync) is what
	 * the server actually sees. Without this the server cannot tell a deliberate
	 * reset from the client bugs its drop guard exists to stop — they are the same
	 * shape on the wire.
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
		log.warn("[CHUNKBLAZER] intentional reset declared ({}) — the next sync will be "
			+ "allowed to drop progress server-side", reason);
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

		List<String> completed = new ArrayList<>(getCompletedTaskIds());

		GameMode mode = getGameMode(); // authoritative: locked mode wins over the raw dropdown
		String modeName = (mode != null && isModeLocked()) ? mode.name() : null;

		return PlayerSyncRequest.builder()
			.playerHash(hashRsn(rsn))
			.displayName(rsn)
			.accountType("NORMAL")
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
			.clientPoints(config.totalPoints())
			.pointsSpent(config.pointsSpent())
			.completedTasks(completed)
			.intentionalReset(pendingIntentionalReset)
			.timestamp(System.currentTimeMillis())
			.clientVersion("1.0.0")
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

	public Set<String> getUnlockedRegionIds()
	{
		String chunkList = config.unlockedChunks();
		if (chunkList == null || chunkList.isEmpty())
		{
			return new HashSet<>();
		}
		return Arrays.stream(chunkList.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
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
		Set<String> unlocked = getUnlockedRegionIds();
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

	/**
	 * Distinct unlocked chunks as sorted display labels ("Name (regionId)"), one
	 * per chunk — multi-region chunks (surface + dungeon) collapse to a single
	 * entry. Backs the read-only "Unlocked Chunks" list in the side panel.
	 */
	public List<String> getUnlockedChunkDisplayNames()
	{
		java.util.TreeSet<String> labels = new java.util.TreeSet<>();
		for (String idStr : getUnlockedRegionIds())
		{
			try
			{
				int id = Integer.parseInt(idStr.trim());
				NuzlockeChunk chunk = chunksByRegionId.get(id);
				if (chunk != null && chunk.getRegionIds() != null && !chunk.getRegionIds().isEmpty())
				{
					labels.add(chunk.getName() + " (" + chunk.getRegionIds().get(0) + ")");
				}
				else
				{
					labels.add("Region " + id);
				}
			}
			catch (NumberFormatException ignored)
			{
				// skip malformed id
			}
		}
		return new ArrayList<>(labels);
	}

	/**
	 * Dev-only: unlock one or more regions by ID (comma-separated), bypassing the
	 * point cost and adjacency gate. Replaces the old hand-editable "Unlocked
	 * Regions" config field as the testing shortcut.
	 */
	public void devUnlockRegions(String csv)
	{
		if (devToolsDenied("unlockRegions") || csv == null)
		{
			return;
		}
		for (String part : csv.split(","))
		{
			String t = part.trim();
			if (t.isEmpty())
			{
				continue;
			}
			try
			{
				unlockRegionFree(Integer.parseInt(t));
			}
			catch (NumberFormatException e)
			{
				log.warn("devUnlockRegions: ignoring bad region id '{}'", t);
			}
		}
		loadActiveTasks();
		panel.updatePanel();
	}

	// --- Task Methods ---

	/**
	 * Get the tasks available for the current region (only the 4-5 rolled tasks).
	 */
	public List<NuzlockeTask> getCurrentRegionTasks()
	{
		int regionId = getCurrentRegionId();
		if (regionId < 0)
		{
			return new ArrayList<>();
		}

		// Get rolled tasks for this region
		Set<String> rolledTaskIds = getRolledTasksForRegion(regionId);

		// If no tasks rolled yet for this region, roll them now
		if (rolledTaskIds.isEmpty() && isRegionUnlocked(regionId))
		{
			rolledTaskIds = rollTasksForRegion(regionId);
		}

		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk == null || chunk.getTasks() == null)
		{
			return new ArrayList<>();
		}

		// Return only the tasks that were rolled for this region
		List<NuzlockeTask> rolledTasks = new ArrayList<>();
		for (NuzlockeTask task : chunk.getTasks())
		{
			if (rolledTaskIds.contains(task.getTaskId()))
			{
				rolledTasks.add(task);
			}
		}

		return rolledTasks;
	}

	/**
	 * Check if a task has been assigned (and thus cannot be assigned again).
	 */
	public boolean isTaskAssigned(String taskId)
	{
		return getAssignedTaskIds().contains(taskId);
	}

	private void loadOrAssignTask()
	{
		// Load all active tasks for the current region
		loadActiveTasks();
	}

	/**
	 * Load and activate all rolled tasks for unlocked regions.
	 * All 5 rolled tasks per region are active simultaneously.
	 */
	private void loadActiveTasks()
	{
		// Nothing may be loaded — and above all nothing may be WRITTEN — until
		// RuneLite can say whose account this is. This method both bootstraps
		// (ensureStartingChunkUnlocked, the two migrations) and rolls tasks, so
		// running it account-less writes progress that belongs to nobody and
		// then attributes it to whoever logs in next.
		//
		// The in-memory task state is cleared rather than left standing, so the
		// previous account's tasks cannot linger in the panel across a logout or
		// an account switch.
		if (!isAccountStateAvailable())
		{
			log.debug("[CHUNKBLAZER] loadActiveTasks skipped — no account known yet");
			activeTasks.clear();
			activeTask = null;
			taskModuleManager.clearTask();
			return;
		}

		// The default starting chunk (Lumbridge / 12850) is unlocked with tasks
		// rolled for EVERY account, no matter what — it's the universal starting
		// point. Casual mode lets a player additionally unlock the chunk they're
		// standing in (a one-time choice), but it never replaces this one. Enforcing
		// it here — the single path every task (re)load goes through — guarantees the
		// start chunk can never end up locked or task-less. Idempotent + cheap.
		ensureStartingChunkUnlocked();
		migrateStripSeededCharterChunks();
		migrateRepairBogusProgressionBaseline();

		activeTasks.clear();
		taskModuleManager.clearTask(); // Clear module state to prevent duplicates

		Set<String> completedTaskIds = getCompletedTaskIds();
		Set<String> addedTaskIds = new HashSet<>(); // Track added tasks to prevent duplicates

		// Tasks still behind a face-down card are NOT active and must not be registered
		// with any module — that is the whole point of the reveal gate. They stay in
		// regionRolledTasks (the roll is committed) and rejoin here once flipped.
		Set<String> unrevealed = new HashSet<>(getUnrevealedTaskIds());

		Set<String> unlockedRegions = getUnlockedRegionIds();

		for (String regionIdStr : unlockedRegions)
		{
			try
			{
				int regionId = Integer.parseInt(regionIdStr);
				Set<String> rolledTaskIds = getRolledTasksForRegion(regionId);

				// Free-list chunks have no tasks BY DESIGN, so they never have a roll to
				// find and would be re-attempted on every loadActiveTasks — which runs
				// constantly. Skipping them keeps the "No chunk found for region N"
				// warning meaning what it says: a chunk that should exist and doesn't.
				if (rolledTaskIds.isEmpty() && !isFreeUnlockableRegion(regionId))
				{
					rolledTaskIds = rollTasksForRegion(regionId);
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

							// Self-heal stuck tasks where progress saved but completion never fired (e.g. throw in popup code between onProgressUpdated and onTaskCompleted).
							if (task.getCurrentProgress() >= task.getTargetQuantity())
							{
								completedTaskCache.put(taskId, task);
								addPoints(task.getBasePoints());
								markTaskCompleted(taskId);
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
			log.warn("PROGRESS REGRESSION: task '{}' (id={}) in-memory={}, restoring from config={} — caller stack:",
				task.getName(), task.getTaskId(), inMemoryProgress, savedProgress, new Throwable());
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
					// Multi-item (set) task: the saved target is the SUM across
					// items, not any single item's quantity. Pinning the sum onto
					// the first item inflated that slot's requirement (Splitbark
					// helm became x5 for a 5-piece set), letting duplicate copies
					// of one piece count toward the whole set. Per-item quantities
					// here are deterministic (authored fixed values, default 1),
					// so clear stale caches and let each item report its own.
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
			// First time - roll the target quantity. Clear any stale cached
			// rolledQuantity first: devResetAll / devResetTasks / rerollTask
			// wipe the config but don't touch the in-memory cache on
			// NuzlockeTask instances in allChunks, so a value pinned from a
			// previous (possibly corrupted) saved target would survive the
			// reset and be reused here as if it were a fresh roll. Without
			// this, devResetAll was a no-op for any task whose saved target
			// had been corrupted to 1 — the user pressed Reset, the config
			// cleared, and the same 1 got rolled and re-saved.
			//
			// The roll then caches itself on the TargetNpc / RequiredItem /
			// RequiredObject so module code that calls getRequiredQuantity()
			// later in this session gets the same value.
			targetQty = 1;
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
			// Save the rolled target quantity
			saveTaskProgress(task.getTaskId(), savedProgress, targetQty);
		}

		task.setTargetQuantity(targetQty);
		task.setCurrentProgress(savedProgress);
		task.setCompleted(false);
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

	public void assignNewTask()
	{
		Set<String> unlockedRegions = getUnlockedRegionIds();
		Set<String> assignedTaskIds = getAssignedTaskIds();
		List<NuzlockeTask> eligibleTasks = new ArrayList<>();

		// Gather eligible tasks from unlocked regions (only from rolled task pools)
		for (String regionIdStr : unlockedRegions)
		{
			try
			{
				int regionId = Integer.parseInt(regionIdStr);

				// Get or roll the tasks for this region
				Set<String> rolledTaskIds = getRolledTasksForRegion(regionId);
				if (rolledTaskIds.isEmpty())
				{
					// First time seeing this region - roll 4-5 tasks
					rolledTaskIds = rollTasksForRegion(regionId);
				}

				// Only consider tasks that are in the rolled set AND not yet assigned
				NuzlockeChunk chunk = chunksByRegionId.get(regionId);
				if (chunk != null && chunk.getTasks() != null)
				{
					for (NuzlockeTask task : chunk.getTasks())
					{
						String taskId = task.getTaskId();
						if (rolledTaskIds.contains(taskId) &&
							!assignedTaskIds.contains(taskId) &&
							!task.isLocked())
						{
							eligibleTasks.add(task);
						}
					}
				}
			}
			catch (NumberFormatException e)
			{
				log.warn("Invalid region ID: {}", regionIdStr);
			}
		}

		if (eligibleTasks.isEmpty())
		{
			activeTask = null;
			saveCurrentTask();
			panel.showNoTasksMessage();
			return;
		}

		// Weighted random selection
		int totalWeight = eligibleTasks.stream()
			.mapToInt(NuzlockeTask::getAssignmentWeight)
			.sum();

		int roll = random.nextInt(totalWeight);
		int cumulative = 0;

		for (NuzlockeTask task : eligibleTasks)
		{
			cumulative += task.getAssignmentWeight();
			if (roll < cumulative)
			{
				activeTask = task;
				break;
			}
		}

		if (activeTask != null)
		{
			// Mark this task as assigned (can never be assigned again)
			markTaskAssigned(activeTask.getTaskId());

			// Calculate target quantity for this task instance
			int targetQty = 1;
			if (activeTask.getTargetNpc() != null)
			{
				targetQty = activeTask.getTargetNpc().getRequiredQuantity();
			}
			else if (activeTask.getRequiredItems() != null && !activeTask.getRequiredItems().isEmpty())
			{
				targetQty = activeTask.getRequiredItems().get(0).getRequiredQuantity();
			}

			activeTask.setTargetQuantity(targetQty);
			activeTask.setCurrentProgress(0);
			activeTask.setCompleted(false);

			// Route task to appropriate module for auto-tracking
			taskModuleManager.assignTask(activeTask);

			saveCurrentTask();
		}
		else
		{
			// No task assigned, clear module tracking
			taskModuleManager.clearTask();
		}

		panel.updateTaskDisplay();
	}

	// --- Rolled Tasks Management ---

	private static final int MIN_TASKS_PER_REGION = 4;
	private static final int MAX_TASKS_PER_REGION = 5;

	/**
	 * Roll 4-5 random tasks for a region using weighted selection.
	 * Tasks are globally unique - once a task is assigned anywhere, it cannot be rolled again.
	 */
	private Set<String> rollTasksForRegion(int regionId)
	{
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk == null)
		{
			log.warn("rollTasksForRegion: No chunk found for region {}. Total chunks in map: {}",
				regionId, chunksByRegionId.size());
			return new HashSet<>();
		}
		if (chunk.getTasks() == null || chunk.getTasks().isEmpty())
		{
			log.warn("rollTasksForRegion: Chunk {} ({}) has no tasks", regionId, chunk.getName());
			return new HashSet<>();
		}

		// Get tasks already rolled for this region
		Set<String> alreadyRolledForThisRegion = getRolledTasksForRegion(regionId);

		// Get ALL globally assigned tasks (tasks assigned in ANY region)
		Set<String> globallyAssignedTasks = getAssignedTaskIds();

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

		// Determine how many tasks to roll (4-5, or all if fewer available)
		int numToRoll = MIN_TASKS_PER_REGION + random.nextInt(MAX_TASKS_PER_REGION - MIN_TASKS_PER_REGION + 1);
		numToRoll = Math.min(numToRoll, availableTasks.size());

		// Use weighted random selection based on assignment_weight
		Set<String> rolledIds = new HashSet<>();
		List<NuzlockeTask> remainingTasks = new ArrayList<>(availableTasks);

		for (int i = 0; i < numToRoll && !remainingTasks.isEmpty(); i++)
		{
			NuzlockeTask selected = selectWeightedRandom(remainingTasks);
			if (selected != null)
			{
				rolledIds.add(selected.getTaskId());
				remainingTasks.remove(selected);

				// NOTE: do NOT mark rolled tasks as "assigned" here. In the current
				// all-rolled-tasks-active model, "assigned" is what the panel treats as
				// "done/unavailable" (greyed out, counted as 0 available). Marking at
				// roll time made every freshly-rolled task render greyed with a "(0/N)"
				// header — i.e. the player appears to get no initial tasks after a
				// reset + mode select. Assignment is only meaningful for the legacy
				// one-task-at-a-time flow (assignNewTask), which is no longer used.
			}
		}

		// Save to config. The roll is committed here and never revisited — see
		// markTasksUnrevealed for why that has to happen BEFORE the reveal gate.
		saveRolledTasksForRegion(regionId, rolledIds);

		// Park them behind cards. loadActiveTasks skips anything still pending, so
		// these do not become active until the player flips them.
		markTasksUnrevealed(rolledIds);

		return rolledIds;
	}

	/**
	 * Select a random task using weighted probability based on assignment_weight.
	 * Higher weight = higher chance of being selected.
	 */
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

	/**
	 * Get the rolled task IDs for a region from config.
	 */
	// --- Task reveal cards -------------------------------------------------------
	//
	// A rolled task waits behind a face-down card until the player flips it (see
	// TaskCardOverlay). Until then its id sits in `unrevealedTasks` and loadActiveTasks
	// skips it, so it is not in the active list and no module is tracking it.
	//
	// The ROLL is still committed the moment it happens (regionRolledTasks, written by
	// rollTasksForRegion). Only the reveal is deferred. That ordering is deliberate:
	// if the pending set decided the task instead of merely hiding it, a player could
	// leave bad cards unflipped and reroll them by relogging.

	/** Task ids rolled but not yet flipped, in a stable order for a stable card layout. */
	public List<String> getUnrevealedTaskIds()
	{
		String data = config.unrevealedTasks();
		if (data == null || data.isEmpty())
		{
			return new ArrayList<>();
		}
		List<String> ids = new ArrayList<>();
		for (String id : data.split(","))
		{
			String trimmed = id.trim();
			if (!trimmed.isEmpty() && !ids.contains(trimmed))
			{
				ids.add(trimmed);
			}
		}
		return ids;
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

		// Now it counts: pick it up as an active task and repaint.
		loadActiveTasks();
		if (panel != null)
		{
			panel.updatePanel();
		}
	}

	/** Exposed for the card overlay, which only has ids to work with. */
	public NuzlockeTask getTaskById(String taskId)
	{
		return findTaskById(taskId);
	}

	public Set<String> getRolledTasksForRegion(int regionId)
	{
		String data = config.regionRolledTasks();
		if (data == null || data.isEmpty())
		{
			return new HashSet<>();
		}

		// Format: "regionId:task1,task2,task3|regionId2:task4,task5"
		for (String regionEntry : data.split("\\|"))
		{
			if (regionEntry.isEmpty()) continue;

			String[] parts = regionEntry.split(":");
			if (parts.length == 2)
			{
				try
				{
					int entryRegionId = Integer.parseInt(parts[0]);
					if (entryRegionId == regionId)
					{
						Set<String> taskIds = new HashSet<>();
						for (String taskId : parts[1].split(","))
						{
							if (!taskId.isEmpty())
							{
								taskIds.add(taskId);
							}
						}
						return taskIds;
					}
				}
				catch (NumberFormatException e)
				{
					log.warn("Invalid region ID in rolled tasks: {}", parts[0]);
				}
			}
		}

		return new HashSet<>();
	}

	private void saveRolledTasksForRegion(int regionId, Set<String> taskIds)
	{
		String data = config.regionRolledTasks();
		Map<Integer, Set<String>> regionTasks = new HashMap<>();

		// Parse existing data
		if (data != null && !data.isEmpty())
		{
			for (String regionEntry : data.split("\\|"))
			{
				if (regionEntry.isEmpty()) continue;

				String[] parts = regionEntry.split(":");
				if (parts.length == 2)
				{
					try
					{
						int entryRegionId = Integer.parseInt(parts[0]);
						Set<String> existingTasks = new HashSet<>();
						for (String taskId : parts[1].split(","))
						{
							if (!taskId.isEmpty())
							{
								existingTasks.add(taskId);
							}
						}
						regionTasks.put(entryRegionId, existingTasks);
					}
					catch (NumberFormatException e)
					{
						// Skip invalid entries
					}
				}
			}
		}

		// Update with new data
		regionTasks.put(regionId, taskIds);

		// Serialize back
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Set<String>> entry : regionTasks.entrySet())
		{
			if (sb.length() > 0) sb.append('|');
			sb.append(entry.getKey()).append(':').append(String.join(",", entry.getValue()));
		}

		setAccountState("regionRolledTasks", sb.toString());
	}

	// --- Assigned Tasks Management ---

	private Set<String> getAssignedTaskIds()
	{
		String assigned = config.assignedTasks();
		if (assigned == null || assigned.isEmpty())
		{
			return new HashSet<>();
		}
		return Arrays.stream(assigned.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
	}

	private void markTaskAssigned(String taskId)
	{
		String assigned = config.assignedTasks();
		if (assigned == null || assigned.isEmpty())
		{
			assigned = taskId;
		}
		else if (!getAssignedTaskIds().contains(taskId))
		{
			assigned = assigned + "," + taskId;
		}
		setAccountState("assignedTasks", assigned);
	}

	public void rerollTask()
	{
		int currentRegion = getCurrentRegionId();

		// Clear rolled tasks for current region
		if (currentRegion > 0)
		{
			clearRolledTasksForRegion(currentRegion);
		}

		// DEV: Clear globally assigned tasks so reroll can get fresh tasks
		// This bypasses the "no duplicate tasks globally" rule for testing
		setAccountState("assignedTasks", "");

		// Clear task progress data
		setAccountState("taskProgressData", "");

		// Clear module state
		taskModuleManager.clearTask();

		// Clear active tasks
		activeTasks.clear();
		activeTask = null;

		// Re-roll and load tasks
		loadActiveTasks();
		panel.updatePanel();

	}

	/**
	 * Guard for every dev tool that mutates progress. Hiding the panel is the
	 * primary gate; this is the backstop, because the panel is only UI and these
	 * methods are public. A dev-granted task is indistinguishable from an earned
	 * one at sync time — it's a real catalog task, so the server's points
	 * recompute matches the client and Tier-0 anti-cheat stays silent — so an
	 * ungated call here is an invisible self-grant.
	 */
	private boolean devToolsDenied(String action)
	{
		if (devAuthorized)
		{
			return false;
		}
		log.warn("[CHUNKBLAZER] refused dev action '{}': account not authorized by server", action);
		return true;
	}

	public void devCompleteActiveTask()
	{
		if (devToolsDenied("completeActiveTask") || activeTask == null)
		{
			return;
		}

		completeTask(activeTask);
	}

	/**
	 * Complete a specific task (used by dev tools when a task is selected)
	 */
	public void devCompleteSpecificTask(NuzlockeTask task)
	{
		if (devToolsDenied("completeSpecificTask") || task == null)
		{
			return;
		}

		// Check if this task is in our active tasks list
		if (!activeTasks.contains(task))
		{
			return;
		}

		completeTask(task);
	}

	public void devAddPoints(int points)
	{
		if (devToolsDenied("addPoints"))
		{
			return;
		}

		int current = config.totalPoints();
		setAccountState("totalPoints", current + points);
	}

	public void devResetTasks()
	{
		if (devToolsDenied("resetTasks"))
		{
			return;
		}

		// Dev accounts are already exempt from the server's drop guard, but say it
		// anyway: the exemption is a property of the account, this is a property of
		// the operation, and only the second one stays true if the account is ever
		// demoted.
		declareIntentionalReset("devResetTasks");

		// Clear rolled tasks for current region only
		int currentRegion = getCurrentRegionId();
		if (currentRegion > 0)
		{
			clearRolledTasksForRegion(currentRegion);
		}

		// Log current state before clearing

		// Clear completed tasks
		setAccountState("completedTasks", "");
		// Clear task progress data
		setAccountState("taskProgressData", "");
		// Clear assigned tasks
		setAccountState("assignedTasks", "");
		// Also clear rolled tasks for ALL regions to fully reset
		setAccountState("regionRolledTasks", "");

		// Verify the clear worked

		// Clear module state
		taskModuleManager.clearTask();

		// Clear active tasks
		activeTasks.clear();
		activeTask = null;
		completedTaskCache.clear();


		// Re-roll and load tasks
		loadActiveTasks();
		panel.updatePanel();
	}

	/**
	 * Clear the rolled tasks for a specific region so they can be re-rolled.
	 */
	private void clearRolledTasksForRegion(int regionId)
	{
		String data = config.regionRolledTasks();
		if (data == null || data.isEmpty())
		{
			return;
		}

		StringBuilder newData = new StringBuilder();
		for (String entry : data.split("\\|"))
		{
			if (entry.isEmpty()) continue;
			String[] parts = entry.split(":");
			if (parts.length >= 1)
			{
				try
				{
					int entryRegionId = Integer.parseInt(parts[0]);
					if (entryRegionId != regionId)
					{
						if (newData.length() > 0) newData.append('|');
						newData.append(entry);
					}
				}
				catch (NumberFormatException e)
				{
					// Keep malformed entries
					if (newData.length() > 0) newData.append('|');
					newData.append(entry);
				}
			}
		}
		setAccountState("regionRolledTasks", newData.toString());
	}

	public void devResetAll()
	{
		if (devToolsDenied("resetAll"))
		{
			return;
		}

		// The most destructive operation the plugin has — it drops every task AND
		// every chunk but the starting one. Exactly the shape the server's drop
		// guard refuses, so it has to declare itself.
		declareIntentionalReset("devResetAll");

		// Reset tasks
		setAccountState("regionRolledTasks", "");
		setAccountState("assignedTasks", "");
		setAccountState("completedTasks", "");
		setAccountState("taskProgressData", "");
		taskModuleManager.clearTask();
		activeTask = null;
		activeTasks.clear();
		completedTaskCache.clear();
		saveCurrentTask();

		// Reset points
		setAccountState("totalPoints", 0);

		// Reset unlocked chunks to the free starting chunk only. Every other
		// chunk must be unlocked with points after reset.
		setAccountState("unlockedChunks", String.valueOf(DEFAULT_START_REGION));

		// Reset game mode lock
		setAccountState("accountModeHash", "");
		setAccountState("gameMode", GameMode.CASUAL);


		// Re-roll and load tasks for the now-only starting chunk so the panel populates.
		loadActiveTasks();
	}

	private void completeTask(NuzlockeTask task)
	{
		completeTasks(Collections.singletonList(task));
	}

	/**
	 * Complete one or many tasks, doing the expensive settle-up work ONCE.
	 *
	 * The per-task work (cache, remove from active) scales linearly and is
	 * cheap. The settle-up work does NOT: two config writes plus five panel
	 * rebuilds, and updateCompletedTasks() re-resolves every completed id.
	 * Looping completeTask() over a batch therefore costs 2N config writes and
	 * 5N rebuilds — that is what froze the client when ~150 quest tasks
	 * completed in one tick (2026-07-19).
	 *
	 * Any future task source that can satisfy many tasks at once — skill-level
	 * thresholds, retroactive unlocks — must come through here rather than
	 * looping the single-task version.
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

			if (panel != null)
			{
				// Clear selected task if it was the completed one
				panel.clearSelectedTaskIfMatch(task);
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

		if (panel != null)
		{
			panel.updateStats();
			panel.updateTaskDisplay();
			panel.updateCompletedTasks();
			panel.updateGlobalTasks();
			panel.updateTaskList();
		}
	}

	private void saveCurrentTask()
	{
		if (activeTask != null)
		{
			setAccountState("currentTaskId", activeTask.getTaskId());
			setAccountState("currentTaskQuantity", activeTask.getTargetQuantity());
			setAccountState("currentTaskProgress", activeTask.getCurrentProgress());
		}
		else
		{
			setAccountState("currentTaskId", "");
			setAccountState("currentTaskQuantity", 1);
			setAccountState("currentTaskProgress", 0);
		}
	}

	/**
	 * Append many task ids to the completed list in a SINGLE config write.
	 * markTaskCompleted() per task means one read + one write each, which is
	 * what made the Global Tasks backfill unusable — see
	 * backfillAndRegisterGlobalTasks().
	 */
	private void markTasksCompleted(Collection<String> taskIds)
	{
		if (taskIds == null || taskIds.isEmpty())
		{
			return;
		}

		String completed = config.completedTasks();
		StringBuilder sb = new StringBuilder(completed == null ? "" : completed);
		for (String taskId : taskIds)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(taskId);
		}
		setAccountState("completedTasks", sb.toString());
	}

	private void markTaskCompleted(String taskId)
	{
		String completed = config.completedTasks();
		if (completed == null || completed.isEmpty())
		{
			completed = taskId;
		}
		else
		{
			completed = completed + "," + taskId;
		}
		setAccountState("completedTasks", completed);
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
		String completed = config.completedTasks();
		if (completed == null || completed.isEmpty())
		{
			return new HashSet<>();
		}
		return Arrays.stream(completed.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
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
		String data = config.regionRolledTasks();
		if (data != null && !data.isEmpty())
		{
			for (String regionEntry : data.split("\\|"))
			{
				if (regionEntry.isEmpty()) continue;

				String[] parts = regionEntry.split(":");
				if (parts.length == 2)
				{
					try
					{
						int regionId = Integer.parseInt(parts[0]);
						String[] tasks = parts[1].split(",");
						for (String tid : tasks)
						{
							if (tid.equals(taskId))
							{
								return regionId;
							}
						}
					}
					catch (NumberFormatException e)
					{
						// Skip invalid entries
					}
				}
			}
		}

		// Fallback: locate the chunk that defines this task. Tasks completed outside
		// the rolled-task flow (or after rolled data is cleared) still need a region
		// so the popup shows the right name and the area-specific jingle plays.
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
					return chunk.getRegionIds().get(0);
				}
			}
		}

		return -1;
	}

	/**
	 * Get all unique categories from all tasks.
	 */
	public Set<String> getAllCategories()
	{
		// TreeSet, not HashSet: this backs the Category filter combos, and a
		// HashSet's iteration order made them list in an arbitrary order while
		// the Active Tasks combos (already TreeSet-backed) listed A-Z.
		Set<String> categories = new java.util.TreeSet<>();
		for (NuzlockeChunk chunk : allChunks)
		{
			if (chunk.getTasks() != null)
			{
				for (NuzlockeTask task : chunk.getTasks())
				{
					if (task.getCategory() != null && !task.getCategory().isEmpty())
					{
						categories.add(task.getCategory());
					}
				}
			}
		}

		// Global tasks live in no chunk, so scanning allChunks alone left their
		// categories (Quest, and later Progression/Mystery) out of the Completed
		// Tasks category filter even though the tasks themselves were listed.
		for (NuzlockeTask task : globalTasks)
		{
			if (task.getCategory() != null && !task.getCategory().isEmpty())
			{
				categories.add(task.getCategory());
			}
		}

		return categories;
	}

	/** Whether this taskID belongs to the chunk-independent Global Tasks pool. */
	public boolean isGlobalTask(String taskId)
	{
		return taskId != null && globalTaskIds.contains(taskId);
	}

	/**
	 * Area bucket for a completed task, for the Completed Tasks area filter.
	 *
	 * Global tasks have no chunk, so getAreaForRegionId(-1) returns null and any
	 * specific area selection would silently drop every one of them. They get
	 * their own bucket instead. Shared by the filter and the combo population so
	 * the two can't disagree about what's in an area.
	 */
	public String getAreaForCompletedTask(String taskId, int regionId)
	{
		if (isGlobalTask(taskId))
		{
			return GLOBAL_AREA_NAME;
		}
		return getAreaForRegionId(regionId);
	}

	/**
	 * Get all unique region names that have had tasks completed.
	 */
	public Set<String> getCompletedTaskRegions()
	{
		// TreeSet to match getActiveTaskRegions() — the Completed Tasks chunk
		// filter was the odd one out, listing chunks in hash order.
		Set<String> regions = new java.util.TreeSet<>();
		for (CompletedTaskInfo info : getCompletedTasksWithInfo())
		{
			if (info.getRegionName() != null && !info.getRegionName().equals("Unknown Region"))
			{
				regions.add(info.getRegionName());
			}
		}
		return regions;
	}

	/**
	 * Areas (Misthalin, Asgarnia, Zeah, ...) that have at least one completed task.
	 */
	public Set<String> getCompletedTaskAreas()
	{
		Set<String> areas = new java.util.TreeSet<>();
		for (CompletedTaskInfo info : getCompletedTasksWithInfo())
		{
			String area = getAreaForCompletedTask(info.getTaskId(), info.getRegionId());
			if (area != null && !area.isEmpty())
			{
				areas.add(area);
			}
		}
		return areas;
	}

	/**
	 * Get all unique region names that have active tasks.
	 */
	public Set<String> getActiveTaskRegions()
	{
		Set<String> regions = new java.util.TreeSet<>();
		for (NuzlockeTask task : activeTasks)
		{
			String regionName = getTaskRegionName(task);
			if (regionName != null && !regionName.equals("Unknown Region"))
			{
				regions.add(regionName);
			}
		}
		return regions;
	}

	/**
	 * Areas (Misthalin, Asgarnia, Zeah, ...) that have at least one active task.
	 * Used by the Active Tasks panel to populate its Area filter dropdown.
	 */
	public Set<String> getActiveTaskAreas()
	{
		Set<String> areas = new java.util.TreeSet<>();
		for (NuzlockeTask task : activeTasks)
		{
			String area = getTaskArea(task);
			if (area != null && !area.isEmpty())
			{
				areas.add(area);
			}
		}
		return areas;
	}

	/**
	 * Resolve a region ID to its overarching area name (e.g. 12850 → "Misthalin").
	 * Returns null if the region isn't mapped to any known chunk.
	 */
	public String getAreaForRegionId(int regionId)
	{
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		return chunk != null ? chunk.getArea() : null;
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
	 * Label for the completion popup's "Region Assigned" slot.
	 *
	 * Global Tasks belong to no chunk, so getTaskRegionName() is always null for
	 * them and the popup fell back to "Unknown" — every quest and every skill
	 * rung completed under the same meaningless label. They report their pool
	 * instead ("Quest", "Progression"), which is the same wording the side panel
	 * uses on the global task cards.
	 *
	 * Deliberately separate from getTaskRegionName(): that one feeds the chunk
	 * filters, which must keep returning null for chunkless tasks or the filter
	 * dropdowns would list "Quest" as a chunk.
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

	// --- Task Progress Persistence ---

	/**
	 * Load task progress and target quantity.
	 * Format: "taskId:progress:targetQty,taskId2:progress2:targetQty2"
	 * Old format "taskId:progress" is also supported for backward compatibility.
	 * @return int[2] where [0] = progress, [1] = targetQty (0 if not saved)
	 */
	private int[] loadTaskProgressAndTarget(String taskId)
	{
		String data = config.taskProgressData();
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
	 * Callers MUST pass targetQty from the live activeTasks instance — looking
	 * the task up via findTaskById is unsafe because taskIDs can appear in
	 * multiple chunk JSONs (cook_tuna lives in Mistrock and Fishing Guild) and
	 * the lookup returns whichever instance allChunks iterates first, often
	 * with the default targetQuantity=1 that wipes the rolled value.
	 */
	public void saveTaskProgress(String taskId, int progress, int targetQty)
	{
		String data = config.taskProgressData();
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

		// Guard: if the caller is trying to write target=0 but we already have a
		// real target persisted, keep the old one. Hits the case where a progress
		// event fires with a task whose in-memory targetQuantity hasn't been set
		// yet (e.g. findTaskById returns the singleton before initializeTask
		// reaches it). Otherwise we corrupt the saved target and the next
		// loadActiveTasks re-rolls a fresh random number — Mike's "Y changes
		// after a chunk unlock" symptom.
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
		// Save progress for all active tasks. Pass each task's live target
		// directly — the 2-arg saveTaskProgress would re-look up via
		// findTaskById, which returns whichever instance appears first in
		// allChunks. For taskIDs defined in multiple chunks (cook_tuna lives in
		// Mistrock with quantityRange and in Fishing Guild with a fixed qty=1)
		// the wrong instance gets returned and overwrites the rolled target.
		for (NuzlockeTask task : activeTasks)
		{
			saveTaskProgress(task.getTaskId(), task.getCurrentProgress(), task.getTargetQuantity());
		}
	}

	// --- Stats Methods ---

	public int getTotalPoints()
	{
		return config.totalPoints();
	}

	public int getCompletedTaskCount()
	{
		String completed = config.completedTasks();
		if (completed == null || completed.isEmpty())
		{
			return 0;
		}
		return completed.split(",").length;
	}

	/**
	 * Force RuneLite to write config to DISK now, instead of whenever its timer
	 * next fires.
	 *
	 * ConfigManager only persists on a schedule and on a clean shutdown:
	 *
	 *   scheduleWithFixedDelay(this::sendConfig,
	 *       30 + (int)(5 * 60 * Math.random()),   // first flush: 30-330s
	 *       5 * 60, TimeUnit.SECONDS);            // then every 5 minutes
	 *   ... plus @Subscribe onClientShutdown -> sendConfig()
	 *
	 * So a setConfiguration() call lives in memory for up to five minutes. If the
	 * client dies without a clean ClientShutdown — killed, crashed, force-closed —
	 * that write is lost with no error anywhere. Cruk hit this: he unlocked East
	 * Falador (12084, force-unlocked) and Air Altar (11827, walked into), closed
	 * the client, and both were locked again on the next login. His session log
	 * showed no unlockedChunks write at all, because the value never reached disk.
	 *
	 * Progress-losing state (unlocks, points, completed tasks) is therefore
	 * flushed explicitly. These are infrequent, player-visible events, so the
	 * extra disk write is cheap next to silently losing a chunk unlock.
	 */
	/**
	 * Make a just-happened chunk unlock durable in BOTH places, immediately.
	 *
	 * An unlock previously survived only if the player lived long enough for two
	 * independent timers: RuneLite's config flush (up to 5 minutes) and our
	 * server sync (every 30s). Cruk lost East Falador (12084) and Air Altar
	 * (11827) in exactly that window — the DB showed neither region, and his
	 * next sync then overwrote the server's copy with the truncated list, since
	 * unlock sync is DELETE-then-INSERT with the client authoritative.
	 *
	 * syncToServer() self-guards on apiEnabled/LOGGED_IN and does its network I/O
	 * asynchronously, so this is safe to call straight from an unlock handler.
	 */
	private void persistUnlockNow()
	{
		flushConfigToDisk();
		syncToServer();
	}

	private void flushConfigToDisk()
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
	}

	private void addPoints(int points)
	{
		int current = config.totalPoints();
		setAccountState("totalPoints", current + points);
	}

	// --- Points: earned, spent, balance ------------------------------------
	//
	// `totalPoints` is the SPENDABLE BALANCE — unlocking a chunk decrements it,
	// and it gates whether an unlock is affordable. The SERVER's total_points is
	// a different quantity: lifetime EARNED, recomputed from the completed task
	// list (tasks.Catalog#Recompute), with no notion of spending.
	//
	// Conflating them cost a real player 939 phantom points on 2026-07-21: login
	// hydration wrote the server's EARNED total straight into the client's
	// BALANCE, discarding every chunk purchase they had ever made. It could not
	// self-correct, because the server has no balance to correct it from.
	//
	// So the balance is no longer a stored running total that drifts. It is
	// DERIVED:
	//
	//     balance = earned(completed tasks) - spent
	//
	// `earned` recomputes from the task list exactly as the server does, so the
	// two agree by construction and any accumulated drift heals itself. `spent`
	// is the only stored quantity, and it is monotonic — you cannot un-unlock a
	// chunk — which is what makes it safe to reconcile across profiles by taking
	// the maximum.
	//
	// Spend can NOT be derived from the unlocked-chunk list: the starting chunk
	// and the Casual first-pick are granted free via unlockRegionFree() yet have
	// a non-zero unlock_cost, and nothing records which chunks were paid for.
	// That is why it has to be tracked as it happens.

	/** Lifetime points earned, summed over completed tasks — mirrors the server. */
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
		int spent = config.pointsSpent();
		int balance = Math.max(0, earned - spent);

		// max(0, ...) silently absorbs a state that play cannot produce, and that
		// silence is why the derivation bug ran for weeks looking like "unlocking
		// a chunk spends all my points": the balance was pinned at zero and every
		// recompute just re-confirmed it without complaint. Say so instead.
		if (spent > earned)
		{
			log.warn("[CHUNKBLAZER] spend counter exceeds lifetime earnings: spent {} > earned {}. "
					+ "The balance is pinned at 0 and every point earned will vanish on the next "
					+ "recompute. Expect migrateRepairImpossiblePointsSpent() to correct this on login.",
				spent, earned);
		}

		if (balance != config.totalPoints())
		{
			log.info("[CHUNKBLAZER] points balance recomputed: earned {} - spent {} = {} (was {})",
				earned, spent, balance, config.totalPoints());
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
		setAccountState("pointsSpent", config.pointsSpent() + cost);
		recomputePointsBalance();
	}

	/**
	 * One-time derivation of the spend counter for accounts that predate it.
	 *
	 * <p>Their balance already reflects everything they ever spent, so
	 * {@code spent = earned - balance} recovers it exactly — no guessing at which
	 * chunks were free. Runs only when no spend has been recorded and the balance
	 * is genuinely below what they earned; a balance at or above earned means
	 * nothing was spent, or the balance was inflated by the hydration bug, and in
	 * both cases 0 is the right starting point (the real value then arrives from
	 * the server, which holds the higher, monotonic figure).
	 */
	private void deriveInitialPointsSpent()
	{
		if (config.pointsSpent() > 0)
		{
			return;
		}

		// The whole derivation rests on the local balance being a REAL record of
		// past spending. When the key is absent that reading is not merely
		// unreliable, it is inverted: config.totalPoints() answers 0 from its
		// default, and "balance 0" is then taken to mean SPENT EVERYTHING when it
		// actually means NOTHING IS KNOWN.
		//
		// clearAccountState() unsets totalPoints and pointsSpent together, so
		// every account switch landed in exactly that state and charged the
		// incoming account its entire lifetime earnings. Cruk, 2026-08-01
		// 17:23:19 — cleared, then "earned 414 - balance 0 = 414" one line later.
		// The value is monotonic and syncs upward, so each switch ratcheted it
		// permanently higher and pinned the balance at zero. A fresh install
		// hydrating from the server hits the same state for the same reason.
		//
		// A legacy account — the only case this was written for — has a genuine
		// persisted balance, so it still derives correctly.
		if (!isPointsBalancePersisted())
		{
			log.info("[CHUNKBLAZER] skipping spend derivation — no balance stored locally "
				+ "(fresh profile or account switch), so a zero balance means UNKNOWN, "
				+ "not SPENT EVERYTHING. The server's spend figure stands.");
			return;
		}

		int earned = computeEarnedPoints();
		int balance = config.totalPoints();
		if (earned <= 0 || balance >= earned)
		{
			return;
		}

		int spent = earned - balance;
		setAccountState("pointsSpent", spent);
		log.info("[CHUNKBLAZER] derived points spent for this account: earned {} - balance {} = {}",
			earned, balance, spent);
	}

	/**
	 * Whether the spendable balance actually EXISTS in stored config, rather than
	 * being {@link ChunkBlazerConfig#totalPoints()}'s default of 0.
	 *
	 * <p>Same shape as {@link #isUnlockedChunksPersisted()}: a config default makes
	 * "absent" and "genuinely zero" indistinguishable through the typed accessor,
	 * and every caller that treats those two as the same thing is wrong. Here it
	 * decides whether a zero balance is evidence of spending or evidence of
	 * nothing at all.
	 */
	private boolean isPointsBalancePersisted()
	{
		String raw = getAccountState("totalPoints");
		return raw != null && !raw.trim().isEmpty();
	}

	/**
	 * Undo an impossible spend counter left behind by the derivation bug above.
	 *
	 * <p>{@code spent > earned} cannot happen in play — points must be earned
	 * before they can be spent — so it is a reliable signature of the corruption
	 * rather than a state any legitimate session can reach. Both of Cruk's
	 * accounts were sitting in it on 2026-08-01: ChunkBlazer at earned 117 /
	 * spent 121, Cruk at earned 414 / spent 453. Fixing the derivation stops new
	 * damage but cannot clear this, because the counter only ever moves upward
	 * and the inflated figure is already on the server.
	 *
	 * <p>The chunks a player owns ARE the spend ledger: every paid chunk cost
	 * exactly its unlock cost, and free/charter chunks cost zero. Re-summing them
	 * recovers the true figure. The starting chunk is subtracted because
	 * {@link #ensureStartingChunkUnlocked()} grants it rather than selling it.
	 *
	 * <p>Deliberately one-directional — it only ever LOWERS the counter, and only
	 * to a total derived from chunks actually owned. Other free grants (casual
	 * mode's one-time standing-chunk pick) make the recomputed figure slightly
	 * high, which errs toward the player keeping fewer points than they are owed.
	 * Balance stays {@code earned - spent} and so can never exceed earned, which
	 * is what the server's Tier-0 check compares against.
	 */
	private void migrateRepairImpossiblePointsSpent()
	{
		int earned = computeEarnedPoints();
		int spent = config.pointsSpent();
		if (earned <= 0 || spent <= earned)
		{
			return;
		}

		int ledger = 0;
		for (String id : getUnlockedRegionIds())
		{
			try
			{
				ledger += getRegionUnlockCost(Integer.parseInt(id.trim()));
			}
			catch (NumberFormatException ignored)
			{
				// a malformed region id contributes nothing
			}
		}
		ledger = Math.max(0, ledger - getRegionUnlockCost(DEFAULT_START_REGION));

		if (ledger >= spent)
		{
			return;
		}

		log.warn("[CHUNKBLAZER] impossible spend counter repaired: spent {} exceeded earned {}, "
				+ "which play cannot produce. Rebuilt from the {} chunk(s) actually owned: spent = {}. "
				+ "Balance goes {} -> { }.",
			spent, earned, getUnlockedRegionIds().size(), ledger,
			Math.max(0, earned - spent), Math.max(0, earned - ledger));
		setAccountState("pointsSpent", ledger);
	}

	// --- Boss Tokens (secondary currency) ---

	/** Current Boss Token balance. New players start with 2 (config default). */
	public int getBossTokens()
	{
		return config.bossTokens();
	}

	/**
	 * Add (or, with a negative amount, remove) Boss Tokens; clamped at 0. Earned
	 * by defeating a boss in its boss chunk, or rarely from superior slayer
	 * monsters. (Earning triggers are wired separately once boss chunks exist /
	 * superior detection lands.)
	 */
	public void addBossTokens(int amount)
	{
		int updated = Math.max(0, config.bossTokens() + amount);
		setAccountState("bossTokens", updated);
		if (panel != null)
		{
			panel.updateStats();
		}
	}

	/**
	 * Spend one Boss Token (e.g. unlocking a boss chunk). Returns false without
	 * mutating if the player has none. Wire into boss-chunk unlock once boss
	 * chunks are defined.
	 */
	public boolean spendBossToken()
	{
		int current = config.bossTokens();
		if (current <= 0)
		{
			return false;
		}
		setAccountState("bossTokens", current - 1);
		if (panel != null)
		{
			panel.updateStats();
		}
		return true;
	}

	// --- Helper Methods ---

	private String getPlayerName()
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
		Set<String> unlocked = getUnlockedRegionIds();

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
				// Free chunks have no task-chunk entry — their neighbours come
				// from Free_Chunks.json. Without this, an unlocked free chunk
				// offered nothing onward (connectivity dead end). INVARIANT: a
				// free chunk ALWAYS opens its 4 cardinal neighbours — if the JSON
				// entry has no neighbor_ids, derive them from the region grid
				// (regionId ±1 = N/S, ±256 = E/W). Authored neighbor_ids act as
				// a curation override.
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

		return neighbors;
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
		if (chunk != null)
		{
			return chunk.getUnlockCostValue();
		}
		return 1; // Default cost
	}

	// Overworld surface lives in regionY 39..64 (world y 2496..4159). Everything
	// outside that band is off-map storage — underground dungeons, relocated
	// cities (Prifddinas), instanced content — which we treat as free dungeons.
	private static final int SURFACE_MIN_REGION_Y = 39;
	private static final int SURFACE_MAX_REGION_Y = 64;

	/**
	 * @return true if this region is a free dungeon (always accessible, never part
	 * of the chunk challenge). A region qualifies if it's outside the overworld
	 * surface band (regionY 39..64) — a coordinate rule that auto-covers every
	 * dungeon / off-map area including future content, with no dataset gaps.
	 * (Free_Chunks.json regions are NOT always-free — they're 0-cost unlock-on-demand
	 * chunks; see {@link #isFreeUnlockableRegion}.)
	 */
	// Prifddinas: the city's REAL in-game regions sit far above the overworld
	// surface band (regionY 94–95), so the free-dungeon coordinate rule would
	// auto-free them the moment a player steps inside. It's a genuine surface
	// city, not a dungeon — exempt it so the four city chunks start LOCKED.
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

		int cost = getRegionUnlockCost(regionId);
		int currentPoints = getTotalPoints();

		if (currentPoints < cost)
		{
			log.warn("Not enough points to unlock region {}. Need {} but have {}",
				regionId, cost, currentPoints);
			return;
		}

		// Adjacency gate: a points-purchased unlock must be a neighbor of an
		// already-unlocked chunk. Without this, the side-panel "Unlock"
		// button (and any future UI path that calls unlockRegion directly)
		// lets a player pay points to unlock any locked chunk they happen to
		// be standing in — even if they walked through other locked chunks
		// to get there. The first-pick-anywhere path for new Casual Mode
		// players goes through unlockRegionFree(...) which intentionally
		// bypasses this gate.
		Set<Integer> neighbors = getNeighborRegionIds();
		if (!neighbors.contains(regionId))
		{
			log.warn("unlockRegion({}) refused — region is not adjacent to any unlocked chunk (neighbors: {})",
				regionId, neighbors);
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
		soundManager.playRandomSoundForArea(area);
	}

	/**
	 * Dismiss any open chatbox prompt (e.g. the unlock-confirmation popup that
	 * fires on entering an unlockable region). Called by the side-panel unlock
	 * button so the chatbox prompt doesn't linger after the player has
	 * already confirmed the unlock through the panel — and can't be clicked
	 * a second time, which would otherwise re-fire unlockRegion. Safe no-op if
	 * nothing is open.
	 */
	public void closeChatboxPrompt()
	{
		if (chatboxPanelManager != null)
		{
			chatboxPanelManager.close();
		}
	}

	/**
	 * Dev dump: append every prayer-related VarPlayer + Varbit (current value)
	 * to C:\Chunkblazer\VarBit_VarPlayer.txt with a timestamp header. Used to
	 * reverse-engineer which var holds which prayer state when adding new
	 * VARBIT_CHECK tasks for prayers.
	 *
	 * <p>Reflection (not hardcoded IDs) so RuneLite API renames don't silently
	 * stop the dump — we just discover whatever PRAYER-tagged fields exist on
	 * {@code net.runelite.api.Varbits} / {@code VarPlayer} at runtime.
	 */
	public void dumpPrayerVars()
	{
		dumpVarSnapshot("Prayer",
			new String[]{"PRAYER", "QUICK_PRAYER"},
			null);
	}

	/**
	 * Dev dump for magic/spell vars (spellbook, autocast, spell unlock varbits, etc.).
	 * Appends to the same file as {@link #dumpPrayerVars()} with its own header so
	 * snapshots from both buttons coexist in one chronological log.
	 *
	 * <p>Excludes anything starting with {@code PRAYER_} so the "Protect from Magic"
	 * prayer doesn't bleed into the magic section.
	 */
	public void dumpMagicVars()
	{
		dumpVarSnapshot("Magic",
			new String[]{"SPELL", "MAGIC", "AUTOCAST", "SPELLBOOK"},
			new String[]{"PRAYER_"});
	}

	/**
	 * Append a snapshot of VarPlayers + Varbits whose names contain any of
	 * {@code includeKeywords} (and none of {@code excludePrefixes}) to the
	 * shared dump file.
	 */
	private void dumpVarSnapshot(String label, String[] includeKeywords, String[] excludePrefixes)
	{
		java.nio.file.Path outPath = java.nio.file.Paths.get("C:\\Chunkblazer\\VarBit_VarPlayer.txt");
		StringBuilder sb = new StringBuilder();
		sb.append("\n========================================================\n");
		sb.append(label).append(" var snapshot @ ").append(java.time.LocalDateTime.now()).append('\n');
		sb.append("========================================================\n");

		sb.append("\n--- VarPlayers (").append(label).append("-related) ---\n");
		collectVarPlayers(sb, includeKeywords, excludePrefixes);

		sb.append("\n--- Varbits (").append(label).append("-related) ---\n");
		collectVarbits(sb, includeKeywords, excludePrefixes);

		try
		{
			java.nio.file.Files.createDirectories(outPath.getParent());
			java.nio.file.Files.write(
				outPath,
				sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
		}
		catch (Exception e)
		{
			log.error("Dev: failed to write {} var dump to {}", label, outPath, e);
		}
	}

	private boolean matchesKeyword(String name, String[] includeKeywords, String[] excludePrefixes)
	{
		if (excludePrefixes != null)
		{
			for (String p : excludePrefixes)
			{
				if (name.startsWith(p)) return false;
			}
		}
		for (String k : includeKeywords)
		{
			if (name.contains(k)) return true;
		}
		return false;
	}

	private void collectVarPlayers(StringBuilder sb, String[] includeKeywords, String[] excludePrefixes)
	{
		// VarPlayer is an enum in current RuneLite API (older versions had a
		// class with static int fields). Handle both by checking isEnum().
		try
		{
			Class<?> cls = Class.forName("net.runelite.api.VarPlayer");
			if (cls.isEnum())
			{
				java.lang.reflect.Method getId = cls.getMethod("getId");
				for (Object constant : cls.getEnumConstants())
				{
					String name = ((Enum<?>) constant).name();
					if (!matchesKeyword(name, includeKeywords, excludePrefixes)) continue;
					int id = (int) getId.invoke(constant);
					int value = client.getVarpValue(id);
					sb.append(String.format("  %-40s id=%-6d value=%d%n", name, id, value));
				}
			}
			else
			{
				for (java.lang.reflect.Field f : cls.getDeclaredFields())
				{
					if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
					if (f.getType() != int.class) continue;
					if (!matchesKeyword(f.getName(), includeKeywords, excludePrefixes)) continue;
					int id = f.getInt(null);
					int value = client.getVarpValue(id);
					sb.append(String.format("  %-40s id=%-6d value=%d%n", f.getName(), id, value));
				}
			}
		}
		catch (Exception e)
		{
			sb.append("  (could not enumerate VarPlayer: ").append(e.getClass().getSimpleName())
				.append(": ").append(e.getMessage()).append(")\n");
		}
	}

	private void collectVarbits(StringBuilder sb, String[] includeKeywords, String[] excludePrefixes)
	{
		try
		{
			Class<?> cls = Class.forName("net.runelite.api.Varbits");
			for (java.lang.reflect.Field f : cls.getDeclaredFields())
			{
				if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				if (f.getType() != int.class) continue;
				String name = f.getName();
				// QUICK_PRAYER lacks "PRAYER_" prefix but is prayer-related;
				// include if any keyword matches (covers both cases).
				if (!matchesKeyword(name, includeKeywords, excludePrefixes)) continue;
				int id = f.getInt(null);
				int value = client.getVarbitValue(id);
				sb.append(String.format("  %-40s id=%-6d value=%d%n", name, id, value));
			}
		}
		catch (Exception e)
		{
			sb.append("  (could not enumerate Varbits: ").append(e.getClass().getSimpleName())
				.append(": ").append(e.getMessage()).append(")\n");
		}
	}
}
