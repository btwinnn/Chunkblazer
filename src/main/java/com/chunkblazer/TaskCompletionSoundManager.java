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

import com.chunkblazer.api.AssetStore;
import com.chunkblazer.api.AudioAsset;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * Manages and plays region-specific sounds for task completion.
 *
 * <p>Playback goes through RuneLite's {@link AudioPlayer}. That opens a
 * {@code Clip} on the audio stream as-is and a Clip output line cannot open
 * compressed encodings, so the server-delivered µ-law jingles are expanded to
 * 16-bit PCM here first ({@link #toPcmWav}). The expansion is pure arithmetic
 * (no javax.sound), and bundled PCM seeds pass straight through.
 */
@Slf4j
@Singleton
public class TaskCompletionSoundManager
{
	private static final String SOUNDS_BASE_PATH = "Task_Complete_Region_Sounds/";

	// Used when a task's area has no sound folder of its own.
	private static final String DEFAULT_SOUND_FOLDER = "Misthalin_Sounds";

	// Map of area names to their sound folder names
	private static final Map<String, String> AREA_TO_FOLDER = new HashMap<>();

	static
	{
		AREA_TO_FOLDER.put("Misthalin", "Misthalin_Sounds");
		AREA_TO_FOLDER.put("Asgarnia", "Asgarnia_Sounds");
		AREA_TO_FOLDER.put("Kandarin", "Kandarin_Sounds");
		AREA_TO_FOLDER.put("Karamja", "Karamja_Sounds");
		AREA_TO_FOLDER.put("Morytania", "Morytania_Sounds");
		AREA_TO_FOLDER.put("Fremennik", "Fremmy_Sounds");
		AREA_TO_FOLDER.put("Fremennik Province", "Fremmy_Sounds");
		AREA_TO_FOLDER.put("Tirannwn", "Tirannwn_Sounds");
		AREA_TO_FOLDER.put("Wilderness", "Wilderness_Sounds");
		// The area name comes from the task FILENAME (Zeah_Tasks.json -> "Zeah"),
		// so "Zeah" is the key that actually gets looked up. The sound folder was
		// renamed Kourend_Sounds -> Zeah_Sounds but this map wasn't, so every Zeah
		// task completed in silence. Kourend aliases kept in case an area is ever
		// named that way.
		AREA_TO_FOLDER.put("Zeah", "Zeah_Sounds");
		AREA_TO_FOLDER.put("Kourend", "Zeah_Sounds");
		AREA_TO_FOLDER.put("Great Kourend", "Zeah_Sounds");
		AREA_TO_FOLDER.put("Desert", "Desert_Sounds");
		AREA_TO_FOLDER.put("Kharidian Desert", "Desert_Sounds");
		AREA_TO_FOLDER.put("Varlamore", "Varlamore_Sounds");
	}

	// The one jingle still bundled in the jar. Every other completion sound is
	// fetched from the server at runtime (see AssetStore). This seed is the
	// fallback whenever the server copy isn't cached yet, or the manifest hasn't
	// loaded (offline / first run) — so a completion is never totally silent.
	private static final String SEED_SOUND = SOUNDS_BASE_PATH + DEFAULT_SOUND_FOLDER + "/Quest_Complete_1.wav";

	// µ-law expansion bias (ITU-T G.711).
	private static final int ULAW_BIAS = 0x84;
	private static final int WAV_FORMAT_PCM = 1;
	private static final int WAV_FORMAT_MULAW = 7;

	private final Random random = new Random();
	private final ChunkBlazerConfig config;
	private final AssetStore assetStore;
	private final AudioPlayer audioPlayer;

	@Inject
	public TaskCompletionSoundManager(ChunkBlazerConfig config, AssetStore assetStore, AudioPlayer audioPlayer)
	{
		this.config = config;
		this.assetStore = assetStore;
		this.audioPlayer = audioPlayer;
	}

	/**
	 * Play a random sound for the given area.
	 * @param area The area name (e.g., "Misthalin", "Asgarnia")
	 */
	public void playRandomSoundForArea(String area)
	{
		// A null/empty area is normal for Global Tasks (quests belong to no
		// chunk), so play the fallback rather than nothing.
		String folder = (area == null || area.isEmpty())
			? DEFAULT_SOUND_FOLDER
			: AREA_TO_FOLDER.get(area);
		if (folder == null)
		{
			// Try to find a partial match
			for (Map.Entry<String, String> entry : AREA_TO_FOLDER.entrySet())
			{
				if (area.toLowerCase().contains(entry.getKey().toLowerCase()) ||
					entry.getKey().toLowerCase().contains(area.toLowerCase()))
				{
					folder = entry.getValue();
					break;
				}
			}
			if (folder == null)
			{
				// No mapping (Charter, Starter Area, and the chunk-independent
				// Global Tasks, which have no area at all). Falling back beats
				// silence — a missing map entry should degrade, not mute.
				folder = DEFAULT_SOUND_FOLDER;
			}
		}

		// Play a random server-delivered jingle for this area. This runs only on
		// task completion (not per-frame), so the small per-asset disk checks
		// below are fine.
		List<AudioAsset> remote = assetStore != null
			? assetStore.audioForArea(folder)
			: java.util.Collections.emptyList();
		if (!remote.isEmpty())
		{
			// Prefer a jingle from THIS region that's already cached, for regional
			// variety. getIfPresent() is a pure disk lookup — no network.
			List<AudioAsset> cached = new ArrayList<>();
			for (AudioAsset a : remote)
			{
				if (assetStore.getIfPresent(a) != null)
				{
					cached.add(a);
				}
			}
			if (!cached.isEmpty())
			{
				AudioAsset pick = cached.get(random.nextInt(cached.size()));
				playFile(assetStore.getIfPresent(pick));
			}
			else
			{
				// Nothing from this area cached yet (cold start): seed this once.
				playSeed();
			}
			// Ensure the whole area is (being) cached so subsequent completions
			// here play the real regional jingles, not the seed.
			assetStore.warmArea(folder);
			return;
		}

		// Manifest not loaded yet (offline, or before the first fetch completes):
		// the seed jingle is the only audio bundled in the jar.
		playSeed();
	}

	/**
	 * Play the single bundled fallback jingle. Used when the server copy for an
	 * area isn't cached yet, or the asset manifest hasn't loaded (offline / first
	 * run) — so a task completion is never totally silent.
	 */
	private void playSeed()
	{
		playResource(SEED_SOUND);
	}

	/**
	 * Play a sound file bundled in plugin resources.
	 * @param resourcePath Path to the sound file relative to the plugin package
	 */
	private void playResource(String resourcePath)
	{
		try (InputStream is = getClass().getResourceAsStream(resourcePath))
		{
			if (is == null)
			{
				log.error("Sound file not found at path: {}", resourcePath);
				return;
			}
			play(is.readAllBytes());
		}
		catch (Exception e)
		{
			log.error("Failed to play sound: {} - {}", resourcePath, e.getMessage(), e);
		}
	}

	/**
	 * Play a sound from a cached asset file on disk (the server-delivered copy).
	 * @param file The cached .wav file
	 */
	private void playFile(File file)
	{
		try
		{
			play(Files.readAllBytes(file.toPath()));
		}
		catch (Exception e)
		{
			log.error("Failed to play cached asset {}: {}", file, e.getMessage(), e);
		}
	}

	/**
	 * Expand µ-law to PCM if needed, then hand the PCM WAV to RuneLite's
	 * {@link AudioPlayer} at the configured volume.
	 */
	private void play(byte[] wav) throws Exception
	{
		byte[] pcm = toPcmWav(wav);
		audioPlayer.play(new ByteArrayInputStream(pcm), gainDb());
	}

	/**
	 * The configured volume as an {@code AudioPlayer} gain (MASTER_GAIN decibels).
	 * A percentage converts to dB via {@code 20*log10(pct)}: 100% is 0 dB, and it
	 * falls away below that. The floor keeps {@code log10(0)} out of the maths.
	 */
	private float gainDb()
	{
		int configured = config != null ? config.taskCompletionSoundVolume() : 3;
		float volumePercent = Math.max(0.001f, Math.min(1.0f, configured / 100.0f));
		return (float) (Math.log10(volumePercent) * 20.0);
	}

	/**
	 * Kept for API compatibility. {@code AudioPlayer} owns its one-shot clips and
	 * closes each when it finishes, so there is nothing to stop here.
	 */
	public void stopCurrentSound()
	{
	}

	/**
	 * Clean up resources. Nothing to release now that playback is fire-and-forget.
	 */
	public void shutdown()
	{
	}

	// --- µ-law -> PCM (pure arithmetic; no javax.sound) --------------------

	/**
	 * If {@code wav} is a µ-law WAVE (format tag 7), expand it to a 16-bit signed
	 * PCM WAVE that a Clip output line can open. PCM input (and anything we don't
	 * recognise) is returned unchanged for the player to handle.
	 */
	static byte[] toPcmWav(byte[] wav)
	{
		if (wav == null || wav.length < 44
			|| wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F')
		{
			return wav;
		}

		int fmtOff = -1;
		int dataOff = -1;
		int dataLen = 0;
		int p = 12; // skip "RIFF"<size>"WAVE"
		while (p + 8 <= wav.length)
		{
			String id = new String(wav, p, 4, StandardCharsets.US_ASCII);
			int sz = le32(wav, p + 4);
			int body = p + 8;
			if (sz < 0 || body + sz > wav.length)
			{
				break;
			}
			if ("fmt ".equals(id))
			{
				fmtOff = body;
			}
			else if ("data".equals(id))
			{
				dataOff = body;
				dataLen = sz;
			}
			p = body + sz + (sz & 1); // chunks are word-aligned
		}

		if (fmtOff < 0 || dataOff < 0 || le16(wav, fmtOff) != WAV_FORMAT_MULAW)
		{
			return wav; // PCM or unrecognised: leave it to the player
		}

		int channels = le16(wav, fmtOff + 2);
		int sampleRate = le32(wav, fmtOff + 4);

		// One µ-law byte per sample expands to one 16-bit PCM sample, frame order
		// preserved, so mono and stereo both round-trip correctly.
		byte[] pcm = new byte[dataLen * 2];
		for (int i = 0; i < dataLen; i++)
		{
			short s = ulawToPcm(wav[dataOff + i]);
			pcm[i * 2] = (byte) (s & 0xff);
			pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
		}
		return buildPcmWav(pcm, channels, sampleRate);
	}

	/** ITU-T G.711 µ-law byte to a linear 16-bit sample. */
	static short ulawToPcm(byte mu)
	{
		int u = (~mu) & 0xff;
		int t = ((u & 0x0f) << 3) + ULAW_BIAS;
		t <<= (u & 0x70) >> 4;
		return (short) ((u & 0x80) != 0 ? (ULAW_BIAS - t) : (t - ULAW_BIAS));
	}

	private static byte[] buildPcmWav(byte[] pcmData, int channels, int sampleRate)
	{
		int bitsPerSample = 16;
		int blockAlign = channels * bitsPerSample / 8;
		int byteRate = sampleRate * blockAlign;
		int dataLen = pcmData.length;

		ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
		writeAscii(out, "RIFF");
		writeLe32(out, 36 + dataLen);
		writeAscii(out, "WAVE");
		writeAscii(out, "fmt ");
		writeLe32(out, 16);
		writeLe16(out, WAV_FORMAT_PCM);
		writeLe16(out, channels);
		writeLe32(out, sampleRate);
		writeLe32(out, byteRate);
		writeLe16(out, blockAlign);
		writeLe16(out, bitsPerSample);
		writeAscii(out, "data");
		writeLe32(out, dataLen);
		out.write(pcmData, 0, dataLen);
		return out.toByteArray();
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

	private static void writeAscii(ByteArrayOutputStream o, String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			o.write(s.charAt(i));
		}
	}

	private static void writeLe16(ByteArrayOutputStream o, int v)
	{
		o.write(v & 0xff);
		o.write((v >> 8) & 0xff);
	}

	private static void writeLe32(ByteArrayOutputStream o, int v)
	{
		o.write(v & 0xff);
		o.write((v >> 8) & 0xff);
		o.write((v >> 16) & 0xff);
		o.write((v >> 24) & 0xff);
	}
}
