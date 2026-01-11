package net.runelite.client.plugins.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Overlay that renders ChunkBlazer icons and info above other players who are also using the plugin.
 */
public class ChunkBlazerPlayerOverlay extends Overlay
{
	private static final int ICON_SIZE = 16;
	private static final int VERTICAL_OFFSET = 40; // Pixels above player's head
	private static final Color NUZLOCKE_COLOR = new Color(255, 100, 100); // Red-ish
	private static final Color CASUAL_COLOR = new Color(100, 200, 100);   // Green-ish
	private static final Color TEXT_BACKGROUND = new Color(0, 0, 0, 150); // Semi-transparent black

	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;

	// Cache of known ChunkBlazer players on the current world
	// Key: RSN (lowercase), Value: player info from server
	private final Map<String, OnlineChunkBlazerPlayer> knownPlayers = new ConcurrentHashMap<>();

	// ChunkBlazer icon (loaded from resources)
	private BufferedImage chunkBlazerIcon;
	private BufferedImage nuzlockeIcon;
	private BufferedImage casualIcon;

	@Inject
	public ChunkBlazerPlayerOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.HIGH);

		loadIcons();
	}

	private void loadIcons()
	{
		// Generate fire icons programmatically
		chunkBlazerIcon = ChunkBlazerIcons.createFireIcon(ICON_SIZE);
		nuzlockeIcon = ChunkBlazerIcons.createNuzlockeIcon(ICON_SIZE);
		casualIcon = ChunkBlazerIcons.createCasualIcon(ICON_SIZE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOtherPlayers())
		{
			return null;
		}

		if (knownPlayers.isEmpty())
		{
			return null;
		}

		// Iterate through all visible players
		for (Player player : client.getPlayers())
		{
			// Skip the local player
			if (player == client.getLocalPlayer())
			{
				continue;
			}

			String playerName = player.getName();
			if (playerName == null)
			{
				continue;
			}

			// Check if this player is a known ChunkBlazer player
			OnlineChunkBlazerPlayer cbPlayer = knownPlayers.get(playerName.toLowerCase());
			if (cbPlayer == null)
			{
				continue;
			}

			// Render the overlay above this player
			renderPlayerOverlay(graphics, player, cbPlayer);
		}

		return null;
	}

	private void renderPlayerOverlay(Graphics2D graphics, Player player, OnlineChunkBlazerPlayer cbPlayer)
	{
		// Get position above player's head
		Point textLocation = player.getCanvasTextLocation(graphics, cbPlayer.getRsn(), VERTICAL_OFFSET);
		if (textLocation == null)
		{
			return;
		}

		// Determine colors based on game mode
		Color modeColor = cbPlayer.getGameMode() == GameMode.NUZLOCKE ? NUZLOCKE_COLOR : CASUAL_COLOR;

		// Build the display text
		String pointsText = formatPoints(cbPlayer.getTotalPoints()) + " pts";
		String modeText = cbPlayer.getGameMode().getName();

		FontMetrics fm = graphics.getFontMetrics();
		int textWidth = Math.max(fm.stringWidth(pointsText), fm.stringWidth(modeText));
		int totalWidth = ICON_SIZE + 4 + textWidth;

		int x = textLocation.getX() - totalWidth / 2;
		int y = textLocation.getY();

		// Draw background box
		int boxHeight = fm.getHeight() * 2 + 4;
		graphics.setColor(TEXT_BACKGROUND);
		graphics.fillRoundRect(x - 2, y - fm.getHeight(), totalWidth + 4, boxHeight, 4, 4);

		// Draw mode-specific icon
		BufferedImage iconToDraw = chunkBlazerIcon;
		if (cbPlayer.getGameMode() == GameMode.NUZLOCKE && nuzlockeIcon != null)
		{
			iconToDraw = nuzlockeIcon;
		}
		else if (cbPlayer.getGameMode() == GameMode.CASUAL && casualIcon != null)
		{
			iconToDraw = casualIcon;
		}

		if (iconToDraw != null)
		{
			graphics.drawImage(iconToDraw, x, y - fm.getHeight() + 2, null);
		}
		else
		{
			// Fallback: draw a colored square
			graphics.setColor(modeColor);
			graphics.fillRect(x, y - fm.getHeight() + 2, ICON_SIZE, ICON_SIZE);
		}

		// Draw mode text (e.g., "Nuzlocke")
		int textX = x + ICON_SIZE + 4;
		graphics.setColor(modeColor);
		graphics.drawString(modeText, textX, y);

		// Draw points text
		graphics.setColor(Color.WHITE);
		graphics.drawString(pointsText, textX, y + fm.getHeight());

		// Optionally draw rank if available
		if (cbPlayer.getRank() > 0)
		{
			String rankText = "#" + cbPlayer.getRank();
			graphics.setColor(Color.YELLOW);
			graphics.drawString(rankText, x + totalWidth - fm.stringWidth(rankText), y);
		}
	}

	/**
	 * Format points with K/M suffix for large numbers.
	 */
	private String formatPoints(int points)
	{
		if (points >= 1_000_000)
		{
			return String.format("%.1fM", points / 1_000_000.0);
		}
		else if (points >= 1_000)
		{
			return String.format("%.1fK", points / 1_000.0);
		}
		return String.valueOf(points);
	}

	/**
	 * Update the list of known ChunkBlazer players on this world.
	 * Called periodically by the plugin after fetching from the API.
	 */
	public void updateKnownPlayers(Map<String, OnlineChunkBlazerPlayer> players)
	{
		knownPlayers.clear();
		knownPlayers.putAll(players);
	}

	/**
	 * Add a single player to the known list.
	 */
	public void addKnownPlayer(OnlineChunkBlazerPlayer player)
	{
		knownPlayers.put(player.getRsn().toLowerCase(), player);
	}

	/**
	 * Remove a player from the known list.
	 */
	public void removeKnownPlayer(String rsn)
	{
		knownPlayers.remove(rsn.toLowerCase());
	}

	/**
	 * Clear all known players.
	 */
	public void clearKnownPlayers()
	{
		knownPlayers.clear();
	}

	/**
	 * Check if a player is a known ChunkBlazer player.
	 */
	public boolean isKnownPlayer(String rsn)
	{
		return knownPlayers.containsKey(rsn.toLowerCase());
	}

	/**
	 * Get info for a known player.
	 */
	public OnlineChunkBlazerPlayer getKnownPlayer(String rsn)
	{
		return knownPlayers.get(rsn.toLowerCase());
	}

	/**
	 * Data class representing an online ChunkBlazer player.
	 */
	@lombok.Data
	public static class OnlineChunkBlazerPlayer
	{
		private String rsn;
		private GameMode gameMode;
		private int totalPoints;
		private int rank;
		private int world;
		private int regionId;
		private String currentTask;
	}
}
