package com.tumuyan.ncnn.realsr;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import android.os.Build;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded single-presentation-frame video transcode; no frame-rate resampling. */
public final class VideoPipeline {
  public static final class Options {
    public int scale = 2, bitrate = 12000000;
    public String mime = "video/avc";
    public String decoderBackend = VideoDecodePolicy.MEDIACODEC;
    public int decoderThreads = 1;
    public VideoGpuHints gpuHints; // Owned/closed by the worker service scope.
    public long startUs = 0, durationUs = Long.MAX_VALUE;
    public boolean preserveAudio = true, hardwareOnly = true;
  }

  public interface Processor {
    Bitmap upscale(Bitmap bitmap, VideoPolicy.Control control) throws Exception;
  }

  public interface Progress {
    void update(long frames, long sourcePts);
  }

  public static final class Source {
    public final MediaFormat video;
    public final int videoIndex;
    public final List<Integer> audioIndices = new ArrayList<>();
    public final List<MediaFormat> audio = new ArrayList<>();

    Source(MediaExtractor e) {
      MediaFormat found = null;
      int track = -1;
      for (int i = 0; i < e.getTrackCount(); i++) {
        MediaFormat f = e.getTrackFormat(i);
        String mime = f.getString(MediaFormat.KEY_MIME);
        if (mime.startsWith("video/")) {
          if (found != null)
            throw new IllegalArgumentException("Multiple video tracks unsupported");
          found = f;
          track = i;
        } else if (mime.startsWith("audio/")) {
          audioIndices.add(i);
          audio.add(f);
        }
      }
      if (found == null) throw new IllegalArgumentException("No video track");
      video = found;
      videoIndex = track;
    }

    public int width() {
      return video.getInteger(MediaFormat.KEY_WIDTH);
    }

    public int height() {
      return video.getInteger(MediaFormat.KEY_HEIGHT);
    }

    public long duration() {
      return video.containsKey(MediaFormat.KEY_DURATION)
          ? video.getLong(MediaFormat.KEY_DURATION)
          : 0;
    }

    public int rotation() {
      return video.containsKey("rotation-degrees") ? video.getInteger("rotation-degrees") : 0;
    }

    @Override
    public String toString() {
      return width()
          + " x "
          + height()
          + " • "
          + (video.containsKey(MediaFormat.KEY_FRAME_RATE)
              ? video.getInteger(MediaFormat.KEY_FRAME_RATE)
              : "VFR")
          + " fps • "
          + duration() / 1000000.0
          + " s • rotation "
          + rotation()
          + " • "
          + audio.size()
          + " audio track(s)";
    }
  }

  private final Context context;
  private long decodeNanos;
  private String decodeBackend = VideoDecodePolicy.MEDIACODEC, decoderDetails = "", encoderName = "pending";
  public String diagnostics() {
    return String.format(Locale.ROOT, "decode backend %s • decoded-frame acquisition cumulative %.3fs • hardware encoder %s%s",
        decodeBackend, decodeNanos / 1e9, encoderName, decoderDetails.isEmpty() ? "" : " • " + decoderDetails);
  }
  private long encodeFrame(Bitmap frame, long pts, long lastPts, int w, int h,
      VideoPolicy.Window window, Processor processor, VideoPolicy.Control control, VideoGl gl,
      VideoGpuHints gpuHints, long frameStart) throws Exception {
    Bitmap result = null;
    try {
      control.check();
      result = processor.upscale(frame, control);
      control.check();
      if (result == null || result.getWidth() != w || result.getHeight() != h)
        throw new IllegalArgumentException("Engine output dimensions do not match scale");
      long normalized = window.normalize(pts);
      if (normalized <= lastPts) throw new IllegalArgumentException("Non-increasing presentation timestamps unsupported");
      boolean measureGraphics = gpuHints != null && gpuHints.measuringGraphics();
      gl.encode(result, normalized, measureGraphics);
      if (measureGraphics) gpuHints.reportFrame(frameStart, VideoPerformanceHints.workClockNanos());
      return normalized;
    } finally {
      if (result != null && result != frame) result.recycle();
    }
  }

  public VideoPipeline(Context context) {
    this.context = context;
  }

