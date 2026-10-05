# Fast Anime shader: Anime4K v1-style (non-neural)

Port of bloc97's original `Anime4K_Adaptive_v0.9.glsl`, MIT, copyright 2019 bloc97.
Pinned commit: 867df9ef83e296852bdaa0d046bd50ce25e56c2b (tag v0.9, also v1.0-RC).
Source: https://raw.githubusercontent.com/bloc97/Anime4K/867df9ef83e296852bdaa0d046bd50ce25e56c2b/glsl/Anime4K_Adaptive_v0.9.glsl
Upstream source SHA256: b0c1ed4bb6133bac54c92edd51345c5a400a1437fc0c2947c6d0c501e4162327
Original shader and full upstream MIT license are packaged in this directory.

This is NOT Anime4K v4, not a trained neural restoration model, and not an exact
mpv pipeline: it replaces the player prescaler with GLES bilinear enlargement,
then retains v0.9 eight-direction luminance-based line thinning, Sobel gradient,
and gradient-directed refinement. Luma computation is fused into enlargement
and gradient passes. Four fullscreen GPU passes, one iteration, RGBA8 targets;
8-bit intermediate precision differs from mpv float targets. Thin strength =
scale/6 (capped 1), refine strength = scale/2 (capped 1), as upstream.
Only 2x and 4x are exposed. Best suited to SDR line-art anime; it can remove
small detail or over-thin lines. No denoise model or invented missing details.

Android port modifications: mpv hook symbols become GLES ES 2.0 uniforms/main,
float literals for GLSL ES, clamp-to-edge normalized sampling, original alpha
is bilinearly sampled separately in final pass rather than overwritten by luma.
Upload is straight RGBA via Bitmap.getPixels (unpremultiplied); no GLUtils
premultiplied bitmap upload. Logical top row is texture v=0 and readback row=0,
so Bitmap row ordering is retained without a double flip. Output setPixels
performs Android's normal premultiplication. Fully transparent RGB is inherently
not preserved by Android Bitmap, but alpha is not replaced by gradient/luma.

Requires hardware GLES2+ with fragment highp support, RGBA8 FBO completeness,
and adequate GL_MAX_TEXTURE_SIZE; no half-float extensions/JNI/new models.
Software renderers are rejected, not silently used as GPU. Max 8,388,608 output
pixels and 8192 per axis, also limited by device texture size. Three reusable
output textures, one source texture, one FBO, upload/readback/ARGB buffers per
job; input dimensions cannot change mid-job. Output Bitmap belongs to caller.
Programs initialize once at first inference, not every frame. CPU performs
only upload/readback packing, not reconstruction. Bitmap readback is not zero-copy.
EGL caller display/context/draw/read restored on inference, errors and close.
Borrowed display is never terminated; no eglReleaseThread on encoder's thread.
Cancellation checked between passes and after blocking readback; it cannot
interrupt a GLES driver call. GL/EGL errors close the job; next job can recreate.

Device instrumentation is required for shader compilation, pixel/color/alpha,
EGL restoration, line effect, cancellation and repeat-job correctness. No
Poco F6 throughput, thermals or end-to-end video performance is claimed.
