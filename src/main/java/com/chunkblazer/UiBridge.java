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

import com.chunkblazer.modules.NPCKillModule;
import com.chunkblazer.modules.RaidChallengeModule;
import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * ChunkBlazer's end of the link to the ChunkBlazer UI plugin. The two are separate plugins
 * (separate classloaders), so they only talk through RuneLite's {@link PluginMessage}s, and
 * only in plain Java and RuneLite types; a task goes across as its JSON.
 *
 * <p>The UI asks things with a "call" message (method name and arguments), answered in the
 * same message before the UI's post returns. ChunkBlazer tells the UI what to show through
 * {@link RemoteUi}. {@link UiSwitch} hands the UI over when the UI plugin says hello.
 */
@Slf4j
@Singleton
public class UiBridge
{
	/** Messages from ChunkBlazer UI. */
	static final String TO_CORE = "chunkblazer.core";
	/** Messages to ChunkBlazer UI. */
	static final String TO_UI = "chunkblazer.ui";

	@Inject
	private ChunkBlazerPlugin plugin;
	@Inject
	private ChunkBlazerConfig config;
	@Inject
	private EventBus eventBus;
	@Inject
	private ChunkBlazerWorldMapOverlay worldMap;
	@Inject
	private ChunkWallOverlay walls;
	@Inject
	private TaskSidePanel sidePanel;
	@Inject
	private TaskLists lists;
	@Inject
	private NPCKillModule killModule;
	@Inject
	private RaidChallengeModule challengeModule;

	private Gson gson;

	@Inject
	void useGson(Gson gson)
	{
		// Runtime fields (progress, rolled amounts) are transient, and they go across too.
		this.gson = gson.newBuilder().excludeFieldsWithModifiers(Modifier.STATIC).create();
	}

	/** Tell ChunkBlazer UI something; returns the message, with any answer in it. */
	Map<String, Object> post(String name, Map<String, Object> data)
	{
		Map<String, Object> message = data == null ? new HashMap<>() : data;
		eventBus.post(new PluginMessage(TO_UI, name, message));
		return message;
	}

	/** Whether ChunkBlazer UI is running to answer. */
	boolean isUiThere()
	{
		return Boolean.TRUE.equals(post("ping", null).get("r"));
	}

	String toJson(NuzlockeTask task)
	{
		if (task == null)
		{
			return null;
		}
		String id = task.getTaskId();
		int fingerprint = fingerprint(task);
		synchronized (json)
		{
			Sent sent = id == null ? null : json.get(id);
			if (sent != null && sent.task == task && sent.fingerprint == fingerprint)
			{
				return sent.json;
			}
			String text = gson.toJson(task);
			if (id != null)
			{
				json.put(id, new Sent(task, fingerprint, text));
			}
			return text;
		}
	}

	/**
	 * Each task's JSON as last sent, while it's the same task object with the same progress:
	 * the UI asks for the same lists every tick, and the same String each time is cheap at
	 * both ends (no writing it again here, and its hash is already worked out there).
	 */
	private final Map<String, Sent> json = new HashMap<>();

	private static final class Sent
	{
		final NuzlockeTask task;
		final int fingerprint;
		final String json;

		Sent(NuzlockeTask task, int fingerprint, String json)
		{
			this.task = task;
			this.fingerprint = fingerprint;
			this.json = json;
		}
	}

	/** The parts of a task that change as it's played (the rest is the catalog's). */
	private static int fingerprint(NuzlockeTask task)
	{
		int h = Objects.hash(task.getCurrentProgress(), task.getTargetQuantity(), task.isCompleted());
		if (task.getRequiredItems() != null)
		{
			for (RequiredItem item : task.getRequiredItems())
			{
				h = h * 31 + Objects.hashCode(item == null ? null : item.getRolledQuantity());
			}
		}
		if (task.getRequiredObjects() != null)
		{
			for (RequiredObject object : task.getRequiredObjects())
			{
				h = h * 31 + Objects.hashCode(object == null ? null : object.getRolledQuantity());
			}
		}
		if (task.getTargetNpc() != null)
		{
			h = h * 31 + Objects.hashCode(task.getTargetNpc().getRolledQuantity());
		}
		return h;
	}

	NuzlockeTask fromJson(Object json)
	{
		return json instanceof String ? gson.fromJson((String) json, NuzlockeTask.class) : null;
	}

	private List<String> toJson(Collection<NuzlockeTask> tasks)
	{
		List<String> out = new ArrayList<>(tasks.size());
		for (NuzlockeTask task : tasks)
		{
			out.add(toJson(task));
		}
		return out;
	}

