package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class TaskScreenshotManagerTest
{
	private static NuzlockeTask task(String id, String name)
	{
		NuzlockeTask t = new NuzlockeTask();
		t.setTaskId(id);
		t.setName(name);
		return t;
	}

	@Test
	void namesFileAfterTask()
	{
		assertEquals("Task Complete - Cook a tuna",
			TaskScreenshotManager.buildFileName(Collections.singletonList(task("cook_tuna", "Cook a tuna"))));
	}

	@Test
	void stripsCharactersIllegalInFileNames()
	{
		assertEquals("Task Complete - Kill 10 Goblins",
			TaskScreenshotManager.buildFileName(Collections.singletonList(task("x", "Kill 10 Goblins?"))));
	}

	@Test
	void countsTheRestOfTheBatch()
	{
		assertEquals("Task Complete - A (+2 more)",
			TaskScreenshotManager.buildFileName(Arrays.asList(task("a", "A"), task("b", "B"), task("c", "C"))));
	}

	@Test
	void fallsBackToTaskIdWhenUnnamed()
	{
		assertEquals("Task Complete - cook_tuna",
			TaskScreenshotManager.buildFileName(Collections.singletonList(task("cook_tuna", null))));
	}
}
