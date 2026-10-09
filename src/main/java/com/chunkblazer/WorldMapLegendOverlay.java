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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The chunk colour key in the bottom-left corner of the world map. Its own overlay on
 * the always-on-top layer, so map icons and other overlays never cover it. Uses the
 * same colours as the map (ChunkBlazerWorldMapOverlay.fillFor). Registered by
 * TaskBrowserOverlay.
 */
@Singleton
public class WorldMapLegendOverlay extends Overlay
{
	private static final Color SWATCH_BORDER = new Color(255, 255, 255, 70);
	private static final ChunkUnlockType[] ROWS = {
		ChunkUnlockType.UNLOCKED, ChunkUnlockType.PAID, ChunkUnlockType.FREE,
		ChunkUnlockType.CHARTER, ChunkUnlockType.BOSS, ChunkUnlockType.LOCKED
	};

	private final Client client;
	private final ChunkBlazerConfig config;

	@Inject
	public WorldMapLegendOverlay(Client client, ChunkBlazerConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showWorldMapChunks() || !config.showChunkLegend())
		{
			return null;
		}
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null || map.isHidden())
		{
			return null;
		}
		Rectangle worldMapRect = map.getBounds();
		if (worldMapRect == null || worldMapRect.width <= 0)
		{
			return null;
		}

		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = graphics.getFontMetrics();

		int padding = 6;
		int swatch = 10;
		int lineHeight = Math.max(fm.getHeight(), swatch + 4);
		int textWidth = 0;
		for (ChunkUnlockType row : ROWS)
		{
			textWidth = Math.max(textWidth, fm.stringWidth(row.legend));
		}
		int width = padding * 3 + swatch + textWidth;
		int height = padding * 2 + lineHeight * ROWS.length;
		int x = (int) worldMapRect.getX() + 8;
		int y = (int) (worldMapRect.getY() + worldMapRect.getHeight()) - height - 8;

		Draw.box(graphics, x, y, width, height, new Color(30, 30, 30, 220), new Color(90, 90, 90));

		int rowY = y + padding;
		for (ChunkUnlockType row : ROWS)
		{
			int sx = x + padding;
			int sy = rowY + (lineHeight - swatch) / 2;
			// The swatch is drawn over a map-like backing so it matches the tint
			// players see on the chunks.
			graphics.setColor(new Color(110, 110, 90));
			graphics.fillRect(sx, sy, swatch, swatch);
			graphics.setColor(ChunkBlazerWorldMapOverlay.fillFor(config, row));
			graphics.fillRect(sx, sy, swatch, swatch);
			graphics.setColor(SWATCH_BORDER);
			graphics.drawRect(sx, sy, swatch, swatch);

			graphics.setColor(Color.WHITE);
			graphics.drawString(row.legend, sx + swatch + padding, Draw.textY(fm, rowY, lineHeight));
			rowY += lineHeight;
		}
		return null;
	}
}
