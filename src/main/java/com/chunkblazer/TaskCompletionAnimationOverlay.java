package com.chunkblazer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Animated overlay for task completion.
 *
 * Animation sequence:
 * Phase 1 (0-1s): Bar 1 reveals from center outward
 * Phase 2 (1-2.5s): Task Complete slides UP, Points box slides DOWN (Bar 1 hides)
 * Phase 3 (2.5-6.5s): Hold position - just the two boxes visible
 * Phase 4 (6.5-9s): Reverse all animations
 */
@Slf4j
@Singleton
public class TaskCompletionAnimationOverlay extends Overlay
{
	// Animation timing (milliseconds)
	private static final long PHASE_1_DURATION = 1000;   // Bar 1 reveal from center
	private static final long PHASE_2_DURATION = 1500;   // Boxes reveal
	private static final long PHASE_3_DURATION = 4000;   // Hold
	private static final long PHASE_4_DURATION = 2500;   // Reverse

	private static final long PHASE_2_START = PHASE_1_DURATION;
	private static final long PHASE_3_START = PHASE_2_START + PHASE_2_DURATION;
	private static final long PHASE_4_START = PHASE_3_START + PHASE_3_DURATION;
	private static final long TOTAL_DURATION = PHASE_4_START + PHASE_4_DURATION;

	// Fonts
	private static final Font FONT_TASK_NAME = new Font("Verdana", Font.BOLD, 11);
	private static final Font FONT_VALUES = new Font("Verdana", Font.PLAIN, 10);
	private static final Color COLOR_TEXT = new Color(255, 255, 255);
	private static final Color COLOR_SHADOW = new Color(0, 0, 0, 180);
	private static final int SHADOW_OFFSET_X = 1;
	private static final int SHADOW_OFFSET_Y = 1;

	private final ChunkBlazerConfig config;
	private final Client client;

	// Loaded images
	private BufferedImage bar1Image;           // 1_Anim_Starting_Bar (only visible during phase 1)
	private BufferedImage pointsBoxImage;      // Base_Anim_Points_Box
	private BufferedImage taskCompleteBoxImage; // Base_Anim_TaskComplete_Box

	// Current state
	private long startTime = -1;
	private String taskName = "";
	private String regionName = "";
	private int pointsAwarded = 0;

	@Inject
	public TaskCompletionAnimationOverlay(ChunkBlazerConfig config, Client client)
	{
		this.config = config;
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
		loadImages();
	}

	private void loadImages()
	{
		try
		{
			bar1Image = loadImage("Task_Complete_Anim/1_Anim_Starting_Bar.png");
			pointsBoxImage = loadImage("Task_Complete_Anim/Base_Anim_Points_Box.png");
			taskCompleteBoxImage = loadImage("Task_Complete_Anim/Base_Anim_TaskComplete_Box.png");

		}
		catch (Exception e)
		{
			log.error("Failed to load animation images", e);
		}
	}

	private BufferedImage loadImage(String path)
	{
		try (InputStream is = getClass().getResourceAsStream(path))
		{
			if (is != null)
			{
				return ImageIO.read(is);
			}
			log.warn("Could not find image: {}", path);
		}
		catch (Exception e)
		{
			log.error("Failed to load image: {}", path, e);
		}
		return null;
	}

	public void showTaskCompletion(NuzlockeTask task, int points, String regionName)
	{
		if (task == null) return;
		this.taskName = task.getName();
		this.pointsAwarded = points > 0 ? points : task.getBasePoints();
		this.regionName = regionName != null ? regionName : "Unknown";
		if (this.regionName.contains("("))
			this.regionName = this.regionName.substring(0, this.regionName.indexOf("(")).trim();
		this.startTime = System.currentTimeMillis();
	}

