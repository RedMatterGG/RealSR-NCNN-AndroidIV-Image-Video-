package com.tumuyan.ncnn.realsr;

/** Admission stays closed during all teardown, including reentrant start requests. */
final class VideoJobGate {
  private boolean busy;

  synchronized boolean begin() {
    if (busy) return false;
    busy = true;
    return true;
  }

  synchronized void finish(Runnable teardown) {
    try {
      teardown.run();
    } finally {
      busy = false;
    }
  }
}
