package com.neotalkie.device;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local allowlist of app contracts. A client cannot supply a provider URI. */
enum ManagedApp {
  TALK(DeviceRoutes.TALK, "com.neotalkie.talk.management", 1,
      Arrays.asList("info","records"), Arrays.asList("save","audition"));

  private static final String CONTROL = "com.neotalkie.launcher.permission.CONTROL";
  final String id, authority;
  final int api;
  final List<String> reads;
  private final List<String> writes;

  ManagedApp(String id,String authority,int api,List<String> reads,List<String> writes) {
    this.id=id;
    this.authority=authority;
    this.api=api;
    this.reads=reads;
    this.writes=writes;
  }
  private int installedApi(Context context) {
    PackageManager pm=context.getPackageManager();
    ProviderInfo provider=pm.resolveContentProvider(authority,PackageManager.GET_META_DATA);
    if(provider==null || !id.equals(provider.packageName) || !CONTROL.equals(provider.readPermission)
        || !CONTROL.equals(provider.writePermission)
        || pm.checkSignatures(context.getPackageName(),id)!=PackageManager.SIGNATURE_MATCH) return 0;
    // The original Talk provider implemented v1 before adding manifest metadata.
    int fallback=this==TALK?1:0;
    return provider.metaData==null?fallback:provider.metaData.getInt("com.neotalkie.management.API",fallback);
  }
  static JSONArray directory(Context context) throws JSONException {
    JSONArray apps=new JSONArray();
    for(ManagedApp app:values()) {
      int installed=app.installedApi(context);
      apps.put(new JSONObject().put("id",app.id).put("api",installed).put("available",installed!=0));
    }
    return apps;
  }
  static ManagedApp resolve(Context context,String id,int api,String op) throws IOException {
    for(ManagedApp app:values()) {
      if(app.id.equals(id) && app.api==api && app.installedApi(context)==api
          && (app.reads.contains(op) || app.writes.contains(op))) return app;
    }
    throw new IOException("unavailable management operation");
  }
}
