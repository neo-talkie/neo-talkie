package com.neotalkie.device;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Offline tests only. No device, microphone or external service is used. */
public final class ProtocolTest {
  static int checks;

  static void ok(boolean value) {
    checks++;
    if (!value) throw new AssertionError("check " + checks);
  }

  interface Checked {
    void run() throws Exception;
  }

  static void fails(Checked f) throws Exception {
    boolean failed = false;
    try {
      f.run();
    } catch (IOException e) {
      failed = true;
    }
    ok(failed);
  }

  static byte[] frame(int kind, int flags, int compression, int event, String sid, byte[] body)
      throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.write(new byte[] {0x11, (byte) (kind << 4 | flags), (byte) (0x10 | compression), 0});
    if (kind == 15) d.writeInt(45000000);
    if ((flags & 1) != 0) d.writeInt(-1);
    if ((flags & 4) != 0) d.writeInt(event);
    if ((flags & 4) != 0 && (event >= 100 || event == 50 || event == 51 || event == 52)) {
      byte[] s = sid.getBytes(Protocol.UTF8);
      d.writeInt(s.length);
      d.write(s);
    }
    d.writeInt(body.length);
    d.write(body);
    return out.toByteArray();
  }

  static byte[] zip(byte[] b) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    GZIPOutputStream z = new GZIPOutputStream(out);
    z.write(b);
    z.close();
    return out.toByteArray();
  }

  public static void main(String[] args) throws Exception {
    ok(
        Arrays.equals(
            Protocol.asr(new byte[] {1, 2}, true, true),
            new byte[] {0x11, 0x22, 0, 0, 0, 0, 0, 2, 1, 2}));
    ok(
        Arrays.equals(
            Protocol.tts(1, "", "{}"),
            new byte[] {0x11, 0x14, 0x10, 0, 0, 0, 0, 1, 0, 0, 0, 2, 123, 125}));
    byte[] json = "{\"result\":{\"text\":\"你好\"}}".getBytes(Protocol.UTF8);
    Protocol.Frame f = Protocol.decode(frame(9, 3, 0, 0, "", json), true);
    ok(f.last);
    ok(Arrays.equals(f.body, json));
    f = Protocol.decode(frame(9, 2, 1, 0, "", zip(json)), true);
    ok(f.last);
    ok(Arrays.equals(f.body, json));
    f = Protocol.decode(frame(9, 4, 0, 150, "session", json), false);
    ok(f.event == 150);
    ok(f.session.equals("session"));
    f = Protocol.decode(frame(11, 4, 0, 352, "session", new byte[] {1, 2}), false);
    ok(f.event == 352);
    ok(f.body.length == 2);
    f = Protocol.decode(frame(9, 4, 0, 50, "connection", json), false);
    ok(f.session.equals("connection"));
    f = Protocol.decode(frame(15, 0, 0, 0, "", json), true);
    ok(f.error == 45000000);
    final byte[] good = frame(9, 3, 0, 0, "", json);
    for (int i = 0; i < good.length; i++) {
      final byte[] shortFrame = Arrays.copyOf(good, i);
      fails(
          new Checked() {
            public void run() throws Exception {
              Protocol.decode(shortFrame, true);
            }
          });
    }
    fails(
        new Checked() {
          public void run() throws Exception {
            Protocol.decode(Arrays.copyOf(good, good.length + 1), true);
          }
        });
    final byte[] negative = good.clone();
    Arrays.fill(negative, 8, 12, (byte) 0xff);
    fails(
        new Checked() {
          public void run() throws Exception {
            Protocol.decode(negative, true);
          }
        });
    final byte[] bomb = frame(9, 2, 1, 0, "", zip(new byte[Protocol.MAX + 1]));
    fails(
        new Checked() {
          public void run() throws Exception {
            Protocol.decode(bomb, true);
          }
        });
    Sse s =
        new Sse(
            new StringReader(
                ": keepalive\r\n"
                    + "event: token\r\n"
                    + "data: first\r\n"
                    + "data: second\r\n\r\n"
                    + "data: [DONE]\n\n"));
    ok("first\nsecond".equals(s.next()));
    ok("[DONE]".equals(s.next()));
    ok(s.next() == null);
    ok("end".equals(new Sse(new StringReader("data: end")).next()));
    final char[] huge = new char[262145];
    Arrays.fill(huge, 'x');
    fails(
        new Checked() {
          public void run() throws Exception {
            new Sse(new StringReader("data: " + new String(huge))).next();
          }
        });
    System.out.println("Offline protocol/SSE checks passed: " + checks);
  }
}
