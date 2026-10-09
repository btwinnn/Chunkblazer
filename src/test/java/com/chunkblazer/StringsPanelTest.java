package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Each panel string moved to strings.properties must reproduce the original Java
 * expression (copied from 6af0185) character for character.
 */
public class StringsPanelTest
{
	@Test
	public void movedPanelTextIsUnchanged()
	{
		assertEquals("Click a task to track it, right-click to star it, "
			+ "click a heading to fold it.",
			Strings.t("panel.overlayHint"));
		assertEquals("Every task you've completed, newest first, on chunkblazer.com",
			Strings.t("panel.historyTip"));
		assertEquals("Reveal your account sync key to move this account to another computer",
			Strings.t("panel.showKeyTip"));
		assertEquals("Wipe this account's local ChunkBlazer data and restore it fresh from the server. "
			+ "Your server progress is not touched.",
			Strings.t("panel.resetTip"));
		assertEquals("Type this code in public chat and hit Enter to verify your ChunkBlazer account:",
			Strings.t("panel.verifyBody"));
		assertEquals("Track your account progress at chunkblazer.com",
			Strings.t("panel.siteTip"));
		assertEquals("No sync key yet. Turn on Server Sync and log in once, and your key is created automatically.",
			Strings.t("panel.noSyncKey"));
		assertEquals("This will reveal your account's Sync Key on screen. Anyone who can see your "
			+ "screen, including a stream or screen share, will be able to read it. "
			+ "Are you sure you want to show it?",
			Strings.t("panel.revealConfirm"));
		assertEquals("<html><body style='width:260px'>Select the key below and copy it "
			+ "(Ctrl+C), then save it somewhere safe like a password manager. To sync this account "
			+ "on another computer, "
			+ "paste it into the \"Sync recovery key\" setting there.<br><br><b>Anyone with this key "
			+ "can access your account. Do not share it.</b></body></html>",
			Strings.t("panel.keyBackupHtml"));
		assertEquals("<html><body style='width:270px'>This clears this account's ChunkBlazer data on THIS "
			+ "computer (mode, tasks, points, chunks, and the stored sync key) and restores it "
			+ "fresh from the server the next time you log in. Your server progress is not "
			+ "touched.<br><br>Use this only if this account is showing the wrong mode or another "
			+ "account's progress. After it finishes, restart RuneLite and log back in.<br><br>"
			+ "Continue?</body></html>",
			Strings.t("panel.resetConfirmHtml"));
		assertEquals("Local data cleared. Restart RuneLite and log back in to restore this account from the "
			+ "server. If sync does not come back on its own, paste this account's key into the "
			+ "\"Sync recovery key\" setting.",
			Strings.t("panel.resetDone"));
		assertEquals("Turn on sync to save your progress across devices, show on the leaderboard, "
			+ "see other players, and play Competitive mode. Nothing is sent until you enable it. "
			+ "Your current progress will sync with the server when you do.",
			Strings.t("panel.syncBody"));
		assertEquals("Competitive mode requires a new Ironman, Hardcore Ironman, or Ultimate Ironman account with combat level 9 or lower and no skill above level 3.",
			Strings.t("panel.competitiveReq"));
		assertEquals("Your starting tasks are dealt once you choose Enable Sync or Play offline.",
			Strings.t("panel.syncChoiceHint"));
		assertEquals("Sync your progress to chunkblazer.com",
			Strings.t("panel.enableTip"));
		assertEquals("Keep progress on this computer only. You can enable sync later.",
			Strings.t("panel.offlineTip"));
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
			+ "Track your account progress at chunkblazer.com.",
			Strings.t("panel.dataUse"));
		assertEquals("How ChunkBlazer uses your data",
			Strings.t("panel.dataUseTitle"));
		assertEquals("<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>"
			+ "Verify your account, then choose your game mode. Type the code from the verify "
			+ "banner in public chat first."
			+ "</td></tr></table></html>",
			Strings.t("panel.verifyFirstHtml"));
		assertEquals("<html><i>This choice is permanent for this account!</i></html>",
			Strings.t("panel.permanentHtml"));
		assertEquals("Start anywhere. Play on any account. Featured on the casual leaderboard.",
			Strings.t("panel.casualTip"));
		assertEquals("<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>"
			+ "Start anywhere. Play on any account. Featured on the casual leaderboard."
			+ "</td></tr></table></html>",
			Strings.t("panel.casualHtml"));
		assertEquals("Featured on the main page of the leaderboard and website. You must start on a new Ironman, Hardcore Ironman or Ultimate Ironman account (combat level 9 or lower).",
			Strings.t("panel.competitiveTip"));
		assertEquals("<html><table width='190' cellpadding='0' cellspacing='0'><tr><td>"
			+ "Featured on the main page of the leaderboard and website. You must start on a new Ironman, Hardcore Ironman or Ultimate Ironman account (combat level 9 or lower)."
			+ "</td></tr></table></html>",
			Strings.t("panel.competitiveHtml"));
		assertEquals("<html>Log into Old School RuneScape to start playing ChunkBlazer.</html>",
			Strings.t("panel.loggedOutHtml"));
		assertEquals("Are you sure you want to select " + "Casual" + " mode?\n\n" +
			"This choice is PERMANENT for this account!",
			Strings.t("panel.confirmMode", "Casual"));
		assertEquals("Competitive mode needs a connection to the ChunkBlazer server to verify\n"
			+ "your RuneScape account.\n\n"
			+ "Do you want to enable Server Sync?",
			Strings.t("panel.competitiveNeedsSync"));
	}
}
