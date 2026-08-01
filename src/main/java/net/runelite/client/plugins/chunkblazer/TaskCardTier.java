package net.runelite.client.plugins.chunkblazer;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Card rarity for the task-reveal cards. One tier per card art set.
 *
 * <p><b>Where the tier comes from.</b> Task JSON has no difficulty field — {@code level}
 * is a skill-level requirement, not a rarity — so the tier is derived from
 * {@code base_points}, which is authored 1..5 across every task file and already means
 * "how much is this worth". The mapping is deliberately kept in this one method: if a
 * real {@code tier} field is ever authored into the task JSON, {@link #fromTask} is the
 * only thing that has to change.
 *
 * <p>Shipped distribution at the time of writing (4511 tasks): 1968 / 1299 / 696 / 225 /
 * 323 for points 1..5 — a sensible rarity pyramid, though Master (5) is a little more
 * common than Elite (4).
 */
@Getter
@RequiredArgsConstructor
public enum TaskCardTier
{
	EASY("Easy", new Color(88, 166, 216)),
	MEDIUM("Medium", new Color(106, 168, 79)),
	HARD("Hard", new Color(152, 108, 190)),
	ELITE("Elite", new Color(214, 158, 46)),
	MASTER("Master", new Color(196, 68, 62));

	/** Shown on the card and in the fallback art. */
	private final String displayName;

	/** Accent used for the name plate, the flip glow, and the art-missing fallback. */
	private final Color accent;

	/** Card face art, e.g. {@code Task_Cards/front_easy.png}. */
	public String getFrontAsset()
	{
		return "Task_Cards/front_" + name().toLowerCase() + ".png";
	}

	/** Card back art, e.g. {@code Task_Cards/back_easy.png}. */
	public String getBackAsset()
	{
		return "Task_Cards/back_" + name().toLowerCase() + ".png";
	}

	/**
	 * Tier for a task, from its {@code base_points}. Anything outside 1..5 — including
	 * an unauthored 0 — clamps into range rather than throwing, because a card with no
	 * art is far worse than a card of a slightly wrong rarity.
	 */
	public static TaskCardTier fromTask(NuzlockeTask task)
	{
		if (task == null)
		{
			return EASY;
		}
		return fromPoints(task.getBasePoints());
	}

	public static TaskCardTier fromPoints(int basePoints)
	{
		TaskCardTier[] tiers = values();
		int index = Math.max(1, Math.min(tiers.length, basePoints)) - 1;
		return tiers[index];
	}
}
