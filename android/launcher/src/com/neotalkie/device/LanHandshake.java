package com.neotalkie.device;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** One pending challenge. A new hello never displaces an authenticated session. */
final class LanHandshake {
  private final SecureRandom random = new SecureRandom();
  private final byte[] binding;
  private String transcript;
  private long expires;

  LanHandshake(byte[] binding) { this.binding = binding.clone(); }

  static String hex(byte[] bytes) {
    char[] digits = "0123456789abcdef".toCharArray(), out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      out[i*2] = digits[(bytes[i] & 255) >> 4];
      out[i*2+1] = digits[bytes[i] & 15];
    }
    return new String(out);
  }

  private byte[] derive(String role) throws Exception {
    return WifiProvisionSession.mac(binding, ("lan-v1/" + role + "/" + transcript).getBytes(StandardCharsets.US_ASCII));
  }

  synchronized String hello(String client, long now) throws Exception {
    if (!client.matches("[0-9a-f]{64}")) throw new IOException();
    byte[] nonce = new byte[32];
    random.nextBytes(nonce);
    String radio = hex(nonce);
    transcript = client + "/" + radio;
    expires = now + 30000;
    return radio + " " + hex(derive("radio"));
  }

  synchronized byte[] finish(String proof, long now) throws Exception {
    if (transcript == null || now >= expires || !proof.matches("[0-9a-f]{64}")) throw new IOException();
    if (!MessageDigest.isEqual(hex(derive("phone")).getBytes(StandardCharsets.US_ASCII),
                               proof.getBytes(StandardCharsets.US_ASCII))) throw new IOException();
    byte[] key = derive("session");
    transcript = null;
    return key;
  }

  synchronized void close() { transcript = null; Arrays.fill(binding, (byte)0); }
}
