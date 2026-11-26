package com.neotalkie.audiolink;

import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;

/** Only the mx_b5 framework implements this version of the HID peripheral API. */
final class MxB5Keyboard implements BluetoothProfile.ServiceListener {
  private static final int GLOBE = 1;
  private static final int PROFILE = 17;
  private static final byte[] REPORT = {
      5, 1, 9, 6, (byte) 0xa1, 1, (byte) 0x85, 1, // Keyboard, report 1
      5, 12, 10, (byte) 0x9d, 2, 21, 0, 37, 1, 117, 1, (byte) 0x95, 1, (byte) 0x81, 2, // Globe
      117, 7, (byte) 0x95, 1, (byte) 0x81, 3, (byte) 0xc0
  };
  private final Handler main;
  private final Runnable changed;
  private final BluetoothAdapter adapter;
  private BluetoothHidDevice proxy;
  private BluetoothHidDeviceAppConfiguration config;
  private boolean connected, closed;

  MxB5Keyboard(Context context, Handler main, Runnable changed) {
    this.main = main;
    this.changed = changed;
    adapter = BluetoothAdapter.getDefaultAdapter();
    adapter.getProfileProxy(context, this, PROFILE);
  }

  public void onServiceConnected(int profile, BluetoothProfile service) {
    BluetoothHidDevice hid = (BluetoothHidDevice) service;
    if (closed) { adapter.closeProfileProxy(PROFILE, hid); return; }
    proxy = hid;
    hid.registerApp(new BluetoothHidDeviceAppSdpSettings("NeoTalkie Audio Link", "PTT keyboard",
        "NeoTalkie", (byte) 64, REPORT), null, null, new BluetoothHidDeviceCallback() {
      public void onAppStatusChanged(BluetoothDevice host, BluetoothHidDeviceAppConfiguration app, boolean ok) {
        main.post(() -> {
          if (closed || hid != proxy) { if (ok) hid.unregisterApp(app); return; }
          config = ok ? app : null;
          if (!ok) connected = false;
          else if (host != null && !connected) hid.connect();
          changed.run();
        });
      }
      public void onConnectionStateChanged(BluetoothDevice host, int state) {
        main.post(() -> {
          if (closed || hid != proxy) return;
          connected = state == BluetoothProfile.STATE_CONNECTED;
          if (connected) send(0);
          changed.run();
        });
      }
    });
  }
  public void onServiceDisconnected(int profile) {
    proxy = null; config = null; connected = false;
    if (!closed) changed.run();
  }
  boolean ready() { return connected && proxy != null; }
  boolean fn(boolean down) { return send(down ? GLOBE : 0); }
  private boolean send(int bits) {
    return ready() && proxy.sendReport(1, new byte[]{(byte) bits});
  }
  void close() {
    closed = true;
    if (proxy != null) {
      send(0);
      if (config != null) proxy.unregisterApp(config);
      adapter.closeProfileProxy(PROFILE, proxy);
    }
    proxy = null; connected = false;
  }
}
