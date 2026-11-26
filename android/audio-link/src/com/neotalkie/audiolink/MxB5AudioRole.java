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
  // Android 5.1 HFP Client uses this hidden AudioManager stream for speaker gain.
  static final int STREAM_BLUETOOTH_SCO = 6;
  private static final String HFP = "persist.service.bt.hfp.client";

  private static final String A2DP = "persist.service.bt.a2dp.sink";

  private static String readRole(String property) throws IOException, InterruptedException {
    Process process = new ProcessBuilder("/system/bin/getprop", property).start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
      String value = reader.readLine();
      if (process.waitFor() != 0 || value == null) throw new IOException("读取蓝牙配置失败");
      return value.trim();
    }
  }

  private static void writeRole(String property, String current, String target) throws IOException, InterruptedException {
    if (target.equals(current)) return;
    Process write = new ProcessBuilder("/system/bin/setprop", property, target).start();
    if (write.waitFor() != 0 || !target.equals(readRole(property)))
      throw new IOException("系统未允许应用修改" + (HFP.equals(property) ? "通话" : "媒体") + "角色");
  }

  private static void waitFor(BluetoothAdapter adapter, int state) throws IOException, InterruptedException {
    long end = SystemClock.elapsedRealtime() + 15000;
    while (adapter.getState() != state) {
      if (SystemClock.elapsedRealtime() >= end) throw new IOException("蓝牙切换超时，请重试");
      Thread.sleep(150);
    }
  }

  static void apply(Context context, boolean open) throws IOException, InterruptedException {
    SharedPreferences prefs = context.getSharedPreferences("role", Context.MODE_PRIVATE);
    try {
      switchRoles(prefs, open);
    } catch (IOException | SecurityException e) {
      if (open && prefs.contains("originalA2dp")) {
        try {
          switchRoles(prefs, false);
        } catch (IOException | SecurityException restore) {
          e.addSuppressed(restore);
        }
      }
      throw e;
    }
  }

  private static void switchRoles(SharedPreferences prefs, boolean open) throws IOException, InterruptedException {
    boolean legacySession = prefs.contains("wasEnabled") || "true".equals(prefs.getString("applied", ""));
    if (!open && !legacySession) return;
    if (!"mx_b5".equalsIgnoreCase(Build.MODEL) || Build.VERSION.SDK_INT != 22)
      throw new IOException("此版本适用于 mx_b5 安卓 5.1 手台");
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) throw new IOException("设备没有蓝牙适配器");
    String hfp = readRole(HFP);
    String a2dp = readRole(A2DP);
    if (!prefs.contains("originalA2dp")) {
      int initial = adapter.getState();
      boolean wasEnabled = initial == BluetoothAdapter.STATE_ON || initial == BluetoothAdapter.STATE_TURNING_ON;
      // Older APKs owned HFP only and restored it to false.
      if (!prefs.edit().putBoolean("wasEnabled", prefs.getBoolean("wasEnabled", legacySession || wasEnabled))
          .putString("originalHfp", legacySession ? "false" : hfp)
          .putString("originalA2dp", a2dp).remove("applied").commit())
        throw new IOException("无法保存原蓝牙状态");
    }
    boolean powerOn = open || prefs.getBoolean("wasEnabled", true);
    String hfpTarget = open ? "true" : prefs.getString("originalHfp", "false");
    String a2dpTarget = open ? "true" : prefs.getString("originalA2dp", "");
    boolean change = !hfp.equals(hfpTarget) || !a2dp.equals(a2dpTarget);
    boolean restart = change || !Boolean.toString(open).equals(prefs.getString("applied", ""));
    if (change) {
      // Invalidate before either write so a partial switch is retried even after process death.
      if (!prefs.edit().remove("applied").commit()) throw new IOException("无法记录切换状态");
      writeRole(HFP, hfp, hfpTarget);
      writeRole(A2DP, a2dp, a2dpTarget);
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
    SharedPreferences.Editor saved = prefs.edit().putString("applied", Boolean.toString(open));
    if (!open) saved.remove("wasEnabled").remove("originalHfp").remove("originalA2dp");
    if (!saved.commit())
      throw new IOException("无法记录已应用的模式");
  }
}
