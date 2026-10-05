package com.tumuyan.ncnn.realsr;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** GPU-only non-neural Anime4K v0.9/v1-style fast port; see packaged anime4k/NOTICE.md.
 * One processor per job. Caller owns returned Bitmaps. EGL state belongs to the caller:
 * we never share GL objects, terminate a borrowed display or release the encoder thread.
 */
public final class Anime4kFrameProcessor implements VideoPipeline.Processor, AutoCloseable {
  private final int scale;
  private final String vertexSource;
  private final String[] fragmentSources = new String[4];
  private final Program[] programs = new Program[4];
  private final int[] textures = new int[4]; // source, scaled/result, thin, gradient
  private final FloatBuffer vertices = ByteBuffer.allocateDirect(32)
      .order(ByteOrder.nativeOrder()).asFloatBuffer();
  private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
  private EGLContext context = EGL14.EGL_NO_CONTEXT;
  private EGLSurface surface = EGL14.EGL_NO_SURFACE;
  private boolean ownsDisplay, closed;
  private int framebuffer, width, height, outputWidth, outputHeight, maxTextureSize;
  private ByteBuffer upload, readback;
  private int[] argb;
  private long frames, elapsedNanos;
  private String renderer = "pending first frame", version = "pending";

  /** Reads packaged shaders now; GPU setup is lazy once per job on the pipeline worker. */
  public Anime4kFrameProcessor(Context context, int scale) throws IOException {
    if (!ShaderUpscalePolicy.supportsScale(scale))
      throw new IllegalArgumentException("Anime4K shader supports only 2x and 4x");
    this.scale = scale;
    vertexSource = asset(context, "quad.vert");
    String[] names = {"scale.frag", "thin.frag", "gradient.frag", "refine.frag"};
    for (int i = 0; i < names.length; i++) fragmentSources[i] = asset(context, names[i]);
    vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
  }

