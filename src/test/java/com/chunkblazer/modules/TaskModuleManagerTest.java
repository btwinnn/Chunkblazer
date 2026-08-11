package com.chunkblazer.modules;

import com.chunkblazer.NuzlockeTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TaskModuleManager.
 * Tests task routing to correct modules and multi-task handling.
 */
@ExtendWith(MockitoExtension.class)
class TaskModuleManagerTest
{
	@Mock
	private NPCKillModule npcKillModule;

	@Mock
	private SkillModule skillModule;

	@Mock
	private ObtainModule obtainModule;

	@Mock
	private EquipModule equipModule;

	@Mock
	private FiremakingModule firemakingModule;

	@Mock
	private FarmingModule farmingModule;

	@Mock
	private AgilityModule agilityModule;

	@Mock
	private ThievingModule thievingModule;

	@Mock
	private ConstructionModule constructionModule;

	@Mock
	private VarbitCheckModule varbitCheckModule;

	@Mock
	private NpcDialogueModule npcDialogueModule;

	@Mock
	private QuestCheckModule questCheckModule;

	@Mock
	private ProgressionModule progressionModule;

	@InjectMocks
	private TaskModuleManager taskModuleManager;

	@BeforeEach
	void setUp() throws Exception
	{
		// Inject mocks
		injectField(taskModuleManager, "npcKillModule", npcKillModule);
		injectField(taskModuleManager, "skillModule", skillModule);
		injectField(taskModuleManager, "obtainModule", obtainModule);
		injectField(taskModuleManager, "equipModule", equipModule);
		injectField(taskModuleManager, "firemakingModule", firemakingModule);
		injectField(taskModuleManager, "farmingModule", farmingModule);
		injectField(taskModuleManager, "agilityModule", agilityModule);
		injectField(taskModuleManager, "thievingModule", thievingModule);
		injectField(taskModuleManager, "constructionModule", constructionModule);
		injectField(taskModuleManager, "varbitCheckModule", varbitCheckModule);
		injectField(taskModuleManager, "npcDialogueModule", npcDialogueModule);
		injectField(taskModuleManager, "questCheckModule", questCheckModule);
		injectField(taskModuleManager, "progressionModule", progressionModule);

		// Setup module completion types
		when(npcKillModule.getCompletionType()).thenReturn("NPC_KILL");
		when(skillModule.getCompletionType()).thenReturn("SKILL_LEVEL");
		when(obtainModule.getCompletionType()).thenReturn("OBTAIN");
		when(equipModule.getCompletionType()).thenReturn("EQUIP");
		when(firemakingModule.getCompletionType()).thenReturn("FIREMAKING");
		when(farmingModule.getCompletionType()).thenReturn("FARMING");
		when(agilityModule.getCompletionType()).thenReturn("AGILITY");
		when(thievingModule.getCompletionType()).thenReturn("THIEVING");
		when(constructionModule.getCompletionType()).thenReturn("CONSTRUCTION");
		when(varbitCheckModule.getCompletionType()).thenReturn("VARBIT_CHECK");
		when(npcDialogueModule.getCompletionType()).thenReturn("NPC_DIALOGUE");
		when(questCheckModule.getCompletionType()).thenReturn("QUEST_CHECK");
		when(progressionModule.getCompletionType()).thenReturn("SKILL_THRESHOLD");

		// Initialize the manager
		taskModuleManager.initialize();
	}

	private void injectField(Object target, String fieldName, Object value) throws Exception
	{
		Field field = findField(target.getClass(), fieldName);
		if (field != null)
		{
			field.setAccessible(true);
			field.set(target, value);
		}
	}

	private Field findField(Class<?> clazz, String fieldName)
	{
		while (clazz != null)
		{
			try
			{
				return clazz.getDeclaredField(fieldName);
			}
			catch (NoSuchFieldException e)
			{
				clazz = clazz.getSuperclass();
			}
		}
		return null;
	}

