package net.runelite.client.plugins.chunkblazer;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.input.MouseAdapter;

/**
 * Routes left clicks to the task-card overlay.
 *
 * <p>A click that lands on a face-down card is CONSUMED, so it flips the card instead of
 * also walking the player to that tile — the cards sit dead centre in the viewport, which
 * is exactly where a stray click would otherwise send you running. Clicks that miss every
 * card pass straight through untouched, so the overlay never interferes with normal play.
 */
@Singleton
public class TaskCardInput extends MouseAdapter
{
	private final TaskCardOverlay overlay;

	@Inject
	private TaskCardInput(TaskCardOverlay overlay)
	{
		this.overlay = overlay;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (event.getButton() != MouseEvent.BUTTON1 || !overlay.isActive())
		{
			return event;
		}

		if (overlay.onClick(event.getX(), event.getY()))
		{
			event.consume();
		}
		return event;
	}
}
