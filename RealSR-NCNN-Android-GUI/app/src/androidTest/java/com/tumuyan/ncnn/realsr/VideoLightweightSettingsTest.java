package com.tumuyan.ncnn.realsr;
import android.widget.Spinner;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real UI cases; compile-only until an Android device runs them. */
public class VideoLightweightSettingsTest {
  @Test public void lightweightChoicesExposeOnlyTheirNativeScales() {
    try(ActivityScenario<VideoActivity> scenario=ActivityScenario.launch(VideoActivity.class)) {
      scenario.onActivity(activity -> {
        Spinner model=activity.findViewById(R.id.video_model);
        assertEquals(VideoUpscalerCatalog.OPTIONS.size(),model.getCount());
        int selected=-1;
        for(int i=0;i<VideoUpscalerCatalog.OPTIONS.size();i++)
          if(VideoUpscalerCatalog.OPTIONS.get(i).model.equals("models-RealeSR-general-v3")) selected=i;
        assertTrue(selected>=0);
        model.setSelection(selected);
      });
      androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      scenario.onActivity(activity -> {
        Spinner scale=activity.findViewById(R.id.video_scale);
        assertEquals(1,scale.getCount());
        assertEquals("4x",scale.getSelectedItem().toString());
      });
    }
  }
}
