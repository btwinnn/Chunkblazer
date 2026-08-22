package com.chunkblazer.modules;

import net.runelite.api.events.VarbitChanged;
import net.runelite.client.chat.ChatMessageManager;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.TaskConstraints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VarbitCheckModule.
 * Tests varbit/varp state monitoring for VARBIT_CHECK and VARP_CHECK types.
 */
@ExtendWith(MockitoExtension.class)
class VarbitCheckModuleTest extends AbstractTaskModuleTest
{
	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private VarbitCheckModule varbitCheckModule;

	@BeforeEach
	void setUp() throws Exception
	{
		setupCommonMocks();

		injectField(varbitCheckModule, "client", client);
		injectField(varbitCheckModule, "clientThread", clientThread);
		injectField(varbitCheckModule, "eventBus", eventBus);
		injectField(varbitCheckModule, "config", config);

		varbitCheckModule.setCompletionCallback(completionCallback);
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
		assertEquals("VARBIT_CHECK", varbitCheckModule.getCompletionType());
	}

	@Test
	void testCanHandle_VarbitCheckType()
	{
		NuzlockeTask task = createTestTask("Activate Prayer", "activate_prayer", "VARBIT_CHECK", 1);
		assertTrue(varbitCheckModule.canHandle(task));
	}

	@Test
	void testCanHandle_VarpCheckType()
	{
		NuzlockeTask task = createTestTask("Check Varp", "check_varp", "VARP_CHECK", 1);
		assertTrue(varbitCheckModule.canHandle(task));
	}

	@Test
	void testCanHandle_WrongType()
	{
		NuzlockeTask task = createTestTask("Kill Monster", "kill_monster", "NPC_KILL", 5);
		assertFalse(varbitCheckModule.canHandle(task));
	}

	@Test
	void testAddActiveTask_Varbit()
	{
		NuzlockeTask task = createTaskWithVarbit("Activate Burst of Strength", "pray_burst", 4103, 1);

		when(client.getVarbitValue(4103)).thenReturn(0);

		varbitCheckModule.addActiveTask(task);

		assertEquals(1, varbitCheckModule.getActiveTasks().size());
	}

	@Test
	void testOnTaskCleared()
	{
		NuzlockeTask task = createTaskWithVarbit("Activate Burst of Strength", "pray_burst", 4103, 1);

		when(client.getVarbitValue(4103)).thenReturn(0);

		varbitCheckModule.addActiveTask(task);
		varbitCheckModule.onTaskCleared();

		assertTrue(varbitCheckModule.getActiveTasks().isEmpty());
	}

	@Test
	void testStartUpRegistersEventBus()
	{
		varbitCheckModule.startUp();
		verify(eventBus).register(varbitCheckModule);
	}

	@Test
	void testShutDownUnregistersEventBus()
	{
		varbitCheckModule.shutDown();
		verify(eventBus).unregister(varbitCheckModule);
	}

	@Test
	void testTaskCompletion_VarbitMatches()
	{
		NuzlockeTask task = createTaskWithVarbit("Activate Burst of Strength", "pray_burst", 4103, 1);

		// Initially varbit is 0
		when(client.getVarbitValue(4103)).thenReturn(0);
		varbitCheckModule.addActiveTask(task);

		// Simulate varbit becoming 1 (prayer activated)
		task.setCurrentProgress(1);
		task.setCompleted(true);

		assertTrue(task.isCompleted());
		assertEquals(1, task.getCurrentProgress());
	}

	// --- Bit-mask path (bitmap varbits like 4101 ACTIVE_PRAYERS) -------------------------------
	// Tasks set varbit_bit=N to check (value & (1 << N)) != 0 instead of value == expected.
	// Required because varbit 4101 packs all 29 prayer states into one int — exact-value match
	// would only fire when Smite is the ONLY active prayer, which is almost never true.

	private NuzlockeTask prayerBitTask(String name, String taskId, int bit)
	{
		// Helper: build a VARBIT_CHECK task on varbit 4101 with a bit position.
		NuzlockeTask task = createTaskWithVarbit(name, taskId, 4101, 1);
		task.setVarbitBit(bit);
		return task;
	}

