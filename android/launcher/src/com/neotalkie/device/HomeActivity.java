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
    String cachedPage = "";
    Bitmap pageNumber;

    HomePanel() {
      super(HomeActivity.this);
      setFocusable(true);
    }

    protected void onDraw(Canvas canvas) {
      canvas.save();
      canvas.scale(getWidth() / 160f, getHeight() / 128f);
      canvas.drawColor(Color.rgb(13, 24, 30));
      statusStrip.draw(canvas);
      paint.setTextAlign(Paint.Align.CENTER);
      if (!entries.isEmpty()) {
        Entry entry = entries.get(selected);
        if (entry.intent.getComponent() != null
            && DeviceRoutes.TALK.equals(entry.intent.getComponent().getPackageName())) {
          paint.setColor(UiContract.C_HOME_ICON);
          canvas.drawRect(56, 31, 104, 79, paint);
          canvas.drawBitmap(PixelArt.HOME_MARK, 58, 39, null);
        } else {
          entry.icon.setBounds(56, 31, 104, 79);
          entry.icon.draw(canvas);
        }
        paint.setColor(Color.WHITE);
        paint.setTextSize(22);
        String label = entry.label;
        while (paint.measureText(label) > 150 && label.length() > 1)
          label = label.substring(0, label.length() - 1);
        canvas.drawText(label, 80, 98, paint);
        String page = (selected + 1) + "/" + entries.size();
        if (!page.equals(cachedPage)) {
          cachedPage = page;
          pageNumber = PixelArt.numbers(page, 2, UiContract.C_ACCENT);
        }
        canvas.drawBitmap(pageNumber, 154 - pageNumber.getWidth(), 33, null);
        paint.setColor(UiContract.C_VOLUME_BG);
        canvas.drawRect(0, 104, 160, 128, paint);
        paint.setTextSize(14);
        paint.setColor(UiContract.C_ACCENT);
        canvas.drawText(UiContract.T_TEXT_009, 80, 122, paint);
        setContentDescription(entry.label + UiContract.T_TEXT_151 + (selected + 1) + UiContract.T_TEXT_149 + entries.size() + UiContract.T_TEXT_148);
      }
      volumeStrip.draw(canvas);
      canvas.restore();
    }

    public boolean onTouchEvent(android.view.MotionEvent event) {
      if (event.getAction() == MotionEvent.ACTION_UP) {
        open(selected);
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
