package com.tumuyan.ncnn.realsr;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.*;

public final class VideoActivity extends AppCompatActivity {
  private Uri source;
  private TextView metadata, status;
  private Spinner model, scale, codec, noise, backend, cpuThreads, workTarget, decoderChoice;
  private TextView performanceInfo;
  private EditText tile, bitrate, timestamp;
  private CheckBox audio, gpuRequest;
  private boolean gpuRequestSupported;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView modelInfo;
  private int[] selectedScales = {2,3};
  private int[] selectedNoises = {-1,0,3};
  private String[] modelLabels() {
    String[] labels=new String[VideoUpscalerCatalog.OPTIONS.size()];
    for(int i=0;i<labels.length;i++) labels[i]=VideoUpscalerCatalog.OPTIONS.get(i).label;
    return labels;
  }
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          status.setText(VideoProcessingService.status);
          boolean busy = VideoProcessingService.running;
          findViewById(R.id.video_start).setEnabled(!busy && source != null);
          findViewById(R.id.video_preview).setEnabled(!busy && source != null);
          findViewById(R.id.video_cancel).setEnabled(busy);
          boolean ready = !busy && VideoProcessingService.result != null;
          findViewById(R.id.video_save).setEnabled(ready);
          findViewById(R.id.video_share).setEnabled(ready);
          findViewById(R.id.video_play).setEnabled(ready);
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_video);
    setTitle("RealSR Video");
    metadata = findViewById(R.id.video_metadata);
    status = findViewById(R.id.video_status);
    model = findViewById(R.id.video_model);
    scale = findViewById(R.id.video_scale);
    codec = findViewById(R.id.video_codec);
    noise = findViewById(R.id.video_noise);
    tile = findViewById(R.id.video_tile);
    bitrate = findViewById(R.id.video_bitrate);
    timestamp = findViewById(R.id.video_timestamp);
    audio = findViewById(R.id.video_audio);
    decoderChoice = findViewById(R.id.video_decoder);
    populate(decoderChoice, new String[] {"Android MediaCodec (hardware default)", "FFmpeg (software decode; hardware encode)"});
    decoderChoice.setSelection(VideoDecodePolicy.FFMPEG.equals(loadDecoder(getSharedPreferences(VideoDecodePolicy.PREFS, MODE_PRIVATE)).backend) ? 1 : 0);
    decoderChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
        saveDecoder(getSharedPreferences(VideoDecodePolicy.PREFS, MODE_PRIVATE), selectedDecoder());
      }
      public void onNothingSelected(AdapterView<?> parent) { }
    });
    configurePerformance();
    modelInfo = findViewById(R.id.video_model_info);
    populate(model, modelLabels());
    populate(scale, new String[] {"2x", "3x"});
    model.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
        applyModelChoice();
      }
      public void onNothingSelected(AdapterView<?> parent) { }
    });
    String savedModel=getSharedPreferences("video_upscaler",MODE_PRIVATE).getString("model","models-pro");
    for(int i=0;i<VideoUpscalerCatalog.OPTIONS.size();i++)
      if(VideoUpscalerCatalog.OPTIONS.get(i).model.equals(savedModel)) model.setSelection(i);
    populate(codec, new String[] {"AVC / H.264 hardware", "HEVC / H.265 hardware"});
    populate(
        noise,
        new String[] {
          "Conservative (-1)", "No denoise (0)", "Denoise 1", "Denoise 2", "Denoise 3"
        });
    tile.setText(
        Integer.toString(getSharedPreferences("config", MODE_PRIVATE).getInt("tileSize", 0)));
    applyModelChoice();
    scale.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onItemSelected(AdapterView<?> parent, android.view.View view,int position,long id) { applyNoiseChoice(); }
      public void onNothingSelected(AdapterView<?> parent) { }
    });
    findViewById(R.id.nav_image)
        .setOnClickListener(
            v -> {
              startActivity(
                  new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
              finish();
            });
    findViewById(R.id.nav_batch)
        .setOnClickListener(v -> startActivity(new Intent(this, DirectoryProcessActivity.class)));
    findViewById(R.id.video_battery_settings).setOnClickListener(v -> {
      try {
        startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
      } catch (ActivityNotFoundException e) {
        try {
          startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException unavailable) {
          Toast.makeText(this, "Battery settings unavailable", Toast.LENGTH_LONG).show();
        }
      }
    });
    findViewById(R.id.video_open)
        .setOnClickListener(
            v ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("video/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                    101));
    findViewById(R.id.video_start).setOnClickListener(v -> start(false));
    findViewById(R.id.video_preview).setOnClickListener(v -> start(true));
    findViewById(R.id.video_cancel)
        .setOnClickListener(
            v ->
                startService(
                    new Intent(this, VideoProcessingService.class)
                        .setAction(VideoProcessingService.CANCEL)));
    findViewById(R.id.video_save)
        .setOnClickListener(
            v -> {
              if (VideoProcessingService.result != null)
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("video/mp4")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE, "RealSR-video.mp4"),
                    102);
            });
    findViewById(R.id.video_play).setOnClickListener(v -> export(false));
    findViewById(R.id.video_share).setOnClickListener(v -> export(true));
    if (saved != null && saved.getString("source") != null)
      select(Uri.parse(saved.getString("source")));
    else if (Intent.ACTION_SEND.equals(getIntent().getAction()))
      select(getIntent().getParcelableExtra(Intent.EXTRA_STREAM));
  }

  private void applyNoiseChoice() {
    if(model.getSelectedItemPosition()<0 || scale.getSelectedItemPosition()<0) return;
    VideoUpscalerCatalog.Entry entry=VideoUpscalerCatalog.OPTIONS.get(model.getSelectedItemPosition());
    int previous=selectedNoises[Math.max(0,Math.min(noise.getSelectedItemPosition(),selectedNoises.length-1))];
    selectedNoises=entry.noises(selectedScales[scale.getSelectedItemPosition()]);
    String[] labels=new String[selectedNoises.length];
    int retained=0;
    for(int i=0;i<labels.length;i++) {
      int n=selectedNoises[i];
      labels[i]=entry.noiseLabel(n);
      if(n==previous) retained=i;
    }
    populate(noise,labels);
    noise.setSelection(retained);
    noise.setEnabled(selectedNoises.length>1);
  }

  private void applyModelChoice() {
    if(model == null || model.getSelectedItemPosition()<0 || modelInfo == null) return;
    VideoUpscalerCatalog.Entry entry=VideoUpscalerCatalog.OPTIONS.get(model.getSelectedItemPosition());
    int previous=selectedScales[Math.max(0,Math.min(scale.getSelectedItemPosition(),selectedScales.length-1))];
    selectedScales=entry.scales();
    String[] labels=new String[selectedScales.length];
    int retained=0;
    for(int i=0;i<labels.length;i++) {
      labels[i]=selectedScales[i]+"x";
      if(selectedScales[i]==previous) retained=i;
    }
    populate(scale,labels);
    scale.setSelection(retained);
    modelInfo.setText(entry.description);
    applyNoiseChoice();
    tile.setEnabled(!entry.shader);
    if(entry.shader) backend.setSelection(0);
    backend.setEnabled(!entry.shader);
    getSharedPreferences("video_upscaler",MODE_PRIVATE).edit().putString("model",entry.model).apply();
  }

  static VideoDecodePolicy loadDecoder(SharedPreferences prefs) {
    return new VideoDecodePolicy(prefs.getString("backend", VideoDecodePolicy.MEDIACODEC));
  }
  static void saveDecoder(SharedPreferences prefs, VideoDecodePolicy policy) {
    prefs.edit().putString("backend", policy.backend).apply();
  }
  static Intent putDecoder(Intent intent, VideoDecodePolicy policy) {
    return intent.putExtra(VideoDecodePolicy.EXTRA, policy.backend);
  }
  private VideoDecodePolicy selectedDecoder() {
    return new VideoDecodePolicy(VideoDecodePolicy.CHOICES.get(decoderChoice.getSelectedItemPosition()));
  }
  private void configurePerformance() {
    backend = findViewById(R.id.video_backend);
    cpuThreads = findViewById(R.id.video_cpu_threads);
    workTarget = findViewById(R.id.video_work_target);
    performanceInfo = findViewById(R.id.video_performance_info);
    populate(backend, new String[] {"GPU (Vulkan neural / OpenGL shader)", "CPU (neural models only)"});
    populate(cpuThreads, new String[] {
        "Auto (" + new VideoPerformancePolicy(false, 0, 0, 0)
            .threads(Runtime.getRuntime().availableProcessors()) + " threads; cap 8)",
        "1 thread", "2 threads", "4 threads", "6 threads", "8 threads"});
    populate(workTarget, new String[] {"Auto — previous measured inference", "250 ms per inference (request)",
        "1000 ms per inference (request)", "5000 ms per inference (request)"});
    VideoPerformancePolicy p = VideoPerformanceHints.load(getSharedPreferences(VideoPerformanceHints.PREFS, MODE_PRIVATE));
    gpuRequest = findViewById(R.id.video_gpu_request);
    gpuRequestSupported = Build.VERSION.SDK_INT >= 36;
    gpuRequest.setChecked(getSharedPreferences(VideoPerformanceHints.PREFS, MODE_PRIVATE)
        .getBoolean(VideoGpuHintPolicy.KEY, false));
    gpuRequest.setOnCheckedChangeListener((button, checked) -> getSharedPreferences(VideoPerformanceHints.PREFS, MODE_PRIVATE)
        .edit().putBoolean(VideoGpuHintPolicy.KEY, checked).apply());
    backend.setSelection(p.cpu ? 1 : 0);
    cpuThreads.setSelection(indexOf(VideoPerformancePolicy.THREAD_CHOICES, p.requestedThreads));
    workTarget.setSelection(indexOf(VideoPerformancePolicy.TARGET_CHOICES_MS, p.targetMs));
    android.widget.AdapterView.OnItemSelectedListener changed = new android.widget.AdapterView.OnItemSelectedListener() {
      public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
        VideoPerformanceHints.save(getSharedPreferences(VideoPerformanceHints.PREFS, MODE_PRIVATE), selectedPerformance());
        applyWindowPerformance();
      }
      public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    };
    backend.setOnItemSelectedListener(changed);
    cpuThreads.setOnItemSelectedListener(changed);
    workTarget.setOnItemSelectedListener(changed);
    applyWindowPerformance();
  }

  private static int indexOf(int[] choices, int value) {
    for (int i = 0; i < choices.length; i++) if (choices[i] == value) return i;
    return 0;
  }

  private VideoPerformancePolicy selectedPerformance() {
    return new VideoPerformancePolicy(backend.getSelectedItemPosition() == 1,
        VideoPerformancePolicy.THREAD_CHOICES[Math.max(0, cpuThreads.getSelectedItemPosition())],
        VideoPerformancePolicy.HIGH,
        VideoPerformancePolicy.TARGET_CHOICES_MS[Math.max(0, workTarget.getSelectedItemPosition())]);
  }

  private void applyWindowPerformance() {
    if (backend == null) return;
    if (gpuRequest != null) {
      boolean cpu = backend.getSelectedItemPosition() == 1;
      gpuRequest.setEnabled(gpuRequestSupported && !cpu);
      TextView note = findViewById(R.id.video_gpu_request_note);
      note.setText(!gpuRequestSupported ? "Unavailable: requires Android 16 or newer."
          : cpu ? "GPU request disabled for CPU inference; saved choice is preserved." : "Best effort; cannot lock GPU clocks or bypass thermal limits.");
    }
    performanceInfo.setText("CPU thread choice configures NCNN neural inference and FFmpeg software decode; NCNN threads do not apply to shader enhancement. "
        + "Job settings apply next start. Hints/priority cannot force clocks or bypass thermal limits.");
  }

  private void populate(Spinner spinner, String[] values) {
    ArrayAdapter<String> a =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
    spinner.setAdapter(a);
  }

  private void select(Uri uri) {
    if (uri == null) return;
    source = uri;
    metadata.setText("Reading metadata…");
    new Thread(
            () -> {
              try {
                String s = new VideoPipeline(this).inspect(uri).toString();
                runOnUiThread(() -> metadata.setText(uri.getLastPathSegment() + "\n" + s));
              } catch (Exception e) {
                runOnUiThread(
                    () -> {
                      source = null;
                      metadata.setText("Cannot read video: " + e.getMessage());
                    });
              }
            },
            "video-metadata")
        .start();
  }

  private void start(boolean preview) {
    if (source == null || VideoProcessingService.running) return;
    try {
      int index = model.getSelectedItemPosition();
      VideoUpscalerCatalog.Entry entry=VideoUpscalerCatalog.OPTIONS.get(index);
      int selectedScale=selectedScales[scale.getSelectedItemPosition()];
      int size = entry.shader ? 0 : Integer.parseInt(tile.getText().toString());
      int mbps = Integer.parseInt(bitrate.getText().toString());
      VideoPolicy.validateTile(size);
      if (mbps < 1 || mbps > 200)
        throw new IllegalArgumentException("Bitrate 1..200 Mbit/s required");
      long start =
          preview
              ? new java.math.BigDecimal(timestamp.getText().toString())
                  .multiply(new java.math.BigDecimal(1000000))
                  .longValueExact()
              : 0;
      if (start < 0) throw new IllegalArgumentException("Preview timestamp must be non-negative");
      VideoPerformancePolicy settings = selectedPerformance();
      entry.validate(selectedScale,settings.cpu);
      VideoPerformanceHints.save(getSharedPreferences(VideoPerformanceHints.PREFS, MODE_PRIVATE), settings);
      Intent job =
          new Intent(this, VideoProcessingService.class)
              .setData(source)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              .putExtra("engine", entry.engine)
              .putExtra("model", entry.model)
              .putExtra("scale", selectedScale)
              .putExtra("tile", size)
              .putExtra("noise", selectedNoises[noise.getSelectedItemPosition()])
              .putExtra("codec", codec.getSelectedItemPosition() == 0 ? "video/avc" : "video/hevc")
              .putExtra("bitrate", mbps * 1000000)
              .putExtra("audio", audio.isChecked())
              .putExtra("start", start)
              .putExtra("duration", preview ? 5000000L : Long.MAX_VALUE);
      saveDecoder(getSharedPreferences(VideoDecodePolicy.PREFS, MODE_PRIVATE), selectedDecoder());
      putDecoder(job, selectedDecoder());
      VideoPerformanceHints.putExtras(job, settings, Runtime.getRuntime().availableProcessors());
      job.putExtra(VideoGpuHintPolicy.KEY, gpuRequest.isEnabled() && gpuRequest.isChecked());
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(job);
      else startService(job);
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void export(boolean share) {
    File file = VideoProcessingService.result;
    if (file == null) return;
    try {
      Uri uri =
          FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", file);
      Intent intent =
          share
              ? new Intent(Intent.ACTION_SEND)
                  .setType("video/mp4")
                  .putExtra(Intent.EXTRA_STREAM, uri)
              : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4");
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri("video", uri));
      startActivity(Intent.createChooser(intent, share ? "Share video" : "Play video"));
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == 101) {
      try {
        getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException ignored) {
      }
      select(uri);
    } else if (request == 102) {
      File file = VideoProcessingService.result;
      if (file == null) return;
      new Thread(
              () -> {
                try (InputStream in = new FileInputStream(file);
                    OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                  if (out == null) throw new IOException("Cannot open destination");
                  byte[] b = new byte[65536];
                  int n;
                  while ((n = in.read(b)) >= 0) out.write(b, 0, n);
                  runOnUiThread(() -> Toast.makeText(this, "Saved MP4", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                  try {
                    android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri);
                  } catch (Exception ignored) {
                  }
                  runOnUiThread(
                      () ->
                          Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG)
                              .show());
                }
              },
              "video-save")
          .start();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (source != null) out.putString("source", source.toString());
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onResume() {
    super.onResume();
    applyWindowPerformance();
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    VideoPerformanceHints.save(getSharedPreferences(VideoPerformanceHints.PREFS, MODE_PRIVATE), selectedPerformance());
    applyWindowPerformance();
    super.onPause();
  }
}
