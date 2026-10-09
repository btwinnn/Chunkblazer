package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

/**
 * Proves every module.* key in strings.properties reproduces the original
 * inline Java expression from the task modules, character for character.
 * Each expected value is the original expression (copied from the source at
 * 6af0185) with concrete sample values substituted for its variables.
 */
public class StringsModuleTest
{
	private static void assertLog(String original, String key, Object[] args)
	{
		assertEquals(MessageFormatter.arrayFormat(original, args).getMessage(),
			MessageFormatter.arrayFormat(Strings.t(key), args).getMessage());
		assertEquals(original, Strings.t(key));
	}

	@Test
	public void moduleTextMatchesOriginal()
	{
		// module.announce (AbstractTaskModule.announce)
		assertEquals("<col=" + "3366ff" + ">[ChunkBlazer]</col> <col=" + "228b22" + ">" + "Task Progress:" + "</col> <col="
				+ "000000" + ">" + "Cut logs" + "</col>" + " (1/5)",
			Strings.t("module.announce", "3366ff", "228b22", "Task Progress:", "000000", "Cut logs", " (1/5)"));
		assertEquals("<col=" + "3366ff" + ">[ChunkBlazer]</col> <col=" + "1a5276" + ">" + "Task Complete!" + "</col> <col="
				+ "000000" + ">" + "Cut logs" + "</col>" + "",
			Strings.t("module.announce", "3366ff", "1a5276", "Task Complete!", "000000", "Cut logs", ""));
		// module.slotMustBeEmptyAllowed (NPCKillModule.validateEquipmentForTask)
		assertEquals("Cape" + " slot must be empty - only allowed slots: " +
				java.util.stream.Stream.of("Head", "Body")
					.reduce((a, b) -> a + ", " + b)
					.orElse("none"),
			Strings.t("module.slotMustBeEmptyAllowed", "Cape", java.util.stream.Stream.of("Head", "Body")
				.reduce((a, b) -> a + ", " + b)
				.orElse("none")));
		assertEquals("Cape" + " slot must be empty - only allowed slots: " + "none",
			Strings.t("module.slotMustBeEmptyAllowed", "Cape", "none"));
		// module.log.raidNoChallenge
		assertLog("RAID_CHALLENGE task {} has no challenge block",
			"module.log.raidNoChallenge", new Object[]{"T1"});
		// module.raidTookDamage
		assertEquals("You took damage. This challenge must be done without taking a hit.",
			Strings.t("module.raidTookDamage"));
		// module.raidWrongStyle
		assertEquals("You hit this boss with a " + "Crush"
					+ " attack, " + "Stab" + " only.",
			Strings.t("module.raidWrongStyle", "Crush", "Stab"));
		// module.raidProtectedNpcKilled
		assertEquals("A protected NPC was killed. This run no longer counts.",
			Strings.t("module.raidProtectedNpcKilled"));
		// module.raidTooSlow
		assertEquals("You did not defeat it fast enough. Kill it within "
									+ 50 + " ticks.",
			Strings.t("module.raidTooSlow", 50));
		// module.raidForbiddenPickup
		assertEquals("You picked up an item that isn't allowed for this challenge.",
			Strings.t("module.raidForbiddenPickup"));
		// module.raidRunOn
		assertEquals("Run was on. This challenge must be done with run disabled.",
			Strings.t("module.raidRunOn"));
		// module.raidNoRequiredWeapon
		assertEquals("You weren't using a required weapon for this challenge.",
			Strings.t("module.raidNoRequiredWeapon"));
		// module.raidForbiddenWeapon
		assertEquals("You used a forbidden weapon for this challenge.",
			Strings.t("module.raidForbiddenWeapon"));
		// module.raidSlotNotEmpty
		assertEquals("An equipment slot that must stay empty is filled.",
			Strings.t("module.raidSlotNotEmpty"));
		// module.raidMissingInvItem
		assertEquals("You must keep the required item in your inventory for this challenge.",
			Strings.t("module.raidMissingInvItem"));
		// module.raidMissingInvGroup
		assertEquals("You must keep a required item in your inventory for this challenge.",
			Strings.t("module.raidMissingInvGroup"));
		// module.raidConsumedTooMany
		assertEquals("You consumed too many. The limit is " + 3
					+ " (you've used " + 4 + ").",
			Strings.t("module.raidConsumedTooMany", 3, 4));
		// module.raidInventoryNotEmpty
		assertEquals("Your inventory must be completely empty for this challenge.",
			Strings.t("module.raidInventoryNotEmpty"));
		// module.raidMissingSet
		assertEquals("You must be wearing the full required set for this challenge.",
			Strings.t("module.raidMissingSet"));
		// module.raidForbiddenPrayer
		assertEquals("You used a prayer that isn't allowed for this challenge.",
			Strings.t("module.raidForbiddenPrayer"));
		// module.raidMissingGear
		assertEquals("You must be wearing the required gear for this challenge.",
			Strings.t("module.raidMissingGear"));
		// module.raidLeftArea
		assertEquals("You left the area this challenge must be done in.",
			Strings.t("module.raidLeftArea"));
		// module.raidGearTooValuable
		assertEquals("Your equipped gear is worth too much. Must be under "
				+ "100K" + " (you have " + "250K" + ").",
			Strings.t("module.raidGearTooValuable", "100K", "250K"));
		// module.raidGearTooCheap
		assertEquals("Your equipped gear isn't worth enough. Need at least "
				+ "1M" + " (you have " + "20K" + ").",
			Strings.t("module.raidGearTooCheap", "1M", "20K"));
		// module.raidPrayerBonusLow
		assertEquals("Your equipped Prayer bonus is too low. Need at least +"
				+ 20 + " (you have +" + 5 + ").",
			Strings.t("module.raidPrayerBonusLow", 20, 5));
		// module.raidCrushDefenceLow
		assertEquals("Your equipped Crush defence is too low. Need at least "
				+ 300 + " (you have " + -12 + ").",
			Strings.t("module.raidCrushDefenceLow", 300, -12));
		// module.raidRangedDefenceLow
		assertEquals("Your equipped Ranged defence is too low. Need at least "
				+ 250 + " (you have " + 40 + ").",
			Strings.t("module.raidRangedDefenceLow", 250, 40));
		// module.raidHitpointsHigh
		assertEquals("Your Hitpoints are too high. Stay at " + 10
					+ " or below (you have " + 99 + ").",
			Strings.t("module.raidHitpointsHigh", 10, 99));
		// module.raidPrayerLost
		assertEquals("You lost a Prayer point. This challenge must be done without losing any.",
			Strings.t("module.raidPrayerLost"));
		// module.raidPrayerRestored
		assertEquals("You restored Prayer points. This challenge must be done without restoring any.",
			Strings.t("module.raidPrayerRestored"));
		// module.raidPrayerHigh
		assertEquals("Your Prayer points went above " + 1
					+ ". Keep them at or below that.",
			Strings.t("module.raidPrayerHigh", 1));
		// module.raidDefenceBonusHigh
		assertEquals("A Defence bonus went above +" + 0
					+ " (highest is +" + 17 + "). Keep them at or below that.",
			Strings.t("module.raidDefenceBonusHigh", 0, 17));
		// module.raidFreeSlotsLow
		assertEquals("You must keep at least " + 5
					+ " empty inventory spaces (you have " + 2 + ").",
			Strings.t("module.raidFreeSlotsLow", 5, 2));
		// module.raidNotVengeance
		assertEquals("The finishing blow wasn't a Vengeance rebound. Vengeance must land the kill.",
			Strings.t("module.raidNotVengeance"));
		// module.raidChallengeComplete
		assertEquals("<col=" + "3366ff" + ">[ChunkBlazer]</col> "
			+ "<col=" + "1a5276" + ">Challenge Complete!</col> "
			+ "<col=" + "000000" + ">" + "Dodge it" + "</col>",
			Strings.t("module.raidChallengeComplete", "3366ff", "1a5276", "000000", "Dodge it"));
		// module.raidChallengeProgress
		assertEquals("<col=" + "3366ff" + ">[ChunkBlazer]</col> "
			+ "<col=" + "228b22" + ">Challenge Progress:</col> "
			+ "<col=" + "000000" + ">" + "Room x3" + "</col> "
			+ "(" + 2 + "/" + 3 + ")",
			Strings.t("module.raidChallengeProgress", "3366ff", "228b22", "000000", "Room x3", 2, 3));
		// module.raidChallengeFailed
		assertEquals("<col=" + "3366ff" + ">[ChunkBlazer]</col> "
			+ "<col=" + "ff3333" + ">Challenge Failed:</col> "
			+ "<col=" + "000000" + ">" + "No hit" + "</col>",
			Strings.t("module.raidChallengeFailed", "3366ff", "ff3333", "000000", "No hit"));
		// module.raidLevelLow
		assertEquals("Raid level was too low. Need " + 300
				+ "+ (was " + 150 + ").",
			Strings.t("module.raidLevelLow", 300, 150));
		// module.raidNotSolo
		assertEquals("This challenge must be done solo (team size was " + 3 + ").",
			Strings.t("module.raidNotSolo", 3));
		// module.raidWeightLow
		assertEquals("Your weight was too low. Need at least " + 40 + "kg.",
			Strings.t("module.raidWeightLow", 40));
		// module.raidWeightHigh
		assertEquals("Your weight was too high. Must be at most " + 0 + "kg.",
			Strings.t("module.raidWeightHigh", 0));
		// module.raidGearWasTooValuable
		assertEquals("Your equipped gear was worth too much for this challenge.",
			Strings.t("module.raidGearWasTooValuable"));
		// module.raidRequirementsUnmet
		assertEquals("The run didn't meet this challenge's requirements.",
			Strings.t("module.raidRequirementsUnmet"));
		// module.dropNotReceived
		assertEquals(String.format("Required drop '%s' was not received (collected %d/%d)",
						"Dragon bones", 1, 2),
			Strings.t("module.dropNotReceived", "Dragon bones", 1, 2));
		// module.killedWithDrop
		assertEquals(String.format("Killed %s and received %s drop",
					"Goblin", "Bones"),
			Strings.t("module.killedWithDrop", "Goblin", "Bones"));
		// module.killedWithDrop
		assertEquals(String.format("Killed %s and received %s drop", "Cow", "Cowhide"),
			Strings.t("module.killedWithDrop", "Cow", "Cowhide"));
		// module.notOnSlayerTask
		assertEquals("Not on a slayer task for this monster",
			Strings.t("module.notOnSlayerTask"));
		// module.cannonProhibited
		assertEquals("Cannon use is prohibited for restricted tasks. Kill it without your cannon firing.",
			Strings.t("module.cannonProhibited"));
		// module.restrictedFullHealth
		assertEquals("Restricted kill must start from full health. This monster was already damaged when you first hit it.",
			Strings.t("module.restrictedFullHealth"));
		// module.restrictedFreshFight
		assertEquals(String.format(
						"Restricted kill must be a fresh fight. Wait ~%.0fs after logging in, then fight it start to finish.",
						18.0),
			Strings.t("module.restrictedFreshFight", 18.0));
		// module.restrictedSolo
		assertEquals("Restricted kill must be solo. Another player damaged this monster.",
			Strings.t("module.restrictedSolo"));
		// module.restrictedGearWorn
		assertEquals("Equipment: restricted gear was worn during the fight",
			Strings.t("module.restrictedGearWorn"));
		// module.mustHaveNoEquipment
		assertEquals("Must have no equipment - currently have " + 4 + " items equipped",
			Strings.t("module.mustHaveNoEquipment", 4));
		// module.equipNothingRequired
		assertEquals("Equip nothing required - currently have " + 2 + " items equipped",
			Strings.t("module.equipNothingRequired", 2));
		// module.missingRequiredEquipment
		assertEquals("Missing required equipment: item ID " + 4151,
			Strings.t("module.missingRequiredEquipment", 4151));
		// module.equipmentNotAllowed
		assertEquals("Forbidden equipment detected: item ID " + 1277 + " is not in allowed list",
			Strings.t("module.equipmentNotAllowed", 1277));
		// module.forbiddenEquipment
		assertEquals("Forbidden equipment detected: item ID " + 11802,
			Strings.t("module.forbiddenEquipment", 11802));
		// module.slotMustBeEmpty
		assertEquals("Shield" + " slot must be empty (has item ID " + 1540 + ")",
			Strings.t("module.slotMustBeEmpty", "Shield", 1540));
		// module.varbitMismatch
		assertEquals("Varbit " + 3698 + " must be " + 0 + " but is " + 1,
			Strings.t("module.varbitMismatch", 3698, 0, 1));
		// module.noCombatStart
		assertEquals("Time constraint failed - no combat start recorded",
			Strings.t("module.noCombatStart"));
		// module.killTooSlow
		assertEquals(String.format("Kill took %d ticks (%.1f sec), max allowed is %d ticks (%.1f sec)",
				25, 15.0, 20, 12.0),
			Strings.t("module.killTooSlow", 25, 15.0, 20, 12.0));
		// module.log.equipNullItemIds
		assertLog("EquipModule: itemIds is NULL for a RequiredItem on task '{}'",
			"module.log.equipNullItemIds", new Object[]{"Wear it"});
		// module.log.equipNoRequiredItems
		assertLog("EquipModule: no required_items defined for EQUIP task '{}'",
			"module.log.equipNoRequiredItems", new Object[]{"Wear it"});
		// module.log.equipReportRejected
		assertLog("EquipModule: server rejected equip report: {}",
			"module.log.equipReportRejected", new Object[]{"bad"});
		// module.requiresSkillLevel
		assertEquals(String.format("Requires %s level %d (you have %d)",
						"Attack", 60, 40),
			Strings.t("module.requiresSkillLevel", "Attack", 60, 40));
		// module.requiresSkillLevel
		assertEquals(String.format("Requires %s level %d (you have %d)",
						"Ranged", 70, 1),
			Strings.t("module.requiresSkillLevel", "Ranged", 70, 1));
		// module.requiresCombatLevel
		assertEquals(String.format("Requires combat level %d (you have %d)",
						100, 3),
			Strings.t("module.requiresCombatLevel", 100, 3));
		// module.equipNeedsAllowedRegion
		assertEquals("Must be in an allowed region to equip this item",
			Strings.t("module.equipNeedsAllowedRegion"));
		// module.equipNeedsRegion
		assertEquals("Must be in specific region to equip this item",
			Strings.t("module.equipNeedsRegion"));
		// module.log.caNoIds
		assertLog("COMBAT_ACHIEVEMENT task {} has no ca_ids, it can never complete",
			"module.log.caNoIds", new Object[]{"ca1"});
		// module.log.constructionNoObject
		assertLog("CONSTRUCTION task '{}' ({}) has no required_finished_object, cannot track it",
			"module.log.constructionNoObject", new Object[]{"Chair", "c1"});
		// module.log.constructionRegionFailed
		assertLog("ConstructionModule: failed to resolve region for task '{}'",
			"module.log.constructionRegionFailed", new Object[]{"c1"});
		// module.log.obtainNullItemIds
		assertLog("      >>> WARNING: itemIds is NULL/empty for this RequiredItem, slot ignored!",
			"module.log.obtainNullItemIds", new Object[]{});
		// module.log.obtainNoRequiredItems
		assertLog("  >>> WARNING: No required_items defined for this OBTAIN task!",
			"module.log.obtainNoRequiredItems", new Object[]{});
		// module.log.skillNoSkill
		assertLog("SKILL_THRESHOLD task '{}' has no constraints.required_skill, not tracking",
			"module.log.skillNoSkill", new Object[]{"s1"});
		// module.log.skillUnknown
		assertLog("SKILL_THRESHOLD task '{}' names unknown skill '{}', not tracking",
			"module.log.skillUnknown", new Object[]{"s1", "SAILING"});
		// module.log.skillNoLevel
		assertLog("SKILL_THRESHOLD task '{}' has no meaningful required_level ({}), not tracking",
			"module.log.skillNoLevel", new Object[]{"s1", 1});
		// module.log.questNoQuest
		assertLog("QUEST_CHECK task '{}' has no constraints.quest, not tracking",
			"module.log.questNoQuest", new Object[]{"q1"});
		// module.log.questUnknown
		assertLog("QUEST_CHECK task '{}' names unknown quest constant '{}', "
					+ "task data is likely newer than the RuneLite API this plugin was built against",
			"module.log.questUnknown", new Object[]{"q1", "NEW_QUEST"});
	}
}
