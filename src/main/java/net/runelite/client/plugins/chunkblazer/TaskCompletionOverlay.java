package net.runelite.client.plugins.chunkblazer;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Overlay that displays a popup notification when a task is completed.
 * Features fade-in and fade-out animations.
 */
@Slf4j
@Singleton
public class TaskCompletionOverlay extends Overlay
{
	// Animation timing (milliseconds)
	private static final long DISPLAY_DURATION = 4000;  // Total time popup is visible
	private static final long FADE_IN_DURATION = 300;   // Fade in time
	private static final long FADE_OUT_DURATION = 500;  // Fade out time

	// Fonts
	private static final Font FONT_HEADER = new Font("Verdana", Font.BOLD, 11);
	private static final Font FONT_TASK_NAME = new Font("Verdana", Font.BOLD, 14);
	private static final Font FONT_DETAILS = new Font("Verdana", Font.PLAIN, 11);

	// Colors
	private static final Color COLOR_BACKGROUND = new Color(20, 20, 20, 220);
	private static final Color COLOR_HEADER = new Color(200, 200, 200);
	private static final Color COLOR_TASK_NAME = new Color(255, 215, 0);  // Gold
	private static final Color COLOR_POINTS = new Color(100, 255, 100);   // Green
	private static final Color COLOR_CATEGORY = new Color(180, 180, 180);

	// Difficulty/category colors for border
	private static final Color COLOR_COMBAT = new Color(220, 50, 50);      // Red
	private static final Color COLOR_SKILLING = new Color(50, 180, 50);    // Green
	private static final Color COLOR_OBTAIN = new Color(50, 150, 220);     // Blue
	private static final Color COLOR_DEFAULT = new Color(200, 160, 50);    // Gold

	// Box dimensions
	private static final int BOX_WIDTH = 280;
	private static final int BOX_PADDING = 12;
	private static final int BORDER_RADIUS = 8;
	private static final int BORDER_WIDTH = 2;

	private final ChunkBlazerConfig config;

	// Current popup state
	private long startTime = -1;
	private String taskName = "";
	private String category = "";
	private int pointsAwarded = 0;
	private String completionType = "";

	// Debug counter for render calls
	private int renderCallCount = 0;

	@Inject
	public TaskCompletionOverlay(ChunkBlazerConfig config)
	{
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
		log.info(">>> TaskCompletionOverlay CONSTRUCTOR called - overlay initialized");
	}

	/**
	 * Show the task completion popup.
	 *
	 * @param task The completed task
	 * @param points Points awarded for completion
	 */
	public void showTaskCompletion(NuzlockeTask task, int points)
	{
		log.info(">>> TaskCompletionOverlay.showTaskCompletion() CALLED");
		log.info(">>>   task={}, points={}", task != null ? task.getName() : "NULL", points);

		if (task == null)
		{
			log.warn(">>>   Task is NULL - returning early!");
			return;
		}

		this.taskName = task.getName();
		this.category = task.getCategory() != null ? task.getCategory() : "";
		this.completionType = task.getCompletionType() != null ? task.getCompletionType() : "";
		this.pointsAwarded = points > 0 ? points : task.getBasePoints();
		this.startTime = System.currentTimeMillis();

		log.info(">>> TaskCompletionOverlay: POPUP TRIGGERED for '{}' (+{} points)", taskName, pointsAwarded);
		log.info(">>>   startTime set to: {}", startTime);
		log.info(">>>   category={}, completionType={}", category, completionType);
	}

	/**
	 * Show a custom task completion popup.
	 */
	public void showTaskCompletion(String name, String category, String type, int points)
	{
		this.taskName = name;
		this.category = category != null ? category : "";
		this.completionType = type != null ? type : "";
		this.pointsAwarded = points;
		this.startTime = System.currentTimeMillis();

		log.info("TaskCompletionOverlay: Showing popup for '{}' (+{} points)", taskName, pointsAwarded);
	}

	/**
	 * Hide the popup immediately.
	 */
	public void hide()
	{
		startTime = -1;
	}

