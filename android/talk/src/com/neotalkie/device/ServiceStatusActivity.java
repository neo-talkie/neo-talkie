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
public final class ServiceStatusActivity extends Activity {
  private final Handler main = new Handler();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ArrayList<Page> pages = new ArrayList<>();
  private final ArrayList<ServiceCheckResult> results = new ArrayList<>();
  private Panel panel;
  private StatusStrip strip;
  private VolumeStrip volume;
  private Config config;
  private ServiceChecks checks;
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
    try {
      config = new Config(this);
    } catch (Exception ignored) {
    }
    panel = new Panel();
    strip = new StatusStrip(this, panel);
    volume = new VolumeStrip(this, panel);
    setContentView(panel);
    menu = false;
    networkPages();
    networkPageCount = pages.size();
    pages.add(0, new Page(UiContract.T_NETWORK_TEST, UiContract.T_TEXT_083));
    runChecks(new int[] {0, 1, 2});

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
    worker.shutdownNow();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private void activate() {}

  private void showPages() {
    page = 0;
    panel.invalidate();
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

  private void runChecks(final int[] services) {
    cancelChecks();
    final int run = ++generation;
    final ServiceChecks task = new ServiceChecks(config);
    checks = task;
    results.clear();
    running = true;
    ScreenPower.busy(this, true);
    checking = ServiceChecks.NAMES[services[0]];
    updateCheckingPage();
    worker.execute(
        new Runnable() {
          public void run() {
            try {
              for (final int service : services) {
                main.post(
                    new Runnable() {
                      public void run() {
                        if (generation != run) return;
                        checking = ServiceChecks.NAMES[service];
                        updateCheckingPage();
                      }
                    });
                final ServiceCheckResult result = task.check(service);
                main.post(
                    new Runnable() {
                      public void run() {
                        if (generation != run) return;
                        results.add(result);
                      }
                    });
              }
              main.post(
                  new Runnable() {
                    public void run() {
                      if (generation != run) return;
                      checks = null;
                      running = false;
                      ScreenPower.busy(ServiceStatusActivity.this, false);
                      resultPages();
                      panel.invalidate();
                    }
                  });
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            } catch (Exception ignored) {
              main.post(
                  new Runnable() {
                    public void run() {
                      if (generation != run) return;
                      checks = null;
                      running = false;
                      ScreenPower.busy(ServiceStatusActivity.this, false);
                      pages.set(0, new Page(UiContract.T_TEXT_076, UiContract.T_TEXT_140, UiContract.T_TEXT_126, UiContract.T_TEXT_018));
                      panel.invalidate();
                    }
                  });
            } finally {
              task.close();
            }
          }
        });
  }

  private void updateCheckingPage() {
    pages.set(0, new Page(UiContract.T_NETWORK_TEST, checking, UiContract.T_TEXT_083, UiContract.T_TEXT_012));
    panel.invalidate();
  }

  private void cancelChecks() {
    running = false;
    ScreenPower.busy(ServiceStatusActivity.this, false);
    generation++;
    if (checks != null) checks.close();
    checks = null;
  }

  private void resultPages() {
    ArrayList<String> summary = new ArrayList<>();
    for (ServiceCheckResult r : results)
      summary.add(r.name + (r.success ? UiContract.T_TEXT_004 : UiContract.T_TEXT_003));
    pages.set(0, new Page(UiContract.T_TEXT_077, summary.toArray(new String[0])));
    while (pages.size() > networkPageCount + 1) pages.remove(pages.size() - 1);
    for (ServiceCheckResult r : results)
      pages.add(new Page(r.name, r.message, UiContract.T_TEXT_109 + r.elapsedMs + " ms", r.detail));
  }

  private void move(int delta) {
    if (menu) selected = UiContract.menuSelection(selected, delta);
    else if (!pages.isEmpty()) page = (page + delta + pages.size()) % pages.size();
    panel.invalidate();
  }

  public void onBackPressed() {
    cancelChecks();
    finish();
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
      super(ServiceStatusActivity.this);
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
        for (int row=0; row<UiContract.MENU_VISIBLE_ROWS && first+row<UiContract.MENU.length; row++) {
          if (selected == first+row) {
            paint.setColor(UiContract.C_SELECTION);
            c.drawRect(UiContract.LEFT,UiContract.MENU_TOP+row*UiContract.MENU_ROW_HEIGHT,
                UiContract.RIGHT,UiContract.MENU_TOP+(row+1)*UiContract.MENU_ROW_HEIGHT,paint);
          }
          text(c,UiContract.MENU[first+row],UiContract.MENU_TEXT_X,
              UiContract.MENU_BASELINE+row*UiContract.MENU_ROW_HEIGHT,UiContract.MENU_FONT,
              selected == first+row ? UiContract.C_WHITE : UiContract.C_INK,UiContract.RIGHT-UiContract.MENU_TEXT_X);
        }
        if (UiContract.MENU.length > UiContract.MENU_VISIBLE_ROWS)
          nextCounter = (first / UiContract.MENU_VISIBLE_ROWS + 1) + "/" +
              ((UiContract.MENU.length+UiContract.MENU_VISIBLE_ROWS-1)/UiContract.MENU_VISIBLE_ROWS);
      } else if (!pages.isEmpty()) {
        Page current = pages.get(page);
        text(c, current.title, UiContract.STATUS_TITLE_X, UiContract.STATUS_TITLE_Y, UiContract.STATUS_TITLE_FONT, UiContract.C_PAGE_TITLE, 116);
        for (int row = 0; row < Math.min(3, current.lines.length); row++)
          text(c, current.lines[row], UiContract.STATUS_TITLE_X, UiContract.STATUS_ROW_Y + row * UiContract.STATUS_ROW_HEIGHT, UiContract.STATUS_ROW_FONT,
              running && page == 0 && row == 0 ? UiContract.C_CHECKING : UiContract.C_INK, 144);
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
          if(item < UiContract.MENU.length) { selected=item; activate(); }
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