	@Test
	void testBitMaskPath_TargetBitSet_Credits()
	{
		// Task wants bit 17 (Smite). Varbit 4101 currently has only bit 17 set
		// → (1 << 17) == 131072. Match.
		NuzlockeTask smite = prayerBitTask("Activate Smite", "pray_smite", 17);
		when(client.getVarbitValue(4101)).thenReturn(0);
		varbitCheckModule.addActiveTask(smite);

		when(client.getVarbitValue(4101)).thenReturn(1 << 17);
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertTrue(smite.isCompleted(), "Smite task should complete when bit 17 is set");
		verify(completionCallback).onTaskCompleted(eq(smite), eq(1));
	}

	@Test
	void testBitMaskPath_OtherBitsSetButNotTarget_DoesNotCredit()
	{
		// Task wants bit 17 (Smite). Other prayers are on (bits 22 Eagle Eye +
		// 23 Mystic Might), but NOT bit 17. Must NOT credit.
		NuzlockeTask smite = prayerBitTask("Activate Smite", "pray_smite", 17);
		when(client.getVarbitValue(4101)).thenReturn(0);
		varbitCheckModule.addActiveTask(smite);

		int otherPrayers = (1 << 22) | (1 << 23);
		when(client.getVarbitValue(4101)).thenReturn(otherPrayers);
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertFalse(smite.isCompleted(), "Smite task must NOT credit when only OTHER bits are set");
	}

	@Test
	void testBitMaskPath_TargetBitPlusOthers_StillCredits()
	{
		// Task wants bit 17 (Smite). Player has Smite + Eagle Eye both active
		// (bits 17 + 22). Other prayers being on is irrelevant — the task only
		// cares whether ITS bit is set. Must still credit.
		NuzlockeTask smite = prayerBitTask("Activate Smite", "pray_smite", 17);
		when(client.getVarbitValue(4101)).thenReturn(0);
		varbitCheckModule.addActiveTask(smite);

		int combined = (1 << 17) | (1 << 22);
		when(client.getVarbitValue(4101)).thenReturn(combined);
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertTrue(smite.isCompleted(), "Smite task must credit when bit 17 is set, regardless of other bits");
	}

	@Test
	void testBitMaskPath_DistinctPrayerTasks_OnlyMatchingOneCredits()
	{
		// Two prayer tasks on the same bitmap varbit. Player activates Eagle Eye
		// (bit 22). The Eagle Eye task credits; the Smite task does not.
		NuzlockeTask smite = prayerBitTask("Activate Smite", "pray_smite", 17);
		NuzlockeTask eagleEye = prayerBitTask("Activate Eagle Eye", "pray_eagle_eye", 22);

		when(client.getVarbitValue(4101)).thenReturn(0);
		varbitCheckModule.addActiveTask(smite);
		varbitCheckModule.addActiveTask(eagleEye);

		when(client.getVarbitValue(4101)).thenReturn(1 << 22);
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertTrue(eagleEye.isCompleted(), "Eagle Eye task must credit when bit 22 is set");
		assertFalse(smite.isCompleted(), "Smite task must NOT credit when only bit 22 (not 17) is set");
	}

	@Test
	void testLegacyEqualityPath_StillWorks()
	{
		// Tasks without varbit_bit continue to use the legacy exact-value match
		// (used by non-bitmap varbits like spellbook 0/1/2/3, quest progress, etc.).
		NuzlockeTask task = createTaskWithVarbit("Switch to Ancients", "switch_ancients", 4070, 1);
		// no setVarbitBit() — legacy mode

		when(client.getVarbitValue(4070)).thenReturn(0);
		varbitCheckModule.addActiveTask(task);

		when(client.getVarbitValue(4070)).thenReturn(1); // exact match
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertTrue(task.isCompleted(), "Legacy equality path must still credit on exact match");
	}

	// --- Schema migration: varbit_boolean + varbit_bit moved into constraints --------------
	// The canonical home for varbit_boolean and varbit_bit is now inside the
	// constraints block (alongside varbit_id) so all varbit-related schema lives
	// in one place. NuzlockeTask still exposes deprecated top-level mirrors for
	// JSON that hasn't been migrated, but new code should populate constraints.

