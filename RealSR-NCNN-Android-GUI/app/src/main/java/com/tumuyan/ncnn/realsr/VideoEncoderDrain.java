package com.tumuyan.ncnn.realsr;

import android.media.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Continuously drains encoder while EGL feeds it, avoiding swap/output deadlock. */
final class VideoEncoderDrain implements AutoCloseable {
  private final MediaCodec codec;
  private final MediaMuxer muxer;
  private final List<MediaFormat> audio;
  private Thread worker;
  private volatile boolean abort, done;
  private volatile Throwable error;
  private volatile boolean started;
  private int videoTrack;
  final List<Integer> audioTracks = new ArrayList<>();

  VideoEncoderDrain(MediaCodec codec, MediaMuxer muxer, List<MediaFormat> audio) {
    this.codec = codec;
    this.muxer = muxer;
    this.audio = audio;
  }

  void start() {
    worker =
        new Thread(
            () -> {
              try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (!abort) {
                  int index = codec.dequeueOutputBuffer(info, 10000);
                  if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (started) throw new IllegalStateException("Encoder changed format twice");
                    videoTrack = muxer.addTrack(codec.getOutputFormat());
                    for (MediaFormat f : audio) audioTracks.add(muxer.addTrack(f));
                    muxer.start();
                    started = true;
                  } else if (index >= 0) {
                    try {
                      if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                          && info.size > 0) {
                        if (!started)
                          throw new IllegalStateException("Encoder output before format");
                        ByteBuffer b = codec.getOutputBuffer(index);
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrack, b, info);
                      }
                      if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true;
                        break;
                      }
                    } finally {
                      codec.releaseOutputBuffer(index, false);
                    }
                  }
                }
              } catch (Throwable t) {
                error = t;
              }
            },
            "video-encoder-drain");
    worker.start();
  }

  void check() {
    if (error != null) throw new IllegalStateException("Encoder drain failed", error);
  }

  void finish() throws InterruptedException {
    worker.join(30000);
    check();
    if (!done) throw new IllegalStateException("Encoder EOS timed out");
  }

  boolean started() {
    return started;
  }

  @Override
  public void close() {
    abort = true;
    if (worker != null) {
      try {
        worker.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
