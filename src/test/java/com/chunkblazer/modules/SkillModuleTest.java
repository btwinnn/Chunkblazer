package com.chunkblazer.modules;

import net.runelite.api.Skill;
import net.runelite.client.chat.ChatMessageManager;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.api.ChunkBlazerApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for SkillModule.
 * Tests skill level and XP tracking for SKILL_LEVEL and SKILL_XP completion types.
 */
@ExtendWith(MockitoExtension.class)
class SkillModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ChatMessageManager chatMessageManager;

	@Mock
	private ChunkBlazerApiClient apiClient;

	@InjectMocks
	private SkillModule skillModule;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(skillModule, "client", client);
		injectField(skillModule, "clientThread", clientThread);
		injectField(skillModule, "eventBus", eventBus);
		injectField(skillModule, "config", config);

		skillModule.setCompletionCallback(completionCallback);
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

	@Test
	void testGetCompletionType()
	{
		assertEquals("SKILL_LEVEL", skillModule.getCompletionType());
	}

	@Test
	void testCanHandle_SkillLevelType()
	{
		NuzlockeTask task = createTestTask("Reach 50 Attack", "attack_50", "SKILL_LEVEL", 1);
		assertTrue(skillModule.canHandle(task));
	}

	@Test
	void testCanHandle_SkillXpType()
	{
		NuzlockeTask task = createTestTask("Gain 10000 XP", "xp_10000", "SKILL_XP", 1);
		assertTrue(skillModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Kill Monster", "kill_monster", "NPC_KILL", 5);
		assertFalse(skillModule.canHandle(task));
	}

	@Test
	void testAddActiveTask_SkillLevel()
	{
		NuzlockeTask task = createTaskWithSkillConstraint("Reach 50 Attack", "attack_50", "SKILL_LEVEL", "Attack", 50, 0);

		lenient().when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(45);

		skillModule.addActiveTask(task);

		assertEquals(1, skillModule.getActiveTasks().size());
	}

	@Test
	void testAddActiveTask_SkillXp()
	{
		NuzlockeTask task = createTaskWithSkillConstraint("Gain 10000 XP", "xp_10000", "SKILL_XP", "Woodcutting", 0, 10000);

		lenient().when(client.getSkillExperience(Skill.WOODCUTTING)).thenReturn(5000);

		skillModule.addActiveTask(task);

		assertEquals(1, skillModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithSkillConstraint("Reach 50 Attack", "attack_50", "SKILL_LEVEL", "Attack", 50, 0);

		lenient().when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(45);

		skillModule.addActiveTask(task);
		skillModule.onTaskCleared();

		assertTrue(skillModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		skillModule.startUp();
		verify(eventBus).register(skillModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		skillModule.shutDown();
		verify(eventBus).unregister(skillModule);
	}

	@Test
	void testSkillNameParsing_Attack()
	{
		NuzlockeTask task = createTaskWithSkillConstraint("Test", "test", "SKILL_LEVEL", "Attack", 50, 0);

		lenient().when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(45);

		skillModule.addActiveTask(task);

		// Verify task was added (skill name was parsed correctly)
		assertEquals(1, skillModule.getActiveTasks().size());
	}

	@Test
	void testSkillNameParsing_Woodcutting()
	{
		NuzlockeTask task = createTaskWithSkillConstraint("Test", "test", "SKILL_LEVEL", "Woodcutting", 50, 0);

		lenient().when(client.getRealSkillLevel(Skill.WOODCUTTING)).thenReturn(45);

		skillModule.addActiveTask(task);

		assertEquals(1, skillModule.getActiveTasks().size());
	}
}
