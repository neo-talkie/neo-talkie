package com.neotalkie.device;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.Toast;
import java.util.*;

/** Optional small-screen home: the vendor home crashes when Zello is absent. */
public final class HomeActivity extends Activity {
  private final ArrayList<Entry> entries = new ArrayList<>();
  private int selected;
  private long lastMove;
  private HomePanel panel;
  private StatusStrip statusStrip;
  private VolumeStrip volumeStrip;

  private static final class Entry {
    final String label;
    final Intent intent;
    final Drawable icon;

    Entry(String label, Intent intent, Drawable icon) {
      this.label = label;
      this.intent = intent;
      this.icon = icon;
    }
  }

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    panel = new HomePanel();
    statusStrip = new StatusStrip(this, panel);
    volumeStrip = new VolumeStrip(this, panel);
    setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
    setContentView(panel);
  }

  protected void onResume() {
    super.onResume();
    PttNavigation.resume(this);
    statusStrip.start();
    PackageManager pm = getPackageManager();
    entries.clear();
    add(UiContract.T_TEXT_005, DeviceRoutes.intent(DeviceRoutes.TALK, "MainActivity"));
    add(UiContract.T_DEVICE_SETTINGS, new Intent(this, StatusActivity.class));
    add(UiContract.T_TEXT_102, new Intent(Settings.ACTION_SETTINGS));
    add(UiContract.T_BLE_ENTRY, new Intent(this, BleSetupActivity.class));
    /* Vendor settings are available through Android Settings.
    add(
        UiContract.T_TEXT_030,
        new Intent(Intent.ACTION_VIEW)
            .setComponent(
                new ComponentName(
                    "com.android.settings", "com.android.settings.StatusBarSettings")));
    */
    Set<String> hidden =
        new HashSet<>(
            Arrays.asList(
                getPackageName(),
                DeviceRoutes.TALK,
                "com.android.settings",
                "com.youdo.simple.launcher",
                "com.innopath.activecare",
                "com.qualcomm.qct.dlt",
                "com.android.dialer",
                "com.android.contacts",
                "com.example.fly.testmode510",
                "com.smartship.inputmethod",
                "com.iflytek.speechcloud",
                "com.verizon.dmclientupdate",
                "com.android.music",
                "com.quectel.otatest",
                "com.youdo.inputmethod",
                "com.youdotech.ble",
                "com.android.mms"));
    List<ResolveInfo> apps =
        pm.queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
    Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));
    for (ResolveInfo app : apps) {
      String pkg = app.activityInfo.packageName;
      if (!hidden.add(pkg)) continue;
      entries.add(
          new Entry(
              app.loadLabel(pm).toString(),
              new Intent(Intent.ACTION_MAIN)
                  .addCategory(Intent.CATEGORY_LAUNCHER)
                  .setComponent(new ComponentName(pkg, app.activityInfo.name)),
              app.loadIcon(pm)));
    }
    selected = Math.min(selected, entries.size() - 1);
    panel.invalidate();
  }

  protected void onPause() {
    PttNavigation.pause(this);
    statusStrip.stop();
    volumeStrip.hide();
    super.onPause();
  }

  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    selected = 0;
    panel.invalidate();
  }

  private void add(String label, Intent intent) {
    ResolveInfo r = getPackageManager().resolveActivity(intent, 0);
    if (r != null) entries.add(new Entry(label, intent, r.loadIcon(getPackageManager())));
  }

  private void open(int index) {
    if (index < 0 || index >= entries.size()) return;
    try {
      Intent target = new Intent(entries.get(index).intent);
      // Internal pages belong above HOME; resetting this task would reopen its root.
      if (target.getComponent() == null || !getPackageName().equals(target.getComponent().getPackageName()))
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
      startActivity(target);
    } catch (ActivityNotFoundException | SecurityException e) {
      Toast.makeText(this, UiContract.T_TEXT_038, Toast.LENGTH_SHORT).show();
    }
  }

  public boolean dispatchKeyEvent(KeyEvent e) {
    if (PttNavigation.key(this, e)) return true;
    if (volumeStrip.handleKey(e, false)) return true;
    int k = e.getKeyCode();
    boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
    if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
      if (!down) open(selected);
      return true;
    }
    int direction = 0;
    if (k == KeyEvent.KEYCODE_DPAD_LEFT
        || k == KeyEvent.KEYCODE_DPAD_UP
        || k == KeyEvent.KEYCODE_F7) direction = -1;
    if (k == KeyEvent.KEYCODE_DPAD_RIGHT
        || k == KeyEvent.KEYCODE_DPAD_DOWN
        || k == KeyEvent.KEYCODE_F8) direction = 1;
    if (direction != 0) {
      if (down
          && e.getRepeatCount() == 0
          && SystemClock.elapsedRealtime() - lastMove > 250
          && !entries.isEmpty()) {
        lastMove = SystemClock.elapsedRealtime();
        selected = (selected + direction + entries.size()) % entries.size();
        panel.invalidate();
      }
      return true;
    }
    if (k == KeyEvent.KEYCODE_BACK) return true;
    return super.dispatchKeyEvent(e);
  }

  final class HomePanel extends View {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    float downY;

    HomePanel() {
      super(HomeActivity.this);
      setFocusable(true);
    }

    void text(Canvas c, String s, float x, float y, int size, int color, int width) {
      paint.setTextSize(size);
      paint.setColor(color);
      if (paint.measureText(s) > width) {
        int end = paint.breakText(s, true, width - paint.measureText("…"), null);
        s = s.substring(0, end) + "…";
      }
      c.drawText(s, x, y, paint);
    }

    protected void onDraw(Canvas canvas) {
      canvas.save();
      canvas.scale(getWidth() / 160f, getHeight() / 128f);
      canvas.drawColor(UiContract.C_BG);
      statusStrip.draw(canvas);
      paint.setTextAlign(Paint.Align.LEFT);
      if (!entries.isEmpty()) {
        int visible = UiContract.MENU_VISIBLE_ROWS;
        int first = Math.max(0, Math.min(selected - visible + 1, entries.size() - visible));
        int activeRow = selected - first;
        paint.setColor(UiContract.C_SELECTION);
        canvas.drawRect(
            UiContract.LEFT,
            UiContract.MENU_TOP + activeRow * UiContract.MENU_ROW_HEIGHT,
            UiContract.RIGHT,
            UiContract.MENU_TOP + (activeRow + 1) * UiContract.MENU_ROW_HEIGHT,
            paint);
        for (int row = 0; row < visible && first + row < entries.size(); row++) {
          text(
              canvas,
              entries.get(first + row).label,
              UiContract.MENU_TEXT_X,
              UiContract.MENU_BASELINE + row * UiContract.MENU_ROW_HEIGHT,
              UiContract.MENU_FONT,
              row == activeRow ? UiContract.C_WHITE : UiContract.C_INK,
              UiContract.RIGHT - UiContract.MENU_TEXT_X - 4);
        }
        if (entries.size() > visible) {
          int trackY = UiContract.MENU_TOP;
          int trackH = visible * UiContract.MENU_ROW_HEIGHT;
          int thumbH = Math.max(12, Math.round((float) trackH * visible / entries.size()));
          int thumbY =
              trackY + Math.round((float) (trackH - thumbH) * first / (entries.size() - visible));
          paint.setColor(0xff22302d);
          canvas.drawRect(156, trackY, 158, trackY + trackH, paint);
          paint.setColor(0xff7b8d89);
          canvas.drawRect(156, thumbY, 158, thumbY + thumbH, paint);
        }
        paint.setColor(UiContract.C_WAIT_BG);
        canvas.drawRect(0, UiContract.STATUS_FOOTER_TOP, UiContract.WIDTH, UiContract.HEIGHT, paint);
        paint.setColor(UiContract.C_WAIT_EDGE);
        canvas.drawRect(
            0, UiContract.STATUS_FOOTER_TOP, UiContract.WIDTH, UiContract.STATUS_FOOTER_TOP + 1, paint);
        text(
            canvas,
            UiContract.T_MENU_ENTER,
            UiContract.STATUS_HINT_X,
            UiContract.STATUS_FOOTER_Y,
            UiContract.STATUS_HINT_FONT,
            UiContract.C_INK,
            UiContract.STATUS_HINT_EXIT_X - UiContract.STATUS_HINT_X);
        paint.setTextSize(UiContract.STATUS_HINT_FONT);
        float switchTextX = UiContract.RIGHT - paint.measureText(UiContract.T_SWITCH_FEATURE);
        int arrowGap =
            UiContract.STATUS_HINT_TEXT_X - UiContract.STATUS_HINT_X - UiContract.STATUS_ARROW_WIDTH;
        int switchArrowX = Math.round(switchTextX - arrowGap - UiContract.STATUS_ARROW_WIDTH);
        canvas.drawBitmap(PixelArt.PAGE_ARROWS, switchArrowX, UiContract.STATUS_ARROW_Y, null);
        text(
            canvas,
            UiContract.T_SWITCH_FEATURE,
            switchTextX,
            UiContract.STATUS_FOOTER_Y,
            UiContract.STATUS_HINT_FONT,
            UiContract.C_INK,
            UiContract.WIDTH - UiContract.STATUS_HINT_EXIT_X);
        Entry entry = entries.get(selected);
        setContentDescription(entry.label + UiContract.T_TEXT_151 + (selected + 1) + UiContract.T_TEXT_149 + entries.size() + UiContract.T_TEXT_148);
      }
      volumeStrip.draw(canvas);
      canvas.restore();
    }

    public boolean onTouchEvent(android.view.MotionEvent event) {
      float y = event.getY() * 128f / getHeight();
      if (event.getAction() == MotionEvent.ACTION_DOWN) {
        downY = y;
        return true;
      }
      if (event.getAction() == MotionEvent.ACTION_UP) {
        if (Math.abs(y - downY) > 20 && !entries.isEmpty()) {
          selected = (selected + (y < downY ? 1 : -1) + entries.size()) % entries.size();
          panel.invalidate();
        } else {
          open(selected);
        }
        performClick();
      }
      return true;
    }

    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
