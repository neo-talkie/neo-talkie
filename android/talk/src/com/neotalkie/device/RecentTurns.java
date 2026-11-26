package com.neotalkie.device;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.*;

final class RecentTurns extends SQLiteOpenHelper {
  private static RecentTurns instance;
  static synchronized RecentTurns get(Context c){
    if(instance==null)instance=new RecentTurns(c.getApplicationContext());
    return instance;
  }
  private RecentTurns(Context c){super(c,"recent-turns.db",null,1);}
  public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE turns(id INTEGER PRIMARY KEY AUTOINCREMENT, record TEXT NOT NULL)");}
  public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException();}
  void add(Config cfg,SpeechEngine.Turn turn)throws Exception{
    if(turn.userText.isEmpty() && turn.replyText.isEmpty())return;
    JSONObject row=new JSONObject().put("time",turn.wallTime).put("channelId",cfg.channelId)
        .put("channelName",cfg.channelName).put("model",cfg.model).put("question",clip(turn.userText))
        .put("answer",clip(turn.replyText)).put("usage",turn.usage).put("speaker",cfg.speaker)
        .put("elapsedMs",android.os.SystemClock.elapsedRealtime()-turn.started).put("searchCalls",turn.searchCalls);
    JSONObject stats=turn.metrics(),times=new JSONObject();
    for(String k:new String[]{"asrTailMs","llmFirstTextMs","textToAudioMs","releaseToFirstWriteMs"})
      if(!stats.isNull(k))times.put(k,stats.get(k));
    row.put("timings",times).put("responseMs",stats.optLong("releaseToFirstWriteMs",0));
    SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
    try{
      db.execSQL("INSERT INTO turns(record) VALUES(?)",new Object[]{row.toString()});
      db.execSQL("DELETE FROM turns WHERE id <= (SELECT MAX(id)-100 FROM turns)");
      db.setTransactionSuccessful();
    }finally{db.endTransaction();}
  }
  private String clip(String s){return s.substring(0,Math.min(s.length(),4000));}
  JSONObject list(long before)throws Exception{
    JSONArray rows=new JSONArray();long last=0;
    try(Cursor c=getReadableDatabase().rawQuery("SELECT id,record FROM turns WHERE id<? ORDER BY id DESC LIMIT 6",new String[]{String.valueOf(before<=0?Long.MAX_VALUE:before)})){
      while(rows.length()<5 && c.moveToNext()){last=c.getLong(0);rows.put(new JSONObject(c.getString(1)).put("id",last));}
      return new JSONObject().put("rows",rows).put("next",c.moveToNext()?last:0);
    }
  }
}
