/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * The task-roll reveal: rolled tasks arrive as face-down cards mid-viewport until
 * flipped.
 *
 * <p>This is a gate: a rolled task isn't added to the active list or any tracking
 * module until its card is turned over. The pending set lives in config
 * ({@code unrevealedTasks}), so unflipped cards survive a relog. The roll is already
 * committed to {@code regionRolledTasks}, so refusing to flip can't reroll it.
 *
 * <p>The cards are drawn in the game's style by {@link CardArt}.
 */
@Slf4j
@Singleton
public class TaskCardOverlay extends Overlay
{
	/** How long a card takes to turn over, milliseconds. */
	private static final long FLIP_DURATION = 420;
	/** Fade-out once the player dismisses a face-up card. */
	private static final long EXIT_DURATION = 320;

	/**
	 * Preferred width of the single centred card. One card at a time (pack-opening),
	 * so it can be larger than the old grid allowed; clamped to the viewport below.
	 */
	private static final int CARD_WIDTH = 260;
	/** How far each stacked "deck" card behind the current one is offset, px. */
	private static final int DECK_OFFSET = 7;
	/** How many face-down cards to draw in the pile behind the current card. */
	private static final int DECK_MAX_VISIBLE = 5;
	/** Height over width, as the original card art (356 x 200). */
	private static final double ASPECT = 200.0 / 356.0;
	private static final Color PROMPT = new Color(235, 235, 235, 225);

	private final Client client;
	private final ChunkBlazerConfig config;
	private final ChunkBlazerPlugin plugin;

	/** Cards currently on screen, in draw order. Rebuilt from the plugin's pending set. */
	private final List<Card> cards = new ArrayList<>();

	/** Raw pending-set text as of the last rebuild — see syncCards(). */
	private String lastPendingRaw = null;

	/**
	 * Bounds of the "Reveal 5" / "Reveal all" batch buttons drawn this frame, or null
	 * when they aren't shown. Set in render (client thread), read in onClick (mouse
	 * thread) — a benign race, same as the card bounds.
	 */
	private Rectangle revealFiveBounds;
	private Rectangle revealAllBounds;

	/**
	 * A batch-reveal asked for by a button click. 0 = none, -1 = reveal all, N =
	 * reveal N. Written on the mouse thread by onClick, consumed on the client thread
	 * in render() so the actual reveal (config writes, task activation) stays on the
	 * same thread the single-card flip already uses.
	 */
	private volatile int batchRevealRequest = 0;

	/**
	 * One card and where it is in its life:
	 * FACE_DOWN → (click) → turning → FACE_UP → (click) → leaving → gone.
	 *
	 * <p>The task is activated at the END OF THE TURN, not on dismissal, so a card left
	 * FACE_UP parks nothing. It stays up until a second click dismisses it.
	 */
	private static class Card
	{
		final String taskId;
		final String taskName;
		final String description;
		final TaskCardTier tier;
		/** How many the task asks for; shown on the face when more than 1. */
		final int quantity;
		Rectangle bounds = new Rectangle();
		/** Wall-clock ms the turn began, or -1 while still face down. */
		long flipStart = -1;
		/** Wall-clock ms the player dismissed it, or -1 while still on screen. */
		long dismissStart = -1;
		/** Set once the task has been handed to the plugin, so it happens exactly once. */
		boolean activated;

		Card(String taskId, String taskName, String description, TaskCardTier tier, int quantity)
		{
			this.taskId = taskId;
			this.taskName = taskName;
			this.description = description;
			this.tier = tier;
			this.quantity = quantity;
		}

		boolean isFaceDown()
		{
			return flipStart < 0;
		}

		boolean isTurning()
		{
			return flipStart > 0 && System.currentTimeMillis() - flipStart < FLIP_DURATION;
		}

		/** Turned over and readable — the state a card rests in. */
		boolean isFaceUp()
		{
			return flipStart > 0 && !isTurning() && dismissStart < 0;
		}

		boolean isLeaving()
		{
			return dismissStart > 0;
		}

		/** Anything past face-down survives a pending-set rebuild. */
		boolean isStarted()
		{
			return flipStart > 0;
		}

		long sinceFlip()
		{
			return System.currentTimeMillis() - flipStart;
		}

		long sinceDismiss()
		{
			return System.currentTimeMillis() - dismissStart;
		}
	}

	@Inject
	private TaskCardOverlay(Client client, ChunkBlazerConfig config, ChunkBlazerPlugin plugin)
	{
		this.client = client;
		this.config = config;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}




