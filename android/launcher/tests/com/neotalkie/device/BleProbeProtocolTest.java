package com.neotalkie.device;
import java.util.*;
public final class BleProbeProtocolTest {
  static void check(boolean ok){if(!ok)throw new AssertionError();}
  static byte[] header(){return new byte[]{1,0,(byte)BleProbeProtocol.SAMPLE.length,0x12,0x34,0x56,0x78};}
  static void send(BleProbeProtocol p,byte[] sample){
    for(int i=0,seq=0;i<sample.length;i+=18,seq++){
      byte[] packet=new byte[Math.min(18,sample.length-i)+2];packet[0]=2;packet[1]=(byte)seq;
      System.arraycopy(sample,i,packet,2,packet.length-2);p.accept(packet,1);
    }
  }
  public static void main(String[] args){
    BleProbeProtocol p=new BleProbeProtocol();
    if(args.length>0){for(String hex:args){byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);p.accept(b,0);}System.out.println(p.reply());return;}
    check(p.reply().equals("NT1:READY"));check(p.accept(header(),0));send(p,BleProbeProtocol.SAMPLE);
    check(p.passed());check(p.reply().equals("NT1:OK:12345678:"+BleProbeProtocol.SAMPLE.length));check(p.size()==BleProbeProtocol.SAMPLE.length);
    check(!p.accept(new byte[]{2,0,1},2));check(!p.passed());
    p.reset();check(!p.accept(new byte[21],0));check(!p.accept(new byte[]{1},0));
    byte[] bad=header();bad[2]--;check(!p.accept(bad,0));
    p.accept(header(),0);check(!p.accept(new byte[]{2,1,1},1));check(p.reply().endsWith("ORDER"));
    p.accept(header(),0);check(!p.accept(new byte[]{2,0,1},15001));check(p.reply().endsWith("TIMEOUT"));
    p.accept(header(),0);byte[] modified=BleProbeProtocol.SAMPLE.clone();modified[15]^=1;send(p,modified);check(!p.passed());check(p.reply().endsWith("CONTENT"));
    p.accept(header(),0);send(p,BleProbeProtocol.SAMPLE);p.reset();check(!p.passed());check(p.size()==0);check(p.reply().equals("NT1:READY"));
    System.out.println("BLE framing, ordering, timeout, corruption and reset checks passed");
  }
}
