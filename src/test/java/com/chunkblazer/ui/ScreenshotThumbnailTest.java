package com.chunkblazer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ScreenshotThumbnailTest
{
	private final List<Consumer<BufferedImage>> requests = new ArrayList<>();
	private final ScreenshotThumbnail thumbnail = new ScreenshotThumbnail(
		new File("shot.png"), 160, (file, w, h, cb) -> requests.add(cb), new Font("Dialog", Font.PLAIN, 10));

	private static BufferedImage image()
	{
		return new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB);
	}

	@Test
	void loadsOnlyOnceWhileWanted()
	{
		thumbnail.load();
		thumbnail.load();
		assertEquals(1, requests.size());
		requests.get(0).accept(image());
		assertNotNull(thumbnail.getIcon());
	}

	@Test
	void unloadReleasesTheImage()
	{
		thumbnail.load();
		requests.get(0).accept(image());
		thumbnail.unload();
		assertNull(thumbnail.getIcon());
	}

	@Test
	void ignoresAReadThatFinishesAfterScrollingAway()
	{
		thumbnail.load();
		thumbnail.unload();
		requests.get(0).accept(image());
		assertNull(thumbnail.getIcon());
	}
}
