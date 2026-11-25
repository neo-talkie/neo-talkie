package com.neotalkie.device;

/** Shared capture budget and allocation-free, low-rate listening feedback. */
final class RecordingFeedback {
  static final int MAX_SECONDS = UiContract.CAPTURE_MS / 1000;
  static final long MAX_MILLIS = MAX_SECONDS * 1000L;
  static final int MAX_BYTES = MAX_SECONDS * 16000 * 2;
  static final int METER_HEIGHT = UiContract.METER_HEIGHT;
  private static final int NOISE_FLOOR = 64;
  private static final int METER_FULL_RMS = 32768;
  private int level;

  // Actual PCM duration, independent of PTT/setup/network scheduling delays.
  static boolean tooShort(long pcmBytes) {
    return pcmBytes <= UiContract.SHORT_INPUT_MS * 16L * 2;
  }

  // Display-only logarithmic scale over nine amplitude doublings (64..32768).
  // RMS, rather than a single peak, keeps transient noise from pinning the meter.
  static int levelForRms(int rms) {
    if (rms <= NOISE_FLOOR) return 0;
    if (rms >= METER_FULL_RMS) return 100;
    return (int) Math.round(100 * Math.log(rms / (double) NOISE_FLOOR) / Math.log(512));
  }

  /** One reusable analyzer per capture: one PCM pass, no per-buffer allocation. */
  static final class PcmMeter {
    int peak, rms;

    int measure(byte[] pcm, int length) {
      int count = Math.max(0, Math.min(length, pcm.length)) / 2;
      long sum = 0, squares = 0;
      peak = 0;
      for (int i = 0; i < count * 2; i += 2) {
        int value = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
        peak = Math.max(peak, Math.abs(value));
        sum += value;
        squares += (long) value * value;
      }
      // Remove microphone DC bias from the display measurement, never from PCM.
      double mean = count == 0 ? 0 : sum / (double) count;
      rms =
          count == 0
              ? 0
              : (int) Math.round(Math.sqrt(Math.max(0, squares / (double) count - mean * mean)));
      return levelForRms(rms);
    }
  }

  void reset() {
    level = 0;
  }

  void sample(int value) {
    value = Math.max(0, Math.min(100, value));
    // Fast attack, gentle release. Constant input has no time-driven animation.
    level = value > level ? value : (level + value) / 2;
  }

  int barHeight() {
    return (level * METER_HEIGHT + 99) / 100;
  }

  // Perceived waiting progress: fast start, a 2.5 s nominal sweep, then a slow tail.
  // Never claim completion before the real reply starts; no effect on network scheduling.
  static int processingWidth(long elapsedMs) {
    double elapsed = Math.max(0, elapsedMs);
    double progress;
    if (elapsed <= UiContract.WAIT_SWEEP_MS) {
      double remaining = 1 - elapsed / (double) UiContract.WAIT_SWEEP_MS;
      progress = 0.08 + 0.88 * (1 - remaining * remaining * remaining);
    } else progress = 0.96 + 0.03 * (1 - Math.exp(-(elapsed - UiContract.WAIT_SWEEP_MS) / (double) UiContract.WAIT_TAIL_MS));
    return Math.min(UiContract.BAR_WIDTH - 1, (int) Math.round(UiContract.BAR_WIDTH * progress));
  }

  static int seconds(long started, long now) {
    if (started == 0) return 0;
    return (int) Math.min(MAX_SECONDS, Math.max(0, now - started) / 1000);
  }
}
