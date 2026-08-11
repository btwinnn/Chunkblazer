package com.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
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
	/** Fade-out once the player dismisses a face-up card. */
	private static final long EXIT_DURATION = 320;

	/**
	 * Preferred card width. The art is 356x200, so this draws it at ~67% — small enough
	 * that five fit across a normal resizable viewport, large enough that the task name
	 * in the body panel stays readable.
	 */
	private static final int CARD_WIDTH = 240;
	private static final int CARD_GAP = 14;

	/**
	 * Below this width the body panel can no longer hold a legible task name, so the row
	 * wraps instead of shrinking further. Five cards in one row needs ~1250px of
	 * viewport; a fixed-mode client has 512. Without wrapping those five would be
	 * squeezed to ~80px each — technically visible, actually useless.
	 */
	private static final int MIN_LEGIBLE_WIDTH = 150;
	private static final int ROW_GAP = 12;

	/**
	 * Never more than three across, even when the viewport could fit five.
	 *
	 * <p>A roll is 4-5 tasks, and five 240px cards in a line want ~1250px — so on any
	 * ordinary window one long row means shrinking every card to keep it on screen. Two
	 * balanced rows (3+2, or 2+2 for a four-task roll) keep the cards at full size and
	 * stay centred, which is what makes this degrade gracefully as the client window
	 * scales down instead of falling off a cliff at some width.
	 */
	private static final int MAX_PER_ROW = 3;
	/** Used only when art is missing — the drawn fallback needs some shape. */
	private static final double FALLBACK_ASPECT = 202.0 / 344.0;

	// Body-panel geometry, MEASURED off the shipped art rather than guessed: the panel
	// is the largest contiguous flat-grey run in front_<tier>.png, which sits at
	// y 90..180 and x 44..312 of 356x200 — identical across all five tiers. The panel is
	// below the title plate, so its centre is NOT the card's centre.
	// If the art is ever redrawn, re-measure rather than nudging these by eye.
	private static final double BODY_CENTRE_FRACTION = 0.675;  // (90+180)/2 / 200
	private static final double BODY_HEIGHT_FRACTION = 0.450;  // (180-90)  / 200
	/** 0.753 measured, trimmed slightly so text never touches the panel bevel. */
	private static final double BODY_WIDTH_FRACTION = 0.720;

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

	/**
	 * One card and where it is in its life.
	 *
	 * <p>FACE_DOWN → (click) → turning → FACE_UP → (click) → leaving → gone. A card sits
	 * FACE_UP for as long as the player wants: there is nothing to read on a card that
	 * has already faded, and a fixed hold either rushes a slow reader or annoys a fast
	 * one. Dismissal is a second click on the card.
	 *
	 * <p>The task is activated at the END OF THE TURN, not on dismissal — the reveal is
	 * what earns it. Leaving a card up therefore parks nothing: the task is already in
	 * the list, and the card is just a receipt you close when you've read it.
	 */
	private static class Card
	{
		final String taskId;
		final String taskName;
		final TaskCardTier tier;
		Rectangle bounds = new Rectangle();
		/** Wall-clock ms the turn began, or -1 while still face down. */
		long flipStart = -1;
		/** Wall-clock ms the player dismissed it, or -1 while still on screen. */
		long dismissStart = -1;
		/** Set once the task has been handed to the plugin, so it happens exactly once. */
		boolean activated;

		Card(String taskId, String taskName, TaskCardTier tier)
		{
			this.taskId = taskId;
			this.taskName = taskName;
			this.tier = tier;
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

	/**
	 * Load card art, tolerating the case the file happens to be saved in.
	 *
	 * <p>Windows is case-insensitive so {@code Front_Easy.PNG} looks identical to
	 * {@code front_easy.png} in Explorer, but the classpath lookup is not — the art would
	 * simply never load and the card would silently fall back to a placeholder with no
	 * error to explain it. Hand-saved assets are exactly where that happens, so try the
	 * obvious variants rather than making a filename typo look like a code bug.
	 */
	private BufferedImage loadImage(String path)
	{
		for (String candidate : caseVariants(path))
		{
			try (InputStream is = getClass().getResourceAsStream(candidate))
			{
				if (is != null)
				{
					BufferedImage image = ImageIO.read(is);
					if (image != null)
					{
						return image;
					}
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to load task card art {}: {}", candidate, e.getMessage());
			}
		}
		return null;
	}

	/** e.g. Task_Cards/front_easy.png -> also try .PNG, Front_easy.png, Front_easy.PNG. */
	private static List<String> caseVariants(String path)
	{
		List<String> variants = new ArrayList<>();
		variants.add(path);

		int dot = path.lastIndexOf('.');
		String stem = dot < 0 ? path : path.substring(0, dot);
		String ext = dot < 0 ? "" : path.substring(dot);
		variants.add(stem + ext.toUpperCase());

		int slash = stem.lastIndexOf('/');
		if (slash >= 0 && slash + 1 < stem.length())
		{
			String dir = stem.substring(0, slash + 1);
			String file = stem.substring(slash + 1);
			String capitalised = dir + Character.toUpperCase(file.charAt(0)) + file.substring(1);
			variants.add(capitalised + ext);
			variants.add(capitalised + ext.toUpperCase());
		}
		return variants;
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
			if (!card.bounds.contains(x, y))
			{
				continue;
			}
			if (card.isFaceDown())
			{
				card.flipStart = System.currentTimeMillis();
				return true;
			}
			if (card.isFaceUp())
			{
				card.dismissStart = System.currentTimeMillis();
				return true;
			}
			// Mid-turn or already leaving: swallow the click so an impatient
			// double-click doesn't fall through to the game world underneath.
			return true;
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

	/**
	 * How many cards go on a row, given how many there are and how much width there is.
	 *
	 * <p>Two rules, in order. Never more than {@link #MAX_PER_ROW} across — five cards in
	 * a line only fits a very wide client, and forcing it shrinks every card. Then
	 * BALANCE: having settled on a row count, spread the cards evenly across it, so five
	 * reads 3+2 and four reads 2+2 rather than 3+1.
	 *
	 * <p>Package-private and static purely so this is testable without a Graphics2D.
	 */
	static int[] rowSizes(int count, int availableWidth)
	{
		if (count <= 0)
		{
			return new int[]{0};
		}
		int cap = Math.min(MAX_PER_ROW, count);
		// If even that many can't hold the legibility floor, narrow further.
		int fits = Math.max(1, (availableWidth + CARD_GAP) / (MIN_LEGIBLE_WIDTH + CARD_GAP));
		cap = Math.min(cap, fits);

		int rows = (count + cap - 1) / cap;
		// Spread the remainder across the LEADING rows rather than letting it pile onto
		// the last one. A single perRow figure can't express this: seven cards at three
		// across is 3+2+2, not 3+3+1, and the stranded single is what looks broken.
		int base = count / rows;
		int extra = count % rows;

		int[] sizes = new int[rows];
		for (int i = 0; i < rows; i++)
		{
			sizes[i] = base + (i < extra ? 1 : 0);
		}
		return sizes;
	}

	/**
	 * Lay the cards out centred, shrinking to fit the viewport and wrapping onto extra
	 * rows once shrinking alone would make them illegible.
	 */
	private void layout(Rectangle viewport)
	{
		int count = cards.size();
		if (count == 0)
		{
			return;
		}

		int maxWidth = viewport.width - 40;
		int[] sizes = rowSizes(count, maxWidth);

		int widest = 1;
		for (int size : sizes)
		{
			widest = Math.max(widest, size);
		}

		int cardWidth = Math.min(CARD_WIDTH, (maxWidth - (widest - 1) * CARD_GAP) / widest);
		cardWidth = Math.max(48, cardWidth); // a truly tiny viewport still gets something
		int cardHeight = (int) Math.round(cardWidth * aspectRatio());

		int blockHeight = sizes.length * cardHeight + (sizes.length - 1) * ROW_GAP;
		int y = viewport.y + (viewport.height - blockHeight) / 2;

		int index = 0;
		for (int size : sizes)
		{
			int rowWidth = size * cardWidth + (size - 1) * CARD_GAP;
			int x = viewport.x + (viewport.width - rowWidth) / 2;

			for (int i = 0; i < size && index < count; i++, index++)
			{
				cards.get(index).bounds = new Rectangle(x, y, cardWidth, cardHeight);
				x += cardWidth + CARD_GAP;
			}
			y += cardHeight + ROW_GAP;
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

		graphics.setColor(CARD_SHADOW);
		graphics.fillRect(b.x + 3, b.y + 4, b.width, b.height);

		BufferedImage art = showFront ? fronts.get(card.tier) : backs.get(card.tier);
		boolean hasArt = art != null;
		if (hasArt)
		{
			graphics.drawImage(art, b.x, b.y, b.width, b.height, null);
		}
		else
		{
			drawFallbackCard(graphics, card, b, showFront);
		}

		if (showFront)
		{
			drawCardFace(graphics, card, b, hasArt);
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

	/**
	 * Task name wrapped into the card's body panel.
	 *
	 * <p>The tier label is drawn ONLY on the placeholder card. The real front art
	 * already has "Easy Task" / "Elite Task" painted into its title plate, so drawing it
	 * again would stack our text on top of theirs.
	 *
	 * <p>{@link #BODY_CENTRE_FRACTION} is where the art's body panel sits, measured down
	 * from the top of the card — the panel is below the title plate, so it is NOT the
	 * card's centre. Expect to tune this against the real PNGs.
	 */
	private void drawCardFace(Graphics2D graphics, Card card, Rectangle b, boolean hasArt)
	{
		if (!hasArt)
		{
			graphics.setFont(FontManager.getRunescapeBoldFont());
			drawCentered(graphics, card.tier.getDisplayName() + " Task",
				b.x + b.width / 2, b.y + Math.max(14, b.height / 8), TEXT);
		}

		graphics.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = graphics.getFontMetrics();

		int textWidth = hasArt
			? (int) Math.round(b.width * BODY_WIDTH_FRACTION)
			: b.width - 20;

		List<String> lines = wrap(card.taskName, fm, textWidth);
		int lineHeight = fm.getHeight();

		// Clamp to what the panel can actually hold. Task names run from "Defeat a Rat"
		// to "Defeat a Level 63 Ogre on Task", and an overlong one would otherwise spill
		// out of the panel and across the frame.
		int panelHeight = (int) Math.round(b.height * (hasArt ? BODY_HEIGHT_FRACTION : 0.8));
		int maxLines = Math.max(1, panelHeight / lineHeight);
		if (lines.size() > maxLines)
		{
			lines = new ArrayList<>(lines.subList(0, maxLines));
			int last = maxLines - 1;
			lines.set(last, ellipsise(lines.get(last), fm, textWidth));
		}

		double centreFraction = hasArt ? BODY_CENTRE_FRACTION : 0.5;
		int bodyCentreY = b.y + (int) Math.round(b.height * centreFraction);
		int y = bodyCentreY - (lines.size() * lineHeight) / 2 + fm.getAscent();

		for (String line : lines)
		{
			drawCentered(graphics, line, b.x + b.width / 2, y, TEXT);
			y += lineHeight;
		}
	}

	/** Trim a line until it plus an ellipsis fits the panel. */
	private static String ellipsise(String line, FontMetrics fm, int maxWidth)
	{
		String text = line;
		while (text.length() > 1 && fm.stringWidth(text + "...") > maxWidth)
		{
			text = text.substring(0, text.length() - 1);
		}
		return text.trim() + "...";
	}

	private void drawPrompt(Graphics2D graphics, Rectangle viewport)
	{
		int faceDown = 0;
		int faceUp = 0;
		for (Card card : cards)
		{
			if (card.isFaceDown())
			{
				faceDown++;
			}
			else if (card.isFaceUp())
			{
				faceUp++;
			}
		}

		String text;
		if (faceDown > 0)
		{
			text = faceDown == 1
				? "Click the card to reveal your task"
				: "Click a card to reveal your task (" + faceDown + " left)";
		}
		else if (faceUp > 0)
		{
			// Nothing is waiting on this — the tasks are already yours. Say so, so the
			// card doesn't read as something still to be completed.
			text = faceUp == 1
				? "Task added. Click the card to dismiss it."
				: "Tasks added. Click a card to dismiss it.";
		}
		else
		{
			return;
		}

		graphics.setFont(FontManager.getRunescapeBoldFont());
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
