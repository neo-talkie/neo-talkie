package com.neotalkie.device;

import android.app.Service;
import android.content.*;
import android.net.*;
import java.net.Inet4Address;
import android.os.Handler;
import android.os.IBinder;
import android.util.Base64;
import java.util.Arrays;

/** Started service on the Android 5.1 handset. Sleeps until connectivity or policy changes. */
public final class ManagementService extends Service {
  private static final String KEY = "managerBinding", ENABLED = "backgroundManagement";
  // Preserve the private preferences previously owned by BleSetupActivity.getPreferences().
  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences(BleSetupActivity.class.getName(), Context.MODE_PRIVATE);
  }
  static synchronized String deviceName(Context c) {
    SharedPreferences preferences = prefs(c);
    String suffix = preferences.getString("bleSuffix", "");
    if (suffix.isEmpty()) {
      suffix = String.format(java.util.Locale.US, "%04X", new java.security.SecureRandom().nextInt(65536));
      preferences.edit().putString("bleSuffix", suffix).apply();
    }
    return "NeoTalkie-" + suffix;
  }
  static byte[] binding(Context c) {
    String key = prefs(c).getString(KEY, "");
    return key.isEmpty() ? null : Base64.decode(key, Base64.NO_WRAP);
  }
  static boolean enabled(Context c) {
    return prefs(c).contains(KEY) && prefs(c).getBoolean(ENABLED, false);
  }
  static void restore(Context c) {
    if (enabled(c)) c.startService(new Intent(c, ManagementService.class));
  }
  static boolean update(Context c, byte[] key, boolean enabled) {
    SharedPreferences.Editor edit = prefs(c).edit().putBoolean(ENABLED, enabled && key != null);
    if (key == null) edit.remove(KEY);
    else edit.putString(KEY, Base64.encodeToString(key, Base64.NO_WRAP));
    if (!edit.commit()) return false;
    if (enabled(c)) restore(c);
    else c.stopService(new Intent(c, ManagementService.class));
    return true;
  }
  public static final class Restore extends BroadcastReceiver {
    public void onReceive(Context c, Intent intent) { restore(c); }
  }

  private final Handler handler = new Handler();
  private ConnectivityManager connectivity;
  private final Runnable retry = this::refresh;
  private final ConnectivityManager.NetworkCallback changes = new ConnectivityManager.NetworkCallback() {
    public void onAvailable(Network n) { handler.post(retry); }
    public void onLinkPropertiesChanged(Network n, LinkProperties properties) { handler.post(retry); }
    public void onLost(Network n) { handler.post(retry); }
  };
  private ManagementEndpoint endpoint;
  private int address;
  private Network activeNetwork;
  private byte[] activeBinding;
  private boolean destroyed;

  public void onCreate() {
    super.onCreate();
    connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
    NetworkRequest wifi=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
    connectivity.registerNetworkCallback(wifi,changes);
  }
  public int onStartCommand(Intent i, int flags, int startId) {
    refresh();
    return enabled(this) ? START_STICKY : START_NOT_STICKY;
  }
  private void refresh() {
    if (destroyed) return;
    handler.removeCallbacks(retry);
    if (!enabled(this)) { closeEndpoint(); stopSelf(); return; }
    int ip=0;
    Network selected=null;
    for(Network net:connectivity.getAllNetworks()) {
      NetworkCapabilities caps=connectivity.getNetworkCapabilities(net);
      if(caps==null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
      LinkProperties link=connectivity.getLinkProperties(net);
      if(link==null) continue;
      for(LinkAddress addr:link.getLinkAddresses()) {
        if(!(addr.getAddress() instanceof Inet4Address)) continue;
        byte[] bytes=addr.getAddress().getAddress();
        for(int i=0;i<4;i++) ip |= (bytes[i]&255) << (i*8);
        selected=net;
        break;
      }
      if(selected!=null) break;
    }
    byte[] key=binding(this);
    if (endpoint!=null && ip==address && activeNetwork.equals(selected) && Arrays.equals(key,activeBinding)) return;
    closeEndpoint();
    if (ip == 0 || key == null) return;
    try {
      endpoint = new ManagementEndpoint(this, ip, key, true);
      address = ip;
      activeNetwork = selected;
      activeBinding = key;
    } catch (Exception e) {
      handler.postDelayed(retry, 10000);
    }
  }
  private void closeEndpoint() {
    if (endpoint != null) endpoint.close();
    endpoint = null;
    if (activeBinding != null) Arrays.fill(activeBinding, (byte)0);
    activeBinding = null;
  }
  public IBinder onBind(Intent intent) { return null; }
  public void onDestroy() {
    destroyed=true;
    connectivity.unregisterNetworkCallback(changes);
    handler.removeCallbacks(retry);
    closeEndpoint();
    super.onDestroy();
  }
}
