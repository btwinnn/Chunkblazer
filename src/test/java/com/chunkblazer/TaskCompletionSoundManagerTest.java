package com.chunkblazer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the area→sound-folder mapping that turns a chunk unlock / task completion
 * into a regional jingle. This map has silently broken before — a sound folder was
 * renamed (Kourend_Sounds → Zeah_Sounds) without updating the map, so every Zeah
 * completion played in silence — which is exactly the failure mode this locks down.
 *
 * <p>Boss chunks ride on the SAME mechanism: the ToA chunk declares area "Desert"
 * and the CoX chunk declares area "Zeah" (in Boss_Tasks.json), and
 * {@code playRegionUnlockJingle} feeds that area straight into
 * {@link TaskCompletionSoundManager#playRandomSoundForArea}. So if these keys ever
 * fall out of the map, the boss-chunk unlock jingle goes silent with no other
 * symptom — this test fails first.
 */
class TaskCompletionSoundManagerTest
{
	@SuppressWarnings("unchecked")
	private static Map<String, String> areaToFolder() throws Exception
	{
		Field f = TaskCompletionSoundManager.class.getDeclaredField("AREA_TO_FOLDER");
		f.setAccessible(true);
		return (Map<String, String>) f.get(null);
	}

	@Test
	void toaDesertAreaResolvesToDesertSounds() throws Exception
	{
		assertEquals("Desert_Sounds", areaToFolder().get("Desert"),
			"the ToA boss chunk's 'Desert' area must resolve to Desert_Sounds, or its unlock is silent");
	}

	@Test
	void coxZeahAreaResolvesToZeahSounds() throws Exception
	{
		assertEquals("Zeah_Sounds", areaToFolder().get("Zeah"),
			"the CoX boss chunk's 'Zeah' area must resolve to Zeah_Sounds, or its unlock is silent");
	}

	/**
	 * Every mapped folder must be non-blank and end in "_Sounds" — the shape the asset
	 * manifest keys its audio by ({@code Desert_Sounds}, {@code Zeah_Sounds}, …). A typo
	 * here is a silent miss, not an error.
	 */
	@Test
	void everyMappedFolderIsWellFormed() throws Exception
	{
		for (Map.Entry<String, String> e : areaToFolder().entrySet())
		{
			String folder = e.getValue();
			assertNotNull(folder, "null folder for area " + e.getKey());
			assertFalse(folder.isBlank(), "blank folder for area " + e.getKey());
			assertTrue(folder.endsWith("_Sounds"),
				"folder for area '" + e.getKey() + "' should end in _Sounds, got " + folder);
		}
	}

	// --- µ-law -> PCM expansion (server jingles are µ-law; AudioPlayer needs PCM) ---

	/** ITU-T G.711 reference points: 0xFF is silence, 0x00 / 0x80 are the extremes. */
	@Test
	void ulawDecodesToKnownReferenceSamples()
	{
		assertEquals(0, TaskCompletionSoundManager.ulawToPcm((byte) 0xFF), "0xFF is µ-law silence");
		assertEquals(-32124, TaskCompletionSoundManager.ulawToPcm((byte) 0x00), "0x00 is the max negative");
		assertEquals(32124, TaskCompletionSoundManager.ulawToPcm((byte) 0x80), "0x80 is the max positive");
	}

	@Test
	void muLawWavIsExpandedToSixteenBitPcm()
	{
		byte[] samples = {(byte) 0xFF, (byte) 0x00, (byte) 0x80};
		byte[] out = TaskCompletionSoundManager.toPcmWav(wav(7, 8, 1, 8000, samples));

		assertEquals("RIFF", ascii(out, 0), "still a RIFF/WAVE");
		assertEquals("WAVE", ascii(out, 8), "still a RIFF/WAVE");
		assertEquals(1, le16(out, 20), "format tag must now be PCM");
		assertEquals(16, le16(out, 34), "must be 16-bit");
		assertEquals(samples.length * 2, le32(out, 40), "one 16-bit sample per µ-law byte");

		assertEquals(0, sampleAt(out, 0));
		assertEquals(-32124, sampleAt(out, 1));
		assertEquals(32124, sampleAt(out, 2));
	}

	@Test
	void pcmWavIsPassedThroughUnchanged()
	{
		byte[] pcm = wav(1, 16, 1, 22050, new byte[]{0x11, 0x22, 0x33, 0x44});
		assertSame(pcm, TaskCompletionSoundManager.toPcmWav(pcm),
			"PCM input needs no expansion and must be handed to the player as-is");
	}

	// --- tiny WAV helpers ---

	private static byte[] wav(int formatTag, int bits, int channels, int rate, byte[] data)
	{
		int blockAlign = channels * bits / 8;
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		putAscii(o, "RIFF");
		putLe32(o, 36 + data.length);
		putAscii(o, "WAVE");
		putAscii(o, "fmt ");
		putLe32(o, 16);
		putLe16(o, formatTag);
		putLe16(o, channels);
		putLe32(o, rate);
		putLe32(o, rate * blockAlign);
		putLe16(o, blockAlign);
		putLe16(o, bits);
		putAscii(o, "data");
		putLe32(o, data.length);
		o.write(data, 0, data.length);
		return o.toByteArray();
	}

	private static short sampleAt(byte[] wav, int index)
	{
		int off = 44 + index * 2;
		return (short) ((wav[off] & 0xff) | (wav[off + 1] << 8));
	}

	private static String ascii(byte[] b, int off)
	{
		return new String(b, off, 4, java.nio.charset.StandardCharsets.US_ASCII);
	}

	private static int le16(byte[] b, int off)
	{
		return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
	}

	private static int le32(byte[] b, int off)
	{
		return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
			| ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
	}

	private static void putAscii(ByteArrayOutputStream o, String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			o.write(s.charAt(i));
		}
	}

	private static void putLe16(ByteArrayOutputStream o, int v)
	{
		o.write(v & 0xff);
		o.write((v >> 8) & 0xff);
	}

	private static void putLe32(ByteArrayOutputStream o, int v)
	{
		o.write(v & 0xff);
		o.write((v >> 8) & 0xff);
		o.write((v >> 16) & 0xff);
		o.write((v >> 24) & 0xff);
	}
}