	/** A "call" from ChunkBlazer UI: answer it (into "r"). */
	void answer(Map<String, Object> data)
	{
		Object method = data.get("m");
		Object rawArgs = data.get("a");
		List<?> args = rawArgs instanceof List ? (List<?>) rawArgs : new ArrayList<>();
		try
		{
			data.put("r", call(String.valueOf(method), args));
		}
		catch (RuntimeException e)
		{
			log.debug("ChunkBlazer UI's {} failed", method, e);
		}
	}

	private static int i(List<?> a, int n)
	{
		return ((Number) a.get(n)).intValue();
	}

	private static String s(List<?> a, int n)
	{
		Object o = a.get(n);
		return o == null ? null : o.toString();
	}

	private NuzlockeTask task(List<?> a, int n)
	{
		String id = s(a, n);
		return id == null ? null : plugin.getTaskById(id);
	}

	private Object call(String method, List<?> a)
	{
		switch (method)
		{
			case "ping":
				return true;
			case "stamp":
				return stamp();

			// Questions
			case "canRollMissing":
				return plugin.canRollMissing();
			case "findRegionForTask":
				return plugin.findRegionForTask(s(a, 0));
			case "getActiveTasks":
				return toJson(plugin.getActiveTasks());
			case "getVisibleGlobalTasks":
				return toJson(plugin.getVisibleGlobalTasks());
			case "getBossOrbBounds":
				return plugin.getBossOrbBounds();
			case "getCompletedTaskIdSet":
				return new HashSet<>(plugin.getCompletedTaskIdSet());
			case "getCurrentRegionId":
				return plugin.getCurrentRegionId();
			case "getLastUnlockChangeMs":
				return plugin.getLastUnlockChangeMs();
			case "getNeighborRegionIds":
				return new HashSet<>(plugin.getNeighborRegionIds());
			case "getUnlockedRegionIds":
				return new LinkedHashSet<>(plugin.getUnlockedRegionIds());
			case "getPendingVerificationCode":
				return plugin.getPendingVerificationCode();
			case "getPracticeTarget":
				return plugin.getPracticeTarget(s(a, 0));
			case "getRegionName":
				return plugin.getRegionName(i(a, 0));
			case "getSelectedTask":
				return toJson(plugin.getSelectedTask());
			case "getTaskById":
				return toJson(plugin.getTaskById(s(a, 0)));
			case "getTasksOrbCentre":
				return plugin.getTasksOrbCentre();
			case "getTotalPoints":
				return plugin.getTotalPoints();
			case "getUnrevealedTaskIds":
				return new ArrayList<>(plugin.getUnrevealedTaskIds());
			case "getUnrevealedTasksRaw":
				return plugin.getUnrevealedTasksRaw();
			case "isFreeRegion":
				return plugin.isFreeRegion(i(a, 0));
			case "isModeLocked":
				return plugin.isModeLocked();
			case "isOnTutorialIsland":
				return plugin.isOnTutorialIsland();
			case "isPlayOffline":
				return plugin.isPlayOffline();
			case "isRegionUnlocked":
				return plugin.isRegionUnlocked(i(a, 0));
			case "isServerSyncEnabled":
				return plugin.isServerSyncEnabled();
			case "isTaskCardsOn":
				return plugin.isTaskCardsOn();
			case "countPayableUnlockedChunks":
				return plugin.countPayableUnlockedChunks();
			case "getRegionUnlockCost":
				return plugin.getRegionUnlockCost(i(a, 0));
			case "curveUnlockCost":
				return ChunkBlazerPlugin.curveUnlockCost(i(a, 0));
			case "isBossRegion":
				return plugin.isBossRegion(i(a, 0));
			case "isBossTask":
				return plugin.isBossTask(task(a, 0));
			case "isCharterRegion":
				return plugin.isCharterRegion(i(a, 0));
			case "isFreeUnlockableRegion":
				return plugin.isFreeUnlockableRegion(i(a, 0));
			case "isKnownRegion":
				return plugin.isKnownRegion(i(a, 0));
			case "isGlobalTask":
				return plugin.isGlobalTask(s(a, 0));
			case "getTaskArea":
			{
				NuzlockeTask task = task(a, 0);
				return task == null ? null : plugin.getTaskArea(task);
			}
			case "getTaskRegionName":
			{
				NuzlockeTask task = task(a, 0);
				return task == null ? null : plugin.getTaskRegionName(task);
			}
			case "meetsLevelRequirement":
			{
				NuzlockeTask task = task(a, 0);
				return task != null && plugin.meetsLevelRequirement(task);
			}
			case "getActiveFightStartTick":
			{
				NuzlockeTask task = task(a, 0);
				return task == null ? -1 : killModule.getActiveFightStartTick(task.getTargetNpc());
			}
			case "challengeStatus":
			{
				NuzlockeTask task = task(a, 0);
				return task == null ? null : challengeModule.attemptStatus(task.getTaskId());
			}
			case "targetExtrasJson":
				return plugin.readTaskFileContent("_Target_Extras.json");
			case "setting":
				return setting(s(a, 0));
			case "chunkFill":
				return ChunkBlazerWorldMapOverlay.fillFor(config, ChunkUnlockType.valueOf(s(a, 0)));

			// The world map
			case "getHoveredRegionId":
				return worldMap.getHoveredRegionId();
			case "focusRegion":
				worldMap.focusRegion(i(a, 0));
				return null;
			case "drawMapTooltip":
				worldMap.drawTooltip((Graphics2D) a.get(0));
				return null;

			// Things the UI does
			case "cancelPendingCompetitive":
				plugin.cancelPendingCompetitive();
				return null;
			case "clearSelectedTask":
				plugin.clearSelectedTask();
				return null;
			case "selectTaskFromGame":
			{
				NuzlockeTask task = task(a, 0);
				plugin.selectTaskFromGame(task != null ? task : fromJson(a.get(1)));
				return null;
			}
			case "playTaskCompleteSound":
				plugin.playTaskCompleteSound();
				return null;
			case "leftTutorialIsland":
				plugin.leftTutorialIsland();
				return null;
			case "lockCompetitive":
				plugin.lockCompetitive();
				return null;
			case "lockGameMode":
				plugin.lockGameMode(GameMode.valueOf(s(a, 0)));
				return null;
			case "refreshPanel":
				plugin.refreshPanel();
				return null;
			case "revealTaskCard":
				plugin.revealTaskCard(s(a, 0));
				return null;
			case "showChunkView":
				plugin.showChunkView(Boolean.TRUE.equals(a.get(0)));
				return null;
			case "showPracticeComplete":
			{
				NuzlockeTask task = fromJson(a.get(0));
				if (task != null)
				{
					plugin.showPracticeComplete(task);
				}
				return null;
			}
			case "choosePlayOffline":
				plugin.choosePlayOffline();
				return null;
			case "enableServerSync":
				plugin.enableServerSync();
				return null;
			case "applyPanelTheme":
				applyPanelTheme(a.get(0));
				return null;
			case "setSidePanelSearch":
				sidePanel.setSearch(s(a, 0) == null ? "" : s(a, 0));
				return null;
			case "sidePanelShowAll":
				sidePanel.showAll();
				return null;
			case "sidePanelChanged":
				lists.loadFilters();
				sidePanel.sideChanged();
				return null;
			case "setFence":
				walls.setFence((int[]) a.get(0));
				return null;
			case "dropFence":
				walls.dropFence(((Number) a.get(0)).doubleValue(), ((Number) a.get(1)).doubleValue());
				return null;
			default:
				log.debug("ChunkBlazer UI asked for {}, which isn't here", method);
				return null;
		}
	}

