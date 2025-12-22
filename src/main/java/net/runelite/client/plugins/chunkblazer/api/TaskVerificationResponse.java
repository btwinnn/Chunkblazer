package net.runelite.client.plugins.chunkblazer.api;

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
