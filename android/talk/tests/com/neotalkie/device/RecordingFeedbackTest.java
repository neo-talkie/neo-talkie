package com.neotalkie.device;

/** Offline boundary/signal checks. No microphone, network or wall-clock waits. */
public final class RecordingFeedbackTest {
  private static int checks;

  private static void check(boolean pass) {
    if (!pass) throw new AssertionError("capture feedback check " + (checks + 1));
    checks++;
  }

  private static byte[] tone(int amplitude, int bias) {
    byte[] pcm = new byte[6400];
    for (int i = 0; i < pcm.length / 2; i++) {
      int value = bias + (int) Math.round(amplitude * Math.sin(2 * Math.PI * 200 * i / 16000));
      pcm[2 * i] = (byte) value;
      pcm[2 * i + 1] = (byte) (value >> 8);
    }
    return pcm;
  }

  public static void main(String[] args) {
    RecordingFeedback.PcmMeter meter = new RecordingFeedback.PcmMeter();
    check(meter.measure(new byte[] {0, 0, 0, 0}, 4) == 0);
    check(meter.measure(new byte[] {0, (byte) 0x80, (byte) 0xff, 0x7f}, 4) == 100);
    check(meter.peak == 32768 && meter.rms == 32768);
    check(meter.measure(new byte[] {0, 2, 0, 2}, 4) == 0); // DC is not sound.
    check(meter.peak == 512 && meter.rms == 0);
    check(meter.measure(new byte[] {0, (byte) 0xfe, 0x7f}, 3) == 0);
    check(meter.peak == 512);
    check(meter.measure(new byte[0], 0) == 0 && meter.peak == 0 && meter.rms == 0);
    byte[] quietSpeech = tone(512, 0);
    byte[] original = quietSpeech.clone();
    int quiet = meter.measure(quietSpeech, quietSpeech.length);
    check(quiet >= 25 && quiet <= 30); // Quiet speech remains visible: about 7 pixels.
    check(java.util.Arrays.equals(original, quietSpeech));
    int biased = meter.measure(tone(512, 8000), 6400);
    check(Math.abs(biased - quiet) <= 1);
    int normal = meter.measure(tone(4096, 0), 6400);
    check(normal > quiet + 25 && normal < 70); // Old peak mapping was already full.
    int loud = meter.measure(tone(16000, 0), 6400);
    check(loud > normal + 15 && loud < 90);
    int veryLoud = meter.measure(tone(32767, 0), 6400);
    check(veryLoud > loud && veryLoud < 100);
    byte[] impulse = new byte[6400];
    impulse[0] = (byte) 0xff;
    impulse[1] = 0x7f;
    check(meter.measure(impulse, impulse.length) < 40); // One click cannot pin the bar.
    java.util.Random random = new java.util.Random(7);
    byte[] noise = new byte[6400];
    for (int i = 0; i < noise.length; i += 2) {
      int value = random.nextInt(8193) - 4096;
      noise[i] = (byte) value;
      noise[i + 1] = (byte) (value >> 8);
    }
    int noiseLevel = meter.measure(noise, noise.length);
    check(meter.peak > 4000 && noiseLevel > 40 && noiseLevel < 65);
    check(meter.measure(new byte[6400], 6400) == 0); // No retained normalization history.
    check(RecordingFeedback.levelForRms(-1) == 0);
    check(RecordingFeedback.levelForRms(64) == 0);
    check(RecordingFeedback.levelForRms(4096) < 70);
    int previous = 0;
    for (int rms = 0; rms <= 32768; rms++) {
      int level = RecordingFeedback.levelForRms(rms);
      if (level < previous || level < 0 || level > 100)
        throw new AssertionError("meter must be monotonic and bounded");
      previous = level;
    }
    check(previous == 100);
    check(RecordingFeedback.tooShort(0));
    check(RecordingFeedback.tooShort(31998));
    check(RecordingFeedback.tooShort(32000));
    check(!RecordingFeedback.tooShort(32002));
    check(!RecordingFeedback.tooShort(64000));
    check(RecordingFeedback.MAX_BYTES == RecordingFeedback.MAX_MILLIS * 32);
    check(RecordingFeedback.seconds(0, 10000) == 0);
    check(RecordingFeedback.seconds(1000, 999) == 0);
    check(RecordingFeedback.seconds(1000, 1999) == 0);
    check(RecordingFeedback.seconds(1000, 2000) == 1);
    check(RecordingFeedback.seconds(1000, 30999) == 29);
    check(RecordingFeedback.seconds(1000, 31000) == 30);
    check(RecordingFeedback.seconds(1000, Long.MAX_VALUE) == 30);
    RecordingFeedback f = new RecordingFeedback();
    f.reset();
    check(f.barHeight() == 0);
    f.sample(1);
    check(f.barHeight() == 1);
    f.sample(50);
    check(f.barHeight() == 13);
    f.sample(100);
    check(f.barHeight() == 25);
    f.sample(1000);
    check(f.barHeight() == 25);
    f.sample(0);
    check(f.barHeight() == 13);
    for (int i = 0; i < 10; i++) f.sample(0);
    check(f.barHeight() == 0);
    f.sample(-100);
    check(f.barHeight() == 0);
    f.sample(40);
    for (int i = 0; i < 100; i++) {
      f.sample(40);
      if (f.barHeight() != 10) throw new AssertionError("constant level must not breathe");
    }
    check(f.barHeight() == 10);
    f.reset();
    check(f.barHeight() == 0);
    check(RecordingFeedback.processingWidth(-100) == RecordingFeedback.processingWidth(0));
    check(RecordingFeedback.processingWidth(500) >= 74);
    check(RecordingFeedback.processingWidth(2500) >= 140);
    check(RecordingFeedback.processingWidth(Long.MAX_VALUE) < 148);
    int prior = 0;
    for (int ms = 0; ms <= 120000; ms += 20) {
      int width = RecordingFeedback.processingWidth(ms);
      if (width < prior || width >= 148)
        throw new AssertionError("progress regressed or completed early");
      prior = width;
    }
    check(
        RecordingFeedback.processingWidth(500) - RecordingFeedback.processingWidth(0)
            > RecordingFeedback.processingWidth(2500) - RecordingFeedback.processingWidth(2000));
    System.out.println("Offline capture feedback checks passed: " + checks);
  }
}
