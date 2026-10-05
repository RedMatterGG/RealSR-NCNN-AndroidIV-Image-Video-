package com.tumuyan.ncnn.realsr;
import org.junit.Test;
import static org.junit.Assert.*;
public class VideoDecodePolicyTest {
 @Test public void acceptsExplicitBackends() {
  assertEquals("mediacodec", new VideoDecodePolicy("mediacodec").backend);
  assertEquals("ffmpeg", new VideoDecodePolicy("ffmpeg").backend);
 }
 @Test public void rejectsMissingAndUnknownWithoutFallback() {
  for (String value : new String[] {null, "", "FFmpeg", "unknown", " mediacodec"}) {
   try { new VideoDecodePolicy(value); fail("Must reject " + value); }
   catch (IllegalArgumentException expected) { }
  }
 }
 @Test public void acceptedChoicesAndSelectionAreImmutable() throws Exception {
  assertEquals(2, VideoDecodePolicy.CHOICES.size());
  assertTrue(java.lang.reflect.Modifier.isFinal(VideoDecodePolicy.class.getField("backend").getModifiers()));
  try { VideoDecodePolicy.CHOICES.add("other"); fail("Immutable choices"); }
  catch (UnsupportedOperationException expected) { }
  try { VideoDecodePolicy.CHOICES.set(0, "other"); fail("Immutable choices"); }
  catch (UnsupportedOperationException expected) { }
 }
}
