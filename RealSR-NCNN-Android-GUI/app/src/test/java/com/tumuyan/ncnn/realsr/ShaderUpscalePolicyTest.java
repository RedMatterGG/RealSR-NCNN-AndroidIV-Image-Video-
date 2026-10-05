package com.tumuyan.ncnn.realsr;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShaderUpscalePolicyTest {
  @Test public void supportsOnlyDocumentedIntegerScales() {
    assertTrue(ShaderUpscalePolicy.supportsScale(2));
    assertFalse(ShaderUpscalePolicy.supportsScale(3));
    assertTrue(ShaderUpscalePolicy.supportsScale(4));
    assertFalse(ShaderUpscalePolicy.supportsScale(1));
    assertFalse(ShaderUpscalePolicy.supportsScale(5));
  }
  @Test public void boundsIncludeDeviceLimitAndMemoryBudget() {
    assertEquals(3840, ShaderUpscalePolicy.outputDimension(1920, 2));
    ShaderUpscalePolicy.validate(1920, 1080, 2, 4096);
    assertThrows(IllegalArgumentException.class, () -> ShaderUpscalePolicy.validate(1920,1080,4,8192));
    assertThrows(IllegalArgumentException.class, () -> ShaderUpscalePolicy.validate(1025,10,4,4096));
    assertThrows(IllegalArgumentException.class, () -> ShaderUpscalePolicy.validate(0,10,2,4096));
    assertThrows(IllegalArgumentException.class, () -> ShaderUpscalePolicy.validate(10,10,3,4096));
    assertThrows(IllegalArgumentException.class, () -> ShaderUpscalePolicy.outputDimension(Integer.MAX_VALUE,4));
  }
}
