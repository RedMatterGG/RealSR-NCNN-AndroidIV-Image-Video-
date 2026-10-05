package com.tumuyan.ncnn.realsr;

import android.app.*;
import android.content.Intent;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.util.concurrent.*;

public final class VideoProcessingService extends Service {
  public static final String CANCEL = "video.cancel";
  public static volatile String status =
      "Choose an SDR video. Audio: AAC passthrough only. Subtitles/interpolation are not"
          + " supported.";
  public static volatile boolean running = false;
  public static volatile File result;
  private VideoPolicy.Control control;
  private Thread worker;
  private PowerManager.WakeLock wake;
  private final VideoJobGate jobs = new VideoJobGate();
  private final Handler main = new Handler(Looper.getMainLooper());
  private int latestStartId;

  static VideoDecodePolicy decoderFromIntent(Intent intent) {
    return new VideoDecodePolicy(intent.hasExtra(VideoDecodePolicy.EXTRA)
        ? intent.getStringExtra(VideoDecodePolicy.EXTRA) : VideoDecodePolicy.MEDIACODEC);
  }
  private Notification notification(String text) {
    Intent cancel = new Intent(this, VideoProcessingService.class).setAction(CANCEL);
    PendingIntent pi =
        PendingIntent.getService(
            this, 4, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            5,
            new Intent(this, VideoActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new NotificationCompat.Builder(this, "video-work")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("RealSR Video")
        .setContentText(text)
        .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .setOngoing(running)
        .addAction(0, "Cancel", pi)
        .build();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= 26)
      ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
          .createNotificationChannel(
              new NotificationChannel(
                  "video-work", "Video processing", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    latestStartId = startId;
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (CANCEL.equals(intent.getAction())) {
      if (control != null) control.cancel();
      else stopSelf();
      return START_NOT_STICKY;
    }
    if (!jobs.begin()) return START_NOT_STICKY;
    running = true;
    result = null;
    status = "Preparing engines and codecs…";
    control = new VideoPolicy.Control();
    try {
      startForeground(9, notification(status));
      wake =
          ((PowerManager) getSystemService(POWER_SERVICE))
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":video");
      wake.acquire(7L * 24 * 60 * 60 * 1000);
      final PowerManager.WakeLock jobWake = wake;
      final VideoPolicy.Control jobControl = control;
      worker =
          new Thread(
              () -> {
                File destination = new File(getCacheDir(), "realsr/video-output.mp4");
                String[] jobDiagnostics = {""};
                final VideoPipeline pipeline = new VideoPipeline(this);
                try {
                  VideoDecodePolicy decoderSelection = decoderFromIntent(intent);
                  VideoPerformancePolicy performance = VideoPerformanceHints.fromIntent(intent);
                  int processors = Runtime.getRuntime().availableProcessors();
                  try (VideoPerformanceHints hints = new VideoPerformanceHints(this, performance);
                      VideoGpuHints gpuHints = VideoGpuHints.create(
                          intent.getBooleanExtra(VideoGpuHintPolicy.KEY, false), performance.cpu,
                          Build.VERSION.SDK_INT, performance.initialTargetNanos(), performance.targetMs == 0)) {
                    jobDiagnostics[0] = performance.description(processors) + "\n" + hints.diagnostics() + " • " + gpuHints.status();
                    status = "Preparing engines and codecs…\n" + jobDiagnostics[0];
                    android.util.Log.i("VideoProcessing", status);
                    jobControl.check();
                    File engineRoot = new File(getFilesDir(), "video-engines");
                    VideoUpscalerCatalog.Entry selectedModel=VideoUpscalerCatalog.find(intent.getStringExtra("model"));
                    selectedModel.validate(intent.getIntExtra("scale",2),performance.cpu);
                    selectedModel.validateNoise(intent.getIntExtra("scale",2),intent.getIntExtra("noise",0));
                    if(!selectedModel.shader) AssetsCopyer.releaseVersionedAssets(this, engineRoot, BuildConfig.VERSION_CODE);
                    final String computeSettings = selectedModel.shader
                        ? "GPU OpenGL shader enhancement (not neural); NCNN tile/threads/denoise not used; decoder threads=" + performance.threads(processors)
                        : performance.description(processors);
                    File engines = new File(engineRoot, "realsr");
                    if (!destination.getParentFile().isDirectory()
                        && !destination.getParentFile().mkdirs())
                      throw new java.io.IOException("Cannot create output directory");
                    VideoPipeline.Options options = new VideoPipeline.Options();
                    options.gpuHints = gpuHints;
                    options.decoderBackend = decoderSelection.backend;
                    options.decoderThreads = performance.threads(processors);
                    options.scale = intent.getIntExtra("scale", 2);
                    options.mime = intent.getStringExtra("codec");
                    options.bitrate = intent.getIntExtra("bitrate", 12000000);
                    options.startUs = intent.getLongExtra("start", 0);
                    options.durationUs = intent.getLongExtra("duration", Long.MAX_VALUE);
                    options.preserveAudio = intent.getBooleanExtra("audio", true);
                    long start = SystemClock.elapsedRealtime();
                    final long[] lastFrameAt = {start};
                    final long interval =
                        options.durationUs == Long.MAX_VALUE
                            ? new VideoPipeline(this).inspect(intent.getData()).duration()
                            : options.durationUs;
                    try (VideoFrameEngine processor =
                        new VideoFrameEngine(
                            this,
                            engines,
                            new File(getCacheDir(), "video-frame"),
                            intent.getStringExtra("engine"),
                            intent.getStringExtra("model"),
                            options.scale,
                            intent.getIntExtra("tile", 0),
                            performance.cpu,
                            intent.getIntExtra("noise", -1),
                            performance.threadArgument(processors))) {
                      jobDiagnostics[0] = computeSettings + "\n"
                          + processor.diagnostics() + "\n" + hints.diagnostics() + " • " + gpuHints.status() + "\n" + pipeline.diagnostics();
                      status = "Engine initialized; preparing codecs…\n" + jobDiagnostics[0];
                      android.util.Log.i("VideoProcessing", status);
                      pipeline.run(
                              intent.getData(),
                              destination,
                              options,
                              (frame, cancellation) -> {
                                long workStart = VideoPerformanceHints.workClockNanos();
                                try { return processor.upscale(frame, cancellation); }
                                finally {
                                  hints.report(VideoPerformanceHints.workClockNanos() - workStart);
                                  jobDiagnostics[0] = computeSettings + "\n"
                                      + processor.diagnostics() + "\n" + hints.diagnostics() + " • " + gpuHints.status() + "\n" + pipeline.diagnostics();
                                }
                              },
                              jobControl,
                              (n, pts) -> {
                                long done = pts - options.startUs;
                                double fraction =
                                    interval > 0 ? Math.min(1, (double) done / interval) : 0;
                                long now = SystemClock.elapsedRealtime();
                                long elapsed = now - start;
                                long frameElapsed = now - lastFrameAt[0];
                                lastFrameAt[0] = now;
                                jobDiagnostics[0] = computeSettings + "\n"
                                    + processor.diagnostics() + "\n" + hints.diagnostics() + " • " + gpuHints.status() + "\n" + pipeline.diagnostics();
                                long eta =
                                    fraction > 0
                                        ? (long) (elapsed * (1 - fraction) / fraction) / 1000
                                        : -1;
                                status =
                                    String.format(
                                        java.util.Locale.ROOT,
                                        "%d frames • %.1f%% • elapsed %ds • ETA %s • %.2f fps average"
                                            + " • last frame %.3fs • inference work %.3fs\n%s",
                                        n,
                                        100 * fraction,
                                        elapsed / 1000,
                                        eta < 0 ? "…" : eta + "s",
                                        elapsed > 0 ? n * 1000.0 / elapsed : 0.0,
                                        frameElapsed / 1000.0,
                                        hints.lastWorkNanos() / 1000000000.0,
                                        jobDiagnostics[0]);
                                android.util.Log.i("VideoProcessing", status);
                                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                                    .notify(9, notification(status));
                              });
                    }
                    result = destination;
                    status = "Complete: " + destination.getName() + ". Save, play or share below.\n"
                        + status;
                  }
                } catch (CancellationException e) {
                  status = "Cancelled. Partial MP4 removed; inference and hint cleanup finished (best effort).\n" + jobDiagnostics[0] + "\n" + pipeline.diagnostics();
                } catch (Exception e) {
                  android.util.Log.e("VideoProcessing", "Video failed", e);
                  status = "Failed: " + e.getMessage() + "\n" + jobDiagnostics[0] + "\n" + pipeline.diagnostics();
                } finally {
                  android.util.Log.i("VideoProcessing", "Job ended; hint scope cleanup attempted. " + status);
                  // Admission and teardown execute on the same main-thread queue. Keep busy
                  // until every old-job side effect is finished, and never touch a new job's lock.
                  main.post(() -> finishJob(jobWake));
                }
              },
              "video-pipeline");
      worker.start();
    } catch (RuntimeException e) {
      status = "Failed to start video worker: " + e.getMessage();
      android.util.Log.e("VideoProcessing", status, e);
      finishJob(wake);
    }
    return START_NOT_STICKY;
  }

  /** Main lifecycle queue only; admission stays closed until all old-job teardown finishes. */
  private void finishJob(PowerManager.WakeLock jobWake) {
    jobs.finish(() -> {
      try {
        try {
          if (jobWake != null && jobWake.isHeld()) jobWake.release();
        } finally {
          try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(9, notification(status));
          } finally {
            try { stopForeground(false); }
            finally { stopSelfResult(latestStartId); }
          }
        }
      } finally {
        control = null;
        wake = null;
        worker = null;
        running = false;
      }
    });
  }

  @Override
  public void onDestroy() {
    if (control != null) control.cancel();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
