package com.tumuyan.ncnn.realsr;

import java.io.*;

/** Owns source streams and publishes only fully written/closed assets. */
final class AssetStreams {
  private AssetStreams() {}

  interface Installer { void install() throws IOException; }

  static void install(File completionMarker, Installer installer) throws IOException {
    if (completionMarker.isFile()) return;
    installer.install();
    copy(completionMarker, new ByteArrayInputStream(new byte[] {1}), false);
  }

  static void copy(File target, InputStream source, boolean skip) throws IOException {
    File temporary = null;
    try {
      try (InputStream in = source) {
        if (skip && target.isFile()) return;
        File parent = target.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
          throw new IOException("Cannot create asset directory");
        temporary = File.createTempFile(".asset-", ".partial", parent);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
          byte[] buffer = new byte[8192];
          int n;
          while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
          out.getFD().sync();
        }
      }
      // Android's same-filesystem rename atomically replaces an existing destination.
      // Never delete a valid destination first: if rename is refused, preserve it and fail.
      if (!temporary.renameTo(target)) throw new IOException("Cannot publish complete asset: " + target);
    } finally {
      if (temporary != null && temporary.exists()) temporary.delete();
    }
  }
}
