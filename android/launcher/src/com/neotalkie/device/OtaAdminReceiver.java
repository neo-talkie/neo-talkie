package com.neotalkie.device;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Silent OTA privilege helper.
 *
 * <p>On Android 5.1 (API 22), PackageInstaller requires the privileged INSTALL_PACKAGES permission
 * (granted automatically when the launcher APK is pre-installed in /system/priv-app/). On Android
 * 6.0+ (API 23+), Device Owner status also grants silent PackageInstaller installs.
 */
public final class OtaAdminReceiver extends DeviceAdminReceiver {
  private static final String PERM_INSTALL_PACKAGES = "android.permission.INSTALL_PACKAGES";

  static ComponentName component(Context context) {
    return new ComponentName(context, OtaAdminReceiver.class);
  }

  static boolean isSilentInstallReady(Context context) {
    if (context.checkCallingOrSelfPermission(PERM_INSTALL_PACKAGES)
        == PackageManager.PERMISSION_GRANTED) {
      return true;
    }
    if (Build.VERSION.SDK_INT >= 23) {
      DevicePolicyManager dpm =
          (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
      return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }
    return false;
  }
}
