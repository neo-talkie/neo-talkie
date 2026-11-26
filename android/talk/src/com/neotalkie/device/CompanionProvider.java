package com.neotalkie.device;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import org.json.*;

public final class CompanionProvider extends ContentProvider {
  public boolean onCreate(){return true;}
  public Bundle call(String method,String arg,Bundle extras){
    Context c=getContext();
    c.enforceCallingPermission("com.neotalkie.launcher.permission.CONTROL","device management");
    Bundle out=new Bundle();
    try{
      JSONObject data=new JSONObject(extras.getString("data")),result;
      switch(method){
        case "info":result=CompanionConfig.publicInfo(CompanionConfig.read(c));break;
        case "save":result=CompanionConfig.save(c,data);break;
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
