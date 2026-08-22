package com.chunkblazer.modules;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TaskConstraints;

/**
 * Module for handling VARBIT_CHECK and VARP_CHECK completion type tasks.
 * Detects when specific game varbits/varps change to expected values.
 *
 * Tasks have varbit_boolean (expected value) and constraints.varbit_id.
 * Detection: Monitor varbit changes and check against expected values.
 */
@Slf4j
@Singleton
public class VarbitCheckModule extends AbstractTaskModule
{
	private static final String VARBIT_TYPE = "VARBIT_CHECK";
	private static final String VARP_TYPE = "VARP_CHECK";

	// Chat colors for ChunkBlazer messages
	private static final String COLOR_BLUE = "3366ff";
	private static final String COLOR_DARK_BLUE = "1a5276";
	private static final String COLOR_BLACK = "000000";

	@Inject
	private ChatMessageManager chatMessageManager;

	// Track task varbit requirements
	// Map: taskId -> varbit/varp ID to check
	private final Map<String, Integer> taskVarbitIds = new ConcurrentHashMap<>();

	// Map: taskId -> expected value (usually 1 for boolean checks)
	private final Map<String, Integer> taskExpectedValues = new ConcurrentHashMap<>();

	// Map: taskId -> is this a varp (true) or varbit (false)?
	private final Map<String, Boolean> taskIsVarp = new ConcurrentHashMap<>();

	// Map: taskId -> bit position to test inside a bitmap varbit (e.g. varbit
	// 4101 ACTIVE_PRAYERS uses 29 separate bit positions for the 29 prayers).
	// When present, the completion check is `(value & (1 << bit)) != 0`
	// instead of `value == expectedValue`. Lets a prayer task fire even when
	// other prayers are also on (other bits set in the same bitmap).
	private final Map<String, Integer> taskBitPositions = new ConcurrentHashMap<>();

	// All varbit IDs we're watching
	private final Set<Integer> watchedVarbitIds = ConcurrentHashMap.newKeySet();
	private final Set<Integer> watchedVarpIds = ConcurrentHashMap.newKeySet();

	// Track previous varbit/varp values for detecting changes
	private final Map<Integer, Integer> previousVarbitValues = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> previousVarpValues = new ConcurrentHashMap<>();


	@Inject
	public VarbitCheckModule()
	{
	}

	@Override
	public String getCompletionType()
	{
		return VARBIT_TYPE;
	}

	@Override
	public boolean canHandle(NuzlockeTask task)
	{
		String type = task.getCompletionType();
		return type != null &&
			(type.equalsIgnoreCase(VARBIT_TYPE) || type.equalsIgnoreCase(VARP_TYPE));
	}

	@Override
	public void startUp()
	{
		eventBus.register(this);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(this);
		taskVarbitIds.clear();
		taskExpectedValues.clear();
		taskIsVarp.clear();
		taskBitPositions.clear();
		watchedVarbitIds.clear();
		watchedVarpIds.clear();
		previousVarbitValues.clear();
		previousVarpValues.clear();
	}

	@Override
	public void addActiveTask(NuzlockeTask task)
	{
		try
		{
			super.addActiveTask(task);


			// Parse varbit/varp requirements from constraints
			TaskConstraints constraints = task.getConstraints();
			int varbitId = -1;
			int expectedValue = 1; // Default to 1 for boolean checks
			boolean isVarp = VARP_TYPE.equalsIgnoreCase(task.getCompletionType());

			if (constraints != null)
			{
				varbitId = constraints.getVarbitId();
				// Use varpId if this is a VARP_CHECK
				if (isVarp && constraints.getVarpId() > 0)
				{
					varbitId = constraints.getVarpId();
				}
			}

			// varbit_boolean now lives inside `constraints` for schema uniformity
			// with varbit_id. Fall back to the deprecated top-level field for
			// any JSON that hasn't been migrated yet (constraints version wins
			// when both are present, since that's the new canonical home).
			Integer varbitBoolean = null;
			if (constraints != null)
			{
				varbitBoolean = constraints.getVarbitBoolean();
			}
			if (varbitBoolean == null)
			{
				varbitBoolean = task.getVarbitBoolean();
			}
			if (varbitBoolean != null)
			{
				expectedValue = varbitBoolean;
			}

			if (varbitId > 0)
			{
				taskVarbitIds.put(task.getTaskId(), varbitId);
				taskExpectedValues.put(task.getTaskId(), expectedValue);
				taskIsVarp.put(task.getTaskId(), isVarp);

				// Optional: bit-position check (bitmap varbits like 4101 ACTIVE_PRAYERS).
				// Same constraints-first / top-level-fallback chain as varbit_boolean.
				Integer bit = null;
				if (constraints != null)
				{
					bit = constraints.getVarbitBit();
				}
				if (bit == null)
				{
					bit = task.getVarbitBit();
				}
				if (bit != null && bit >= 0)
				{
					taskBitPositions.put(task.getTaskId(), bit);
				}

				if (isVarp)
				{
					watchedVarpIds.add(varbitId);
				}
				else
				{
					watchedVarbitIds.add(varbitId);
					if (bit != null && bit >= 0)
					{
					}
					else
					{
					}
				}

				// Initialize tracking on client thread
				final int finalVarbitId = varbitId;
				final boolean finalIsVarp = isVarp;
				clientThread.invokeLater(() -> initializeVarTracking(finalVarbitId, finalIsVarp));
			}
			else
			{
				log.warn("  >>> No varbit/varp ID found in task constraints!");
			}
		}
		catch (Exception e)
		{
			log.error("VarbitCheckModule.addActiveTask() EXCEPTION: ", e);
		}
	}

