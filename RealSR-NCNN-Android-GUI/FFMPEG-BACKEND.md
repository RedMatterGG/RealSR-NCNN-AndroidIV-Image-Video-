# Video decoder comparison

The Video decoder selector is independent of Vulkan GPU / CPU neural inference.
Android MediaCodec is the default. FFmpeg performs software video decoding only:
both paths use the same persistent NCNN model, bitmap inference, EGL upload,
concurrent Android hardware Surface encoder (AVC/H.264 or HEVC/H.265), and MP4 muxer.
No PNG/frame dumps, forced CFR reconstruction, FFmpeg encoding, or zero-copy Vulkan.
The stored decoder and explicit `video_decoder_backend` intent value accept only
`mediacodec` and `ffmpeg`. Unknown values fail rather than silently switching.

Android MediaExtractor still inspects the source and remuxes AAC audio. FFmpeg
selection does not make additional containers/formats importable. Existing SDR,
HDR rejection, dimensions/crop, single video track and AAC passthrough restrictions
remain. MPEG-TS is explicitly rejected by the FFmpeg wrapper because Android and
FFmpeg use different transport timestamp origins; choose MediaCodec for TS. FFmpeg
must provide unrotated visible bitmaps and original source PTS;
rotation stays in the muxer hint. Preview discards keyframe preroll, keeps the
requested half-open interval and subtracts the requested start from video AND audio
PTS. Variable frame intervals are retained, not rebuilt from nominal FPS.

## Five-second phone comparison

1. Choose the SAME SDR clip and preview timestamp; run a five-second preview with
   MediaCodec, then FFmpeg. Keep model, scale, tile, denoise, inference GPU/CPU,
   intraop threads, performance mode/work target, codec, output bitrate and audio
   settings identical. FFmpeg decoder threads use the selected bounded thread count.
2. Begin each trial at comparable temperature/thermal status and battery state;
   cool between runs and reverse trial order. Repeat rather than comparing one cold
   hardware run to one throttled software run. Record device/Android/app versions.
3. Record elapsed time, average FPS, last inference work, cumulative decoded-frame
   acquisition and thermal status, plus actual decoder/encoder diagnostic names.
   Acquisition includes decode polling/packet reads and MediaCodec bitmap readback,
   not an isolated codec CPU/GPU profiler measurement. There is no fabricated GPU time.
4. Play both outputs: check VFR motion, audio sync, portrait orientation, crop/color,
   preview start/end, and cancellation (partial output deleted; next job admitted
   only after cleanup). Verify no frame dump directory appears.

Software decoding may be slower or warmer. If persistent NCNN inference dominates,
switching decoder cannot remove that bottleneck; no speed gain is promised.
Android <15 simply lacks the optional power-efficiency ADPF preference; GPU inference
is unaffected. Other API failures remain visible and neither accepted hints nor
priority requests prove a frequency increase.

## Recorded verification

- Pure Java javac/JUnit run: **24 host tests passed**, including 3 decoder-policy
  cases. Initial policy test compilation failed on the absent VideoDecodePolicy
  interface before production implementation (RED); the implemented policy passed.
- Initial targeted Gradle Java compile was blocked by the missing sibling wrapper.
  Once it appeared, `:app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac
  -x buildVideoNative` succeeded: **24 tests, zero failures/errors/skips**, and
  instrumentation Java compiled. No full build or APK publication was attempted.
- ADB reports no attached devices. Five new device tests compiled but are unexecuted: separate selector, prefs/explicit intent,
  neutral optional efficiency diagnostic, FFmpeg VFR/portrait/AAC preview, cancellation.

## Verification scope

Host tests exercise pure decoder policy only, never JNI. Device selection/prefs/intent
and real FFmpeg VFR/portrait/preview/audio/cancel tests require the sibling native
FFmpeg library and an ARM64 phone. Defined or compiled instrumentation is NOT an
executed device pass. The existing VideoGl constructor allocates its decoder-side
SurfaceTexture even for FFmpeg, but the FFmpeg branch never configures a MediaCodec
decoder, renders to that surface, or performs GL decoder readback. Removing that idle
allocation requires a separately authorized VideoGl change.