	/**
	 * Take a click at viewport coordinates.
	 *
	 * @return true if it landed on a face-down card, which starts its flip and consumes
	 * the click so it doesn't also walk the player somewhere.
	 */
	public boolean onClick(int x, int y)
	{
		if (!isActive())
		{
			return false;
		}
		// Batch controls first — the escape hatch from clicking through a big pack
		// (e.g. a boss chunk's ~30 cards). Handled on the client thread in render().
		if (revealAllBounds != null && revealAllBounds.contains(x, y))
		{
			batchRevealRequest = -1;
			return true;
		}
		if (revealFiveBounds != null && revealFiveBounds.contains(x, y))
		{
			batchRevealRequest = 5;
			return true;
		}
		// Pack-opening: only the front card (index 0) is interactive. Click it to
		// flip; click the face-up card to dismiss it and advance to the next.
		Card current = cards.get(0);
		if (!current.bounds.contains(x, y))
		{
			return false;
		}
		if (current.isFaceDown())
		{
			current.flipStart = System.currentTimeMillis();
			return true;
		}
		if (current.isFaceUp())
		{
			current.dismissStart = System.currentTimeMillis();
			return true;
		}
		// Mid-turn or already leaving: swallow the click so an impatient
		// double-click doesn't fall through to the game world underneath.
		return true;
	}

	/** True while there is anything to draw or click. */
	public boolean isActive()
	{
		return config.showTaskCards() && !cards.isEmpty();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTaskCards())
		{
			// Disabled mid-session: don't strand pending tasks behind an overlay that
			// will never draw again. Reveal them and let the old flow take over.
			revealAllPending();
			return null;
		}

		// Button bounds are recomputed every frame by drawPrompt; clear them first so
		// a stale rectangle can't take a click once the buttons stop being drawn.
		revealFiveBounds = null;
		revealAllBounds = null;

		processBatchReveal();
		syncCards();
		retireFinishedCards();

		if (cards.isEmpty())
		{
			return null;
		}

		Rectangle viewport = viewportBounds();
		if (viewport == null)
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		Card current = cards.get(0);
		layoutCurrent(current, viewport);

