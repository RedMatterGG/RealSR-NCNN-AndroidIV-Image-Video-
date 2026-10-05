package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;
import org.junit.Test;

public class VideoJobGateTest {
  @Test
  public void completionKeepsAdmissionClosedUntilTeardownFinishes() {
    VideoJobGate gate = new VideoJobGate();
    assertTrue(gate.begin());
    gate.finish(() -> assertFalse("No job may start inside old teardown", gate.begin()));
    assertTrue("Next job admitted after teardown", gate.begin());
  }
}
