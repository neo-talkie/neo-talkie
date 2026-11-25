package com.neotalkie.device;

/** Short-lived launcher handoff. No credentials/audio; monotonic times only. */
final class PttLease {
  private String token = "";
  private long started;
  private boolean held;
  synchronized void begin(String id, long now) { token = id; started = now; held = true; }
  synchronized void release() { held = false; }
  synchronized boolean release(String id) {
    if (!token.equals(id)) return false;
    held = false; return true;
  }
  synchronized boolean isHeld(String id, long now) {
    return held && !id.isEmpty() && token.equals(id) && now >= started && now - started < 30000;
  }
}
