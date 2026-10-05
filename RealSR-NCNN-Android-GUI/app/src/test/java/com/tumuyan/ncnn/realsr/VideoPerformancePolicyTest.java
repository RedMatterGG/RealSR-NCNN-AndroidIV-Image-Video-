package com.tumuyan.ncnn.realsr;

/** Run directly with javac/java; also kept independent of Android for host verification. */
public final class VideoPerformancePolicyTest {
  private static void check(boolean value) { if (!value) throw new AssertionError(); }
  private static void rejects(Runnable r) {
    try { r.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Invalid performance setting accepted");
  }
  @org.junit.Test public void hostContracts() { main(new String[0]); }

  public static void main(String[] args) {
    VideoPerformancePolicy p = new VideoPerformancePolicy(false, 0, 0, 0);
    check(p.threads(128) == 8);
    check(p.threads(0) == 1);
    check(p.threads(6) == 6);
    check(p.threadArgument(6).equals("1:6:1"));
    check(!p.cpu && p.mode == VideoPerformancePolicy.BALANCED);
    for (int n : new int[] {1, 2, 4, 6, 8})
      check(new VideoPerformancePolicy(true, n, 1, 1000).threads(2) == n);
    rejects(() -> new VideoPerformancePolicy(false, 3, 0, 0));
    rejects(() -> new VideoPerformancePolicy(false, 0, 3, 0));
    rejects(() -> new VideoPerformancePolicy(false, 0, 0, 16));
    rejects(() -> new VideoPerformancePolicy(false, 0, 0, -1));
    check(p.initialTargetNanos() == 1000000000L);
    System.out.println("PASS: backend defaults, auto cap, explicit intraop threads, target/mode validation");
  }
}
