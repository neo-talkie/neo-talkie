package com.neotalkie.device;

import android.content.Context;
import java.io.*;
import org.json.JSONObject;

final class Config {
  final String voiceKey, arkKey, model, speaker, prompt;
  final boolean webSearch;
  final String channelId,channelName,epoch;
  final long contextMs,version;
  final Context context;

  Config(Context c) throws Exception {
    this(c,null);
  }
  Config(Context c,String previewSpeaker) throws Exception {
    context=c.getApplicationContext();
    JSONObject o=CompanionConfig.read(c),channel=CompanionConfig.active(o);
    version=o.getLong("version");channelId=channel.getString("id");channelName=channel.getString("name");
    epoch=o.optString("contextEpoch","");contextMs=channel.getInt("contextMin")*60000L;
    voiceKey = o.getString("voiceApiKey");
    arkKey = o.optString("arkApiKey");
    if (voiceKey.isEmpty() || (previewSpeaker==null && arkKey.isEmpty())) throw new IOException("语音密钥未配置");
    model = o.optString("model", "");
    if (previewSpeaker==null && model.trim().isEmpty()) throw new IOException("Missing model or endpoint ID");
    speaker = previewSpeaker==null?channel.getString("speaker"):previewSpeaker;
    webSearch = o.optBoolean("webSearch", true);
    prompt =
        channel.getString("systemPrompt") + "\n本轮语音回复必须在30秒内结束，最多90个汉字或等长短句，优先直接回答重点，不复述问题。";
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
