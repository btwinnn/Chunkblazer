package com.chunkblazer;

import org.junit.jupiter.api.Test;

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
}
