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
			"Correct!_(Recipe_for_Disaster_-_Lumbridge_Guide).ogg",
			"Fancy_Stone_Entrance_(POH).ogg",
			"Logic_(Recruitment_Drive).ogg",
			"Quest_Complete_1.ogg",
			"Quest_Complete_3.ogg",
			"Rift_Closed_(Guardians_of_the_Rift).ogg",
			"Task_Mastered_(Leagues).ogg",
			"The_Watchtower_Shield.ogg",
			"Tinsay_Satisfied_(Tai_Bwo_Wannai_Trio).ogg"
		));

		areaSoundFiles.put("Asgarnia_Sounds", List.of(
			"Draw_(Burthorpe_Games_Room).ogg",
			"Maze_Centre.ogg",
			"Memory_(Recruitment_Drive).ogg",
			"Pest_Controlled_(Pest_Control).ogg",
			"Safe_Cracked_(Rouges__Den).ogg",
			"Treasure!_(Treasure_Trails).ogg",
			"Victory!_(Burthorpe_Games_Room).ogg",
			"You_Are_Victorious!_(Emir_s_Arena).ogg"
		));

		areaSoundFiles.put("Kandarin_Sounds", List.of(
			"A_Forgettable_Puzzle..._(Forgettable_Tale...).ogg",
			"Box_of_Health_(Stronghold_of_Security).ogg",
			"Case_Closed_(King_s_Ransom).ogg",
			"Draw._(Castle_Wars).ogg",
			"Gnomeball_GOAL!.ogg",
			"King_has_Come_(King_s_Ransom).ogg",
			"Victory!_(Castle_Wars).ogg"
		));

		areaSoundFiles.put("Karamja_Sounds", List.of(
			"All_Easy_Tasks_(Karamja_Diary).ogg",
			"Easy_Task_(Karamja_Diary).ogg",
			"Hard_Task_(Karamja_Diary).ogg",
			"Last_Man_Standing!_(Fight_Pits).ogg",
			"Meanwhile._(Monkey_Madness).ogg",
			"Medium_Task_(Karamja_Diary).ogg",
			"Tagged_a_Ticket!_(Brimhaven_Agility_Arena).ogg",
			"The_Fight_Continues_(Fight_Cave).ogg"
		));

		areaSoundFiles.put("Morytania_Sounds", List.of(
			"Air_Guitar.ogg",
			"Canifis_Entrance_(POH).ogg",
			"Danger_Evaded_(Temple_Trekking).ogg",
			"Dangers_of_Morytania_(Temple_Trekking).ogg",
			"Deathly_Mansion_Entrance_(POH).ogg",
			"Petrification_of_the_Basilisk_(The_Fremennik_Exiles).ogg",
			"Rat_Beats_Cat_(Rat_Pits).ogg",
			"Trek_Continues_(Temple_Trekking).ogg",
			"Trek_Destination_(Temple_Trekking).ogg"
		));

		areaSoundFiles.put("Fremmy_Sounds", List.of(
			"Ballad_Refrain_(Fremennik_Trials).ogg",
			"Border_Broken_(Leagues).ogg",
			"Fremennik-Style_Wood_Entrance_(POH).ogg",
			"Making_Sense_of_Dwarven_Schematics_(Between_a_Rock).ogg",
			"Perfectly_Tuned_(Fremennik_Trials).ogg",
			"The_Royal_Decree_(The_Fremennik_Isles).ogg",
			"Tiadeche_Thankful_(Tai_Bwo_Wannai_Trio).ogg"
		));

		areaSoundFiles.put("Tirannwn_Sounds", List.of(
			"Audience_of_Nature.ogg",
			"Clearing_the_Gauntlet.ogg",
			"Fairy_Queen_Awakens!_(A_Fairy_Tale_Part_II).ogg",
			"Flamtaer_Restored.ogg",
			"Quest_Complete_2.ogg",
			"Star_of_Your_Own_(Shooting_Stars).ogg",
			"Stealing_from_the_Godfather_(A_Fairy_Tale_Part_II).ogg",
			"The_Chest_of_Light_(Mourning_s_End_Part_II).ogg"
		));

		areaSoundFiles.put("Wilderness_Sounds", List.of(
			"An_Ogre_Sail.ogg",
			"Defeated!_(Soul_Wars).ogg",
			"Honourable_Victory!_(Barbarian_Assault).ogg",
			"Oh_Dear!.ogg",
			"Sudden_Cry_(The_Eyes_of_Glouphrie).ogg",
			"Sword_Good._Hand_Over._(Giants__Foundry).ogg",
			"Victorious!_(Soul_Wars).ogg",
			"Void_Knight_Defeated..._(Pest_Control).ogg"
		));

		areaSoundFiles.put("Kourend_Sounds", List.of(
			"Commence_The_Fight!_(Duel_Arena).ogg",
			"Hosidius_Entrance_(POH).ogg",
			"Lucky_Win_(Death_Plateau).ogg",
			"Observation_(Recruitment_Drive).ogg",
			"Order_(Recruitment_Drive).ogg",
			"Relic_of_Power_(Leagues).ogg",
			"Tamayu_Slays_the_Shaikahan_(Tai_Bwo_Wannai_Trio).ogg"
		));

		areaSoundFiles.put("Desert_Sounds", List.of(
			"Icthlarin_s_Little_Puzzle.ogg",
			"Rune_Casket_Open!_(Rouge_Trader).ogg",
			"Snake_Charming_(Pyramid_Plunder).ogg",
			"Snake_Charming_(The_Feud).ogg",
			"Top_of_the_Pyramid!.ogg",
			"Whitewashed_Stone_Entrance_(POH).ogg"
		));

		areaSoundFiles.put("Varlamore_Sounds", List.of(
			"A_New_Champion!_(Champion_s_Challenge).ogg",
			"Civitas_Entrance_(POH).ogg",
			"First_Sunshine_(Death_to_the_Dorgeshuun).ogg",
			"Scape_Jingle.ogg",
			"Star_of_Your_Own_(Shooting_Stars).ogg",
			"Tinsay_Satisfied_(Tai_Bwo_Wannai_Trio).ogg"
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
			"Quest_Complete_1.ogg",
			"Quest_Complete_2.ogg",
			"Quest_Complete_3.ogg",
			"Task_Mastered_(Leagues).ogg"
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
				float volumePercent = 0.7f; // 70% volume
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
