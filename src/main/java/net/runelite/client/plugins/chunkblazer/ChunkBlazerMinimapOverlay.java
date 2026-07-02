package net.runelite.client.plugins.chunkblazer;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
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
 * Overlay that enables right-click to unlock chunks on the minimap.
 * No visual drawing - just tracks mouse position for menu entries.
 */
@Slf4j
public class ChunkBlazerMinimapOverlay extends Overlay
{
	private static final int REGION_SIZE = 64; // 64 tiles per region

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

		Set<Integer> neighborRegions = plugin.getNeighborRegionIds();

		// Get mouse position for hover detection
		Point mousePos = client.getMouseCanvasPosition();
		hoveredRegionId = -1;

		// Calculate the center of the minimap
		int centerX = (int) (minimapBounds.getCenterX());
		int centerY = (int) (minimapBounds.getCenterY());

		// Minimap scale (approximate)
		double scale = 4.0; // pixels per tile (approximate, varies with zoom)

		// Calculate visible region range (roughly 3x3 regions around player)
		int playerRegionX = playerLocation.getX() >> 6;
		int playerRegionY = playerLocation.getY() >> 6;

		// Only track hover for right-click menu - no drawing
		for (int rx = playerRegionX - 1; rx <= playerRegionX + 1; rx++)
		{
			for (int ry = playerRegionY - 1; ry <= playerRegionY + 1; ry++)
			{
				int regionId = (rx << 8) | ry;

				// Only care about unlockable regions (neighbours + charter ports)
				if (!neighborRegions.contains(regionId) && !plugin.isCharterRegion(regionId) && !plugin.isFreeUnlockableRegion(regionId))
				{
					continue;
				}

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

				// Check hover - only set if it's an unlockable neighbor
				if (mousePos != null && regionRect.contains(mousePos.getX(), mousePos.getY()))
				{
					if (!plugin.isRegionUnlocked(regionId))
					{
						hoveredRegionId = regionId;
					}
				}
			}
		}

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

		// Check if it's an unlockable region (neighbour or charter port)
		if (!plugin.isUnlockableRegion(hoveredRegionId))
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
