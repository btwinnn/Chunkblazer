package net.runelite.client.plugins.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * The task-roll reveal: freshly rolled tasks arrive as face-down cards in the middle of
 * the viewport and stay there until the player flips them.
 *
 * <p><b>Why this exists as a gate, not just decoration.</b> A rolled task is not added to
 * the active list — and is not registered with any tracking module — until its card is
 * turned over. That is the whole point of the feature: the roll should feel like opening
 * a pack rather than silently gaining five list entries. The pending set lives in config
 * ({@code unrevealedTasks}), so cards left unflipped are still waiting after a relog.
 *
 * <p><b>What flipping does NOT do.</b> It does not decide the task. The roll itself is
 * already committed to {@code regionRolledTasks} the moment it happens, so refusing to
 * flip a card you don't like cannot reroll it — the card is a reveal, never a gamble.
 *
 * <p>Art lives in {@code Task_Cards/} ({@code front_<tier>.png} / {@code back_<tier>.png},
 * see {@link TaskCardTier}). Missing art is drawn as a flat tier-coloured card rather
 * than skipped, so the flow is testable before the assets land.
 */
@Slf4j
@Singleton
public class TaskCardOverlay extends Overlay
{
	/** How long a card takes to turn over, milliseconds. */
	private static final long FLIP_DURATION = 420;
	/** How long the face is held after the flip before the card leaves. */
	private static final long HOLD_DURATION = 900;
	/** Fade-out after the hold. */
	private static final long EXIT_DURATION = 320;

	private static final long FLIP_END = FLIP_DURATION;
	private static final long HOLD_END = FLIP_END + HOLD_DURATION;
	private static final long EXIT_END = HOLD_END + EXIT_DURATION;

	/** Card footprint when art is present; art is scaled to fit this width. */
	private static final int CARD_WIDTH = 172;
	private static final int CARD_GAP = 14;
	/** Used only when art is missing — the drawn fallback needs some shape. */
	private static final double FALLBACK_ASPECT = 202.0 / 344.0;

	private static final Color TEXT = new Color(255, 255, 255);
	private static final Color TEXT_SHADOW = new Color(0, 0, 0, 190);
	private static final Color PROMPT = new Color(235, 235, 235, 225);
	private static final Color CARD_SHADOW = new Color(0, 0, 0, 110);

	private final Client client;
	private final ChunkBlazerConfig config;
	private final ChunkBlazerPlugin plugin;

	private final Map<TaskCardTier, BufferedImage> fronts = new EnumMap<>(TaskCardTier.class);
	private final Map<TaskCardTier, BufferedImage> backs = new EnumMap<>(TaskCardTier.class);

	/** Cards currently on screen, in draw order. Rebuilt from the plugin's pending set. */
	private final List<Card> cards = new ArrayList<>();

	/** Raw pending-set text as of the last rebuild — see syncCards(). */
	private String lastPendingRaw = null;

	/** Hit-test needs the last laid-out bounds; render is the only thing that knows them. */
	private static class Card
	{
		final String taskId;
		final String taskName;
		final TaskCardTier tier;
		Rectangle bounds = new Rectangle();
		/** Wall-clock ms when the player clicked it, or -1 while face down. */
		long flipStart = -1;

		Card(String taskId, String taskName, TaskCardTier tier)
		{
			this.taskId = taskId;
			this.taskName = taskName;
			this.tier = tier;
		}

		boolean isFlipping()
		{
			return flipStart > 0;
		}

		long elapsed()
		{
			return System.currentTimeMillis() - flipStart;
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
		loadArt();
	}

	private void loadArt()
	{
		for (TaskCardTier tier : TaskCardTier.values())
		{
			BufferedImage front = loadImage(tier.getFrontAsset());
			BufferedImage back = loadImage(tier.getBackAsset());
			if (front != null)
			{
				fronts.put(tier, front);
			}
			if (back != null)
			{
				backs.put(tier, back);
			}
		}
		if (fronts.isEmpty() && backs.isEmpty())
		{
			log.info("[CHUNKBLAZER] No task card art found under Task_Cards/ — drawing placeholder cards");
		}
	}

