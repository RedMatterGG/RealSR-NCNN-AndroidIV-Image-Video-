package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoUpscalerCatalogTest {
  @Test public void existingDefaultAndFastAnimeModelArePreserved() {
    assertEquals("models-pro", VideoUpscalerCatalog.OPTIONS.get(0).model);
    VideoUpscalerCatalog.Entry anime = VideoUpscalerCatalog.find("models-Real-ESRGANv3-anime");
    assertEquals("realsr-ncnn", anime.engine);
    assertTrue(anime.label.contains("Fast Anime"));
    anime.validate(2, false);
  }
  @Test public void denoiseChoicesMatchPackagedModelAndScale() {
    assertArrayEquals(new int[]{-1,0,3},VideoUpscalerCatalog.find("models-pro").noises(2));
    assertArrayEquals(new int[]{-1,0,3},VideoUpscalerCatalog.find("models-pro").noises(3));
    assertArrayEquals(new int[]{-1,0,1,2,3},VideoUpscalerCatalog.find("models-se").noises(2));
    assertArrayEquals(new int[]{-1,0,3},VideoUpscalerCatalog.find("models-se").noises(3));
    assertArrayEquals(new int[]{-1,0,3},VideoUpscalerCatalog.find("models-se").noises(4));
    assertArrayEquals(new int[]{0},VideoUpscalerCatalog.find("anime4k-v1-fast").noises(2));
    assertThrows(IllegalArgumentException.class,()->VideoUpscalerCatalog.find("models-pro").validateNoise(2,1));
  }
  @Test public void smallNeuralAndWaifuChoicesHaveExplicitNativeControls() {
    VideoUpscalerCatalog.Entry small=VideoUpscalerCatalog.find("models-FSRCNN-small");
    assertEquals("fsrcnn-ncnn",small.engine);
    small.validate(2,false);
    assertThrows(IllegalArgumentException.class,()->small.validate(4,false));
    assertArrayEquals(new int[]{0},small.noises(2));
    for(String model:new String[]{"models-upconv_7_anime_style_art_rgb","models-upconv_7_photo"}) {
      VideoUpscalerCatalog.Entry waifu=VideoUpscalerCatalog.find(model);
      assertEquals("waifu2x-ncnn",waifu.engine);
      waifu.validate(2,false);
      assertArrayEquals(new int[]{-1,0,1,2,3},waifu.noises(2));
      assertEquals("Upscale only (-1)",waifu.noiseLabel(-1));
      assertEquals("Noise 0 model",waifu.noiseLabel(0));
    }
  }
  @Test public void cuganSeRetainsExistingThreeTimesScale() {
    VideoUpscalerCatalog.find("models-se").validate(3,false);
  }
  @Test public void fastGeneralUsesNativeFourTimesModel() {
    VideoUpscalerCatalog.Entry general = VideoUpscalerCatalog.find("models-RealeSR-general-v3");
    general.validate(4, false);
    assertThrows(IllegalArgumentException.class, () -> general.validate(2, false));
  }
  @Test public void everyAdvertisedNeuralScaleHasPackagedWeights() {
    java.nio.file.Path assets=java.nio.file.Paths.get("src/main/assets/realsr");
    if(!java.nio.file.Files.isDirectory(assets)) assets=java.nio.file.Paths.get("app/src/main/assets/realsr");
    for(VideoUpscalerCatalog.Entry entry:VideoUpscalerCatalog.OPTIONS) {
      if(entry.shader) continue;
      for(int scale:entry.scales()) for(int noise:entry.noises(scale)) {
        String stem=entry.engine.equals("waifu2x-ncnn") ? (noise<0 ? "scale2.0x_model" : "noise"+noise+"_scale2.0x_model") : entry.engine.equals("realcugan-ncnn") ? "up"+scale+"x-"+(noise<0 ? "conservative" : noise==0 ? "no-denoise" : "denoise"+noise+"x") : "x"+scale;
        for(String extension:new String[]{".param",".bin"})
          assertTrue(entry.model+" missing "+stem+extension,java.nio.file.Files.isRegularFile(assets.resolve(entry.model).resolve(stem+extension)));
      }
    }
  }
  @Test public void animeShaderIsNotMisrepresentedAsCpuNeuralInference() {
    VideoUpscalerCatalog.Entry shader = VideoUpscalerCatalog.find("anime4k-v1-fast");
    assertEquals("anime4k-shader", shader.engine);
    assertTrue(shader.shader);
    shader.validate(2, false);
    shader.validate(4, false);
    assertThrows(IllegalArgumentException.class, () -> shader.validate(2, true));
  }
}
