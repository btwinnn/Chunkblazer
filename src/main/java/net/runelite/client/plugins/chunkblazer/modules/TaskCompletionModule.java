package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.client.plugins.chunkblazer.NuzlockeTask;

/**
 * Interface for task completion modules.
 * Each module handles a specific type of task completion (NPC_KILL, SKILL, ITEM_OBTAIN, etc.)
 */
public interface TaskCompletionModule
{
    /**
     * Get the completion type this module handles.
     * Must match the completion_type field in task JSON.
     */
    String getCompletionType();

    /**
     * Called when the plugin starts up.
     * Register any event listeners here.
     */
    void startUp();

    /**
     * Called when the plugin shuts down.
     * Unregister any event listeners here.
     */
    void shutDown();

    /**
     * Check if this module can handle the given task.
     */
    boolean canHandle(NuzlockeTask task);

    /**
     * Called when a new task is assigned that this module handles.
     * Use this to set up tracking for the specific task requirements.
     */
    void onTaskAssigned(NuzlockeTask task);

    /**
     * Called when the current task is completed or changed.
     * Clean up any task-specific tracking.
     */
    void onTaskCleared();

    /**
     * Get the current progress for the active task.
     * This is called to update the UI.
     */
    int getCurrentProgress();

    /**
     * Force a progress check/update.
     * Called when the player wants to manually verify progress.
     */
    void checkProgress();
}