	public void showTaskCompletion(String name, int points, String regionName)
	{
		this.taskName = name;
		this.pointsAwarded = points;
		this.regionName = regionName != null ? regionName : "Unknown";
		if (this.regionName.contains("("))
			this.regionName = this.regionName.substring(0, this.regionName.indexOf("(")).trim();
		this.startTime = System.currentTimeMillis();
	}

	public void hide()
	{
		startTime = -1;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (startTime == -1 || !config.showTaskCompletionPopup()) return null;

		long elapsed = System.currentTimeMillis() - startTime;
		if (elapsed > TOTAL_DURATION)
		{
			startTime = -1;
			return null;
		}

		// Get screen dimensions
		int screenWidth = client.getCanvasWidth();
		int screenHeight = client.getCanvasHeight();

		// Setup rendering
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		// Image dimensions
		int bar1Width = bar1Image != null ? bar1Image.getWidth() : 313;
		int bar1Height = bar1Image != null ? bar1Image.getHeight() : 6;
		int taskCompleteWidth = taskCompleteBoxImage != null ? taskCompleteBoxImage.getWidth() : 186;
		int taskCompleteHeight = taskCompleteBoxImage != null ? taskCompleteBoxImage.getHeight() : 40;
		int pointsBoxWidth = pointsBoxImage != null ? pointsBoxImage.getWidth() : 313;
		int pointsBoxHeight = pointsBoxImage != null ? pointsBoxImage.getHeight() : 92;

		// Origin point: 1/8 from top of screen (higher up), centered horizontally
		int originY = screenHeight / 8;
		int bar1X = (screenWidth - bar1Width) / 2;

		// Calculate effective elapsed time (for reverse animation)
		long effectiveElapsed = elapsed;
		boolean reversing = elapsed >= PHASE_4_START;
		if (reversing)
		{
			long reverseProgress = elapsed - PHASE_4_START;
			float reverseRatio = (float) reverseProgress / PHASE_4_DURATION;
			effectiveElapsed = (long) (PHASE_3_START * (1.0f - reverseRatio));
			effectiveElapsed = Math.max(0, effectiveElapsed);
		}

		// Phase 1: Only Bar 1 reveals from center
		if (effectiveElapsed < PHASE_2_START)
		{
			float bar1Progress = easeInOutCubic((float) effectiveElapsed / PHASE_1_DURATION);

			if (bar1Image != null && bar1Progress > 0)
			{
				int revealWidth = (int) (bar1Width * bar1Progress);
				if (revealWidth > 0)
				{
					int cropX = (bar1Width - revealWidth) / 2;
					BufferedImage croppedBar1 = bar1Image.getSubimage(cropX, 0, revealWidth, bar1Height);
					int drawX = bar1X + cropX;
					graphics.drawImage(croppedBar1, drawX, originY, null);
				}
			}
		}
		// Phase 2+: Boxes reveal (Bar 1 is hidden)
		else
		{
			float boxProgress;
			if (effectiveElapsed < PHASE_3_START)
			{
				boxProgress = easeInOutCubic((float) (effectiveElapsed - PHASE_2_START) / PHASE_2_DURATION);
			}
			else
			{
				boxProgress = 1.0f;
			}

			// Center X positions for boxes
			int taskCompleteX = (screenWidth - taskCompleteWidth) / 2;
			int pointsBoxX = (screenWidth - pointsBoxWidth) / 2;

			// Task Complete box: reveals upward from origin
			// Final position: bottom edge at originY
			int taskCompleteRevealHeight = (int) (taskCompleteHeight * boxProgress);
			int taskCompleteFinalY = originY - taskCompleteHeight;
			int taskCompleteCurrentY = originY - taskCompleteRevealHeight;

			// Points box: reveals downward from origin
			// Final position: top edge at originY
			int pointsBoxRevealHeight = (int) (pointsBoxHeight * boxProgress);
			int pointsBoxY = originY;

			// Draw Task Complete Box
			if (taskCompleteBoxImage != null && taskCompleteRevealHeight > 0)
			{
				int finalY = originY - taskCompleteHeight;

				// When fully revealed, draw without any clipping
				if (boxProgress >= 0.98f)
				{
					graphics.drawImage(taskCompleteBoxImage, taskCompleteX, finalY, null);
				}
				else
				{
					// Use clip to reveal from bottom up, with padding to prevent edge clipping
					Shape oldClip = graphics.getClip();
					int clipPadding = 5;
					graphics.setClip(new Rectangle(
						taskCompleteX - clipPadding,
						taskCompleteCurrentY - clipPadding,
						taskCompleteWidth + clipPadding * 2,
						taskCompleteRevealHeight + clipPadding * 2));
					graphics.drawImage(taskCompleteBoxImage, taskCompleteX, finalY, null);
					graphics.setClip(oldClip);
				}
			}

			// Draw Points Box (crop from bottom, reveal from top)
			if (pointsBoxImage != null && pointsBoxRevealHeight > 0)
			{
				BufferedImage croppedPoints = pointsBoxImage.getSubimage(0, 0, pointsBoxWidth, pointsBoxRevealHeight);
				graphics.drawImage(croppedPoints, pointsBoxX, pointsBoxY, null);

				// Draw dynamic text when box is mostly revealed
				if (boxProgress > 0.6f)
				{
					drawPointsBoxText(graphics, pointsBoxX, pointsBoxY, pointsBoxWidth, pointsBoxHeight);
				}
			}
		}

		return new Dimension(screenWidth, screenHeight);
	}

