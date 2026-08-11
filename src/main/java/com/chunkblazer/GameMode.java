package com.chunkblazer;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GameMode
{
	CASUAL("Casual", "Start anywhere. Play on any account. Featured on the casual leaderboard."),
	NUZLOCKE("Competitive", "Featured on the main page of the leaderboard and website. You must start on a fresh level 3 account.");

	private final String name;
	private final String description;

	@Override
	public String toString()
	{
		return name;
	}
}
