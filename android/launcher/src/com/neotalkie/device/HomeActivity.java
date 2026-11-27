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
    final String description;
    final String iconKind;
    final Intent intent;
    final Drawable icon;

    Entry(String label, String description, String iconKind, Intent intent, Drawable icon) {
      this.label = label;
      this.description = description;
      this.iconKind = iconKind;
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
    statusStrip = new StatusStrip(this, panel, StatusStrip.Mode.COMPACT);
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
    add(
        UiContract.T_TEXT_005,
        UiContract.T_HOME_DESC_TALK,
        "talk",
        DeviceRoutes.intent(DeviceRoutes.TALK, "MainActivity"));
    add(
        UiContract.T_DEVICE_SETTINGS,
        UiContract.T_HOME_DESC_DEVICE,
        "device",
        new Intent(this, StatusActivity.class));
    add(
        UiContract.T_TEXT_102,
        UiContract.T_HOME_DESC_ANDROID,
        "android",
        new Intent(Settings.ACTION_SETTINGS));
    add(
        UiContract.T_BLE_ENTRY,
        UiContract.T_HOME_DESC_BLE,
        "ble",
        new Intent(this, BleSetupActivity.class));
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
      boolean audioLink = "com.neotalkie.hfpmic".equals(pkg);
      entries.add(
          new Entry(
              app.loadLabel(pm).toString(),
              audioLink ? UiContract.T_HOME_DESC_AUDIO : UiContract.T_HOME_DESC_APP,
              audioLink ? "audio" : "app",
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

  private void add(String label, String description, String iconKind, Intent intent) {
    ResolveInfo r = getPackageManager().resolveActivity(intent, 0);
    if (r != null)
      entries.add(new Entry(label, description, iconKind, intent, r.loadIcon(getPackageManager())));
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
    final Paint pixelPaint = new Paint();
    String counterText = "";
    Bitmap counterBitmap;
    float downY;

    HomePanel() {
      super(HomeActivity.this);
      setFocusable(true);
    }

    void rect(Canvas c, int x, int y, int w, int h, int color) {
      pixelPaint.setColor(color);
      c.drawRect(x, y, x + w, y + h, pixelPaint);
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

    void drawHomeTile(Canvas c, int x, int y, int size) {
      rect(c, x + 2, y, size - 4, size, UiContract.C_SELECTION);
      rect(c, x, y + 2, size, size - 4, UiContract.C_SELECTION);
      rect(c, x + 1, y + 1, size - 2, size - 2, UiContract.C_SELECTION);
      rect(c, x + 2, y + 1, size - 4, size - 2, UiContract.C_HOME_ICON);
      rect(c, x + 1, y + 2, size - 2, size - 4, UiContract.C_HOME_ICON);
      rect(c, x + 3, y + 2, size - 6, 1, UiContract.C_HOME_TILE_HIGHLIGHT);
    }

    void drawHomeIcon(Canvas c, Entry entry, int x, int y) {
      int ox = x + 8;
      int oy = y + 8;
      String kind = entry.iconKind;
      if ("talk".equals(kind)) {
        rect(c, ox + 2, oy + 3, 8, 2, UiContract.C_PTT);
        rect(c, ox + 2, oy + 23, 8, 2, UiContract.C_PTT);
        rect(c, ox + 1, oy + 5, 2, 18, UiContract.C_PTT);
        rect(c, ox + 9, oy + 5, 2, 18, UiContract.C_PTT);
        rect(c, ox + 5, oy + 9, 2, 10, UiContract.C_METER);
        rect(c, ox + 14, oy + 9, 2, 2, UiContract.C_SELECTION);
        rect(c, ox + 16, oy + 11, 2, 6, UiContract.C_SELECTION);
        rect(c, ox + 14, oy + 17, 2, 2, UiContract.C_SELECTION);
        rect(c, ox + 19, oy + 6, 2, 2, UiContract.C_ACCENT);
        rect(c, ox + 21, oy + 8, 2, 12, UiContract.C_ACCENT);
        rect(c, ox + 19, oy + 20, 2, 2, UiContract.C_ACCENT);
        rect(c, ox + 24, oy + 4, 2, 2, UiContract.C_PTT);
        rect(c, ox + 26, oy + 6, 2, 16, UiContract.C_PTT);
        rect(c, ox + 24, oy + 22, 2, 2, UiContract.C_PTT);
      } else if ("device".equals(kind)) {
        rect(c, ox + 2, oy + 5, 24, 2, UiContract.C_INK);
        rect(c, ox + 14, oy + 2, 8, 8, UiContract.C_HOME_ICON);
        rect(c, ox + 15, oy + 2, 6, 8, UiContract.C_ACCENT);
        rect(c, ox + 2, oy + 13, 24, 2, UiContract.C_INK);
        rect(c, ox + 5, oy + 10, 8, 8, UiContract.C_HOME_ICON);
        rect(c, ox + 6, oy + 10, 6, 8, UiContract.C_PTT);
        rect(c, ox + 2, oy + 21, 24, 2, UiContract.C_INK);
        rect(c, ox + 15, oy + 18, 8, 8, UiContract.C_HOME_ICON);
        rect(c, ox + 16, oy + 18, 6, 8, UiContract.C_WHITE);
      } else if ("android".equals(kind)) {
        rect(c, ox + 11, oy + 2, 6, 4, UiContract.C_INK);
        rect(c, ox + 11, oy + 22, 6, 4, UiContract.C_INK);
        rect(c, ox + 2, oy + 11, 4, 6, UiContract.C_INK);
        rect(c, ox + 22, oy + 11, 4, 6, UiContract.C_INK);
        rect(c, ox + 5, oy + 5, 4, 4, UiContract.C_INK);
        rect(c, ox + 19, oy + 5, 4, 4, UiContract.C_INK);
        rect(c, ox + 5, oy + 19, 4, 4, UiContract.C_INK);
        rect(c, ox + 19, oy + 19, 4, 4, UiContract.C_INK);
        rect(c, ox + 7, oy + 6, 14, 16, UiContract.C_INK);
        rect(c, ox + 6, oy + 7, 16, 14, UiContract.C_INK);
        rect(c, ox + 10, oy + 10, 8, 8, UiContract.C_HOME_ICON);
        rect(c, ox + 12, oy + 12, 4, 4, UiContract.C_PTT);
      } else if ("ble".equals(kind)) {
        rect(c, ox + 3, oy + 3, 10, 2, UiContract.C_PAGE_TITLE);
        rect(c, ox + 3, oy + 23, 10, 2, UiContract.C_PAGE_TITLE);
        rect(c, ox + 1, oy + 5, 2, 18, UiContract.C_PAGE_TITLE);
        rect(c, ox + 13, oy + 5, 2, 18, UiContract.C_PAGE_TITLE);
        rect(c, ox + 3, oy + 5, 10, 18, UiContract.C_BG);
        rect(c, ox + 6, oy + 7, 4, 1, UiContract.C_HOME_DESC);
        rect(c, ox + 5, oy + 10, 6, 3, UiContract.C_PTT);
        rect(c, ox + 5, oy + 15, 4, 2, UiContract.C_METER);
        rect(c, ox + 6, oy + 20, 4, 1, UiContract.C_INK);
        rect(c, ox + 17, oy + 11, 2, 1, UiContract.C_SELECTION);
        rect(c, ox + 18, oy + 12, 2, 4, UiContract.C_SELECTION);
        rect(c, ox + 17, oy + 16, 2, 1, UiContract.C_SELECTION);
        rect(c, ox + 21, oy + 8, 2, 2, UiContract.C_ACCENT);
        rect(c, ox + 22, oy + 10, 2, 8, UiContract.C_ACCENT);
        rect(c, ox + 21, oy + 18, 2, 2, UiContract.C_ACCENT);
        rect(c, ox + 25, oy + 7, 2, 14, UiContract.C_PTT);
      } else if ("audio".equals(kind)) {
        rect(c, ox + 8, oy + 4, 12, 3, UiContract.C_INK);
        rect(c, ox + 5, oy + 7, 3, 3, UiContract.C_INK);
        rect(c, ox + 20, oy + 7, 3, 3, UiContract.C_INK);
        rect(c, ox + 4, oy + 10, 3, 8, UiContract.C_INK);
        rect(c, ox + 21, oy + 10, 3, 8, UiContract.C_INK);
        rect(c, ox + 3, oy + 14, 5, 8, UiContract.C_PTT);
        rect(c, ox + 20, oy + 14, 5, 8, UiContract.C_PTT);
        rect(c, ox + 4, oy + 15, 2, 6, UiContract.C_METER);
        rect(c, ox + 22, oy + 15, 2, 6, UiContract.C_METER);
        rect(c, ox + 12, oy + 18, 4, 3, UiContract.C_ACCENT);
        rect(c, ox + 10, oy + 22, 8, 2, UiContract.C_ACCENT);
      } else if (entry.icon != null) {
        entry.icon.setBounds(x + 6, y + 6, x + 38, y + 38);
        entry.icon.draw(c);
      } else {
        rect(c, ox + 4, oy + 4, 9, 9, UiContract.C_ACCENT);
        rect(c, ox + 15, oy + 4, 9, 9, UiContract.C_INK);
        rect(c, ox + 4, oy + 15, 9, 9, UiContract.C_INK);
        rect(c, ox + 15, oy + 15, 9, 9, UiContract.C_PTT);
      }
    }

    protected void onDraw(Canvas canvas) {
      canvas.save();
      canvas.scale(getWidth() / 160f, getHeight() / 128f);
      canvas.drawColor(UiContract.C_BG);
      statusStrip.draw(canvas);
      if (!entries.isEmpty()) {
        Entry entry = entries.get(selected);
        int centerX = 78;
        int tileSize = UiContract.HOME_TILE_SIZE;
        int tileX = centerX - tileSize / 2;
        int tileY = UiContract.HOME_TILE_Y;

        drawHomeTile(canvas, tileX, tileY, tileSize);
        drawHomeIcon(canvas, entry, tileX, tileY);

        if (entries.size() > 1) {
          String nextCounter = (selected + 1) + "/" + entries.size();
          if (!nextCounter.equals(counterText)) {
            counterText = nextCounter;
            counterBitmap = PixelArt.numbers(counterText, 2, UiContract.C_ACCENT);
          }
          if (counterBitmap != null) {
            canvas.drawBitmap(counterBitmap, UiContract.RIGHT - counterBitmap.getWidth(), 21, null);
          }
          int activeH = 9;
          int dotH = 3;
          int gap = 3;
          int totalH = (entries.size() - 1) * (dotH + gap) + activeH;
          int dotY = Math.max(37, Math.round(66f - totalH / 2f));
          for (int idx = 0; idx < entries.size(); idx++) {
            int h = idx == selected ? activeH : dotH;
            rect(
                canvas,
                152,
                dotY,
                3,
                h,
                idx == selected ? UiContract.C_SELECTION : UiContract.C_HOME_DOT_IDLE);
            dotY += h + gap;
          }
        }

        paint.setTextAlign(Paint.Align.CENTER);
        text(
            canvas,
            entry.label,
            centerX,
            UiContract.HOME_TITLE_Y,
            UiContract.HOME_TITLE_FONT,
            UiContract.C_WHITE,
            140);
        if (entry.description != null && !entry.description.isEmpty()) {
          int descSize = UiContract.HOME_DESC_FONT;
          paint.setTextSize(descSize);
          while (descSize > 11 && paint.measureText(entry.description) > 142) {
            descSize -= 1;
            paint.setTextSize(descSize);
          }
          text(
              canvas,
              entry.description,
              centerX,
              UiContract.HOME_DESC_Y,
              descSize,
              UiContract.C_HOME_DESC,
              142);
        }

        paint.setTextAlign(Paint.Align.LEFT);
        rect(
            canvas,
            0,
            UiContract.STATUS_FOOTER_TOP,
            UiContract.WIDTH,
            UiContract.HEIGHT - UiContract.STATUS_FOOTER_TOP,
            UiContract.C_WAIT_BG);
        rect(canvas, 0, UiContract.STATUS_FOOTER_TOP, UiContract.WIDTH, 1, UiContract.C_WAIT_EDGE);
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
        setContentDescription(
            entry.label
                + UiContract.T_TEXT_151
                + (selected + 1)
                + UiContract.T_TEXT_149
                + entries.size()
                + UiContract.T_TEXT_148);
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