	private NuzlockeTask createTask(String name, String taskId, String completionType)
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setName(name);
		task.setTaskId(taskId);
		task.setCompletionType(completionType);
		task.setTargetQuantity(1);
		return task;
	}

	@Test
	void testInitialize_RegistersAllModules()
	{
		// Verify all modules had their callbacks set
		verify(npcKillModule).setCompletionCallback(taskModuleManager);
		verify(skillModule).setCompletionCallback(taskModuleManager);
		verify(obtainModule).setCompletionCallback(taskModuleManager);
		verify(equipModule).setCompletionCallback(taskModuleManager);
		verify(firemakingModule).setCompletionCallback(taskModuleManager);
		verify(agilityModule).setCompletionCallback(taskModuleManager);
		verify(thievingModule).setCompletionCallback(taskModuleManager);
		verify(constructionModule).setCompletionCallback(taskModuleManager);
		verify(varbitCheckModule).setCompletionCallback(taskModuleManager);
		verify(npcDialogueModule).setCompletionCallback(taskModuleManager);
	}

	@Test
	void testStartUp_StartsAllModules()
	{
		taskModuleManager.startUp();

		verify(npcKillModule).startUp();
		verify(skillModule).startUp();
		verify(obtainModule).startUp();
		verify(equipModule).startUp();
		verify(firemakingModule).startUp();
		verify(agilityModule).startUp();
		verify(thievingModule).startUp();
		verify(constructionModule).startUp();
		verify(varbitCheckModule).startUp();
		verify(npcDialogueModule).startUp();
	}

	@Test
	void testShutDown_StopsAllModules()
	{
		taskModuleManager.shutDown();

		verify(npcKillModule).shutDown();
		verify(skillModule).shutDown();
		verify(obtainModule).shutDown();
		verify(equipModule).shutDown();
		verify(firemakingModule).shutDown();
		verify(agilityModule).shutDown();
		verify(thievingModule).shutDown();
		verify(constructionModule).shutDown();
		verify(varbitCheckModule).shutDown();
		verify(npcDialogueModule).shutDown();
	}

	@Test
	void testRegisterActiveTask_NpcKill()
	{
		NuzlockeTask task = createTask("Kill Goblin", "kill_goblin", "NPC_KILL");

		taskModuleManager.registerActiveTask(task);

		verify(npcKillModule).addActiveTask(task);
		assertEquals(1, taskModuleManager.getActiveTasks().size());
	}

	@Test
	void testRegisterActiveTask_Combat()
	{
		NuzlockeTask task = createTask("Combat Task", "combat_task", "COMBAT");

		taskModuleManager.registerActiveTask(task);

		verify(npcKillModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Slayer()
	{
		NuzlockeTask task = createTask("Slayer Task", "slayer_task", "SLAYER");

		taskModuleManager.registerActiveTask(task);

		verify(npcKillModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Obtain()
	{
		NuzlockeTask task = createTask("Obtain Logs", "obtain_logs", "OBTAIN");

		taskModuleManager.registerActiveTask(task);

		verify(obtainModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Cooking()
	{
		NuzlockeTask task = createTask("Cook Shrimp", "cook_shrimp", "COOKING");

		taskModuleManager.registerActiveTask(task);

		verify(obtainModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Mining()
	{
		NuzlockeTask task = createTask("Mine Ore", "mine_ore", "MINING");

		taskModuleManager.registerActiveTask(task);

		verify(obtainModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Equip()
	{
		NuzlockeTask task = createTask("Equip Sword", "equip_sword", "EQUIP");

		taskModuleManager.registerActiveTask(task);

		verify(equipModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Firemaking()
	{
		NuzlockeTask task = createTask("Burn Logs", "burn_logs", "FIREMAKING");

		taskModuleManager.registerActiveTask(task);

		verify(firemakingModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Farming()
	{
		NuzlockeTask task = createTask("Plant some Sweetcorn", "plant_sweetcorn_seed", "FARMING");

		taskModuleManager.registerActiveTask(task);

		verify(farmingModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Agility()
	{
		NuzlockeTask task = createTask("Complete Lap", "complete_lap", "AGILITY");

		taskModuleManager.registerActiveTask(task);

		verify(agilityModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Thieving()
	{
		NuzlockeTask task = createTask("Pickpocket", "pickpocket", "THIEVING");

		taskModuleManager.registerActiveTask(task);

		verify(thievingModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_Construction()
	{
		NuzlockeTask task = createTask("Build Table", "build_table", "CONSTRUCTION");

		taskModuleManager.registerActiveTask(task);

		verify(constructionModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_VarbitCheck()
	{
		NuzlockeTask task = createTask("Activate Prayer", "activate_prayer", "VARBIT_CHECK");

		taskModuleManager.registerActiveTask(task);

		verify(varbitCheckModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_VarpCheck()
	{
		NuzlockeTask task = createTask("Check Varp", "check_varp", "VARP_CHECK");

		taskModuleManager.registerActiveTask(task);

		verify(varbitCheckModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_NpcDialogue()
	{
		NuzlockeTask task = createTask("Talk to NPC", "talk_npc", "NPC_DIALOGUE");

		taskModuleManager.registerActiveTask(task);

		verify(npcDialogueModule).addActiveTask(task);
	}

	@Test
	void testRegisterActiveTask_SkillLevel()
	{
		NuzlockeTask task = createTask("Reach Level 50", "level_50", "SKILL_LEVEL");

		taskModuleManager.registerActiveTask(task);

		verify(skillModule).addActiveTask(task);
	}

	@Test
	void testClearTask()
	{
		NuzlockeTask task = createTask("Kill Goblin", "kill_goblin", "NPC_KILL");
		taskModuleManager.registerActiveTask(task);

		taskModuleManager.clearTask();

		assertTrue(taskModuleManager.getActiveTasks().isEmpty());
	}

	@Test
	void testGetActiveTaskById()
	{
		NuzlockeTask task = createTask("Kill Goblin", "kill_goblin", "NPC_KILL");
		taskModuleManager.registerActiveTask(task);

		NuzlockeTask found = taskModuleManager.getActiveTaskById("kill_goblin");

		assertNotNull(found);
		assertEquals("Kill Goblin", found.getName());
	}

	@Test
	void testGetActiveTaskById_NotFound()
	{
		NuzlockeTask task = createTask("Kill Goblin", "kill_goblin", "NPC_KILL");
		taskModuleManager.registerActiveTask(task);

		NuzlockeTask found = taskModuleManager.getActiveTaskById("non_existent");

		assertNull(found);
	}

	@Test
	void testMultipleTasks()
	{
		NuzlockeTask task1 = createTask("Kill Goblin", "kill_goblin", "NPC_KILL");
		NuzlockeTask task2 = createTask("Obtain Logs", "obtain_logs", "OBTAIN");
		NuzlockeTask task3 = createTask("Equip Sword", "equip_sword", "EQUIP");

		taskModuleManager.registerActiveTask(task1);
		taskModuleManager.registerActiveTask(task2);
		taskModuleManager.registerActiveTask(task3);

		assertEquals(3, taskModuleManager.getActiveTasks().size());
	}
}
