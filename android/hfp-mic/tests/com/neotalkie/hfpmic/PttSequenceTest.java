package com.neotalkie.hfpmic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PttSequenceTest implements PttSequence.Port {
  private final List<String> changes = new ArrayList<>();
  private long now, due;
  private Runnable pending;
  private boolean keyboard = true;

  public boolean fn(boolean down) {
    changes.add(now + (down ? " down" : " up"));
    return keyboard;
  }
  public void audio(boolean open) { changes.add(now + (open ? " open" : " close")); }
  public void schedule(Runnable task, long delay) {
    if (pending != null) throw new AssertionError("Two simultaneous timers");
    pending = task;
    due = now + delay;
  }
  public void cancel(Runnable task) { if (pending == task) pending = null; }
  public void status(String message) {}
  private void until(long deadline) {
    while (pending != null && due <= deadline) {
      now = due;
      Runnable task = pending;
      pending = null;
      task.run();
    }
    now = deadline;
  }
  private void expect(String... expected) {
    if (!changes.equals(Arrays.asList(expected))) throw new AssertionError(changes.toString());
  }
  private static void ordinary() {
    PttSequenceTest io = new PttSequenceTest();
    PttSequence ptt = new PttSequence(io, PttSequence.Mode.VOICE);
    ptt.press(); ptt.press(); io.until(1000); ptt.release(); ptt.release();
    io.expect("0 open", "1000 close");
  }
  private static void toggle() {
    PttSequenceTest io = new PttSequenceTest();
    PttSequence ptt = new PttSequence(io, PttSequence.Mode.TOGGLE_FN);
    ptt.press(); io.until(719); io.expect("0 down", "220 up");
    io.until(1000); ptt.release(); io.until(1500);
    io.expect("0 down", "220 up", "720 open", "1000 close", "1080 down", "1300 up");
  }
  private static void shortTap(int release) {
    PttSequenceTest io = new PttSequenceTest();
    PttSequence ptt = new PttSequence(io, PttSequence.Mode.TOGGLE_FN);
    ptt.press(); io.until(release); ptt.release(); io.until(1200);
    if (release < 220) io.expect("0 down", "220 up", "300 down", "520 up");
    else io.expect("0 down", "220 up", release + " close", (release + 80) + " down", (release + 300) + " up");
  }
  private static void held() {
    PttSequenceTest io = new PttSequenceTest();
    PttSequence ptt = new PttSequence(io, PttSequence.Mode.HOLD_FN);
    ptt.press(); io.until(1000); ptt.release(); io.until(1050); ptt.press(); io.until(1300);
    ptt.reset(); io.until(2000);
    io.expect("0 down", "180 open", "1000 close", "1180 up", "1180 down", "1300 close", "1300 up");
  }
  private static void disconnect() {
    PttSequenceTest io = new PttSequenceTest();
    PttSequence ptt = new PttSequence(io, PttSequence.Mode.TOGGLE_FN);
    ptt.press(); io.until(400); ptt.reset(); io.until(1500);
    io.expect("0 down", "220 up", "400 close", "400 up");
    io.keyboard = false;
    ptt.press(); io.until(2500);
    io.expect("0 down", "220 up", "400 close", "400 up", "1500 down");
  }
  public static void main(String[] args) {
    ordinary(); toggle(); shortTap(60); shortTap(400); held(); disconnect();
    System.out.println("PTT mode sequences passed");
  }
}