  private MediaExtractor extractor(Uri uri) throws Exception {
    MediaExtractor e = new MediaExtractor();
    try {
      e.setDataSource(context, uri, null);
      return e;
    } catch (Exception ex) {
      e.release();
      throw ex;
    }
  }

  public Source inspect(Uri uri) throws Exception {
    MediaExtractor e = extractor(uri);
    try {
      return new Source(e);
    } finally {
      e.release();
    }
  }

  static String encoder(MediaFormat format, boolean hardwareOnly) {
    for (MediaCodecInfo c : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (!c.isEncoder()) continue;
      boolean software =
          Build.VERSION.SDK_INT >= 29
              ? c.isSoftwareOnly()
              : c.getName().startsWith("OMX.google.") || c.getName().startsWith("c2.android.");
      if (hardwareOnly && software) continue;
      try {
        MediaCodecInfo.CodecCapabilities caps =
            c.getCapabilitiesForType(format.getString(MediaFormat.KEY_MIME));
        boolean surface = false;
        for (int v : caps.colorFormats)
          if (v == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
        if (surface && caps.isFormatSupported(format)) return c.getName();
      } catch (IllegalArgumentException ignored) {
      }
    }
    throw new IllegalArgumentException(
        "No "
            + (hardwareOnly ? "hardware " : "")
            + "surface encoder supports requested codec/dimensions/bitrate");
  }

  public void run(
      Uri uri,
      File destination,
      Options options,
      Processor processor,
      VideoPolicy.Control control,
      Progress progress)
      throws Exception {
    File partial = new File(destination.getParentFile(), destination.getName() + ".partial.mp4");
    MediaExtractor input = null;
    MediaCodec decoder = null, encoder = null;
    FfmpegVideoDecoder ffmpeg = null;
    Surface encoderSurface = null;
    VideoGl gl = null;
    MediaMuxer mux = null;
    VideoEncoderDrain drain = null;
    boolean decoderStarted = false, encoderStarted = false, success = false;
    try {
      control.check();
      decodeBackend = new VideoDecodePolicy(options.decoderBackend).backend;
      decodeNanos = 0;
      decoderDetails = "";
      input = extractor(uri);
      Source src = new Source(input);
      if (VideoPolicy.unsupportedProfile(
          src.video.getString(MediaFormat.KEY_MIME),
          src.video.containsKey(MediaFormat.KEY_PROFILE)
              ? src.video.getInteger(MediaFormat.KEY_PROFILE)
              : 0))
        throw new IllegalArgumentException("Tagged 10-bit/HDR/Dolby Vision source unsupported");
      int transfer =
          src.video.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
              ? src.video.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
              : 0;
      VideoPolicy.validate(
          src.width(), src.height(), options.scale, transfer, null, options.preserveAudio);
      for (MediaFormat f : src.audio)
        VideoPolicy.validate(
            src.width(),
            src.height(),
            options.scale,
            transfer,
            f.getString(MediaFormat.KEY_MIME),
            options.preserveAudio);
      if (src.video.containsKey(MediaFormat.KEY_COLOR_STANDARD)
          && src.video.getInteger(MediaFormat.KEY_COLOR_STANDARD) == 6)
        throw new IllegalArgumentException(
            "BT.2020/10-bit source unsupported; use SDR BT.709/BT.601");
      int w = src.width() * options.scale, h = src.height() * options.scale;
      if ((w & 1) != 0 || (h & 1) != 0)
        throw new IllegalArgumentException("Encoder requires even output dimensions");
      if (options.startUs < 0 || options.durationUs <= 0)
        throw new IllegalArgumentException("Invalid preview interval");
      long duration =
          options.durationUs == Long.MAX_VALUE
              ? Long.MAX_VALUE - options.startUs
              : options.durationUs;
      VideoPolicy.Window window = new VideoPolicy.Window(options.startUs, duration);
      MediaFormat fmt = MediaFormat.createVideoFormat(options.mime, w, h);
      fmt.setInteger(
          MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      fmt.setInteger(MediaFormat.KEY_BIT_RATE, options.bitrate);
      int nominal =
          src.video.containsKey(MediaFormat.KEY_FRAME_RATE)
              ? src.video.getInteger(MediaFormat.KEY_FRAME_RATE)
              : 30;
      fmt.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, nominal));
      fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
      if (Build.VERSION.SDK_INT >= 29) fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
      encoderName = encoder(fmt, options.hardwareOnly);
      encoder = MediaCodec.createByCodecName(encoderName);
      encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      encoderSurface = encoder.createInputSurface();
      encoder.start();
      encoderStarted = true;
      mux = new MediaMuxer(partial.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      mux.setOrientationHint(src.rotation());
      drain =
          new VideoEncoderDrain(
              encoder, mux, options.preserveAudio ? src.audio : Collections.emptyList());
      drain.start();
      gl = new VideoGl(encoderSurface, w, h);
      long count = 0, lastPts = -1;
      // Start reset is GPU-only and sent once, immediately before frame production begins.
      if (options.gpuHints != null) options.gpuHints.beginFrame();
      if (VideoDecodePolicy.FFMPEG.equals(decodeBackend)) {
        ffmpeg = new FfmpegVideoDecoder(context, uri, options.startUs, options.decoderThreads);
        while (true) {
          control.check();
          drain.check();
          control.acquireFrame();
          Bitmap frame = null;
          try {
            long frameStart = VideoPerformanceHints.workClockNanos();
            long acquisitionStart = System.nanoTime();
            FfmpegVideoDecoder.Frame decoded;
            try { decoded = ffmpeg.next(control); }
            finally { decodeNanos += System.nanoTime() - acquisitionStart; }
            if (decoded != null) frame = decoded.bitmap;
            decoderDetails = ffmpeg.diagnostics();
            if (decoded == null) break; // Only real EOS is null.
            VideoPolicy.requireSourcePts(decoded.ptsUs);
            if (decoded.ptsUs >= window.endUs) break;
            if (!window.contains(decoded.ptsUs)) continue; // Keyframe preroll.
            if (frame == null || frame.getWidth() != src.width() || frame.getHeight() != src.height())
              throw new IllegalArgumentException("Decoded visible dimensions differ from track; unsupported");
            lastPts = encodeFrame(frame, decoded.ptsUs, lastPts, w, h, window, processor, control, gl, options.gpuHints, frameStart);
            progress.update(++count, decoded.ptsUs);
          } finally {
            if (frame != null) frame.recycle();
            control.releaseFrame();
          }
        }
      } else {
      // Disable automatic decoder rotation: encoded pixels retain source orientation and muxer
      // carries the hint.
      src.video.setInteger("rotation-degrees", 0);
      decoder = MediaCodec.createDecoderByType(src.video.getString(MediaFormat.KEY_MIME));
      decoder.configure(src.video, gl.decoderSurface(), null, 0);
      decoder.start();
      decoderStarted = true;
      input.selectTrack(src.videoIndex);
      input.seekTo(options.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      boolean inputEos = false, outputEos = false;
      long lastActivity = System.nanoTime();
      decoderDetails = decoder.getName();
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (!outputEos) {
        control.check();
        drain.check();
        long frameStart = VideoPerformanceHints.workClockNanos();
        long acquisitionStart = System.nanoTime();
        if (!inputEos) {
          int idx = decoder.dequeueInputBuffer(1000);
          if (idx >= 0) {
            ByteBuffer b = decoder.getInputBuffer(idx);
            long t = input.getSampleTime();
            int n = input.getSampleTrackIndex() < 0 ? -1 : input.readSampleData(b, 0);
            if (n < 0) {
              decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputEos = true;
            } else {
              VideoPolicy.requireSourcePts(t);
              if ((input.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                throw new IllegalArgumentException("Encrypted source unsupported");
              decoder.queueInputBuffer(idx, 0, n, t, 0);
              input.advance();
            }
            lastActivity = System.nanoTime();
          }
        }
        int idx = decoder.dequeueOutputBuffer(info, 10000);
        decodeNanos += System.nanoTime() - acquisitionStart;
        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat f = decoder.getOutputFormat();
          int visibleWidth =
              VideoPolicy.visibleDimension(
                  f.getInteger(MediaFormat.KEY_WIDTH),
                  f.containsKey("crop-left") ? f.getInteger("crop-left") : 0,
                  f.containsKey("crop-right") ? f.getInteger("crop-right") : -1);
          int visibleHeight =
              VideoPolicy.visibleDimension(
                  f.getInteger(MediaFormat.KEY_HEIGHT),
                  f.containsKey("crop-top") ? f.getInteger("crop-top") : 0,
                  f.containsKey("crop-bottom") ? f.getInteger("crop-bottom") : -1);
          if (visibleWidth != src.width() || visibleHeight != src.height())
            throw new IllegalArgumentException(
                "Dynamic visible dimensions differ from track; unsupported");
          VideoPolicy.validate(
              src.width(),
              src.height(),
              options.scale,
              f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
                  ? f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                  : 0,
              null,
              options.preserveAudio);
          lastActivity = System.nanoTime();
        } else if (idx >= 0) {
          lastActivity = System.nanoTime();
          boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          long pts = info.presentationTimeUs;
          boolean render = info.size > 0 && window.contains(pts);
          decoder.releaseOutputBuffer(idx, render);
          if (render) {
            control.acquireFrame();
            Bitmap frame = null;
            try {
              long readStart = System.nanoTime();
              try { frame = gl.read(src.width(), src.height(), control); }
              finally { decodeNanos += System.nanoTime() - readStart; }
              lastPts = encodeFrame(frame, pts, lastPts, w, h, window, processor, control, gl, options.gpuHints, frameStart);
              progress.update(++count, pts);
            } finally {
              if (frame != null) frame.recycle();
              control.releaseFrame();
            }
            lastActivity = System.nanoTime();
          }
          outputEos = eos || pts >= window.endUs;
        }
        if (System.nanoTime() - lastActivity > 30000000000L)
          throw new IllegalStateException("Decoder stalled for 30 seconds");
      }
      } // MediaCodec branch
      if (count == 0) throw new IllegalArgumentException("Selected interval has no frames");
      control.check();
      encoder.signalEndOfInputStream();
      drain.finish();
      if (options.preserveAudio) copyAudio(uri, src, window, drain.audioTracks, mux, control);
      control.check();
      mux.stop();
      mux.release();
      mux = null;
      control.check();
      if (destination.exists() && !destination.delete())
        throw new IllegalStateException("Cannot replace destination");
      if (!partial.renameTo(destination)) throw new IllegalStateException("Cannot finalize output");
      success = true;
    } finally {
      if (ffmpeg != null) {
        try { ffmpeg.close(); }
        catch (Exception closeFailure) { android.util.Log.w("VideoProcessing", "FFmpeg close failed", closeFailure); }
      }
      if (drain != null) drain.close();
      if (decoder != null) {
        if (decoderStarted)
          try {
            decoder.stop();
          } catch (Exception ignored) {
          }
        decoder.release();
      }
      if (gl != null) gl.close();
      if (encoder != null) {
        if (encoderStarted)
          try {
            encoder.stop();
          } catch (Exception ignored) {
          }
        encoder.release();
      }
      if (encoderSurface != null) encoderSurface.release();
      if (input != null) input.release();
      if (mux != null) mux.release();
      if (!success) partial.delete();
    }
  }

  private void copyAudio(
      Uri uri,
      Source src,
      VideoPolicy.Window window,
      List<Integer> tracks,
      MediaMuxer mux,
      VideoPolicy.Control control)
      throws Exception {
    for (int i = 0; i < src.audioIndices.size(); i++) {
      MediaExtractor audio = extractor(uri);
      try {
        audio.selectTrack(src.audioIndices.get(i));
        audio.seekTo(window.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long previous = -1;
        while (true) {
          control.check();
          long t = audio.getSampleTime();
          if (audio.getSampleTrackIndex() < 0 || t >= window.endUs) break;
          VideoPolicy.requireSourcePts(t);
          if (t >= window.startUs) {
            buffer.clear();
            int n = audio.readSampleData(buffer, 0);
            if (n < 0) break;
            if (n > buffer.capacity())
              throw new IllegalArgumentException("Audio packet exceeds bounded 1MiB");
            long normalized = window.normalize(t);
            if (normalized <= previous)
              throw new IllegalArgumentException("Non-increasing audio PTS");
            if ((audio.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
              throw new IllegalArgumentException("Encrypted audio unsupported");
            info.set(0, n, normalized, 0);
            mux.writeSampleData(tracks.get(i), buffer, info);
            previous = normalized;
          }
          audio.advance();
        }
      } finally {
        audio.release();
      }
    }
  }
}