	@Override
	public void onTaskAssigned(NuzlockeTask task)
	{
		super.onTaskAssigned(task);
		addActiveTask(task);
	}

	@Override
	public void onTaskCleared()
	{
		super.onTaskCleared();
		taskVarbitIds.clear();
		taskExpectedValues.clear();
		taskIsVarp.clear();
		taskBitPositions.clear();
		watchedVarbitIds.clear();
		watchedVarpIds.clear();
	}

	@Override
	public void checkProgress()
	{
		// Progress is tracked via varbit change events
		// But we can also do a manual check here
		for (NuzlockeTask task : activeTasks)
		{
			checkTaskCompletion(task);
		}
	}

	private void initializeVarTracking(int varId, boolean isVarp)
	{
		try
		{
			int currentValue;
			if (isVarp)
			{
				currentValue = client.getVarpValue(varId);
				previousVarpValues.put(varId, currentValue);
			}
			else
			{
				currentValue = client.getVarbitValue(varId);
				previousVarbitValues.put(varId, currentValue);
			}
		}
		catch (Exception e)
		{
			log.error("Failed to initialize var tracking for ID {}: ", varId, e);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{


		// Check varp values each tick (since VarbitChanged doesn't always fire for varps)
		if (!activeTasks.isEmpty() && !watchedVarpIds.isEmpty())
		{
			checkVarpValues();
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (activeTasks.isEmpty())
		{
			return;
		}

		// Check all tasks since VarbitChanged doesn't tell us which varbit changed
		for (NuzlockeTask task : new HashSet<>(activeTasks))
		{
			Boolean isVarp = taskIsVarp.get(task.getTaskId());
			if (isVarp != null && !isVarp)
			{
				// Only check varbits here, varps are checked in onGameTick
				checkTaskCompletion(task);
			}
		}
	}

	private void checkVarpValues()
	{
		for (int varpId : watchedVarpIds)
		{
			int currentValue = client.getVarpValue(varpId);
			Integer previousValue = previousVarpValues.get(varpId);

			if (previousValue == null || currentValue != previousValue)
			{
				previousVarpValues.put(varpId, currentValue);

				// Check tasks that watch this varp
				for (NuzlockeTask task : new HashSet<>(activeTasks))
				{
					Integer taskVarId = taskVarbitIds.get(task.getTaskId());
					Boolean isVarp = taskIsVarp.get(task.getTaskId());
					if (taskVarId != null && taskVarId == varpId && Boolean.TRUE.equals(isVarp))
					{
						checkTaskCompletion(task);
					}
				}
			}
		}
	}

	private void checkTaskCompletion(NuzlockeTask task)
	{
		Integer varId = taskVarbitIds.get(task.getTaskId());
		Integer expectedValue = taskExpectedValues.get(task.getTaskId());
		Boolean isVarp = taskIsVarp.get(task.getTaskId());

		if (varId == null || expectedValue == null || isVarp == null)
		{
			return;
		}

		int currentValue;
		if (isVarp)
		{
			currentValue = client.getVarpValue(varId);
		}
		else
		{
			currentValue = client.getVarbitValue(varId);
		}

		// Two completion-check modes. Bit-position mode wins if the task supplied
		// varbit_bit (used for bitmap varbits like 4101 ACTIVE_PRAYERS, where one
		// varbit packs 29 independent prayer flags). Otherwise fall back to the
		// legacy exact-value match.
		Integer bit = taskBitPositions.get(task.getTaskId());
		boolean matched;
		if (bit != null)
		{
			matched = (currentValue & (1 << bit)) != 0;
		}
		else
		{
			matched = currentValue == expectedValue;
		}

		if (matched && !task.isCompleted())
		{
			if (bit != null)
			{
			}
			else
			{
			}

			task.setCurrentProgress(1);
			task.setCompleted(true);

			sendTaskSuccess(task, (isVarp ? "Varp" : "Varbit") + " check passed!");

			if (completionCallback != null)
			{
				completionCallback.onTaskCompleted(task, 1);
			}

			// Clean up
			taskVarbitIds.remove(task.getTaskId());
			taskExpectedValues.remove(task.getTaskId());
			taskIsVarp.remove(task.getTaskId());
			taskBitPositions.remove(task.getTaskId());
			activeTasks.remove(task);
			rebuildWatchedIds();
		}
	}

	private void rebuildWatchedIds()
	{
		watchedVarbitIds.clear();
		watchedVarpIds.clear();
		for (Map.Entry<String, Integer> entry : taskVarbitIds.entrySet())
		{
			String taskId = entry.getKey();
			int varId = entry.getValue();
			Boolean isVarp = taskIsVarp.get(taskId);
			if (Boolean.TRUE.equals(isVarp))
			{
				watchedVarpIds.add(varId);
			}
			else
			{
				watchedVarbitIds.add(varId);
			}
		}
	}

	private void sendTaskSuccess(NuzlockeTask task, String details)
	{
		if (!config.showChatSuccess())
		{
			return;
		}

		String message = "<col=" + COLOR_BLUE + ">[ChunkBlazer]</col> " +
			"<col=" + COLOR_DARK_BLUE + ">Task Complete!</col> " +
			"<col=" + COLOR_BLACK + ">" + task.getName() + "</col>";

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.value(message)
			.build());

	}
}
