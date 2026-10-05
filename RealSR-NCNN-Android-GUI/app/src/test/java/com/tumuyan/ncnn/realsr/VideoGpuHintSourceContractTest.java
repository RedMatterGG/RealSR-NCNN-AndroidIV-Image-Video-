package com.tumuyan.ncnn.realsr;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
/** Integrating graphics timing must not accidentally synchronize reset-only jobs. */
public class VideoGpuHintSourceContractTest {
 private String source(String name)throws Exception{
  Path app=Files.isDirectory(Paths.get("src"))?Paths.get(""):Paths.get("app");
  return new String(Files.readAllBytes(app.resolve("src/main/java/com/tumuyan/ncnn/realsr/"+name+".java")),StandardCharsets.UTF_8);
 }
 @Test public void ordinarySessionNeverTriggersGraphicsSynchronization()throws Exception{
  String pipeline=source("VideoPipeline"),gl=source("VideoGl");
  assertTrue(pipeline.contains("gpuHints != null && gpuHints.measuringGraphics()"));
  assertFalse(pipeline.contains("gpuHints != null && gpuHints.active()"));
  assertTrue(pipeline.contains("gl.encode(result, normalized, measureGraphics)"));
  assertTrue(pipeline.contains("if (measureGraphics) gpuHints.reportFrame"));
  assertTrue(gl.contains("if (waitForDrawing) { GLES20.glFinish();"));
 }
 @Test public void uiEligibilityDoesNotProbeOrClearSavedPreference()throws Exception{
  String a=source("VideoActivity");
  assertTrue(a.contains("gpuRequestSupported = Build.VERSION.SDK_INT >= 36"));
  assertFalse(a.contains("VideoGpuHints.supported"));
  assertFalse(a.contains("setChecked(gpuRequestSupported"));
  assertTrue(a.contains("gpuRequest.setEnabled(gpuRequestSupported && !cpu)"));
  assertFalse(a.contains("High performance is fixed"));
 }
}
