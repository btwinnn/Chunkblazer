package net.runelite.client.plugins.chunkblazer;

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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MessageNode;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.widgets.ComponentID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.chunkblazer.api.ChunkBlazerApiClient;
import net.runelite.client.plugins.chunkblazer.api.PlayerLoginResponse;
import net.runelite.client.plugins.chunkblazer.api.PlayerSyncRequest;
import net.runelite.client.plugins.chunkblazer.api.VerifyStartResponse;
import net.runelite.client.plugins.chunkblazer.modules.TaskModuleManager;
import net.runelite.client.plugins.chunkblazer.verification.VarPlayerVerificationService;

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
	private ChunkBlazerRoster roster;

	@Inject
	private ChatIconManager chatIconManager;

	@Inject
	private TaskCompletionOverlay taskCompletionOverlay;

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

	// --- Plugin State ---
	@Getter
	private NuzlockeTask activeTask; // Legacy single task for backward compatibility

	@Getter
	private final List<NuzlockeTask> activeTasks = new CopyOnWriteArrayList<>(); // All active tasks for current region (thread-safe)

	private final List<NuzlockeChunk> allChunks = new ArrayList<>();
	private final Map<Integer, NuzlockeChunk> chunksByRegionId = new HashMap<>();
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

	// --- Constants ---
	private static final String DEV_MENU_OPTION = "DEBUG: Complete Task";
	private static final String DEV_MENU_TARGET = "ChunkBlazer";

	// --- Plugin Lifecycle ---

	@Override
	protected void startUp()
	{
		log.info("ChunkBlazer starting up...");

		// Start verification service (registers for VarPlayer events)
		varPlayerService.startUp();

		// Load task data from JSON
		loadChunkData();

		// Ensure the free starting chunk is unlocked
		ensureStartingChunkUnlocked();

		// Initialize task module manager
		taskModuleManager.initialize();
		taskModuleManager.setCompletionHandler(new TaskModuleManager.TaskCompletionHandler()
		{
			@Override
			public void onTaskCompleted(NuzlockeTask task, int progress)
			{
				// Task completed locally - request server verification
				log.info(">>> onTaskCompleted CALLBACK TRIGGERED");
				log.info(">>>   Task: {} (progress: {})", task != null ? task.getName() : "NULL", progress);
				log.info(">>>   taskCompletionOverlay: {}", taskCompletionOverlay != null ? "NOT NULL" : "NULL");
				log.info(">>>   taskCompletionAnimationOverlay: {}", taskCompletionAnimationOverlay != null ? "NOT NULL" : "NULL");

				// Show animated task completion popup
				if (taskCompletionAnimationOverlay != null && task != null)
				{
					log.info(">>>   Calling taskCompletionAnimationOverlay.showTaskCompletion()...");
					String regionName = getTaskRegionName(task);
					taskCompletionAnimationOverlay.showTaskCompletion(task, task.getBasePoints(), regionName);
					log.info(">>>   Animated popup triggered with region: {}", regionName);

					// Play region-specific completion sound
					if (config.playTaskCompletionSound())
					{
						String area = getTaskArea(task);
						if (soundManager != null && area != null)
						{
							soundManager.playRandomSoundForArea(area);
							log.info(">>>   Playing completion sound for area: {}", area);
						}
					}
				}
				else
				{
					log.error(">>>   CANNOT show animated popup - overlay or task is null!");
				}

				// Legacy popup disabled - using new animation overlay instead
				// if (taskCompletionOverlay != null && task != null)
				// {
				// 	log.info(">>>   Calling taskCompletionOverlay.showTaskCompletion()...");
				// 	String regionName = getTaskRegionName(task);
				// 	String area = getTaskArea(task);
				// 	taskCompletionOverlay.showTaskCompletion(task, task.getBasePoints(), regionName, area);
				// 	log.info(">>>   Legacy popup completed with region: {}, area: {}", regionName, area);
				// }

				completeTask(task);
			}

			@Override
			public void onServerVerified(NuzlockeTask task, int pointsAwarded)
			{
				// Server verified completion
				log.info("Server verified: {} (+{} points)", task.getName(), pointsAwarded);
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

		// Register minimap overlay for chunk visualization and click-to-unlock
		overlayManager.add(minimapOverlay);

		// Register scene overlay that draws chunk borders + locked-chunk wash on the gameplay screen
		overlayManager.add(sceneOverlay);

		// Legacy task completion popup overlay disabled - using animation overlay instead
		// overlayManager.add(taskCompletionOverlay);
		// log.info(">>> TaskCompletionOverlay registered with OverlayManager: {}", taskCompletionOverlay != null ? "OK" : "NULL");

		// Register animated task completion overlay
		overlayManager.add(taskCompletionAnimationOverlay);
		log.info(">>> TaskCompletionAnimationOverlay registered with OverlayManager: {}", taskCompletionAnimationOverlay != null ? "OK" : "NULL");

		// Player recognition surfaces: overhead tag + model outline (scene) and
		// minimap dots. Each render path is individually config-gated.
		overlayManager.add(playerOverlay);
		overlayManager.add(minimapPlayerOverlay);

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

		log.info("ChunkBlazer started successfully");
	}

	@Override
	protected void shutDown()
	{
		log.info("ChunkBlazer shutting down...");
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
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(playerOverlay);
		overlayManager.remove(minimapPlayerOverlay);
		// overlayManager.remove(taskCompletionOverlay); // Legacy overlay disabled
		overlayManager.remove(taskCompletionAnimationOverlay);
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
			PlayerSyncRequest finalSync = buildSyncRequest();
			if (finalSync != null && config.apiEnabled())
			{
				apiClient.syncPlayerState(finalSync)
					.thenAccept(resp -> log.info("Logout sync: success={}",
						resp != null && resp.isSuccess()));
			}
			// Logout beacon — tells the server we're offline now so it can snapshot
			// this just-ended session's hi-scores immediately instead of waiting for
			// heartbeats to go stale. Fire-and-forget; goOffline() no-ops when the
			// API is disabled or no api_key is set.
			apiClient.goOffline();
			activeTask = null;
			lastRegionId = -1;
			pendingServerLogin = false;
			// Real logout — reset the dedupe flag so the NEXT LOGGED_IN
			// (which is a fresh game session) re-runs loginToServer.
			serverLoginDone = false;
			// And clear any pending verification — the nonce is tied to the
			// pre-logout session.
			pendingVerificationNonce = null;
			panel.hideVerificationPrompt();
			// Drop the recognition roster; it'll repopulate after next login.
			roster.clear();
			// Refresh the side panel into its logged-out state (gates the
			// gameplay sections behind being in-game).
			panel.updatePanel();
		}
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

			// Auto-unlock region if enabled and player has enough points
			if (config.autoUnlockRegions())
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

		// Check if it's an unlockable neighbor
		if (!getNeighborRegionIds().contains(hoveredRegion) || isRegionUnlocked(hoveredRegion))
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
					.option("OK", () -> {})
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
						log.info("Player unlocked region {} via minimap", regionName);
					})
					.option("No", () -> {})
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
			log.info("Auto-unlocking region {} ({}) for FREE (exploration mode)",
				regionId, chunkName);
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
					.option("OK", () -> {})
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
						log.info("Player unlocked region {} via popup", regionName);
					})
					.option("No, not yet", () -> {})
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
		configManager.setConfiguration("chunkblazer", "unlockedChunks", unlocked);

		log.info("Unlocked region {} for FREE", regionId);

		if (!wasAlreadyUnlocked)
		{
			playRegionUnlockJingle(regionId);
		}

		// Roll tasks for this region if it has a chunk defined
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk != null && chunk.getTasks() != null && !chunk.getTasks().isEmpty())
		{
			Set<String> existingRolled = getRolledTasksForRegion(regionId);
			if (existingRolled.isEmpty())
			{
				Set<String> newTasks = rollTasksForRegion(regionId);
				log.info("Rolled {} tasks for free-unlocked region {}", newTasks.size(), regionId);
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
			log.info("Unlocking starting chunk: {}", DEFAULT_START_REGION);
		}

		if (needsUpdate)
		{
			configManager.setConfiguration("chunkblazer", "unlockedChunks", newUnlocked.toString());
			log.info("Unlocked starting chunk {} (neighbours remain unlockable)", DEFAULT_START_REGION);
		}

		// Pre-roll tasks for the starting chunk so they're ready immediately.
		// Other chunks get their tasks rolled lazily when unlocked.
		if (getRolledTasksForRegion(DEFAULT_START_REGION).isEmpty())
		{
			NuzlockeChunk chunk = chunksByRegionId.get(DEFAULT_START_REGION);
			if (chunk != null && chunk.getTasks() != null && !chunk.getTasks().isEmpty())
			{
				log.info("Rolling tasks for starting chunk {} ({})", DEFAULT_START_REGION, chunk.getName());
				Set<String> newTasks = rollTasksForRegion(DEFAULT_START_REGION);
				log.info("Rolled {} tasks for starting chunk: {}", newTasks.size(), newTasks);
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
		"Zeah_Tasks.json"
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

		log.info("=== CHUNKBLAZER LOADING CHUNK DATA ===");

		Type mapType = new TypeToken<Map<String, List<NuzlockeChunk>>>()
		{
		}.getType();
		int totalChunksLoaded = 0;
		int totalRegionMappings = 0;

		for (String jsonFile : TASK_JSON_FILES)
		{
			try
			{
				log.info(">>> Attempting to load: {}", jsonFile);

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
				log.info("Found {} via {}", jsonFile, foundVia);

				String jsonContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
				log.info("Read {} bytes from {}", jsonContent.length(), jsonFile);

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

					log.info("Found root key '{}' in {} with {} chunks",
						rootKey, jsonFile, chunks != null ? chunks.size() : 0);

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
									log.debug("Mapped region {} -> {} ({})",
										regionId, chunk.getName(), jsonFile);
								}
							}
							else
							{
								log.warn("Chunk '{}' in {} has null regionIds!", chunk.getName(), jsonFile);
							}
						}

						totalChunksLoaded += chunkCount;
						totalRegionMappings += regionCount;
						log.info("Loaded {} chunks with {} regions from {} (key: {})",
							chunkCount, regionCount, jsonFile, rootKey);
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

		log.info("=== CHUNKBLAZER LOAD COMPLETE ===");
		log.info("Total: {} chunks, {} region mappings", totalChunksLoaded, totalRegionMappings);

		// Debug: Log all loaded region IDs
		if (!chunksByRegionId.isEmpty())
		{
			log.info("All loaded region IDs: {}", chunksByRegionId.keySet());

			// Specifically check for Lumbridge (12850)
			if (chunksByRegionId.containsKey(12850))
			{
				NuzlockeChunk lumbridge = chunksByRegionId.get(12850);
				log.info(">>> LUMBRIDGE (12850) FOUND: name={}, tasks={}",
					lumbridge.getName(),
					lumbridge.getTasks() != null ? lumbridge.getTasks().size() : 0);
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

	public void lockGameMode(GameMode mode)
	{
		String rsn = getPlayerName();
		if (rsn == null)
		{
			log.warn("Cannot lock game mode: player not logged in");
			return;
		}

		String rsnHash = hashRsn(rsn);
		String modeKey = rsnHash + ":" + mode.name();

		configManager.setConfiguration("chunkblazer", "accountModeHash", modeKey);
		configManager.setConfiguration("chunkblazer", "gameMode", mode);

		log.info("Game mode locked to {} for account hash {}", mode, rsnHash);

		// Mirror the lock to the server. Fire-and-forget — local config is the
		// immediate source of truth; the server call backs it up so the choice
		// survives across installs / machines.
		log.info("[CB-DIAG] lockGameMode server-call apiEnabled={} apiClient={} mode={}",
			config.apiEnabled(), apiClient, mode);
		if (config.apiEnabled() && apiClient != null)
		{
			apiClient.lockGameMode(mode)
				.thenAccept(response ->
				{
					if (response == null)
					{
						return;
					}
					if (response.isSuccess())
					{
						log.info("Server confirmed mode lock: {}", mode);
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
			log.info("Casual mode selected - checking current region: {}", currentRegion);

			if (currentRegion > 0 && !isRegionUnlocked(currentRegion))
			{
				log.info("Unlocking current region {} for Casual mode start", currentRegion);
				unlockRegionFree(currentRegion);

				// Roll tasks for the newly unlocked region
				Set<String> newTasks = rollTasksForRegion(currentRegion);
				log.info("Rolled {} tasks for starting region {}", newTasks.size(), currentRegion);

				// Reload active tasks
				loadActiveTasks();
			}
			else if (currentRegion > 0)
			{
				log.info("Current region {} is already unlocked", currentRegion);
			}
		}

		panel.updateModeDisplay();
		panel.updatePanel();
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
		log.info("[CB-DIAG] loginToServer entered apiEnabled={} rsn={} apiClient={}",
			config.apiEnabled(), getPlayerName(), apiClient);
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
		log.info("[CB-DIAG] loginToServer calling apiClient.login url={}/api/player/login",
			config.apiBaseUrl());
		apiClient.login(rsn, fullHashRsn(rsn))
			.thenAccept(resp ->
			{
				log.info("[CB-DIAG] login response received: status={} apiKey={}",
					resp == null ? "null" : resp.getStatus(),
					resp == null ? "null" : (resp.getApiKey() != null ? "set" : "null"));
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
					log.info("Account verified via chat handshake");
					addPluginChatMessage("Account verified! You're all set.");
					panel.hideVerificationPrompt();
				}
				else
				{
					log.warn("Verification POST rejected: {}",
						resp != null ? resp.getMessage() : "null response");
					addPluginChatMessage("That code didn't work - it may have expired. Issuing a fresh one...");
					// Likely an expired code. Issue a new one so the player can retry.
					requestAndShowVerification();
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
				log.info("Verification nonce issued: {}", nonce);
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

			// 1. Mode lock reconciliation, both directions.
			if (response.isModeLocked() && response.getGameMode() != null && !isModeLocked())
			{
				// Server has a lock we don't — adopt it (e.g. fresh install on a new machine).
				GameMode serverMode = response.getGameMode();
				String modeKey = hashRsn(rsn) + ":" + serverMode.name();
				configManager.setConfiguration("chunkblazer", "accountModeHash", modeKey);
				configManager.setConfiguration("chunkblazer", "gameMode", serverMode);
				log.info("Game mode hydrated from server: {}", serverMode);
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
					log.info("Mode locked locally but not on server — re-pushing lock: {}", localMode);
					apiClient.lockGameMode(localMode);
				}
			}

			// 2. Unlocked regions — hydrate only when local is empty
			if (pdata != null && pdata.getUnlockedRegions() != null && !pdata.getUnlockedRegions().isEmpty())
			{
				String localChunks = config.unlockedChunks();
				if (localChunks == null || localChunks.trim().isEmpty())
				{
					String csv = pdata.getUnlockedRegions().stream()
						.map(String::valueOf)
						.collect(Collectors.joining(","));
					configManager.setConfiguration("chunkblazer", "unlockedChunks", csv);
					log.info("Unlocked regions hydrated from server: {} regions", pdata.getUnlockedRegions().size());
				}
			}

			// 3. Completed tasks — hydrate only when local is empty
			if (pdata != null && pdata.getCompletedTasks() != null && !pdata.getCompletedTasks().isEmpty())
			{
				String localTasks = config.completedTasks();
				if (localTasks == null || localTasks.trim().isEmpty())
				{
					String csv = String.join(",", pdata.getCompletedTasks());
					configManager.setConfiguration("chunkblazer", "completedTasks", csv);
					log.info("Completed tasks hydrated from server: {} tasks", pdata.getCompletedTasks().size());
				}
			}

			if (panel != null)
			{
				panel.updateModeDisplay();
				panel.updatePanel();
			}
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
			apiClient.sendHeartbeat(world, region, config.visibleToOthers());
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
				log.debug("Roster refresh failed: {}", e.toString());
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
			apiClient.sendHeartbeat(world, region, config.visibleToOthers())
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
		clientThread.invoke(() ->
		{
			PlayerSyncRequest req = buildSyncRequest();
			if (req == null)
			{
				return;
			}
			apiClient.syncPlayerState(req)
				.thenAccept(resp ->
				{
					if (resp != null && resp.isSuccess())
					{
						log.debug("Sync ok: points={} regions={} tasks={}",
							resp.getServerPoints(),
							resp.getServerUnlockedRegions() != null ? resp.getServerUnlockedRegions().size() : 0,
							resp.getServerCompletedTasks() != null ? resp.getServerCompletedTasks().size() : 0);
					}
				});
		});
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
			.clientPoints(config.totalPoints())
			.completedTasks(completed)
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
		return getUnlockedRegionIds().contains(String.valueOf(regionId));
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
		activeTasks.clear();
		taskModuleManager.clearTask(); // Clear module state to prevent duplicates

		Set<String> completedTaskIds = getCompletedTaskIds();
		Set<String> addedTaskIds = new HashSet<>(); // Track added tasks to prevent duplicates

		Set<String> unlockedRegions = getUnlockedRegionIds();
		log.info("Loading tasks for {} unlocked regions: {}", unlockedRegions.size(), unlockedRegions);

		for (String regionIdStr : unlockedRegions)
		{
			try
			{
				int regionId = Integer.parseInt(regionIdStr);
				Set<String> rolledTaskIds = getRolledTasksForRegion(regionId);

				if (rolledTaskIds.isEmpty())
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
							!addedTaskIds.contains(taskId) && // Prevent duplicates
							!task.isLocked())
						{
							// Initialize task
							initializeTask(task);

							// Self-heal stuck tasks where progress saved but completion never fired (e.g. throw in popup code between onProgressUpdated and onTaskCompleted).
							if (task.getCurrentProgress() >= task.getTargetQuantity())
							{
								log.info("Self-healing stuck task '{}' ({}/{}) — crediting as completed",
									task.getName(), task.getCurrentProgress(), task.getTargetQuantity());
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

		// Set first task as "active" for backward compatibility
		activeTask = activeTasks.isEmpty() ? null : activeTasks.get(0);

		log.info("Loaded {} active tasks across all unlocked regions", activeTasks.size());
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
			// says (1/18) for the same task. For multi-item tasks the saved
			// value is the SUM, which we can't split across items, so we only
			// pin the first item (matches initializeTask's first-item roll on
			// the fresh path below).
			if (task.getTargetNpc() != null)
			{
				task.getTargetNpc().setRolledQuantity(savedTargetQty);
			}
			else if (task.getRequiredItems() != null && !task.getRequiredItems().isEmpty())
			{
				task.getRequiredItems().get(0).setRolledQuantity(savedTargetQty);
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
		// First check allChunks (primary source)
		for (NuzlockeChunk chunk : allChunks)
		{
			if (chunk.getTasks() != null)
			{
				for (NuzlockeTask task : chunk.getTasks())
				{
					if (taskId.equals(task.getTaskId()))
					{
						return task;
					}
				}
			}
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
			log.info("No eligible tasks available (all rolled tasks have been assigned)");
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

			log.info("Assigned new task: {} (type: {}, target: {})",
				activeTask.getName(), activeTask.getCompletionType(), targetQty);
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
		log.info("rollTasksForRegion: Found chunk {} ({}) with {} tasks",
			regionId, chunk.getName(), chunk.getTasks().size());

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
			log.info("No available tasks for region {} (all locked or already assigned globally)", regionId);
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

		// Save to config
		saveRolledTasksForRegion(regionId, rolledIds);

		log.info("Rolled {} tasks for region {} (weighted): {}", rolledIds.size(), regionId, rolledIds);
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

		configManager.setConfiguration("chunkblazer", "regionRolledTasks", sb.toString());
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
		configManager.setConfiguration("chunkblazer", "assignedTasks", assigned);
		log.info("Marked task {} as assigned (cannot be reassigned)", taskId);
	}

	public void rerollTask()
	{
		int currentRegion = getCurrentRegionId();
		log.info("DEV Rerolling tasks for region {}...", currentRegion);

		// Clear rolled tasks for current region
		if (currentRegion > 0)
		{
			clearRolledTasksForRegion(currentRegion);
		}

		// DEV: Clear globally assigned tasks so reroll can get fresh tasks
		// This bypasses the "no duplicate tasks globally" rule for testing
		configManager.setConfiguration("chunkblazer", "assignedTasks", "");
		log.info("DEV: Cleared global assigned tasks list for fresh reroll");

		// Clear task progress data
		configManager.setConfiguration("chunkblazer", "taskProgressData", "");

		// Clear module state
		taskModuleManager.clearTask();

		// Clear active tasks
		activeTasks.clear();
		activeTask = null;

		// Re-roll and load tasks
		loadActiveTasks();
		panel.updatePanel();

		log.info("DEV: Tasks re-rolled for region {}", currentRegion);
	}

	public void devCompleteActiveTask()
	{
		if (activeTask == null)
		{
			log.info("No active task to complete");
			return;
		}

		log.info("DEV: Completing task: {}", activeTask.getName());
		completeTask(activeTask);
	}

	/**
	 * Complete a specific task (used by dev tools when a task is selected)
	 */
	public void devCompleteSpecificTask(NuzlockeTask task)
	{
		if (task == null)
		{
			log.info("DEV: No task specified to complete");
			return;
		}

		// Check if this task is in our active tasks list
		if (!activeTasks.contains(task))
		{
			log.info("DEV: Task '{}' is not in active tasks list", task.getName());
			return;
		}

		log.info("DEV: Completing specific task: {}", task.getName());
		completeTask(task);
	}

	public void devAddPoints(int points)
	{
		int current = config.totalPoints();
		configManager.setConfiguration("chunkblazer", "totalPoints", current + points);
		log.info("DEV: Added {} points. Total: {}", points, current + points);
	}

	public void devResetTasks()
	{
		log.info("DEV: devResetTasks() called");

		// Clear rolled tasks for current region only
		int currentRegion = getCurrentRegionId();
		if (currentRegion > 0)
		{
			clearRolledTasksForRegion(currentRegion);
		}

		// Log current state before clearing
		log.info("DEV: Before clear - completedTasks={}", config.completedTasks());

		// Clear completed tasks
		configManager.setConfiguration("chunkblazer", "completedTasks", "");
		// Clear task progress data
		configManager.setConfiguration("chunkblazer", "taskProgressData", "");
		// Clear assigned tasks
		configManager.setConfiguration("chunkblazer", "assignedTasks", "");
		// Also clear rolled tasks for ALL regions to fully reset
		configManager.setConfiguration("chunkblazer", "regionRolledTasks", "");

		// Verify the clear worked
		log.info("DEV: After clear - completedTasks={}", config.completedTasks());

		// Clear module state
		taskModuleManager.clearTask();

		// Clear active tasks
		activeTasks.clear();
		activeTask = null;
		completedTaskCache.clear();

		log.info("DEV: Reset all task progress for region {}", currentRegion);

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
		configManager.setConfiguration("chunkblazer", "regionRolledTasks", newData.toString());
		log.info("Cleared rolled tasks for region {}", regionId);
	}

	public void devResetAll()
	{
		// Reset tasks
		configManager.setConfiguration("chunkblazer", "regionRolledTasks", "");
		configManager.setConfiguration("chunkblazer", "assignedTasks", "");
		configManager.setConfiguration("chunkblazer", "completedTasks", "");
		configManager.setConfiguration("chunkblazer", "taskProgressData", "");
		taskModuleManager.clearTask();
		activeTask = null;
		activeTasks.clear();
		completedTaskCache.clear();
		saveCurrentTask();

		// Reset points
		configManager.setConfiguration("chunkblazer", "totalPoints", 0);

		// Reset unlocked chunks to the free starting chunk only. Every other
		// chunk must be unlocked with points after reset.
		configManager.setConfiguration("chunkblazer", "unlockedChunks", String.valueOf(DEFAULT_START_REGION));

		// Reset game mode lock
		configManager.setConfiguration("chunkblazer", "accountModeHash", "");
		configManager.setConfiguration("chunkblazer", "gameMode", GameMode.CASUAL);

		log.info("DEV: Full reset complete");

		// Re-roll and load tasks for the now-only starting chunk so the panel populates.
		loadActiveTasks();
	}

	private void completeTask(NuzlockeTask task)
	{
		// Cache the task before removing from active list (for completed tasks lookup)
		completedTaskCache.put(task.getTaskId(), task);

		// Add points for completing the task
		addPoints(task.getBasePoints());

		// Mark as completed
		markTaskCompleted(task.getTaskId());

		// Remove from active tasks
		activeTasks.remove(task);
		if (activeTask == task)
		{
			activeTask = activeTasks.isEmpty() ? null : activeTasks.get(0);
		}

		// Clear progress data for this task
		saveActiveTasks();

		log.info("Task completed: {} (+{} points)", task.getName(), task.getBasePoints());

		// Clear selected task if it was the completed one
		panel.clearSelectedTaskIfMatch(task);

		// Update all panel sections
		panel.updateStats();
		panel.updateTaskDisplay();
		panel.updateCompletedTasks();
		panel.updateTaskList();
	}

	private void saveCurrentTask()
	{
		if (activeTask != null)
		{
			configManager.setConfiguration("chunkblazer", "currentTaskId", activeTask.getTaskId());
			configManager.setConfiguration("chunkblazer", "currentTaskQuantity", activeTask.getTargetQuantity());
			configManager.setConfiguration("chunkblazer", "currentTaskProgress", activeTask.getCurrentProgress());
		}
		else
		{
			configManager.setConfiguration("chunkblazer", "currentTaskId", "");
			configManager.setConfiguration("chunkblazer", "currentTaskQuantity", 1);
			configManager.setConfiguration("chunkblazer", "currentTaskProgress", 0);
		}
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
		configManager.setConfiguration("chunkblazer", "completedTasks", completed);
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
				String regionName = getRegionName(regionId);
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
		Set<String> categories = new HashSet<>();
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
		return categories;
	}

	/**
	 * Get all unique region names that have had tasks completed.
	 */
	public Set<String> getCompletedTaskRegions()
	{
		Set<String> regions = new HashSet<>();
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
			String area = getAreaForRegionId(info.getRegionId());
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
		configManager.setConfiguration("chunkblazer", "taskProgressData", sb.toString());
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

	private void addPoints(int points)
	{
		int current = config.totalPoints();
		configManager.setConfiguration("chunkblazer", "totalPoints", current + points);
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
			}
			catch (NumberFormatException e)
			{
				log.warn("Invalid region ID in unlocked list: {}", regionIdStr);
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
		return "Unknown Region (" + regionId + ")";
	}

	public int getRegionUnlockCost(int regionId)
	{
		NuzlockeChunk chunk = chunksByRegionId.get(regionId);
		if (chunk != null)
		{
			return chunk.getUnlockCostValue();
		}
		return 1; // Default cost
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
			log.info("unlockRegion({}) — already unlocked, ignoring duplicate request", regionId);
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

		// Deduct points
		configManager.setConfiguration("chunkblazer", "totalPoints", currentPoints - cost);

		// Add to unlocked list
		String unlocked = config.unlockedChunks();
		if (unlocked == null || unlocked.isEmpty())
		{
			unlocked = String.valueOf(regionId);
		}
		else
		{
			unlocked = unlocked + "," + regionId;
		}
		configManager.setConfiguration("chunkblazer", "unlockedChunks", unlocked);

		log.info("Unlocked region {} for {} points. Remaining points: {}",
			regionId, cost, currentPoints - cost);

		// Auto-roll tasks for the new region
		Set<String> newTasks = rollTasksForRegion(regionId);
		log.info("Auto-rolled {} tasks for newly unlocked region {}", newTasks.size(), regionId);

		// Reload all active tasks (includes the new region's tasks)
		loadActiveTasks();

		// Update panel
		panel.updatePanel();

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
		log.info("Playing unlock jingle for region {} ({}, area={})", regionId, chunk.getName(), area);
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
			log.info("Dev: dumped {} vars to {}", label, outPath);
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
