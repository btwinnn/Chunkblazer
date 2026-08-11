package com.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.ImageUtil;

/**
 * Renders two ChunkBlazer status orbs in the empty space beneath the minimap
 * orb column: a boss-token orb (secondary currency) and a chunks-unlocked orb,
 * each with its live count drawn on the orb. The art is OSRS-orb styled
 * (holder + orb). Anchored to the minimap draw-area widget so it tracks the
 * fixed / resizable layouts; the OFFSET_* constants nudge it within the band.
 */
public class ChunkBlazerOrbOverlay extends Overlay
{
	// The orb cluster is centred horizontally under the minimap draw-area and
	// dropped just below it, into the empty band. Tweak to nudge: a positive
	// CENTER_NUDGE shifts right (negative shifts left), a larger OFFSET_Y drops
	// it further down.
	private static final int CENTER_NUDGE = 0;
	private static final int OFFSET_Y = 6;
	private static final int ORB_GAP = 2;
	// Nudge the count within each holder socket: + right / + down.
	private static final int TEXT_DX = 3;
	private static final int TEXT_DY = 5;

	private final Client client;
	private final ChunkBlazerPlugin plugin;

	private final BufferedImage bossTokenOrb;
	private final BufferedImage chunksOrb;

	@Inject
	public ChunkBlazerOrbOverlay(Client client, ChunkBlazerPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);

		bossTokenOrb = ImageUtil.loadImageResource(ChunkBlazerPlugin.class, "boss_token_orb.png");
		chunksOrb = ImageUtil.loadImageResource(ChunkBlazerPlugin.class, "chunks_orb.png");
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (bossTokenOrb == null || chunksOrb == null)
		{
			return null;
		}

		Rectangle minimap = minimapBounds();
		if (minimap == null)
		{
			return null;
		}

		// Centred horizontally under the minimap, dropped into the empty band
		// below it; the two orbs sit side by side.
		int totalWidth = bossTokenOrb.getWidth() + ORB_GAP + chunksOrb.getWidth();
		int x = minimap.x + (minimap.width - totalWidth) / 2 + CENTER_NUDGE;
		int y = minimap.y + minimap.height + OFFSET_Y;

		drawOrb(graphics, bossTokenOrb, x, y, String.valueOf(plugin.getBossTokens()));
		drawOrb(graphics, chunksOrb, x + bossTokenOrb.getWidth() + ORB_GAP, y,
			String.valueOf(plugin.getUnlockedRegionIds().size()));

		return null;
	}

	/** Draw an orb image and centre its count in the empty holder socket (left). */
	private void drawOrb(Graphics2D graphics, BufferedImage orb, int x, int y, String count)
	{
		graphics.drawImage(orb, x, y, null);

		// The count goes in the empty holder socket on the LEFT, not on the
		// coloured orb. The round orb occupies the rightmost ~height pixels, so
		// the holder spans [0, width - height]; centre the text within it.
		int cx = x + (orb.getWidth() - orb.getHeight()) / 2 + TEXT_DX;
		int cy = y + orb.getHeight() / 2 + TEXT_DY;

		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = graphics.getFontMetrics();
		int tx = cx - fm.stringWidth(count) / 2;
		int ty = cy + fm.getAscent() / 2 - 1;

		graphics.setColor(Color.BLACK);
		graphics.drawString(count, tx + 1, ty + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(count, tx, ty);
	}

	private Rectangle minimapBounds()
	{
		Widget w = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA);
		if (w == null || w.isHidden())
		{
			w = client.getWidget(ComponentID.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
		}
		if (w == null || w.isHidden())
		{
			w = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_MINIMAP_DRAW_AREA);
		}
		if (w == null || w.isHidden())
		{
			return null;
		}
		return w.getBounds();
	}
}
