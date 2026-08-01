package net.runelite.client.plugins.chunkblazer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The tier mapping is the one place the card art is chosen, and it runs against
 * author-supplied numbers, so it has to be total: every task must land on a tier with
 * art, including tasks whose base_points was never authored.
 */
class TaskCardTierTest
{
	@Test
	void basePointsMapOntoTheFiveTiers()
	{
		assertEquals(TaskCardTier.EASY, TaskCardTier.fromPoints(1));
		assertEquals(TaskCardTier.MEDIUM, TaskCardTier.fromPoints(2));
		assertEquals(TaskCardTier.HARD, TaskCardTier.fromPoints(3));
		assertEquals(TaskCardTier.ELITE, TaskCardTier.fromPoints(4));
		assertEquals(TaskCardTier.MASTER, TaskCardTier.fromPoints(5));
	}

	@Test
	void outOfRangePointsClampInsteadOfThrowing()
	{
		// An unauthored base_points reads as 0. Drawing an Easy card is a small wrong;
		// an exception inside an overlay's render loop is a broken client.
		assertEquals(TaskCardTier.EASY, TaskCardTier.fromPoints(0));
		assertEquals(TaskCardTier.EASY, TaskCardTier.fromPoints(-3));
		assertEquals(TaskCardTier.MASTER, TaskCardTier.fromPoints(6));
		assertEquals(TaskCardTier.MASTER, TaskCardTier.fromPoints(999));
	}

	@Test
	void nullTaskDoesNotThrow()
	{
		assertEquals(TaskCardTier.EASY, TaskCardTier.fromTask(null));
	}

	@Test
	void tierFromTaskUsesBasePoints()
	{
		NuzlockeTask task = new NuzlockeTask();
		task.setBasePoints(4);
		assertEquals(TaskCardTier.ELITE, TaskCardTier.fromTask(task));
	}

	@Test
	void everyTierNamesBothArtFiles()
	{
		for (TaskCardTier tier : TaskCardTier.values())
		{
			assertNotNull(tier.getAccent(), tier + " needs an accent colour for the fallback card");
			assertEquals("Task_Cards/front_" + tier.name().toLowerCase() + ".png", tier.getFrontAsset());
			assertEquals("Task_Cards/back_" + tier.name().toLowerCase() + ".png", tier.getBackAsset());
		}
	}
}
