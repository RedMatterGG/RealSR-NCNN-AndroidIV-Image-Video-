# Genuine persistent lightweight models

## Exact choices

| JNI alias | Actual asset directory under `assets/realsr/` | Native scale | Noise | Stem |
|---|---|---|---|---|
| `fsrcnn-ncnn` | `models-FSRCNN-small` | 2 only | 0 only | `x2` |
| `waifu2x-ncnn` | `models-upconv_7_anime_style_art_rgb` | 2 only | -1,0,1,2,3 | -1: `scale2.0x_model`; otherwise `noiseN_scale2.0x_model` |
| `waifu2x-ncnn` | `models-upconv_7_photo` | 2 only | -1,0,1,2,3 | as above |
| `realsr-ncnn` | `models-RealeSR-general-v3` | 4 | 0 | `x4` |

Waifu noise -1 is upscale-only; 0 is genuine official noise0 weights (not a no-denoise alias).
The General directory's historical spelling **RealeSR** is intentional and verified against
the upstream 1.11.1 assets.zip. Its earlier checkpoint/conversion ancestry is not asserted.
Per-model PROVENANCE.json includes source, exact weight hashes and preprocessing; model
licenses are adjacent. New models do not need image CLI binaries and are never launched
as per-frame processes. They operate on packed RGB from Bitmap, returning opaque video
RGB. JNI registry locking, deterministic close and process-lifetime Vulkan ownership remain.

## FSRCNN-small compilation

Pinned Saafke/FSRCNN_Tensorflow commit `6a4812c4ef1c4f5947d79beafa32a05a6eb4a94d`.
This actual pretrained model is d=32,s=5,m=1 and uses subpixel convolution, **not** the
paper's transposed convolution. `convert_fsrcnn.py` parses TensorFlow GraphDef tensors,
transposes HWIO to OIHW, compiles real convolution/PReLU/PixelShuffle NCNN layers and
replicates the final shared Y bias before shuffle. No training or random weights.

RGB-to-8-bit-YCrCb uses OpenCV's fixed-point coefficients. Rounded Y is divided by255
before inference. Predicted Y*255 is clipped and truncated as upstream. Cr/Cb are resized
bicubically using the OpenCV A=-0.75 kernel and reconstructed with fixed-point YCrCb-to-RGB.
The native floating cubic sampler can differ from OpenCV's architecture-dependent fixed-point
resize rounding by a byte; a tested random native production fixture differs at most1 RGB
byte from TensorFlow + OpenCV. Inference uses FP32, including Vulkan, for this tiny model.
NCNN automatically uploads/downloads host Mats for GPU extractor work; this is not zero-copy.
The persistent Net's actual input/output names are read from the loaded graph. Tile context
radius3 preserves real zero-padding at frame boundaries and avoids seams.

## Waifu2x private source

Official nihui/waifu2x-ncnn-vulkan tag20250915, commit
`a86cfb043e6482b5f08778a114df9b5faf6e73b7`. `import_waifu2x.py` copies the official
CPU engine plus original four GPU kernels into native-video/engines/Waifu2x, generates
headers and packages the official pretrained upconv7 release models unchanged.
Private changes: float clamp before unsigned GPU conversion, checked CPU workspace/ROI/border allocations with an injectable workspace allocator, checked model/graph/shader/pipeline/extractor/submission results,
partial-init-safe destructor, RAII GPU allocators, always-RGB convention, and native2x
upconv-only preflight. The CLI source directories are untouched. Original normalized RGB,
replicate border padding7, native deconvolution, crop and rounded RGB output are retained.

## Reproduction and verified boundaries

Private toolchain only: `F:/videoimageupscaler/toolchain/fsrcnn-convert` (Python3.11).
See `conversion-requirements.lock` for the actual resolved Python dependency versions.
`python native-video/build.py` builds real API24 ARM64 JNI using NDKr28c and NCNN20250916,
then host pixel/color executables. Windows production-engine host harness:

```
cmake -S native-video -B F:/videoimageupscaler/toolchain/build-ncnn-lightweight-host -G "Visual Studio 18 2026" -A x64 -DVIDEO_TEST_NCNN=ON -Dncnn_DIR=F:/videoimageupscaler/toolchain/ncnn-20250916-windows-vs2022/x64/lib/cmake/ncnn
cmake --build F:/videoimageupscaler/toolchain/build-ncnn-lightweight-host --config Release
F:/videoimageupscaler/toolchain/fsrcnn-convert/Scripts/python.exe native-video/test_persistent_native.py
```

Observed RED->GREEN: unsupported FSRCNN alias; missing genuine compiled graph; missing
native color pipeline; missing native dispatch; unsupported Waifu alias/dispatch/model family.
Host tests load real models and run 3 repeat frames, compare bounded/full tiles and check
missing-load destruction. All10 official Waifu pairs pass CPU tests; FSRCNN, both Waifu
families and General pass actual host RTX4060 Vulkan tests. General passes through the
existing production RealSR processor. These are real host execution results, **not Android
phone tests**. Added NcnnNativeTest CPU/Vulkan repeat cases for all new families and noise
weights plus corrupt models compile with Gradle; adb has no connected device, so Android
instrumentation remains UNEXECUTED. No APK delivery or performance speed claim here.
