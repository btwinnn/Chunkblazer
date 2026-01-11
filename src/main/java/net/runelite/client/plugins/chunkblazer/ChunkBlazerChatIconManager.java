package net.runelite.client.plugins.chunkblazer;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ImageUtil;

/**
 * Manages ChunkBlazer icons displayed next to player names in chat.
 * When a known ChunkBlazer player sends a message, their name will have a small icon next to it.
 */
@Slf4j
@Singleton
public class ChunkBlazerChatIconManager
{
	private static final int ICON_WIDTH = 11;
	private static final int ICON_HEIGHT = 11;

	private final Client client;
	private final ClientThread clientThread;
	private final ChunkBlazerConfig config;

	// Map of known ChunkBlazer players: RSN (lowercase) -> GameMode
	private final Map<String, GameMode> knownPlayers = new ConcurrentHashMap<>();

	// The mod icons index for our custom icon
	private int chunkBlazerIconIndex = -1;
	private int nuzlockeIconIndex = -1;
	private int casualIconIndex = -1;

	@Inject
	public ChunkBlazerChatIconManager(Client client, ClientThread clientThread, ChunkBlazerConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
	}

	/**
	 * Load custom icons into the game's mod icons array.
	 * Must be called after the client is ready.
	 */
	public void loadIcons()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getModIcons() == null)
			{
				return false; // Not ready yet
			}

			// Generate fire icons programmatically
			BufferedImage mainIcon = ChunkBlazerIcons.createChatIcon(ICON_WIDTH);
			BufferedImage nuzlockeImage = ChunkBlazerIcons.createNuzlockeIcon(ICON_WIDTH);
			BufferedImage casualImage = ChunkBlazerIcons.createCasualIcon(ICON_WIDTH);

			// Add to mod icons
			chunkBlazerIconIndex = addModIcon(mainIcon);
			nuzlockeIconIndex = addModIcon(nuzlockeImage);
			casualIconIndex = addModIcon(casualImage);

			log.info("ChunkBlazer chat icons loaded - main: {}, nuzlocke: {}, casual: {}",
				chunkBlazerIconIndex, nuzlockeIconIndex, casualIconIndex);

			return true;
		});
	}

	/**
	 * Add a custom icon to the mod icons array.
	 */
	private int addModIcon(BufferedImage image)
	{
		IndexedSprite[] modIcons = client.getModIcons();

		// Create new sprite from image
		IndexedSprite newSprite = ImageUtil.getImageIndexedSprite(image, client);

		// Extend the mod icons array
		IndexedSprite[] newModIcons = new IndexedSprite[modIcons.length + 1];
		System.arraycopy(modIcons, 0, newModIcons, 0, modIcons.length);
		newModIcons[modIcons.length] = newSprite;

		client.setModIcons(newModIcons);

		return modIcons.length; // Return the index of the new icon
	}

	/**
	 * Process a chat message and add ChunkBlazer icon if the sender is a known player.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.showChatIcons())
		{
			return;
		}

		if (chunkBlazerIconIndex < 0)
		{
			return; // Icons not loaded yet
		}

		String senderName = event.getName();
		if (senderName == null || senderName.isEmpty())
		{
			return;
		}

		// Clean the name (remove existing icons/formatting)
		String cleanName = cleanPlayerName(senderName);

		// Check if this is a known ChunkBlazer player
		GameMode mode = knownPlayers.get(cleanName.toLowerCase());
		if (mode == null)
		{
			return;
		}

		// Determine which icon to use
		int iconIndex = chunkBlazerIconIndex;
		if (mode == GameMode.NUZLOCKE && nuzlockeIconIndex >= 0)
		{
			iconIndex = nuzlockeIconIndex;
		}
		else if (mode == GameMode.CASUAL && casualIconIndex >= 0)
		{
			iconIndex = casualIconIndex;
		}

		// Add the icon to the player's name
		// The format is <img=X> where X is the mod icon index
		String iconTag = "<img=" + iconIndex + ">";
		String newName = iconTag + senderName;

		// Update the message
		event.getMessageNode().setName(newName);
	}

	/**
	 * Clean a player name by removing any existing icon tags.
	 */
	private String cleanPlayerName(String name)
	{
		// Remove <img=X> tags
		return name.replaceAll("<img=\\d+>", "").trim();
	}

	/**
	 * Update the list of known ChunkBlazer players.
	 */
	public void updateKnownPlayers(Map<String, GameMode> players)
	{
		knownPlayers.clear();
		// Store with lowercase keys for case-insensitive matching
		players.forEach((rsn, mode) -> knownPlayers.put(rsn.toLowerCase(), mode));
		log.debug("Updated known players for chat icons: {} players", knownPlayers.size());
	}

	/**
	 * Add a single known player.
	 */
	public void addKnownPlayer(String rsn, GameMode mode)
	{
		knownPlayers.put(rsn.toLowerCase(), mode);
	}

	/**
	 * Remove a known player.
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
	 * Check if a player is known.
	 */
	public boolean isKnownPlayer(String rsn)
	{
		return knownPlayers.containsKey(rsn.toLowerCase());
	}

	/**
	 * Get the icon tag for a player (for use in custom messages).
	 */
	public String getIconTag(String rsn)
	{
		if (chunkBlazerIconIndex < 0)
		{
			return "";
		}

		GameMode mode = knownPlayers.get(rsn.toLowerCase());
		if (mode == null)
		{
			return "";
		}

		int iconIndex = chunkBlazerIconIndex;
		if (mode == GameMode.NUZLOCKE && nuzlockeIconIndex >= 0)
		{
			iconIndex = nuzlockeIconIndex;
		}
		else if (mode == GameMode.CASUAL && casualIconIndex >= 0)
		{
			iconIndex = casualIconIndex;
		}

		return "<img=" + iconIndex + ">";
	}

	/**
	 * Get the icon tag for the ChunkBlazer icon (for plugin messages).
	 */
	public String getChunkBlazerIconTag()
	{
		if (chunkBlazerIconIndex < 0)
		{
			return "";
		}
		return "<img=" + chunkBlazerIconIndex + ">";
	}
}
