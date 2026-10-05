package com.tumuyan.ncnn.realsr;
/** Pure policy for the GPU-only, non-neural Anime4K v0.9 port. */
public final class ShaderUpscalePolicy {
  private ShaderUpscalePolicy() {}
  public static boolean supportsScale(int scale) { return scale == 2 || scale == 4; }
  // Three RGBA8 render targets + packed readback + ARGB staging; bounded per job.
  public static final long MAX_OUTPUT_PIXELS = 8388608L;
  public static int outputDimension(int source, int scale) {
    if (source <= 0 || !supportsScale(scale) || (long) source * scale > 8192)
      throw new IllegalArgumentException("Anime4K shader supports 2x/4x, at most 8192 per axis");
    return source * scale;
  }
  public static void validate(int width, int height, int scale, int maxTextureSize) {
    int w = outputDimension(width, scale), h = outputDimension(height, scale);
    if (w > maxTextureSize || h > maxTextureSize || (long) w * h > MAX_OUTPUT_PIXELS)
      throw new IllegalArgumentException("Anime4K output exceeds GPU texture limit or 8 megapixel shader budget");
  }
}
