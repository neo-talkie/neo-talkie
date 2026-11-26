package com.neotalkie.device;

import android.content.Context;
import android.content.ContentProviderClient;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;

/** Foreground BLE handoff or independently authenticated background Wi-Fi management. */
final class ManagementEndpoint implements Closeable {
  static final int PORT = 38671;
  private final Context context;
  private final ServerSocket listener;
  static final int BACKGROUND_PORT = 38672;
  private static final long IDLE_MS = 5 * 60 * 1000L;
  private final LanHandshake handshake;
  private final byte[] boundIdentity;
  private final Handler timer = new Handler(Looper.getMainLooper());
  private final Runnable expire = this::expireIdle;
  private long deadline;
  private byte[] key;
  private volatile boolean closed;
  private volatile Socket currentSocket;
  private int lastSequence;
  ManagementEndpoint(Context context, int address, byte[] secret, boolean background) throws Exception {
    this.context=context.getApplicationContext();
    boundIdentity = background ? secret.clone() : null;
    handshake = background ? new LanHandshake(secret) : null;
    if (!background) key=WifiProvisionSession.mac(secret,"management-v1".getBytes(StandardCharsets.US_ASCII));
    byte[] ip=new byte[4];
    for(int i=0;i<4;i++)ip[i]=(byte)(address >>> (8*i));
    listener=new ServerSocket();
    try {
      listener.setReuseAddress(true);
      listener.bind(new InetSocketAddress(InetAddress.getByAddress(ip),background ? BACKGROUND_PORT : PORT),4);
    } catch(IOException e){listener.close();throw e;}
    new Thread(this::serve,"local-device-management").start();
  }

  private void serve(){
    while(!closed){
      try(Socket socket=listener.accept()){
        currentSocket=socket;
        if(closed)return;
        socket.setSoTimeout(3500);
        try{handle(socket);}catch(Exception e){
          if(!closed)respond(socket,403,"");
        }
      }catch(IOException ignored){}finally{currentSocket=null;}
    }
  }

