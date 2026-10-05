package com.tumuyan.ncnn.realsr;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;

/** Owns exactly one persistent neural engine or explicitly labelled GPU shader processor. */
final class VideoFrameEngine implements VideoPipeline.Processor, AutoCloseable {
  private final NcnnFrameProcessor neural;
  private final Anime4kFrameProcessor shader;

  VideoFrameEngine(Context context, File engines, File work, String engine, String model,
      int scale, int tile, boolean cpu, int noise, String threads) throws Exception {
    VideoUpscalerCatalog.Entry entry=VideoUpscalerCatalog.find(model);
    if(!entry.engine.equals(engine)) throw new IllegalArgumentException("Video engine/model mismatch");
    entry.validate(scale,cpu);
    if(entry.shader) {
      shader=new Anime4kFrameProcessor(context,scale);
      neural=null;
    } else {
      neural=new NcnnFrameProcessor(engines,work,engine,model,scale,tile,cpu,noise,threads);
      shader=null;
    }
  }
  public Bitmap upscale(Bitmap bitmap, VideoPolicy.Control control) throws Exception {
    return shader!=null ? shader.upscale(bitmap,control) : neural.upscale(bitmap,control);
  }
  public String diagnostics() { return shader!=null ? shader.diagnostics() : neural.diagnostics(); }
  public void close() throws Exception {
    if(shader!=null) shader.close(); else neural.close();
  }
}
