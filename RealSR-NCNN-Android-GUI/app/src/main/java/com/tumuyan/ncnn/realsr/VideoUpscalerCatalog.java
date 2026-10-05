package com.tumuyan.ncnn.realsr;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Model choices are independent of decoder choice; native scales are not post-resize promises. */
public final class VideoUpscalerCatalog {
  private VideoUpscalerCatalog() {}
  public static final class Entry {
    public final String label, engine, model, description;
    public final boolean shader;
    private final int[] scales;
    Entry(String label, String engine, String model, String description, boolean shader, int... scales) {
      this.label=label; this.engine=engine; this.model=model;
      this.description=description; this.shader=shader; this.scales=scales.clone();
    }
    public int[] scales() { return scales.clone(); }
    public int[] noises(int scale) {
      validate(scale,false);
      if(model.equals("models-pro")) return new int[]{-1,0,3};
      if(model.equals("models-se")) return scale==2 ? new int[]{-1,0,1,2,3} : new int[]{-1,0,3};
      if(engine.equals("waifu2x-ncnn")) return new int[]{-1,0,1,2,3};
      return new int[]{0};
    }
    public String noiseLabel(int noise) {
      if(engine.equals("waifu2x-ncnn")) return noise<0 ? "Upscale only (-1)" : "Noise "+noise+" model";
      return noise<0 ? "Conservative (-1)" : noise==0 ? "No denoise (0)" : "Denoise "+noise;
    }
    public void validateNoise(int scale, int noise) {
      for(int supported:noises(scale)) if(supported==noise) return;
      throw new IllegalArgumentException(label+" does not provide noise "+noise+" at "+scale+"x");
    }
    public void validate(int scale, boolean cpu) {
      boolean valid=false;
      for(int supported:scales) if(scale==supported) valid=true;
      if(!valid) throw new IllegalArgumentException(label+" does not support native "+scale+"x");
      if(shader && cpu) throw new IllegalArgumentException("Anime4K shader requires GPU; select GPU backend");
    }
  }
  public static final List<Entry> OPTIONS=Collections.unmodifiableList(Arrays.asList(
    new Entry("Real-CUGAN Pro", "realcugan-ncnn", "models-pro", "Anime restoration; conservative or denoise controls.", false, 2,3),
    new Entry("Real-CUGAN SE", "realcugan-ncnn", "models-se", "Anime restoration; supported noise/scale checked by engine.", false, 2,3,4),
    new Entry("Real-ESRGAN", "realsr-ncnn", "models-Real-ESRGAN", "General neural restoration; heavier quality option.", false, 4),
    new Entry("Real-ESRGAN anime", "realsr-ncnn", "models-Real-ESRGAN-anime", "Anime neural restoration; not AnimeVideo v3.", false, 4),
    new Entry("Real-ESRGAN v2 anime", "realsr-ncnn", "models-Real-ESRGANv2-anime", "Anime neural model.", false, 2,4),
    new Entry("Fast Anime — Real-ESRGAN AnimeVideo v3", "realsr-ncnn", "models-Real-ESRGANv3-anime", "Extra-small anime-video model. Phone speed is not yet benchmarked.", false, 2,3,4),
    new Entry("ESRGAN Nomos8kSC", "realsr-ncnn", "models-ESRGAN-Nomos8kSC", "General neural restoration model.", false, 4),
    new Entry("Fast General — Real-ESRGAN General v3 (4x)", "realsr-ncnn", "models-RealeSR-general-v3", "Small general-scene model; native 4x only, not a cheap 2x model.", false, 4),
    new Entry("Fast 2x — FSRCNN-small", "fsrcnn-ncnn", "models-FSRCNN-small", "Tiny luminance neural model with bicubic chroma reconstruction; native 2x only, no denoise. More conservative detail than restoration models.", false, 2),
    new Entry("Fast Anime 2x — Waifu2x upconv7", "waifu2x-ncnn", "models-upconv_7_anime_style_art_rgb", "Anime RGB neural model; native 2x. -1 is upscale-only; noise 0 uses genuine noise0 weights.", false, 2),
    new Entry("Fast Photo 2x — Waifu2x upconv7", "waifu2x-ncnn", "models-upconv_7_photo", "Photo RGB neural model; native 2x. -1 is upscale-only; noise 0 uses genuine noise0 weights.", false, 2),
    new Entry("Fast Anime shader — Anime4K v1-style", "anime4k-shader", "anime4k-v1-fast", "GPU edge/luma enhancement, not neural restoration or current Anime4K v4. No neural denoise or NCNN tile controls.", true, 2,4)
  ));
  public static Entry find(String model) {
    for(Entry entry:OPTIONS) if(entry.model.equals(model)) return entry;
    throw new IllegalArgumentException("Unknown video model: "+model);
  }
}