	private void drawPointsBoxText(Graphics2D graphics, int boxX, int boxY, int boxWidth, int boxHeight)
	{
		// Task name - centered in the top area of the box (above "Region Assigned")
		graphics.setFont(FONT_TASK_NAME);
		FontMetrics fm = graphics.getFontMetrics();
		String displayName = truncateText(taskName, fm, boxWidth - 30);
		int nameX = boxX + (boxWidth - fm.stringWidth(displayName)) / 2;
		int nameY = boxY + 25; // Near the top
		// Draw shadow then text
		graphics.setColor(COLOR_SHADOW);
		graphics.drawString(displayName, nameX + SHADOW_OFFSET_X, nameY + SHADOW_OFFSET_Y);
		graphics.setColor(COLOR_TEXT);
		graphics.drawString(displayName, nameX, nameY);

		// Region value - below and slightly left of "Region Assigned:" label
		graphics.setFont(FONT_VALUES);
		fm = graphics.getFontMetrics();
		int regionX = boxX + 175; // Slightly left
		int regionY = boxY + 58; // Moved down
		// Draw shadow then text
		graphics.setColor(COLOR_SHADOW);
		graphics.drawString(regionName, regionX + SHADOW_OFFSET_X, regionY + SHADOW_OFFSET_Y);
		graphics.setColor(COLOR_TEXT);
		graphics.drawString(regionName, regionX, regionY);

		// Points value - underneath region value
		String pointsStr = String.valueOf(pointsAwarded);
		int pointsX = boxX + 175; // Same X as region
		int pointsY = boxY + 78; // Below region
		// Draw shadow then text
		graphics.setColor(COLOR_SHADOW);
		graphics.drawString(pointsStr, pointsX + SHADOW_OFFSET_X, pointsY + SHADOW_OFFSET_Y);
		graphics.setColor(COLOR_TEXT);
		graphics.drawString(pointsStr, pointsX, pointsY);
	}

	private String truncateText(String text, FontMetrics fm, int maxWidth)
	{
		if (fm.stringWidth(text) <= maxWidth) return text;
		for (int i = text.length() - 1; i > 0; i--)
		{
			String t = text.substring(0, i) + "...";
			if (fm.stringWidth(t) <= maxWidth) return t;
		}
		return "...";
	}

	private float easeInOutCubic(float t)
	{
		return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
	}
}
