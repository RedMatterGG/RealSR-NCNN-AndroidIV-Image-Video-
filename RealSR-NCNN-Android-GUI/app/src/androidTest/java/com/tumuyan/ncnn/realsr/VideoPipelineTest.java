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

public class VideoPipelineTest {
  private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private final long[] pts = {0, 33000, 81000, 100000, 170000, 250000};

  private File synthetic() throws Exception {
    File f = new File(context.getCacheDir(), "synthetic.mp4");
    MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
    MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", 64, 48);
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
    try (VideoGl gl = new VideoGl(surface, 64, 48)) {
      for (int i = 0; i < pts.length; i++) {
        Bitmap b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xff0000ff);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(0xffff0000);
        new android.graphics.Canvas(b).drawRect(0, 0, 64, 24, paint);
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

  private File syntheticAudio() throws Exception {
    File f = new File(context.getCacheDir(), "synthetic-aac.m4a");
    MediaCodec codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
    MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1);
    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
    format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    codec.start();
    MediaMuxer mux = new MediaMuxer(f.toString(), 0);
    boolean started = false, eos = false, inputEos = false;
    int track = -1, chunk = 0;
    long deadline = System.nanoTime() + 20000000000L;
    try {
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (!eos) {
        if (System.nanoTime() > deadline) throw new IllegalStateException("Synthetic AAC timeout");
        if (!inputEos) {
          int i = codec.dequeueInputBuffer(1000);
          if (i >= 0) {
            java.nio.ByteBuffer b = codec.getInputBuffer(i);
            b.clear();
            if (chunk == 14) {
              codec.queueInputBuffer(
                  i, 0, 0, chunk * 1024L * 1000000 / 44100, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputEos = true;
            } else {
              for (int j = 0; j < 1024; j++)
                b.putShort(
                    (short) (Math.sin((chunk * 1024 + j) * 2 * Math.PI * 440 / 44100) * 5000));
              codec.queueInputBuffer(i, 0, 2048, chunk * 1024L * 1000000 / 44100, 0);
              chunk++;
            }
          }
        }
        int i = codec.dequeueOutputBuffer(info, 10000);
        if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          track = mux.addTrack(codec.getOutputFormat());
          mux.start();
          started = true;
        } else if (i >= 0) {
          if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            java.nio.ByteBuffer b = codec.getOutputBuffer(i);
            b.position(info.offset);
            b.limit(info.offset + info.size);
            mux.writeSampleData(track, b, info);
          }
          eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          codec.releaseOutputBuffer(i, false);
        }
      }
      mux.stop();
      return f;
    } finally {
      codec.stop();
      codec.release();
      mux.release();
    }
  }

  private void remuxTracks(File source, MediaMuxer mux, int track, String mimePrefix)
      throws Exception {
    MediaExtractor e = new MediaExtractor();
    e.setDataSource(source.toString());
    try {
      for (int i = 0; i < e.getTrackCount(); i++)
        if (e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(mimePrefix)) {
          e.selectTrack(i);
          break;
        }
      java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(1024 * 1024);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (e.getSampleTime() >= 0) {
        b.clear();
        int n = e.readSampleData(b, 0);
        if (n < 0) break;
        info.set(0, n, e.getSampleTime(), e.getSampleFlags());
        mux.writeSampleData(track, b, info);
        e.advance();
      }
    } finally {
      e.release();
    }
  }

  private File withAudio(File video) throws Exception {
    File aac = syntheticAudio(), dst = new File(context.getCacheDir(), "with-audio.mp4");
    MediaExtractor v = new MediaExtractor(), a = new MediaExtractor();
    v.setDataSource(video.toString());
    a.setDataSource(aac.toString());
    MediaMuxer mux = new MediaMuxer(dst.toString(), 0);
    try {
      int vt = mux.addTrack(v.getTrackFormat(0)), at = mux.addTrack(a.getTrackFormat(0));
      mux.setOrientationHint(90);
      mux.start();
      remuxTracks(video, mux, vt, "video/");
      remuxTracks(aac, mux, at, "audio/");
      mux.stop();
      return dst;
    } finally {
      v.release();
      a.release();
      mux.release();
    }
  }

