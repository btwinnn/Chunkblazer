package net.runelite.client.plugins.chunkblazer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Overlay that renders chunk borders on the minimap and allows click-to-unlock.
 */
@Slf4j
public class ChunkBlazerMinimapOverlay extends Overlay
{
	private static final int REGION_SIZE = 64; // 64 tiles per region
	private static final Color UNLOCKED_COLOR = new Color(0, 255, 0, 100);
	private static final Color LOCKED_COLOR = new Color(255, 0, 0, 60);
	private static final Color NEIGHBOR_COLOR = new Color(255, 215, 0, 120);
	private static final Color CURRENT_COLOR = new Color(0, 200, 255, 150);

	private final Client client;
	private final ClientThread clientThread;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final ChatboxPanelManager chatboxPanelManager;

	// Track hovered region for click detection
	private int hoveredRegionId = -1;
	private Rectangle lastMinimapBounds = null;

	@Inject
	public ChunkBlazerMinimapOverlay(Client client, ClientThread clientThread, ChunkBlazerPlugin plugin,
		ChunkBlazerConfig config, ChatboxPanelManager chatboxPanelManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.plugin = plugin;
		this.config = config;
		this.chatboxPanelManager = chatboxPanelManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showMinimapChunks())
		{
			return null;
		}

		Widget minimapWidget = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_MINIMAP_DRAW_AREA);
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			minimapWidget = client.getWidget(ComponentID.FIXED_VIEWPORT_MINIMAP_DRAW_AREA);
		}
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			minimapWidget = client.getWidget(ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_MINIMAP_DRAW_AREA);
		}
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			return null;
		}

		Rectangle minimapBounds = minimapWidget.getBounds();
		if (minimapBounds == null)
		{
			return null;
		}
		lastMinimapBounds = minimapBounds;

		// Get player position
		WorldPoint playerLocation = client.getLocalPlayer().getWorldLocation();
		if (playerLocation == null)
		{
			return null;
		}

		int playerRegionId = playerLocation.getRegionID();
		Set<String> unlockedRegions = plugin.getUnlockedRegionIds();
		Set<Integer> neighborRegions = plugin.getNeighborRegionIds();

		// Get mouse position for hover detection
		Point mousePos = client.getMouseCanvasPosition();
		hoveredRegionId = -1;

		// Calculate the center of the minimap
		int centerX = (int) (minimapBounds.getCenterX());
		int centerY = (int) (minimapBounds.getCenterY());

		// Minimap scale (approximate)
		double scale = 4.0; // pixels per tile (approximate, varies with zoom)

		// Draw chunk borders for nearby regions
		graphics.setClip(minimapBounds);
		Stroke oldStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(2));

		// Calculate visible region range (roughly 3x3 regions around player)
		int playerRegionX = playerLocation.getX() >> 6;
		int playerRegionY = playerLocation.getY() >> 6;

		for (int rx = playerRegionX - 1; rx <= playerRegionX + 1; rx++)
		{
			for (int ry = playerRegionY - 1; ry <= playerRegionY + 1; ry++)
			{
				int regionId = (rx << 8) | ry;

				// Calculate region corners in world coordinates
				int regionBaseX = rx << 6;
				int regionBaseY = ry << 6;

				// Convert to minimap coordinates (relative to player)
				int dx = regionBaseX - playerLocation.getX();
				int dy = regionBaseY - playerLocation.getY();

				// Minimap has Y inverted
				int minimapX = centerX + (int)(dx * scale);
				int minimapY = centerY - (int)(dy * scale);
				int regionSize = (int)(REGION_SIZE * scale);

				// Create region rectangle on minimap
				Rectangle regionRect = new Rectangle(minimapX, minimapY - regionSize, regionSize, regionSize);

				// Check if region is within minimap bounds
				if (!minimapBounds.intersects(regionRect))
				{
					continue;
				}

				// Check hover
				if (mousePos != null && regionRect.contains(mousePos.getX(), mousePos.getY()))
				{
					hoveredRegionId = regionId;
				}

				// Determine color based on state
				boolean isUnlocked = unlockedRegions.contains(String.valueOf(regionId));
				boolean isNeighbor = neighborRegions.contains(regionId);
				boolean isCurrent = regionId == playerRegionId;

				Color fillColor;
				Color borderColor;

				if (isCurrent)
				{
					fillColor = new Color(0, 200, 255, 40);
					borderColor = CURRENT_COLOR;
				}
				else if (isUnlocked)
				{
					fillColor = new Color(0, 255, 0, 20);
					borderColor = UNLOCKED_COLOR;
				}
				else if (isNeighbor)
				{
					// Brighter if hovered
					if (regionId == hoveredRegionId)
					{
						fillColor = new Color(255, 215, 0, 80);
					}
					else
					{
						fillColor = new Color(255, 215, 0, 30);
					}
					borderColor = NEIGHBOR_COLOR;
				}
				else
				{
					fillColor = new Color(0, 0, 0, 40);
					borderColor = LOCKED_COLOR;
				}

				// Clip to minimap bounds
				Rectangle clippedRect = minimapBounds.intersection(regionRect);

				// Draw fill
				graphics.setColor(fillColor);
				graphics.fill(clippedRect);

				// Draw border
				graphics.setColor(borderColor);
				graphics.draw(clippedRect);
			}
		}

		graphics.setStroke(oldStroke);
		graphics.setClip(null);

		return null;
	}

	/**
	 * Handle minimap click - show unlock popup if clicking on a neighbor region.
	 */
	public boolean handleMinimapClick(int mouseX, int mouseY)
	{
		if (!config.showMinimapChunks() || lastMinimapBounds == null)
		{
			return false;
		}

		// Check if click is within minimap
		if (!lastMinimapBounds.contains(mouseX, mouseY))
		{
			return false;
		}

		// Check if we have a hovered unlockable region
		if (hoveredRegionId <= 0)
		{
			return false;
		}

		// Check if it's a neighbor region that can be unlocked
		Set<Integer> neighborRegions = plugin.getNeighborRegionIds();
		if (!neighborRegions.contains(hoveredRegionId))
		{
			return false;
		}

		// Already unlocked?
		if (plugin.isRegionUnlocked(hoveredRegionId))
		{
			return false;
		}

		// Show unlock popup
		showUnlockPopup(hoveredRegionId);
		return true;
	}

	private void showUnlockPopup(int regionId)
	{
		String regionName = plugin.getRegionName(regionId);
		int cost = plugin.getRegionUnlockCost(regionId);
		int currentPoints = plugin.getTotalPoints();

		clientThread.invokeLater(() ->
		{
			if (currentPoints < cost)
			{
				chatboxPanelManager.openTextMenuInput(
						"Cannot unlock " + regionName + "! " +
						"Need " + (cost - currentPoints) + " more points. " +
						"(Cost: " + cost + ", You have: " + currentPoints + ")")
					.option("OK", () -> {})
					.build();
			}
			else
			{
				chatboxPanelManager.openTextMenuInput(
						"Unlock " + regionName + " for " + cost + " points? " +
						"(Remaining: " + (currentPoints - cost) + " points)")
					.option("Yes, unlock!", () ->
					{
						plugin.unlockRegion(regionId);
						log.info("Player unlocked region {} via minimap click", regionName);
					})
					.option("No", () -> {})
					.build();
			}
		});
	}

	public int getHoveredRegionId()
	{
		return hoveredRegionId;
	}
}
