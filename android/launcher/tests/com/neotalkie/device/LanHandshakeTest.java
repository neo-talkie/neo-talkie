package com.neotalkie.device;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Offline test. The bridge mode accepts synthetic test transcripts on stdin. */
public final class LanHandshakeTest {
  private static byte[] tag(byte[] binding, String role, String client, String radio) throws Exception {
    return WifiProvisionSession.mac(binding, ("lan-v1/"+role+"/"+client+"/"+radio).getBytes(StandardCharsets.US_ASCII));
  }
  private static void check(boolean valid) { if (!valid) throw new AssertionError(); }
  private static void rejected(LanHandshake server,String proof,long now) throws Exception {
    try {server.finish(proof,now);throw new AssertionError();} catch(IOException expected){}
  }
  public static void main(String[] args) throws Exception {
    // Both the local checks and the JS bridge use this public, synthetic binding.
    byte[] binding=new byte[32];
    LanHandshake server=new LanHandshake(binding);
    if(args.length>0) {
      java.io.BufferedReader in=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
      for(String line;(line=in.readLine())!=null;) {
        String[] fields=line.split(" ");
        try {
          if(fields[0].equals("hello"))System.out.println(server.hello(fields[1],1));
          else System.out.println(LanHandshake.hex(server.finish(fields[1],2)));
        }catch(IOException e){System.out.println("REJECTED");}
      }
      return;
    }
    String client=LanHandshake.hex(new byte[32]);
    String[] first=server.hello(client,0).split(" ");
    check(first[1].equals(LanHandshake.hex(tag(binding,"radio",client,first[0]))));
    rejected(server,first[1],1);
    String proof=LanHandshake.hex(tag(binding,"phone",client,first[0]));
    check(MessageDigest.isEqual(server.finish(proof,2),tag(binding,"session",client,first[0])));
    rejected(server,proof,3);
    String second=server.hello(client,4).split(" ")[0];
    check(!first[0].equals(second));
    rejected(server,proof,5);
    rejected(server,LanHandshake.hex(tag(binding,"phone",client,second)),30004);
    System.out.println("LAN challenge, role separation, one-time proof and expiry passed");
  }
}
