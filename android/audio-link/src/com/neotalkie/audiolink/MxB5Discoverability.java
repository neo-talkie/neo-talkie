package com.neotalkie.audiolink;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.lang.reflect.Method;

/** The mx_b5 system API is absent from the public Android SDK. */
final class MxB5Discoverability {
  static final int SECONDS = 120;

  static boolean request(Context context, BluetoothAdapter adapter) {
    if (!"mx_b5".equalsIgnoreCase(Build.MODEL) || Build.VERSION.SDK_INT != 22
        || context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED) return false;
    try {
      Method setScanMode = BluetoothAdapter.class.getMethod("setScanMode", int.class, int.class);
      return Boolean.TRUE.equals(setScanMode.invoke(adapter,
          BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, SECONDS));
    } catch (ReflectiveOperationException | SecurityException e) {
      Log.w("AudioLink", "System discoverability request unavailable", e);
      return false;
    }
  }

  private MxB5Discoverability() {}
}
