package com.tumuyan.ncnn.realsr;

/** Pure host-testable video timing rules. All values are microseconds. */
public final class VideoPolicy {
  private VideoPolicy() {}

  public static void requireSourcePts(long pts) {
    if (pts < 0)
      throw new IllegalArgumentException(
          "Negative source PTS/preroll unsupported; no samples silently discarded");
  }

  public static int visibleDimension(int coded, int first, int last) {
    if (last == -1) return coded;
    if (coded <= 0 || first < 0 || last < first || last >= coded)
      throw new IllegalArgumentException("Invalid decoder crop");
    return last - first + 1;
  }

  public static boolean unsupportedProfile(String mime, int profile) {
    if ("video/dolby-vision".equals(mime)) return true;
    if ("video/hevc".equals(mime)) return profile != 0 && profile != 1;
    if ("video/avc".equals(mime)) return profile == 16 || profile == 32 || profile == 64;
    if ("video/x-vnd.on2.vp9".equals(mime)) return profile >= 4;
    if ("video/av01".equals(mime)) return profile != 0 && profile != 1;
    return false;
  }

  public static void validate(
      int width, int height, int scale, int transfer, String audio, boolean preserve) {
    if (width <= 0
        || height <= 0
        || scale < 1
        || scale > 4
        || (long) width * scale > 8192
        || (long) height * scale > 8192
        || (long) width * height * scale * scale > 16777216L)
      throw new IllegalArgumentException(
          "Output exceeds 8192 pixels per axis or 16 megapixels; select a smaller scale");
    if (transfer == 6 || transfer == 7)
      throw new IllegalArgumentException("HDR PQ/HLG is not supported; use an SDR source");
    if (preserve && audio != null && !audio.equals("audio/mp4a-latm"))
      throw new IllegalArgumentException(
          "Only AAC audio passthrough is supported. Disable preserve audio explicitly to make"
              + " silent video.");
  }

  public static void validateTile(int tile) {
    if (tile != 0 && (tile < 32 || tile > 4096))
      throw new IllegalArgumentException("Tile must be 0 (auto) or 32..4096");
  }

  public static String[] command(
      String engine, String model, String input, String output, int scale, int tile, boolean cpu) {
    validateTile(tile);
    CommandBuilder b =
        new CommandBuilder()
            .append(engine)
            .append("-i", input)
            .append("-o", output)
            .append("-m", model)
            .append("-s", scale)
            .append("-t", tile);
    b.appendIf(cpu, "-g", "-1");
    return b.buildArray();
  }

  public static final class Control {
    private volatile boolean cancelled;
    private boolean leased;

    public void cancel() {
      cancelled = true;
    }

    public void check() {
      if (cancelled || Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException("Video cancelled");
    }

    public synchronized void acquireFrame() {
      check();
      if (leased) throw new IllegalStateException("Only one frame may be in flight");
      leased = true;
    }

    public synchronized void releaseFrame() {
      leased = false;
    }
  }

  public static final class Window {
    public final long startUs, endUs;

    public Window(long startUs, long durationUs) {
      if (startUs < 0 || durationUs <= 0)
        throw new IllegalArgumentException("Invalid video interval");
      this.startUs = startUs;
      this.endUs = Math.addExact(startUs, durationUs);
    }

    public boolean contains(long pts) {
      return pts >= startUs && pts < endUs;
    }

    public long normalize(long pts) {
      return Math.subtractExact(pts, startUs);
    }
  }
}
