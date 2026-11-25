package com.neotalkie.device;

import android.app.Activity;
import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

/** Android 5.1: use real system display sleep, never a black overlay or an idle CPU lock. */
final class ScreenPower {
  static boolean interactive(Context context) {
    return ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).isInteractive();
  }

  static void configure(Activity activity) {
    try {
      if (Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0)
          != UiContract.IDLE_SCREEN_MS) {
        if (!Settings.System.putInt(activity.getContentResolver(),
            Settings.System.SCREEN_OFF_TIMEOUT, UiContract.IDLE_SCREEN_MS))
          Log.w("NeoTalkiePower", "System rejected screen timeout");
      }
    } catch (SecurityException e) {
      Log.w("NeoTalkiePower", "Screen timeout requires system settings access");
    }
    touch(activity, false);
  }

  // ON_AFTER_RELEASE refreshes the system inactivity timer, including ROM broadcast-only keys.
  // Wake only for an explicit PTT edge, never for completion callbacks or background work.
  static void touch(Context context, boolean wake) {
    if (!wake && !interactive(context)) return;
    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    int flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE;
    if (wake) flags |= PowerManager.ACQUIRE_CAUSES_WAKEUP;
    PowerManager.WakeLock pulse = pm.newWakeLock(flags, "NeoTalkie:screen-pulse");
    pulse.acquire(1000);
    pulse.release();
  }

  static void busy(Activity activity, boolean busy) {
    if (busy) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else {
      boolean wasBusy = (activity.getWindow().getAttributes().flags
          & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
      activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      if (wasBusy) touch(activity, false);
    }
  }
}
