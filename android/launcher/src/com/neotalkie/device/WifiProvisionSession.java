package com.neotalkie.device;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** One physical-confirmation-bound connection; no Android APIs, logs or persistence. */
final class WifiProvisionSession {
  static final String VALUE="641f2002-cf44-4c44-9121-b0219fd08011";
  static final int WAIT=1, READY=2, RECEIVING=3, CONNECTING=4, SUCCESS=5,
      AUTH=6, TIMEOUT=7, CANCELLED=8, ERROR=9, SAVE=10, INVALID=11;
  private KeyPair pair;
  private final byte[] challenge=new byte[16], publicBytes=new byte[272], encrypted=new byte[256];
  private final byte[] commitment=new byte[32];
  private int committed;
  private int mode;
  private boolean trusted;
  private byte[] secret, reply=new byte[]{0x7f};
  private int state=WAIT, received, sequence;
  private long began;
  private boolean publicRequested, consumed, destroyed;
  private int ip;
  private Credentials credentials;
  String fingerprint="";
  WifiProvisionSession() throws Exception {
    SecureRandom random=new SecureRandom(); random.nextBytes(challenge);
    KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048,random);pair=gen.generateKeyPair();
    byte[] modulus=((RSAPublicKey)pair.getPublic()).getModulus().toByteArray();
    System.arraycopy(modulus,modulus.length-256,publicBytes,0,256);System.arraycopy(challenge,0,publicBytes,256,16);

  }
  static byte[] mac(byte[] key,byte[] data)throws Exception{
    Mac h=Mac.getInstance("HmacSHA256");h.init(new SecretKeySpec(key,"HmacSHA256"));return h.doFinal(data);
  }
  private byte[] proof(byte[] key,String role)throws Exception{
    Mac h=Mac.getInstance("HmacSHA256");h.init(new SecretKeySpec(key,"HmacSHA256"));
    h.update(role.getBytes("US-ASCII"));h.update(publicBytes);return Arrays.copyOf(h.doFinal(commitment),16);
  }
  synchronized boolean recognize(byte[] packet,byte[] key){
    if(destroyed || !publicRequested || state!=WAIT || packet.length!=17 || key==null)return false;
    try{
      if(!MessageDigest.isEqual(proof(key,"phone"),Arrays.copyOfRange(packet,1,17)))return false;
      byte[] tag=proof(key,"radio");reply=new byte[17];reply[0]=0x15;
      System.arraycopy(tag,0,reply,1,16);trusted=true;state=READY;return true;
    }catch(Exception e){return false;}
  }
  synchronized boolean management(){return mode==2 || mode==3;}
  synchronized boolean currentNetwork(){return mode==3;}
  synchronized byte[] managementSecret(){return secret.clone();}
  synchronized boolean trusted(){return trusted;}
  synchronized boolean approve(){if(destroyed || !publicRequested || state!=WAIT)return false;state=READY;return true;}
  synchronized boolean awaitingApproval(){return publicRequested && state==WAIT;}
  synchronized int state(){return state;}
  synchronized byte[] reply(){return reply.clone();}
  synchronized boolean accept(byte[] packet,long now) {
    if(destroyed || packet==null || packet.length<1 || packet.length>20)return false;
    int op=packet[0]&255;
    if(op==0x10 && packet.length==2){
      int page=packet[1]&255,start=page*17;if(start>=publicBytes.length)return false;
      int count=Math.min(17,publicBytes.length-start);reply=new byte[count+3];
      reply[0]=0x10;reply[1]=(byte)page;reply[2]=16;System.arraycopy(publicBytes,start,reply,3,count);return true;
    }
    if(op==0x14 && state==WAIT && committed<32){
      int size=packet.length-2;
      if(packet.length<3 || (packet[1]&255)!=(committed/18) || size!=Math.min(18,32-committed))return false;
      System.arraycopy(packet,2,commitment,committed,size);committed+=size;
      if(committed==32)try{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(publicBytes);byte[] hash=digest.digest(commitment);
        StringBuilder h=new StringBuilder();for(int i=0;i<6;i++)h.append(String.format(java.util.Locale.US,"%02X",hash[i]&255));
        fingerprint=h.substring(0,6)+" "+h.substring(6);publicRequested=true;
      }catch(Exception e){state=INVALID;return false;}
      return true;
    }
    if(op==0x11 && packet.length==3){
      reply=new byte[20];reply[0]=0x11;reply[1]=(byte)state;reply[2]=packet[1];reply[3]=packet[2];
      for(int i=0;i<4;i++)reply[4+i]=(byte)(ip>>>(i*8));
      if(secret!=null)try{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
        byte[] tag=mac.doFinal(Arrays.copyOf(reply,8));System.arraycopy(tag,0,reply,8,12);
      }catch(Exception e){return false;}
      return true;
    }
    if(op==0x12 && packet.length==3 && packet[1]==1 && packet[2]==0 && state==READY && !consumed){
      state=RECEIVING;received=sequence=0;began=now;return true;
    }
    if(op!=0x13 || state!=RECEIVING || consumed)return false;
    if(packet.length<3 || (packet[1]&255)!=sequence || now<began || now-began>15000 || received+packet.length-2>256){state=INVALID;Arrays.fill(encrypted,(byte)0);return false;}
    System.arraycopy(packet,2,encrypted,received,packet.length-2);received+=packet.length-2;sequence++;
    if(received==256){
      consumed=true;byte[] plain=null;
      try{
        Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.DECRYPT_MODE,pair.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
        plain=rsa.doFinal(encrypted);
        if(plain.length<53 || (plain[0]<1 || plain[0]>3) || !MessageDigest.isEqual(challenge,Arrays.copyOfRange(plain,1,17)))throw new Exception();
        byte[] candidate=Arrays.copyOfRange(plain,17,49);
        if(!MessageDigest.isEqual(commitment,MessageDigest.getInstance("SHA-256").digest(candidate))){Arrays.fill(candidate,(byte)0);throw new Exception();}
        secret=candidate;
        mode=plain[0];
        if(mode==3){
          if(plain.length!=53 || plain[49]!=0 || plain[50]!=0)throw new Exception();
          credentials=new Credentials("","",true,false);
        }else credentials=Credentials.parse(plain);
        state=CONNECTING;
      }catch(Exception e){state=INVALID;}
      finally{Arrays.fill(encrypted,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);pair=null;}
    }
    return true;
  }
  synchronized Credentials takeCredentials(){Credentials c=credentials;credentials=null;return c;}
  synchronized void result(int code,int address){if(!destroyed && state==CONNECTING){state=code;ip=address;}}
  synchronized void destroy(){destroyed=true;pair=null;if(secret!=null)Arrays.fill(secret,(byte)0);secret=null;Arrays.fill(encrypted,(byte)0);Arrays.fill(challenge,(byte)0);Arrays.fill(commitment,(byte)0);if(credentials!=null)credentials.clear();credentials=null;reply=new byte[]{0x7f};}
  static final class Credentials {
    String ssid,password;final boolean open,hidden;
    Credentials(String ssid,String password,boolean open,boolean hidden){this.ssid=ssid;this.password=password;this.open=open;this.hidden=hidden;}
    static Credentials parse(byte[] b)throws Exception{
      int n=b[49]&255,p=b[50]&255,mode=b[51]&255,hidden=b[52]&255;
      if(n<1 || n>32 || p>63 || b.length!=53+n+p || mode>1 || hidden>1 || (mode==0 ? p!=0 : p<8))throw new Exception();
      CharsetDecoder decoder=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
      String ssid=decoder.decode(ByteBuffer.wrap(b,53,n)).toString();
      String password=new String(b,53+n,p,Charset.forName("US-ASCII"));
      for(int i=53+n;i<b.length;i++)if((b[i]&255)<32 || (b[i]&255)>126)throw new Exception();
      if(ssid.indexOf('\0')>=0)throw new Exception();return new Credentials(ssid,password,mode==0,hidden==1);
    }
    void clear(){ssid=null;password=null;}
  }
}
