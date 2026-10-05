# Video implementation research and scope

## Evidence, not a desktop performance benchmark

The existing Android [README](https://github.com/tumuyan/RealSR-NCNN-Android/blob/master/README.md) describes the GUI as a shell around ARM64 image CLI programs. Local `MainActivity` selects an image/preset, copies assets into private storage, executes the image CLI, previews PNG output, and saves/shares images. GIF support uses ImageMagick frame directories, not an ordinary video decoder. `CommandBuilder.buildArray()` can preserve arguments without shell parsing; the video bridge uses that API and `ProcessBuilder`, not user-supplied shell text.

The desktop source baseline is [Waifu2x-Extension-GUI/video.cpp](https://github.com/AaronFeng753/Waifu2x-Extension-GUI/blob/master/Waifu2x-Extension-QT/video.cpp), locally captured as `F:/videoimageupscaler/research/desktop-video.cpp`:

- lines 1010–1038: ffmpeg exports numbered PNGs, with `-r` based on probed FPS, then extracts audio separately;
- lines 487–503: audio extraction;
- lines 1253–1259: encode an image sequence with declared frame rate;
- lines 417–481: segment processing; lines 255–408: concatenate processed clips.

This establishes an actual source-level desktop baseline. It is **not** a PC runtime benchmark, nor evidence that its forced-FPS sequence path preserves arbitrary variable-frame-rate timestamps. The [v1.61.3 release](https://github.com/AaronFeng753/Waifu2x-Extension-GUI/releases/tag/v1.61.3) explicitly describes segment processing as a disk-space reduction feature.

## Android design

References: [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), [MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor), [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer), [SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture), [eglPresentationTimeANDROID](https://developer.android.com/reference/android/opengl/EGLExt#eglPresentationTimeANDROID(android.opengl.EGLDisplay,android.opengl.EGLSurface,long)), [VideoCapabilities](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities).

Implemented path:

1. SAF URI → MediaExtractor. Inspect tracks, dimensions, duration, nominal FPS, rotation, audio MIME; reject visibly unsupported profiles/formats.
2. MediaCodec decoder → SurfaceTexture external-OES texture on a private EGL context. Decode presentation frames, not MediaMetadataRetriever samples. Decoder crop transforms are applied by SurfaceTexture; inclusive crop dimensions allow coded padding such as 1088 → 1080.
3. Read one frame back to an 8-bit RGBA bitmap. Reuse `input.png` and `output.png` in one private frame workspace. Execute the real packaged NCNN CLI with an argument array, explicit model path, scale/tile/CPU/thread settings and bounded diagnostic tail.
4. Upload the upscaled bitmap into the hardware encoder's Surface with EGL. Set presentation time from decoder PTS in microseconds converted exactly to nanoseconds. Nominal frame rate config is only an encoder hint: no timestamp resampling or frame sampling.
5. Dedicated drain thread consumes encoded buffers continuously while frames are submitted, so encoder output backpressure does not accumulate a whole clip or deadlock the EGL producer. Configure muxer tracks before starting it. After video EOS, copy supported AAC audio packets from fresh extractors into the muxer.
6. Finalize a private `.partial.mp4`, then rename to the result only after successful EOS/audio/muxer completion and cancellation checks. SAF save, FileProvider share/play expose only completed results.

Preview seeks to the previous sync frame, decodes/skips earlier presentations, retains `[requestedStart, requestedStart + 5s)`, and subtracts the requested start from both audio and video PTS. This may leave a short initial gap until the first retained frame/audio packet. AAC is cut at packet boundaries; no sample-accurate audio trimming/re-encode or crossfade is implemented.

Rotation remains a muxer orientation hint; decoder auto-rotation is disabled so pixels are not rotated twice. Different player behavior still requires phone validation. No subtitle/metadata copying or frame interpolation is claimed.

## Packaged dependencies and reproducible release

Official [1.11.1 assets.zip](https://github.com/tumuyan/RealSR-NCNN-Android/releases/download/1.11.1/assets.zip) is unpacked under GUI `app/src/main/assets/realsr`. `F:/videoimageupscaler/setup.log` verifies each ELF is ELF64 / machine 183 (AArch64). The complete official model/binary package is retained so existing Image functionality has its expected assets; it makes APKs large. Assets are upstream 1.11.1 binaries, not locally rebuilt from current native sources. The assets directory is ignored by the upstream git rules; keep the setup archive/script for clean rebuilds.

Use the portable JDK21 in `F:/videoimageupscaler/toolchain`, not the installed Java26 or Android Studio JBR25. `F:/videoimageupscaler/build-release.py` generates a local signing key outside the repository and runs host tests, debug/release lint, debug/release assembly and instrumentation APK assembly. Signing is enabled through `REALSR_SIGNING_PROPERTIES`; credentials are not in the repository or reports. The native Mali/OpenCL manifest libraries are optional rather than install-blocking requirements on Qualcomm hardware.

## Explicit limits and unresolved validation

- Every frame starts a new CLI process and initializes its model. This is an expensive bridge, **not zero-copy Vulkan**, persistent NCNN inference, real-time video or a throughput promise. PNG read/write and GPU readback/upload add overhead.
- GUI video exposes packaged Real-CUGAN and RealSR/ESRGAN models, valid model/scale/noise combinations checked on start. It shares tile/CPU/thread preferences with Image settings. Arbitrary custom shell/image presets, MNN/Anime4K/SRMD/Waifu2x video integration and video-batch queues are not exposed. Existing image/batch workflows remain available.
- Only SDR 8-bit conversion is intended. PQ, HLG, BT.2020, Dolby Vision and tagged unsupported 10-bit profiles reject. Incorrectly tagged/untagged HDR cannot be guaranteed detectable; no HDR tone mapping or certified color-managed transform is claimed.
- Only AAC audio passthrough is supported, including multiple AAC tracks. Unsupported audio fails when preservation is checked. Unchecking explicitly produces silent output. Subtitles and other metadata are not preserved.
- Hardware AVC/HEVC Surface encoders are selected by advertised format/capability support. Driver configure/EGL failures remain explicit failures, not silent software fallback. Codec identity seam allowing software encoders exists only for instrumentation tests, never selected by GUI/service.
- Output dimensions must be even, at most 8192 per axis and 16,777,216 pixels total. Actual codec/GL/device memory limits can be stricter; NCNN tile/model memory adds independent pressure. Dynamic visible dimensions, encrypted media, negative source/preroll PTS and non-increasing presentation timestamps reject. No resumable jobs after process death.
- Foreground service, notification cancellation, a bounded-duration wakelock, decoder/encoder EOS timeouts, frame-process timeout and cleanup are implemented. The input clip and final output can still be large; bounded frame scratch does not imply bounded final MP4 size. Results live in private cache until saved; do not rely on cache as archival storage.
- **No device runtime verification was possible.** `adb devices` remained empty. Headless emulator attempt failed because no hypervisor driver is installed; `-accel off` attempt exited 139. `connectedDebugAndroidTest` failed with `No connected devices!`. Instrumentation sources/APK compile, but codec/EGL/NCNN/audio/rotation/preview/cancel assertions have not executed. Neither Poco F6 nor any emulator transcode is claimed verified.
- Red/green logs establish host policy/resource tests and missing-feature compile failures. Device-dependent vertical slices could reach compile-green only, not runtime-green; strict end-to-end TDD acceptance is therefore not fully satisfied. Follow the phone checklist before trusting long jobs.
