package com.neotalkie.device;

/** Offline gain boundaries, attenuation and transitions; no speaker or network access. */
public final class PlaybackVolumeTest {
  public static void main(String[] args) {
    int checks = 0;
    if (PlaybackVolume.gain(0) != 0f) throw new AssertionError("mute");
    checks++;
    double db = 20 * Math.log10(PlaybackVolume.gain(1) / 0.75);
    if (Math.abs(db + 6.0206) > 0.001) throw new AssertionError("lowest step attenuation");
    checks++;
    for (int level = 2; level <= 15; level++) {
      if (PlaybackVolume.gain(level) != 0.75f) throw new AssertionError("higher step changed");
      checks++;
    }
    int[] levels = {2, 1, 0, 1, 5, 1};
    float[] gains = {0.75f, 0.375f, 0f, 0.375f, 0.75f, 0.375f};
    for (int i = 0; i < levels.length; i++) {
      if (PlaybackVolume.gain(levels[i]) != gains[i]) throw new AssertionError("transition");
      checks++;
    }
    System.out.println("Offline playback volume checks passed: " + checks);
  }
}