	private NuzlockeTask taskWithConstraintsOnly(String name, String taskId, int varbitId,
												Integer expected, Integer bit)
	{
		// Build a VARBIT_CHECK task where varbit_boolean and varbit_bit live
		// in `constraints` and the top-level NuzlockeTask fields are null —
		// mirrors the post-migration JSON shape exactly.
		NuzlockeTask task = createTestTask(name, taskId, "VARBIT_CHECK", 1);
		TaskConstraints c = new TaskConstraints();
		c.setVarbitId(varbitId);
		c.setVarbitBoolean(expected);
		c.setVarbitBit(bit);
		task.setConstraints(c);
		return task;
	}

	@Test
	void testNewSchema_ConstraintsCarriesVarbitBoolean_Credits()
	{
		// varbit_boolean lives in constraints (no top-level mirror). Equality
		// check fires correctly because the module reads constraints first.
		NuzlockeTask task = taskWithConstraintsOnly(
			"Switch to Ancients", "switch_ancients", 4070, 1, null);

		when(client.getVarbitValue(4070)).thenReturn(0);
		varbitCheckModule.addActiveTask(task);

		when(client.getVarbitValue(4070)).thenReturn(1);
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertTrue(task.isCompleted(), "varbit_boolean from constraints must drive the check");
	}

	@Test
	void testNewSchema_ConstraintsCarriesVarbitBit_Credits()
	{
		// Bit-mask check from constraints (the prayer-style task post-migration).
		// Top-level varbit_bit is null on this task; the module must pick up the
		// bit from constraints.
		NuzlockeTask piety = taskWithConstraintsOnly(
			"Activate Piety", "pray_piety", 4101, 1, 26);

		when(client.getVarbitValue(4101)).thenReturn(0);
		varbitCheckModule.addActiveTask(piety);

		when(client.getVarbitValue(4101)).thenReturn(1 << 26);
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertTrue(piety.isCompleted(), "varbit_bit from constraints must drive the bit-mask check");
		assertNull(piety.getVarbitBit(), "top-level varbit_bit should remain unset on the post-migration shape");
	}

	@Test
	void testNewSchema_ConstraintsBit_OtherBitsSetButNotTarget_DoesNotCredit()
	{
		// Same wrong-bit semantics as before, but reading from constraints.
		NuzlockeTask piety = taskWithConstraintsOnly(
			"Activate Piety", "pray_piety", 4101, 1, 26);

		when(client.getVarbitValue(4101)).thenReturn(0);
		varbitCheckModule.addActiveTask(piety);

		// Bit 27 set (Augury), not bit 26 (Piety).
		when(client.getVarbitValue(4101)).thenReturn(1 << 27);
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertFalse(piety.isCompleted(),
			"Piety task must not fire when Augury's bit is set but Piety's isn't");
	}

	@Test
	void testFallback_ConstraintsHasIdOnly_TopLevelVarbitBoolean_StillWorks()
	{
		// Pre-migration shape: varbit_id in constraints, varbit_boolean at top
		// level. The fallback chain must still credit this task — otherwise we
		// silently break any JSON that hasn't been migrated yet.
		NuzlockeTask task = createTaskWithVarbit("Pre-migration task", "old_task", 4070, 1);
		// createTaskWithVarbit already puts varbit_id in constraints and
		// varbit_boolean at top-level — exact pre-migration shape.
		assertNull(task.getConstraints().getVarbitBoolean(), "test precondition: constraints version unset");
		assertEquals(Integer.valueOf(1), task.getVarbitBoolean(), "test precondition: top-level set");

		when(client.getVarbitValue(4070)).thenReturn(0);
		varbitCheckModule.addActiveTask(task);

		when(client.getVarbitValue(4070)).thenReturn(1);
		varbitCheckModule.onVarbitChanged(mock(VarbitChanged.class));

		assertTrue(task.isCompleted(), "Pre-migration JSON must keep working via top-level fallback");
	}
}
