package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

/**
 * Each ui.* key must reproduce, character for character, the Java expression it replaced
 * (copied from the original source, with sample values for anything spliced in).
 */
public class StringsUiTest
{
	private static String log(String key, Object... args)
	{
		return MessageFormatter.arrayFormat(Strings.t(key), args).getMessage();
	}

	@Test
	public void plainTextIsUnchanged()
	{
		assertEquals("You're not in a chunk with tasks.", Strings.t("ui.notInTaskChunk"));
		assertEquals("Click a task's book icon to archive it here.", Strings.t("ui.archiveEmpty"));
		assertEquals("Star tasks in the Active tab to save them here.", Strings.t("ui.savedEmpty"));
		assertEquals("Only if (all ticked)", Strings.t("ui.filterOnlyIf"));
		assertEquals("Type (any ticked)", Strings.t("ui.filterType"));
		assertEquals("How the task you track is shown in game. Off hides it.", Strings.t("ui.setTaskBox"));
		assertEquals("Using an NPC or object a task needs (attack, talk, chop, mine...) tracks that task.", Strings.t("ui.setAutoTrack"));
		assertEquals("A bar at the bottom of the screen with your saved tasks, nearest first.", Strings.t("ui.setSavedTracker"));
		assertEquals("Adds a Tasks submenu when right-clicking NPCs and objects your tasks need.", Strings.t("ui.setRightClick"));
		assertEquals("Task progress (3/10), completed and failed messages in the chat box. Pick them one by one in RuneLite's plugin settings.", Strings.t("ui.setChatMessages"));
		assertEquals("Which task targets get an outline: all of them, saved tasks, ones you can do now, or none.", Strings.t("ui.setOutlines"));
		assertEquals("Outlines items your tasks need (gear to equip, tools like a knife or tinderbox).", Strings.t("ui.setTaskItems"));
		assertEquals("A see-through wall between unlocked and locked chunks.", Strings.t("ui.setChunkWalls"));
		assertEquals("Shows the chunk's name at the top of the screen when you walk into a new one.", Strings.t("ui.setNameBanner"));
		assertEquals("Draws chunk borders and colours on the world map.", Strings.t("ui.setMapChunks"));
		assertEquals("Outlines each unlocked chunk. Off shows your unlocked area as one piece.", Strings.t("ui.setGridLines"));
		assertEquals("Writes what each unlockable chunk costs inside it (free, points or a boss token).", Strings.t("ui.setCostLabels"));
		assertEquals("A key to the chunk colours in the corner of the world map.", Strings.t("ui.setLegend"));
		assertEquals("Hover a setting to see what it does. Colours are in RuneLite's plugin settings.", Strings.t("ui.settingsFooter"));
		assertEquals("Click the card to reveal your task", Strings.t("ui.cardClickToReveal"));
		assertEquals("Task added. Click to close", Strings.t("ui.cardAddedClose"));
		assertEquals("Send 5 To Task List", Strings.t("ui.cardSendFive"));
		assertEquals("Start anywhere. Play on any account. Featured on the casual leaderboard.", Strings.t("ui.modeCasual"));
		assertEquals("Featured on the main page of the leaderboard and website. Requires new Ironman, Hardcore Ironman, or Ultimate Ironman account with combat level 9 or lower and no skills above level 3.", Strings.t("ui.modeCompetitive"));
		assertEquals("group_content tasks cannot have a time limit (the solo-only gates it enables can't be met in a team)", Strings.t("ui.groupTimeLimit"));
		assertEquals("group_content tasks cannot have equipment constraints (the solo-only gates they enable can't be met in a team)", Strings.t("ui.groupEquipment"));
	}

	@Test
	public void templatesMatchTheOriginalConcatenation()
	{
		int extra = 7;
		assertEquals("+" + extra + " more in the task window", Strings.t("ui.moreInTaskWindow", extra));

		String skillName = "Mining";
		assertEquals("Click " + skillName + " again to clear", Strings.t("ui.clickSkillToClear", skillName));

		int required = 42;
		for (int left : new int[]{1, 2, 13})
		{
			assertEquals(left + (left == 1 ? " level-up left" : " level-ups left") + " - next: level "
				+ required, Strings.t("ui.levelUpsLeft", left, left == 1 ? "" : "s", required));
		}

		String display = "Cook's Assistant (100%)";
		assertEquals(display + " isn't in your task list (it's done, or not a ChunkBlazer quest).",
			Strings.t("ui.questNotInList", display));

		String name = "Lumbridge";
		assertEquals("Open the world map to see " + name + ".", Strings.t("ui.openMapToSee", name));

		int total = 12;
		assertEquals("Click to reveal. " + total + " cards to open", Strings.t("ui.cardsToOpen", total));
		assertEquals("Send All To Task List (" + total + ")", Strings.t("ui.cardSendAll", total));

		int left = 3;
		assertEquals("Task added. Click for the next card (" + left + " left)", Strings.t("ui.cardAddedNext", left));
	}

	@Test
	public void logsMatchTheOriginalMessages()
	{
		String candidate = "/card_art/t1.png";
		String error = "image == null!";
		assertEquals(MessageFormatter.arrayFormat("Failed to load task card art {}: {}", new Object[]{candidate, error}).getMessage(),
			log("ui.log.cardArtFailed", candidate, error));

		String taskId = "kill_goblin";
		assertEquals(MessageFormatter.arrayFormat("[CHUNKBLAZER] unrevealed task '{}' has no definition, discarding", new Object[]{taskId}).getMessage(),
			log("ui.log.cardNoDefinition", taskId));
	}
}
