package net.runelite.client.plugins.chunkblazer;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
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
		AREA_TO_FOLDER.put("Kourend", "Kourend_Sounds");
		AREA_TO_FOLDER.put("Great Kourend", "Kourend_Sounds");
		AREA_TO_FOLDER.put("Desert", "Desert_Sounds");
		AREA_TO_FOLDER.put("Kharidian Desert", "Desert_Sounds");
		AREA_TO_FOLDER.put("Varlamore", "Varlamore_Sounds");
	}

	// Cache of loaded sound files per area
	private final Map<String, List<String>> areaSoundFiles = new HashMap<>();
	private final Random random = new Random();
	private final ChunkBlazerConfig config;

	private Clip currentClip;

	@Inject
	public TaskCompletionSoundManager(ChunkBlazerConfig config)
	{
		this.config = config;
		initializeSoundFiles();
	}

	/**
	 * Initialize the list of available sound files for each area.
	 */
	private void initializeSoundFiles()
	{
		// Pre-define known sound files for each region
		areaSoundFiles.put("Misthalin_Sounds", List.of(
			"Correct!_(Recipe_for_Disaster_-_Lumbridge_Guide).wav",
			"Fancy_Stone_Entrance_(POH).wav",
			"Logic_(Recruitment_Drive).wav",
			"Quest_Complete_1.wav",
			"Quest_Complete_3.wav",
			"Rift_Closed_(Guardians_of_the_Rift).wav",
			"Task_Mastered_(Leagues).wav",
			"The_Watchtower_Shield.wav",
			"Tinsay_Satisfied_(Tai_Bwo_Wannai_Trio).wav"
		));

		areaSoundFiles.put("Asgarnia_Sounds", List.of(
			"Draw_(Burthorpe_Games_Room).wav",
			"Maze_Centre.wav",
			"Memory_(Recruitment_Drive).wav",
			"Pest_Controlled_(Pest_Control).wav",
			"Safe_Cracked_(Rouges__Den).wav",
			"Treasure!_(Treasure_Trails).wav",
			"Victory!_(Burthorpe_Games_Room).wav",
			"You_Are_Victorious!_(Emir_s_Arena).wav"
		));

		areaSoundFiles.put("Kandarin_Sounds", List.of(
			"A_Forgettable_Puzzle..._(Forgettable_Tale...).wav",
			"Box_of_Health_(Stronghold_of_Security).wav",
			"Case_Closed_(King_s_Ransom).wav",
			"Draw._(Castle_Wars).wav",
			"Gnomeball_GOAL!.wav",
			"King_has_Come_(King_s_Ransom).wav",
			"Victory!_(Castle_Wars).wav"
		));

		areaSoundFiles.put("Karamja_Sounds", List.of(
			"All_Easy_Tasks_(Karamja_Diary).wav",
			"Easy_Task_(Karamja_Diary).wav",
			"Hard_Task_(Karamja_Diary).wav",
			"Last_Man_Standing!_(Fight_Pits).wav",
			"Meanwhile._(Monkey_Madness).wav",
			"Medium_Task_(Karamja_Diary).wav",
			"Tagged_a_Ticket!_(Brimhaven_Agility_Arena).wav",
			"The_Fight_Continues_(Fight_Cave).wav"
		));

		areaSoundFiles.put("Morytania_Sounds", List.of(
			"Air_Guitar.wav",
			"Canifis_Entrance_(POH).wav",
			"Danger_Evaded_(Temple_Trekking).wav",
			"Dangers_of_Morytania_(Temple_Trekking).wav",
			"Deathly_Mansion_Entrance_(POH).wav",
			"Petrification_of_the_Basilisk_(The_Fremennik_Exiles).wav",
			"Rat_Beats_Cat_(Rat_Pits).wav",
			"Trek_Continues_(Temple_Trekking).wav",
			"Trek_Destination_(Temple_Trekking).wav"
		));

		areaSoundFiles.put("Fremmy_Sounds", List.of(
			"Ballad_Refrain_(Fremennik_Trials).wav",
			"Border_Broken_(Leagues).wav",
			"Fremennik-Style_Wood_Entrance_(POH).wav",
			"Making_Sense_of_Dwarven_Schematics_(Between_a_Rock).wav",
			"Perfectly_Tuned_(Fremennik_Trials).wav",
			"The_Royal_Decree_(The_Fremennik_Isles).wav",
			"Tiadeche_Thankful_(Tai_Bwo_Wannai_Trio).wav"
		));

		areaSoundFiles.put("Tirannwn_Sounds", List.of(
			"Audience_of_Nature.wav",
			"Clearing_the_Gauntlet.wav",
			"Fairy_Queen_Awakens!_(A_Fairy_Tale_Part_II).wav",
			"Flamtaer_Restored.wav",
			"Quest_Complete_2.wav",
			"Star_of_Your_Own_(Shooting_Stars).wav",
			"Stealing_from_the_Godfather_(A_Fairy_Tale_Part_II).wav",
			"The_Chest_of_Light_(Mourning_s_End_Part_II).wav"
		));

		areaSoundFiles.put("Wilderness_Sounds", List.of(
			"An_Ogre_Sail.wav",
			"Defeated!_(Soul_Wars).wav",
			"Honourable_Victory!_(Barbarian_Assault).wav",
			"Oh_Dear!.wav",
			"Sudden_Cry_(The_Eyes_of_Glouphrie).wav",
			"Sword_Good._Hand_Over._(Giants__Foundry).wav",
			"Victorious!_(Soul_Wars).wav",
			"Void_Knight_Defeated..._(Pest_Control).wav"
		));

		areaSoundFiles.put("Kourend_Sounds", List.of(
			"Commence_The_Fight!_(Duel_Arena).wav",
			"Hosidius_Entrance_(POH).wav",
			"Lucky_Win_(Death_Plateau).wav",
			"Observation_(Recruitment_Drive).wav",
			"Order_(Recruitment_Drive).wav",
			"Relic_of_Power_(Leagues).wav",
			"Tamayu_Slays_the_Shaikahan_(Tai_Bwo_Wannai_Trio).wav"
		));

		areaSoundFiles.put("Desert_Sounds", List.of(
			"Icthlarin_s_Little_Puzzle.wav",
			"Rune_Casket_Open!_(Rouge_Trader).wav",
			"Snake_Charming_(Pyramid_Plunder).wav",
			"Snake_Charming_(The_Feud).wav",
			"Top_of_the_Pyramid!.wav",
			"Whitewashed_Stone_Entrance_(POH).wav"
		));

		areaSoundFiles.put("Varlamore_Sounds", List.of(
			"A_New_Champion!_(Champion_s_Challenge).wav",
			"Civitas_Entrance_(POH).wav",
			"First_Sunshine_(Death_to_the_Dorgeshuun).wav",
			"Scape_Jingle.wav",
			"Star_of_Your_Own_(Shooting_Stars).wav",
			"Tinsay_Satisfied_(Tai_Bwo_Wannai_Trio).wav"
		));

		log.info("TaskCompletionSoundManager initialized with {} area mappings and {} sound folders",
			AREA_TO_FOLDER.size(), areaSoundFiles.size());
	}

	/**
	 * Play a random sound for the given area.
	 * @param area The area name (e.g., "Misthalin", "Asgarnia")
	 */
	public void playRandomSoundForArea(String area)
	{
		if (area == null || area.isEmpty())
		{
			log.debug("No area provided for sound playback");
			return;
		}

		String folder = AREA_TO_FOLDER.get(area);
		if (folder == null)
		{
			log.debug("No sound folder mapping for area: {}", area);
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
				log.debug("Could not find sound folder for area: {}", area);
				return;
			}
		}

		List<String> sounds = areaSoundFiles.get(folder);
		if (sounds == null || sounds.isEmpty())
		{
			// Try to load sounds dynamically
			sounds = discoverSoundsInFolder(folder);
			if (sounds.isEmpty())
			{
				log.debug("No sounds available for folder: {}", folder);
				return;
			}
			areaSoundFiles.put(folder, sounds);
		}

		// Pick a random sound
		String soundFile = sounds.get(random.nextInt(sounds.size()));
		String fullPath = SOUNDS_BASE_PATH + folder + "/" + soundFile;

		log.info("Playing sound for area {}: {}", area, soundFile);
		playSound(fullPath);
	}

	/**
	 * Discover sound files in a folder by trying common names.
	 */
	private List<String> discoverSoundsInFolder(String folder)
	{
		List<String> discovered = new ArrayList<>();

		// Try to load the folder's sounds by checking if resources exist
		String[] commonSounds = {
			"Quest_Complete_1.wav",
			"Quest_Complete_2.wav",
			"Quest_Complete_3.wav",
			"Task_Mastered_(Leagues).wav"
		};

		for (String sound : commonSounds)
		{
			String path = SOUNDS_BASE_PATH + folder + "/" + sound;
			try (InputStream is = getClass().getResourceAsStream(path))
			{
				if (is != null)
				{
					discovered.add(sound);
				}
			}
			catch (Exception e)
			{
				// Ignore - file doesn't exist
			}
		}

		return discovered;
	}

	/**
	 * Play a sound file from resources.
	 * @param resourcePath Path to the sound file relative to the plugin package
	 */
	private void playSound(String resourcePath)
	{
		// Stop any currently playing sound
		stopCurrentSound();

		log.info("Attempting to play sound: {}", resourcePath);

		try
		{
			InputStream is = getClass().getResourceAsStream(resourcePath);
			if (is == null)
			{
				log.error("Sound file not found at path: {}", resourcePath);
				return;
			}

			log.info("Sound file found, attempting to load audio stream...");

			BufferedInputStream bis = new BufferedInputStream(is);
			AudioInputStream ais = AudioSystem.getAudioInputStream(bis);

			log.info("Audio stream loaded, format: {}", ais.getFormat());

			currentClip = AudioSystem.getClip();
			currentClip.open(ais);

			// Set volume if available
			if (currentClip.isControlSupported(FloatControl.Type.MASTER_GAIN))
			{
				FloatControl volume = (FloatControl) currentClip.getControl(FloatControl.Type.MASTER_GAIN);
				// Convert percentage to decibels (-80 to 6 dB range typically)
				float volumePercent = 0.25f; // 25% volume baseline
				float dB = (float) (Math.log(volumePercent) / Math.log(10.0) * 20.0);
				volume.setValue(Math.max(volume.getMinimum(), Math.min(volume.getMaximum(), dB)));
			}

			currentClip.start();
			log.info("Started playing sound: {}", resourcePath);
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
