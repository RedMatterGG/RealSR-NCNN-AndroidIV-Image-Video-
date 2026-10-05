package com.tumuyan.ncnn.realsr;
import org.junit.Test;
import static org.junit.Assert.*;
public class NcnnSettingsTest {
  @Test public void middleCountIsCpuIntraOpThreads() {
    assertEquals(6, NcnnSettings.cpuThreads("1:6:1"));
    assertEquals(3, NcnnSettings.cpuThreads("8:3:7"));
    assertEquals(1, NcnnSettings.cpuThreads(null));
  }
  @Test public void rejectsUnsafeCounts() {
    for (String s : new String[]{"1:0:1", "1:-2:1", "1:2,2:1", "1:999999999999:1", "1:257:1", "1:2"}) {
      try { NcnnSettings.cpuThreads(s); fail(s); } catch (IllegalArgumentException expected) {}
    }
  }
  @Test public void packagedModelPathsArePreserved() {
    assertEquals("up2x-conservative", NcnnSettings.stem("realcugan-ncnn",2,-1));
    assertEquals("up3x-no-denoise", NcnnSettings.stem("realcugan-ncnn",3,0));
    assertEquals("up4x-denoise3x", NcnnSettings.stem("realcugan-ncnn",4,3));
    assertEquals("x4", NcnnSettings.stem("realsr-ncnn",4,0));
  }
  @Test public void smallNeuralModelIsNative2xOnly() {
    assertEquals("x2", NcnnSettings.stem("fsrcnn-ncnn",2,0));
    for (int scale : new int[]{1,3,4}) {
      try { NcnnSettings.stem("fsrcnn-ncnn",scale,0); fail(); } catch (IllegalArgumentException expected) {}
    }
    for (int noise : new int[]{-1,1,2,3}) {
      try { NcnnSettings.stem("fsrcnn-ncnn",2,noise); fail(); } catch (IllegalArgumentException expected) {}
    }
  }
  @Test public void waifuUpconvIsNative2xWithOfficialNoiseNames() {
    assertEquals("scale2.0x_model",NcnnSettings.stem("waifu2x-ncnn",2,-1));
    for(int n=0;n<=3;n++) assertEquals("noise"+n+"_scale2.0x_model",NcnnSettings.stem("waifu2x-ncnn",2,n));
    for(int s:new int[]{1,3,4})try {NcnnSettings.stem("waifu2x-ncnn",s,0);fail();}catch(IllegalArgumentException expected){}
    for(int n:new int[]{-2,4})try {NcnnSettings.stem("waifu2x-ncnn",2,n);fail();}catch(IllegalArgumentException expected){}
  }
  @Test public void lightweightAliasRejectsDifferentGraphFamily() {
    NcnnSettings.validateModel("fsrcnn-ncnn","models-FSRCNN-small");
    NcnnSettings.validateModel("waifu2x-ncnn","models-upconv_7_anime_style_art_rgb");
    NcnnSettings.validateModel("waifu2x-ncnn","models-upconv_7_photo");
    NcnnSettings.validateModel("realsr-ncnn","models-RealeSR-general-v3");
    for(String[] pair:new String[][]{{"fsrcnn-ncnn","models-se"},{"waifu2x-ncnn","models-cunet"}})
      try {NcnnSettings.validateModel(pair[0],pair[1]);fail();}catch(IllegalArgumentException expected){}
  }
  @Test public void refusesUnsupportedEngine() {
    try { NcnnSettings.stem("missing-ncnn",2,0); fail(); } catch (IllegalArgumentException expected) {}
  }
}
