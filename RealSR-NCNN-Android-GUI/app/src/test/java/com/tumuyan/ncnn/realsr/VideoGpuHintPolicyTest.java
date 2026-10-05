package com.tumuyan.ncnn.realsr;
public final class VideoGpuHintPolicyTest {
  public static void main(String[] args) {
    if (VideoGpuHintPolicy.enabled(false,false,36,true)) throw new AssertionError("off default");
    if (VideoGpuHintPolicy.enabled(true,true,36,true)) throw new AssertionError("CPU must disable GPU request");
    if (VideoGpuHintPolicy.enabled(true,false,35,true)) throw new AssertionError("API guard");
    if (!VideoGpuHintPolicy.enabled(true,false,36,false)) throw new AssertionError("Android16 GPU remains selectable without graphics capability");
    if (!VideoGpuHintPolicy.enabled(true,false,36,true)) throw new AssertionError("GPU request");
    System.out.println("GPU policy: 5 assertions passed");
  }
}
