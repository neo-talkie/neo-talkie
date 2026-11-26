package com.neotalkie.device;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/** BLE wire formats for address discovery and the fixed-data setup probe. */
final class BleProbeProtocol {
  static final String SERVICE = "641f2000-cf44-4c44-9121-b0219fd08011";
  static final String VALUE = "641f2001-cf44-4c44-9121-b0219fd08011";
  static final byte[] SAMPLE = "NeoTalkie|测试网络_日本語_é|BLE".getBytes(Charset.forName("UTF-8"));
  static String discoveryUuid(String deviceName, int wifiAddress) {
    String suffix = deviceName.substring("NeoTalkie-".length());
    return String.format(java.util.Locale.US, "7d92e3b1-6f41-4c10-8101-%s%08x",
        suffix, Integer.reverseBytes(wifiAddress));
  }
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private int expected, sequence;
  private long started;
  private String token = "", reply = "NT1:READY";
  private boolean receiving;
  synchronized void reset() { bytes.reset(); expected = sequence = 0; token = ""; receiving = false; reply = "NT1:READY"; }
  synchronized boolean accept(byte[] packet, long now) {
    if (packet == null || packet.length < 1 || packet.length > 20) return fail("SIZE");
    if (packet[0] == 1) {
      reset();
      if (packet.length != 7) return fail("HEADER");
      expected = ((packet[1] & 255) << 8) | (packet[2] & 255);
      if (expected != SAMPLE.length) return fail("LENGTH");
      StringBuilder hex = new StringBuilder();
      for (int i=3;i<7;i++) hex.append(String.format(java.util.Locale.US,"%02x",packet[i] & 255));
      token = hex.toString(); started = now; receiving = true; reply = "NT1:RX:" + token;
      return true;
    }
    if (!receiving) return fail("STATE");
    if (now < started || now - started > 15000) return fail("TIMEOUT");
    if (packet[0] != 2 || packet.length < 3 || (packet[1] & 255) != sequence) return fail("ORDER");
    if (bytes.size() + packet.length - 2 > expected) return fail("OVERFLOW");
    bytes.write(packet,2,packet.length-2); sequence++;
    if (bytes.size() == expected) {
      receiving = false;
      if (!Arrays.equals(SAMPLE, bytes.toByteArray())) return fail("CONTENT");
      reply = "NT1:OK:" + token + ":" + expected;
    }
    return true;
  }
  private boolean fail(String code) { receiving = false; bytes.reset(); reply = "NT1:ERR:" + code; return false; }
  synchronized String reply() { return reply; }
  synchronized boolean passed() { return reply.startsWith("NT1:OK:"); }
  synchronized int size() { return bytes.size(); }
}
