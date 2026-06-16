package net.runelite.client.plugins.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ImageUtil;

/**
 * Draws ChunkBlazer recognition decorations on other plugin users who are in
 * the scene: an overhead info tag (account type, game mode, leaderboard rank,
 * points) and a glowing model outline. Each surface is independently togglable
 * via config; the recognized-player set comes from {@link ChunkBlazerRoster}.
 */
public class ChunkBlazerPlayerOverlay extends Overlay
{
	private static final int ICON_SIZE = 16;
	private static final int VERTICAL_OFFSET = 40; // pixels above the player's head
	private static final Color NUZLOCKE_COLOR = new Color(255, 100, 100);
	private static final Color CASUAL_COLOR = new Color(120, 220, 120);
	private static final Color DEV_COLOR = new Color(255, 157, 60); // flame orange — matches the site's Dev badge
	private static final Color TEXT_BACKGROUND = new Color(0, 0, 0, 150);
	private static final int OUTLINE_WIDTH = 2;
	private static final int OUTLINE_FEATHER = 4;
	private static final String SEP = " · "; // middle dot

	private final Client client;
	private final ChunkBlazerConfig config;
	private final ChunkBlazerRoster roster;
	private final ModelOutlineRenderer modelOutlineRenderer;

	private BufferedImage chunkBlazerIcon;

	@Inject
	public ChunkBlazerPlayerOverlay(Client client, ChunkBlazerConfig config, ChunkBlazerRoster roster,
		ModelOutlineRenderer modelOutlineRenderer)
	{
		this.client = client;
		this.config = config;
		this.roster = roster;
		this.modelOutlineRenderer = modelOutlineRenderer;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);

		loadIcon();
	}

	private void loadIcon()
	{
		BufferedImage loaded = ImageUtil.loadImageResource(ChunkBlazerPlugin.class, "icon.png");
		chunkBlazerIcon = loaded != null
			? ImageUtil.resizeImage(loaded, ICON_SIZE, ICON_SIZE)
			: ChunkBlazerIcons.createFireIcon(ICON_SIZE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		boolean wantTag = config.showOtherPlayers();
		boolean wantOutline = config.showPlayerOutline();
		if ((!wantTag && !wantOutline) || roster.isEmpty())
		{
			return null;
		}

		Player local = client.getLocalPlayer();
		for (Player player : client.getPlayers())
		{
			if (player == null || player == local)
			{
				continue;
			}
			String name = player.getName();
			if (name == null)
			{
				continue;
			}
			ChunkBlazerRoster.Entry entry = roster.get(name);
			if (entry == null)
			{
				continue;
			}

			if (wantOutline)
			{
				modelOutlineRenderer.drawOutline(player, OUTLINE_WIDTH, config.recognitionColor(), OUTLINE_FEATHER);
			}
			if (wantTag)
			{
				renderTag(graphics, player, entry);
			}
		}

		return null;
	}

	private void renderTag(Graphics2D graphics, Player player, ChunkBlazerRoster.Entry entry)
	{
		// Top line: a "Dev" tag for dev/tester accounts, then account type, then
		// game mode when one is locked.
		StringBuilder top = new StringBuilder();
		if (entry.isDev())
		{
			top.append("Dev");
		}
		if (entry.getAccountLabel() != null && !entry.getAccountLabel().isEmpty())
		{
			if (top.length() > 0)
			{
				top.append(SEP);
			}
			top.append(entry.getAccountLabel());
		}
		if (entry.getGameMode() != null)
		{
			if (top.length() > 0)
			{
				top.append(SEP);
			}
			top.append(entry.getGameMode().getName());
		}
		String topLine = top.length() > 0 ? top.toString() : "ChunkBlazer";

		// Bottom line: rank + points, each gated by its own toggle.
		List<String> parts = new ArrayList<>(2);
		if (config.showPlayerRank() && entry.getRank() > 0)
		{
			parts.add("#" + entry.getRank());
		}
		if (config.showPlayerPoints())
		{
			parts.add(String.format("%,d pts", entry.getTotalPoints()));
		}
		String bottomLine = String.join(SEP, parts);
		boolean hasBottom = !bottomLine.isEmpty();

		// Empty anchor string => point.x is the player's horizontal centre.
		Point anchor = player.getCanvasTextLocation(graphics, "", VERTICAL_OFFSET);
		if (anchor == null)
		{
			return;
		}

		FontMetrics fm = graphics.getFontMetrics();
		int lineHeight = fm.getHeight();
		int textWidth = fm.stringWidth(topLine);
		if (hasBottom)
		{
			textWidth = Math.max(textWidth, fm.stringWidth(bottomLine));
		}
		int totalWidth = ICON_SIZE + 4 + textWidth;
		int totalHeight = hasBottom ? lineHeight * 2 : lineHeight;

		int x = anchor.getX() - totalWidth / 2;
		int topBaseline = anchor.getY();
		int boxTop = topBaseline - fm.getAscent();

		// Background box.
		graphics.setColor(TEXT_BACKGROUND);
		graphics.fillRoundRect(x - 3, boxTop - 2, totalWidth + 6, totalHeight + 4, 6, 6);

		// Icon, aligned with the first line.
		if (chunkBlazerIcon != null)
		{
			graphics.drawImage(chunkBlazerIcon, x, boxTop, null);
		}

		int textX = x + ICON_SIZE + 4;

		// Top line: orange for dev accounts, otherwise the mode colour.
		graphics.setColor(entry.isDev() ? DEV_COLOR : modeColor(entry.getGameMode()));
		graphics.drawString(topLine, textX, topBaseline);

		// Bottom line in white.
		if (hasBottom)
		{
			graphics.setColor(Color.WHITE);
			graphics.drawString(bottomLine, textX, topBaseline + lineHeight);
		}
	}

	private Color modeColor(GameMode mode)
	{
		if (mode == GameMode.NUZLOCKE)
		{
			return NUZLOCKE_COLOR;
		}
		if (mode == GameMode.CASUAL)
		{
			return CASUAL_COLOR;
		}
		return config.recognitionColor();
	}
}
