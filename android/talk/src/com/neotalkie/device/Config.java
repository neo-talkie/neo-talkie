package com.neotalkie.device;

import android.content.Context;
import java.io.*;
import org.json.JSONObject;

final class Config {
  final String voiceKey, arkKey, model, speaker, prompt;
  final boolean webSearch;

  Config(Context c) throws Exception {
    File file = new File(c.getFilesDir(), "config.json");
    if (!file.isFile()) throw new IOException("尚未配置，请运行 provision.py");
    JSONObject o = new JSONObject(read(new FileInputStream(file)));
    voiceKey = o.getString("voiceApiKey");
    arkKey = o.getString("arkApiKey");
    if (voiceKey.isEmpty() || arkKey.isEmpty()) throw new IOException("语音密钥未配置");
    model = o.optString("model", "");
    if (model.trim().isEmpty()) throw new IOException("Missing model or endpoint ID");
    speaker = o.optString("speaker", "zh_female_xiaohe_uranus_bigtts");
    webSearch = o.optBoolean("webSearch", true);
    prompt =
        read(c.getAssets().open("prompt.txt")) + "\n本轮语音回复必须在30秒内结束，最多90个汉字或等长短句，优先直接回答重点，不复述问题。";
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
