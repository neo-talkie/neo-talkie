package com.neotalkie.device;

import java.util.ArrayList;
import java.util.List;

/** Fixed-size wrapping and a four-line viewport; no body text is discarded. */
final class TurnText {
  interface Measure {
    int fit(String text);
  }

  static final class Document {
    final List<String> lines;
    final int rows;

    Document(List<String> lines) {
      this(lines, UiContract.EXPANDED_ROWS);
    }

    Document(List<String> lines, int rows) {
      this.lines = lines;
      this.rows = rows;
    }

    boolean expanded() {
      return rows == UiContract.EXPANDED_ROWS && lines.size() > UiContract.COMPACT_ROWS;
    }

    int maxOffset() {
      return Math.max(0, lines.size() - rows);
    }

    int clamp(int offset) {
      return Math.max(0, Math.min(offset, maxOffset()));
    }

    String[] window(int offset) {
      offset = clamp(offset);
      String[] result = {"", "", "", ""};
      for (int i = 0; i < rows && offset + i < lines.size(); i++) result[i] = lines.get(offset + i);
      return result;
    }
  }

  static Document document(String state, String body, Measure measure) {
    return document(state, body, measure, false);
  }

  static Document document(String state, String body, Measure measure, boolean answer) {
    List<String> lines = new ArrayList<>();
    if (!answer || clean(body).isEmpty()) {
      lines.add(ellipsis(clean(state), measure));
      lines.add(ellipsis(clean(body), measure));
      return new Document(lines);
    }
    wrap(clean(body), measure, lines);
    return new Document(lines);
  }

  static Document processing(String state, Measure measure) {
    return document(state, state.equals(UiContract.T_PHASE_LISTENING) ? UiContract.T_RELEASE_SEND : UiContract.T_PLEASE_WAIT, measure);
  }

  // Ordinal workflow milestones, not time estimates or completion percentages.
  static int processingStage(String state) {
    return UiContract.stage(state);
  }

  private static void wrap(String text, Measure measure, List<String> lines) {
    while (!text.isEmpty()) {
      int end = boundary(text, measure.fit(text));
      // A single oversized glyph is clipped by the viewport, never dropped or split.
      if (end == 0) end = text.offsetByCodePoints(0, 1);
      lines.add(text.substring(0, end));
      text = text.substring(end);
    }
  }

  /** Only schedules work during the 100 ms transition, using the caller's monotonic clock. */
  static final class FooterFade {
    static final int DURATION_MS = UiContract.FADE_MS;
    private int from = 255, target = 255;
    private long started;

    void setExpanded(boolean expanded, long now) {
      int next = expanded ? 0 : 255;
      if (next == target) return;
      from = alpha(now);
      target = next;
      started = now;
    }

    int alpha(long now) {
      long elapsed = Math.max(0, Math.min(DURATION_MS, now - started));
      return from + (int) ((target - from) * elapsed / DURATION_MS);
    }

    boolean running(long now) {
      return alpha(now) != target;
    }
  }

  static String ellipsis(String text, Measure measure) {
    if (measure.fit(text) >= text.length()) return text;
    int end = boundary(text, measure.fit(text));
    while (end > 0 && measure.fit(text.substring(0, end) + "…") < end + 1)
      end = text.offsetByCodePoints(end, -1);
    return measure.fit("…") == 0 ? "" : text.substring(0, end) + "…";
  }

  private static int boundary(String text, int end) {
    end = Math.max(0, Math.min(text.length(), end));
    if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
    return end;
  }

  private static String clean(String text) {
    return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ');
  }
}
