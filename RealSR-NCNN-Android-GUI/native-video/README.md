# Persistent video JNI engine

Image CLI assets/targets remain unchanged. Video creates one RealCUGAN or RealSR object and loads the selected `.param/.bin` once per processor. Bitmap RGBA rows are converted directly to packed RGB; `ncnn::Mat(w,h,bytes,3,3)` is intentional: these engines expect byte pixels with `elempack=3`, not a planar float `from_pixels` Mat. There is no frame PNG or subprocess.

## Reproduce on this Windows workspace

```
python native-video/bootstrap.py
python native-video/build.py
python F:/videoimageupscaler/build-release.py
```

Dependencies live only under `F:/videoimageupscaler/toolchain`: NDK r28c, CMake 3.31.6, official static NCNN 20250916 Android Vulkan (OpenMP enabled), with archive SHA256 checks in bootstrap.py. Ninja and host CMake/MSVC are taken from the installed Visual Studio tools. Gradle `preBuild` invokes the native recipe, tracks its source inputs and packages the genuine generated `app/src/main/jniLibs/arm64-v8a/libncnn_video.so`. `REALSR_PYTHON` optionally selects Python. No SDK/global configuration is changed. The existing release helper uses portable JDK21 and external signing properties.

The copied `engines/` sources/shaders originate from the repository CLI sources; only this video copy is hardened: checked load/input/extract/submit/pipeline results, safe partial-init destruction, missing 4x pipeline deletion, RAII Vulkan allocator reclamation. Upstream image engines are untouched.

## Lifetime and behavior

Native monotonically allocated handle IDs index a mutex-protected shared-owner registry; untrusted/stale IDs never become pointers. Per-engine locks serialize inference/diagnostics/destruction. Java `synchronized` methods also serialize close; cancelling an in-flight frame waits for that frame to return, discards its result, then deterministically closes the model. NCNN does not expose an interruptible extractor: cancellation is **not instantaneous within a native tile/frame**. Explicit CPU uses `gpuid=-1`; GPU requests fail if Vulkan/device init fails, never fall back silently. One lazy Vulkan instance is process-lifetime; the model is job-lifetime. Tile 0 currently selects bounded 128px tiles, not free-memory auto sizing. TTA disabled, CUGAN syncgap=3 (nose=0), padding matches CLI. The middle `load:process:save` value sets the constructor's actual `net.opt.num_threads`, not multiple GPU models/workers.

`diagnostics()` reports NCNN Vulkan GPU device name/index/vendor/device/driver/API (or CPU/OpenMP), selected intra-op threads, one model load, successful frame count, initialization and last-frame wall milliseconds (including Bitmap bridge work). Alpha must be opaque; software ARGB_8888 only. Video decoder/GL frames satisfy this; transparent inputs are rejected rather than silently premultiplied into incorrect colors. Output limited to 8192px per axis/16MP as in pipeline preflight.

## Verification boundary

Host Java validation tests check thread selection, invalid counts, engine/model naming. CTest executes native packed-pixel RGB, row-stride, alpha and bounds tests with assertions enabled even in Release. ARM64 compilation/linking is real, using official NCNN Vulkan/OpenMP archives. The resulting JNI library uses static libc++/OpenMP and 16KB PT_LOAD alignment. APK must additionally pass `zipalign -c -P 16 4` and signature verification. Existing image CLI asset ELFs are not covered by JNI page-alignment claims.

`NcnnNativeTest` adds six instrumentation tests: actual CPU/Vulkan repeat-frame model reuse for both engines, malformed model load, cancellation/close. They require an ARM64 phone and real models, and Vulkan cases deliberately fail on an unusable requested backend. They are not host mocks and have not been executed without a device. Device Vulkan correctness/throughput/thermals remain unverified until those tests and a real clip run on the Poco F6.
