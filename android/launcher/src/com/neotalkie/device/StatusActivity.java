package com.neotalkie.device;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.*;
import android.graphics.*;
import android.media.AudioManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.view.*;
import java.util.*;
import java.util.concurrent.*;

/** Small native, keyboard-first status pages. Network checks run only on explicit selection. */
public final class StatusActivity extends Activity {
  private final Handler main = new Handler();
  private final ArrayList<Page> pages = new ArrayList<>();
  private Panel panel;
  private StatusStrip strip;
  private VolumeStrip volume;
  private int page, generation, networkPageCount;
  private boolean running, menu = true;
  private int selected;
  private String checking = "";
  private long acceptKeysAfter;

  private static final class Page {
    final String title;
    final String[] lines;

    Page(String title, String... lines) {
      this.title = title;
      this.lines = lines;
    }
  }

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    panel = new Panel();
    strip = new StatusStrip(this, panel);
    volume = new VolumeStrip(this, panel);
    setContentView(panel);
    if ("device".equals(getIntent().getStringExtra("page"))) { menu = false; devicePages(); }

  }

  protected void onResume() {
    super.onResume();
    acceptKeysAfter =
        SystemClock.elapsedRealtime() + 350; // Ignore the opening key's ROM duplicate.
    strip.start();
    PttNavigation.resume(this);
  }

  protected void onPause() {
    PttNavigation.pause(this);
    strip.stop();
    volume.hide();
    if (running) {
      cancelChecks();
      pages.set(0, new Page(UiContract.T_NETWORK_TEST, UiContract.T_TEXT_075));
    }
    super.onPause();
  }

  protected void onDestroy() {
    cancelChecks();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private void activate() {
    if (!menu) return;
    menu = false;
    if (selected == UiContract.MENU_NETWORK) {
      networkPages();
      networkPageCount = pages.size();
      showPages();
    } else devicePages();
    panel.invalidate();
  }

  private void showPages() {
    page = 0;
    panel.invalidate();
  }

  private void devicePages() {
    pages.clear();
    String version = "";
    try {
      version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception ignored) {
    }
    pages.add(
        new Page(UiContract.T_SYSTEM_STATUS, UiContract.T_TEXT_113 + Build.MODEL, UiContract.T_TEXT_028 + Build.VERSION.RELEASE, UiContract.T_TEXT_037 + version));
    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (battery != null) {
      int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
          scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
      String percent = level >= 0 && scale > 0 ? (level * 100 / scale) + "%" : UiContract.T_TEXT_065;
      int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
      String charge =
          status == BatteryManager.BATTERY_STATUS_FULL
              ? UiContract.T_TEXT_034
              : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ? UiContract.T_TEXT_035 : UiContract.T_TEXT_087;
      pages.add(
          new Page(
              UiContract.T_BATTERY_STATUS,
              UiContract.T_TEXT_089 + percent,
              charge,
              UiContract.T_TEXT_086 + battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f + "°C"));
    }
    ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
    ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
    StatFs disk = new StatFs(getFilesDir().getPath());
    pages.add(
        new Page(
            UiContract.T_TEXT_026,
            UiContract.T_TEXT_016 + memory.availMem / 1048576 + "MB",
            UiContract.T_TEXT_046 + memory.totalMem / 1048576 + "MB",
            UiContract.T_TEXT_017 + disk.getAvailableBytes() / 1048576 + "MB"));
    AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    pages.add(
        new Page(
            UiContract.T_TEXT_133,
            UiContract.T_TEXT_025
                + audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                + "/"
                + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            UiContract.T_TEXT_039 + SystemClock.elapsedRealtime() / 60000 + UiContract.T_TEXT_001,
            UiContract.T_DEVICE_SETTINGS));
    showPages();
  }

  private static String ip(int value) {
    if (value == 0) return UiContract.T_TEXT_061;
    return (value & 255)
        + "."
        + ((value >>> 8) & 255)
        + "."
        + ((value >>> 16) & 255)
        + "."
        + ((value >>> 24) & 255);
  }

  private void networkPages() {
    pages.clear();
    ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    NetworkInfo active = cm.getActiveNetworkInfo();
    NetworkInfo mobile = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
    boolean connected = active != null && active.isConnected();
    pages.add(
        new Page(
            UiContract.T_NETWORK_STATUS,
            connected ? UiContract.T_TEXT_105 : UiContract.T_OFFLINE,
            connected
                ? (active.getType() == ConnectivityManager.TYPE_WIFI
                    ? UiContract.T_TEXT_041
                    : UiContract.T_TEXT_040 + active.getTypeName())
                : UiContract.T_TEXT_130,
            mobile != null && mobile.isConnected() ? UiContract.T_TEXT_098 : UiContract.T_TEXT_099));
    if (connected && active.getType() == ConnectivityManager.TYPE_WIFI) {
      WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
      WifiInfo wifi = manager.getConnectionInfo();
      DhcpInfo dhcp = manager.getDhcpInfo();
      if (wifi != null) {
        String ssid = wifi.getSSID();
        if (ssid == null || ssid.equals("<unknown ssid>")) ssid = UiContract.T_TEXT_066;
        else if (ssid.startsWith("\"") && ssid.endsWith("\""))
          ssid = ssid.substring(1, ssid.length() - 1);
        pages.add(
            new Page(
                UiContract.T_TEXT_006,
                ssid,
                "IP " + ip(wifi.getIpAddress()),
                UiContract.T_TEXT_104 + ip(dhcp == null ? 0 : dhcp.gateway)));
        int mhz = wifi.getFrequency();
        pages.add(
            new Page(
                UiContract.T_TEXT_007,
                UiContract.T_TEXT_014 + wifi.getRssi() + " dBm",
                UiContract.T_TEXT_145 + wifi.getLinkSpeed() + " Mbps",
                UiContract.T_TEXT_150 + mhz + " MHz"));
      }
    }
    showPages();
  }

  private void cancelChecks() { running = false; }

  private void move(int delta) {
    if (menu) selected = UiContract.menuSelection(selected, delta);
    else if (!pages.isEmpty()) page = (page + delta + pages.size()) % pages.size();
    panel.invalidate();
  }

  public void onBackPressed() {
    cancelChecks();
    if (menu) finish();
    else { menu = true; page = 0; panel.invalidate(); }
  }

  public boolean dispatchKeyEvent(KeyEvent event) {
    if (PttNavigation.key(this, event)) return true;
    if (volume.handleKey(event, false)) return true;
    int key = event.getKeyCode();
    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
    if (key == KeyEvent.KEYCODE_BACK) {
      if (down && event.getRepeatCount() == 0) onBackPressed();
      return true;
    }
    if (SystemClock.elapsedRealtime() < acceptKeysAfter) return true;
    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_MENU) {
      if (down && event.getRepeatCount() == 0) activate();
      return true;
    }
    int delta =
        key == KeyEvent.KEYCODE_DPAD_UP
                || key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_F7
            ? -1
            : key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_F8
                ? 1
                : 0;
    if (delta != 0) {
      if (down && event.getRepeatCount() == 0) move(delta);
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  final class Panel extends View {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    String counter = "";
    Bitmap counterImage;
    float downY;

    Panel() {
      super(StatusActivity.this);
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

    float exitLeft() {
      paint.setTextSize(UiContract.STATUS_HINT_FONT);
      return UiContract.RIGHT - paint.measureText(UiContract.T_STATUS_EXIT_KEY);
    }

    protected void onDraw(Canvas c) {
      c.save();
      c.scale(getWidth() / 160f, getHeight() / 128f);
      c.drawColor(UiContract.C_BG);
      strip.draw(c);
      String nextCounter = "";
      if (menu) {
        int first = selected / UiContract.MENU_VISIBLE_ROWS * UiContract.MENU_VISIBLE_ROWS;
        for (int row=0; row<UiContract.MENU_VISIBLE_ROWS && first+row<UiContract.DEVICE_MENU.length; row++) {
          if (selected == first+row) {
            paint.setColor(UiContract.C_SELECTION);
            c.drawRect(UiContract.LEFT,UiContract.MENU_TOP+row*UiContract.MENU_ROW_HEIGHT,
                UiContract.RIGHT,UiContract.MENU_TOP+(row+1)*UiContract.MENU_ROW_HEIGHT,paint);
          }
          text(c,UiContract.DEVICE_MENU[first+row],UiContract.MENU_TEXT_X,
              UiContract.MENU_BASELINE+row*UiContract.MENU_ROW_HEIGHT,UiContract.MENU_FONT,
              selected == first+row ? UiContract.C_WHITE : UiContract.C_INK,UiContract.RIGHT-UiContract.MENU_TEXT_X);
        }
        if (UiContract.DEVICE_MENU.length > UiContract.MENU_VISIBLE_ROWS)
          nextCounter = (first / UiContract.MENU_VISIBLE_ROWS + 1) + "/" +
              ((UiContract.DEVICE_MENU.length+UiContract.MENU_VISIBLE_ROWS-1)/UiContract.MENU_VISIBLE_ROWS);
      } else if (!pages.isEmpty()) {
        Page current = pages.get(page);
        text(c, current.title, UiContract.STATUS_TITLE_X, UiContract.STATUS_TITLE_Y, UiContract.STATUS_TITLE_FONT, UiContract.C_PAGE_TITLE, 116);
        for (int row = 0; row < Math.min(3, current.lines.length); row++)
          text(c, current.lines[row], UiContract.STATUS_TITLE_X, UiContract.STATUS_ROW_Y + row * UiContract.STATUS_ROW_HEIGHT, UiContract.STATUS_ROW_FONT, UiContract.C_INK, 144);
        if (pages.size() > 1) nextCounter = (page + 1) + "/" + pages.size();
      }
      if (!nextCounter.equals(counter)) {
        counter = nextCounter;
        counterImage = counter.isEmpty() ? null : PixelArt.numbers(counter, 2, UiContract.C_ACCENT);
      }
      if (counterImage != null) c.drawBitmap(counterImage, 154 - counterImage.getWidth(), 34, null);
      paint.setColor(UiContract.C_WAIT_BG);
      c.drawRect(0, UiContract.STATUS_FOOTER_TOP, UiContract.WIDTH, UiContract.HEIGHT, paint);
      paint.setColor(UiContract.C_WAIT_EDGE);
      c.drawRect(0, UiContract.STATUS_FOOTER_TOP, UiContract.WIDTH, UiContract.STATUS_FOOTER_TOP + 1, paint);
      if (menu) {
        text(c,UiContract.T_MENU_ENTER,UiContract.STATUS_HINT_X,UiContract.STATUS_FOOTER_Y,
            UiContract.STATUS_HINT_FONT,UiContract.C_INK,UiContract.STATUS_HINT_EXIT_X-UiContract.STATUS_HINT_X);
      } else {
        c.drawBitmap(PixelArt.PAGE_ARROWS, UiContract.STATUS_HINT_X, UiContract.STATUS_ARROW_Y, null);
        text(c, UiContract.T_STATUS_PAGE_KEYS,
            UiContract.STATUS_HINT_TEXT_X, UiContract.STATUS_FOOTER_Y, UiContract.STATUS_HINT_FONT,
            UiContract.C_INK, UiContract.STATUS_HINT_EXIT_X - UiContract.STATUS_HINT_X);
      }
      text(c, UiContract.T_STATUS_EXIT_KEY,
          exitLeft(),
          UiContract.STATUS_FOOTER_Y, UiContract.STATUS_HINT_FONT, UiContract.C_INK,
          UiContract.WIDTH - UiContract.STATUS_HINT_EXIT_X);
      volume.draw(c);
      c.restore();
    }

    public boolean onTouchEvent(MotionEvent event) {
      float x = event.getX() * 160f / getWidth(), y = event.getY() * 128f / getHeight();
      if (event.getAction() == MotionEvent.ACTION_DOWN) {
        downY = y;
        return true;
      }
      if (event.getAction() == MotionEvent.ACTION_UP) {
        if (Math.abs(y - downY) > 20) move(y < downY ? 1 : -1);
        else if (y >= UiContract.STATUS_FOOTER_TOP) {
          if (x >= exitLeft()) onBackPressed();
          else if (menu) activate();
          else move(x < (UiContract.STATUS_HINT_X + UiContract.STATUS_HINT_EXIT_X) / 2f ? -1 : 1);
        } else if (menu && y >= UiContract.MENU_TOP && y < UiContract.STATUS_FOOTER_TOP) {
          int item = selected / UiContract.MENU_VISIBLE_ROWS * UiContract.MENU_VISIBLE_ROWS + (int)((y-UiContract.MENU_TOP)/UiContract.MENU_ROW_HEIGHT);
          if(item < UiContract.DEVICE_MENU.length) { selected=item; activate(); }
        }
        performClick();
        return true;
      }
      return true;
    }

    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
