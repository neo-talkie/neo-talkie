package com.neotalkie.device;

import android.content.*;
import android.graphics.*;
import android.net.*;
import android.net.wifi.*;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.view.View;

/** Broadcast-driven header: no polling, timers or cloud traffic. Coordinates are 160 x 128. */
final class StatusStrip extends BroadcastReceiver {
  private final Context context;
  private final View view;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private String time = "", network = UiContract.T_TEXT_095;
  private Bitmap clock;
  private int battery = -1;
  private int bars = -1;
  private boolean registered, charging;
  private static final int[] BOLT = UiContract.BOLT;

  StatusStrip(Context context, View view) {
    this.context = context;
    this.view = view;
  }

  void start() {
    if (!registered) {
      IntentFilter f = new IntentFilter();
      f.addAction(Intent.ACTION_TIME_TICK);
      f.addAction(Intent.ACTION_TIME_CHANGED);
      f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
      f.addAction(WifiManager.RSSI_CHANGED_ACTION);
      f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
      f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
      f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
      f.addAction(Intent.ACTION_BATTERY_CHANGED);
      Intent sticky = context.registerReceiver(this, f);
      if (sticky != null) readBattery(sticky);
      registered = true;
    }
    refresh();
  }

  void stop() {
    if (registered) context.unregisterReceiver(this);
    registered = false;
  }

  public void onReceive(Context c, Intent intent) {
    if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) readBattery(intent);
    refresh();
  }

  private void readBattery(Intent intent) {
    int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
    int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 0);
    int status =
        intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
    int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
    charging =
        status == BatteryManager.BATTERY_STATUS_CHARGING
            || (status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0);
    battery = level < 0 || scale <= 0 ? -1 : Math.min(100, level * 100 / scale);
  }

  private void refresh() {
    String nextTime = DateFormat.format("HH:mm", System.currentTimeMillis()).toString();
    if (!nextTime.equals(time)) {
      time = nextTime;
      clock = PixelArt.numbers(time, 4, Color.WHITE);
    }
    NetworkInfo active =
        ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE))
            .getActiveNetworkInfo();
    network = UiContract.T_TEXT_095;
    bars = -1;
    if (active != null && active.isConnected()) {
      if (active.getType() == ConnectivityManager.TYPE_WIFI) {
        WifiInfo wifi =
            ((WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                .getConnectionInfo();
        network = "WiFi";
        if (wifi != null && wifi.getRssi() > -127)
          bars = WifiManager.calculateSignalLevel(wifi.getRssi(), 5);
      } else network = active.getType() == ConnectivityManager.TYPE_MOBILE ? UiContract.T_TEXT_097 : UiContract.T_TEXT_036;
    }
    view.invalidate();
  }

  void draw(Canvas canvas) {
    if (clock != null) canvas.drawBitmap(clock, 6, 3, null);
    if (network.equals("WiFi")) {
      canvas.drawBitmap(PixelArt.WIFI[Math.max(0, bars)], 103, 4, null);
    } else {
      paint.setColor(Color.WHITE);
      paint.setTextSize(14);
      canvas.drawText(
          network.equals(UiContract.T_TEXT_097) ? UiContract.T_TEXT_096 : network.equals(UiContract.T_TEXT_036) ? UiContract.T_TEXT_110 : UiContract.T_TEXT_095, 103, 19, paint);
    }
    paint.setColor(Color.WHITE);
    canvas.drawRect(87, 3, 91, 5, paint);
    canvas.drawRect(84, 5, 94, 22, paint);
    paint.setColor(UiContract.C_BG);
    canvas.drawRect(86, 7, 92, 20, paint);
    paint.setColor(Color.WHITE);
    if (battery > 0) canvas.drawRect(86, 20 - Math.max(1, battery * 13 / 100), 92, 20, paint);
    // The adjacent bolt preserves the battery fill, including at full charge.
    if (charging) {
      for (int y = 0; y < BOLT.length; y++)
        for (int x = 0; x < 6; x++)
          if ((BOLT[y] & (1 << (5 - x))) != 0) canvas.drawRect(96 + x, 7 + y, 97 + x, 8 + y, paint);
    }
    canvas.drawRect(6, 26, 154, 27, paint);
  }
}
