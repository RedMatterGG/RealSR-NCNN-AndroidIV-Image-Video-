package com.tumuyan.ncnn.realsr;

public final class VideoHintSessionTest {
  static class Hint implements VideoPerformancePolicy.Hint {
    int reports, closes; long target, actual; boolean denied;
    public void target(long n) { target = n; }
    public void report(long n) { if (denied) throw new SecurityException("denied"); reports++; actual = n; }
    public void close() { closes++; }
  }
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  @org.junit.Test public void hostContracts() { main(new String[0]); }

  public static void main(String[] args) {
    for (int ending = 0; ending < 3; ending++) {
      Hint hint = new Hint();
      VideoPerformancePolicy.HintSession session = new VideoPerformancePolicy.HintSession(hint, true);
      try (VideoPerformancePolicy.HintSession owned = session) {
        owned.report(2500000000L);
        check(hint.actual == 2500000000L && hint.target == 2500000000L);
        if (ending == 1) throw new java.util.concurrent.CancellationException();
        if (ending == 2) throw new IllegalStateException("pipeline failed");
      } catch (RuntimeException expected) { }
      session.close();
      session.report(1000000000L);
      check(hint.closes == 1 && hint.reports == 1);
    }
    Hint denied = new Hint(); denied.denied = true;
    try (VideoPerformancePolicy.HintSession session = new VideoPerformancePolicy.HintSession(denied, false)) {
      session.report(500000000L);
      check(session.status().contains("unavailable"));
      session.report(500000000L);
    }
    check(denied.closes == 1);
    System.out.println("PASS: hint measured target/report, completion/cancel/error idempotent cleanup, denied visible");
  }
}
