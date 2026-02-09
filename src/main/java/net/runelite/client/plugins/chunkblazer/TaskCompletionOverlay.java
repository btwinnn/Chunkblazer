package net.runelite.client.plugins.chunkblazer;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
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

	// Fonts - RuneScape style
	private static final Font FONT_TASK_NAME = new Font("Verdana", Font.BOLD, 16);
	private static final Font FONT_LABELS = new Font("Verdana", Font.BOLD, 12);
	private static final Font FONT_POINTS = new Font("Verdana", Font.BOLD, 20);

	// Colors
	private static final Color COLOR_BACKGROUND = new Color(58, 50, 43, 240);  // RS brown
	private static final Color COLOR_BORDER = new Color(82, 71, 61);           // Darker border
	private static final Color COLOR_BORDER_OUTER = new Color(40, 35, 30);     // Outer edge
	private static final Color COLOR_TASK_NAME = new Color(255, 255, 255);     // White for task name
	private static final Color COLOR_LABELS = new Color(255, 152, 31);         // Orange for labels
	private static final Color COLOR_POINTS_TEXT = new Color(255, 255, 255);   // White for points

	// Difficulty colors
	private static final Color COLOR_EASY = new Color(0, 255, 0);              // Green
	private static final Color COLOR_INTERMEDIATE = new Color(255, 255, 0);    // Yellow
	private static final Color COLOR_HARD = new Color(255, 165, 0);            // Orange
	private static final Color COLOR_ELITE = new Color(255, 0, 0);             // Red
	private static final Color COLOR_MASTER = new Color(148, 0, 211);          // Purple

	// Box dimensions (for programmatic fallback)
	private static final int BOX_WIDTH = 320;
	private static final int BOX_PADDING = 16;
	private static final int BORDER_WIDTH = 4;

	private final ChunkBlazerConfig config;
	private final ItemManager itemManager;

	// Region-specific popup images
	private BufferedImage misthalinPopupImage;

	// Current popup state
	private long startTime = -1;
	private String taskName = "";
	private String category = "";
	private int pointsAwarded = 0;
	private String completionType = "";
	private String regionName = "";  // The chunk/region name
	private String area = "";        // The area (Misthalin, Asgarnia, etc.)
	private int itemId = -1;         // Item ID for display (if task has required_items)

	// Debug counter for render calls
	private int renderCallCount = 0;

	@Inject
	public TaskCompletionOverlay(ChunkBlazerConfig config, ItemManager itemManager)
	{
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
		loadImages();
		log.info(">>> TaskCompletionOverlay CONSTRUCTOR called - overlay initialized");
	}

	/**
	 * Load popup background images for each region.
	 */
	private void loadImages()
	{
		try
		{
			InputStream is = getClass().getResourceAsStream("images/misthalin_task_popup.png");
			if (is != null)
			{
				misthalinPopupImage = ImageIO.read(is);
				log.info("Loaded Misthalin popup image: {}x{}",
					misthalinPopupImage.getWidth(), misthalinPopupImage.getHeight());
			}
			else
			{
				log.warn("Could not find Misthalin popup image");
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load popup images", e);
		}
	}

	/**
	 * Get difficulty name based on points.
	 * 1 = Easy, 2 = Intermediate, 3 = Hard, 4 = Elite, 5 = Master
	 */
	private String getDifficultyName(int points)
	{
		switch (points)
		{
			case 1:
				return "Easy";
			case 2:
				return "Intermediate";
			case 3:
				return "Hard";
			case 4:
				return "Elite";
			case 5:
			default:
				return points >= 5 ? "Master" : "Easy";
		}
	}

	/**
	 * Get difficulty color based on points.
	 */
	private Color getDifficultyColor(int points)
	{
		switch (points)
		{
			case 1:
				return COLOR_EASY;
			case 2:
				return COLOR_INTERMEDIATE;
			case 3:
				return COLOR_HARD;
			case 4:
				return COLOR_ELITE;
			case 5:
			default:
				return points >= 5 ? COLOR_MASTER : COLOR_EASY;
		}
	}

	/**
	 * Show the task completion popup.
	 *
	 * @param task The completed task
	 * @param points Points awarded for completion
	 */
	public void showTaskCompletion(NuzlockeTask task, int points)
	{
		showTaskCompletion(task, points, null, null);
	}

	/**
	 * Show the task completion popup with region name.
	 *
	 * @param task The completed task
	 * @param points Points awarded for completion
	 * @param regionName The region/chunk name where the task was assigned
	 */
	public void showTaskCompletion(NuzlockeTask task, int points, String regionName)
	{
		showTaskCompletion(task, points, regionName, null);
	}

	/**
	 * Show the task completion popup with region name and area.
	 *
	 * @param task The completed task
	 * @param points Points awarded for completion
	 * @param regionName The region/chunk name where the task was assigned
	 * @param area The area name (Misthalin, Asgarnia, etc.)
	 */
	public void showTaskCompletion(NuzlockeTask task, int points, String regionName, String area)
	{
		log.info(">>> TaskCompletionOverlay.showTaskCompletion() CALLED");
		log.info(">>>   task={}, points={}, region={}, area={}", task != null ? task.getName() : "NULL", points, regionName, area);

		if (task == null)
		{
			log.warn(">>>   Task is NULL - returning early!");
			return;
		}

		this.taskName = task.getName();
		this.category = task.getCategory() != null ? task.getCategory() : "";
		this.completionType = task.getCompletionType() != null ? task.getCompletionType() : "";
		this.pointsAwarded = points > 0 ? points : task.getBasePoints();
		this.regionName = regionName != null ? regionName : "Unknown Region";
		this.area = area != null ? area : "";

		// Extract item ID from required_items if available
		this.itemId = -1;
		List<RequiredItem> requiredItems = task.getRequiredItems();
		if (requiredItems != null && !requiredItems.isEmpty())
		{
			int firstItemId = requiredItems.get(0).getFirstItemId();
			if (firstItemId > 0)
			{
				this.itemId = firstItemId;
				log.info(">>>   Found item ID: {}", this.itemId);
			}
		}

		// TEST: Force teak plank (8780) for testing item display
		// Remove this line after testing!
		this.itemId = 8780;  // Teak plank
		log.info(">>>   TEST: Forcing item ID to teak plank (8780)");

		this.startTime = System.currentTimeMillis();

		log.info(">>> TaskCompletionOverlay: POPUP TRIGGERED for '{}' (+{} points) in {} ({}) with itemId={}",
			taskName, pointsAwarded, this.regionName, this.area, this.itemId);
	}

	/**
	 * Show a custom task completion popup.
	 */
	public void showTaskCompletion(String name, String category, String type, int points)
	{
		showTaskCompletion(name, category, type, points, null, null);
	}

	/**
	 * Show a custom task completion popup with region name.
	 */
	public void showTaskCompletion(String name, String category, String type, int points, String regionName)
	{
		showTaskCompletion(name, category, type, points, regionName, null);
	}

	/**
	 * Show a custom task completion popup with region name and area.
	 */
	public void showTaskCompletion(String name, String category, String type, int points, String regionName, String area)
	{
		this.taskName = name;
		this.category = category != null ? category : "";
		this.completionType = type != null ? type : "";
		this.pointsAwarded = points;
		this.regionName = regionName != null ? regionName : "Unknown Region";
		this.area = area != null ? area : "";
		this.startTime = System.currentTimeMillis();

		log.info("TaskCompletionOverlay: Showing popup for '{}' (+{} points) in {} ({})", taskName, pointsAwarded, this.regionName, this.area);
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

		// Apply alpha composite for fade effect
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

		// Check if we should use image-based rendering for Misthalin
		if ("Misthalin".equalsIgnoreCase(area) && misthalinPopupImage != null)
		{
			return renderWithImage(graphics, misthalinPopupImage);
		}

		// Fall back to programmatic rendering for other areas
		return renderProgrammatic(graphics);
	}

	/**
	 * Render popup using a background image.
	 */
	private Dimension renderWithImage(Graphics2D graphics, BufferedImage bgImage)
	{
		int imgWidth = bgImage.getWidth();
		int imgHeight = bgImage.getHeight();

		// Draw the background image
		graphics.drawImage(bgImage, 0, 0, null);

		// Get font metrics
		graphics.setFont(FONT_TASK_NAME);
		FontMetrics fmTaskName = graphics.getFontMetrics();
		graphics.setFont(FONT_LABELS);
		FontMetrics fmLabels = graphics.getFontMetrics();
		graphics.setFont(FONT_POINTS);
		FontMetrics fmPoints = graphics.getFontMetrics();

		// Draw task name at top (in the empty space above "Task Difficulty")
		// Position it roughly 35 pixels from top, centered
		graphics.setFont(FONT_TASK_NAME);
		graphics.setColor(COLOR_TASK_NAME);
		String displayName = truncateText(taskName, fmTaskName, imgWidth - 80);
		int nameWidth = fmTaskName.stringWidth(displayName);
		int nameX = (imgWidth - nameWidth) / 2;
		int nameY = 55;  // Approximate Y position for task name
		graphics.drawString(displayName, nameX, nameY);

		// Draw difficulty value after "Task Difficulty:" label
		// The label is at approximately y=107 in the image
		graphics.setFont(FONT_LABELS);
		String difficultyValue = getDifficultyName(pointsAwarded);
		graphics.setColor(getDifficultyColor(pointsAwarded));
		// Position after the "Task Difficulty:" text (roughly x=370)
		int diffX = 372;
		int diffY = 107;
		graphics.drawString(difficultyValue, diffX, diffY);

		// Draw region name after "Task Region Assigned:" label
		// The label is at approximately y=132 in the image
		String regionDisplay = regionName;
		if (regionDisplay.contains("("))
		{
			regionDisplay = regionDisplay.substring(0, regionDisplay.indexOf("(")).trim();
		}
		graphics.setColor(COLOR_TASK_NAME);  // White
		int regionX = 430;  // Position after "Task Region Assigned:"
		int regionY = 132;
		graphics.drawString(regionDisplay, regionX, regionY);

		// Draw points value after "Points Earned:" label
		// The label ends around x=430, y=175
		graphics.setFont(FONT_POINTS);
		String pointsValue = String.valueOf(pointsAwarded);
		graphics.setColor(COLOR_POINTS_TEXT);
		int pointsX = 435;
		int pointsY = 175;
		graphics.drawString(pointsValue, pointsX, pointsY);

		// Draw item image if we have an item ID
		if (itemId > 0 && itemManager != null)
		{
			BufferedImage itemImage = itemManager.getImage(itemId);
			if (itemImage != null)
			{
				// Draw item image in the top-left area of the popup
				// Position it nicely - maybe to the left of the task name
				int itemX = 30;  // Left side padding
				int itemY = 35;  // Near top
				graphics.drawImage(itemImage, itemX, itemY, null);
				log.debug("Drew item image for ID {} at ({}, {})", itemId, itemX, itemY);
			}
		}

		return new Dimension(imgWidth, imgHeight);
	}

	/**
	 * Render popup programmatically (fallback for areas without custom images).
	 */
	private Dimension renderProgrammatic(Graphics2D graphics)
	{
		// Get font metrics
		graphics.setFont(FONT_TASK_NAME);
		FontMetrics fmTaskName = graphics.getFontMetrics();
		graphics.setFont(FONT_LABELS);
		FontMetrics fmLabels = graphics.getFontMetrics();
		graphics.setFont(FONT_POINTS);
		FontMetrics fmPoints = graphics.getFontMetrics();

		// Calculate box height:
		// Task name + spacing + difficulty line + region line + spacing + points line
		int lineSpacing = 6;
		int boxHeight = BOX_PADDING + fmTaskName.getHeight() + lineSpacing
			+ fmLabels.getHeight() + lineSpacing  // Task Difficulty line
			+ fmLabels.getHeight() + lineSpacing  // Task Region line
			+ fmPoints.getHeight() + BOX_PADDING; // Points Earned line

		// Draw outer border (dark edge)
		graphics.setColor(COLOR_BORDER_OUTER);
		graphics.fillRect(0, 0, BOX_WIDTH, boxHeight);

		// Draw inner border
		graphics.setColor(COLOR_BORDER);
		graphics.fillRect(BORDER_WIDTH / 2, BORDER_WIDTH / 2, BOX_WIDTH - BORDER_WIDTH, boxHeight - BORDER_WIDTH);

		// Draw background
		graphics.setColor(COLOR_BACKGROUND);
		graphics.fillRect(BORDER_WIDTH, BORDER_WIDTH, BOX_WIDTH - BORDER_WIDTH * 2, boxHeight - BORDER_WIDTH * 2);

		// Draw dotted inner border (RS style)
		graphics.setColor(COLOR_BORDER);
		graphics.setStroke(new java.awt.BasicStroke(1, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
			10, new float[]{2, 2}, 0));
		graphics.drawRect(BORDER_WIDTH + 2, BORDER_WIDTH + 2, BOX_WIDTH - BORDER_WIDTH * 2 - 4, boxHeight - BORDER_WIDTH * 2 - 4);

		int y = BOX_PADDING + BORDER_WIDTH;

		// Draw task name at top (white, centered)
		graphics.setFont(FONT_TASK_NAME);
		graphics.setColor(COLOR_TASK_NAME);
		String displayName = truncateText(taskName, fmTaskName, BOX_WIDTH - BOX_PADDING * 2);
		int nameWidth = fmTaskName.stringWidth(displayName);
		y += fmTaskName.getAscent();
		graphics.drawString(displayName, (BOX_WIDTH - nameWidth) / 2, y);

		y += fmTaskName.getDescent() + lineSpacing + 4;

		// Draw "Task Difficulty: [difficulty]" (orange label, colored value)
		graphics.setFont(FONT_LABELS);
		String difficultyLabel = "Task Difficulty: ";
		String difficultyValue = getDifficultyName(pointsAwarded);

		y += fmLabels.getAscent();

		// Center the whole line
		int diffLineWidth = fmLabels.stringWidth(difficultyLabel) + fmLabels.stringWidth(difficultyValue);
		int diffStartX = (BOX_WIDTH - diffLineWidth) / 2;

		graphics.setColor(COLOR_LABELS);
		graphics.drawString(difficultyLabel, diffStartX, y);
		graphics.setColor(getDifficultyColor(pointsAwarded));
		graphics.drawString(difficultyValue, diffStartX + fmLabels.stringWidth(difficultyLabel), y);

		y += fmLabels.getDescent() + lineSpacing;

		// Draw "Task Region Assigned: [region]" (orange label, white value)
		String regionLabel = "Task Region Assigned: ";
		// Extract just the region name without the ID if present
		String regionDisplay = regionName;
		if (regionDisplay.contains("("))
		{
			regionDisplay = regionDisplay.substring(0, regionDisplay.indexOf("(")).trim();
		}

		y += fmLabels.getAscent();

		// Center the whole line
		int regionLineWidth = fmLabels.stringWidth(regionLabel) + fmLabels.stringWidth(regionDisplay);
		int regionStartX = (BOX_WIDTH - regionLineWidth) / 2;

		graphics.setColor(COLOR_LABELS);
		graphics.drawString(regionLabel, regionStartX, y);
		graphics.setColor(COLOR_TASK_NAME);  // White for the value
		graphics.drawString(regionDisplay, regionStartX + fmLabels.stringWidth(regionLabel), y);

		y += fmLabels.getDescent() + lineSpacing + 4;

		// Draw "Points Earned: [points]" (large white text, centered)
		graphics.setFont(FONT_POINTS);
		String pointsText = "Points Earned: " + pointsAwarded;
		int pointsWidth = fmPoints.stringWidth(pointsText);

		y += fmPoints.getAscent();
		graphics.setColor(COLOR_POINTS_TEXT);
		graphics.drawString(pointsText, (BOX_WIDTH - pointsWidth) / 2, y);

		// Draw item image if we have an item ID
		if (itemId > 0 && itemManager != null)
		{
			BufferedImage itemImage = itemManager.getImage(itemId);
			if (itemImage != null)
			{
				// Draw item image in the top-right corner
				int itemX = BOX_WIDTH - itemImage.getWidth() - 15;
				int itemY = 15;
				graphics.drawImage(itemImage, itemX, itemY, null);
			}
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
