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

package com.chunkblazer.modules;

import com.chunkblazer.NuzlockeTask;

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
