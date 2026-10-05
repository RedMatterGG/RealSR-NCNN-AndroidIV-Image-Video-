package com.tumuyan.ncnn.realsr;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import org.junit.Test;

public class AssetStreamsTest {
  @Test
  public void failedCopyNeverPublishesPartialAssetAndRetryRepairsIt() throws Exception {
    File directory = Files.createTempDirectory("asset-atomic").toFile();
    File target = new File(directory, "model.bin");
    InputStream broken = new InputStream() {
      int calls;
      public int read() throws IOException {
        if (calls++ == 0) return 123;
        throw new IOException("injected read failure");
      }
    };
    try {
      assertThrows(IOException.class, () -> AssetStreams.copy(target, broken, false));
      assertFalse("Partial model must never be published", target.exists());
      assertEquals("Temporary files removed", 0, directory.list().length);
      AssetStreams.copy(target, new ByteArrayInputStream(new byte[] {1,2,3}), true);
      assertArrayEquals(new byte[] {1,2,3}, Files.readAllBytes(target.toPath()));
    } finally { target.delete(); directory.delete(); }
  }

  @Test
  public void incompleteInstallRetriesAndCompleteInstallSkips() throws Exception {
    File dir = Files.createTempDirectory("asset-install").toFile();
    File marker = new File(dir, ".complete");
    int[] attempts = {0};
    try {
      assertThrows(IOException.class, () -> AssetStreams.install(marker, () -> {
        attempts[0]++;
        throw new IOException("injected incomplete extraction");
      }));
      assertFalse(marker.exists());
      AssetStreams.install(marker, () -> attempts[0]++);
      assertTrue(marker.isFile());
      AssetStreams.install(marker, () -> { throw new AssertionError("Already installed"); });
      assertEquals(2, attempts[0]);
    } finally { marker.delete(); dir.delete(); }
  }

  @Test
  public void failedOverwritePreservesCompletePreviousAsset() throws Exception {
    File target = Files.createTempFile("asset-existing", ".bin").toFile();
    byte[] original = {4,5,6};
    Files.write(target.toPath(), original);
    try {
      assertThrows(IOException.class, () -> AssetStreams.copy(target, new InputStream() {
        public int read() throws IOException { throw new IOException("injected failure"); }
      }, false));
      assertArrayEquals(original, Files.readAllBytes(target.toPath()));
    } finally { target.delete(); }
  }

  @Test
  public void skippedExistingAssetsStillCloseTheirInputStreams() throws Exception {
    File f = Files.createTempFile("asset-copy", ".bin").toFile();
    final boolean[] closed = {false};
    InputStream in =
        new ByteArrayInputStream(new byte[] {1, 2, 3}) {
          public void close() throws IOException {
            closed[0] = true;
            super.close();
          }
        };
    try {
      AssetStreams.copy(f, in, true);
      assertTrue(closed[0]);
      assertEquals(0, f.length());
    } finally {
      f.delete();
    }
  }
}
