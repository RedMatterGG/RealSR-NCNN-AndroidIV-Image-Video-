package com.tumuyan.ncnn.realsr;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.net.Uri;
import java.io.IOException;

/**
 * Explicit FFmpeg software decoding. Returned bitmaps belong to the caller. Cancellation waits for
 * an in-flight native call (including a provider pread). No source copies, orientation transforms,
 * model loading or backend fallback.
 */
public final class FfmpegVideoDecoder implements AutoCloseable {
  static {
    System.loadLibrary("ffmpeg_video");
  }

  public static final class Frame {
    public final Bitmap bitmap;
    public final long ptsUs;

    private Frame(Bitmap bitmap, long ptsUs) {
      this.bitmap = bitmap;
      this.ptsUs = ptsUs;
    }
  }

  private long handle;
  private String lastDiagnostics = "FFmpeg closed";

  public FfmpegVideoDecoder(Context context, Uri uri, long startUs, int threads) throws Exception {
    if (startUs < 0 || threads < 1 || threads > 16)
      throw new IllegalArgumentException("FFmpeg start/threads invalid (1..16)");
    try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
      if (afd == null) throw new IOException("Source provider returned no descriptor");
      handle =
          nativeOpen(
              afd.getParcelFileDescriptor().getFd(),
              afd.getStartOffset(),
              afd.getDeclaredLength(),
              startUs,
              threads);
      if (handle == 0) throw new IOException("FFmpeg returned an invalid handle");
      lastDiagnostics = nativeDiagnostics(handle);
    } catch (Exception | Error failure) {
      if (handle != 0) {
        nativeClose(handle);
        handle = 0;
      }
      throw failure;
    }
  }

  public synchronized Frame next(VideoPolicy.Control control) throws Exception {
    if (handle == 0) throw new IllegalStateException("FFmpeg decoder closed");
    control.check();
    Frame frame = nativeNext(handle);
    try {
      control.check();
    } catch (RuntimeException e) {
      if (frame != null) frame.bitmap.recycle();
      throw e;
    }
    return frame;
  }

  public synchronized String diagnostics() {
    return lastDiagnostics;
  }

  @Override
  public synchronized void close() {
    if (handle != 0) {
      nativeClose(handle);
      handle = 0;
    }
  }

  private static native long nativeOpen(int fd, long offset, long length, long startUs, int threads)
      throws IOException;

  private static native Frame nativeNext(long handle) throws IOException;

  private static native String nativeDiagnostics(long handle);

  private static native void nativeClose(long handle);
}
