package net.runelite.client.plugins.chunkblazer.modules;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.chunkblazer.ChunkBlazerConfig;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;
import net.runelite.client.plugins.chunkblazer.RequiredItem;
import net.runelite.client.plugins.chunkblazer.RequiredObject;
import net.runelite.client.plugins.chunkblazer.TargetNpc;
import net.runelite.client.plugins.chunkblazer.TaskConstraints;
import org.mockito.Mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Base test class with common mock setup for task module tests.
 */
public abstract class AbstractTaskModuleTest
{
	@Mock
	protected Client client;

	@Mock
	protected ClientThread clientThread;

	@Mock
	protected EventBus eventBus;

	@Mock
	protected ChunkBlazerConfig config;

	@Mock
	protected Player localPlayer;

	@Mock
	protected WorldPoint playerLocation;

	@Mock
	protected AbstractTaskModule.TaskCompletionCallback completionCallback;

	/**
	 * Common setup for all module tests.
	 * Uses lenient stubbing to avoid UnnecessaryStubbingException.
	 */
	protected void setupCommonMocks()
	{
		// Setup client thread to execute runnables immediately
		lenient().doAnswer(invocation -> {
			Runnable runnable = invocation.getArgument(0);
			runnable.run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		// Setup local player
		lenient().when(client.getLocalPlayer()).thenReturn(localPlayer);
		lenient().when(localPlayer.getWorldLocation()).thenReturn(playerLocation);
		lenient().when(playerLocation.getRegionID()).thenReturn(12345);

		// Setup config defaults
		lenient().when(config.showChatProgress()).thenReturn(true);
		lenient().when(config.showChatSuccess()).thenReturn(true);
	}

	/**
	 * Create a test task with basic fields.
	 */
	protected NuzlockeTask createTestTask(String name, String taskId, String completionType, int targetQuantity)
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setName(name);
		task.setTaskId(taskId);
		task.setCompletionType(completionType);
		task.setTargetQuantity(targetQuantity);
		task.setCurrentProgress(0);
		task.setCompleted(false);
		return task;
	}

	/**
	 * Create a task with required items.
	 */
	protected NuzlockeTask createTaskWithItems(String name, String taskId, String completionType,
											   int targetQuantity, List<Integer> itemIds)
	{
		NuzlockeTask task = createTestTask(name, taskId, completionType, targetQuantity);

		RequiredItem requiredItem = new RequiredItem();
		requiredItem.setItemIds(itemIds);
		requiredItem.setQuantity(targetQuantity);

		task.setRequiredItems(Collections.singletonList(requiredItem));
		return task;
	}

	/**
	 * Create a task with target NPC.
	 */
	protected NuzlockeTask createTaskWithNpc(String name, String taskId, String completionType,
											 int targetQuantity, List<Integer> npcIds)
	{
		NuzlockeTask task = createTestTask(name, taskId, completionType, targetQuantity);

		TargetNpc targetNpc = new TargetNpc();
		targetNpc.setNpcIds(npcIds);
		targetNpc.setQuantity(targetQuantity);

		task.setTargetNpc(targetNpc);
		return task;
	}

	/**
	 * Create a task with a required GameObject (e.g. a stall).
	 */
	protected NuzlockeTask createTaskWithRequiredObject(String name, String taskId, String completionType,
													   int targetQuantity, List<Integer> objectIds)
	{
		NuzlockeTask task = createTestTask(name, taskId, completionType, targetQuantity);

		RequiredObject ro = new RequiredObject();
		ro.setObjectIds(objectIds);
		ro.setQuantity(targetQuantity);

		task.setRequiredObjects(Collections.singletonList(ro));
		return task;
	}

	/**
	 * Create a task with skill constraints.
	 */
	protected NuzlockeTask createTaskWithSkillConstraint(String name, String taskId, String completionType,
														 String skill, int level, int xp)
	{
		NuzlockeTask task = createTestTask(name, taskId, completionType, 1);

		TaskConstraints constraints = new TaskConstraints();
		constraints.setRequiredSkill(skill);
		constraints.setRequiredLevel(level);
		constraints.setRequiredXp(xp);

		task.setConstraints(constraints);
		return task;
	}

	/**
	 * Create a task with varbit constraint.
	 */
	protected NuzlockeTask createTaskWithVarbit(String name, String taskId, int varbitId, int expectedValue)
	{
		NuzlockeTask task = createTestTask(name, taskId, "VARBIT_CHECK", 1);

		TaskConstraints constraints = new TaskConstraints();
		constraints.setVarbitId(varbitId);

		task.setConstraints(constraints);
		task.setVarbitBoolean(expectedValue);

		return task;
	}
}
