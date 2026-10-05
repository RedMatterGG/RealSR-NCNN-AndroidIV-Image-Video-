package com.tumuyan.ncnn.realsr;
import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
/** Real EGL/shader tests: compiled on host, require a GLES Android device to execute. */
public class Anime4kFrameProcessorTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  @Test public void compilesAllPassesPreservesColorsAlphaAndReusesJob() throws Exception {
    for (int scale : new int[]{2,4}) {
      try (Anime4kFrameProcessor processor = new Anime4kFrameProcessor(context,scale)) {
        Bitmap input = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        input.eraseColor(0x80e04020);
        for (int n=0;n<3;n++) {
          Bitmap output = processor.upscale(input,new VideoPolicy.Control());
          assertEquals(8*scale,output.getWidth());
          int c=output.getPixel(4*scale,4*scale);
          assertTrue(Math.abs(((c>>>24)&255)-128)<=1);
          assertTrue(Math.abs(((c>>>16)&255)-224)<=2);
          assertTrue(Math.abs(((c>>>8)&255)-64)<=2);
          assertTrue(Math.abs((c&255)-32)<=2);
          output.recycle();
        }
        assertTrue(processor.diagnostics().contains("frames=3"));
        assertTrue(processor.diagnostics().contains("non-neural"));
        input.recycle();
      }
    }
  }
  @Test public void orientationAndCallerEglSurviveInferenceFailureAndClose() throws Exception {
    EGLDisplay d=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    int[] v=new int[2]; assertTrue(EGL14.eglInitialize(d,v,0,v,1));
    EGLConfig[] configs=new EGLConfig[1]; int[] count=new int[1];
    assertTrue(EGL14.eglChooseConfig(d,new int[]{EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE},0,configs,0,1,count,0));
    EGLContext c=EGL14.eglCreateContext(d,configs[0],EGL14.EGL_NO_CONTEXT,
        new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
    EGLSurface draw=EGL14.eglCreatePbufferSurface(d,configs[0],new int[]{EGL14.EGL_WIDTH,16,EGL14.EGL_HEIGHT,16,EGL14.EGL_NONE},0);
    EGLSurface read=EGL14.eglCreatePbufferSurface(d,configs[0],new int[]{EGL14.EGL_WIDTH,16,EGL14.EGL_HEIGHT,16,EGL14.EGL_NONE},0);
    assertTrue(EGL14.eglMakeCurrent(d,draw,read,c));
    try {
      Anime4kFrameProcessor p=new Anime4kFrameProcessor(context,2);
      Bitmap in=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
      for(int y=0;y<8;y++) for(int x=0;x<8;x++) in.setPixel(x,y,y<4?0xffff0000:0xff0000ff);
      Bitmap out=p.upscale(in,new VideoPolicy.Control());
      assertEquals(0xffff0000,out.getPixel(8,0)); assertEquals(0xff0000ff,out.getPixel(8,15));
      assertEquals(c,EGL14.eglGetCurrentContext()); assertEquals(d,EGL14.eglGetCurrentDisplay());
      assertEquals(draw,EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
      assertEquals(read,EGL14.eglGetCurrentSurface(EGL14.EGL_READ));
      out.recycle();
      // Different-size input is an error, never a per-frame reallocation/fallback.
      Bitmap changed=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);
      try { p.upscale(changed,new VideoPolicy.Control());fail("dimensions must stay fixed"); }
      catch(IllegalArgumentException expected) {}
      finally { changed.recycle(); }
      assertEquals(c,EGL14.eglGetCurrentContext());
      assertEquals(draw,EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
      assertEquals(read,EGL14.eglGetCurrentSurface(EGL14.EGL_READ));
      assertTrue(p.diagnostics().contains("closed"));
      p.close();
      p=new Anime4kFrameProcessor(context,2);
      Bitmap warm=p.upscale(in,new VideoPolicy.Control());warm.recycle();in.recycle();
      VideoPolicy.Control cancelled=new VideoPolicy.Control();cancelled.cancel();
      try { p.upscale(null,cancelled);fail("cancel must throw"); }
      catch(java.util.concurrent.CancellationException expected) {}
      p.close();p.close();
      assertEquals(c,EGL14.eglGetCurrentContext());
      assertEquals(draw,EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
      assertEquals(read,EGL14.eglGetCurrentSurface(EGL14.EGL_READ));
      try(Anime4kFrameProcessor next=new Anime4kFrameProcessor(context,2)) {
        Bitmap b=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);b.eraseColor(0xff00ff00);
        Bitmap result=next.upscale(b,new VideoPolicy.Control());result.recycle();b.recycle();
      }
      assertEquals(c,EGL14.eglGetCurrentContext());
    } finally {
      EGL14.eglMakeCurrent(d,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
      EGL14.eglDestroySurface(d,draw);EGL14.eglDestroySurface(d,read);EGL14.eglDestroyContext(d,c);EGL14.eglTerminate(d);
    }
  }
  @Test public void lineReconstructionIsNotPlainBilinear() throws Exception {
    Bitmap input=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);input.eraseColor(0xffffffff);
    for(int y=0;y<8;y++) input.setPixel(y,y,0xff000000);
    Bitmap baseline=Bitmap.createScaledBitmap(input,16,16,true);
    try(Anime4kFrameProcessor p=new Anime4kFrameProcessor(context,2)) {
      Bitmap output=p.upscale(input,new VideoPolicy.Control());
      boolean differs=false;
      for(int y=0;y<16;y++) for(int x=0;x<16;x++) if(output.getPixel(x,y)!=baseline.getPixel(x,y)) differs=true;
      assertTrue("directional line thinning/refinement must change edge pixels",differs);
      output.recycle();
    } finally { input.recycle();baseline.recycle(); }
  }
}
