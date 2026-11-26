package com.neotalkie.device;

/** Splits streamed spoken text from the trailing topic, even when a tag spans deltas. */
final class ReplyTextStream {
  private static final String OPEN = "<topic>", CLOSE = "</topic>";
  private String pending = "", topic = "";
  private boolean metadata, finished;
  private int remaining;

  ReplyTextStream(int maxChars) { remaining = maxChars; }

  String accept(String delta) {
    if (finished) return "";
    String text = pending + delta;
    pending = "";
    String body = "";
    if (!metadata) {
      int start = text.indexOf(OPEN);
      if (start < 0) {
        int end = text.length();
        for (int n = Math.min(OPEN.length() - 1, end); n > 0; n--) {
          if (text.endsWith(OPEN.substring(0, n))) { end -= n; break; }
        }
        pending = text.substring(end);
        return spoken(text.substring(0, end));
      }
      body = spoken(text.substring(0, start));
      text = text.substring(start + OPEN.length());
      metadata = true;
    }
    int end = text.indexOf(CLOSE);
    if (end >= 0) {
      String value = text.substring(0, end).trim();
      int count = value.codePointCount(0, value.length());
      boolean valid = count > 0 && count <= 6;
      for (int i = 0; valid && i < value.length();) {
        int cp = value.codePointAt(i);
        valid = Character.isIdeographic(cp);
        i += Character.charCount(cp);
      }
      if (valid) topic = value;
      finished = true;
    } else if (text.length() > 64) finished = true;
    else pending = text;
    return body;
  }

  private String spoken(String value) {
    int length = Math.min(remaining, value.codePointCount(0, value.length()));
    remaining -= length;
    return value.substring(0, value.offsetByCodePoints(0, length));
  }

  String topic() { return topic; }
}
