package com.neotalkie.device;

import android.content.*;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import org.json.*;

public final class CompanionProvider extends ContentProvider {
  static final Uri CONFIG_URI = Uri.parse("content://com.neotalkie.talk.management/config");

  private static void playSyncBeep() {
    new Thread(() -> {
      AudioTrack track = null;
      try {
        int rate = 24000;
        int b1 = rate * 65 / 1000;
        int gap = rate * 55 / 1000;
        int b2 = rate * 75 / 1000;
        int fade = rate * 4 / 1000;
        short[] pcm = new short[b1 + gap + b2];
        fillTone(pcm, 0, b1, rate, 1760.0, fade);
        fillTone(pcm, b1 + gap, b2, rate, 2093.0, fade);
        track = new AudioTrack(
            AudioManager.STREAM_MUSIC,
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            pcm.length * 2,
            AudioTrack.MODE_STATIC);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) return;
        track.write(pcm, 0, pcm.length);
        track.play();
        Thread.sleep(260);
      } catch (Throwable ignored) {
      } finally {
        if (track != null) {
          try { track.stop(); } catch (Throwable ignored) {}
          try { track.release(); } catch (Throwable ignored) {}
        }
      }
    }, "sync-beep").start();
  }

  private static void fillTone(short[] pcm, int offset, int len, int rate, double hz, int fade) {
    for (int i = 0; i < len; i++) {
      double env = 1.0;
      if (i < fade) env = (double) i / fade;
      else if (i > len - fade) env = (double) (len - i) / fade;
      pcm[offset + i] = (short) Math.round(Math.sin(2.0 * Math.PI * hz * i / rate) * 14000.0 * env);
    }
  }

  public boolean onCreate(){return true;}
  public Bundle call(String method,String arg,Bundle extras){
    Context c=getContext();
    c.enforceCallingPermission("com.neotalkie.launcher.permission.CONTROL","device management");
    Bundle out=new Bundle();
    try{
      JSONObject data=new JSONObject(extras.getString("data")),result;
      switch(method){
        case "info":result=CompanionConfig.publicInfo(CompanionConfig.read(c));break;
        case "save":
          result=CompanionConfig.save(c,data);
          if (!result.has("error")) {
            c.getContentResolver().notifyChange(CONFIG_URI, null);
            playSyncBeep();
          }
          break;
        case "records":result=RecentTurns.get(c).list(data.optLong("before"));break;
        case "audition":
          String speaker=CompanionConfig.field(data,"speaker",128,false);
          SpeechEngine.audition(c,speaker);result=new JSONObject();break;
        default:throw new IllegalArgumentException();
      }
      out.putString("result",result.toString());
    }catch(Exception e){
      out.putString("result",method.equals("audition")?"{\"error\":\"手台试听失败，请核对语音密钥、音色 ID 和网络\"}":"{\"error\":\"设备无法完成操作，请检查参数或重新连接\"}");
    }
    return out;
  }
  public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
  public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
  public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
  public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
  public String getType(Uri u){return null;}
}
