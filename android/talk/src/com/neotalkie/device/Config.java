package com.neotalkie.device;

import android.content.Context;
import java.io.*;
import org.json.JSONObject;

final class Config {
  final String voiceKey, arkKey, model, speaker, prompt;
  final boolean webSearch;
  final String channelId,channelName;
  final long contextMs;
  final int replySeconds;
  final Context context;

  Config(Context c) throws Exception {
    this(c,null);
  }
  Config(Context c,String previewSpeaker) throws Exception {
    this(c, CompanionConfig.read(c), previewSpeaker);
  }

  Config(Context c, JSONObject o, String previewSpeaker) throws Exception {
    context=c.getApplicationContext();
    JSONObject channel=CompanionConfig.active(o);
    channelId=channel.getString("id");channelName=channel.getString("name");
    replySeconds = channel.getInt("replySeconds");
    contextMs=channel.getInt("contextMin")*60000L;
    voiceKey = o.getString("voiceApiKey");
    arkKey = o.optString("arkApiKey");
    if (voiceKey.isEmpty() || (previewSpeaker==null && arkKey.isEmpty())) throw new IOException("语音密钥未配置");
    model = o.optString("model", "");
    if (previewSpeaker==null && model.trim().isEmpty()) throw new IOException("Missing model or endpoint ID");
    speaker = previewSpeaker==null?channel.getString("speaker"):previewSpeaker;
    webSearch = o.optBoolean("webSearch", true);
    prompt =
        channel.getString("systemPrompt") + "\n本轮语音回复最多" + replySeconds + "秒，正文最多" + (3 * replySeconds) + "个汉字或等长短句，直接回答重点。回答末尾另附 <topic>主题</topic>，用一至六个汉字概括用户这次说的内容。标签内仅写主题。";
  }

  static String read(InputStream in) throws IOException {
    try {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (b.size() + n > 65536) throw new IOException("配置文件过大");
        b.write(buf, 0, n);
      }
      return new String(b.toByteArray(), Protocol.UTF8);
    } finally {
      in.close();
    }
  }
}
