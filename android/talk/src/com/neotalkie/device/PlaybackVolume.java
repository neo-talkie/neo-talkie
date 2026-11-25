package com.neotalkie.device;

/** Additional per-track gain; the platform still owns its original media volume steps. */
final class PlaybackVolume {
  static float gain(int systemLevel) {
    if (systemLevel == 0) return 0f;
    return systemLevel == 1 ? 0.375f : 0.75f;
  }
}