	private BufferedImage loadImage(String path)
	{
		try (InputStream is = getClass().getResourceAsStream(path))
		{
			return is == null ? null : ImageIO.read(is);
		}
		catch (Exception e)
		{
			log.warn("Failed to load task card art {}: {}", path, e.getMessage());
			return null;
		}
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
		// Front-most first: cards are drawn left to right and don't overlap, but a
		// reverse scan is still the correct hit-test order if that ever changes.
		for (int i = cards.size() - 1; i >= 0; i--)
		{
			Card card = cards.get(i);
			if (!card.isFlipping() && card.bounds.contains(x, y))
			{
				card.flipStart = System.currentTimeMillis();
				return true;
			}
		}
		return false;
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

		layout(viewport);

		for (Card card : cards)
		{
			drawCard(graphics, card);
		}

		drawPrompt(graphics, viewport);
		return null;
	}

	/**
	 * Rebuild the card list from the plugin's pending set, preserving the flip state of
	 * cards already on screen, so a roll that happens while the overlay is up simply
	 * appears.
	 *
	 * <p>Guarded by a string compare against the last seen value. render() runs every
	 * frame, and parsing the pending list per frame would mean a config read, a split
	 * and a task lookup per id, forever — this plugin has already hard-locked a client
	 * once by doing per-repaint work that looked individually cheap (see
	 * ChunkBlazerPlugin#tasksById). The pending set only changes on a roll or a flip.
	 */
	private void syncCards()
	{
		String raw = config.unrevealedTasks();
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

		cards.removeIf(card -> !pending.contains(card.taskId) && !card.isFlipping());

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
				log.warn("[CHUNKBLAZER] unrevealed task '{}' has no definition — discarding", taskId);
				plugin.revealTaskCard(taskId);
				continue;
			}
			cards.add(new Card(taskId, task.getName(), TaskCardTier.fromTask(task)));
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
	 * Hand finished cards to the plugin. The task only becomes active here — at the end
	 * of the animation — so the reveal and the list update read as one event.
	 */
	private void retireFinishedCards()
	{
		Iterator<Card> it = cards.iterator();
		while (it.hasNext())
		{
			Card card = it.next();
			if (card.isFlipping() && card.elapsed() >= EXIT_END)
			{
				it.remove();
				plugin.revealTaskCard(card.taskId);
			}
		}
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

	/** Centre the row, shrinking the cards if the viewport is too narrow to hold them. */
	private void layout(Rectangle viewport)
	{
		int count = cards.size();
		int cardWidth = CARD_WIDTH;
		int totalWidth = count * cardWidth + (count - 1) * CARD_GAP;
		int maxWidth = viewport.width - 40;

		if (totalWidth > maxWidth && count > 0)
		{
			cardWidth = Math.max(64, (maxWidth - (count - 1) * CARD_GAP) / count);
			totalWidth = count * cardWidth + (count - 1) * CARD_GAP;
		}

		int cardHeight = (int) Math.round(cardWidth * aspectRatio());
		int x = viewport.x + (viewport.width - totalWidth) / 2;
		int y = viewport.y + (viewport.height - cardHeight) / 2;

		for (Card card : cards)
		{
			card.bounds = new Rectangle(x, y, cardWidth, cardHeight);
			x += cardWidth + CARD_GAP;
		}
	}

	/** Height-to-width ratio taken from the real art when it's present. */
	private double aspectRatio()
	{
		for (BufferedImage img : backs.values())
		{
			if (img != null && img.getWidth() > 0)
			{
				return (double) img.getHeight() / img.getWidth();
			}
		}
		for (BufferedImage img : fronts.values())
		{
			if (img != null && img.getWidth() > 0)
			{
				return (double) img.getHeight() / img.getWidth();
			}
		}
		return FALLBACK_ASPECT;
	}

	private void drawCard(Graphics2D graphics, Card card)
	{
		long elapsed = card.isFlipping() ? card.elapsed() : 0;

		// A 2D card flip is a horizontal squash: the card narrows to nothing at the
		// halfway point, and that is where back swaps to front.
		double scaleX = 1.0;
		boolean showFront = false;
		float alpha = 1f;

		if (card.isFlipping())
		{
			if (elapsed < FLIP_END)
			{
				double progress = (double) elapsed / FLIP_DURATION;
				showFront = progress >= 0.5;
				scaleX = Math.abs(1.0 - 2.0 * progress);
				// Never fully degenerate — a zero-width draw is invisible AND a
				// zero-determinant transform, which some pipelines reject outright.
				scaleX = Math.max(scaleX, 0.02);
			}
			else if (elapsed < HOLD_END)
			{
				showFront = true;
			}
			else
			{
				showFront = true;
				alpha = 1f - (float) (elapsed - HOLD_END) / EXIT_DURATION;
				alpha = Math.max(0f, Math.min(1f, alpha));
			}
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

		graphics.setColor(CARD_SHADOW);
		graphics.fillRect(b.x + 3, b.y + 4, b.width, b.height);

		BufferedImage art = showFront ? fronts.get(card.tier) : backs.get(card.tier);
		if (art != null)
		{
			graphics.drawImage(art, b.x, b.y, b.width, b.height, null);
		}
		else
		{
			drawFallbackCard(graphics, card, b, showFront);
		}

		if (showFront)
		{
			drawCardFace(graphics, card, b);
		}

		graphics.setTransform(originalTransform);
		graphics.setComposite(originalComposite);
	}

	/** Flat tier-coloured stand-in so the flow works before the art is dropped in. */
	private void drawFallbackCard(Graphics2D graphics, Card card, Rectangle b, boolean showFront)
	{
		Color accent = card.tier.getAccent();
		graphics.setColor(showFront ? new Color(58, 58, 58) : accent.darker().darker());
		graphics.fillRect(b.x, b.y, b.width, b.height);
		graphics.setColor(accent);
		graphics.drawRect(b.x, b.y, b.width - 1, b.height - 1);
		graphics.drawRect(b.x + 2, b.y + 2, b.width - 5, b.height - 5);

		if (!showFront)
		{
			graphics.setFont(FontManager.getRunescapeBoldFont());
			drawCentered(graphics, "ChunkBlazer", b.x + b.width / 2, b.y + b.height / 2, accent);
		}
	}

	/** Tier plate along the top, task name wrapped into the body panel. */
	private void drawCardFace(Graphics2D graphics, Card card, Rectangle b)
	{
		graphics.setFont(FontManager.getRunescapeBoldFont());
		drawCentered(graphics, card.tier.getDisplayName() + " Task",
			b.x + b.width / 2, b.y + Math.max(14, b.height / 8), TEXT);

		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = graphics.getFontMetrics();

		List<String> lines = wrap(card.taskName, fm, b.width - 20);
		int lineHeight = fm.getHeight();
		int blockHeight = lines.size() * lineHeight;
		int y = b.y + b.height / 2 - blockHeight / 2 + fm.getAscent();

		for (String line : lines)
		{
			drawCentered(graphics, line, b.x + b.width / 2, y, TEXT);
			y += lineHeight;
		}
	}

	private void drawPrompt(Graphics2D graphics, Rectangle viewport)
	{
		int remaining = 0;
		for (Card card : cards)
		{
			if (!card.isFlipping())
			{
				remaining++;
			}
		}
		if (remaining == 0)
		{
			return;
		}

		graphics.setFont(FontManager.getRunescapeBoldFont());
		String text = remaining == 1
			? "Click the card to reveal your task"
			: "Click a card to reveal your task (" + remaining + " left)";

		Rectangle first = cards.get(0).bounds;
		drawCentered(graphics, text, viewport.x + viewport.width / 2, first.y - 12, PROMPT);
	}

	private void drawCentered(Graphics2D graphics, String text, int centreX, int y, Color colour)
	{
		FontMetrics fm = graphics.getFontMetrics();
		int x = centreX - fm.stringWidth(text) / 2;
		graphics.setColor(TEXT_SHADOW);
		graphics.drawString(text, x + 1, y + 1);
		graphics.setColor(colour);
		graphics.drawString(text, x, y);
	}

	/** Greedy word wrap. Task names are short, so this never needs to be clever. */
	private static List<String> wrap(String text, FontMetrics fm, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		if (text == null || text.isEmpty())
		{
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(candidate) > maxWidth && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}

	/** Drop every card without revealing anything — for shutdown / logout. */
	public void clear()
	{
		cards.clear();
		// Force the next sync to rebuild rather than trust the cached text.
		lastPendingRaw = null;
	}
}
