/*
 * Copyright (c) 2026, Vani-Lab
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer;

import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * ChunkBlazer without ChunkBlazer UI (its plugin off, or not installed): task cards drawn
 * still in the original art (SimpleCardOverlay), the original completion popup, no task
 * window (the Points orb offers ChunkBlazer UI instead), no quest/saved box, task box,
 * outlines or themes, and instead of the tutorial a chatbox prompt for a mode on arriving in
 * Lumbridge (CoreModePrompt). The side panel is ChunkBlazer's own, so it's there either way.
 */
@Singleton
public class CoreOnlyUi implements ChunkBlazerUi
{
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private SimpleCardOverlay cards;
	@Inject
	private TaskCompletionAnimationOverlay completion;
	@Inject
	private TaskLists lists;
	@Inject
	private ChunkBlazerPlugin plugin;
	@Inject
	private EventBus eventBus;
	@Inject
	private CoreModePrompt modePrompt;
	@Inject
	private CompanionPrompt companions;
	@Inject
	private net.runelite.api.Client client;

	// Without ChunkBlazer UI there's no tutorial, but its quiet still holds the sync and mode
	// notices: on Tutorial Island, in Lumbridge (CoreModePrompt asks there) and while the add-ons box is up.
	private final TutorialHooks quiet = new TutorialHooks()
	{
		@Override
		public boolean isQuiet()
		{
			return companions.isShowing() || modePrompt.handlesHere()
				|| client.getVarpValue(net.runelite.api.gameval.VarPlayerID.TUTORIAL) < 1000;
		}
	};

	@Override
	public void startUp()
	{
		// ChunkBlazer's own look, the side panel included (also when ChunkBlazer UI is switched off).
		SidePaint.painter = null;
		plugin.uiCompletionSound = false;
		Skin.setPalette(ThemePalette.original());
		ChunkBlazerPanel panel = plugin.getPanel();
		if (panel != null)
		{
			panel.applyTheme();
		}
		// No tutorial: arriving in Lumbridge without a mode asks for one in the chatbox.
		eventBus.register(modePrompt);
	}

	@Override
	public void shutDown()
	{
		eventBus.unregister(modePrompt);
	}

	@Override
	public void showGameUi(boolean show)
	{
		if (show)
		{
			overlayManager.add(cards);
			overlayManager.add(completion);
		}
		else
		{
			overlayManager.remove(cards);
			overlayManager.remove(completion);
			cards.clear();
		}
	}

	@Override
	public void startTaskUi()
	{
	}

	@Override
	public void stopTaskUi()
	{
	}

	/** No task window: the add-ons box, to turn on or install ChunkBlazer UI. */
	@Override
	public void toggleWindow()
	{
		companions.open();
	}

	@Override
	public void setTaskButtonBounds(Rectangle bounds)
	{
	}

	/** No quest box to open: a click tracks the quest (or stops tracking it), like any other task. */
	@Override
	public void openTask(NuzlockeTask task)
	{
		if (lists.isTracked(task))
		{
			plugin.clearSelectedTask();
		}
		else
		{
			plugin.selectTaskFromGame(task);
		}
	}

	@Override
	public TaskCardShow cards()
	{
		return cards;
	}

	@Override
	public TutorialHooks tutorial()
	{
		return quiet;
	}

	@Override
	public void showTaskCompletion(NuzlockeTask task, int points, String region)
	{
		completion.showTaskCompletion(task, points, region);
	}
}
