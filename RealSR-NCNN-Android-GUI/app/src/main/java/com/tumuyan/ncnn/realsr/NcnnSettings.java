package com.tumuyan.ncnn.realsr;

/** Pure validation shared with native engine preflight. */
final class NcnnSettings {
  static int cpuThreads(String threads) {
    if (threads == null || threads.isEmpty()) return 1;
    if (!threads.matches("[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*"))
      throw new IllegalArgumentException("Thread settings must be load:process:save positive integers");
    String[] parts = threads.split(":");
    try {
      for (String part : parts) if (Integer.parseInt(part) > 256)
        throw new IllegalArgumentException("Thread counts must not exceed 256");
      return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Thread count is too large", e);
    }
  }

  static void validateModel(String engine, String model) {
    if (("fsrcnn-ncnn".equals(engine) && !"models-FSRCNN-small".equals(model)) ||
        ("waifu2x-ncnn".equals(engine) && !"models-upconv_7_anime_style_art_rgb".equals(model) &&
         !"models-upconv_7_photo".equals(model)))
      throw new IllegalArgumentException("Unsupported lightweight native model family: " + model);
  }

  static String stem(String engine, int scale, int noise) {
    if (scale < 1 || scale > 4) throw new IllegalArgumentException("Invalid scale");
    if (engine.equals("waifu2x-ncnn")) {
      if (scale != 2 || noise < -1 || noise > 3) throw new IllegalArgumentException("Waifu2x upconv supports native 2x / noise -1..3 only");
      return noise == -1 ? "scale2.0x_model" : "noise" + noise + "_scale2.0x_model";
    }
    if (engine.equals("fsrcnn-ncnn")) {
      if (scale != 2 || noise != 0) throw new IllegalArgumentException("FSRCNN-small supports native 2x / noise 0 only");
      return "x2";
    }
    if (engine.equals("realcugan-ncnn")) {
      if (noise < -1 || noise > 3) throw new IllegalArgumentException("Invalid CUGAN noise");
      return "up" + scale + "x-" + (noise < 0 ? "conservative" : noise == 0 ? "no-denoise" : "denoise" + noise + "x");
    }
    if (!engine.equals("realsr-ncnn")) throw new IllegalArgumentException("Unsupported video JNI engine: " + engine);
    return "x" + scale;
  }
}
