package net.runelite.client.plugins.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
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
	private static final Color LOCKED_BORDER = new Color(255, 0, 0, 120);
	private static final Color LOCKED_FILL = new Color(0, 0, 0, 100);
	private static final Color NEIGHBOR_BORDER = new Color(255, 215, 0, 200);
	private static final Color NEIGHBOR_FILL = new Color(255, 215, 0, 30);
	private static final Color NEIGHBOR_HOVER_FILL = new Color(255, 215, 0, 80); // Brighter when hovered
	private static final Color CURRENT_BORDER = new Color(0, 200, 255, 255);

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;

	private int hoveredRegionId = -1;
	private boolean isHoveredUnlockable = false;

	@Inject
	private ChunkBlazerWorldMapOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config)
	{
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_HIGH);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.WORLDMAP);
		this.client = client;
		this.plugin = plugin;
		this.config = config;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showChunkBorders())
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
				boolean isUnlocked = unlockedRegions.contains(String.valueOf(regionId));
				boolean isNeighbor = neighborRegions.contains(regionId);

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
						isHoveredUnlockable = isNeighbor && !isUnlocked;
					}
				}

				// Draw fills first
				if (!isUnlocked)
				{
					if (isNeighbor)
					{
						// Unlockable neighbor - brighter when hovered
						boolean isHovered = (regionId == hoveredRegionId);
						graphics.setColor(isHovered ? NEIGHBOR_HOVER_FILL : NEIGHBOR_FILL);
						graphics.fillRect(xPos, yPos, regionPixelSize, regionPixelSize);
					}
					else
					{
						// Locked - dark overlay (greyscale effect)
						graphics.setColor(LOCKED_FILL);
						graphics.fillRect(xPos, yPos, regionPixelSize, regionPixelSize);
					}
				}
			}
		}

		// Second pass: Draw borders
		for (int x = xRegionMin; x < xRegionMax; x += REGION_SIZE)
		{
			for (int y = yRegionMin; y < yRegionMax; y += REGION_SIZE)
			{
				int regionId = ((x >> 6) << 8) | (y >> 6);
				boolean isUnlocked = unlockedRegions.contains(String.valueOf(regionId));
				boolean isNeighbor = neighborRegions.contains(regionId);
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
				else if (isNeighbor)
				{
					graphics.setColor(NEIGHBOR_BORDER);
				}
				else
				{
					graphics.setColor(LOCKED_BORDER);
				}

				// Draw border
				graphics.drawRect(xPos, yPos, regionPixelSize, regionPixelSize);

				// Draw thicker border for current region
				if (isCurrent)
				{
					graphics.drawRect(xPos + 1, yPos + 1, regionPixelSize - 2, regionPixelSize - 2);
				}
			}
		}

		// Add right-click menu for unlocking neighbors (only when Shift is held)
		if (isHoveredUnlockable && hoveredRegionId > 0)
		{
			// Only show unlock menu when Shift is held
			if (client.isKeyPressed(KeyCode.KC_SHIFT))
			{
				addUnlockMenuEntry(hoveredRegionId);
			}

			// Always draw hover tooltip when hovering unlockable region
			drawHoverTooltip(graphics, mousePos, hoveredRegionId);
		}

		return null;
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
		String line3 = canAfford ? "Shift+Right-click to unlock" : "Need " + (unlockCost - playerPoints) + " more pts";

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
		graphics.setColor(canAfford ? NEIGHBOR_BORDER : LOCKED_BORDER);
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
		SwingUtilities.invokeLater(() ->
		{
			if (playerPoints < unlockCost)
			{
				JOptionPane.showMessageDialog(
					null,
					"Not enough points to unlock this chunk!\n\n" +
					regionName + "\n\n" +
					"Cost: " + unlockCost + " points\n" +
					"You have: " + playerPoints + " points\n" +
					"Need: " + (unlockCost - playerPoints) + " more points",
					"Cannot Unlock Chunk",
					JOptionPane.WARNING_MESSAGE
				);
				return;
			}

			int confirm = JOptionPane.showConfirmDialog(
				null,
				"Ready to unlock this chunk?\n\n" +
				regionName + "\n\n" +
				"Cost: " + unlockCost + " points\n" +
				"Your points: " + playerPoints + "\n" +
				"Remaining after unlock: " + (playerPoints - unlockCost) + " points",
				"Unlock Chunk?",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE
			);

			if (confirm == JOptionPane.YES_OPTION)
			{
				plugin.unlockRegion(regionId);
				log.info("Unlocked region {} for {} points", regionName, unlockCost);
			}
		});
	}
}
