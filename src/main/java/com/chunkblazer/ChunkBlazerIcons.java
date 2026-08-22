package com.chunkblazer;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Generates ChunkBlazer icons programmatically.
 * Creates fire-themed icons for the plugin panel, player overlays, and chat.
 */
public class ChunkBlazerIcons
{
	// Fire colors
	private static final Color FIRE_ORANGE = new Color(255, 140, 0);
	private static final Color FIRE_YELLOW = new Color(255, 215, 0);
	private static final Color FIRE_RED = new Color(255, 69, 0);
	private static final Color FIRE_DARK_RED = new Color(178, 34, 34);

	// Nuzlocke colors (more intense red/orange)
	private static final Color NUZLOCKE_RED = new Color(220, 20, 60);
	private static final Color NUZLOCKE_ORANGE = new Color(255, 99, 71);

	// Casual colors (cooler blue flame)
	private static final Color CASUAL_BLUE = new Color(30, 144, 255);
	private static final Color CASUAL_CYAN = new Color(0, 191, 255);

	/**
	 * Create the main ChunkBlazer fire icon.
	 * @param size The width and height of the icon
	 * @return A BufferedImage containing the fire icon
	 */
	public static BufferedImage createFireIcon(int size)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();

		// Enable anti-aliasing for smooth edges
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Create fire shape
		Path2D flame = createFlameShape(size);

		// Create gradient from red at bottom to yellow at top
		GradientPaint gradient = new GradientPaint(
			size / 2f, size,        // Start at bottom center
			FIRE_RED,
			size / 2f, size * 0.2f, // End near top
			FIRE_YELLOW
		);

		g2d.setPaint(gradient);
		g2d.fill(flame);

		// Add inner glow (smaller, brighter flame)
		Path2D innerFlame = createInnerFlameShape(size);
		GradientPaint innerGradient = new GradientPaint(
			size / 2f, size * 0.9f,
			FIRE_ORANGE,
			size / 2f, size * 0.3f,
			FIRE_YELLOW
		);
		g2d.setPaint(innerGradient);
		g2d.fill(innerFlame);

		g2d.dispose();
		return image;
	}

	/**
	 * Create a Nuzlocke-themed fire icon (more intense red).
	 */
	public static BufferedImage createNuzlockeIcon(int size)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Path2D flame = createFlameShape(size);

		GradientPaint gradient = new GradientPaint(
			size / 2f, size,
			FIRE_DARK_RED,
			size / 2f, size * 0.2f,
			NUZLOCKE_ORANGE
		);

		g2d.setPaint(gradient);
		g2d.fill(flame);

		// Inner flame
		Path2D innerFlame = createInnerFlameShape(size);
		GradientPaint innerGradient = new GradientPaint(
			size / 2f, size * 0.9f,
			NUZLOCKE_RED,
			size / 2f, size * 0.3f,
			FIRE_YELLOW
		);
		g2d.setPaint(innerGradient);
		g2d.fill(innerFlame);

		g2d.dispose();
		return image;
	}

	/**
	 * Create a Casual-themed fire icon (cool blue flame).
	 */
	public static BufferedImage createCasualIcon(int size)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Path2D flame = createFlameShape(size);

		GradientPaint gradient = new GradientPaint(
			size / 2f, size,
			CASUAL_BLUE,
			size / 2f, size * 0.2f,
			CASUAL_CYAN
		);

		g2d.setPaint(gradient);
		g2d.fill(flame);

		// Inner flame (white-ish center)
		Path2D innerFlame = createInnerFlameShape(size);
		GradientPaint innerGradient = new GradientPaint(
			size / 2f, size * 0.9f,
			new Color(135, 206, 250),
			size / 2f, size * 0.3f,
			Color.WHITE
		);
		g2d.setPaint(innerGradient);
		g2d.fill(innerFlame);

		g2d.dispose();
		return image;
	}

	/**
	 * Create a small chat icon (simplified flame).
	 */
	public static BufferedImage createChatIcon(int size)
	{
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Simplified flame for small sizes
		Path2D flame = createSimpleFlameShape(size);

		GradientPaint gradient = new GradientPaint(
			size / 2f, size,
			FIRE_ORANGE,
			size / 2f, 0,
			FIRE_YELLOW
		);

		g2d.setPaint(gradient);
		g2d.fill(flame);

		g2d.dispose();
		return image;
	}

	/**
	 * Create the main flame shape.
	 */
	private static Path2D createFlameShape(int size)
	{
		Path2D path = new Path2D.Double();

		double w = size;
		double h = size;

		// Start at bottom center
		path.moveTo(w * 0.5, h * 0.95);

		// Left side of flame
		path.curveTo(
			w * 0.15, h * 0.8,   // Control point 1
			w * 0.1, h * 0.5,    // Control point 2
			w * 0.25, h * 0.25   // End point (left tongue)
		);

		// Left tongue tip curves back
		path.curveTo(
			w * 0.3, h * 0.15,
			w * 0.35, h * 0.1,
			w * 0.4, h * 0.15
		);

		// Center peak
		path.curveTo(
			w * 0.45, h * 0.05,  // Going up to peak
			w * 0.55, h * 0.05,  // Peak
			w * 0.6, h * 0.15    // Coming down
		);

		// Right tongue
		path.curveTo(
			w * 0.65, h * 0.1,
			w * 0.7, h * 0.15,
			w * 0.75, h * 0.25
		);

		// Right side of flame
		path.curveTo(
			w * 0.9, h * 0.5,
			w * 0.85, h * 0.8,
			w * 0.5, h * 0.95
		);

		path.closePath();
		return path;
	}

	/**
	 * Create an inner flame shape (smaller, for the bright center).
	 */
	private static Path2D createInnerFlameShape(int size)
	{
		Path2D path = new Path2D.Double();

		double w = size;
		double h = size;

		// Smaller, centered flame
		path.moveTo(w * 0.5, h * 0.85);

		path.curveTo(
			w * 0.3, h * 0.7,
			w * 0.25, h * 0.5,
			w * 0.35, h * 0.35
		);

		path.curveTo(
			w * 0.4, h * 0.25,
			w * 0.45, h * 0.2,
			w * 0.5, h * 0.15
		);

		path.curveTo(
			w * 0.55, h * 0.2,
			w * 0.6, h * 0.25,
			w * 0.65, h * 0.35
		);

		path.curveTo(
			w * 0.75, h * 0.5,
			w * 0.7, h * 0.7,
			w * 0.5, h * 0.85
		);

		path.closePath();
		return path;
	}

	/**
	 * Create a simplified flame shape for very small icons.
	 */
	private static Path2D createSimpleFlameShape(int size)
	{
		Path2D path = new Path2D.Double();

		double w = size;
		double h = size;

		// Simple teardrop/flame shape
		path.moveTo(w * 0.5, h * 0.9);

		// Left curve
		path.quadTo(w * 0.15, h * 0.6, w * 0.35, h * 0.2);

		// Top peak
		path.quadTo(w * 0.5, h * 0.05, w * 0.65, h * 0.2);

		// Right curve
		path.quadTo(w * 0.85, h * 0.6, w * 0.5, h * 0.9);

		path.closePath();
		return path;
	}

}
