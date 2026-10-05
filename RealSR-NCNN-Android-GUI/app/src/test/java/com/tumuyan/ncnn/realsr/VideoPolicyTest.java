package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;

import org.junit.Test;

public class VideoPolicyTest {
  @Test
  public void tileValidationMatchesNativeCliBoundaries() {
    VideoPolicy.command("engine", "model", "in", "out", 2, 0, false);
    VideoPolicy.command("engine", "model", "in", "out", 2, 32, false);
    for (int tile : new int[] {-1, 1, 31, 4097}) {
      assertThrows(IllegalArgumentException.class,
          () -> VideoPolicy.command("engine", "model", "in", "out", 2, tile, false));
    }
  }

  @Test
  public void validationRejectsHdrUnsupportedAudioAndOverflow() {
    VideoPolicy.validate(320, 240, 2, 0, "audio/mp4a-latm", true);
    assertThrows(
        IllegalArgumentException.class, () -> VideoPolicy.validate(320, 240, 2, 6, null, true));
    assertThrows(
        IllegalArgumentException.class, () -> VideoPolicy.validate(320, 240, 2, 7, null, true));
    assertThrows(
        IllegalArgumentException.class,
        () -> VideoPolicy.validate(320, 240, 2, 0, "audio/opus", true));
    assertThrows(
        IllegalArgumentException.class,
        () -> VideoPolicy.validate(Integer.MAX_VALUE, 240, 4, 0, null, true));
    assertThrows(IllegalArgumentException.class, () -> new VideoPolicy.Window(-1, 5));
    assertThrows(ArithmeticException.class, () -> new VideoPolicy.Window(Long.MAX_VALUE, 5));
    VideoPolicy.validate(320, 240, 2, 0, "audio/opus", false);
  }

  @Test
  public void argumentArrayPreservesPathsWithoutShellInterpretation() {
    String[] a =
        VideoPolicy.command(
            "/a b/engine", "/m ' $/model", "/tmp/in.png", "/tmp/out.png", 2, 64, true);
    assertArrayEquals(
        new String[] {
          "/a b/engine",
          "-i",
          "/tmp/in.png",
          "-o",
          "/tmp/out.png",
          "-m",
          "/m ' $/model",
          "-s",
          "2",
          "-t",
          "64",
          "-g",
          "-1"
        },
        a);
  }

  @Test
  public void cancellationAndFrameLeaseEnforceBoundedWork() {
    VideoPolicy.Control c = new VideoPolicy.Control();
    c.acquireFrame();
    assertThrows(IllegalStateException.class, c::acquireFrame);
    c.releaseFrame();
    c.acquireFrame();
    c.releaseFrame();
    c.cancel();
    assertThrows(java.util.concurrent.CancellationException.class, c::check);
  }

  @Test
  public void negativeSourcePtsFailVisiblyInsteadOfBeingTreatedAsEos() {
    assertThrows(IllegalArgumentException.class, () -> VideoPolicy.requireSourcePts(-23219));
    VideoPolicy.requireSourcePts(0);
    VideoPolicy.requireSourcePts(250000);
  }

  @Test
  public void decoderCodedPaddingUsesInclusiveCropDimensions() {
    assertEquals(1080, VideoPolicy.visibleDimension(1088, 0, 1079));
    assertEquals(1920, VideoPolicy.visibleDimension(1920, 0, -1));
    assertThrows(IllegalArgumentException.class, () -> VideoPolicy.visibleDimension(1088, 10, 9));
  }

  @Test
  public void rejectsTaggedTenBitProfilesAndDolbyVision() {
    assertFalse(VideoPolicy.unsupportedProfile("video/hevc", 1));
    assertTrue(VideoPolicy.unsupportedProfile("video/hevc", 2));
    assertTrue(VideoPolicy.unsupportedProfile("video/dolby-vision", 1));
    assertTrue(VideoPolicy.unsupportedProfile("video/avc", 16));
    assertTrue(VideoPolicy.unsupportedProfile("video/x-vnd.on2.vp9", 4));
  }

  @Test
  public void rejectsExcessiveFrameMemoryBeforeAllocating() {
    assertThrows(
        IllegalArgumentException.class, () -> VideoPolicy.validate(3840, 2160, 2, 0, null, false));
    VideoPolicy.validate(1920, 1080, 2, 0, null, false);
  }

  @Test
  public void previewKeepsVariableTimestampsAndSharedOrigin() {
    VideoPolicy.Window w = new VideoPolicy.Window(45000000L, 5000000L);
    assertFalse(w.contains(44999999));
    assertTrue(w.contains(45000000));
    assertEquals(33123, w.normalize(45033123));
    assertFalse(w.contains(50000000));
    assertEquals(1000, w.normalize(45001000));
  }
}
