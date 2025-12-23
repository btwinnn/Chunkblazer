package net.runelite.client.plugins.chunkblazer.api;

import lombok.Builder;
import lombok.Data;

/**
 * Report for skill level/XP changes sent to server for verification.
 */
@Data
@Builder
public class SkillChangeReport
{
    /** Player RSN hash */
    private String playerHash;

    /** Task ID this skill change is for */
    private String taskId;

    /** Skill ID (0=Attack, 1=Defence, etc.) */
    private int skillId;

    /** Skill name */
    private String skillName;

    /** Previous level */
    private int previousLevel;

    /** New level */
    private int newLevel;

    /** Previous XP */
    private int previousXp;

    /** New XP */
    private int newXp;

    /** XP gained in this event */
    private int xpGained;

    /** Region ID where XP was gained */
    private int regionId;

    /** Game tick when event occurred */
    private int gameTick;

    /** Client timestamp */
    private long timestamp;

    /** Total level after this change */
    private int totalLevel;

    /** Action that caused the XP gain (if known) */
    private String action;
}
