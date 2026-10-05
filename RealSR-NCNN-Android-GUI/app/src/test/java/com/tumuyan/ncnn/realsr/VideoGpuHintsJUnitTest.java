package com.tumuyan.ncnn.realsr;
import org.junit.Test;
/** Include standalone host regression runners in the normal Gradle unit suite. */
public class VideoGpuHintsJUnitTest {
  @Test public void policyGuards() { VideoGpuHintPolicyTest.main(new String[0]); }
  @Test public void timingOwnershipAndFailure() { VideoGpuHintsTest.main(new String[0]); }
}
