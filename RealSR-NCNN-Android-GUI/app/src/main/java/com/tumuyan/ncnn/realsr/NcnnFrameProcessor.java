package com.tumuyan.ncnn.realsr;

import android.graphics.Bitmap;
import java.io.File;
import java.io.IOException;

/** One native model per job, no subprocesses, disk frames or PNG encoding. */
final class NcnnFrameProcessor implements VideoPipeline.Processor, AutoCloseable {
  private long handle;
  private String lastDiagnostics = "Native engine closed";

  NcnnFrameProcessor(File engines, File work, String engine, String model, int scale,
      int tile, boolean cpu, int noise, String threads) throws IOException {
    try {
      VideoPolicy.validateTile(tile);
      int cpuThreads = NcnnSettings.cpuThreads(threads);
      String stem = NcnnSettings.stem(engine, scale, noise);
      NcnnSettings.validateModel(engine, model);
      File modelDir = new File(engines, model).getCanonicalFile();
      File root = engines.getCanonicalFile();
      if (!modelDir.toString().startsWith(root.toString() + File.separator))
        throw new IOException("Model path must remain inside the engine directory");
      File param = new File(modelDir, stem + ".param");
      File weights = new File(modelDir, stem + ".bin");
      if (!param.isFile() || !weights.isFile())
        throw new IOException("Selected packaged model does not support " + scale + "x / noise " + noise);
      System.loadLibrary("ncnn_video");
      handle = nativeCreate(engine, param.toString(), weights.toString(), scale, tile,
          cpu, noise, cpuThreads, model.contains("models-nose"));
      if (handle == 0) throw new IOException("Native model initialization failed");
      lastDiagnostics = nativeDiagnostics(handle);
    } catch (IllegalArgumentException | UnsatisfiedLinkError e) {
      throw new IOException("Cannot initialize in-process NCNN: " + e.getMessage(), e);
    }
  }

  @Override public synchronized Bitmap upscale(Bitmap bitmap, VideoPolicy.Control control) throws Exception {
    Bitmap result = null;
    try {
      control.check();
      if (handle == 0) throw new IOException("NCNN processor is closed");
      if (bitmap == null || bitmap.isRecycled() || bitmap.getConfig() != Bitmap.Config.ARGB_8888)
        throw new IOException("NCNN requires a live ARGB_8888 software bitmap");
      result = nativeProcess(handle, bitmap);
      control.check();
      lastDiagnostics = nativeDiagnostics(handle);
      return result;
    } catch (Exception | Error e) {
      if (result != null) result.recycle();
      close();
      throw e;
    }
  }

  /** Actual selected device, CPU intra-op threads and monotonic native timing. */
  public synchronized String diagnostics() {
    if (handle != 0) lastDiagnostics = nativeDiagnostics(handle);
    return lastDiagnostics;
  }

  /** Waits for an in-flight native frame; safe and idempotent on concurrent cancellation. */
  @Override public synchronized void close() {
    if (handle != 0) {
      long owned = handle;
      handle = 0;
      nativeClose(owned);
    }
  }

  private static native long nativeCreate(String engine, String param, String weights,
      int scale, int tile, boolean cpu, int noise, int threads, boolean nose) throws IOException;
  private static native Bitmap nativeProcess(long handle, Bitmap input) throws IOException;
  private static native String nativeDiagnostics(long handle);
  private static native void nativeClose(long handle);
}
