package net.runelite.client.plugins.chunkblazer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GameMode
{
	CASUAL("Casual", "Any account, Casual leaderboard"),
	NUZLOCKE("Full Nuzlocke", "Level 3 start in Lumbridge, Nuzlocke leaderboard");

	private final String name;
	private final String description;

	@Override
	public String toString()
	{
		return name;
	}
}
