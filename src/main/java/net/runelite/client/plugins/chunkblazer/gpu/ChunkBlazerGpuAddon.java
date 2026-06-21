package net.runelite.client.plugins.chunkblazer.gpu;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.chunkblazer.ChunkBlazerConfig;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class ChunkBlazerGpuAddon
{
	@Inject
	private Client client;

	@Inject
	private ChunkBlazerConfig config;

	// Why we DON'T @Inject ChunkBlazerPlugin here: this addon lives in the
	// ChunkBlazerGpuPlugin child injector, which has no binding for the main
	// ChunkBlazerPlugin (each RuneLite plugin gets its own child injector).
	// Asking Guice for ChunkBlazerPlugin triggers Just-In-Time construction
	// of a *fresh* ChunkBlazerPlugin instance — and ChunkBlazerPlugin's own
	// @Inject graph contains ChunkBlazerSceneOverlay which itself injects
	// ChunkBlazerPlugin, so JIT recurses indefinitely:
	//   "Recursive load of: ChunkBlazerPlugin.<init>()"
	// Instead, read the unlocked-region set directly from the config string,
	// which is the same thing ChunkBlazerPlugin.isRegionUnlocked does
	// internally. Cached per-frame to avoid re-parsing.

	// The GPU plugin renders the extended 184x184 scene, which can span more
	// regions than the old fixed 16-slot list could hold — that overflow is why
	// far locked chunks rendered in colour until you walked closer. Instead of a
	// capped list we upload a per-region locked/unlocked grid covering the whole
	// rendered scene. GRID must match CHUNKBLAZER_GRID in vert.glsl.
	private static final int GRID = 7;
	private final int[] lockedGrid = new int[GRID * GRID];
	// Free (dungeon / non-overworld) regions from Free_Chunks.json, loaded once and
	// cached. They render full-colour like unlocked regions. Loaded here directly
	// (not via ChunkBlazerPlugin.isRegionUnlocked) because this addon lives in a
	// separate child injector and can't reference the main plugin — same reason
	// the unlocked set is read from config rather than the plugin.
	private Set<Integer> freeRegions = null;

	private boolean isValid;
	private int glProgram;
	private int uniUseGray;
	private int uniUseHardBorder;
	private int uniGrayAmount;
	private int uniGrayColor;
	private int uniShadingLevel;
	private int uniBaseX;
	private int uniBaseY;
	private int uniLockedGrid;

	public void reset()
	{
		isValid = false;
		glProgram = 0;
	}

	public void beforeRender(int glProgram)
	{
		if (client.getGameState().getState() < GameState.LOADING.getState())
		{
			return;
		}

		if (this.glProgram != glProgram)
		{
			this.glProgram = glProgram;
			uniUseGray = glGetUniformLocation(glProgram, "chunkblazer_useGray");
			uniUseHardBorder = glGetUniformLocation(glProgram, "chunkblazer_useHardBorder");
			uniGrayAmount = glGetUniformLocation(glProgram, "chunkblazer_configGrayAmount");
			uniGrayColor = glGetUniformLocation(glProgram, "chunkblazer_configGrayColor");
			uniShadingLevel = glGetUniformLocation(glProgram, "chunkblazer_shadingLevel");
			uniBaseX = glGetUniformLocation(glProgram, "chunkblazer_baseX");
			uniBaseY = glGetUniformLocation(glProgram, "chunkblazer_baseY");
			uniLockedGrid = glGetUniformLocation(glProgram, "chunkblazer_lockedGrid");
			isValid = uniUseGray != -1;
			checkGLErrors();
		}

		if (isValid)
		{
			updateUniforms();
		}

		checkGLErrors();
	}

	private void updateUniforms()
	{
		var vw = client.getTopLevelWorldView();
		if (vw == null)
		{
			return;
		}

		// Get the currently bound program, so we can restore the state later if needed
		int currentProgram = glGetInteger(GL_CURRENT_PROGRAM);
		if (currentProgram != glProgram)
		{
			glUseProgram(glProgram);
		}

		// Region Locker reads these from a static singleton it owns; we read
		// from ChunkBlazer's config instead. Defaults map to slaytostay's
		// out-of-the-box settings (50% gray amount, soft black tint, soft
		// border) so the visual feels familiar.
		Color tint = config.gpuGrayTint();
		glUniform1i(uniUseHardBorder, config.gpuHardBorder() ? 1 : 0);
		glUniform1f(uniGrayAmount, config.gpuGrayAmount() / 255f);
		glUniform1i(uniShadingLevel, config.gpuShadingLevel().ordinal());
		glUniform4f(uniGrayColor,
			tint.getRed()   / 255f,
			tint.getGreen() / 255f,
			tint.getBlue()  / 255f,
			tint.getAlpha() / 255f
		);

		var mapRegions = vw.getMapRegions();

		// Snapshot unlocked-region set once per frame from the config string,
		// rather than calling into ChunkBlazerPlugin (cross-injector, see
		// note on the class fields). Same parsing as
		// ChunkBlazerPlugin.getUnlockedRegionIds(), just inlined.
		Set<Integer> unlocked = readUnlockedRegionIds();
		// Dungeon / non-overworld regions are always full-colour (never greyed).
		Set<Integer> free = freeRegions();

		// Don't grey out instanced areas (raids, GoTR, etc.) when the instance
		// happens to share coordinates with an unlocked region — the shader
		// can't tell the cloned region apart from the original. Mirrors
		// Region Locker's behavior, but with our "unlocked = on the safe list"
		// semantics flipped relative to theirs.
		boolean instanceCoincidesWithUnlockedRegion = false;
		if (vw.isInstance() && mapRegions != null)
		{
			for (int region : mapRegions)
			{
				if (isAccessible(region, unlocked, free))
				{
					instanceCoincidesWithUnlockedRegion = true;
					break;
				}
			}
		}

		if (!config.useGpuGreyscale() || instanceCoincidesWithUnlockedRegion)
		{
			glUniform1i(uniUseGray, 0);
		}
		else
		{
			glUniform1i(uniUseGray, 1);
			glUniform1i(uniBaseX, vw.getBaseX() * 128);
			glUniform1i(uniBaseY, vw.getBaseY() * 128);

			// Build a per-region locked/unlocked grid covering the whole
			// rendered (extended) scene, straight from the unlocked-config set.
			// Unlike the old approach this does NOT depend on getMapRegions(),
			// so far chunks at the edge of draw distance grey correctly instead
			// of only once you walk into them. The grid origin is one region
			// before the scene base; the >>6 and -1 here must match the
			// gridBase math in vert.glsl, and GRID must match CHUNKBLAZER_GRID.
			int gridBaseRegionX = (vw.getBaseX() >> 6) - 1;
			int gridBaseRegionY = (vw.getBaseY() >> 6) - 1;
			for (int gx = 0; gx < GRID; ++gx)
			{
				for (int gy = 0; gy < GRID; ++gy)
				{
					int regionId = ((gridBaseRegionX + gx) << 8) | (gridBaseRegionY + gy);
					lockedGrid[gx * GRID + gy] = isAccessible(regionId, unlocked, free) ? 0 : 1;
				}
			}

			glUniform1iv(uniLockedGrid, lockedGrid);
		}

		// Restore the previous state
		if (glProgram != currentProgram)
		{
			glUseProgram(currentProgram);
		}
	}

	private void checkGLErrors()
	{
		int error;
		while ((error = glGetError()) != GL_NO_ERROR)
		{
			log.error("glGetError: {}", error);
		}
	}

	/**
	 * A region renders full-colour (not greyed) if it's unlocked, an explicit
	 * free-chunk override, or an off-map dungeon by the coordinate rule: regionY
	 * outside the overworld surface band 39..64. Mirrors
	 * ChunkBlazerPlugin.isFreeRegion so the shader and the rest of the plugin agree.
	 */
	private boolean isAccessible(int regionId, Set<Integer> unlocked, Set<Integer> free)
	{
		int regionY = regionId & 0xFF;
		if (regionY < 39 || regionY > 64)
		{
			return true;
		}
		return unlocked.contains(regionId) || free.contains(regionId);
	}

	/**
	 * Load (and cache) the free / dungeon region IDs from Free_Chunks.json so the
	 * shader leaves them full-colour. Read once; on failure returns an empty set
	 * and the addon behaves as before. Absolute classpath path so it resolves from
	 * this {@code .gpu} subpackage.
	 */
	private Set<Integer> freeRegions()
	{
		if (freeRegions != null)
		{
			return freeRegions;
		}
		Set<Integer> ids = new HashSet<>();
		try (java.io.InputStream is = ChunkBlazerConfig.class.getResourceAsStream(
			"/net/runelite/client/plugins/chunkblazer/Free_Chunks.json"))
		{
			if (is != null)
			{
				String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);
				if (obj != null && obj.has("free_regions") && obj.get("free_regions").isJsonArray())
				{
					for (com.google.gson.JsonElement el : obj.getAsJsonArray("free_regions"))
					{
						ids.add(el.getAsInt());
					}
				}
			}
		}
		catch (Exception e)
		{
			log.warn("GPU addon: failed to load Free_Chunks.json: {}", e.getMessage());
		}
		freeRegions = ids;
		log.debug("GPU addon: loaded {} free (dungeon) regions", ids.size());
		return freeRegions;
	}

	/**
	 * Parse {@code chunkblazer.unlockedChunks} (comma-separated region IDs) into
	 * a {@code Set<Integer>}. Mirrors ChunkBlazerPlugin.getUnlockedRegionIds()
	 * but inlined here so the addon doesn't need a cross-injector reference to
	 * ChunkBlazerPlugin (which would trigger Guice JIT recursion — see class
	 * comment). Returns an empty set if the config is missing or malformed.
	 */
	private Set<Integer> readUnlockedRegionIds()
	{
		String chunkList = config.unlockedChunks();
		if (chunkList == null || chunkList.isEmpty())
		{
			return java.util.Collections.emptySet();
		}
		Set<Integer> ids = new HashSet<>();
		for (String token : chunkList.split(","))
		{
			String trimmed = token.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			try
			{
				ids.add(Integer.parseInt(trimmed));
			}
			catch (NumberFormatException ignored)
			{
				// silently skip malformed entries — same behavior as
				// ChunkBlazerPlugin.getUnlockedRegionIds()
			}
		}
		return ids;
	}
}
