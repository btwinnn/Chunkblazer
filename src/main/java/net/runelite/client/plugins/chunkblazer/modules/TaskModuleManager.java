package net.runelite.client.plugins.chunkblazer.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.chunkblazer.NuzlockeTask;

/**
 * Manages all task completion modules.
 * Routes tasks to appropriate modules and coordinates callbacks.
 */
@Slf4j
@Singleton
public class TaskModuleManager implements AbstractTaskModule.TaskCompletionCallback
{
	private final List<AbstractTaskModule> modules = new ArrayList<>();
	private final Map<String, AbstractTaskModule> modulesByType = new HashMap<>();

	// Support multiple active tasks
	private final List<NuzlockeTask> activeTasks = new ArrayList<>();
	private final Map<String, AbstractTaskModule> taskToModuleMap = new HashMap<>();

	private AbstractTaskModule activeModule; // Legacy single module for backward compatibility
	private TaskCompletionHandler completionHandler;

	@Inject
	private NPCKillModule npcKillModule;

	@Inject
	private SkillModule skillModule;

	@Inject
	private ObtainModule obtainModule;

	@Inject
	private EquipModule equipModule;

	@Inject
	private FiremakingModule firemakingModule;

	@Inject
	private AgilityModule agilityModule;

	@Inject
	private ThievingModule thievingModule;

	@Inject
	private ConstructionModule constructionModule;

	@Inject
	private VarbitCheckModule varbitCheckModule;

	@Inject
	private NpcDialogueModule npcDialogueModule;

	@Inject
	public TaskModuleManager()
	{
	}

	/**
	 * Initialize all modules. Call this after injection.
	 */
	public void initialize()
	{
		// Register modules
		registerModule(npcKillModule);
		registerModuleWithType(npcKillModule, "COMBAT"); // NpcKillModule handles COMBAT type too
		registerModuleWithType(npcKillModule, "SLAYER"); // NpcKillModule handles SLAYER type with task verification
		registerModule(skillModule);
		registerModule(obtainModule);
		registerModule(equipModule);
		registerModule(firemakingModule);
		registerModule(agilityModule);
		registerModule(thievingModule);
		registerModule(constructionModule);
		registerModule(varbitCheckModule);
		registerModuleWithType(varbitCheckModule, "VARP_CHECK"); // VarbitCheckModule handles both types
		registerModule(npcDialogueModule);

		// Register ObtainModule for all production-based skilling types
		// These all work the same way - tracking items appearing in inventory
		String[] skillingTypes = {
			"COOKING", "CRAFTING", "SMITHING", "MINING", "WOODCUTTING",
			"FISHING", "FLETCHING", "HERBLORE", "RUNECRAFTING", "HUNTER"
		};
		for (String type : skillingTypes)
		{
			registerModuleWithType(obtainModule, type);
		}

	}

	private void registerModule(AbstractTaskModule module)
	{
		modules.add(module);
		modulesByType.put(module.getCompletionType().toUpperCase(), module);
		module.setCompletionCallback(this);
	}

	private void registerModuleWithType(AbstractTaskModule module, String type)
	{
		modulesByType.put(type.toUpperCase(), module);
	}

	/**
	 * Set the handler for task completion events.
	 */
	public void setCompletionHandler(TaskCompletionHandler handler)
	{
		this.completionHandler = handler;
	}

	/**
	 * Start all modules (register event listeners).
	 */
	public void startUp()
	{
		for (AbstractTaskModule module : modules)
		{
			module.startUp();
		}
	}

	/**
	 * Stop all modules (unregister event listeners).
	 */
	public void shutDown()
	{
		for (AbstractTaskModule module : modules)
		{
			module.shutDown();
		}
		activeModule = null;
	}

	/**
	 * Assign a task to the appropriate module.
	 */
	public void assignTask(NuzlockeTask task)
	{
		if (task == null)
		{
			clearTask();
			return;
		}

		String completionType = task.getCompletionType();
		if (completionType == null)
		{
			log.warn("Task {} has no completion type", task.getTaskId());
			return;
		}

		// Find module that can handle this task
		AbstractTaskModule module = findModuleForTask(task);
		if (module == null)
		{
			log.warn("No module found for completion type: {}", completionType);
			return;
		}

		// Clear previous module
		if (activeModule != null && activeModule != module)
		{
			activeModule.onTaskCleared();
		}

		// Assign to new module
		activeModule = module;
		module.onTaskAssigned(task);

	}

