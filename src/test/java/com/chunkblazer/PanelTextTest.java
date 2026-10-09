package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * The panel's long text lives in panel.properties. Each expected value below is the
 * original Java literal, so the resource must reproduce it exactly (whitespace,
 * HTML and non-ASCII included).
 */
public class PanelTextTest
{
	@Test
	public void resourceMatchesOriginalLiterals()
	{
		assertEquals("Please click the Pts orb for the new Task Overlay!", ChunkBlazerPanel.t("pts"));
		assertEquals("Every task you've completed, newest first, on chunkblazer.com", ChunkBlazerPanel.t("historyTip"));
		assertEquals("Reveal your account sync key to move this account to another computer", ChunkBlazerPanel.t("keyTip"));
		assertEquals("Wipe this account's local ChunkBlazer data and restore it fresh from the server. "
			+ "Your server progress is not touched.", ChunkBlazerPanel.t("resetTip"));
		assertEquals("Type this code in public chat and hit Enter to verify your ChunkBlazer account:", ChunkBlazerPanel.t("verifyBody"));
		assertEquals("Track your account progress at chunkblazer.com", ChunkBlazerPanel.t("siteTip"));
		assertEquals("No sync key yet. Turn on Server Sync and log in once, and your key is created automatically.", ChunkBlazerPanel.t("noKey"));
		assertEquals("This will reveal your account's Sync Key on screen. Anyone who can see your "
			+ "screen, including a stream or screen share, will be able to read it. "
			+ "Are you sure you want to show it?", ChunkBlazerPanel.t("reveal"));
		assertEquals("<html><body style='width:260px'>Select the key below and copy it "
			+ "(Ctrl+C), then save it somewhere safe like a password manager. To sync this account "
			+ "on another computer, "
			+ "paste it into the \"Sync recovery key\" setting there.<br><br><b>Anyone with this key "
			+ "can access your account. Do not share it.</b></body></html>", ChunkBlazerPanel.t("keyHelp"));
		assertEquals("<html><body style='width:270px'>This clears this account's ChunkBlazer data on THIS "
			+ "computer (mode, tasks, points, chunks, and the stored sync key) and restores it "
			+ "fresh from the server the next time you log in. Your server progress is not "
			+ "touched.<br><br>Use this only if this account is showing the wrong mode or another "
			+ "account's progress. After it finishes, restart RuneLite and log back in.<br><br>"
			+ "Continue?</body></html>", ChunkBlazerPanel.t("resetConfirm"));
		assertEquals("Local data cleared. Restart RuneLite and log back in to restore this account from the "
			+ "server. If sync does not come back on its own, paste this account's key into the "
			+ "\"Sync recovery key\" setting.", ChunkBlazerPanel.t("resetDone"));
		assertEquals("Turn on sync to save your progress across devices, show on the leaderboard, "
			+ "see other players, and play Competitive mode. Nothing is sent until you enable it. "
			+ "Your current progress will sync with the server when you do.", ChunkBlazerPanel.t("syncBody"));
		assertEquals("Competitive mode requires a new Ironman, Hardcore Ironman, or Ultimate Ironman account with combat level 9 or lower and no skill above level 3.",
			ChunkBlazerPanel.t("compReq"));
		assertEquals("Your starting tasks are dealt once you choose Enable Sync or Play offline.", ChunkBlazerPanel.t("syncHint"));
		assertEquals("Keep progress on this computer only. You can enable sync later.", ChunkBlazerPanel.t("offlineTip"));
		assertEquals("ChunkBlazer is a server-backed game mode. To save your progress\n"
			+ "and rank you on the leaderboards, the plugin sends data to\n"
			+ "ChunkBlazer's servers.\n"
			+ "\n"
			+ "WHAT IS SENT (only while \"Enable Server Sync\" is on):\n"
			+ "  • Your RuneScape name\n"
			+ "  • Your IP address\n"
			+ "  • Your current world and map region\n"
			+ "  • Progress events: NPC kills, XP/skill changes, items\n"
			+ "    obtained or equipped, and task completions\n"
			+ "  • If you're a Hardcore Ironman and lose that status: where\n"
			+ "    it happened and what killed you (shown on chunkblazer.com)\n"
			+ "\n"
			+ "WHAT IT IS USED FOR:\n"
			+ "  • Saving your unlocked chunks, tasks, points and game mode\n"
			+ "  • Server-side verification of completions (anti-cheat)\n"
			+ "  • Leaderboards and seeing other ChunkBlazer players online\n"
			+ "\n"
			+ "WHERE IT GOES:\n"
			+ "  • Over HTTPS to api.chunkblazer.com. Not shared with any\n"
			+ "    third parties.\n"
			+ "\n"
			+ "Track your account progress at chunkblazer.com.", ChunkBlazerPanel.t("dataUse"));
		assertEquals("<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>"
			+ "Verify your account, then choose your game mode. Type the code from the verify "
			+ "banner in public chat first."
			+ "</td></tr></table></html>", ChunkBlazerPanel.t("verifyFirst"));
		assertEquals("<html><i>This choice is permanent for this account!</i></html>", ChunkBlazerPanel.t("permanent"));
		assertEquals("Start anywhere. Play on any account. Featured on the casual leaderboard.", ChunkBlazerPanel.t("casual"));
		assertEquals("<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>"
			+ "Start anywhere. Play on any account. Featured on the casual leaderboard."
			+ "</td></tr></table></html>", ChunkBlazerPanel.t("casualBlurb"));
		assertEquals("Featured on the main page of the leaderboard and website. You must start on a new Ironman, Hardcore Ironman or Ultimate Ironman account (combat level 9 or lower).",
			ChunkBlazerPanel.t("competitive"));
		assertEquals("<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>"
			+ "Featured on the main page of the leaderboard and website. You must start on a new Ironman, Hardcore Ironman or Ultimate Ironman account (combat level 9 or lower)."
			+ "</td></tr></table></html>", ChunkBlazerPanel.t("competitiveBlurb"));
		assertEquals("<html>Log into Old School RuneScape to start playing ChunkBlazer.</html>", ChunkBlazerPanel.t("loggedOut"));
		for (GameMode mode : GameMode.values())
		{
			assertEquals("Are you sure you want to select " + mode.getName() + " mode?\n\n" +
				"This choice is PERMANENT for this account!", String.format(ChunkBlazerPanel.t("confirmMode"), mode.getName()));
		}
		assertEquals("Competitive mode needs a connection to the ChunkBlazer server to verify\n"
			+ "your RuneScape account.\n\n"
			+ "Do you want to enable Server Sync?", ChunkBlazerPanel.t("needSync"));
	}
}