  private static String asset(Context context, String name) throws IOException {
    try (InputStream in = context.getAssets().open("anime4k/" + name)) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int count;
      while ((count = in.read(chunk)) != -1) out.write(chunk, 0, count);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  @Override public synchronized Bitmap upscale(Bitmap input, VideoPolicy.Control control)
      throws Exception {
    SavedEgl previous = new SavedEgl();
    Bitmap result = null;
    Throwable failure = null;
    long started = System.nanoTime();
    try {
      control.check();
      if (closed) throw new IllegalStateException("Anime4K shader job is closed");
      if (input == null || input.isRecycled() || input.getConfig() != Bitmap.Config.ARGB_8888)
        throw new IllegalArgumentException("Anime4K requires a live SDR ARGB_8888 software Bitmap");
      // Validate before EGL allocation and before multiplying/allocating frame arrays.
      ShaderUpscalePolicy.validate(input.getWidth(), input.getHeight(), scale, 8192);
      if (context.equals(EGL14.EGL_NO_CONTEXT)) initialize(previous);
      makeCurrent();
      ShaderUpscalePolicy.validate(input.getWidth(), input.getHeight(), scale, maxTextureSize);
      if (width == 0) allocate(input.getWidth(), input.getHeight());
      else if (width != input.getWidth() || height != input.getHeight())
        throw new IllegalArgumentException("Anime4K shader does not accept dynamic frame dimensions");
      input.getPixels(argb, 0, width, 0, 0, width, height);
      upload.clear();
      for (int i = 0; i < width * height; i++) {
        int c = argb[i]; // Android getPixels is straight ARGB, not premultiplied.
        upload.put((byte)(c >> 16)).put((byte)(c >> 8)).put((byte)c).put((byte)(c >>> 24));
      }
      upload.flip();
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
      GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
          GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, upload);
      checkGl("upload");
      control.check();
      draw(programs[0], textures[0], 0, textures[1], 0f);
      control.check();
      draw(programs[1], textures[1], 0, textures[2], Math.min(scale / 6f, 1f));
      control.check();
      draw(programs[2], textures[2], 0, textures[3], 0f);
      control.check();
      // Scaled texture is no longer an input: reuse it for final reconstruction.
      draw(programs[3], textures[2], textures[3], textures[1], Math.min(scale / 2f, 1f));
      control.check();
      readback.clear();
      GLES20.glReadPixels(0, 0, outputWidth, outputHeight, GLES20.GL_RGBA,
          GLES20.GL_UNSIGNED_BYTE, readback);
      checkGl("readback");
      control.check();
      // Row 0 was uploaded as v=0, so GL readback row 0 is Bitmap top row. No flip.
      for (int i = 0; i < argb.length; i++) {
        int k = i * 4;
        argb[i] = ((readback.get(k+3)&255)<<24) | ((readback.get(k)&255)<<16)
            | ((readback.get(k+1)&255)<<8) | (readback.get(k+2)&255);
      }
      result = Bitmap.createBitmap(argb, outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
      control.check();
      frames++;
      elapsedNanos += System.nanoTime() - started;
    } catch (Exception | Error ex) {
      failure = ex;
      if (result != null) { result.recycle(); result = null; }
      closed = true;
      try { release(); } catch (RuntimeException cleanup) { ex.addSuppressed(cleanup); }
      throw ex;
    } finally {
      try { previous.restore(); }
      catch (RuntimeException restore) {
        if (result != null) result.recycle();
        if (failure != null) failure.addSuppressed(restore);
        else {
          closed = true;
          try { release(); } catch (RuntimeException cleanup) { restore.addSuppressed(cleanup); }
          throw restore;
        }
      }
    }
    return result;
  }

  private void initialize(SavedEgl previous) {
    if (!previous.display.equals(EGL14.EGL_NO_DISPLAY)) display = previous.display;
    else {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      if (display.equals(EGL14.EGL_NO_DISPLAY)) throw egl("get display");
      int[] versions = new int[2];
      if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) throw egl("initialize");
      ownsDisplay = true;
    }
    EGLConfig[] configs = new EGLConfig[1];
    int[] count = new int[1];
    int[] attrs = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
        EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE};
    if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
      throw egl("choose RGBA8 GLES2 config");
    context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
        new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
    if (context.equals(EGL14.EGL_NO_CONTEXT)) throw egl("create context");
    surface = EGL14.eglCreatePbufferSurface(display, configs[0],
        new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
    if (surface.equals(EGL14.EGL_NO_SURFACE)) throw egl("create pbuffer");
    makeCurrent();
    renderer = GLES20.glGetString(GLES20.GL_RENDERER);
    version = GLES20.glGetString(GLES20.GL_VERSION);
    String lower = renderer == null ? "" : renderer.toLowerCase(Locale.ROOT);
    if (lower.isEmpty() || lower.contains("swiftshader") || lower.contains("llvmpipe")
        || lower.contains("softpipe") || lower.contains("lavapipe") || lower.contains("software"))
      throw new IllegalStateException("Anime4K GPU-only mode rejects software renderer: " + renderer);
    int[] range = new int[2], precision = new int[1];
    GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT,
        range, 0, precision, 0);
    if (precision[0] == 0) throw new IllegalStateException("GPU lacks GLES fragment highp support");
    GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, precision, 0);
    maxTextureSize = precision[0];
    GLES20.glDisable(GLES20.GL_BLEND);
    GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    GLES20.glDisable(GLES20.GL_DITHER);
    GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT,1);
    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);
    for (int i = 0; i < programs.length; i++) programs[i] = new Program(vertexSource, fragmentSources[i]);
    GLES20.glGenTextures(textures.length, textures, 0);
    int[] f = new int[1]; GLES20.glGenFramebuffers(1, f, 0); framebuffer = f[0];
    checkGl("initialize");
  }

  private void allocate(int w, int h) {
    width = w; height = h;
    outputWidth = ShaderUpscalePolicy.outputDimension(w, scale);
    outputHeight = ShaderUpscalePolicy.outputDimension(h, scale);
    for (int i = 0; i < textures.length; i++) {
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[i]);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,
          i == 0 ? w : outputWidth,i == 0 ? h : outputHeight,0,
          GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
      checkGl("texture allocation");
    }
    int count = Math.multiplyExact(outputWidth,outputHeight);
    argb = new int[count];
    upload = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(w,h),4));
    readback = ByteBuffer.allocateDirect(Math.multiplyExact(count,4));
  }

  private void draw(Program p, int source, int auxiliary, int target, float strength) {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
        GLES20.GL_TEXTURE_2D,target,0);
    if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE)
      throw new IllegalStateException("Anime4K RGBA8 framebuffer incomplete");
    GLES20.glViewport(0,0,outputWidth,outputHeight);
    GLES20.glUseProgram(p.id);
    vertices.position(0);
    GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,0,vertices);
    GLES20.glEnableVertexAttribArray(0);
    bind(0,source,p.source); bind(1,auxiliary,p.aux); bind(2,textures[0],p.original);
    GLES20.glUniform2f(p.pt,1f/outputWidth,1f/outputHeight);
    GLES20.glUniform1f(p.strength,strength);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    checkGl("shader pass");
  }

  private static void bind(int unit, int texture, int uniform) {
    if (uniform < 0) return; // GLSL legitimately optimizes unused uniforms away.
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0+unit);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
    GLES20.glUniform1i(uniform,unit);
  }

  private void makeCurrent() {
    if (!EGL14.eglMakeCurrent(display,surface,surface,context)) throw egl("make current");
  }
  private static IllegalStateException egl(String action) {
    return new IllegalStateException("Anime4K EGL " + action + ": 0x" + Integer.toHexString(EGL14.eglGetError()));
  }
  private static void checkGl(String action) {
    int error = GLES20.glGetError();
    if (error != GLES20.GL_NO_ERROR)
      throw new IllegalStateException("Anime4K GL " + action + ": 0x" + Integer.toHexString(error));
  }

  public synchronized String diagnostics() {
    return String.format(Locale.ROOT,
        "Anime4K v0.9/v1-style fast • OpenGL GPU-only • non-neural • %dx • %s • %s • frames=%d • frame processing cumulative %.3fs%s",
        scale,renderer,version,frames,elapsedNanos/1e9,closed ? " • closed" : "");
  }

  @Override public synchronized void close() {
    if (closed && display.equals(EGL14.EGL_NO_DISPLAY)) return;
    SavedEgl previous = new SavedEgl();
    // Defensive: our context should only be current within upscale, never caller-owned.
    if (previous.context.equals(context)) previous = SavedEgl.empty();
    closed = true;
    try { release(); } finally { previous.restore(); }
  }

  private void release() {
    if (display.equals(EGL14.EGL_NO_DISPLAY)) return;
    EGLDisplay owned = display;
    boolean canDelete = !context.equals(EGL14.EGL_NO_CONTEXT)
        && !surface.equals(EGL14.EGL_NO_SURFACE)
        && EGL14.eglMakeCurrent(display,surface,surface,context);
    if (canDelete) {
      for (Program p : programs) if (p != null) GLES20.glDeleteProgram(p.id);
      GLES20.glDeleteTextures(textures.length,textures,0);
      if (framebuffer != 0) GLES20.glDeleteFramebuffers(1,new int[]{framebuffer},0);
      // Driver/context destruction also releases objects if makeCurrent failed/context lost.
    }
    if (EGL14.eglGetCurrentContext().equals(context) && !context.equals(EGL14.EGL_NO_CONTEXT))
      EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
    if (!surface.equals(EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display,surface);
    if (!context.equals(EGL14.EGL_NO_CONTEXT)) EGL14.eglDestroyContext(display,context);
    if (ownsDisplay) EGL14.eglTerminate(owned);
    display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; surface = EGL14.EGL_NO_SURFACE;
    upload = null; readback = null; argb = null;
  }

  private static final class SavedEgl {
    final EGLDisplay display;
    final EGLContext context;
    final EGLSurface draw, read;
    SavedEgl() {
      display = EGL14.eglGetCurrentDisplay(); context = EGL14.eglGetCurrentContext();
      draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
      read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
    }
    private SavedEgl(boolean ignored) {
      display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT;
      draw = EGL14.EGL_NO_SURFACE; read = EGL14.EGL_NO_SURFACE;
    }
    static SavedEgl empty() { return new SavedEgl(true); }
    void restore() {
      if (!display.equals(EGL14.EGL_NO_DISPLAY)) {
        if (!EGL14.eglMakeCurrent(display,draw,read,context)) throw egl("restore caller context/draw/read");
      } else {
        EGLDisplay current = EGL14.eglGetCurrentDisplay();
        if (!current.equals(EGL14.EGL_NO_DISPLAY) && !EGL14.eglMakeCurrent(current,
            EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)) throw egl("restore no context");
      }
    }
  }

  private static final class Program {
    final int id, source, aux, original, pt, strength;
    Program(String vertex, String fragment) {
      int v = 0, f = 0, p = 0;
      try {
        v = compile(GLES20.GL_VERTEX_SHADER,vertex);
        f = compile(GLES20.GL_FRAGMENT_SHADER,fragment);
        p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f);
        GLES20.glBindAttribLocation(p,0,"aPosition");
        GLES20.glLinkProgram(p);
        int[] ok = new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if (ok[0] == 0) throw new IllegalStateException("Anime4K program link: " + GLES20.glGetProgramInfoLog(p));
        id = p;
        source = GLES20.glGetUniformLocation(p,"uTexture");
        aux = GLES20.glGetUniformLocation(p,"uAux");
        original = GLES20.glGetUniformLocation(p,"uOriginal");
        pt = GLES20.glGetUniformLocation(p,"uPt");
        strength = GLES20.glGetUniformLocation(p,"uStrength");
      } catch (RuntimeException | Error e) {
        if (p != 0) GLES20.glDeleteProgram(p);
        throw e;
      } finally {
        if (v != 0) GLES20.glDeleteShader(v);
        if (f != 0) GLES20.glDeleteShader(f);
      }
    }
    private static int compile(int type, String text) {
      int s = GLES20.glCreateShader(type);
      try {
        GLES20.glShaderSource(s,text); GLES20.glCompileShader(s);
        int[] ok = new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if (ok[0] == 0) throw new IllegalStateException("Anime4K shader compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
      } catch (RuntimeException | Error e) { GLES20.glDeleteShader(s); throw e; }
    }
  }
}
