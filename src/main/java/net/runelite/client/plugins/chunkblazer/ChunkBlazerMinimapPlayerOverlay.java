package net.runelite.client.plugins.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Draws a coloured dot on the minimap for each nearby ChunkBlazer player, so
 * plugin users can spot one another at a glance. Gated by
 * {@code showMinimapHighlight}; the dot colour comes from {@code recognitionColor}.
 */
public class ChunkBlazerMinimapPlayerOverlay extends Overlay
{
	private static final int DOT_SIZE = 6;

	private final Client client;
	private final ChunkBlazerConfig config;
	private final ChunkBlazerRoster roster;

	@Inject
	public ChunkBlazerMinimapPlayerOverlay(Client client, ChunkBlazerConfig config, ChunkBlazerRoster roster)
	{
		this.client = client;
		this.config = config;
		this.roster = roster;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showMinimapHighlight() || roster.isEmpty())
		{
			return null;
		}

		Player local = client.getLocalPlayer();
		Color color = config.recognitionColor();
		int r = DOT_SIZE / 2;

		for (Player player : client.getPlayers())
		{
			if (player == null || player == local)
			{
				continue;
			}
			String name = player.getName();
			if (name == null || !roster.isMember(name))
			{
				continue;
			}
			Point mm = player.getMinimapLocation();
			if (mm == null)
			{
				continue;
			}
			graphics.setColor(color);
			graphics.fillOval(mm.getX() - r, mm.getY() - r, DOT_SIZE, DOT_SIZE);
			graphics.setColor(Color.BLACK);
			graphics.drawOval(mm.getX() - r, mm.getY() - r, DOT_SIZE, DOT_SIZE);
		}

		return null;
	}
}
