package com.neotalkie.device;

import java.io.*;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;

/** Volcengine v3 binary framing for the direct device speech connection. */
public final class Protocol {
  public static final Charset UTF8 = Charset.forName("UTF-8");
  public static final int MAX = 2 * 1024 * 1024;

  public static final class Frame {
    public int event, error;
    public boolean last;
    public String session = "";
    public byte[] body;
  }

  public static byte[] asr(byte[] body, boolean audio, boolean last) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(body.length + 8);
    DataOutputStream d = new DataOutputStream(b);
    d.write(
        new byte[] {
          0x11, (byte) (audio ? (last ? 0x22 : 0x20) : 0x10), (byte) (audio ? 0 : 0x10), 0
        });
    d.writeInt(body.length);
    d.write(body);
    return b.toByteArray();
  }

  public static byte[] tts(int event, String session, String json) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(b);
    d.write(new byte[] {0x11, 0x14, 0x10, 0});
    d.writeInt(event);
    if (event >= 100) {
      byte[] sid = session.getBytes(UTF8);
      d.writeInt(sid.length);
      d.write(sid);
    }
    byte[] body = json.getBytes(UTF8);
    d.writeInt(body.length);
    d.write(body);
    return b.toByteArray();
  }

  private static byte[] field(DataInputStream d) throws IOException {
    int n = d.readInt();
    if (n < 0 || n > MAX || n > d.available()) throw new IOException("Invalid frame length");
    byte[] b = new byte[n];
    d.readFully(b);
    return b;
  }

  public static Frame decode(byte[] bytes, boolean asr) throws IOException {
    if (bytes.length < 8 || bytes.length > MAX + 4096) throw new IOException("Invalid frame size");
    int header = (bytes[0] & 15) * 4, kind = (bytes[1] & 255) >> 4, flags = bytes[1] & 15;
    int serial = (bytes[2] & 255) >> 4, compression = bytes[2] & 15;
    if ((bytes[0] & 255) >> 4 != 1
        || header < 4
        || header > bytes.length
        || (kind != 9 && kind != 11 && kind != 15)
        || serial > 1
        || compression > 1) throw new IOException("Unsupported voice frame");
    DataInputStream d =
        new DataInputStream(new ByteArrayInputStream(bytes, header, bytes.length - header));
    Frame f = new Frame();
    if (kind == 15) f.error = d.readInt();
    if ((flags & 1) != 0) d.readInt();
    if (!asr && (flags & 4) != 0) f.event = d.readInt();
    if (!asr && (f.event >= 100 || f.event == 50 || f.event == 51 || f.event == 52))
      f.session = new String(field(d), UTF8);
    f.body = field(d);
    if (d.available() != 0) throw new IOException("Trailing voice data");
    if (compression == 1) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(f.body));
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > MAX) throw new IOException("Inflated frame too large");
        out.write(buf, 0, n);
      }
      in.close();
      f.body = out.toByteArray();
    }
    f.last = (flags & 2) != 0;
    if (asr && kind != 9 && f.error == 0) throw new IOException("Unexpected ASR frame");
    return f;
  }

  private Protocol() {}
}