  private List<String> audioPackets(File file) throws Exception {
    MediaExtractor e = new MediaExtractor();
    e.setDataSource(file.toString());
    List<String> packets = new ArrayList<>();
    try {
      int track = -1;
      for (int i = 0; i < e.getTrackCount(); i++)
        if (e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
          track = i;
          break;
        }
      if (track < 0) return packets;
      e.selectTrack(track);
      java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1024 * 1024);
      while (e.getSampleTime() >= 0) {
        b.clear();
        int n = e.readSampleData(b, 0);
        byte[] bytes = new byte[n];
        b.position(0);
        b.get(bytes);
        packets.add(
            e.getSampleTime()
                + ":"
                + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
        e.advance();
      }
      return packets;
    } finally {
      e.release();
    }
  }

  @Test
  public void graphicsRequestUsesRealPipelineDrawingAndPreservesVfr() throws Exception {
    File src = synthetic(), dst = new File(context.getCacheDir(), "gpu-request-result.mp4");
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.scale = 1;
    o.hardwareOnly = false;
    o.preserveAudio = false;
    try (VideoGpuHints hints = VideoGpuHints.create(true, false,
        android.os.Build.VERSION.SDK_INT, 1000000000L, true)) {
      o.gpuHints = hints;
      // Explicit codec-test identity seam, NOT a production NCNN fallback.
      // The pipeline draws actual output and measures its monotonic critical path.
      new VideoPipeline(context).run(Uri.fromFile(src), dst, o,
          (bitmap, control) -> bitmap, new VideoPolicy.Control(), (n, p) -> {});
      assertEquals(samples(src), samples(dst));
      assertFalse(hints.status().isEmpty());
    }
  }

  @Test
  public void audioPassthroughPreservesExactAacPacketsAndTimeline() throws Exception {
    File src = withAudio(synthetic()), dst = new File(context.getCacheDir(), "audio-result.mp4");
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.scale = 1;
    o.hardwareOnly = false;
    new VideoPipeline(context)
        .run(
            Uri.fromFile(src),
            dst,
            o,
            (bitmap, control) -> bitmap,
            new VideoPolicy.Control(),
            (n, p) -> {});
    assertFalse(audioPackets(src).isEmpty());
    assertEquals(audioPackets(src), audioPackets(dst));
    assertEquals(samples(src), samples(dst));
  }

  @Test
  public void explicitSilentOutputDropsAudioOnlyWhenRequested() throws Exception {
    File src = withAudio(synthetic()), dst = new File(context.getCacheDir(), "silent-result.mp4");
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.scale = 1;
    o.hardwareOnly = false;
    o.preserveAudio = false;
    new VideoPipeline(context)
        .run(
            Uri.fromFile(src),
            dst,
            o,
            (bitmap, control) -> bitmap,
            new VideoPolicy.Control(),
            (n, p) -> {});
    assertTrue(audioPackets(dst).isEmpty());
  }

  private List<Long> samples(File f) throws Exception {
    MediaExtractor e = new MediaExtractor();
    e.setDataSource(f.toString());
    List<Long> p = new ArrayList<>();
    try {
      for (int i = 0; i < e.getTrackCount(); i++)
        if (e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")) {
          e.selectTrack(i);
          break;
        }
      while (e.getSampleTime() >= 0) {
        p.add(e.getSampleTime());
        e.advance();
      }
      return p;
    } finally {
      e.release();
    }
  }

