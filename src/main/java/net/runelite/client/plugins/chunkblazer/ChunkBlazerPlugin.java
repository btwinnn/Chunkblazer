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
import net.runelite.client.util.ImageUtil;

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

    // --- Plugin State ---
    @Getter
    private NuzlockeTask activeTask;

    private List<NuzlockeChunk> allChunks = new ArrayList<>();
    private Map<Integer, NuzlockeChunk> chunksByRegionId = new HashMap<>();
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

        // Load task data from JSON
        loadChunkData();

        // Create and register the sidebar panel
        panel = injector.getInstance(ChunkBlazerPanel.class);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "chunkblazer_icon.png");
        if (icon == null)
        {
            // Fallback to a simple colored icon if file not found
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < 16; x++)
            {
                for (int y = 0; y < 16; y++)
                {
                    icon.setRGB(x, y, 0xFFFF9800); // Orange color
                }
            }
        }

        navButton = NavigationButton.builder()
            .tooltip("ChunkBlazer")
            .icon(icon)
            .priority(8)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navButton);

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
            panel.updateRegionDisplay();
            panel.updateTaskList();
        }
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

    public List<NuzlockeTask> getCurrentRegionTasks()
    {
        int regionId = getCurrentRegionId();
        NuzlockeChunk chunk = chunksByRegionId.get(regionId);
        if (chunk != null && chunk.getTasks() != null)
        {
            return chunk.getTasks();
        }
        return new ArrayList<>();
    }

    private void loadOrAssignTask()
    {
        String savedTaskId = config.currentTaskId();
        if (savedTaskId != null && !savedTaskId.isEmpty())
        {
            // Try to restore saved task
            activeTask = findTaskById(savedTaskId);
            if (activeTask != null)
            {
                activeTask.setCurrentProgress(config.currentTaskProgress());
                activeTask.setTargetQuantity(config.currentTaskQuantity());
                log.info("Restored task: {}", activeTask.getName());
                return;
            }
        }

        // Assign a new task
        assignNewTask();
    }

    private NuzlockeTask findTaskById(String taskId)
    {
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
        return null;
    }

    public void assignNewTask()
    {
        Set<String> unlockedRegions = getUnlockedRegionIds();
        List<NuzlockeTask> eligibleTasks = new ArrayList<>();

        // Gather all tasks from unlocked regions
        for (String regionIdStr : unlockedRegions)
        {
            try
            {
                int regionId = Integer.parseInt(regionIdStr);
                NuzlockeChunk chunk = chunksByRegionId.get(regionId);
                if (chunk != null && chunk.getTasks() != null)
                {
                    for (NuzlockeTask task : chunk.getTasks())
                    {
                        if (!task.isLocked() && !isTaskCompleted(task.getTaskId()))
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
            log.info("No eligible tasks available");
            activeTask = null;
            saveCurrentTask();
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

            log.info("Assigned new task: {} (target: {})", activeTask.getName(), targetQty);
            saveCurrentTask();
        }

        panel.updateTaskDisplay();
    }

    public void rerollTask()
    {
        log.info("Rerolling task...");
        activeTask = null;
        assignNewTask();
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

    private void completeTask(NuzlockeTask task)
    {
        // Mark as completed
        markTaskCompleted(task.getTaskId());

        // Clear active task
        activeTask = null;
        saveCurrentTask();

        // Assign new task
        assignNewTask();

        log.info("Task completed: {}", task.getName());
        panel.updateTaskDisplay();
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
}
