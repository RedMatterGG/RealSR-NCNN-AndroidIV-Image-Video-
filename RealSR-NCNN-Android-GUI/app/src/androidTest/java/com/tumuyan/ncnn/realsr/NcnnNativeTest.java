package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.Test;

/** Actual ARM64 model inference; compile-only until an ARM64 phone is connected. */
public class NcnnNativeTest {
  final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  File prepare(String model,String stem) throws Exception {
    File root=new File(context.getCacheDir(),"native-model-tests");
    File dir=new File(root,model);assertTrue(dir.mkdirs() || dir.isDirectory());
    for(String ext:new String[]{".param",".bin"}) {
      try(InputStream in=context.getAssets().open("realsr/"+model+"/"+stem+ext);
          OutputStream out=new FileOutputStream(new File(dir,stem+ext))) {
        byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
      }
    }
    return root;
  }
  void repeated(boolean cpu,String engine,String model,String stem,int scale) throws Exception {
    repeated(cpu,engine,model,stem,scale,0);
  }
  void repeated(boolean cpu,String engine,String model,String stem,int scale,int noise) throws Exception {
    File root=prepare(model,stem);
    Bitmap input=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);
    input.eraseColor(Color.RED);
    try(NcnnFrameProcessor p=new NcnnFrameProcessor(root,new File(root,"unused"),engine,model,scale,32,cpu,noise,"1:2:1")) {
      String init=p.diagnostics();
      assertTrue(init.contains(cpu?"CPU (NCNN/OpenMP)":"Vulkan GPU"));
      assertTrue(init.contains("CPU intra-op threads=2"));
      int[] first=null;
      for(int i=0;i<3;i++) {
        Bitmap output=p.upscale(input,new VideoPolicy.Control());
        try {
          assertEquals(32*scale,output.getWidth());assertEquals(24*scale,output.getHeight());
          int center=output.getPixel(output.getWidth()/2,output.getHeight()/2);
          assertEquals(255,Color.alpha(center));assertTrue("RGB channels swapped",Color.red(center)>Color.blue(center));
          int[] pixels=new int[output.getWidth()*output.getHeight()];
          output.getPixels(pixels,0,output.getWidth(),0,0,output.getWidth(),output.getHeight());
          if(first==null)first=pixels;else assertArrayEquals("Persistent model repeat differs",first,pixels);
        } finally {output.recycle();}
      }
      assertTrue(p.diagnostics().contains("model loads=1; frames=3"));
      assertTrue(p.diagnostics().contains("last-frame="));
      assertFalse(new File(root,"unused/input.png").exists());
      p.close();p.close();
      try {p.upscale(input,new VideoPolicy.Control());fail("Closed processor ran");}catch(IOException expected){}
    } finally {input.recycle();}
  }
  @Test public void cuganCpuModelLoadedOnce() throws Exception {repeated(true,"realcugan-ncnn","models-se","up2x-no-denoise",2);}
  @Test public void cuganVulkanModelLoadedOnce() throws Exception {repeated(false,"realcugan-ncnn","models-se","up2x-no-denoise",2);}
  @Test public void realSrCpuModelLoadedOnce() throws Exception {repeated(true,"realsr-ncnn","models-Real-ESRGANv3-anime","x2",2);}
  @Test public void realSrVulkanModelLoadedOnce() throws Exception {repeated(false,"realsr-ncnn","models-Real-ESRGANv3-anime","x2",2);}
  @Test public void fsrcnnCpuModelLoadedOnce() throws Exception {repeated(true,"fsrcnn-ncnn","models-FSRCNN-small","x2",2);}
  @Test public void fsrcnnVulkanModelLoadedOnce() throws Exception {repeated(false,"fsrcnn-ncnn","models-FSRCNN-small","x2",2);}
  @Test public void generalCpuModelLoadedOnce() throws Exception {repeated(true,"realsr-ncnn","models-RealeSR-general-v3","x4",4);}
  @Test public void generalVulkanModelLoadedOnce() throws Exception {repeated(false,"realsr-ncnn","models-RealeSR-general-v3","x4",4);}
  void waifuAllNoises(boolean cpu,String model) throws Exception {
    for(int noise:new int[]{-1,0,1,2,3})
      repeated(cpu,"waifu2x-ncnn",model,NcnnSettings.stem("waifu2x-ncnn",2,noise),2,noise);
  }
  @Test public void waifuAnimeCpuAllWeightsLoadedOnce() throws Exception {waifuAllNoises(true,"models-upconv_7_anime_style_art_rgb");}
  @Test public void waifuAnimeVulkanAllWeightsLoadedOnce() throws Exception {waifuAllNoises(false,"models-upconv_7_anime_style_art_rgb");}
  @Test public void waifuPhotoCpuAllWeightsLoadedOnce() throws Exception {waifuAllNoises(true,"models-upconv_7_photo");}
  @Test public void waifuPhotoVulkanAllWeightsLoadedOnce() throws Exception {waifuAllNoises(false,"models-upconv_7_photo");}
  @Test public void lightweightCorruptModelFailsWithoutNativeCrash() throws Exception {
    for(String[] model:new String[][]{{"fsrcnn-ncnn","models-FSRCNN-small","x2"},{"waifu2x-ncnn","models-upconv_7_anime_style_art_rgb","noise0_scale2.0x_model"}}) {
      File root=prepare(model[1],model[2]);
      try(FileOutputStream out=new FileOutputStream(new File(root,model[1]+"/"+model[2]+".param"))) {out.write("bad model".getBytes("UTF-8"));}
      try(NcnnFrameProcessor p=new NcnnFrameProcessor(root,root,model[0],model[1],2,32,true,0,"1:2:1")) {fail("Corrupt lightweight model accepted");}catch(IOException expected){}
    }
  }
  @Test public void corruptModelFailsWithoutNativeCrash() throws Exception {
    File root=prepare("models-se","up2x-no-denoise");
    try(FileOutputStream out=new FileOutputStream(new File(root,"models-se/up2x-no-denoise.param"))) {out.write("bad model".getBytes("UTF-8"));}
    try(NcnnFrameProcessor p=new NcnnFrameProcessor(root,root,"realcugan-ncnn","models-se",2,32,true,0,"1:2:1")) {fail("Corrupt model accepted");}catch(IOException expected){}
  }
  void narrowFrames(boolean cpu) throws Exception {
    File root=prepare("models-se","up2x-no-denoise");
    for(int tile:new int[]{32,128}) {
      try(NcnnFrameProcessor p=new NcnnFrameProcessor(root,root,"realcugan-ncnn","models-se",2,tile,cpu,0,"1:2:1")) {
        for(int[] size:new int[][]{{256,64},{64,256}}) {
          Bitmap input=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
          input.eraseColor(Color.RED);
          try {
            Bitmap output=p.upscale(input,new VideoPolicy.Control());
            try {
              assertEquals(size[0]*2,output.getWidth());assertEquals(size[1]*2,output.getHeight());
              int center=output.getPixel(output.getWidth()/2,output.getHeight()/2);
              assertTrue(Color.red(center)>Color.blue(center));
            } finally {output.recycle();}
          } finally {input.recycle();}
        }
        assertTrue(p.diagnostics().contains("model loads=1; frames=2"));
      }
    }
  }
  @Test public void narrowCuganCpuAvoidsEmptySyncSamples() throws Exception {narrowFrames(true);}
  @Test public void narrowCuganVulkanAvoidsEmptySyncSamples() throws Exception {narrowFrames(false);}
  @Test public void cancelledFrameClosesWithoutProcessing() throws Exception {
    File root=prepare("models-se","up2x-no-denoise");
    Bitmap b=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);b.eraseColor(Color.RED);
    try(NcnnFrameProcessor p=new NcnnFrameProcessor(root,root,"realcugan-ncnn","models-se",2,32,true,0,"1:2:1")) {
      VideoPolicy.Control c=new VideoPolicy.Control();c.cancel();
      try {p.upscale(b,c);fail();}catch(java.util.concurrent.CancellationException expected){}
      p.close();assertTrue(p.diagnostics().contains("frames=0"));
    }finally{b.recycle();}
  }
}
