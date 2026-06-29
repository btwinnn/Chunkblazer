package net.runelite.client.plugins.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ColorUtil;

@Slf4j
class ChunkBlazerWorldMapOverlay extends Overlay
{
	private static final int REGION_SIZE = 64; // 64 tiles per region
	private static final int REGION_TRUNCATE = ~((1 << 6) - 1);

	// Colors
	private static final Color UNLOCKED_BORDER = new Color(0, 255, 0, 180);
	private static final Color UNLOCKED_FILL = new Color(0, 255, 0, 35); // Owned chunks are filled in
	private static final Color LOCKED_BORDER = new Color(255, 0, 0, 120);
	private static final Color LOCKED_FILL = new Color(0, 0, 0, 100);
	// Yellow outline = "you can unlock this" — the baseline unlock marker for
	// every unlockable chunk (adjacent neighbours AND charter ports). Outline
	// only: unlockable chunks are never filled or highlighted, so owned (filled)
	// vs available (outlined) reads at a glance.
	private static final Color UNLOCKABLE_BORDER = new Color(255, 215, 0, 220);
	private static final Color CURRENT_BORDER = new Color(0, 200, 255, 255);

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final ChatboxPanelManager chatboxPanelManager;
	private final ClientThread clientThread;

	private int hoveredRegionId = -1;
	private boolean isHoveredUnlockable = false;

