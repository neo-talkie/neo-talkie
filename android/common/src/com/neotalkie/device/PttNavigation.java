package com.neotalkie.device;

import android.app.Activity;
import android.content.*;
import android.view.KeyEvent;
import android.util.Log;
import java.lang.ref.WeakReference;

/** One ROM receiver; retain the last NeoTalkie page only when paused by display sleep. */
final class PttNavigation {
  interface TalkPage { void press(); void release(); }
  static final PttLease lease = new PttLease();
  private static String remoteToken = "";
  private static final android.net.Uri LEASE_URI = android.net.Uri.parse("content://com.neotalkie.launcher.ptt");
  private static final PttHandoff handoff = new PttHandoff();
  private static WeakReference<Activity> foreground = new WeakReference<Activity>(null);
  private static WeakReference<Activity> sleeping = new WeakReference<Activity>(null);
  private static boolean registered;
  static synchronized void finishLease(String token) {
    if (lease.release(token)) handoff.up();
  }
  static synchronized void resume(Activity activity) {
    if (!registered) {
      IntentFilter filter = new IntentFilter();
      filter.addAction("com.zello.ptt.down");
      filter.addAction("com.zello.ptt.up");
      filter.addAction(Intent.ACTION_SCREEN_ON);
      activity.getApplicationContext().registerReceiver(new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            // Hardware can wake the panel before delivering its PTT broadcast. Keep a short
            // handoff window, but do not retain a sleeping owner after another app takes over.
            new android.os.Handler().postDelayed(new Runnable() {
              public void run() { sleeping.clear(); }
            }, 2000);
            return;
          }
          Activity current = foreground.get();
          if ("com.zello.ptt.up".equals(intent.getAction())) up(current);
          else {
            if (current == null) current = sleeping.get();
            if (current != null && !current.isFinishing()) down(current);
          }
        }
      }, filter);
      registered = true;
    }
    foreground = new WeakReference<Activity>(activity);
    sleeping.clear();
    ScreenPower.configure(activity);
    if (activity instanceof TalkPage) {
      String token = activity.getIntent().getStringExtra("pttToken");
      activity.getIntent().removeExtra("pttToken");
      if (token != null) {
        try {
          android.os.Bundle state = activity.getContentResolver().call(LEASE_URI, "held", token, null);
          if (state != null && state.getBoolean("held") && handoff.down(true)) {
            remoteToken = token;
            ((TalkPage)activity).press();
          }
        } catch (RuntimeException e) { Log.w("NeoTalkiePower", "PTT handoff unavailable"); }
      } else if (handoff.resumeTalk()) ((TalkPage)activity).press();
    }
  }
  static synchronized void pause(Activity activity) {
    if (foreground.get() == activity) {
      if (!ScreenPower.interactive(activity)) sleeping = new WeakReference<Activity>(activity);
      else sleeping.clear();
      foreground.clear();
      handoff.pause();
    }
  }
  static synchronized boolean key(Activity activity, KeyEvent event) {
    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
      ScreenPower.touch(activity, false);
    if (event.getKeyCode() != KeyEvent.KEYCODE_F1) return false;
    if (event.getAction() == KeyEvent.ACTION_UP) up(foreground.get());
    else if (event.getRepeatCount() == 0) down(activity);
    return true;
  }
  private static synchronized void down(Activity activity) {
    boolean ready = activity instanceof TalkPage && foreground.get() == activity;
    if (!handoff.down(ready)) return;
    Log.i("NeoTalkiePower", "PTT down interactive=" + ScreenPower.interactive(activity)
        + " direct=" + ready);
    ScreenPower.touch(activity, true);
    if (ready) ((TalkPage)activity).press();
    else {
      Intent target = DeviceRoutes.intent(DeviceRoutes.TALK, "MainActivity")
          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      if (DeviceRoutes.LAUNCHER.equals(activity.getPackageName())) {
        String token = java.util.UUID.randomUUID().toString();
        lease.begin(token, android.os.SystemClock.elapsedRealtime());
        target.putExtra("pttToken", token);
      }
      try { activity.startActivity(target); }
      catch (ActivityNotFoundException | SecurityException e) {
        handoff.up(); lease.release();
        android.widget.Toast.makeText(activity, UiContract.T_TEXT_038, android.widget.Toast.LENGTH_SHORT).show();
      }
    }
  }
  private static synchronized void up(Activity activity) {
    handoff.up();
    lease.release();
    if (activity != null && !remoteToken.isEmpty()) {
      try { activity.getContentResolver().call(LEASE_URI, "release", remoteToken, null); }
      catch (RuntimeException ignored) {}
      remoteToken = "";
    }
    if (activity != null) ScreenPower.touch(activity, false);
    if (activity instanceof TalkPage) ((TalkPage)activity).release();
  }
}