		// Deck of the remaining cards stacked behind the current one, so a 30-card
		// boss-chunk grant reads as a pack to open, not a wall to scroll.
		drawDeck(graphics, current, cards.size() - 1);
		drawCard(graphics, current);
		drawPrompt(graphics, viewport, current);
		return null;
	}

	/**
	 * Rebuild the card list from the plugin's pending set, preserving the flip state of
	 * cards on screen.
	 *
	 * <p>Guarded by a string compare against the last seen value: render() runs every
	 * frame, and per-repaint parsing has hard-locked a client (see
	 * ChunkBlazerPlugin#tasksById).
	 */
	private void syncCards()
	{
		String raw = plugin.getUnrevealedTasksRaw();
		if (raw == null)
		{
			raw = "";
		}
		if (raw.equals(lastPendingRaw) && !cards.isEmpty())
		{
			return;
		}
		lastPendingRaw = raw;

		List<String> pending = plugin.getUnrevealedTaskIds();

		// A card the player has started drops out of the pending set as soon as it
		// finishes turning, so match on "not started" — otherwise every face-up card
		// would be swept away by the very rebuild its own reveal triggered.
		cards.removeIf(card -> !pending.contains(card.taskId) && !card.isStarted());

		for (String taskId : pending)
		{
			if (hasCard(taskId))
			{
				continue;
			}
			NuzlockeTask task = plugin.getTaskById(taskId);
			if (task == null)
			{
				// A pending id with no task behind it can never be revealed by clicking,
				// so it would wedge the queue forever. Drop it rather than draw nothing.
				log.warn("Unknown unrevealed task '{}' dropped", taskId);
				plugin.revealTaskCard(taskId);
				continue;
			}
			cards.add(new Card(taskId, task.getName(), task.getDescription(), TaskCardTier.fromTask(task),
				plugin.getTaskTargetQuantity(taskId)));
		}
	}

	private boolean hasCard(String taskId)
	{
		for (Card card : cards)
		{
			if (card.taskId.equals(taskId))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Activate tasks whose card has finished turning, and drop cards the player has
	 * dismissed. Activation is deliberately at the end of the TURN rather than at
	 * dismissal — see the Card comment: a card left up must not hold a task hostage.
	 */
	private void retireFinishedCards()
	{
		Iterator<Card> it = cards.iterator();
		while (it.hasNext())
		{
			Card card = it.next();

			if (!card.activated && card.isStarted() && card.sinceFlip() >= FLIP_DURATION)
			{
				card.activated = true;
				plugin.revealTaskCard(card.taskId);
			}

			if (card.isLeaving() && card.sinceDismiss() >= EXIT_DURATION)
			{
				it.remove();
			}
		}
	}

	/**
	 * Honour a batch-reveal button click: flip N cards (or all) at once, instantly,
	 * without the per-card turn animation — the whole point is to not click through
	 * them. Runs on the client thread (called from render) so plugin.revealTaskCard,
	 * which activates the task and writes config, stays on the same thread as the
	 * single-card flip path in retireFinishedCards.
	 */
	private void processBatchReveal()
	{
		int req = batchRevealRequest;
		if (req == 0)
		{
			return;
		}
		batchRevealRequest = 0;

		List<String> pending = plugin.getUnrevealedTaskIds();
		int n = req < 0 ? pending.size() : Math.min(req, pending.size());
		for (int i = 0; i < n; i++)
		{
			plugin.revealTaskCard(pending.get(i));
		}

		// Rebuild from the now-smaller pending set on the next syncCards(). Clearing
		// is fine — every revealed task has already been activated, and any remaining
		// cards are re-created face-down.
		cards.clear();
		lastPendingRaw = null;
	}

	private void revealAllPending()
	{
		if (!cards.isEmpty())
		{
			cards.clear();
		}
		for (String taskId : plugin.getUnrevealedTaskIds())
		{
			plugin.revealTaskCard(taskId);
		}
	}

	private Rectangle viewportBounds()
	{
		int w = client.getViewportWidth();
		int h = client.getViewportHeight();
		if (w <= 0 || h <= 0)
		{
			return null;
		}
		return new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(), w, h);
	}

	/** Centre the current card in the viewport, clamped to fit. */
	private void layoutCurrent(Card current, Rectangle viewport)
	{
		int cardWidth = Math.min(CARD_WIDTH, viewport.width - 80);
		cardWidth = Math.max(48, cardWidth); // a truly tiny viewport still gets something
		int cardHeight = (int) Math.round(cardWidth * ASPECT);
		int x = viewport.x + (viewport.width - cardWidth) / 2;
		int y = viewport.y + (viewport.height - cardHeight) / 2;
		current.bounds = new Rectangle(x, y, cardWidth, cardHeight);
	}

	/**
	 * Draw the remaining cards as a face-down pile behind the current one, so the
	 * player can see there is more to open. Purely decorative — only the current
	 * card is interactive.
	 */
	private void drawDeck(Graphics2D graphics, Card current, int remaining)
	{
		if (remaining <= 0)
		{
			return;
		}
		int visible = Math.min(remaining, DECK_MAX_VISIBLE);
		Rectangle b = current.bounds;
		TaskCardTier tier = cards.size() > 1 ? cards.get(1).tier : current.tier;
		// Farthest card first so nearer ones overlap it.
		for (int i = visible; i >= 1; i--)
		{
			int ox = b.x - i * DECK_OFFSET;
			int oy = b.y - i * DECK_OFFSET;
			CardArt.paint(graphics, new Rectangle(ox, oy, b.width, b.height), tier, false, null, null, 0);
		}
	}

	private void drawCard(Graphics2D graphics, Card card)
	{
		// A 2D card flip is a horizontal squash: the card narrows to nothing at the
		// halfway point, and that is where back swaps to front.
		double scaleX = 1.0;
		boolean showFront = !card.isFaceDown();
		float alpha = 1f;

		if (card.isTurning())
		{
			double progress = (double) card.sinceFlip() / FLIP_DURATION;
			showFront = progress >= 0.5;
			scaleX = Math.abs(1.0 - 2.0 * progress);
			// Never fully degenerate — a zero-width draw is invisible AND a
			// zero-determinant transform, which some pipelines reject outright.
			scaleX = Math.max(scaleX, 0.02);
		}
		else if (card.isLeaving())
		{
			alpha = 1f - (float) card.sinceDismiss() / EXIT_DURATION;
			alpha = Math.max(0f, Math.min(1f, alpha));
		}

		Rectangle b = card.bounds;
		java.awt.Composite originalComposite = graphics.getComposite();
		if (alpha < 1f)
		{
			graphics.setComposite(java.awt.AlphaComposite.getInstance(
				java.awt.AlphaComposite.SRC_OVER, alpha));
		}

		AffineTransform originalTransform = graphics.getTransform();
		// Squash about the card's own centre so it turns in place.
		graphics.translate(b.x + b.width / 2.0, b.y + b.height / 2.0);
		graphics.scale(scaleX, 1.0);
		graphics.translate(-(b.x + b.width / 2.0), -(b.y + b.height / 2.0));

		revealGlow(graphics, card, b);
		CardArt.paint(graphics, b, card.tier, showFront, card.taskName, card.description, card.quantity);
		graphics.setTransform(originalTransform);
		graphics.setComposite(originalComposite);
	}

	/**
	 * As a card turns face up, pixel rings step out from behind it, more
	 * the rarer it is (nothing for Easy, a flare for Master).
	 */
	private static void revealGlow(Graphics2D graphics, Card card, Rectangle b)
	{
		int k = card.tier.ordinal();
		if (k == 0 || card.isFaceDown() || card.isLeaving())
		{
			return;
		}
		long age = card.sinceFlip() - FLIP_DURATION / 2;
		long life = 500 + 250L * k;
		if (age < 0 || age > life)
		{
			return;
		}
		float f = 1 - age / (float) life;
		Color c = card.tier.getAccent();
		int grow = Math.round((12 + 8 * k) * (1 - f * f));
		java.awt.Composite old = graphics.getComposite();
		graphics.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, Math.min(1f, f)));
		// Square pixel rings stepping outwards.
		for (int i = 0; i < k; i++)
		{
			int d = grow + i * 6;
			graphics.setColor(i % 2 == 0 ? c : new Color(255, 255, 0));
			graphics.drawRect(b.x - d, b.y - d, b.width + d * 2, b.height + d * 2);
		}
		graphics.setComposite(old);
	}




	private void drawPrompt(Graphics2D graphics, Rectangle viewport, Card current)
	{
		int total = cards.size();
		String text;
		if (current.isFaceDown())
		{
			text = total > 1
				? "Click to reveal. " + total + " cards to open"
				: "Click the card to reveal your task";
		}
		else if (current.isFaceUp())
		{
			// Nothing is waiting on this — the task is already yours. Say so, so the
			// card doesn't read as something still to be completed.
			int left = total - 1;
			text = left > 0
				? "Task added. Click for the next card (" + left + " left)"
				: "Task added. Click to close";
		}
		else
		{
			return;
		}

		graphics.setFont(Skin.bold());
		drawCentered(graphics, text, viewport.x + viewport.width / 2, current.bounds.y - 14,
			new Color(255, 255, 0));

		// Batch controls: only meaningful with a pile to work through, and only while
		// the front card is still face down (mid-flip/face-up the click means "next").
		if (current.isFaceDown() && total > 1)
		{
			// These send tasks straight to the list without the flip animation, so the
			// labels say exactly that. Stacked (not side by side) because the labels are
			// long enough that two columns would overlap on a narrow viewport.
			int cx = viewport.x + viewport.width / 2;
			int by = current.bounds.y + current.bounds.height + 10;
			if (total > 5)
			{
				revealFiveBounds = drawButton(graphics, "Send 5 To Task List", cx, by);
				revealAllBounds = drawButton(graphics, "Send All To Task List (" + total + ")",
					cx, by + revealFiveBounds.height + 6);
			}
			else
			{
				revealAllBounds = drawButton(graphics, "Send All To Task List (" + total + ")", cx, by);
			}
		}
	}

	/** A small centred pill button. Returns its bounds for hit-testing in onClick. */
	private Rectangle drawButton(Graphics2D graphics, String text, int centreX, int top)
	{
		graphics.setFont(Skin.small());
		FontMetrics fm = graphics.getFontMetrics();
		int padX = 10;
		int padY = 4;
		int w = fm.stringWidth(text) + padX * 2;
		int h = fm.getHeight() + padY * 2;
		int x = centreX - w / 2;
		Rectangle bounds = new Rectangle(x, top, w, h);

		Skin.button(graphics, bounds, false, false);

		int tx = centreX - fm.stringWidth(text) / 2;
		int ty = top + padY + fm.getAscent();
		Skin.text(graphics, text, tx, ty, PROMPT);
		return bounds;
	}

	private void drawCentered(Graphics2D graphics, String text, int centreX, int y, Color colour)
	{
		FontMetrics fm = graphics.getFontMetrics();
		int x = centreX - fm.stringWidth(text) / 2;
		Skin.text(graphics, text, x, y, colour);
	}


	/** Drop every card without revealing anything — for shutdown / logout. */
	public void clear()
	{
		cards.clear();
		// Force the next sync to rebuild rather than trust the cached text.
		lastPendingRaw = null;
		// Drop any pending batch request and stale button bounds so they can't fire
		// into the next session.
		batchRevealRequest = 0;
		revealFiveBounds = null;
		revealAllBounds = null;
	}
}
