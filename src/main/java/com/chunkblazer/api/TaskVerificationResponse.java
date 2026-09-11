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

package com.chunkblazer.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from task verification API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskVerificationResponse
{
	/** Whether the verification was successful */
	private boolean success;

	/** Whether the task is now complete */
	private boolean taskCompleted;

	/** Updated progress count (server-authoritative) */
	private int verifiedProgress;

	/** Points awarded (if task completed) */
	private int pointsAwarded;

	/** Server-side task ID for tracking */
	private String serverTaskId;

	/** Error message if verification failed */
	private String errorMessage;

	/** Reason for rejection if not verified */
	private String rejectionReason;

	/** Whether this was verified offline (for testing) */
	private boolean offlineMode;

	/** Server timestamp of verification */
	private long serverTimestamp;

	/**
	 * Create an offline success response for testing without API.
	 */
	public static TaskVerificationResponse offlineSuccess(String taskId)
	{
		return TaskVerificationResponse.builder()
			.success(true)
			.taskCompleted(false)
			.verifiedProgress(1)
			.offlineMode(true)
			.serverTaskId(taskId)
			.serverTimestamp(System.currentTimeMillis())
			.build();
	}

	/**
	 * Create an error response.
	 */
	public static TaskVerificationResponse error(String message)
	{
		return TaskVerificationResponse.builder()
			.success(false)
			.taskCompleted(false)
			.errorMessage(message)
			.serverTimestamp(System.currentTimeMillis())
			.build();
	}

	/**
	 * Create a task completed response.
	 */
	public static TaskVerificationResponse completed(String taskId, int points)
	{
		return TaskVerificationResponse.builder()
			.success(true)
			.taskCompleted(true)
			.pointsAwarded(points)
			.serverTaskId(taskId)
			.serverTimestamp(System.currentTimeMillis())
			.build();
	}
}
