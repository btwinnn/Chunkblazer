package net.runelite.client.plugins.chunkblazer.gpu;

import java.awt.Color;
import java.util.Arrays;
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

	private static final int LOCKED_REGIONS_SIZE = 16;
	private final int[] loadedLockedRegions = new int[LOCKED_REGIONS_SIZE];

	private boolean isValid;
	private int glProgram;
	private int uniUseGray;
	private int uniUseHardBorder;
	private int uniGrayAmount;
	private int uniGrayColor;
	private int uniBaseX;
	private int uniBaseY;
	private int uniLockedRegions;

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
			uniBaseX = glGetUniformLocation(glProgram, "chunkblazer_baseX");
			uniBaseY = glGetUniformLocation(glProgram, "chunkblazer_baseY");
			uniLockedRegions = glGetUniformLocation(glProgram, "chunkblazer_lockedRegions");
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
				if (unlocked.contains(region))
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

			// Pack visible region IDs that are NOT unlocked into the
			// shader's fixed-size array (capacity = LOCKED_REGIONS_SIZE,
			// matches CHUNKBLAZER_LOCKED_REGIONS_SIZE in vert.glsl). 0 is
			// a sentinel meaning "no region" — the shader's float-multiply
			// trick treats a zero entry as never matching.
			Arrays.fill(loadedLockedRegions, 0);
			if (mapRegions != null)
			{
				int slot = 0;
				for (int region : mapRegions)
				{
					if (slot >= LOCKED_REGIONS_SIZE)
					{
						break;
					}
					if (!unlocked.contains(region))
					{
						loadedLockedRegions[slot++] = region;
					}
				}
			}

			glUniform1iv(uniLockedRegions, loadedLockedRegions);
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
