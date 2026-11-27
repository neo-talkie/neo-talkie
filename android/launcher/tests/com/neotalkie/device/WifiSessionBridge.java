package com.neotalkie.device;
import java.io.*;
public final class WifiSessionBridge {
  static byte[] bytes(String hex){byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return b;}
  static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
  public static void main(String[] args)throws Exception{
    WifiProvisionSession s=new WifiProvisionSession();byte[] pub=new byte[272];
    for(int i=0;i<16;i++){s.accept(new byte[]{0x10,(byte)i},0);System.arraycopy(s.reply(),3,pub,i*17,17);}
    System.out.println("PUB "+hex(pub));
    BufferedReader in=new BufferedReader(new InputStreamReader(System.in));String line;
    while((line=in.readLine())!=null){
      if(line.equals("A"))System.out.println("A "+s.approve());
      else if(line.startsWith("W "))System.out.println("W "+s.accept(bytes(line.substring(2)),1));
      else if(line.startsWith("R ")){
        String[] parts=line.split(" ");
        System.out.println("R "+s.recognize(bytes(parts[2]),bytes(parts[1])));
      }
      else if(line.equals("M"))System.out.println("M "+s.management()+" "+s.currentNetwork()+" "+s.trusted());
      else if(line.equals("READ"))System.out.println("READ "+hex(s.reply()));
      else if(line.equals("C")){WifiProvisionSession.Credentials c=s.takeCredentials();
        if(c==null)System.out.println("C NONE");
        else{if(!c.ssid.equals("测试网络_日本語_é") || !c.password.equals("local-test-only") || c.open || c.hidden)throw new AssertionError();c.clear();System.out.println("C OK");}
      }else if(line.equals("SUCCESS")){s.result(WifiProvisionSession.SUCCESS,0x04030201);System.out.println("SUCCESS");}
      else if(line.startsWith("P ")){s.progress((int)Long.parseLong(line.substring(2),16));System.out.println("P OK");}
      else if(line.startsWith("FAIL ")){String[] p=line.split(" ");s.result(Integer.parseInt(p[1]),(int)Long.parseLong(p[2],16));System.out.println("FAIL OK");}
      else if(line.equals("D")){s.destroy();System.out.println("D");}
      System.out.flush();
    }
    s.destroy();
  }
}
