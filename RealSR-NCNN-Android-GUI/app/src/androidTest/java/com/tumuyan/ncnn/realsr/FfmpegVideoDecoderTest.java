package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.*;
import org.junit.Test;

public class FfmpegVideoDecoderTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private final long[] pts = {0, 33000, 81000, 100000, 170000, 250000};

  private File synthetic() throws Exception {
    return synthetic(64, 48);
  }

  private File synthetic(int width, int height) throws Exception {
    File f = new File(context.getCacheDir(), "ffmpeg-synthetic.mp4");
    MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
    MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", width, height);
    fmt.setInteger(
        MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    fmt.setInteger(MediaFormat.KEY_BIT_RATE, 200000);
    fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
    fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    android.view.Surface surface = codec.createInputSurface();
    codec.start();
    MediaMuxer mux = new MediaMuxer(f.toString(), 0);
    mux.setOrientationHint(90);
    VideoEncoderDrain drain = new VideoEncoderDrain(codec, mux, Collections.emptyList());
    drain.start();
    try (VideoGl gl = new VideoGl(surface, width, height)) {
      for (int i = 0; i < pts.length; i++) {
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xff0000ff);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(0xffff0000);
        new android.graphics.Canvas(b).drawRect(0, 0, width, height / 2, paint);
        gl.encode(b, pts[i]);
        b.recycle();
      }
      codec.signalEndOfInputStream();
      drain.finish();
      mux.stop();
    } finally {
      drain.close();
      codec.stop();
      codec.release();
      surface.release();
      mux.release();
    }
    return f;
  }

  @Test
  public void realAvcPreservesVfrOrientationAndEos() throws Exception {
    File f = synthetic();
    try (FfmpegVideoDecoder decoder = new FfmpegVideoDecoder(context, Uri.fromFile(f), 0, 2)) {
      VideoPolicy.Control control = new VideoPolicy.Control();
      for (long expected : pts) {
        FfmpegVideoDecoder.Frame frame = decoder.next(control);
        assertNotNull(frame);
        assertEquals(expected, frame.ptsUs);
        assertEquals(64, frame.bitmap.getWidth());
        assertEquals(48, frame.bitmap.getHeight());
        assertTrue(android.graphics.Color.red(frame.bitmap.getPixel(32, 8)) > 180);
        assertTrue(android.graphics.Color.blue(frame.bitmap.getPixel(32, 40)) > 180);
        frame.bitmap.recycle();
      }
      assertNull(decoder.next(control));
      assertNull(decoder.next(control));
      assertTrue(decoder.diagnostics().contains("h264"));
      decoder.close();
      decoder.close();
      try {
        decoder.next(control);
        fail("closed decoder accepted");
      } catch (IllegalStateException expected) {
      }
    } finally {
      f.delete();
    }
  }

  @Test
  public void codedPaddingIsCroppedToVisibleDimensions() throws Exception {
    File f = synthetic(64, 50);
    try (FfmpegVideoDecoder d = new FfmpegVideoDecoder(context, Uri.fromFile(f), 0, 1)) {
      FfmpegVideoDecoder.Frame frame = d.next(new VideoPolicy.Control());
      assertNotNull(frame);
      assertEquals(64, frame.bitmap.getWidth());
      assertEquals(50, frame.bitmap.getHeight());
      frame.bitmap.recycle();
    } finally {
      f.delete();
    }
  }

  @Test
  public void cancelledDecodeDoesNotBecomeEos() throws Exception {
    File f = synthetic();
    try (FfmpegVideoDecoder d = new FfmpegVideoDecoder(context, Uri.fromFile(f), 0, 1)) {
      VideoPolicy.Control c = new VideoPolicy.Control();
      c.cancel();
      try {
        d.next(c);
        fail("cancel swallowed");
      } catch (java.util.concurrent.CancellationException expected) {
      }
    } finally {
      f.delete();
    }
  }

  private long nativeExtent(java.io.FileInputStream in, long offset, long length) throws Exception {
    java.lang.reflect.Method open =
        FfmpegVideoDecoder.class.getDeclaredMethod(
            "nativeOpen", int.class, long.class, long.class, long.class, int.class);
    open.setAccessible(true);
    try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.dup(in.getFD())) {
      return (Long) open.invoke(null, fd.getFd(), offset, length, 0L, 1);
    }
  }

  @Test
  public void offsetLengthDescriptorOwnsDupAndStopsAtAssetEnd() throws Exception {
    File video = synthetic(),
        wrapped = File.createTempFile("ffmpeg-extent", ".bin", context.getCacheDir());
    byte[] data;
    try (java.io.FileInputStream in = new java.io.FileInputStream(video);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
      byte[] chunk = new byte[4096];
      int n;
      while ((n = in.read(chunk)) != -1) bytes.write(chunk, 0, n);
      data = bytes.toByteArray();
    }
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(wrapped)) {
      out.write(new byte[123]);
      out.write(data);
      out.write(data);
    }
    long handle;
    try (java.io.FileInputStream in = new java.io.FileInputStream(wrapped)) {
      handle = nativeExtent(in, 123, data.length);
    }
    java.lang.reflect.Method next =
        FfmpegVideoDecoder.class.getDeclaredMethod("nativeNext", long.class);
    java.lang.reflect.Method close =
        FfmpegVideoDecoder.class.getDeclaredMethod("nativeClose", long.class);
    next.setAccessible(true);
    close.setAccessible(true);
    try {
      for (long expected : pts) {
        FfmpegVideoDecoder.Frame f = (FfmpegVideoDecoder.Frame) next.invoke(null, handle);
        assertNotNull(f);
        assertEquals(expected, f.ptsUs);
        f.bitmap.recycle();
      }
      assertNull(next.invoke(null, handle));
    } finally {
      close.invoke(null, handle);
      video.delete();
      wrapped.delete();
    }
  }

  @Test
  public void seekReturnsOriginalTimelineKeyframePreroll() throws Exception {
    File f = synthetic();
    try (FfmpegVideoDecoder d = new FfmpegVideoDecoder(context, Uri.fromFile(f), 100000, 1)) {
      FfmpegVideoDecoder.Frame frame = d.next(new VideoPolicy.Control());
      assertNotNull(frame);
      assertTrue(frame.ptsUs <= 100000);
      frame.bitmap.recycle();
    } finally {
      f.delete();
    }
  }

  @Test
  public void invalidContainerIsAnError() throws Exception {
    File f = File.createTempFile("ffmpeg-invalid", ".mp4", context.getCacheDir());
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
      out.write(new byte[] {1, 2, 3, 4});
    }
    try {
      new FfmpegVideoDecoder(context, Uri.fromFile(f), 0, 1);
      fail("invalid container accepted");
    } catch (Exception expected) {
    } finally {
      f.delete();
    }
  }
}
