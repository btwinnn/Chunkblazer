package net.runelite.client.plugins.chunkblazer.modules;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.chunkblazer.ChunkBlazerConfig;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.api.ChunkBlazerApiClient;
import net.runelite.client.plugins.chunkblazer.api.TaskVerificationResponse;

/**
 * Base class for task completion modules with common functionality.
 */
@Slf4j
public abstract class AbstractTaskModule implements TaskCompletionModule
{
    @Inject
    protected Client client;

    @Inject
    protected ClientThread clientThread;

    @Inject
    protected EventBus eventBus;

    @Inject
    protected ChunkBlazerApiClient apiClient;

    @Inject
    protected ChunkBlazerConfig config;

    @Getter
    protected NuzlockeTask activeTask; // Legacy single task

    @Getter
    protected List<NuzlockeTask> activeTasks = new ArrayList<>(); // Multiple active tasks

    @Getter
    protected int currentProgress;

    protected TaskCompletionCallback completionCallback;

    /**
     * Set the callback to be invoked when a task is completed.
     */
    public void setCompletionCallback(TaskCompletionCallback callback)
    {
        this.completionCallback = callback;
    }

    @Override
    public void onTaskAssigned(NuzlockeTask task)
    {
        this.activeTask = task;
        this.currentProgress = task.getCurrentProgress();
        log.info("{} module: Task assigned - {} (progress: {}/{})",
            getCompletionType(), task.getName(), currentProgress, task.getTargetQuantity());
    }

    /**
     * Add an active task for multi-task tracking.
     */
    public void addActiveTask(NuzlockeTask task)
    {
        if (!activeTasks.contains(task))
        {
            activeTasks.add(task);
            // Also set as activeTask for backward compatibility if it's the first
            if (activeTask == null)
            {
                activeTask = task;
                currentProgress = task.getCurrentProgress();
            }
            log.info("{} module: Added active task - {} (progress: {}/{})",
                getCompletionType(), task.getName(), task.getCurrentProgress(), task.getTargetQuantity());
        }
    }

    /**
     * Find an active task matching the given criteria.
     */
    protected NuzlockeTask findMatchingTask(Predicate<NuzlockeTask> matcher)
    {
        for (NuzlockeTask task : activeTasks)
        {
            if (matcher.test(task))
            {
                return task;
            }
        }
        return null;
    }

    @Override
    public void onTaskCleared()
    {
        this.activeTask = null;
        this.activeTasks.clear();
        this.currentProgress = 0;
    }

    @Override
    public boolean canHandle(NuzlockeTask task)
    {
        return getCompletionType().equalsIgnoreCase(task.getCompletionType());
    }

    /**
     * Increment progress and check for completion.
     */
    protected void incrementProgress(int amount)
    {
        if (activeTask == null)
        {
            return;
        }

        currentProgress += amount;
        activeTask.setCurrentProgress(currentProgress);

        log.info("{} module: Progress updated - {}/{}",
            getCompletionType(), currentProgress, activeTask.getTargetQuantity());

        if (currentProgress >= activeTask.getTargetQuantity())
        {
            onTaskCompleted();
        }
    }

    /**
     * Called when task completion is detected locally.
     * Triggers server verification.
     */
    protected void onTaskCompleted()
    {
        if (activeTask == null || completionCallback == null)
        {
            return;
        }

        log.info("{} module: Task completed locally, requesting server verification", getCompletionType());

        // Notify the plugin that this task is complete
        completionCallback.onTaskCompleted(activeTask, currentProgress);
    }

    /**
     * Handle the server verification response.
     */
    protected void handleVerificationResponse(TaskVerificationResponse response)
    {
        if (response.isSuccess())
        {
            if (response.isTaskCompleted())
            {
                log.info("Server confirmed task completion! Points awarded: {}", response.getPointsAwarded());
                if (completionCallback != null && activeTask != null)
                {
                    completionCallback.onServerVerified(activeTask, response.getPointsAwarded());
                }
            }
            else
            {
                // Update progress from server
                currentProgress = response.getVerifiedProgress();
                if (activeTask != null)
                {
                    activeTask.setCurrentProgress(currentProgress);
                }
                log.info("Server verified progress: {}", currentProgress);
            }
        }
        else
        {
            log.warn("Server verification failed: {}", response.getErrorMessage());
            if (response.getRejectionReason() != null)
            {
                log.warn("Rejection reason: {}", response.getRejectionReason());
            }
        }
    }

    /**
     * Get a hash of the player's RSN for API calls.
     */
    protected String getPlayerHash()
    {
        Player player = client.getLocalPlayer();
        if (player == null || player.getName() == null)
        {
            return "unknown";
        }

        return Hashing.sha256()
            .hashString(player.getName().toLowerCase().trim(), StandardCharsets.UTF_8)
            .toString()
            .substring(0, 16);
    }

    /**
     * Get the current region ID.
     */
    protected int getCurrentRegionId()
    {
        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return -1;
        }
        return player.getWorldLocation().getRegionID();
    }

    /**
     * Get current game tick.
     */
    protected int getGameTick()
    {
        return client.getTickCount();
    }

    /**
     * Callback interface for task completion events.
     */
    public interface TaskCompletionCallback
    {
        /**
         * Called when a task is completed locally (before server verification).
         */
        void onTaskCompleted(NuzlockeTask task, int progress);

        /**
         * Called when the server has verified the task completion.
         */
        void onServerVerified(NuzlockeTask task, int pointsAwarded);

        /**
         * Called when progress is updated.
         */
        void onProgressUpdated(NuzlockeTask task, int newProgress);
    }
}
