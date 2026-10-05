package com.tumuyan.ncnn.realsr;
/** Independent of CPU ADPF and worker priority; every job snapshots the request. */
public final class VideoGpuHintPolicy {
  public static final String KEY = "video_gpu_performance_request";
  private VideoGpuHintPolicy() {}
  public static boolean enabled(boolean requested, boolean cpu, int sdk, boolean supported) {
    return requested && !cpu && sdk >= 36;
  }
}
