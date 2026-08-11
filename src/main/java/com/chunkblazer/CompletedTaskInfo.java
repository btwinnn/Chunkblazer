package com.chunkblazer;

import lombok.Data;

/**
 * Holds information about a completed task including which region it was completed in.
 */
@Data
public class CompletedTaskInfo
{
	private final String taskId;
	private final int regionId;
	private final String regionName;
	private final NuzlockeTask task;

	public CompletedTaskInfo(String taskId, int regionId, String regionName, NuzlockeTask task)
	{
		this.taskId = taskId;
		this.regionId = regionId;
		this.regionName = regionName;
		this.task = task;
	}

	public String getCategory()
	{
		return task != null ? task.getCategory() : "Unknown";
	}

	public String getName()
	{
		return task != null ? task.getName() : taskId;
	}

	public int getPoints()
	{
		return task != null ? task.getBasePoints() : 0;
	}
}