	/**
	 * Clear the current task from all modules.
	 */
	public void clearTask()
	{
		// Diagnostic: clearTask wipes module per-task state. If this fires unexpectedly during a session, log the trigger so we can correlate with progress regressions.
		// Clear every module's per-module list, not just the legacy activeModule. Otherwise modules retain stale tasks across resets and end up with duplicates after re-registration.
		for (AbstractTaskModule module : modules)
		{
			module.onTaskCleared();
		}
		activeModule = null;
		activeTasks.clear();
		taskToModuleMap.clear();
	}

	/**
	 * Register an active task for tracking.
	 * Multiple tasks can be active simultaneously.
	 */
	public void registerActiveTask(NuzlockeTask task)
	{
		if (task == null)
		{
			log.warn("registerActiveTask called with null task");
			return;
		}

		String completionType = task.getCompletionType();
		String category = task.getCategory();

		// Find module that can handle this task
		AbstractTaskModule module = findModuleForTask(task);
		if (module == null)
		{
			log.warn(">>> NO MODULE found for task: {} (type: {}, category: {})",
				task.getName(), completionType, category);
			log.warn("    Available modules by type: {}", modulesByType.keySet());
			return;
		}


		// Add to active tasks list
		if (!activeTasks.contains(task))
		{
			activeTasks.add(task);
			taskToModuleMap.put(task.getTaskId(), module);
			module.addActiveTask(task);
		}
		else
		{
		}
	}

	/**
	 * Get all active tasks.
	 */
	public List<NuzlockeTask> getActiveTasks()
	{
		return new ArrayList<>(activeTasks);
	}

	/**
	 * Get a task by ID from active tasks.
	 */
	public NuzlockeTask getActiveTaskById(String taskId)
	{
		for (NuzlockeTask task : activeTasks)
		{
			if (task.getTaskId().equals(taskId))
			{
				return task;
			}
		}
		return null;
	}

	/**
	 * Find the module that can handle the given task.
	 */
	private AbstractTaskModule findModuleForTask(NuzlockeTask task)
	{
		// First try exact match by completion_type
		String type = task.getCompletionType();
		if (type != null)
		{
			AbstractTaskModule module = modulesByType.get(type.toUpperCase());
			if (module != null)
			{
				return module;
			}
		}

		// Try matching by category (e.g., "combat" category maps to NpcKillModule)
		String category = task.getCategory();
		if (category != null)
		{
			AbstractTaskModule module = modulesByType.get(category.toUpperCase());
			if (module != null)
			{
				return module;
			}
		}

		// Fall back to canHandle check
		for (AbstractTaskModule m : modules)
		{
			if (m.canHandle(task))
			{
				return m;
			}
		}

		return null;
	}

	/**
	 * Get the current progress for the active task.
	 */
	public int getCurrentProgress()
	{
		if (activeModule != null)
		{
			return activeModule.getCurrentProgress();
		}
		return 0;
	}

	/**
	 * Force a progress check.
	 */
	public void checkProgress()
	{
		if (activeModule != null)
		{
			activeModule.checkProgress();
		}
	}

	// --- TaskCompletionCallback Implementation ---

	@Override
	public void onTaskCompleted(NuzlockeTask task, int progress)
	{
		if (completionHandler != null)
		{
			completionHandler.onTaskCompleted(task, progress);
		}
	}

	@Override
	public void onServerVerified(NuzlockeTask task, int pointsAwarded)
	{
		if (completionHandler != null)
		{
			completionHandler.onServerVerified(task, pointsAwarded);
		}
	}

	@Override
	public void onProgressUpdated(NuzlockeTask task, int newProgress)
	{
		if (completionHandler != null)
		{
			completionHandler.onProgressUpdated(task, newProgress);
		}
	}

	/**
	 * Handler interface for the plugin to receive completion events.
	 */
	public interface TaskCompletionHandler
	{
		void onTaskCompleted(NuzlockeTask task, int progress);
		void onServerVerified(NuzlockeTask task, int pointsAwarded);
		void onProgressUpdated(NuzlockeTask task, int newProgress);
	}
}
