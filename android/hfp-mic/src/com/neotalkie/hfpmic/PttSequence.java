package com.neotalkie.hfpmic;

/** One physical press can arrive as both a key and a vendor broadcast. */
final class PttSequence {
  enum Mode {
    VOICE, TOGGLE_FN;

    static Mode fromPreference(String name) {
      return TOGGLE_FN.name().equals(name) ? TOGGLE_FN : VOICE;
    }
  }
  interface Port {
    boolean fn(boolean down);
    void audio(boolean open);
    void schedule(Runnable task, long delayMs);
    void cancel(Runnable task);
    void status(String text);
  }
  private enum Phase { IDLE, FIRST_TAP, DELAY, TALKING, TAP_GAP, LAST_TAP }
  private static final long TAP_MS = 220, DELAY_MS = 500;
  private final Port port;
  private final Runnable next = this::advance;
  private Phase phase = Phase.IDLE;
  private Mode mode, turn;
  private boolean held, earlyRelease;

  PttSequence(Port port, Mode mode) { this.port = port; this.mode = mode; }
  void select(Mode target) {
    if (target == mode) return;
    release();
    mode = target;
  }
  void press() {
    if (held) return;
    held = true;
    if (phase == Phase.IDLE) begin();
  }
  private void begin() {
    turn = mode;
    if (turn != Mode.VOICE && !port.fn(true)) {
      port.status("请在电脑连接键盘");
      return;
    }
    if (turn == Mode.TOGGLE_FN) {
      port.status("等待 Fn 和语音延迟");
      waitIn(Phase.FIRST_TAP, TAP_MS);
    } else talk();
  }
  void release() {
    held = false;
    switch (phase) {
      case FIRST_TAP:
        // Complete the first tap even if PTT was released immediately.
        earlyRelease = true;
        break;
      case DELAY:
      case TALKING:
        port.cancel(next);
        port.audio(false);
        if (turn == Mode.TOGGLE_FN) waitIn(Phase.TAP_GAP, 80);
        else idle();
        break;
      default: break;
    }
  }
  private void advance() {
    switch (phase) {
      case FIRST_TAP:
        if (!port.fn(false)) { reset(); return; }
        if (earlyRelease) waitIn(Phase.TAP_GAP, 80);
        else waitIn(Phase.DELAY, DELAY_MS);
        break;
      case DELAY: talk(); break;
      case TAP_GAP:
        if (port.fn(true)) waitIn(Phase.LAST_TAP, TAP_MS);
        else reset();
        break;
      case LAST_TAP:
        port.fn(false);
        idle();
        break;
      default: break;
    }
  }
  private void talk() { phase = Phase.TALKING; port.audio(true); }
  private void waitIn(Phase value, long ms) {
    phase = value;
    port.schedule(next, ms);
  }
  private void idle() {
    phase = Phase.IDLE;
    earlyRelease = false;
    port.status("等待 PTT");
    if (held) begin();
  }
  /** Loss of transport invalidates queued gestures for the old connection. */
  void reset() {
    held = false;
    port.cancel(next);
    if (phase != Phase.IDLE) {
      port.audio(false);
      if (turn != Mode.VOICE) port.fn(false);
    }
    idle();
  }
}
