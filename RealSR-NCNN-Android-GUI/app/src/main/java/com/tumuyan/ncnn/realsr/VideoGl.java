package com.tumuyan.ncnn.realsr;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.*;

/** Worker-owned EGL context: decoder OES readback and encoder bitmap upload. */
final class VideoGl implements AutoCloseable {
  private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
  private EGLContext context = EGL14.EGL_NO_CONTEXT;
  private EGLSurface window = EGL14.EGL_NO_SURFACE, pbuffer = EGL14.EGL_NO_SURFACE;
  private int program2d, programOes, texture2d, textureOes;
  private final int width, height;
  private SurfaceTexture texture;
  private Surface decoderSurface;
  private HandlerThread callbacks;
  private boolean available;
  private ByteBuffer pixels;
  private int[] argb;
  private final Object frameLock = new Object();
  private final float[] matrix = new float[16];
  private final FloatBuffer vertices = buffer(new float[] {-1, -1, 1, -1, -1, 1, 1, 1});
  private final FloatBuffer texNormal = buffer(new float[] {0, 0, 1, 0, 0, 1, 1, 1});
  private final FloatBuffer texBitmap = buffer(new float[] {0, 1, 1, 1, 0, 0, 1, 0});

  VideoGl(Surface encoder, int width, int height) {
    this.width = width;
    this.height = height;
    try {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      int[] v = new int[2];
      if (!EGL14.eglInitialize(display, v, 0, v, 1))
        throw new IllegalStateException("EGL initialize");
      int[] attrs = {
        EGL14.EGL_RED_SIZE,
        8,
        EGL14.EGL_GREEN_SIZE,
        8,
        EGL14.EGL_BLUE_SIZE,
        8,
        EGL14.EGL_ALPHA_SIZE,
        8,
        EGL14.EGL_RENDERABLE_TYPE,
        EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE,
        EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
        0x3142,
        1,
        EGL14.EGL_NONE
      };
      EGLConfig[] configs = new EGLConfig[1];
      int[] count = new int[1];
      if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
        throw new IllegalStateException("No recordable EGL config");
      context =
          EGL14.eglCreateContext(
              display,
              configs[0],
              EGL14.EGL_NO_CONTEXT,
              new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE},
              0);
      window =
          EGL14.eglCreateWindowSurface(display, configs[0], encoder, new int[] {EGL14.EGL_NONE}, 0);
      pbuffer =
          EGL14.eglCreatePbufferSurface(
              display,
              configs[0],
              new int[] {EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE},
              0);
      current(pbuffer);
      program2d = program(false);
      programOes = program(true);
      texture2d = newTexture(GLES20.GL_TEXTURE_2D);
      textureOes = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
      texture = new SurfaceTexture(textureOes);
      texture.setDefaultBufferSize(width, height);
      callbacks = new HandlerThread("video-surface-callback");
      callbacks.start();
      texture.setOnFrameAvailableListener(
          t -> {
            synchronized (frameLock) {
              available = true;
              frameLock.notifyAll();
            }
          },
          new Handler(callbacks.getLooper()));
      decoderSurface = new Surface(texture);
    } catch (RuntimeException ex) {
      close();
      throw ex;
    }
  }

  Surface decoderSurface() {
    return decoderSurface;
  }

  Bitmap read(int w, int h, VideoPolicy.Control control) throws InterruptedException {
    long deadline = System.nanoTime() + 10000000000L;
    synchronized (frameLock) {
      while (!available) {
        control.check();
        if (System.nanoTime() > deadline)
          throw new IllegalStateException("Decoder Surface frame timeout");
        frameLock.wait(100);
      }
      available = false;
    }
    current(pbuffer);
    texture.updateTexImage();
    texture.getTransformMatrix(matrix);
    draw(programOes, textureOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texNormal, matrix, w, h);
    int length = Math.multiplyExact(w, h);
    if (pixels == null) {
      pixels = ByteBuffer.allocateDirect(Math.multiplyExact(length, 4));
      argb = new int[length];
    } else if (argb.length != length) {
      throw new IllegalArgumentException("Dynamic readback dimensions unsupported");
    }
    pixels.clear();
    GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
    check();
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int k = (y * w + x) * 4;
        int r = pixels.get(k) & 255, g = pixels.get(k + 1) & 255, b = pixels.get(k + 2) & 255;
        argb[(h - 1 - y) * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
      }
    return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
  }

  void encode(Bitmap bitmap, long ptsUs) { encode(bitmap, ptsUs, false); }

  void encode(Bitmap bitmap, long ptsUs, boolean waitForDrawing) {
    current(window);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture2d);
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
    android.opengl.Matrix.setIdentityM(matrix, 0);
    draw(
        program2d,
        texture2d,
        GLES20.GL_TEXTURE_2D,
        texBitmap,
        matrix,
        bitmap.getWidth(),
        bitmap.getHeight());
    // Only the opt-in graphics-hint path waits for output drawing to complete.
    // This is synchronization, NOT a GPU duration measurement; no GPU split is reported.
    if (waitForDrawing) { GLES20.glFinish(); check(); }
    if (!EGLExt.eglPresentationTimeANDROID(display, window, Math.multiplyExact(ptsUs, 1000)))
      throw new IllegalStateException("EGL timestamp");
    if (!EGL14.eglSwapBuffers(display, window))
      throw new IllegalStateException("EGL swap " + EGL14.eglGetError());
  }

  private void current(EGLSurface surface) {
    if (!EGL14.eglMakeCurrent(display, surface, surface, context))
      throw new IllegalStateException("EGL make current");
  }

  private void draw(
      int program, int tex, int target, FloatBuffer uv, float[] transform, int w, int h) {
    GLES20.glViewport(0, 0, w, h);
    GLES20.glUseProgram(program);
    int p = GLES20.glGetAttribLocation(program, "aPosition"),
        t = GLES20.glGetAttribLocation(program, "aTex");
    vertices.position(0);
    uv.position(0);
    GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, vertices);
    GLES20.glEnableVertexAttribArray(p);
    GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 0, uv);
    GLES20.glEnableVertexAttribArray(t);
    GLES20.glUniformMatrix4fv(
        GLES20.glGetUniformLocation(program, "uMatrix"), 1, false, transform, 0);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(target, tex);
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    check();
  }

  private static FloatBuffer buffer(float[] f) {
    FloatBuffer b =
        ByteBuffer.allocateDirect(f.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    b.put(f).position(0);
    return b;
  }

  private static int newTexture(int target) {
    int[] t = new int[1];
    GLES20.glGenTextures(1, t, 0);
    GLES20.glBindTexture(target, t[0]);
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    return t[0];
  }

  private static int shader(int type, String source) {
    int s = GLES20.glCreateShader(type);
    GLES20.glShaderSource(s, source);
    GLES20.glCompileShader(s);
    int[] ok = new int[1];
    GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
    if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));
    return s;
  }

  private static int program(boolean oes) {
    int v =
        shader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 aPosition;attribute vec4 aTex;uniform mat4 uMatrix;varying vec2"
                + " vTex;void main(){gl_Position=aPosition;vTex=(uMatrix*aTex).xy;}");
    int f =
        shader(
            GLES20.GL_FRAGMENT_SHADER,
            (oes ? "#extension GL_OES_EGL_image_external : require\n" : "")
                + "precision mediump float;varying vec2 vTex;uniform "
                + (oes ? "samplerExternalOES" : "sampler2D")
                + " uTexture;void main(){gl_FragColor=texture2D(uTexture,vTex);}");
    int p = GLES20.glCreateProgram();
    GLES20.glAttachShader(p, v);
    GLES20.glAttachShader(p, f);
    GLES20.glLinkProgram(p);
    int[] ok = new int[1];
    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
    GLES20.glDeleteShader(v);
    GLES20.glDeleteShader(f);
    if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(p));
    return p;
  }

  private static void check() {
    int e = GLES20.glGetError();
    if (e != GLES20.GL_NO_ERROR) throw new IllegalStateException("GL error " + e);
  }

  @Override
  public void close() {
    if (decoderSurface != null) decoderSurface.release();
    if (texture != null) texture.release();
    if (callbacks != null) callbacks.quitSafely();
    if (display != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglMakeCurrent(
          display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
      if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
      if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
      EGL14.eglReleaseThread();
      EGL14.eglTerminate(display);
      display = EGL14.EGL_NO_DISPLAY;
    }
  }
}
