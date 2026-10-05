package com.tumuyan.ncnn.realsr;
import java.util.List;
/** Immutable decoder selection, independent of neural inference backend. */
public final class VideoDecodePolicy {
 public static final String MEDIACODEC = "mediacodec", FFMPEG = "ffmpeg";
 public static final String EXTRA = "video_decoder_backend";
 public static final String PREFS = "video_decoder";
 public static final List<String> CHOICES = java.util.Collections.unmodifiableList(java.util.Arrays.asList(MEDIACODEC, FFMPEG));
 public final String backend;
 public VideoDecodePolicy(String backend) {
  if (backend == null || !CHOICES.contains(backend)) throw new IllegalArgumentException("Unknown video decoder: " + backend);
  this.backend = backend;
 }
}
