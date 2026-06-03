uniform bool chunkblazer_useHardBorder;
uniform vec4 chunkblazer_configGrayColor;
uniform float chunkblazer_configGrayAmount;
uniform int chunkblazer_shadingLevel; // 0 = LIGHT, 1 = HEAVY, 2 = SILHOUETTE (matches ShadingLevel.ordinal())

in float chunkblazer_grayAmount;

float chunkblazer_blendSoftLight(float base, float blend) {
  return blend < 0.5 ?
    2.0 * base * blend + base * base * (1.0 - 2.0 * blend) :
    sqrt(base) * (2.0 * blend - 1.0) + 2.0 * base * (1.0 - blend);
}

vec3 chunkblazer_blendSoftLight(vec3 base, vec3 blend, float opacity) {
  blend = vec3(
    chunkblazer_blendSoftLight(base.r, blend.r),
    chunkblazer_blendSoftLight(base.g, blend.g),
    chunkblazer_blendSoftLight(base.b, blend.b)
  );
  return mix(base, blend, opacity);
}

void chunkblazer_frag(inout vec4 color) {
  float finalGrayAmount = chunkblazer_grayAmount;
  if (chunkblazer_useHardBorder && finalGrayAmount > 0.0)
    finalGrayAmount = 1.0;

  // Jagex bakes directional terrain lighting into face colours, so the pixel's
  // luminance already encodes the relief of slopes, hills, cliffs and mountains.
  // Every level below is built on that single luminance value.
  float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
  vec3 shaded;

  if (chunkblazer_shadingLevel == 2) {
    // SILHOUETTE: crush luminance with a gamma curve so flat, evenly-lit ground
    // collapses toward black while brighter lit slopes survive as faint
    // outlines. No hue is kept — only the shape of the terrain reads through.
    float relief = pow(clamp(lum, 0.0, 1.0), 3.0) * 0.65;
    shaded = vec3(relief);
    // Allow the configured tint to wash the silhouette (e.g. a cold blue night),
    // at half strength so it stays dark.
    shaded = chunkblazer_blendSoftLight(
      shaded, chunkblazer_configGrayColor.rgb, chunkblazer_configGrayColor.a * 0.5);
  } else if (chunkblazer_shadingLevel == 1) {
    // HEAVY: fully desaturate and darken — clearly off-limits but still legible.
    shaded = vec3(lum * 0.45);
    shaded = chunkblazer_blendSoftLight(
      shaded, chunkblazer_configGrayColor.rgb, chunkblazer_configGrayColor.a);
  } else {
    // LIGHT (default): partial desaturation governed by the Grey Amount slider,
    // then a soft-light tint. Identical to the original wash.
    shaded = mix(color.rgb, vec3(lum), chunkblazer_configGrayAmount);
    shaded = chunkblazer_blendSoftLight(
      shaded, chunkblazer_configGrayColor.rgb, chunkblazer_configGrayColor.a);
  }

  color.rgb = mix(color.rgb, shaded, finalGrayAmount);
}
