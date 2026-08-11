package com.chunkblazer;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The group_content flag and the schema rule that keeps it honest.
 *
 * <p>Pairing group_content with a time or equipment constraint produces a task
 * that loads, displays, and rolls — but can never be completed, because the
 * solo-only gates it implies are unsatisfiable in a team. That is an authoring
 * mistake we want caught at load, not by a player who spends an evening on it.
 */
class NuzlockeTaskGroupContentTest
{
	private final Gson gson = new Gson();

	@Test
	void absentFlagMeansSolo()
	{
		NuzlockeTask task = gson.fromJson(
			"{\"name\":\"Defeat a Mugger\",\"taskID\":\"defeat_mugger\"}", NuzlockeTask.class);

		assertFalse(task.isGroupContent(), "a task with no group_content field must default to solo");
		assertNull(task.getGroupContentSchemaError(), "a plain solo task has no schema error");
	}

	@Test
	void flagIsParsedFromJson()
	{
		NuzlockeTask task = gson.fromJson(
			"{\"name\":\"Defeat Nex\",\"taskID\":\"defeat_nex\",\"group_content\":true}", NuzlockeTask.class);

		assertTrue(task.isGroupContent(), "group_content:true must deserialize");
		assertNull(task.getGroupContentSchemaError(), "an unconstrained group task is valid");
	}

	@Test
	void groupContentWithTimeLimitIsRejected()
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setTaskId("defeat_nex_fast");
		task.setGroupContent(true);
		TaskConstraints c = new TaskConstraints();
		c.setTimeInTicks(100);
		task.setConstraints(c);

		String problem = task.getGroupContentSchemaError();
		assertNotNull(problem, "group_content + time limit must be reported as a schema error");
		assertTrue(problem.contains("time limit"), "the message should name the offending constraint: " + problem);
	}

	@Test
	void groupContentWithEquipmentConstraintIsRejected()
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setTaskId("defeat_nex_naked");
		task.setGroupContent(true);
		TaskConstraints c = new TaskConstraints();
		c.setForbiddenEquipmentIds(Arrays.asList(1234));
		task.setConstraints(c);

		String problem = task.getGroupContentSchemaError();
		assertNotNull(problem, "group_content + equipment constraint must be reported as a schema error");
		assertTrue(problem.contains("equipment"), "the message should name the offending constraint: " + problem);
	}

	/**
	 * Constraints that don't engage the solo-only gates are fine on group tasks —
	 * a required boss drop is the obvious legitimate case.
	 */
	@Test
	void groupContentWithDropConstraintIsAllowed()
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setTaskId("obtain_nex_unique");
		task.setGroupContent(true);
		TaskConstraints c = new TaskConstraints();
		c.setDroppedItem("Zaryte vambraces");
		c.setDroppedItemIds(Arrays.asList(26235));
		task.setConstraints(c);

		assertNull(task.getGroupContentSchemaError(),
			"a drop constraint doesn't engage the solo gates, so it's valid on group content");
	}

	/** A solo task keeps its constraints — the rule is scoped to group content. */
	@Test
	void soloTaskWithTimeLimitIsUnaffected()
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setTaskId("defeat_mugger_fast");
		TaskConstraints c = new TaskConstraints();
		c.setTimeInTicks(27);
		task.setConstraints(c);

		assertNull(task.getGroupContentSchemaError(), "solo speed tasks must remain valid");
	}
}
