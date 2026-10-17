package com.chunkblazer.debug;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * Outlines every NPC and object a catalog task points at.
 *  Green: a task lists its id.  Cyan: only matched by a name/option rule (no ids).
 *  Red: a task names it but lists none of its ids, or (ChunkBlazer running) an active task is
 *  missing from its Tasks menu. Thicker when it has an active task.
 * NPC labels: "ok/total tasks", plus the active count and ID GAP. Objects are only labelled when
 * red or close by, to keep the screen readable.
 */
class TaskDebugOverlay extends Overlay
{
	private static final Color OK = new Color(60, 220, 60);
	private static final Color RULE = new Color(60, 210, 240);
	private static final Color BAD = new Color(240, 40, 40);
	private static final int OBJECT_RANGE = 20;
	private static final int OBJECT_LABEL_RANGE = 4;

	private final Client client;
	private final TaskDebugPlugin plugin;
	private final ModelOutlineRenderer outlines;

	@Inject
	TaskDebugOverlay(Client client, TaskDebugPlugin plugin, ModelOutlineRenderer outlines)
	{
		this.client = client;
		this.plugin = plugin;
		this.outlines = outlines;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		String mode = plugin.outlineMode;
		if (mode.equals("off"))
		{
			return null;
		}
		if (!mode.equals("objects"))
		{
			for (NPC npc : client.getTopLevelWorldView().npcs())
			{
				if (npc == null || npc.getName() == null)
				{
					continue;
				}
				TaskDebugPlugin.Check c = plugin.check(npc);
				if (c.empty())
				{
					continue;
				}
				Color color = color(c);
				outlines.drawOutline(npc, c.expected.isEmpty() ? 2 : 4, color, 2);
				Point at = npc.getCanvasTextLocation(graphics, label(c), npc.getLogicalHeight() + 40);
				if (at != null)
				{
					OverlayUtil.renderTextLocation(graphics, at, label(c), color);
				}
			}
		}
		if (!mode.equals("npcs"))
		{
			Player me = client.getLocalPlayer();
			WorldPoint here = me == null ? null : me.getWorldLocation();
			for (TileObject o : new ArrayList<>(plugin.taskObjects))
			{
				WorldPoint wp = o.getWorldLocation();
				if (here == null || wp == null || wp.getPlane() != here.getPlane() || wp.distanceTo2D(here) > OBJECT_RANGE)
				{
					continue;
				}
				TaskDebugPlugin.Check c = plugin.check(plugin.target(o));
				if (c.empty())
				{
					continue;
				}
				Color color = color(c);
				outlines.drawOutline(o, c.expected.isEmpty() ? 2 : 4, color, 2);
				if (c.wrong() || wp.distanceTo2D(here) <= OBJECT_LABEL_RANGE)
				{
					Point at = o.getCanvasTextLocation(graphics, label(c), 60);
					if (at != null)
					{
						OverlayUtil.renderTextLocation(graphics, at, label(c), color);
					}
				}
			}
		}
		return null;
	}

	private static Color color(TaskDebugPlugin.Check c)
	{
		return c.wrong() ? BAD : c.rulesOnly() ? RULE : OK;
	}

	private static String label(TaskDebugPlugin.Check c)
	{
		long gaps = c.matches.stream().filter(m -> m.gap).count();
		return (c.matches.size() - gaps) + "/" + c.matches.size() + " tasks"
			+ (c.expected.isEmpty() ? "" : "  active " + (c.expected.size() - c.missing.size()) + "/" + c.expected.size())
			+ (gaps == 0 ? "" : "  ID GAP")
			+ (c.extra.isEmpty() ? "" : "  +" + c.extra.size() + " unlinked");
	}
}