	/** ChunkBlazer's settings the UI goes by. */
	private Object setting(String key)
	{
		switch (key)
		{
			case "showTaskCompletionPopup":
				return config.showTaskCompletionPopup();
			case "showWorldMapChunks":
				return config.showWorldMapChunks();
			case "worldMapTasksKey":
				return config.worldMapTasksKey();
			default:
				return null;
		}
	}

	/** The side panel takes the UI's theme (its palette, field by field), or its own look. */
	void applyPanelTheme(Object palette)
	{
		Skin.setPalette(palette instanceof Map ? ThemePalette.fromMap((Map<?, ?>) palette) : ThemePalette.original());
		ChunkBlazerPanel panel = plugin.getPanel();
		if (panel != null)
		{
			panel.applyTheme();
		}
	}

	/**
	 * Changes whenever something the UI shows may have: it checks this once a client tick and
	 * asks again for everything when it moves (it also asks again every game tick).
	 */
	private Object stamp()
	{
		NuzlockeTask selected = plugin.getSelectedTask();
		int progress = 0;
		List<NuzlockeTask> active = plugin.getActiveTasks();
		for (NuzlockeTask task : active)
		{
			progress = progress * 31 + task.getCurrentProgress() + (task.isCompleted() ? 1 : 0);
		}
		return Objects.hash(plugin.getLastUnlockChangeMs(), selected == null ? null : selected.getTaskId(),
			selected == null ? 0 : selected.getCurrentProgress(), plugin.getTotalPoints(), active.size(), progress,
			plugin.getCompletedTasksRaw(), plugin.getUnrevealedTasksRaw(), plugin.isModeLocked(),
			plugin.getCurrentRegionId(), plugin.isServerSyncEnabled(), plugin.isPlayOffline(), plugin.canRollMissing(),
			plugin.getPendingVerificationCode(), plugin.isOnTutorialIsland());
	}
}
