package com.chunkblazer;

import com.chunkblazer.api.AssetStore;
import com.chunkblazer.api.AudioAsset;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages and plays region-specific sounds for task completion.
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

	private final Random random = new Random();
	private final ChunkBlazerConfig config;
	private final AssetStore assetStore;

	private Clip currentClip;

	@Inject
	public TaskCompletionSoundManager(ChunkBlazerConfig config, AssetStore assetStore)
	{
		this.config = config;
		this.assetStore = assetStore;
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
		playSound(SEED_SOUND);
	}

	/**
	 * Play a sound file bundled in plugin resources.
	 * @param resourcePath Path to the sound file relative to the plugin package
	 */
	private void playSound(String resourcePath)
	{
		try
		{
			InputStream is = getClass().getResourceAsStream(resourcePath);
			if (is == null)
			{
				log.error("Sound file not found at path: {}", resourcePath);
				return;
			}
			try (AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(is)))
			{
				startClip(ais);
			}
		}
		catch (javax.sound.sampled.UnsupportedAudioFileException e)
		{
			log.error("Unsupported audio format for: {} - OGG files require conversion to WAV", resourcePath, e);
		}
		catch (Exception e)
		{
			log.error("Failed to play sound: {} - {}", resourcePath, e.getMessage(), e);
		}
	}

	/**
	 * Play a sound from a cached asset file on disk (the server-delivered copy).
	 * Streamed straight off disk — nothing is held decoded in memory between
	 * plays. µ-law WAVs are decoded natively by javax.sound.sampled.
	 * @param file The cached .wav file
	 */
	private void playFile(File file)
	{
		try (FileInputStream fis = new FileInputStream(file);
			AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(fis)))
		{
			startClip(ais);
		}
		catch (javax.sound.sampled.UnsupportedAudioFileException e)
		{
			log.error("Unsupported audio format for cached asset: {}", file, e);
		}
		catch (Exception e)
		{
			log.error("Failed to play cached asset {}: {}", file, e.getMessage(), e);
		}
	}

	/**
	 * Open and start a clip from an already-opened audio stream, applying the
	 * configured volume. Stops any currently-playing clip first. Shared by the
	 * bundled-resource and cached-file play paths.
	 */
	private void startClip(AudioInputStream ais) throws Exception
	{
		// Stop any currently playing sound
		stopCurrentSound();

		// A Clip output line can't open compressed encodings (µ-law/A-law) directly
		// on most mixers — javax.sound can READ them but not play them raw. Decode
		// to 16-bit signed PCM first (still no external dependency; Java Sound does
		// the conversion in-memory). Bundled PCM WAVs pass through unchanged.
		AudioFormat src = ais.getFormat();
		if (src.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
			&& src.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED)
		{
			AudioFormat pcm = new AudioFormat(
				AudioFormat.Encoding.PCM_SIGNED,
				src.getSampleRate(),
				16,
				src.getChannels(),
				src.getChannels() * 2,
				src.getSampleRate(),
				false);
			ais = AudioSystem.getAudioInputStream(pcm, ais);
		}

		currentClip = AudioSystem.getClip();
		currentClip.open(ais);

		// Set volume if available
		if (currentClip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			FloatControl volume = (FloatControl) currentClip.getControl(FloatControl.Type.MASTER_GAIN);
			// Convert percentage to decibels (-80 to 6 dB range typically).
			// 0.03f (3%) was hardcoded here, which works out to -30dB — the
			// clip really did play, it was just inaudible. Now player-tunable.
			int configured = config != null ? config.taskCompletionSoundVolume() : 3;
			float volumePercent = Math.max(0.001f, Math.min(1.0f, configured / 100.0f));
			float dB = (float) (Math.log(volumePercent) / Math.log(10.0) * 20.0);
			volume.setValue(Math.max(volume.getMinimum(), Math.min(volume.getMaximum(), dB)));
		}

		currentClip.start();
	}

	/**
	 * Stop the currently playing sound if any.
	 */
	public void stopCurrentSound()
	{
		if (currentClip != null && currentClip.isRunning())
		{
			currentClip.stop();
			currentClip.close();
			currentClip = null;
		}
	}

	/**
	 * Clean up resources.
	 */
	public void shutdown()
	{
		stopCurrentSound();
	}
}