	/**
	 * Check if popup is currently showing.
	 */
	public boolean isShowing()
	{
		if (startTime == -1)
		{
			return false;
		}
		long elapsed = System.currentTimeMillis() - startTime;
		return elapsed < DISPLAY_DURATION;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		renderCallCount++;

		// Log every 100 render calls when popup is active, or first few calls
		if (startTime != -1 && (renderCallCount % 100 == 0 || renderCallCount <= 5))
		{
			log.info(">>> TaskCompletionOverlay.render() called #{} - startTime={}", renderCallCount, startTime);
		}

		// Check if popup should be showing
		if (startTime == -1)
		{
			return null;
		}

		// Check config - allow disabling popup
		if (!config.showTaskCompletionPopup())
		{
			log.info(">>> TaskCompletionOverlay: Config disabled popup - returning null");
			return null;
		}

		long elapsed = System.currentTimeMillis() - startTime;

		// Log the first render after popup triggered
		if (elapsed < 100)
		{
			log.info(">>> TaskCompletionOverlay: RENDERING popup! elapsed={}ms, alpha will be {}",
				elapsed, calculateAlpha(elapsed));
		}

		// Check if display duration has passed
		if (elapsed > DISPLAY_DURATION)
		{
			log.info(">>> TaskCompletionOverlay: Display duration passed ({}ms > {}ms) - hiding", elapsed, DISPLAY_DURATION);
			startTime = -1;
			return null;
		}

		// Calculate alpha for fade in/out
		float alpha = calculateAlpha(elapsed);

		// Enable antialiasing for smooth text
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

		// Calculate box height based on content
		graphics.setFont(FONT_TASK_NAME);
		FontMetrics fmTask = graphics.getFontMetrics();
		graphics.setFont(FONT_DETAILS);
		FontMetrics fmDetails = graphics.getFontMetrics();
		graphics.setFont(FONT_HEADER);
		FontMetrics fmHeader = graphics.getFontMetrics();

		int lineHeight = fmTask.getHeight();
		int boxHeight = BOX_PADDING * 2 + fmHeader.getHeight() + lineHeight + fmDetails.getHeight() + 8;

		// Apply alpha composite for fade effect
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		// Draw background
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRoundRect(0, 0, BOX_WIDTH, boxHeight, BORDER_RADIUS, BORDER_RADIUS);

		// Draw border with category color
		Color borderColor = getCategoryColor(category, completionType);
		graphics.setColor(borderColor);
		graphics.setStroke(new java.awt.BasicStroke(BORDER_WIDTH));
		graphics.drawRoundRect(1, 1, BOX_WIDTH - 2, boxHeight - 2, BORDER_RADIUS, BORDER_RADIUS);

		// Draw accent line at top
		graphics.setColor(borderColor);
		graphics.fillRoundRect(0, 0, BOX_WIDTH, 4, BORDER_RADIUS, BORDER_RADIUS);
		graphics.fillRect(0, 2, BOX_WIDTH, 2);

		int y = BOX_PADDING + fmHeader.getAscent();

		// Draw header text "TASK COMPLETED"
		graphics.setFont(FONT_HEADER);
		graphics.setColor(COLOR_HEADER);
		String headerText = "TASK COMPLETED";
		int headerWidth = fmHeader.stringWidth(headerText);
		graphics.drawString(headerText, (BOX_WIDTH - headerWidth) / 2, y);

		y += fmHeader.getHeight() + 4;

		// Draw task name (centered, possibly truncated)
		graphics.setFont(FONT_TASK_NAME);
		graphics.setColor(COLOR_TASK_NAME);
		String displayName = truncateText(taskName, fmTask, BOX_WIDTH - BOX_PADDING * 2);
		int nameWidth = fmTask.stringWidth(displayName);
		graphics.drawString(displayName, (BOX_WIDTH - nameWidth) / 2, y);

		y += lineHeight + 4;

		// Draw points and category on same line
		graphics.setFont(FONT_DETAILS);

		// Points on left
		String pointsText = "+" + pointsAwarded + " points";
		graphics.setColor(COLOR_POINTS);
		graphics.drawString(pointsText, BOX_PADDING, y);

		// Category on right
		if (!category.isEmpty())
		{
			graphics.setColor(COLOR_CATEGORY);
			int catWidth = fmDetails.stringWidth(category);
			graphics.drawString(category, BOX_WIDTH - BOX_PADDING - catWidth, y);
		}

		return new Dimension(BOX_WIDTH, boxHeight);
	}

	/**
	 * Calculate alpha value based on elapsed time for fade effects.
	 */
	private float calculateAlpha(long elapsed)
	{
		if (elapsed < FADE_IN_DURATION)
		{
			// Fade in
			return (float) elapsed / FADE_IN_DURATION;
		}
		else if (elapsed > DISPLAY_DURATION - FADE_OUT_DURATION)
		{
			// Fade out
			return (float) (DISPLAY_DURATION - elapsed) / FADE_OUT_DURATION;
		}
		else
		{
			// Fully visible
			return 1.0f;
		}
	}

	/**
	 * Get border color based on task category or completion type.
	 */
	private Color getCategoryColor(String category, String type)
	{
		if (category == null)
		{
			category = "";
		}
		if (type == null)
		{
			type = "";
		}

		String cat = category.toLowerCase();
		String t = type.toLowerCase();

		// Combat-related
		if (cat.equals("combat") || t.contains("kill") || t.equals("combat"))
		{
			return COLOR_COMBAT;
		}

		// Obtain/collection
		if (cat.equals("obtain") || t.equals("obtain"))
		{
			return COLOR_OBTAIN;
		}

		// Skilling categories
		if (cat.equals("woodcutting") || cat.equals("mining") || cat.equals("fishing") ||
			cat.equals("cooking") || cat.equals("firemaking") || cat.equals("smithing") ||
			cat.equals("crafting") || cat.equals("fletching") || cat.equals("herblore") ||
			cat.equals("agility") || cat.equals("thieving") || cat.equals("farming") ||
			cat.equals("runecrafting") || cat.equals("hunter") || cat.equals("construction") ||
			cat.equals("prayer"))
		{
			return COLOR_SKILLING;
		}

		// Equipment/Defence
		if (cat.equals("defence") || cat.equals("attack") || cat.equals("strength") ||
			cat.equals("ranged") || cat.equals("magic") || t.equals("equip"))
		{
			return COLOR_DEFAULT;
		}

		return COLOR_DEFAULT;
	}

	/**
	 * Truncate text to fit within maxWidth, adding ellipsis if needed.
	 */
	private String truncateText(String text, FontMetrics fm, int maxWidth)
	{
		if (fm.stringWidth(text) <= maxWidth)
		{
			return text;
		}

		String ellipsis = "...";
		int ellipsisWidth = fm.stringWidth(ellipsis);

		for (int i = text.length() - 1; i > 0; i--)
		{
			String truncated = text.substring(0, i);
			if (fm.stringWidth(truncated) + ellipsisWidth <= maxWidth)
			{
				return truncated + ellipsis;
			}
		}

		return ellipsis;
	}
}
