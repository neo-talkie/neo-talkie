package com.neotalkie.audiolink;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

final class MxB5AudioRole {
  private static final String PROPERTY = "persist.service.bt.hfp.client";

  private static String readRole() throws IOException, InterruptedException {
    Process process = new ProcessBuilder("/system/bin/getprop", PROPERTY).start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
      String value = reader.readLine();
      if (process.waitFor() != 0 || value == null) throw new IOException("读取蓝牙配置失败");
      return value.trim();
    }
  }

  private static void waitFor(BluetoothAdapter adapter, int state) throws IOException, InterruptedException {
    long end = SystemClock.elapsedRealtime() + 15000;
    while (adapter.getState() != state) {
      if (SystemClock.elapsedRealtime() >= end) throw new IOException("蓝牙切换超时，请重试");
      Thread.sleep(150);
    }
  }

  static void apply(Context context, boolean mic) throws IOException, InterruptedException {
    SharedPreferences prefs = context.getSharedPreferences("role", Context.MODE_PRIVATE);
    // Older APKs only persisted "applied". Treat an active legacy role as a pending restore.
    if (!mic && !prefs.contains("wasEnabled") && !"true".equals(prefs.getString("applied", ""))) return;
    if (!"mx_b5".equalsIgnoreCase(Build.MODEL) || Build.VERSION.SDK_INT != 22)
      throw new IOException("此版本适用于 mx_b5 安卓 5.1 手台");
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) throw new IOException("设备没有蓝牙适配器");
    if (mic && !prefs.contains("wasEnabled")) {
      int initial = adapter.getState();
      boolean wasEnabled = initial == BluetoothAdapter.STATE_ON || initial == BluetoothAdapter.STATE_TURNING_ON;
      if (!prefs.edit().putBoolean("wasEnabled", wasEnabled).commit())
        throw new IOException("无法保存原蓝牙状态");
    }
    boolean powerOn = mic || prefs.getBoolean("wasEnabled", true);
    String target = Boolean.toString(mic);
    boolean change = !target.equals(readRole());
    // Remember incomplete restarts across process death. First launch also checks by restarting.
    boolean restart = change || !target.equals(prefs.getString("applied", ""));
    if (change) {
      if (!prefs.edit().remove("applied").commit()) throw new IOException("无法记录切换状态");
      Process write = new ProcessBuilder("/system/bin/setprop", PROPERTY, target).start();
      if (write.waitFor() != 0 || !target.equals(readRole()))
        throw new IOException("系统未允许应用修改蓝牙角色");
    }

    int state = adapter.getState();
    if (state == BluetoothAdapter.STATE_TURNING_OFF) waitFor(adapter, BluetoothAdapter.STATE_OFF);
    if (state == BluetoothAdapter.STATE_TURNING_ON) waitFor(adapter, BluetoothAdapter.STATE_ON);
    if ((restart || !powerOn) && adapter.isEnabled()) {
      if (!adapter.disable()) throw new IOException("系统未允许关闭蓝牙");
      waitFor(adapter, BluetoothAdapter.STATE_OFF);
    }
    if (powerOn && !adapter.isEnabled()) {
      if (!adapter.enable()) throw new IOException("系统未允许开启蓝牙");
      waitFor(adapter, BluetoothAdapter.STATE_ON);
    }
    SharedPreferences.Editor saved = prefs.edit().putString("applied", target);
    if (!mic) saved.remove("wasEnabled");
    if (!saved.commit())
      throw new IOException("无法记录已应用的模式");
  }
}