	@Inject
	private ChunkBlazerWorldMapOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config,
		ChatboxPanelManager chatboxPanelManager, ClientThread clientThread)
	{
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_HIGH);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.chatboxPanelManager = chatboxPanelManager;
		this.clientThread = clientThread;
	}

	/**
	 * @return the region id under the cursor on the world map, or -1 if none.
	 * Read by {@link ChunkBlazerPlugin#onMenuOptionClicked} for keybind+click unlock.
	 */
	int getHoveredRegionId()
	{
		return hoveredRegionId;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Single "Show Chunk Borders" toggle (showSceneChunks) now gates both the
		// scene and world-map border overlays — the old showChunkBorders duplicate
		// was removed.
		if (!config.showSceneChunks())
		{
			return null;
		}

		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null)
		{
			return null;
		}

		WorldMap worldMap = client.getWorldMap();
		if (worldMap == null)
		{
			return null;
		}

		Rectangle worldMapRect = map.getBounds();
		graphics.setClip(worldMapRect);

		float pixelsPerTile = worldMap.getWorldMapZoom();
		int widthInTiles = (int) Math.ceil(worldMapRect.getWidth() / pixelsPerTile);
		int heightInTiles = (int) Math.ceil(worldMapRect.getHeight() / pixelsPerTile);

		Point worldMapPosition = worldMap.getWorldMapPosition();

		// Calculate visible region bounds
		int yTileMin = worldMapPosition.getY() - heightInTiles / 2;
		int xRegionMin = (worldMapPosition.getX() - widthInTiles / 2) & REGION_TRUNCATE;
		int xRegionMax = ((worldMapPosition.getX() + widthInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
		int yRegionMin = (yTileMin & REGION_TRUNCATE);
		int yRegionMax = ((worldMapPosition.getY() + heightInTiles / 2) & REGION_TRUNCATE) + REGION_SIZE;
		int regionPixelSize = (int) Math.ceil(REGION_SIZE * pixelsPerTile);

		Set<String> unlockedRegions = plugin.getUnlockedRegionIds();
		Set<Integer> neighborRegions = plugin.getNeighborRegionIds();
		int currentRegionId = plugin.getCurrentRegionId();

		// Get mouse position for hover detection
		Point mousePos = client.getMouseCanvasPosition();
		hoveredRegionId = -1;
		isHoveredUnlockable = false;

		// First pass: Draw locked chunk overlays (greyscale effect)
		for (int x = xRegionMin; x < xRegionMax; x += REGION_SIZE)
		{
			for (int y = yRegionMin; y < yRegionMax; y += REGION_SIZE)
			{
				int regionId = ((x >> 6) << 8) | (y >> 6);
				// Free dungeon / off-map regions (regionY outside the surface band)
				// are always accessible, so draw them as unlocked, not locked.
				boolean isUnlocked = unlockedRegions.contains(String.valueOf(regionId))
					|| plugin.isFreeRegion(regionId);
				boolean isNeighbor = neighborRegions.contains(regionId);
				boolean isCharter = plugin.isCharterRegion(regionId);

				int yTileOffset = -(yTileMin - y);
				int xTileOffset = x + widthInTiles / 2 - worldMapPosition.getX();

				int xPos = ((int) (xTileOffset * pixelsPerTile)) + (int) worldMapRect.getX();
				int yPos = (worldMapRect.height - (int) (yTileOffset * pixelsPerTile)) + (int) worldMapRect.getY();
				yPos -= regionPixelSize;

				Rectangle regionRect = new Rectangle(xPos, yPos, regionPixelSize, regionPixelSize);

				// Check if mouse is hovering over this region
				if (mousePos != null && worldMapRect.contains(mousePos.getX(), mousePos.getY()))
				{
					if (regionRect.contains(mousePos.getX(), mousePos.getY()))
					{
						hoveredRegionId = regionId;
						isHoveredUnlockable = (isNeighbor || isCharter) && !isUnlocked;
					}
				}

				// Draw fills first. Owned chunks are filled in; unlockable chunks
				// (neighbours + charter ports) get a yellow outline only (border
				// pass) with NO fill; locked chunks get the dark wash.
				if (isUnlocked)
				{
					graphics.setColor(UNLOCKED_FILL);
					graphics.fillRect(xPos, yPos, regionPixelSize, regionPixelSize);
				}
				else if (!isNeighbor && !isCharter)
				{
					graphics.setColor(LOCKED_FILL);
					graphics.fillRect(xPos, yPos, regionPixelSize, regionPixelSize);
				}
			}
		}

		// Second pass: Draw borders and region IDs
		Font regionFont = FontManager.getRunescapeBoldFont().deriveFont(14f);
		graphics.setFont(regionFont);

		for (int x = xRegionMin; x < xRegionMax; x += REGION_SIZE)
		{
			for (int y = yRegionMin; y < yRegionMax; y += REGION_SIZE)
			{
				int regionId = ((x >> 6) << 8) | (y >> 6);
				// Free dungeon / off-map regions (regionY outside the surface band)
				// are always accessible, so draw them as unlocked, not locked.
				boolean isUnlocked = unlockedRegions.contains(String.valueOf(regionId))
					|| plugin.isFreeRegion(regionId);
				boolean isNeighbor = neighborRegions.contains(regionId);
				boolean isCharter = plugin.isCharterRegion(regionId);
				boolean isCurrent = regionId == currentRegionId;

				int yTileOffset = -(yTileMin - y);
				int xTileOffset = x + widthInTiles / 2 - worldMapPosition.getX();

				int xPos = ((int) (xTileOffset * pixelsPerTile)) + (int) worldMapRect.getX();
				int yPos = (worldMapRect.height - (int) (yTileOffset * pixelsPerTile)) + (int) worldMapRect.getY();
				yPos -= regionPixelSize;

				// Choose border color based on state
				if (isCurrent)
				{
					graphics.setColor(CURRENT_BORDER);
				}
				else if (isUnlocked)
				{
					graphics.setColor(UNLOCKED_BORDER);
				}
				else if (isNeighbor || isCharter)
				{
					graphics.setColor(UNLOCKABLE_BORDER);
				}
				else
				{
					graphics.setColor(LOCKED_BORDER);
				}

				// Draw border
				graphics.drawRect(xPos, yPos, regionPixelSize, regionPixelSize);

				// Thicker border for the current region, and a hover emphasis on
				// unlockable chunks (still outline-only — no fill/highlight).
				if (isCurrent)
				{
					graphics.drawRect(xPos + 1, yPos + 1, regionPixelSize - 2, regionPixelSize - 2);
				}
				else if ((isNeighbor || isCharter) && regionId == hoveredRegionId)
				{
					graphics.drawRect(xPos + 1, yPos + 1, regionPixelSize - 2, regionPixelSize - 2);
				}

				// Draw region ID in top-left corner of each chunk (only if chunk is large enough to show text)
				if (regionPixelSize > 20)
				{
					String idText = String.valueOf(regionId);
					int textX = xPos + 4;
					int textY = yPos + 16;

					// Make sure text position is within the map bounds
					if (textX > worldMapRect.getX() && textY > worldMapRect.getY())
					{
						// Simple drop shadow (single offset, bottom-right)
						graphics.setColor(Color.BLACK);
						graphics.drawString(idText, textX + 1, textY + 1);

						// Draw text in green for unlocked, gold for neighbor, red for locked
						if (isUnlocked)
						{
							graphics.setColor(new Color(0, 255, 0));
						}
						else if (isNeighbor)
						{
							graphics.setColor(new Color(255, 215, 0));
						}
						else
						{
							graphics.setColor(new Color(255, 80, 80));
						}
						graphics.drawString(idText, textX, textY);
					}
				}
			}
		}

		// Hovering an unlockable neighbour: show the keybind+click tooltip. The
		// actual unlock is handled in ChunkBlazerPlugin.onMenuOptionClicked when
		// the map-unlock key is held during the click (Region Locker model) —
		// world-map right-click menu entries don't render reliably.
		if (isHoveredUnlockable && hoveredRegionId > 0)
		{
			drawHoverTooltip(graphics, mousePos, hoveredRegionId);
		}

		// Draw region ID in top-left corner of world map
		if (hoveredRegionId > 0)
		{
			drawRegionIdDisplay(graphics, worldMapRect, hoveredRegionId);
		}
		else
		{
			// Show current player region if not hovering
			drawRegionIdDisplay(graphics, worldMapRect, currentRegionId);
		}

		return null;
	}

	private void drawRegionIdDisplay(Graphics2D graphics, Rectangle worldMapRect, int regionId)
	{
		if (regionId <= 0)
		{
			return;
		}

		String regionName = plugin.getRegionName(regionId);
		String regionIdText = "Region: " + regionId;

		Font font = FontManager.getRunescapeSmallFont();
		graphics.setFont(font);
		FontMetrics fm = graphics.getFontMetrics();

		int padding = 4;
		int lineHeight = fm.getHeight();
		int maxWidth = Math.max(fm.stringWidth(regionName), fm.stringWidth(regionIdText));
		int boxWidth = maxWidth + padding * 2;
		int boxHeight = lineHeight * 2 + padding * 2;

		int boxX = (int) worldMapRect.getX() + 5;
		int boxY = (int) worldMapRect.getY() + 5;

		// Draw background
		graphics.setColor(new Color(0, 0, 0, 180));
		graphics.fillRect(boxX, boxY, boxWidth, boxHeight);

		// Draw border
		graphics.setColor(new Color(255, 215, 0, 200));
		graphics.drawRect(boxX, boxY, boxWidth, boxHeight);

		// Draw region name
		int textX = boxX + padding;
		int textY = boxY + padding + fm.getAscent();
		graphics.setColor(Color.WHITE);
		graphics.drawString(regionName, textX, textY);

		// Draw region ID
		textY += lineHeight;
		graphics.setColor(new Color(200, 200, 200));
		graphics.drawString(regionIdText, textX, textY);
	}

	private void drawHoverTooltip(Graphics2D graphics, Point mousePos, int regionId)
	{
		if (mousePos == null)
		{
			return;
		}

		String regionName = plugin.getRegionName(regionId);
		int unlockCost = plugin.getRegionUnlockCost(regionId);
		int playerPoints = plugin.getTotalPoints();
		boolean canAfford = playerPoints >= unlockCost;

		// Build tooltip text
		String line1 = regionName;
		String line2 = "Cost: " + unlockCost + " pts";
		String line3 = canAfford
			? "Hold " + config.worldMapUnlockKey() + " + click to unlock"
			: "Need " + (unlockCost - playerPoints) + " more pts";

		// Setup font
		Font font = FontManager.getRunescapeSmallFont();
		graphics.setFont(font);
		FontMetrics fm = graphics.getFontMetrics();

		// Calculate tooltip size
		int padding = 6;
		int lineHeight = fm.getHeight();
		int maxWidth = Math.max(fm.stringWidth(line1), Math.max(fm.stringWidth(line2), fm.stringWidth(line3)));
		int tooltipWidth = maxWidth + padding * 2;
		int tooltipHeight = lineHeight * 3 + padding * 2;

		// Position tooltip near mouse (offset to not cover cursor)
		int tooltipX = mousePos.getX() + 15;
		int tooltipY = mousePos.getY() - tooltipHeight - 5;

		// Draw background
		graphics.setColor(new Color(30, 30, 30, 230));
		graphics.fillRect(tooltipX, tooltipY, tooltipWidth, tooltipHeight);

		// Draw border
		graphics.setColor(canAfford ? UNLOCKABLE_BORDER : LOCKED_BORDER);
		graphics.drawRect(tooltipX, tooltipY, tooltipWidth, tooltipHeight);

		// Draw text
		int textX = tooltipX + padding;
		int textY = tooltipY + padding + fm.getAscent();

		graphics.setColor(Color.WHITE);
		graphics.drawString(line1, textX, textY);

		textY += lineHeight;
		graphics.setColor(new Color(255, 215, 0)); // Gold for cost
		graphics.drawString(line2, textX, textY);

		textY += lineHeight;
		graphics.setColor(canAfford ? new Color(100, 255, 100) : new Color(255, 100, 100));
		graphics.drawString(line3, textX, textY);
	}

	private void addUnlockMenuEntry(int regionId)
	{
		Widget bottomBar = client.getWidget(InterfaceID.Worldmap.BOTTOM_GRAPHIC0);
		if (bottomBar == null || client.isMenuOpen())
		{
			return;
		}

		String regionName = plugin.getRegionName(regionId);
		int unlockCost = plugin.getRegionUnlockCost(regionId);
		int playerPoints = plugin.getTotalPoints();

		client.createMenuEntry(-1)
			.setTarget(ColorUtil.wrapWithColorTag(regionName + " (" + regionId + ")", JagexColors.MENU_TARGET))
			.setOption("Unlock chunk")
			.setType(MenuAction.RUNELITE)
			.onClick(m -> showUnlockConfirmation(regionId, regionName, unlockCost, playerPoints));

		// Show cost info
		String costText = "Cost: " + unlockCost + " pts";
		if (playerPoints < unlockCost)
		{
			costText += " (Need " + (unlockCost - playerPoints) + " more)";
		}
		client.createMenuEntry(-2)
			.setTarget(ColorUtil.wrapWithColorTag(costText, playerPoints >= unlockCost ? new Color(255, 215, 0) : Color.RED))
			.setOption("")
			.setType(MenuAction.RUNELITE);
	}


	private void showUnlockConfirmation(int regionId, String regionName, int unlockCost, int playerPoints)
	{
		clientThread.invokeLater(() ->
		{
			if (playerPoints < unlockCost)
			{
				// Show "not enough points" message with OK button
				chatboxPanelManager.openTextMenuInput(
						"Cannot unlock " + regionName + "! " +
						"Need " + (unlockCost - playerPoints) + " more points. " +
						"(Cost: " + unlockCost + ", You have: " + playerPoints + ")")
					.option("OK", () -> {})
					.build();
				return;
			}

			// Show unlock confirmation with Confirm/Cancel options
			chatboxPanelManager.openTextMenuInput(
					"Confirm: it costs " + unlockCost + " points to unlock " + regionName + ". " +
					"(Remaining after unlock: " + (playerPoints - unlockCost) + " points)")
				.option("Confirm", () ->
				{
					plugin.unlockRegion(regionId);
					log.info("Unlocked region {} for {} points", regionName, unlockCost);
				})
				.option("Cancel", () -> {})
				.build();
		});
	}
}
