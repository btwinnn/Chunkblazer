package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskScreenshotManagerTest
{
	private static NuzlockeTask task(String id, String name)
	{
		NuzlockeTask t = new NuzlockeTask();
		t.setTaskId(id);
		t.setName(name);
		return t;
	}

	private static String fileName(String id, String name)
	{
		return TaskScreenshotManager.buildFileName(Collections.singletonList(task(id, name)), null);
	}

	// ── buildFileName ────────────────────────────────────────────────────

	@Test
	void namesFileAfterTask()
	{
		assertEquals("Task Complete - Cook a tuna", fileName("cook_tuna", "Cook a tuna"));
	}

	@Test
	void stripsCharactersIllegalInFileNames()
	{
		assertEquals("Task Complete - Kill 10 Goblins", fileName("x", "Kill 10 Goblins?"));
	}

	@Test
	void countsTheRestOfTheBatch()
	{
		assertEquals("Task Complete - A (+2 more)",
			TaskScreenshotManager.buildFileName(Arrays.asList(task("a", "A"), task("b", "B"), task("c", "C")), null));
	}

	@Test
	void fallsBackToTaskIdWhenUnnamed()
	{
		assertEquals("Task Complete - cook_tuna", fileName("cook_tuna", null));
	}

	@Test
	void stripsBackslash()
	{
		assertEquals("Task Complete - ab", fileName("x", "a\\b"));
	}

	@Test
	void pathTraversalCannotEscapeFolder()
	{
		String name = fileName("x", "\\..\\..\\evil");
		assertFalse(name.contains("\\"), name);
		assertFalse(name.contains("/"), name);
		assertEquals("Task Complete - ....evil", name);
	}

	@Test
	void stripsControlCharacters()
	{
		assertEquals("Task Complete - abc", fileName("x", "a\u0000b\u001fc\u007f"));
	}

	@Test
	void stripsColourTags()
	{
		assertEquals("Task Complete - Red", fileName("x", "<col=ff0000>Red</col>"));
	}

	@Test
	void collapsesWhitespace()
	{
		assertEquals("Task Complete - Cook a tuna", fileName("x", "  Cook \t a\n\ntuna  "));
	}

	@Test
	void capsLongNames()
	{
		StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 30; i++)
		{
			longName.append("abcde ");
		}
		String name = fileName("x", longName.toString());
		String cleaned = name.substring("Task Complete - ".length());
		assertTrue(cleaned.length() <= 80, cleaned);
		assertEquals(cleaned.trim(), cleaned);
		assertTrue(longName.toString().startsWith(cleaned));
	}

	@Test
	void fallsBackToTaskIdWhenNameCleansToNothing()
	{
		assertEquals("Task Complete - cook_tuna", fileName("cook_tuna", "???"));
	}

	@Test
	void fallsBackToTaskWhenNameAndIdCleanToNothing()
	{
		assertEquals("Task Complete - Task", fileName("***", "???"));
		assertEquals("Task Complete - Task", fileName(null, null));
	}

	@Test
	void usesFeaturedTaskName()
	{
		NuzlockeTask c = task("c", "C");
		assertEquals("Task Complete - C (+2 more)",
			TaskScreenshotManager.buildFileName(Arrays.asList(task("a", "A"), task("b", "B"), c), c));
	}

	@Test
	void featuredNullUsesFirstTask()
	{
		assertEquals("Task Complete - A (+1 more)",
			TaskScreenshotManager.buildFileName(Arrays.asList(task("a", "A"), task("b", "B")), null));
	}

	// ── Capture lifecycle ────────────────────────────────────────────────

	private static final String EXPECTED_FILE = "Task Complete - Cook a tuna";

	private Client client;
	private ClientThread clientThread;
	private ImageCapture imageCapture;
	private ChunkBlazerConfig config;
	private TaskScreenshotManager manager;
	private long now;

	@BeforeEach
	void setUp()
	{
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		imageCapture = mock(ImageCapture.class);
		config = mock(ChunkBlazerConfig.class);
		when(config.screenshotOnTaskComplete()).thenReturn(true);
		when(config.showTaskCompletionPopup()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		manager = new TaskScreenshotManager(client, clientThread, imageCapture, config);
		now = 1_000_000L;
		manager.clock = () -> now;
	}

	private List<NuzlockeTask> batch()
	{
		return Collections.singletonList(task("cook_tuna", "Cook a tuna"));
	}

	/** Complete a batch and return the supplier it queued on the client thread. */
	private BooleanSupplier complete()
	{
		manager.onTasksCompleted(batch(), null);
		ArgumentCaptor<BooleanSupplier> captor = ArgumentCaptor.forClass(BooleanSupplier.class);
		verify(clientThread, times(1)).invokeLater(captor.capture());
		return captor.getValue();
	}

	private void verifyCaptured(int count)
	{
		verify(imageCapture, times(count)).takeScreenshot(
			TaskScreenshotManager.SUB_DIR, EXPECTED_FILE, false, false, false);
	}

	private void verifyNeverCaptured()
	{
		verify(imageCapture, never()).takeScreenshot(anyString(), anyString(), anyBoolean(), anyBoolean(), anyBoolean());
	}

	@Test
	void waitsForPopupThenCapturesOnce()
	{
		BooleanSupplier attempt = complete();
		assertFalse(attempt.getAsBoolean());
		now += TaskScreenshotManager.POPUP_SETTLE_MS - 1;
		assertFalse(attempt.getAsBoolean());
		verifyNeverCaptured();

		now += 1;
		assertTrue(attempt.getAsBoolean());
		verifyCaptured(1);
	}

	@Test
	void capturesImmediatelyWithPopupOff()
	{
		when(config.showTaskCompletionPopup()).thenReturn(false);
		BooleanSupplier attempt = complete();
		assertTrue(attempt.getAsBoolean());
		verifyCaptured(1);
	}

	@Test
	void newerBatchSupersedesPending()
	{
		manager.onTasksCompleted(batch(), null);
		manager.onTasksCompleted(batch(), null);
		ArgumentCaptor<BooleanSupplier> captor = ArgumentCaptor.forClass(BooleanSupplier.class);
		verify(clientThread, times(2)).invokeLater(captor.capture());
		BooleanSupplier first = captor.getAllValues().get(0);
		BooleanSupplier second = captor.getAllValues().get(1);

		now += TaskScreenshotManager.POPUP_SETTLE_MS;
		assertTrue(first.getAsBoolean());
		verifyNeverCaptured();

		assertTrue(second.getAsBoolean());
		verifyCaptured(1);
	}

	@Test
	void shutDownCancelsPending()
	{
		BooleanSupplier attempt = complete();
		manager.shutDown();
		now += TaskScreenshotManager.POPUP_SETTLE_MS;
		assertTrue(attempt.getAsBoolean());
		verifyNeverCaptured();
	}

	@Test
	void toggleOffBeforeCaptureCancels()
	{
		BooleanSupplier attempt = complete();
		when(config.screenshotOnTaskComplete()).thenReturn(false);
		now += TaskScreenshotManager.POPUP_SETTLE_MS;
		assertTrue(attempt.getAsBoolean());
		verifyNeverCaptured();
	}

	@Test
	void ridesOutLoadingThenCaptures()
	{
		BooleanSupplier attempt = complete();
		now += TaskScreenshotManager.POPUP_SETTLE_MS;
		when(client.getGameState()).thenReturn(GameState.LOADING);
		assertFalse(attempt.getAsBoolean());
		when(client.getGameState()).thenReturn(GameState.HOPPING);
		assertFalse(attempt.getAsBoolean());
		verifyNeverCaptured();

		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		assertTrue(attempt.getAsBoolean());
		verifyCaptured(1);
	}

	@Test
	void givesUpWhenLoadingOutlastsPopup()
	{
		BooleanSupplier attempt = complete();
		when(client.getGameState()).thenReturn(GameState.LOADING);
		now += TaskScreenshotManager.GIVE_UP_MS - 1;
		assertFalse(attempt.getAsBoolean());
		now += 1;
		assertTrue(attempt.getAsBoolean());
		verifyNeverCaptured();
	}

	@Test
	void givesUpImmediatelyOnLoginScreen()
	{
		BooleanSupplier attempt = complete();
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		assertTrue(attempt.getAsBoolean());
		verifyNeverCaptured();
	}

	@Test
	void doesNothingWhenToggleOff()
	{
		when(config.screenshotOnTaskComplete()).thenReturn(false);
		manager.onTasksCompleted(batch(), null);
		verify(clientThread, never()).invokeLater(any(BooleanSupplier.class));
	}
}
