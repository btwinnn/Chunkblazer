uniform bool chunkblazer_useHardBorder;
uniform vec4 chunkblazer_configGrayColor;
uniform float chunkblazer_configGrayAmount;

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
  if (chunkblazer_useHardBorder && finalGrayAmount > 0)
    finalGrayAmount = 1;
  vec3 grayColor = vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114)));
  grayColor = mix(color.rgb, grayColor, chunkblazer_configGrayAmount);
  grayColor = chunkblazer_blendSoftLight(
    grayColor, chunkblazer_configGrayColor.rgb, chunkblazer_configGrayColor.a);
  color.rgb = mix(color.rgb, grayColor, finalGrayAmount);
}
