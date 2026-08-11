package com.chunkblazer;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Scene-level overlay that draws chunk (region) borders on the gameplay
 * viewport. The actual geometry/rendering work is delegated to
 * {@link ChunkBorderRenderer}, which is a separate, unit-tested class.
 * This overlay only wires the lifecycle into RuneLite's overlay system.
 */
@Slf4j
public class ChunkBlazerSceneOverlay extends Overlay
{
	private final Client client;
	private final ChunkBlazerPlugin plugin;
	private final ChunkBlazerConfig config;
	private final ChunkBorderRenderer renderer;

	@Inject
	public ChunkBlazerSceneOverlay(Client client, ChunkBlazerPlugin plugin, ChunkBlazerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.renderer = new ChunkBorderRenderer();

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(OverlayPriority.LOW);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showSceneChunks())
		{
			return null;
		}

		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}

		Scene scene = client.getScene();
		if (scene == null)
		{
			return null;
		}

		renderer.render(graphics, client, plugin::isRegionUnlocked);
		return null;
	}
}
