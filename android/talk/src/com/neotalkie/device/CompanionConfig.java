package com.neotalkie.device;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import org.json.*;

/** config.json also accepts the original ADB provisioning format. */
final class CompanionConfig {
  private static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"config.json"));}
  static synchronized JSONObject read(Context c)throws Exception{
    JSONObject o;
    try(InputStream stream=file(c).openRead()){o=new JSONObject(Config.read(stream));}
    catch(FileNotFoundException e){o=new JSONObject();}
    if(!o.has("channels")){
      JSONObject ch=new JSONObject().put("id","ch-01").put("name","默认频道")
          .put("speaker",o.optString("speaker","zh_female_xiaohe_uranus_bigtts")).put("contextMin",5)
          .put("systemPrompt",Config.read(c.getAssets().open("prompt.txt")).trim());
      o.put("channels",new JSONArray().put(ch)).put("activeChannelId","ch-01").put("version",0);
    }
    return o;
  }
  static JSONObject active(JSONObject o)throws JSONException{
    JSONArray channels=o.getJSONArray("channels");
    for(int i=0;i<channels.length();i++){
      JSONObject ch=channels.getJSONObject(i);
      if(ch.getString("id").equals(o.getString("activeChannelId")))return ch;
    }
    throw new JSONException("invalid channel");
  }
  static JSONObject publicInfo(JSONObject o)throws Exception{
    return new JSONObject().put("updated",o.optLong("updated")).put("version",o.getLong("version")).put("model",o.optString("model"))
        .put("hasVoice",!o.optString("voiceApiKey").isEmpty()).put("hasArk",!o.optString("arkApiKey").isEmpty())
        .put("channels",o.getJSONArray("channels")).put("activeChannelId",o.getString("activeChannelId"));
  }
  static String field(JSONObject o,String key,int max,boolean empty)throws Exception{
    Object raw=o.get(key);
    if(!(raw instanceof String))throw new JSONException(key);
    String value=((String)raw).trim();
    if(value.length()>max || (!empty && value.isEmpty()) || value.indexOf(0)>=0)throw new JSONException(key);
    return value;
  }
  static synchronized JSONObject save(Context c,JSONObject data)throws Exception{
    JSONObject o=read(c);
    if(o.getLong("version")!=data.getLong("version"))return new JSONObject().put("code","conflict").put("error","配置已变化，请重新读取后再保存");
    String model=field(data,"model",180,false);
    JSONArray input=data.getJSONArray("channels"),channels=new JSONArray();
    if(input.length()<1 || input.length()>3)throw new JSONException("channels");
    HashSet<String> ids=new HashSet<>();
    for(int i=0;i<input.length();i++){
      JSONObject ch=input.getJSONObject(i);
      String id=field(ch,"id",64,false), speaker=field(ch,"speaker",128,false);
      int minutes=ch.getInt("contextMin");
      if(!id.matches("[a-zA-Z0-9_-]+") || !ids.add(id) || !Arrays.asList(0,3,5,15).contains(minutes))throw new JSONException("channel");
      channels.put(new JSONObject().put("id",id).put("name",field(ch,"name",24,false)).put("speaker",speaker)
          .put("contextMin",minutes).put("systemPrompt",field(ch,"systemPrompt",2000,true)));
    }
    String active=field(data,"activeChannelId",64,false);
    if(!ids.contains(active))throw new JSONException("active");
    if(!active.equals(o.getString("activeChannelId")))o.put("contextEpoch",UUID.randomUUID().toString());
    for(String key:Arrays.asList("voiceApiKey","arkApiKey")){
      String value=field(data,key,512,true);
      if(!value.isEmpty())o.put(key,value);
    }
    o.put("updated",System.currentTimeMillis()).put("model",model).put("channels",channels).put("activeChannelId",active).put("version",o.getLong("version")+1);
    o.put("speaker",active(o).getString("speaker"));
    AtomicFile file=file(c);
    FileOutputStream out=file.startWrite();
    try{out.write(o.toString().getBytes("UTF-8"));file.finishWrite(out);}
    catch(IOException e){file.failWrite(out);throw e;}
    return publicInfo(o);
  }
}
