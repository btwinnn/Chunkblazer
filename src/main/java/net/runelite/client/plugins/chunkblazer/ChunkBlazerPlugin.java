package net.runelite.client.plugins.chunkblazer;

import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
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
    private TaskModuleManager taskModuleManager;

    @Inject
    private VarPlayerVerificationService varPlayerService;

    // --- Plugin State ---
    @Getter
    private NuzlockeTask activeTask; // Legacy single task for backward compatibility

    @Getter
    private List<NuzlockeTask> activeTasks = new ArrayList<>(); // All active tasks for current region

    private List<NuzlockeChunk> allChunks = new ArrayList<>();
    private Map<Integer, NuzlockeChunk> chunksByRegionId = new HashMap<>();
    private Map<String, NuzlockeTask> completedTaskCache = new HashMap<>(); // Cache completed tasks for lookup
    private ChunkBlazerPanel panel;
    private NavigationButton navButton;
    private int lastRegionId = -1;
    private final Random random = new Random();

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

        // Initialize task module manager
        taskModuleManager.initialize();
        taskModuleManager.setCompletionHandler(new TaskModuleManager.TaskCompletionHandler()
        {
            @Override
            public void onTaskCompleted(NuzlockeTask task, int progress)
            {
                // Task completed locally - request server verification
                log.info("Task completed locally: {} (progress: {})", task.getName(), progress);
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
                // Save progress to config
                saveTaskProgress(task.getTaskId(), newProgress);

                // Progress updated - refresh UI on Swing thread
                javax.swing.SwingUtilities.invokeLater(() -> {
                    panel.updateTaskDisplay();
                });
            }
        });
        taskModuleManager.startUp();

        // Create and register the sidebar panel
        panel = new ChunkBlazerPanel();
        panel.init(this);

        // Create a simple orange icon (16x16)
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++)
        {
            for (int y = 0; y < 16; y++)
            {
                icon.setRGB(x, y, 0xFFFF9800); // Orange color
            }
        }

        navButton = NavigationButton.builder()
            .tooltip("ChunkBlazer")
            .icon(icon)
            .priority(8)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);

        // Register world map overlay
        overlayManager.add(worldMapOverlay);

        // Load or assign a task if player is logged in
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            loadOrAssignTask();
            panel.updatePanel();
        }

        log.info("ChunkBlazer started successfully");
    }

    @Override
    protected void shutDown()
    {
        log.info("ChunkBlazer shutting down...");
        clientToolbar.removeNavigation(navButton);
        overlayManager.remove(worldMapOverlay);
        taskModuleManager.shutDown();
        varPlayerService.shutDown();
        activeTask = null;
    }

    @Provides
    ChunkBlazerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ChunkBlazerConfig.class);
    }

    // --- Event Handlers ---

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            // Small delay to ensure client is ready
            clientThread.invokeLater(() -> {
                loadOrAssignTask();
                panel.updatePanel();
            });
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            activeTask = null;
            lastRegionId = -1;
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

            panel.updateRegionDisplay();
            panel.updateTaskList();
        }
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

            // Reload tasks for the newly unlocked region
            loadActiveTasks();
            panel.updatePanel();
        }
        else
        {
            // Non-free mode: only unlock neighbor regions with defined tasks
            if (chunk == null)
            {
                // Not a defined region, skip
                return;
            }

            // Check if it's a neighbor of any unlocked region
            Set<Integer> neighbors = getNeighborRegionIds();
            if (!neighbors.contains(regionId))
            {
                log.debug("Region {} is not a neighbor of any unlocked region", regionId);
                return;
            }

            // Check if player has enough points
            int cost = getRegionUnlockCost(regionId);
            int currentPoints = getTotalPoints();

            if (currentPoints < cost)
            {
                log.debug("Not enough points to auto-unlock region {}. Need {} but have {}",
                    regionId, cost, currentPoints);
                return;
            }

            // Auto-unlock the region (spends points)
            log.info("Auto-unlocking region {} ({}) for {} points",
                regionId, chunk.getName(), cost);
            unlockRegion(regionId);

            // Reload tasks for the newly unlocked region
            loadActiveTasks();
            panel.updatePanel();
        }
    }

    /**
     * Unlock a region without spending points (for free/exploration mode).
     */
    public void unlockRegionFree(int regionId)
    {
        // Add to unlocked list without deducting points
        String unlocked = config.unlockedChunks();
        if (unlocked == null || unlocked.isEmpty())
        {
            unlocked = String.valueOf(regionId);
        }
        else if (!getUnlockedRegionIds().contains(String.valueOf(regionId)))
        {
            unlocked = unlocked + "," + regionId;
        }
        configManager.setConfiguration("chunkblazer", "unlockedChunks", unlocked);

        log.info("Unlocked region {} for FREE", regionId);

        // Update panel
        panel.updateStats();
    }

    // --- Data Loading ---

    private void loadChunkData()
    {
        try
        {
            InputStream is = getClass().getResourceAsStream("Starter_Area_Tasks.JSON");
            if (is == null)
            {
                log.warn("Could not find Starter_Area_Tasks.JSON");
                return;
            }

            Type listType = new TypeToken<Map<String, List<NuzlockeChunk>>>(){}.getType();
            Map<String, List<NuzlockeChunk>> data = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), listType);

            if (data != null && data.containsKey("nuzlocke_chunks_lumbridge"))
            {
                allChunks = data.get("nuzlocke_chunks_lumbridge");

                // Build lookup map
                for (NuzlockeChunk chunk : allChunks)
                {
                    if (chunk.getRegionIds() != null)
                    {
                        for (Integer regionId : chunk.getRegionIds())
                        {
                            chunksByRegionId.put(regionId, chunk);
                        }
                    }
                }

                log.info("Loaded {} chunks with {} region mappings", allChunks.size(), chunksByRegionId.size());
            }
        }
        catch (Exception e)
        {
            log.error("Failed to load chunk data", e);
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
        panel.updateModeDisplay();
    }

    private String hashRsn(String rsn)
    {
        return Hashing.sha256()
            .hashString(rsn.toLowerCase().trim(), StandardCharsets.UTF_8)
            .toString()
            .substring(0, 16);
    }

    // --- Region Methods ---

    public int getCurrentRegionId()
    {
        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return -1;
        }
        return player.getWorldLocation().getRegionID();
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
        Set<String> completedTaskIds = getCompletedTaskIds();

        for (String regionIdStr : getUnlockedRegionIds())
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
                        if (rolledTaskIds.contains(task.getTaskId()) &&
                            !completedTaskIds.contains(task.getTaskId()) &&
                            !task.isLocked())
                        {
                            // Initialize task
                            initializeTask(task);
                            activeTasks.add(task);
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
     */
    private void initializeTask(NuzlockeTask task)
    {
        // Load saved progress if exists
        int savedProgress = loadTaskProgress(task.getTaskId());

        int targetQty = 1;
        if (task.getTargetNpc() != null)
        {
            targetQty = task.getTargetNpc().getRequiredQuantity();
        }
        else if (task.getRequiredItems() != null && !task.getRequiredItems().isEmpty())
        {
            targetQty = task.getRequiredItems().get(0).getRequiredQuantity();
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
     * Tasks that have been assigned before (globally) are excluded.
     * This happens once per region.
     */
    private Set<String> rollTasksForRegion(int regionId)
    {
        NuzlockeChunk chunk = chunksByRegionId.get(regionId);
        if (chunk == null || chunk.getTasks() == null || chunk.getTasks().isEmpty())
        {
            return new HashSet<>();
        }

        // Get globally assigned tasks to exclude
        Set<String> globallyAssigned = getAssignedTaskIds();

        // Filter available tasks: not locked and not already assigned globally
        List<NuzlockeTask> availableTasks = chunk.getTasks().stream()
            .filter(t -> !t.isLocked())
            .filter(t -> !globallyAssigned.contains(t.getTaskId()))
            .collect(Collectors.toList());

        if (availableTasks.isEmpty())
        {
            log.info("No available tasks for region {} (all locked or already assigned)", regionId);
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

                // Mark as globally assigned (cannot be assigned again in any chunk)
                markTaskAssigned(selected.getTaskId());
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
            if (sb.length() > 0) sb.append("|");
            sb.append(entry.getKey()).append(":").append(String.join(",", entry.getValue()));
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
        log.info("Rerolling tasks for region {}...", currentRegion);

        // Clear rolled tasks for current region
        if (currentRegion > 0)
        {
            clearRolledTasksForRegion(currentRegion);
        }

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

        log.info("Tasks re-rolled for region {}", currentRegion);
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

    public void devAddPoints(int points)
    {
        int current = config.totalPoints();
        configManager.setConfiguration("chunkblazer", "totalPoints", current + points);
        log.info("DEV: Added {} points. Total: {}", points, current + points);
    }

    public void devResetTasks()
    {
        // Clear rolled tasks for current region only
        int currentRegion = getCurrentRegionId();
        if (currentRegion > 0)
        {
            clearRolledTasksForRegion(currentRegion);
        }

        // Clear completed tasks
        configManager.setConfiguration("chunkblazer", "completedTasks", "");
        // Clear task progress data
        configManager.setConfiguration("chunkblazer", "taskProgressData", "");
        // Clear assigned tasks
        configManager.setConfiguration("chunkblazer", "assignedTasks", "");

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
                        if (newData.length() > 0) newData.append("|");
                        newData.append(entry);
                    }
                }
                catch (NumberFormatException e)
                {
                    // Keep malformed entries
                    if (newData.length() > 0) newData.append("|");
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
        activeTask = null;
        activeTasks.clear();
        completedTaskCache.clear();
        saveCurrentTask();

        // Reset points
        configManager.setConfiguration("chunkblazer", "totalPoints", 0);

        // Reset unlocked chunks to default (starting area)
        configManager.setConfiguration("chunkblazer", "unlockedChunks", "12850");

        // Reset game mode lock
        configManager.setConfiguration("chunkblazer", "accountModeHash", "");
        configManager.setConfiguration("chunkblazer", "gameMode", GameMode.CASUAL);

        log.info("DEV: Full reset complete");

        // Assign a new task
        assignNewTask();
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

    private boolean isTaskCompleted(String taskId)
    {
        String completed = config.completedTasks();
        if (completed == null || completed.isEmpty())
        {
            return false;
        }
        return Arrays.asList(completed.split(",")).contains(taskId);
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
        if (data == null || data.isEmpty())
        {
            return -1;
        }

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

    // --- Task Progress Persistence ---

    private int loadTaskProgress(String taskId)
    {
        String data = config.taskProgressData();
        if (data == null || data.isEmpty())
        {
            return 0;
        }
        // Format: "taskId:progress,taskId2:progress2"
        for (String entry : data.split(","))
        {
            String[] parts = entry.split(":");
            if (parts.length == 2 && parts[0].equals(taskId))
            {
                try
                {
                    return Integer.parseInt(parts[1]);
                }
                catch (NumberFormatException e)
                {
                    return 0;
                }
            }
        }
        return 0;
    }

    public void saveTaskProgress(String taskId, int progress)
    {
        String data = config.taskProgressData();
        Map<String, Integer> progressMap = new HashMap<>();

        if (data != null && !data.isEmpty())
        {
            for (String entry : data.split(","))
            {
                String[] parts = entry.split(":");
                if (parts.length == 2)
                {
                    try
                    {
                        progressMap.put(parts[0], Integer.parseInt(parts[1]));
                    }
                    catch (NumberFormatException e)
                    {
                        // Skip invalid entries
                    }
                }
            }
        }

        progressMap.put(taskId, progress);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : progressMap.entrySet())
        {
            if (sb.length() > 0) sb.append(",");
            sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
        configManager.setConfiguration("chunkblazer", "taskProgressData", sb.toString());
    }

    private void saveActiveTasks()
    {
        // Save progress for all active tasks
        for (NuzlockeTask task : activeTasks)
        {
            saveTaskProgress(task.getTaskId(), task.getCurrentProgress());
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
            return chunk.getName();
        }
        return "Unknown Region";
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
        int cost = getRegionUnlockCost(regionId);
        int currentPoints = getTotalPoints();

        if (currentPoints < cost)
        {
            log.warn("Not enough points to unlock region {}. Need {} but have {}",
                regionId, cost, currentPoints);
            return;
        }

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
    }
}
