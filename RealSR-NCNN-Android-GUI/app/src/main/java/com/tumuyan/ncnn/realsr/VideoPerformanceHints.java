package com.tumuyan.ncnn.realsr;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

/** Worker-confined, best-effort public OS APIs. Does not promise frequency or background boosts. */
public final class VideoPerformanceHints implements AutoCloseable {
  public static final String PREFS = "video_performance";
  private final int tid = Process.myTid();
  private final PowerManager power;
  private final VideoPerformancePolicy policy;
  private VideoPerformancePolicy.HintSession session;
  private String hintStatus = "ADPF unsupported (requires Android 12 / API 31)";
  private String priority = "priority unchanged";
  private String efficiency = optionalEfficiencyStatus(Build.VERSION.SDK_INT);
  static String optionalEfficiencyStatus(int sdk) {
    return sdk < 35 ? "optional power-efficiency preference unavailable on Android <15; GPU inference unaffected"
        : "optional power-efficiency preference not requested (no ADPF session)";
  }
  private long lastWorkNanos;
  private boolean closed;

  /** MUST be constructed inside the pipeline worker, never on the Activity/main thread. */
  public VideoPerformanceHints(Context context, VideoPerformancePolicy policy) {
    this.policy = policy;
    power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    if (policy.mode == VideoPerformancePolicy.HIGH) {
      try {
        Process.setThreadPriority(tid, -4);
        priority = "worker priority " + Process.getThreadPriority(tid) + " (requested -4)";
      } catch (RuntimeException e) { priority = "priority unavailable: " + failure(e); }
    }
    if (Build.VERSION.SDK_INT >= 31) {
      try {
        PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
        PerformanceHintManager.Session nativeSession = manager == null ? null
            : manager.createHintSession(new int[] {tid}, policy.initialTargetNanos());
        if (nativeSession == null) {
          hintStatus = "ADPF unsupported/unavailable: no session returned";
        } else {
          // Adopt immediately so even preference failure cannot leak the session.
          session = new VideoPerformancePolicy.HintSession(new VideoPerformancePolicy.Hint() {
            public void target(long nanos) { nativeSession.updateTargetWorkDuration(nanos); }
            public void report(long nanos) { nativeSession.reportActualWorkDuration(nanos); }
            public void close() { nativeSession.close(); }
          }, policy.targetMs == 0);
          if (Build.VERSION.SDK_INT >= 35) {
            try {
              boolean preferEfficiency = policy.mode != VideoPerformancePolicy.HIGH;
              nativeSession.setPreferPowerEfficiency(preferEfficiency);
              efficiency = "preferPowerEfficiency=" + preferEfficiency + " request accepted";
            } catch (RuntimeException e) { efficiency = "power preference unavailable: " + failure(e); }
          }
        }
      } catch (RuntimeException e) { hintStatus = "ADPF unavailable: " + failure(e); }
    }
  }

  private static String failure(RuntimeException e) {
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  /** Uptime clock matches ADPF contract, excludes deep sleep. API 31..34 has ms precision. */
  public static long workClockNanos() {
    return Build.VERSION.SDK_INT >= 35 ? SystemClock.uptimeNanos() : SystemClock.uptimeMillis() * 1000000L;
  }

  public int workerTid() { return tid; }
  public boolean isClosed() { return closed; }
  public long lastWorkNanos() { return lastWorkNanos; }

  public void report(long nanos) {
    if (closed || nanos <= 0) return;
    lastWorkNanos = nanos;
    if (session != null) session.report(nanos);
  }

  public String thermal() {
    if (Build.VERSION.SDK_INT < 29) return "thermal unsupported (requires API 29)";
    if (power == null) return "thermal unavailable";
    try {
      int value = power.getCurrentThermalStatus();
      String[] names = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};
      return "thermal " + (value >= 0 && value < names.length ? names[value] : "unknown " + value);
    } catch (RuntimeException e) { return "thermal unavailable: " + failure(e); }
  }

  public String diagnostics() {
    return "pipeline TID " + tid + " • " + priority + " • "
        + (session == null ? hintStatus : session.status()) + " • " + efficiency
        + " • work target " + (policy.targetMs == 0 ? "Auto (previous measured inference; bootstrap 1000ms)"
            : policy.targetMs + "ms requested") + " • " + thermal();
  }

  @Override public void close() {
    if (closed) return;
    closed = true;
    if (session != null) session.close();
    android.util.Log.i("VideoProcessing", "Hint scope closed (best effort): " + diagnostics());
    // Thermal status is polled, not subscribed: no listener or Activity reference to leak.
  }

  // Every video job uses HIGH. Legacy mode values are ignored; other settings remain independent.
  public static VideoPerformancePolicy load(SharedPreferences prefs) {
    try {
      return new VideoPerformancePolicy(prefs.getBoolean("cpu", false),
          prefs.getInt("cpu_threads", 0), VideoPerformancePolicy.HIGH, prefs.getInt("target_ms", 0));
    } catch (IllegalArgumentException | ClassCastException e) {
      return new VideoPerformancePolicy(false, 0, VideoPerformancePolicy.HIGH, 0);
    }
  }

  public static void save(SharedPreferences prefs, VideoPerformancePolicy p) {
    prefs.edit().putBoolean("cpu", p.cpu).putInt("cpu_threads", p.requestedThreads)
        .putInt("mode", VideoPerformancePolicy.HIGH).putInt("target_ms", p.targetMs).apply();
  }

  public static Intent putExtras(Intent intent, VideoPerformancePolicy p, int processors) {
    return intent.putExtra("cpu", p.cpu).putExtra("threads", p.threadArgument(processors))
        .putExtra("video_cpu_threads", p.requestedThreads).putExtra("video_performance_mode", VideoPerformancePolicy.HIGH)
        .putExtra("video_work_target_ms", p.targetMs);
  }

  public static VideoPerformancePolicy fromIntent(Intent intent) {
    return new VideoPerformancePolicy(intent.getBooleanExtra("cpu", false),
        intent.getIntExtra("video_cpu_threads", 0), VideoPerformancePolicy.HIGH,
        intent.getIntExtra("video_work_target_ms", 0));
  }
}
