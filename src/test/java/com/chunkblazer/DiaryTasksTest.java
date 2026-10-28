package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chunkblazer.modules.VarbitCheckModule;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import org.junit.jupiter.api.Test;

/** The achievement diary global tasks: one per tier, on the game's own completion varbits. */
class DiaryTasksTest
{
	private static final String[] TIERS = {"easy", "medium", "hard", "elite"};

	/** Each area's Easy, Medium, Hard and Elite completion varbit, by RuneLite's names. */
	private static final Map<String, int[]> VARBITS = new LinkedHashMap<>();

	static
	{
		VARBITS.put("ardougne", new int[]{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE});
		VARBITS.put("desert", new int[]{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE});
		VARBITS.put("falador", new int[]{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE});
		VARBITS.put("fremennik", new int[]{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE});
		VARBITS.put("kandarin", new int[]{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE});
		VARBITS.put("karamja", new int[]{VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE,
			VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE});
		VARBITS.put("kourend", new int[]{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE});
		VARBITS.put("lumbridge", new int[]{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE});
		VARBITS.put("morytania", new int[]{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE});
		VARBITS.put("varrock", new int[]{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE});
		VARBITS.put("western", new int[]{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE});
		VARBITS.put("wilderness", new int[]{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE});
	}

	/** One task per area and tier, on that tier's varbit, worth 2 (Easy) to 5 (Elite) points. */
	@Test
	void everyTierPresent() throws Exception
	{
		Map<String, NuzlockeTask> byId = new HashMap<>();
		for (NuzlockeTask t : diaryTasks())
		{
			assertNull(byId.put(t.getTaskId(), t), "duplicate " + t.getTaskId());
		}
		assertEquals(VARBITS.size() * TIERS.length, byId.size(), "one task per area and tier");
		for (Map.Entry<String, int[]> area : VARBITS.entrySet())
		{
			for (int i = 0; i < TIERS.length; i++)
			{
				NuzlockeTask t = byId.get("diary_" + area.getKey() + "_" + TIERS[i]);
				assertNotNull(t, area.getKey() + " " + TIERS[i] + " is missing");
				assertEquals("VARBIT_CHECK", t.getCompletionType(), t.getTaskId());
				assertEquals(area.getValue()[i], (int) t.getConstraints().getVarbitId(), t.getTaskId());
				assertEquals(i + 2, t.getBasePoints(), t.getTaskId());
			}
		}
	}

	/**
	 * The game's own diary script (client script 56) counts Karamja Easy, Medium and Hard
	 * as done at 2 (their varbits are 2 bits wide) and every other tier at 1.
	 */
	@Test
	void karamjaDoneAtTwo() throws Exception
	{
		for (NuzlockeTask t : diaryTasks())
		{
			boolean oldKaramja = t.getTaskId().startsWith("diary_karamja_") && !t.getTaskId().endsWith("_elite");
			assertEquals(oldKaramja ? 2 : 1, (int) t.getConstraints().getVarbitBoolean(), t.getTaskId());
		}
	}

	/** The backfill's check passes only at the target value, and never for a quest task. */
	@Test
	void satisfiedReadsVarbit() throws Exception
	{
		Client client = mock(Client.class);
		NuzlockeTask easy = null;
		for (NuzlockeTask t : diaryTasks())
		{
			easy = "diary_karamja_easy".equals(t.getTaskId()) ? t : easy;
		}
		assertNotNull(easy);
		when(client.getVarbitValue(VarbitID.ATJUN_EASY_DONE)).thenReturn(1);
		assertFalse(VarbitCheckModule.isSatisfied(client, easy), "1 isn't done for the old Karamja tiers");
		when(client.getVarbitValue(VarbitID.ATJUN_EASY_DONE)).thenReturn(2);
		assertTrue(VarbitCheckModule.isSatisfied(client, easy));

		NuzlockeTask quest = new NuzlockeTask();
		quest.setCompletionType("QUEST_CHECK");
		assertFalse(VarbitCheckModule.isSatisfied(client, quest));
	}

	private static List<NuzlockeTask> diaryTasks() throws Exception
	{
		String json = ShippedSeed.fileContent("Diary_Tasks.json");
		assertNotNull(json, "Diary_Tasks.json is missing from the shipped seed");
		Map<String, List<NuzlockeChunk>> data = new Gson().fromJson(json,
			new TypeToken<Map<String, List<NuzlockeChunk>>>()
			{
			}.getType());
		List<NuzlockeTask> out = new ArrayList<>();
		for (NuzlockeChunk group : data.get("Diary_Tasks"))
		{
			out.addAll(group.getTasks());
		}
		return out;
	}
}
