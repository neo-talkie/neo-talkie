package com.neotalkie.device;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.*;
import android.os.ParcelUuid;
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

  // The setup window and the service live in the launcher process, on the main thread.
  private static ManagementService running;
  private static boolean setupOpen;
  static void setupWindow(boolean open) {
    setupOpen = open;
    if (running == null) return;
    if (open) running.stopDiscovery();
    else running.handler.post(running.retry);
  }

  private BluetoothLeAdvertiser advertiser;
  private AdvertiseCallback advertising;
  private boolean mayEnableBluetooth = true;
  private final BroadcastReceiver stateChanges = new BroadcastReceiver() {
    public void onReceive(Context c, Intent intent) {
      String action = intent.getAction();
      if (Intent.ACTION_SCREEN_OFF.equals(action)) {
        handler.removeCallbacks(retry);
        closeEndpoint();
      } else {
        if (Intent.ACTION_SCREEN_ON.equals(action)) mayEnableBluetooth = true;
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) &&
            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) stopDiscovery();
        refresh();
      }
    }
  };

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
    running = this;
    IntentFilter states = new IntentFilter(Intent.ACTION_SCREEN_ON);
    states.addAction(Intent.ACTION_SCREEN_OFF);
    states.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
    registerReceiver(stateChanges, states);
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
    if (!ScreenPower.interactive(this)) { closeEndpoint(); return; }
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
    if (endpoint!=null && ip==address && activeNetwork.equals(selected) && Arrays.equals(key,activeBinding)) {
      startDiscovery();
      return;
    }
    closeEndpoint();
    if (ip == 0 || key == null) return;
    try {
      endpoint = new ManagementEndpoint(this, ip, key, true);
      address = ip;
      activeNetwork = selected;
      activeBinding = key;
      startDiscovery();
    } catch (Exception e) {
      handler.postDelayed(retry, 10000);
    }
  }
  private void startDiscovery() {
    if (setupOpen || advertising != null) return;
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) return;
    try {
      if (!adapter.isEnabled()) {
        if (mayEnableBluetooth && adapter.getState() == BluetoothAdapter.STATE_OFF) {
          mayEnableBluetooth = false;
          adapter.enable();
        }
        return;
      }
      mayEnableBluetooth = false;
      advertiser = adapter.getBluetoothLeAdvertiser();
      if (advertiser == null) return;
      ParcelUuid uuid = ParcelUuid.fromString(BleProbeProtocol.discoveryUuid(deviceName(this), address));
      advertising = new AdvertiseCallback() {
        public void onStartFailure(int code) {
          if (this != advertising) return;
          stopDiscovery();
          if (code != ADVERTISE_FAILED_FEATURE_UNSUPPORTED) handler.postDelayed(retry, 10000);
        }
      };
      advertiser.startAdvertising(new AdvertiseSettings.Builder()
              .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setConnectable(false).build(),
          new AdvertiseData.Builder().addServiceUuid(uuid).build(), advertising);
    } catch (RuntimeException e) {
      stopDiscovery();
      handler.postDelayed(retry, 10000);
    }
  }
  private void stopDiscovery() {
    if (advertiser != null && advertising != null) {
      try { advertiser.stopAdvertising(advertising); } catch (RuntimeException ignored) {}
    }
    advertiser = null;
    advertising = null;
  }
  private void closeEndpoint() {
    stopDiscovery();
    if (endpoint != null) endpoint.close();
    endpoint = null;
    if (activeBinding != null) Arrays.fill(activeBinding, (byte)0);
    activeBinding = null;
  }
  public IBinder onBind(Intent intent) { return null; }
  public void onDestroy() {
    destroyed=true;
    running = null;
    unregisterReceiver(stateChanges);
    connectivity.unregisterNetworkCallback(changes);
    handler.removeCallbacks(retry);
    closeEndpoint();
    super.onDestroy();
  }
}
