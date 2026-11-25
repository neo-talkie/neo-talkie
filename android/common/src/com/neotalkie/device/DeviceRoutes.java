package com.neotalkie.device;

import android.app.Activity;
import android.content.*;
import android.widget.Toast;

/** Explicit, version-independent destinations between the two installed apps. */
final class DeviceRoutes {
  static final String TALK = "com.neotalkie.talk", LAUNCHER = "com.neotalkie.launcher";
  static Intent intent(String app, String screen) {
    return new Intent().setComponent(new ComponentName(app, "com.neotalkie.device." + screen));
  }
  static void open(Activity activity, String app, String screen, String page) {
    try { activity.startActivity(intent(app, screen).putExtra("page", page)); }
    catch (ActivityNotFoundException | SecurityException e) {
      Toast.makeText(activity, UiContract.T_TEXT_038, Toast.LENGTH_SHORT).show();
    }
  }
}
