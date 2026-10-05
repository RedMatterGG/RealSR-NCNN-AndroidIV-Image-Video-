package com.tumuyan.ncnn.realsr;

/** Android-independent validated video settings. No image preference inheritance. */
public final class VideoPerformancePolicy {
  public static final int BALANCED = 0, HIGH = 1, SUSTAINED = 2;
  public static final int[] THREAD_CHOICES = {0, 1, 2, 4, 6, 8};
  public static final int[] TARGET_CHOICES_MS = {0, 250, 1000, 5000};
  public final boolean cpu;
  public final int requestedThreads, mode, targetMs;

  public VideoPerformancePolicy(boolean cpu, int threads, int mode, int targetMs) {
    if (!contains(THREAD_CHOICES, threads))
      throw new IllegalArgumentException("CPU threads must be Auto, 1, 2, 4, 6 or 8");
    if (mode < BALANCED || mode > SUSTAINED)
      throw new IllegalArgumentException("Unknown video performance mode");
    if (!contains(TARGET_CHOICES_MS, targetMs))
      throw new IllegalArgumentException("Work target must be measured Auto, 250, 1000 or 5000 ms");
    this.cpu = cpu;
    this.requestedThreads = threads;
    this.mode = mode;
    this.targetMs = targetMs;
  }

  /** Adapter seam for real Android sessions and host cleanup/failure tests. Worker-confined. */
  public interface Hint {
    void target(long nanos);
    void report(long nanos);
    void close();
  }

  public static final class HintSession implements AutoCloseable {
    private Hint hint;
    private final boolean measuredTarget;
    private long previous;
    private String status = "ADPF session created (best effort)";

    public HintSession(Hint hint, boolean measuredTarget) {
      this.hint = hint;
      this.measuredTarget = measuredTarget;
    }

    public void report(long nanos) {
      if (hint == null || nanos <= 0) return;
      try {
        // Auto uses the last measured cycle, never an invented realtime frame deadline.
        if (measuredTarget) hint.target(previous == 0 ? nanos : previous);
        hint.report(nanos);
        previous = nanos;
        status = "ADPF reports accepted (best effort)";
      } catch (RuntimeException e) {
        status = "ADPF unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        close();
      }
    }

    public String status() { return status; }

    @Override public void close() {
      Hint owned = hint;
      hint = null;
      if (owned != null) {
        try { owned.close(); }
        catch (RuntimeException e) { status = "ADPF close failed: " + e.getClass().getSimpleName(); }
      }
    }
  }

  private static boolean contains(int[] choices, int value) {
    for (int n : choices) if (n == value) return true;
    return false;
  }

  public int threads(int availableProcessors) {
    return requestedThreads == 0 ? Math.max(1, Math.min(8, availableProcessors)) : requestedThreads;
  }

  public String threadArgument(int availableProcessors) {
    return "1:" + threads(availableProcessors) + ":1";
  }

  public long initialTargetNanos() {
    return (targetMs == 0 ? 1000L : targetMs) * 1000000L;
  }

  public String description(int availableProcessors) {
    return (cpu ? "CPU" : "Vulkan GPU") + " • CPU intraop " + threads(availableProcessors)
        + (requestedThreads == 0 ? " (Auto)" : "") + " • "
        + new String[] {"Balanced", "High performance", "Sustained (visible window only)"}[mode];
  }
}
