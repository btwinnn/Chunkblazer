package com.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.ImageUtil;

/**
 * Bottom-left HUD widget (above the chatbox) showing the Boss Token currency:
 * the boss_token.png icon with the current balance drawn to its lower-right,
 * outside the icon. Reads the balance live each frame. Toggle off via
 * {@link ChunkBlazerConfig#showBossTokenCounter()}.
 */
public class ChunkBlazerBossTokenOverlay extends Overlay
{
	private static final int ICON_SIZE = 32;
	private static final int GAP = 3; // space between the icon and the count
	private static final Color TOKEN_GOLD = new Color(255, 215, 0);

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;

	private final BufferedImage icon;

	@Inject
	public ChunkBlazerBossTokenOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.BOTTOM_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.LOW);

		icon = loadScaled("boss_token.png", ICON_SIZE);
	}

	/**
	 * Load a bundled image and scale it to fit {@code maxSize} on its longest
	 * side, preserving aspect ratio (uniform — no squishing) with smooth
	 * interpolation. boss_token.png is 122x100, so forcing it into a square
	 * skewed it; this keeps it natural.
	 */
	private static BufferedImage loadScaled(String resource, int maxSize)
	{
		BufferedImage src = ImageUtil.loadImageResource(ChunkBlazerPlugin.class, resource);
		if (src == null)
		{
			return null;
		}
		int w = src.getWidth();
		int h = src.getHeight();
		double scale = (double) maxSize / Math.max(w, h);
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(src, 0, 0, nw, nh, null);
		g.dispose();
		return out;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (icon == null || !config.showBossTokenCounter() || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		String count = String.valueOf(plugin.getBossTokens());

		graphics.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics fm = graphics.getFontMetrics();
		int textWidth = fm.stringWidth(count);

		// Icon top-left; the count sits at the icon's lower-right, just outside it
		// (bottom-aligned with the icon), with a 1px shadow for readability.
		int iconW = icon.getWidth();
		int iconH = icon.getHeight();
		graphics.drawImage(icon, 0, 0, null);

		int textX = iconW + GAP;
		int textBaseline = iconH;
		graphics.setColor(Color.BLACK);
		graphics.drawString(count, textX + 1, textBaseline + 1);
		graphics.setColor(TOKEN_GOLD);
		graphics.drawString(count, textX, textBaseline);

		return new Dimension(iconW + GAP + textWidth, iconH);
	}
}
