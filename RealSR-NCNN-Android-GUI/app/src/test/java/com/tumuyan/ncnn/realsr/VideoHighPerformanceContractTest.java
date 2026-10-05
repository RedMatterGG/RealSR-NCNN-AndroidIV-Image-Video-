package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;
import java.nio.file.*;
import org.junit.Test;

/** Source guard: removed controls must not silently regain stale selection wiring. */
public class VideoHighPerformanceContractTest {
  private String source(String relative) throws Exception {
    Path app = Files.isDirectory(Paths.get("src")) ? Paths.get("") : Paths.get("app");
    return new String(Files.readAllBytes(app.resolve("src/main").resolve(relative)), java.nio.charset.StandardCharsets.UTF_8);
  }
  @Test public void noPerformanceChoiceOrSustainedWindowRequest() throws Exception {
    String layout = source("res/layout/activity_video.xml");
    assertFalse("performance spinner must be removed", layout.contains("video_performance_mode"));
    assertFalse("performance choice label must be removed", layout.contains("Video performance mode"));
    String activity = source("java/com/tumuyan/ncnn/realsr/VideoActivity.java");
    assertFalse(activity.contains("video_performance_mode"));
    assertFalse(activity.contains("performance.getSelectedItemPosition"));
    assertFalse(activity.contains("setSustainedPerformanceMode"));
    assertTrue(activity.contains("VideoPerformancePolicy.HIGH"));
    for (String control : new String[]{"video_backend", "video_cpu_threads", "video_work_target", "video_model"})
      assertTrue("preserve control " + control, layout.contains(control));
  }
  @Test public void serviceUsesFixedHighIntentBoundary() throws Exception {
    String service = source("java/com/tumuyan/ncnn/realsr/VideoProcessingService.java");
    assertTrue(service.contains("VideoPerformanceHints.fromIntent(intent)"));
    String hints = source("java/com/tumuyan/ncnn/realsr/VideoPerformanceHints.java");
    assertFalse(hints.contains("getIntExtra(\"video_performance_mode\""));
    assertFalse(hints.contains("getInt(\"mode\""));
    assertTrue(hints.contains("Process.setThreadPriority(tid, -4)"));
    assertTrue(hints.contains("nativeSession.setPreferPowerEfficiency(preferEfficiency)"));
  }
}