  @Test
  public void ffmpegRetainsVfrPortraitPixelsAndAacPreviewOrigin() throws Exception {
    File src = withAudio(synthetic()), dst = new File(context.getCacheDir(), "ffmpeg-preview.mp4");
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.decoderBackend = VideoDecodePolicy.FFMPEG;
    o.decoderThreads = 2;
    o.scale = 1;
    o.startUs = 80000;
    o.durationUs = 100000;
    VideoPolicy.Control control = new VideoPolicy.Control();
    VideoPipeline pipeline = new VideoPipeline(context);
    pipeline.run(Uri.fromFile(src), dst, o, (bitmap, c) -> {
      assertEquals(64, bitmap.getWidth());
      assertEquals(48, bitmap.getHeight());
      int top = bitmap.getPixel(32, 8), bottom = bitmap.getPixel(32, 40);
      assertTrue(android.graphics.Color.red(top) > android.graphics.Color.blue(top) + 80);
      assertTrue(android.graphics.Color.blue(bottom) > android.graphics.Color.red(bottom) + 80);
      return bitmap;
    }, control, (n, pts) -> { });
    assertEquals(Arrays.asList(1000L, 20000L, 90000L), samples(dst));
    List<String> expectedAudio = new ArrayList<>();
    for (String packet : audioPackets(src)) {
      int colon = packet.indexOf(':');
      long time = Long.parseLong(packet.substring(0, colon));
      if (time >= o.startUs && time < o.startUs + o.durationUs)
        expectedAudio.add((time - o.startUs) + packet.substring(colon));
    }
    assertFalse(expectedAudio.isEmpty());
    assertEquals(expectedAudio, audioPackets(dst));
    assertEquals(90, pipeline.inspect(Uri.fromFile(dst)).rotation());
    assertTrue(pipeline.diagnostics().contains("decode backend ffmpeg"));
  }

  @Test
  public void ffmpegCancellationRecyclesFrameAndDeletesPartial() throws Exception {
    File src = synthetic(), dst = new File(context.getCacheDir(), "ffmpeg-cancel.mp4");
    dst.delete();
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.decoderBackend = VideoDecodePolicy.FFMPEG;
    o.scale = 1;
    final Bitmap[] leased = {null};
    try {
      new VideoPipeline(context).run(Uri.fromFile(src), dst, o, (bitmap, control) -> {
        leased[0] = bitmap;
        control.cancel();
        return bitmap;
      }, new VideoPolicy.Control(), (n, pts) -> { });
      fail("Cancellation expected");
    } catch (java.util.concurrent.CancellationException expected) { }
    assertNotNull(leased[0]);
    assertTrue(leased[0].isRecycled());
    assertFalse(dst.exists());
    assertFalse(new File(dst.getParentFile(), dst.getName() + ".partial.mp4").exists());
  }

  @Test
  public void guiExposesVideoAndBatchNavigation() throws Exception {
    try (androidx.test.core.app.ActivityScenario<VideoActivity> scenario =
        androidx.test.core.app.ActivityScenario.launch(VideoActivity.class)) {
      scenario.onActivity(
          activity -> {
            assertNotNull(activity.findViewById(R.id.video_start));
            assertNotNull(activity.findViewById(R.id.video_preview));
            assertNotNull(activity.findViewById(R.id.video_cancel));
          });
    }
  }

  @Test
  public void previewSeeksKeyframeThenKeepsOnlyExactInterval() throws Exception {
    File src = synthetic(), dst = new File(context.getCacheDir(), "preview-result.mp4");
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.scale = 1;
    o.hardwareOnly = false;
    o.startUs = 80000;
    o.durationUs = 100000;
    new VideoPipeline(context)
        .run(
            Uri.fromFile(src),
            dst,
            o,
            (bitmap, control) -> bitmap,
            new VideoPolicy.Control(),
            (n, p) -> {});
    assertEquals(Arrays.asList(1000L, 20000L, 90000L), samples(dst));
  }

