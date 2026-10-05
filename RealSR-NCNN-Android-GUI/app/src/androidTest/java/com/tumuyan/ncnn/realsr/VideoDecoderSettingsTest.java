package com.tumuyan.ncnn.realsr;
import android.content.*;
import android.widget.Spinner;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import static org.junit.Assert.*;
public class VideoDecoderSettingsTest {
 @Test public void selectionPersistsSeparatelyFromInferenceAndIntentIsExplicit() {
  Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
  SharedPreferences prefs = c.getSharedPreferences(VideoDecodePolicy.PREFS, 0);
  prefs.edit().clear().commit();
  assertEquals("mediacodec", VideoActivity.loadDecoder(prefs).backend);
  VideoActivity.saveDecoder(prefs, new VideoDecodePolicy("ffmpeg"));
  assertEquals("ffmpeg", VideoActivity.loadDecoder(prefs).backend);
  Intent job = VideoActivity.putDecoder(new Intent(), VideoActivity.loadDecoder(prefs));
  assertEquals("ffmpeg", job.getStringExtra(VideoDecodePolicy.EXTRA));
  assertEquals("ffmpeg", VideoProcessingService.decoderFromIntent(job).backend);
  assertFalse(job.hasExtra("cpu"));
  try { VideoProcessingService.decoderFromIntent(new Intent().putExtra(VideoDecodePolicy.EXTRA, "typo")); fail(); }
  catch (IllegalArgumentException expected) { }
  prefs.edit().clear().commit();
 }
 @Test public void activityExposesSeparateDecoderChoice() {
  try (ActivityScenario<VideoActivity> s = ActivityScenario.launch(VideoActivity.class)) {
   s.onActivity(a -> {
    Spinner decoder = a.findViewById(R.id.video_decoder);
    assertEquals(2, decoder.getCount());
    assertNotSame(decoder, a.findViewById(R.id.video_backend));
   });
  }
 }
 @Test public void optionalApi35PreferenceDiagnosticIsNeutralOnOlderAndroid() {
  String message = VideoPerformanceHints.optionalEfficiencyStatus(34);
  assertTrue(message.contains("optional"));
  assertTrue(message.contains("GPU inference unaffected"));
  assertFalse(message.contains("failure"));
 }
}
