package net.runelite.client.plugins.chunkblazer;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import net.runelite.client.input.KeyListener;

/**
 * Tracks whether the configured "map unlock" key is held, so a click on the
 * world map while it's down unlocks the hovered chunk — the same keybind+click
 * model Region Locker uses. The unlock itself happens in
 * {@link ChunkBlazerPlugin#onMenuOptionClicked}; this listener only flips the
 * held-state flag.
 */
public class ChunkBlazerInput implements KeyListener
{
	@Inject
	private ChunkBlazerConfig config;

	@Inject
	private ChunkBlazerPlugin plugin;

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (config.worldMapUnlockKey().matches(e))
		{
			plugin.setWorldMapUnlockKeyPressed(true);
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (config.worldMapUnlockKey().matches(e))
		{
			plugin.setWorldMapUnlockKeyPressed(false);
		}
	}
}
