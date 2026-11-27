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

/** Small native, keyboard-first device settings, status pages, and silent OTA updater. */
public final class StatusActivity extends Activity {
  private static final int MODE_MENU = 0;
  private static final int MODE_PAGES = 1;
  private static final int MODE_OTA = 2;

  private static final int OTA_CHECKING = 0;
  private static final int OTA_UP_TO_DATE = 1;
  private static final int OTA_AVAILABLE = 2;
  private static final int OTA_UPDATING = 3;
  private static final int OTA_COMPLETED = 4;
  private static final int OTA_ERROR = 5;

  private final Handler main = new Handler();
  private final ArrayList<Page> pages = new ArrayList<>();
  private Panel panel;
  private StatusStrip strip;
  private VolumeStrip volume;
  private OtaUpdater updater;
  private OtaManifest.UpdatePlan otaPlan;
  private int mode = MODE_MENU;
  private int otaState = OTA_CHECKING;
  private int otaProgress;
  private boolean otaInstalling;
  private boolean otaSilentReady;
  private String otaLine1 = "";
  private String otaLine2 = "";
  private String otaLine3 = "";
  private int otaRunId;
  private int page;
  private int selected;
  private long acceptKeysAfter;
  private final Runnable otaPulse =
      new Runnable() {
        public void run() {
          if (mode == MODE_OTA && otaState == OTA_CHECKING) {
            panel.invalidate();
            main.postDelayed(this, 80);
          }
        }
      };

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
    updater = new OtaUpdater(this);
    setContentView(panel);
    handleIntent(getIntent());
  }

  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
  }

  private void handleIntent(Intent intent) {
    String extraPage = intent == null ? null : intent.getStringExtra("page");
    if ("device".equals(extraPage)) {
      mode = MODE_PAGES;
      devicePages();
    } else if ("ota_done".equals(extraPage)) {
      String ver = intent.getStringExtra("version");
      showOtaCompleted(ver == null ? "" : ver);
    }
  }

  protected void onResume() {
    super.onResume();
    acceptKeysAfter =
        SystemClock.elapsedRealtime() + 350; // Ignore the opening key's ROM duplicate.
    strip.start();
    PttNavigation.resume(this);
    updateKeepAwake();
  }

  protected void onPause() {
    PttNavigation.pause(this);
    strip.stop();
    volume.hide();
    main.removeCallbacks(otaPulse);
    if (mode == MODE_OTA && !updater.isInstalling() && (otaState == OTA_CHECKING || otaState == OTA_UPDATING)) {
      cancelOta();
      mode = MODE_MENU;
    }
    ScreenPower.busy(this, false);
    super.onPause();
  }

  protected void onDestroy() {
    if (!updater.isInstalling()) cancelOta();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private void updateKeepAwake() {
    boolean busy = mode == MODE_OTA && (otaState == OTA_CHECKING || otaState == OTA_UPDATING);
    ScreenPower.busy(this, busy);
  }

  private void cancelOta() {
    otaRunId++;
    main.removeCallbacks(otaPulse);
    if (updater != null) updater.cancel();
    otaInstalling = false;
  }

  private void activate() {
    if (mode == MODE_MENU) {
      if (selected == UiContract.MENU_NETWORK) {
        mode = MODE_PAGES;
        networkPages();
      } else if (selected == UiContract.MENU_UPDATE) {
        startOtaCheck();
      } else {
        mode = MODE_PAGES;
        devicePages();
      }
      panel.invalidate();
      return;
    }
    if (mode == MODE_OTA) {
      if (otaState == OTA_AVAILABLE) {
        if (otaPlan != null && otaSilentReady) {
          startOtaInstall(otaPlan);
        } else {
          otaState = OTA_ERROR;
          otaLine1 = UiContract.T_OTA_NO_PERMISSION;
          otaLine2 = UiContract.T_OTA_ADB_HINT;
          otaLine3 = "";
          updateKeepAwake();
          panel.invalidate();
        }
      } else if (otaState == OTA_UP_TO_DATE || otaState == OTA_ERROR) {
        startOtaCheck();
      }
    }
  }

  private void startOtaCheck() {
    cancelOta();
    final int run = ++otaRunId;
    mode = MODE_OTA;
    otaState = OTA_CHECKING;
    otaPlan = null;
    otaProgress = 0;
    otaInstalling = false;
    otaLine1 = UiContract.T_OTA_CHECKING;
    otaLine2 = UiContract.T_OTA_GITHUB_HINT;
    otaLine3 = "";
    updateKeepAwake();
    panel.invalidate();
    main.postDelayed(otaPulse, 80);
    updater.check(
        new OtaUpdater.Callback() {
          public void onUpToDate(final OtaManifest.Release release, final int installedCount) {
            main.post(
                () -> {
                  if (run != otaRunId) return;
                  main.removeCallbacks(otaPulse);
                  otaState = OTA_UP_TO_DATE;
                  otaLine1 = UiContract.T_OTA_UP_TO_DATE;
                  otaLine2 = UiContract.T_OTA_CURRENT_PREFIX + versionLabel(release.version);
                  otaLine3 = installedCount + UiContract.T_OTA_ALL_LATEST_SUFFIX;
                  updateKeepAwake();
                  panel.invalidate();
                });
          }

          public void onAvailable(final OtaManifest.UpdatePlan plan, final boolean silentReady) {
            main.post(
                () -> {
                  if (run != otaRunId) return;
                  main.removeCallbacks(otaPulse);
                  otaPlan = plan;
                  otaSilentReady = silentReady;
                  otaState = OTA_AVAILABLE;
                  otaLine1 = versionLabel(plan.release.version);
                  otaLine2 =
                      UiContract.T_OTA_APPS_PREFIX
                          + plan.updates.size()
                          + UiContract.T_OTA_APPS_SUFFIX
                          + " · "
                          + OtaManifest.formatSize(plan.totalBytes);
                  otaLine3 =
                      silentReady ? UiContract.T_OTA_CONFIRM_HINT : UiContract.T_OTA_NO_PERMISSION;
                  updateKeepAwake();
                  panel.invalidate();
                });
          }

          public void onProgress(int percent, String stage, String detail, boolean installing) {}

          public void onCompleted(OtaManifest.Release release) {}

          public void onError(final String headline, final String detail) {
            main.post(
                () -> {
                  if (run != otaRunId) return;
                  main.removeCallbacks(otaPulse);
                  otaState = OTA_ERROR;
                  otaLine1 = headline;
                  otaLine2 = detail;
                  otaLine3 = "";
                  updateKeepAwake();
                  panel.invalidate();
                });
          }
        });
  }

  private void startOtaInstall(final OtaManifest.UpdatePlan plan) {
    cancelOta();
    final int run = ++otaRunId;
    mode = MODE_OTA;
    otaState = OTA_UPDATING;
    otaProgress = 0;
    otaInstalling = false;
    otaLine1 = UiContract.T_OTA_DOWNLOADING;
    otaLine2 = "0% · " + OtaManifest.formatSize(plan.totalBytes);
    otaLine3 = "";
    updateKeepAwake();
    panel.invalidate();
    updater.update(
        plan,
        new OtaUpdater.Callback() {
          public void onUpToDate(OtaManifest.Release release, int installedCount) {}

          public void onAvailable(OtaManifest.UpdatePlan p, boolean silentReady) {}

          public void onProgress(
              final int percent,
              final String stageTitle,
              final String detail,
              final boolean installing) {
            main.post(
                () -> {
                  if (run != otaRunId) return;
                  otaProgress = Math.max(0, Math.min(100, percent));
                  otaInstalling = installing;
                  otaLine1 = stageTitle;
                  otaLine2 = otaProgress + "% · " + detail;
                  panel.invalidate();
                });
          }

          public void onCompleted(final OtaManifest.Release release) {
            main.post(
                () -> {
                  if (run != otaRunId) return;
                  showOtaCompleted(release.version);
                });
          }

          public void onError(final String headline, final String detail) {
            main.post(
                () -> {
                  if (run != otaRunId) return;
                  otaState = OTA_ERROR;
                  otaInstalling = false;
                  otaLine1 = headline;
                  otaLine2 = detail;
                  otaLine3 = "";
                  updateKeepAwake();
                  panel.invalidate();
                });
          }
        });
  }

  private void showOtaCompleted(String version) {
    main.removeCallbacks(otaPulse);
    mode = MODE_OTA;
    otaState = OTA_COMPLETED;
    otaProgress = 100;
    otaInstalling = false;
    otaLine1 = UiContract.T_OTA_COMPLETED_BODY;
    otaLine2 =
        version.isEmpty()
            ? UiContract.T_OTA_UP_TO_DATE
            : UiContract.T_OTA_CURRENT_PREFIX + versionLabel(version);
    otaLine3 = UiContract.T_OTA_COMPLETED_HINT;
    updateKeepAwake();
    panel.invalidate();
  }

  private static String versionLabel(String version) {
    if (version == null || version.isEmpty()) return "";
    return version.startsWith("v") || version.startsWith("V") ? version : "v" + version;
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
        new Page(
            UiContract.T_SYSTEM_STATUS,
            UiContract.T_TEXT_113 + Build.MODEL,
            UiContract.T_TEXT_028 + Build.VERSION.RELEASE,
            UiContract.T_TEXT_037 + version));
    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (battery != null) {
      int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
          scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
      String percent = level >= 0 && scale > 0 ? (level * 100 / scale) + "%" : UiContract.T_TEXT_065;
      int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
      String charge =
          status == BatteryManager.BATTERY_STATUS_FULL
              ? UiContract.T_TEXT_034
              : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                  ? UiContract.T_TEXT_035
                  : UiContract.T_TEXT_087;
      pages.add(
          new Page(
              UiContract.T_BATTERY_STATUS,
              UiContract.T_TEXT_089 + percent,
              charge,
              UiContract.T_TEXT_086
                  + battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                  + "°C"));
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

  private void move(int delta) {
    if (mode == MODE_MENU) {
      selected = UiContract.menuSelection(selected, delta, UiContract.DEVICE_MENU.length);
    } else if (mode == MODE_PAGES && !pages.isEmpty()) {
      page = (page + delta + pages.size()) % pages.size();
    }
    panel.invalidate();
  }

  public void onBackPressed() {
    if (mode == MODE_OTA && otaState == OTA_UPDATING && otaInstalling) {
      return; // Do not interrupt active silent package commit.
    }
    if (mode == MODE_OTA) {
      cancelOta();
      mode = MODE_MENU;
      updateKeepAwake();
      panel.invalidate();
      return;
    }
    if (mode == MODE_MENU) finish();
    else {
      mode = MODE_MENU;
      page = 0;
      panel.invalidate();
    }
  }

  public boolean dispatchKeyEvent(KeyEvent event) {
    boolean lockDuringInstall = mode == MODE_OTA && otaState == OTA_UPDATING && otaInstalling;
    if (!lockDuringInstall && PttNavigation.key(this, event)) return true;
    if (volume.handleKey(event, false)) return true;
    int key = event.getKeyCode();
    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
    if (key == KeyEvent.KEYCODE_BACK) {
      if (down && event.getRepeatCount() == 0) onBackPressed();
      return true;
    }
    if (SystemClock.elapsedRealtime() < acceptKeysAfter) return true;
    if (key == KeyEvent.KEYCODE_DPAD_CENTER
        || key == KeyEvent.KEYCODE_ENTER
        || key == KeyEvent.KEYCODE_MENU) {
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

    float rightHintLeft(String label) {
      paint.setTextSize(UiContract.STATUS_HINT_FONT);
      return UiContract.RIGHT - paint.measureText(label);
    }

    protected void onDraw(Canvas c) {
      c.save();
      c.scale(getWidth() / 160f, getHeight() / 128f);
      c.drawColor(UiContract.C_BG);
      strip.draw(c);
      String nextCounter = "";
      if (mode == MODE_MENU) {
        int first = selected / UiContract.MENU_VISIBLE_ROWS * UiContract.MENU_VISIBLE_ROWS;
        for (int row = 0;
            row < UiContract.MENU_VISIBLE_ROWS && first + row < UiContract.DEVICE_MENU.length;
            row++) {
          if (selected == first + row) {
            paint.setColor(UiContract.C_SELECTION);
            c.drawRect(
                UiContract.LEFT,
                UiContract.MENU_TOP + row * UiContract.MENU_ROW_HEIGHT,
                UiContract.RIGHT,
                UiContract.MENU_TOP + (row + 1) * UiContract.MENU_ROW_HEIGHT,
                paint);
          }
          text(
              c,
              UiContract.DEVICE_MENU[first + row],
              UiContract.MENU_TEXT_X,
              UiContract.MENU_BASELINE + row * UiContract.MENU_ROW_HEIGHT,
              UiContract.MENU_FONT,
              selected == first + row ? UiContract.C_WHITE : UiContract.C_INK,
              UiContract.RIGHT - UiContract.MENU_TEXT_X);
        }
        if (UiContract.DEVICE_MENU.length > UiContract.MENU_VISIBLE_ROWS)
          nextCounter =
              (first / UiContract.MENU_VISIBLE_ROWS + 1)
                  + "/"
                  + ((UiContract.DEVICE_MENU.length + UiContract.MENU_VISIBLE_ROWS - 1)
                      / UiContract.MENU_VISIBLE_ROWS);
      } else if (mode == MODE_OTA) {
        drawOta(c);
      } else if (!pages.isEmpty()) {
        Page current = pages.get(page);
        text(
            c,
            current.title,
            UiContract.STATUS_TITLE_X,
            UiContract.STATUS_TITLE_Y,
            UiContract.STATUS_TITLE_FONT,
            UiContract.C_PAGE_TITLE,
            116);
        for (int row = 0; row < Math.min(3, current.lines.length); row++)
          text(
              c,
              current.lines[row],
              UiContract.STATUS_TITLE_X,
              UiContract.STATUS_ROW_Y + row * UiContract.STATUS_ROW_HEIGHT,
              UiContract.STATUS_ROW_FONT,
              UiContract.C_INK,
              144);
        if (pages.size() > 1) nextCounter = (page + 1) + "/" + pages.size();
      }
      if (!nextCounter.equals(counter)) {
        counter = nextCounter;
        counterImage = counter.isEmpty() ? null : PixelArt.numbers(counter, 2, UiContract.C_ACCENT);
      }
      if (counterImage != null) c.drawBitmap(counterImage, 154 - counterImage.getWidth(), 34, null);
      drawFooter(c);
      volume.draw(c);
      c.restore();
    }

    private void drawOta(Canvas c) {
      String heading;
      if (otaState == OTA_AVAILABLE) heading = UiContract.T_OTA_FOUND_TITLE;
      else if (otaState == OTA_UPDATING) heading = UiContract.T_OTA_UPDATING_TITLE;
      else if (otaState == OTA_COMPLETED) heading = UiContract.T_OTA_COMPLETED_TITLE;
      else if (otaState == OTA_ERROR) heading = UiContract.T_OTA_FAILED_TITLE;
      else heading = UiContract.T_OTA_UPDATE;

      text(
          c,
          heading,
          UiContract.STATUS_TITLE_X,
          UiContract.STATUS_TITLE_Y,
          UiContract.STATUS_TITLE_FONT,
          UiContract.C_PAGE_TITLE,
          144);

      if (otaState == OTA_UPDATING) {
        text(
            c,
            otaLine1,
            UiContract.STATUS_TITLE_X,
            64,
            14,
            otaInstalling ? UiContract.C_PTT : UiContract.C_ACCENT,
            144);
        text(c, otaLine2, UiContract.STATUS_TITLE_X, 80, 12, UiContract.C_INK, 144);
        paint.setColor(UiContract.C_METER_BG);
        c.drawRect(8, 87, 152, 97, paint);
        int fill = Math.round(144f * otaProgress / 100f);
        if (fill > 0) {
          paint.setColor(otaInstalling ? UiContract.C_PTT : UiContract.C_SELECTION);
          c.drawRect(8, 87, 8 + fill, 97, paint);
        }
      } else if (otaState == OTA_CHECKING) {
        text(c, otaLine1, UiContract.STATUS_TITLE_X, 65, 14, UiContract.C_ACCENT, 144);
        text(c, otaLine2, UiContract.STATUS_TITLE_X, 83, 13, UiContract.C_INK, 144);
        paint.setColor(UiContract.C_METER_BG);
        c.drawRect(8, 90, 152, 96, paint);
        int span = 144 - 42;
        int offset = (int) ((SystemClock.elapsedRealtime() / 16) % (span * 2));
        if (offset > span) offset = span * 2 - offset;
        paint.setColor(UiContract.C_ACCENT);
        c.drawRect(8 + offset, 90, 8 + offset + 42, 96, paint);
      } else {
        int line1Color =
            (otaState == OTA_UP_TO_DATE || otaState == OTA_COMPLETED)
                ? UiContract.C_PTT
                : UiContract.C_ACCENT;
        text(c, otaLine1, UiContract.STATUS_TITLE_X, 65, 14, line1Color, 144);
        text(c, otaLine2, UiContract.STATUS_TITLE_X, 83, 14, UiContract.C_INK, 144);
        if (!otaLine3.isEmpty()) {
          text(c, otaLine3, UiContract.STATUS_TITLE_X, 99, 12, 0xff9eb0aa, 144);
        }
      }
    }

    private void drawFooter(Canvas c) {
      paint.setColor(UiContract.C_WAIT_BG);
      c.drawRect(0, UiContract.STATUS_FOOTER_TOP, UiContract.WIDTH, UiContract.HEIGHT, paint);
      paint.setColor(UiContract.C_WAIT_EDGE);
      c.drawRect(
          0, UiContract.STATUS_FOOTER_TOP, UiContract.WIDTH, UiContract.STATUS_FOOTER_TOP + 1, paint);

      if (mode == MODE_MENU) {
        text(
            c,
            UiContract.T_MENU_ENTER,
            UiContract.STATUS_HINT_X,
            UiContract.STATUS_FOOTER_Y,
            UiContract.STATUS_HINT_FONT,
            UiContract.C_INK,
            UiContract.STATUS_HINT_EXIT_X - UiContract.STATUS_HINT_X);
        text(
            c,
            UiContract.T_STATUS_EXIT_KEY,
            rightHintLeft(UiContract.T_STATUS_EXIT_KEY),
            UiContract.STATUS_FOOTER_Y,
            UiContract.STATUS_HINT_FONT,
            UiContract.C_INK,
            UiContract.WIDTH - UiContract.STATUS_HINT_EXIT_X);
      } else if (mode == MODE_OTA) {
        if (otaState == OTA_UPDATING && otaInstalling) {
          text(
              c,
              UiContract.T_OTA_KEEP_POWER,
              UiContract.STATUS_HINT_X,
              UiContract.STATUS_FOOTER_Y,
              UiContract.STATUS_HINT_FONT,
              UiContract.C_ACCENT,
              144);
          return;
        }
        String leftHint = "";
        if (otaState == OTA_AVAILABLE && otaSilentReady) leftHint = UiContract.T_OTA_UPDATE_KEY;
        else if (otaState == OTA_UP_TO_DATE) leftHint = UiContract.T_OTA_RECHECK_KEY;
        else if (otaState == OTA_ERROR) leftHint = UiContract.T_BLE_RETRY_KEY;
        if (!leftHint.isEmpty()) {
          text(
              c,
              leftHint,
              UiContract.STATUS_HINT_X,
              UiContract.STATUS_FOOTER_Y,
              UiContract.STATUS_HINT_FONT,
              UiContract.C_INK,
              UiContract.STATUS_HINT_EXIT_X - UiContract.STATUS_HINT_X);
        }
        String rightHint =
            (otaState == OTA_UPDATING) ? UiContract.T_BLE_CANCEL_KEY : UiContract.T_STATUS_EXIT_KEY;
        text(
            c,
            rightHint,
            rightHintLeft(rightHint),
            UiContract.STATUS_FOOTER_Y,
            UiContract.STATUS_HINT_FONT,
            UiContract.C_INK,
            UiContract.WIDTH - UiContract.STATUS_HINT_EXIT_X);
      } else {
        c.drawBitmap(PixelArt.PAGE_ARROWS, UiContract.STATUS_HINT_X, UiContract.STATUS_ARROW_Y, null);
        text(
            c,
            UiContract.T_STATUS_PAGE_KEYS,
            UiContract.STATUS_HINT_TEXT_X,
            UiContract.STATUS_FOOTER_Y,
            UiContract.STATUS_HINT_FONT,
            UiContract.C_INK,
            UiContract.STATUS_HINT_EXIT_X - UiContract.STATUS_HINT_X);
        text(
            c,
            UiContract.T_STATUS_EXIT_KEY,
            rightHintLeft(UiContract.T_STATUS_EXIT_KEY),
            UiContract.STATUS_FOOTER_Y,
            UiContract.STATUS_HINT_FONT,
            UiContract.C_INK,
            UiContract.WIDTH - UiContract.STATUS_HINT_EXIT_X);
      }
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
          if (x >= rightHintLeft(UiContract.T_STATUS_EXIT_KEY)) onBackPressed();
          else if (mode == MODE_MENU || mode == MODE_OTA) activate();
          else move(x < (UiContract.STATUS_HINT_X + UiContract.STATUS_HINT_EXIT_X) / 2f ? -1 : 1);
        } else if (mode == MODE_MENU && y >= UiContract.MENU_TOP && y < UiContract.STATUS_FOOTER_TOP) {
          int item =
              selected / UiContract.MENU_VISIBLE_ROWS * UiContract.MENU_VISIBLE_ROWS
                  + (int) ((y - UiContract.MENU_TOP) / UiContract.MENU_ROW_HEIGHT);
          if (item < UiContract.DEVICE_MENU.length) {
            selected = item;
            activate();
          }
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
