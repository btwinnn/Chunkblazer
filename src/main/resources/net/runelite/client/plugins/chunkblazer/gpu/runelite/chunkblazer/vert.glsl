// Grid covering the rendered (extended, up to 184x184) scene. The GPU plugin
// draws more regions than a small fixed list could hold, which is why far
// locked chunks used to stay in colour until you walked closer. CHUNKBLAZER_GRID
// must match GRID in ChunkBlazerGpuAddon. 7 comfortably covers the ~4-5 regions
// per axis an extended scene can span.
#define CHUNKBLAZER_GRID 7
uniform int chunkblazer_useGray;
uniform int chunkblazer_baseX;
uniform int chunkblazer_baseY;
uniform int chunkblazer_lockedGrid[CHUNKBLAZER_GRID * CHUNKBLAZER_GRID];

out float chunkblazer_grayAmount;

// Locked flag (1.0 / 0.0) for the region containing world-unit position (wx, wy).
// gridBaseX/Y are the grid's origin region; positions outside the grid return
// 0.0 (never greyed — fails safe).
float chunkblazer_lockedAt(int wx, int wy, int gridBaseX, int gridBaseY) {
  int gx = (wx >> 13) - gridBaseX;
  int gy = (wy >> 13) - gridBaseY;
  if (gx < 0 || gy < 0 || gx >= CHUNKBLAZER_GRID || gy >= CHUNKBLAZER_GRID) {
    return 0.0;
  }
  return float(chunkblazer_lockedGrid[gx * CHUNKBLAZER_GRID + gy]);
}

void chunkblazer_vert(vec3 vertex) {
  // Sub-tile offsets so a vertex sitting exactly on a region boundary still
  // counts as locked when an adjacent region is locked (avoids thin seams).
  const ivec2 offsets[5] = ivec2[](
    ivec2(0, 0),
    ivec2(-1, -1),
    ivec2(-1, 1),
    ivec2(1, -1),
    ivec2(1, 1)
  );

  // World position in 1/128-tile units; >>13 converts to region coords
  // (7 bits units->tiles, 6 bits tiles->region).
  int wx = int(vertex.x) + chunkblazer_baseX;
  int wy = int(vertex.z) + chunkblazer_baseY;

  // Grid origin: one region before the scene base. Must match the addon's
  // gridBaseRegion = (getBaseX() >> 6) - 1.
  int gridBaseX = (chunkblazer_baseX >> 13) - 1;
  int gridBaseY = (chunkblazer_baseY >> 13) - 1;

  float locked = 0.0;
  for (int i = 0; i < 5; ++i) {
    locked = max(locked, chunkblazer_lockedAt(wx + offsets[i].x, wy + offsets[i].y, gridBaseX, gridBaseY));
  }

  chunkblazer_grayAmount = float(chunkblazer_useGray) * locked;
}