  @Test
  public void cancelDuringFrameRemovesPartialAndDoesNotPublish() throws Exception {
    File src = synthetic(), dst = new File(context.getCacheDir(), "cancel-result.mp4");
    dst.delete();
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.scale = 1;
    o.hardwareOnly = false;
    VideoPolicy.Control c = new VideoPolicy.Control();
    try {
      new VideoPipeline(context)
          .run(
              Uri.fromFile(src),
              dst,
              o,
              (bitmap, control) -> {
                control.cancel();
                return bitmap;
              },
              c,
              (n, p) -> {});
      fail("Expected cancellation");
    } catch (java.util.concurrent.CancellationException expected) {
    }
    assertFalse(dst.exists());
    assertFalse(new File(dst.toString() + ".partial.mp4").exists());
  }

  @Test
  public void arm64RunsPersistentPackagedNcnnWithoutDiskFrames() throws Exception {
    org.junit.Assume.assumeTrue(
        Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a"));
    File src = synthetic(),
        dst = new File(context.getCacheDir(), "ncnn-result.mp4"),
        engines = new File(context.getFilesDir(), "instrumentation-engines"),
        work = new File(context.getCacheDir(), "instrumentation-memory-only-frame");
    assertFalse("JNI test must start without a disk frame workspace", work.exists());
    AssetsCopyer.releaseAssets(context, "realsr", engines.toString(), true);
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.scale = 2;
    try (NcnnFrameProcessor processor =
        new NcnnFrameProcessor(
            new File(engines, "realsr"),
            work,
            "realcugan-ncnn",
            "models-pro",
            2,
            32,
            false,
            -1,
            "1:1:1")) {
      new VideoPipeline(context)
          .run(
              Uri.fromFile(src),
              dst,
              o,
              processor,
              new VideoPolicy.Control(),
              (n, p) -> {
                assertFalse("JNI must never create a disk frame workspace", work.exists());
                assertTrue(processor.diagnostics().contains("model loads=1; frames=" + n + ";"));
              });
    }
    assertEquals(pts.length, samples(dst).size());
    assertFalse(work.exists());
    MediaExtractor e = new MediaExtractor();
    e.setDataSource(dst.toString());
    assertEquals(128, e.getTrackFormat(0).getInteger(MediaFormat.KEY_WIDTH));
    assertEquals(96, e.getTrackFormat(0).getInteger(MediaFormat.KEY_HEIGHT));
    e.release();
  }

  @Test
  public void surfaceReadbackPreservesColorAndPixelOrientation() throws Exception {
    File src = synthetic(), dst = new File(context.getCacheDir(), "color-result.mp4");
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.scale = 1;
    o.hardwareOnly = false;
    new VideoPipeline(context)
        .run(
            Uri.fromFile(src),
            dst,
            o,
            (bitmap, control) -> {
              int top = bitmap.getPixel(32, 8), bottom = bitmap.getPixel(32, 40);
              assertTrue(android.graphics.Color.red(top) > android.graphics.Color.blue(top) + 80);
              assertTrue(
                  android.graphics.Color.blue(bottom) > android.graphics.Color.red(bottom) + 80);
              return bitmap;
            },
            new VideoPolicy.Control(),
            (n, p) -> {});
    assertEquals(pts.length, samples(dst).size());
  }

  @Test
  public void streamsEveryPresentationFrameWithVariablePtsAndRotation() throws Exception {
    File src = synthetic(), dst = new File(context.getCacheDir(), "test-result.mp4");
    VideoPipeline.Options o = new VideoPipeline.Options();
    o.scale = 1;
    o.hardwareOnly = false;
    new VideoPipeline(context)
        .run(
            Uri.fromFile(src),
            dst,
            o,
            (bitmap, control) -> bitmap,
            new VideoPolicy.Control(),
            (n, p) -> {});
    assertEquals(samples(src), samples(dst));
    assertEquals(pts.length, samples(dst).size());
    MediaExtractor e = new MediaExtractor();
    e.setDataSource(dst.toString());
    assertEquals(90, e.getTrackFormat(0).getInteger("rotation-degrees"));
    e.release();
  }
}
