package com.neotalkie.device;

/** Process-local button latch: deduplicate ROM key/broadcast edges across activity changes. */
final class PttHandoff {
  private boolean held, returning;
  boolean down(boolean onTalk) {
    if (held) return false;
    held = true;
    returning = !onTalk;
    return true;
  }
  void up() { held = false; }
  boolean resumeTalk() {
    boolean start = returning && held;
    returning = false;
    return start;
  }
  void pause() { if (!returning) held = false; }
}