  private static String line(InputStream in)throws IOException{
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    for(int i=0;i<2048;i++){
      int b=in.read();
      if(b<0)throw new EOFException();
      if(b==10)return bytes.toString("US-ASCII").trim();
      bytes.write(b);
    }
    throw new IOException();
  }
  private void handle(Socket socket)throws Exception{
    DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    String[] request = line(in).split(" ");
    if (request.length != 3 || !request[0].equals("POST") || !request[2].matches("HTTP/1\\.[01]")) throw new IOException();
    String path = request[1];
    int size=-1;
    for(int i=0;;i++){
      if(i>=48)throw new IOException();
      String header=line(in);
      if(header.isEmpty())break;
      int colon=header.indexOf(':');
      if(colon<1)throw new IOException();
      String name=header.substring(0,colon);
      if(name.equalsIgnoreCase("Transfer-Encoding"))throw new IOException();
      if(name.equalsIgnoreCase("Content-Length")){
        if(size!=-1)throw new IOException();
        size=Integer.parseInt(header.substring(colon+1).trim());
      }
    }
    if(size<8 || size>64000)throw new IOException();
    byte[] data=new byte[size];in.readFully(data);
    String body = new String(data,StandardCharsets.US_ASCII);
    if (!ScreenPower.interactive(context)) throw new IOException();
    if (handshake != null) {
      if (!ManagementService.enabled(context) || !Arrays.equals(boundIdentity, ManagementService.binding(context))) throw new IOException();
      if (path.equals("/hello")) {
        respond(socket,200,handshake.hello(body,SystemClock.elapsedRealtime()));
        return;
      }
      if (path.equals("/session")) {
        installKey(handshake.finish(body,SystemClock.elapsedRealtime()));
        respond(socket,200,"OK");
        return;
      }
    }
    if (!path.equals("/manage")) throw new IOException();
    String[] fields=body.split(" ",2);
    int seq=Integer.parseInt(fields[0]);
    if(seq<=lastSequence || seq<=0 || closed)throw new IOException();
    byte[] plain=crypt(seq,false,Base64.decode(fields[1],Base64.NO_WRAP));
    lastSequence=seq;
    JSONObject result;
    try{
      JSONObject req=new JSONObject(new String(plain,StandardCharsets.UTF_8));
      String op=req.getString("op");
      String app = req.optString("app", DeviceRoutes.TALK);
      if (app.equals("device") && op.equals("info")) {
        result = deviceInfo();
      } else {
        ManagedApp target=ManagedApp.resolve(context,app,req.optInt("api",1),op);
        Bundle bundle=new Bundle();
        bundle.putString("data",req.getJSONObject("data").toString());
        if(closed)return;
        result=callProvider(target,op,bundle);
        // Retain the original foreground client's combined device/app response.
        if(!req.has("app") && (op.equals("info")||op.equals("save")) && !result.has("error")) addDeviceInfo(result);
      }
    }catch(Exception e){
      android.util.Log.w("NeoTalkieManage","local request failed: "+e.getClass().getSimpleName());
      result=new JSONObject().put("error","设备服务不可用，请检查两个 APK 的版本");
    }
    finally{Arrays.fill(plain,(byte)0);}
    if(!closed)respond(socket,200,Base64.encodeToString(crypt(seq,true,result.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
  }
  private JSONObject deviceInfo() throws Exception {
    JSONObject result = new JSONObject().put("apps",ManagedApp.directory(context))
        .put("background",ManagementService.enabled(context));
    addDeviceInfo(result);
    return result;
  }
  private void addDeviceInfo(JSONObject result) throws Exception {
    Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int level=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
    int scale=battery==null?0:battery.getIntExtra(BatteryManager.EXTRA_SCALE,0);
    result.put("battery",level<0||scale<=0?-1:level*100/scale);
    result.put("deviceName",ManagementService.deviceName(context));
    result.put("deviceModel",Build.MODEL);
    result.put("serial",Build.UNKNOWN.equals(Build.SERIAL)?"":Build.SERIAL);
    result.put("firmware",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName);
  }
  private JSONObject callProvider(ManagedApp target,String op,Bundle args)throws Exception{
    boolean read=target.reads.contains(op);
    for(int attempt=0;!closed;attempt++){
      ContentProviderClient provider=context.getContentResolver().acquireUnstableContentProviderClient(
          Uri.parse("content://"+target.authority));
      if(provider==null)throw new IOException();
      try{
        Bundle reply=provider.call(op,null,args);
        return new JSONObject(reply.getString("result"));
      }catch(android.os.DeadObjectException e){
        if(!read || attempt>=2)throw e;
      }finally{provider.release();}
    }
    throw new IOException();
  }

  private synchronized void clearKey() {
    timer.removeCallbacks(expire);
    if (key != null) Arrays.fill(key,(byte)0);
    key=null;
  }
  private synchronized void installKey(byte[] fresh) throws IOException {
    clearKey();
    if (closed) throw new IOException();
    key=fresh;
    lastSequence=0;
    touch();
  }
  private void touch() {
    deadline=SystemClock.elapsedRealtime()+IDLE_MS;
    timer.removeCallbacks(expire);
    timer.postDelayed(expire,IDLE_MS);
  }
  private synchronized void expireIdle() {
    if (key != null && SystemClock.elapsedRealtime()>=deadline) clearKey();
  }
  private synchronized byte[] crypt(int seq,boolean response,byte[] input)throws Exception{
    if (handshake != null) expireIdle();
    if (closed || key == null) throw new IOException();
    byte[] iv=ByteBuffer.allocate(12).putInt(response?2:1).putInt(0).putInt(seq).array();
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(response?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
    byte[] output=cipher.doFinal(input);
    if(handshake!=null) touch();
    return output;
  }
  private static void respond(Socket socket,int code,String body)throws IOException{
    OutputStream out=socket.getOutputStream();
    out.write(("HTTP/1.1 "+code+(code==200?" OK":" Forbidden")+"\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+body.length()+"\r\n\r\n"+body).getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }
  public void close(){
    closed=true;
    try{listener.close();}catch(IOException ignored){}
    Socket client=currentSocket;
    if(client!=null)try{client.close();}catch(IOException ignored){}
    clearKey();
    if (handshake!=null) handshake.close();
  }
}
