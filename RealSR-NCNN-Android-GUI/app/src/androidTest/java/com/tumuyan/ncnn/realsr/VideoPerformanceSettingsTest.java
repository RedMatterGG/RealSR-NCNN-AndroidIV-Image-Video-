package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Device tests; no claim of real NCNN, ADPF or phone throughput verification. */
public class VideoPerformanceSettingsTest {
  @Test public void videoDefaultsAreGpuAndIndependentOfImagePreferences() {
    assertNotEquals("config", VideoPerformanceHints.PREFS);
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("video_performance_test", 0);
    prefs.edit().clear().commit();
    VideoPerformancePolicy p = VideoPerformanceHints.load(prefs);
    assertFalse(p.cpu);
    assertEquals(0, p.requestedThreads);
    assertEquals(VideoPerformancePolicy.HIGH, p.mode);
  }

  @Test public void videoPreferencesAndIntentPreserveExplicitCpuIntraopAndTarget() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("video_performance_test", 0);
    try {
      VideoPerformanceHints.save(prefs, new VideoPerformancePolicy(true, 6, 1, 5000));
      VideoPerformancePolicy loaded = VideoPerformanceHints.load(prefs);
      Intent intent = VideoPerformanceHints.putExtras(new Intent(), loaded, 8);
      assertTrue(intent.getBooleanExtra("cpu", false));
      assertEquals("1:6:1", intent.getStringExtra("threads"));
      VideoPerformancePolicy passed = VideoPerformanceHints.fromIntent(intent);
      assertTrue(passed.cpu);
      assertEquals(6, passed.requestedThreads);
      assertEquals(VideoPerformancePolicy.HIGH, passed.mode);
      assertEquals(5000, passed.targetMs);
    } finally { prefs.edit().clear().commit(); }
  }

  @Test public void legacyModesBecomeHighWithoutChangingVideoOrImageSettings() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("video_performance_test", 0);
    SharedPreferences image = context.getSharedPreferences("config", 0);
    java.util.Map<String, ?> imageBefore = new java.util.HashMap<>(image.getAll());
    try {
      for (int mode : new int[]{VideoPerformancePolicy.BALANCED, VideoPerformancePolicy.SUSTAINED}) {
        prefs.edit().clear().putBoolean("cpu", true).putInt("cpu_threads", 6)
            .putInt("mode", mode).putInt("target_ms", 5000).commit();
        VideoPerformancePolicy loaded = VideoPerformanceHints.load(prefs);
        assertEquals(VideoPerformancePolicy.HIGH, loaded.mode);
        assertTrue(loaded.cpu);
        assertEquals(6, loaded.requestedThreads);
        assertEquals(5000, loaded.targetMs);
        VideoPerformancePolicy legacy = new VideoPerformancePolicy(true, 6, mode, 5000);
        VideoPerformanceHints.save(prefs, legacy);
        assertEquals(VideoPerformancePolicy.HIGH, prefs.getInt("mode", -1));
        Intent sent = VideoPerformanceHints.putExtras(new Intent(), legacy, 8);
        assertEquals(VideoPerformancePolicy.HIGH, sent.getIntExtra("video_performance_mode", -1));
        assertEquals("1:6:1", sent.getStringExtra("threads"));
      }
      assertEquals(imageBefore, image.getAll());
    } finally { prefs.edit().clear().commit(); }
  }

  @Test public void serviceIntentDefaultsAndLegacyModesAreHighAndRemainGpu() {
    for (int mode : new int[]{-1, VideoPerformancePolicy.BALANCED, VideoPerformancePolicy.SUSTAINED}) {
      Intent job = new Intent();
      if (mode >= 0) job.putExtra("video_performance_mode", mode);
      VideoPerformancePolicy gpu = VideoPerformanceHints.fromIntent(job);
      assertEquals(VideoPerformancePolicy.HIGH, gpu.mode);
      assertFalse("missing backend extra must remain GPU", gpu.cpu);
      job.putExtra("cpu", true).putExtra("video_cpu_threads", 4).putExtra("video_work_target_ms", 250);
      VideoPerformancePolicy cpu = VideoPerformanceHints.fromIntent(job);
      assertEquals(VideoPerformancePolicy.HIGH, cpu.mode);
      assertTrue(cpu.cpu);
      assertEquals(4, cpu.requestedThreads);
      assertEquals(250, cpu.targetMs);
    }
  }

  @Test public void activityHasNoPerformanceModeChoice() {
    try (androidx.test.core.app.ActivityScenario<VideoActivity> scenario =
        androidx.test.core.app.ActivityScenario.launch(VideoActivity.class)) {
      scenario.onActivity(activity -> {
        assertEquals(0, activity.getResources().getIdentifier("video_performance_mode", "id", activity.getPackageName()));
        assertFalse(containsModeLabel(activity.findViewById(android.R.id.content)));
        assertNotNull(activity.findViewById(R.id.video_backend));
        assertNotNull(activity.findViewById(R.id.video_cpu_threads));
        assertNotNull(activity.findViewById(R.id.video_work_target));
      });
    }
  }

  private boolean containsModeLabel(android.view.View view) {
    if (view instanceof android.widget.TextView &&
        "Video performance mode".contentEquals(((android.widget.TextView) view).getText())) return true;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) if (containsModeLabel(group.getChildAt(i))) return true;
    }
    return false;
  }

  @Test public void hintsBindToCallingWorkerAndCloseIdempotently() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    final Throwable[] failure = {null};
    Thread thread = new Thread(() -> {
      try (VideoPerformanceHints hints = new VideoPerformanceHints(context,
          VideoPerformanceHints.fromIntent(new Intent()))) {
        assertEquals(android.os.Process.myTid(), hints.workerTid());
        hints.report(1000000000L);
        assertFalse(hints.diagnostics().isEmpty());
        hints.close(); hints.close();
        assertTrue(hints.isClosed());
      } catch (Throwable e) { failure[0] = e; }
    }, "video-hints-test");
    thread.start(); thread.join(10000);
    assertFalse("worker did not stop", thread.isAlive());
    if (failure[0] != null) throw new AssertionError(failure[0]);
  }
}
