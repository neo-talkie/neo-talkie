package com.neotalkie.device;

import java.io.*;

/** Bounded SSE parser: CRLF, comments, multiline data and EOF are supported. */
public final class Sse {
  private final Reader reader;

  public Sse(Reader reader) {
    this.reader = reader;
  }

  public String next() throws IOException {
    StringBuilder data = new StringBuilder(), line = new StringBuilder();
    while (true) {
      int c = reader.read();
      if (c == '\n' || c == -1) {
        String s = line.toString();
        line.setLength(0);
        if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("data:")) {
          String value = s.substring(5);
          if (value.startsWith(" ")) value = value.substring(1);
          if (data.length() > 0) data.append('\n');
          data.append(value);
          if (data.length() > 262144) throw new IOException("SSE event too large");
        }
        if ((s.isEmpty() || c == -1) && data.length() > 0) return data.toString();
        if (c == -1) return null;
      } else {
        line.append((char) c);
        if (line.length() > 262144) throw new IOException("SSE line too large");
      }
    }
  }
}
