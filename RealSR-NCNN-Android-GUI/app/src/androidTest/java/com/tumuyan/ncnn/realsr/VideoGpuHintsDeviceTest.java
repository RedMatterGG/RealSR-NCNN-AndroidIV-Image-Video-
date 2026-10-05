package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** Compile is not execution. Acceptance of a hint is not a GPU frequency measurement. */
public class VideoGpuHintsDeviceTest {
  @Test public void persistedRequestIsSnapshottedNotReadAgainByWorker() {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs = c.getSharedPreferences("gpu_hint_device_test", 0);
    try {
      assertFalse(prefs.getBoolean(VideoGpuHintPolicy.KEY, false));
      assertTrue(prefs.edit().putBoolean(VideoGpuHintPolicy.KEY, true).commit());
      Intent job = new Intent().putExtra(VideoGpuHintPolicy.KEY,
          prefs.getBoolean(VideoGpuHintPolicy.KEY, false));
      prefs.edit().putBoolean(VideoGpuHintPolicy.KEY, false).commit();
      assertTrue(job.getBooleanExtra(VideoGpuHintPolicy.KEY, false));
    } finally { prefs.edit().clear().commit(); }
  }

  @Test public void optOutCpuAndOldApiNeverOpenNativeGraphicsSession() {
    try (VideoGpuHints off = VideoGpuHints.create(false, false, Build.VERSION.SDK_INT, 1000000000L, false);
         VideoGpuHints cpu = VideoGpuHints.create(true, true, Build.VERSION.SDK_INT, 1000000000L, false);
         VideoGpuHints old = VideoGpuHints.create(true, false, 35, 1000000000L, false)) {
      assertFalse(off.active()); assertFalse(cpu.active()); assertFalse(old.active());
      assertFalse(VideoGpuHints.supported(35));
      assertFalse(old.beginFrame());
    }
  }

  @Test public void activityPreservesCheckedPreferenceWithoutCapabilityGate() {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    SharedPreferences prefs=c.getSharedPreferences(VideoPerformanceHints.PREFS,0);
    boolean old=prefs.getBoolean(VideoGpuHintPolicy.KEY,false);
    prefs.edit().putBoolean(VideoGpuHintPolicy.KEY,true).commit();
    try(androidx.test.core.app.ActivityScenario<VideoActivity> scenario=androidx.test.core.app.ActivityScenario.launch(VideoActivity.class)){
      scenario.onActivity(a->{
        android.widget.CheckBox request=a.findViewById(R.id.video_gpu_request);
        android.widget.Spinner backend=a.findViewById(R.id.video_backend);
        assertTrue(request.isChecked());
        boolean gpu=backend.getSelectedItemPosition()!=1;
        assertEquals(Build.VERSION.SDK_INT>=36&&gpu,request.isEnabled());
        assertTrue(prefs.getBoolean(VideoGpuHintPolicy.KEY,false));
        assertFalse(((android.widget.TextView)a.findViewById(R.id.video_performance_info)).getText().toString().contains("High performance is fixed"));
      });
    }finally{prefs.edit().putBoolean(VideoGpuHintPolicy.KEY,old).commit();}
  }

  @Test public void runtimeCapabilityAndSessionCreationAreBestEffort() {
    boolean eligible = VideoGpuHintPolicy.enabled(true, false, Build.VERSION.SDK_INT, false);
    try (VideoGpuHints hints = VideoGpuHints.create(true, false, Build.VERSION.SDK_INT, 1000000000L, false)) {
      if (!eligible) assertFalse(hints.active());
      if (hints.active() && !hints.probe().graphics) assertFalse(hints.measuringGraphics());
      assertNotNull(hints.probe().missingExports);
      assertFalse(hints.status().isEmpty());
      // Do not send synthetic work or fabricated timing to the real Android service.
      hints.close(); hints.close(); assertFalse(hints.active());
    }
  }
}
