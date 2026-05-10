#define CHUNKBLAZER_LOCKED_REGIONS_SIZE 16
uniform int chunkblazer_useGray;
uniform int chunkblazer_baseX;
uniform int chunkblazer_baseY;
uniform int chunkblazer_lockedRegions[CHUNKBLAZER_LOCKED_REGIONS_SIZE];

out float chunkblazer_grayAmount;

int chunkblazer_toRegionId(int x, int y) {
  return (x >> 13 << 8) + (y >> 13);
}

float chunkblazer_isLocked(int x, int y) {
  const ivec2 regionOffsets[5] = ivec2[](
    ivec2(0, 0),
    ivec2(-1, -1),
    ivec2(-1, 1),
    ivec2(1, -1),
    ivec2(1, 1)
  );

  x = x + chunkblazer_baseX;
  y = y + chunkblazer_baseY;
  float result = 1.0;
  for (int i = 0; i < CHUNKBLAZER_LOCKED_REGIONS_SIZE; ++i) {
    for (int j = 0; j < regionOffsets.length(); ++j) {
      ivec2 off = regionOffsets[j];
      int region = chunkblazer_toRegionId(x + off.x, y + off.y);
      result = result * (chunkblazer_lockedRegions[i] - region);
    }
  }
  return clamp(abs(result), 0.0, 1.0);
}

void chunkblazer_vert(vec3 vertex) {
  float isLocked = chunkblazer_isLocked(int(vertex.x), int(vertex.z));
  chunkblazer_grayAmount = chunkblazer_useGray * isLocked;
}
