package com.neotalkie.device;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

/**
 * Checks GitHub Releases OTA manifests, downloads and SHA-256 verifies all updated APKs, and
 * silently installs them via Device Owner PackageInstaller sessions.
 */
final class OtaUpdater {
  private static final String TAG = "NeoTalkieOTA";
  private static final String PREFS = "neotalkie_ota";
  private static final String KEY_MANIFEST_URL = "manifestUrl";
  private static final String KEY_MIRROR_PREFIX = "mirrorPrefix";
  private static final String KEY_LAST_UPDATED_TAG = "lastUpdatedTag";
  private static final String KEY_PENDING_SELF_TAG = "pendingSelfTag";
  private static final String ACTION_INSTALL_RESULT = "com.neotalkie.launcher.OTA_INSTALL_RESULT";
  private static final int CONNECT_TIMEOUT_MS = 8000;
  private static final int READ_TIMEOUT_MS = 20000;
  private static final int INSTALL_TIMEOUT_SEC = 120;
  private static final int MAX_MANIFEST_BYTES = 256 * 1024;
  private static final AtomicInteger NEXT_REQ = new AtomicInteger(100);

  interface Callback {
    void onUpToDate(OtaManifest.Release release, int installedCount);

    void onAvailable(OtaManifest.UpdatePlan plan, boolean silentReady);

    void onProgress(int percent, String stageTitle, String detail, boolean installing);

    void onCompleted(OtaManifest.Release release);

    void onError(String headline, String detail);
  }

  private final Context appContext;
  private volatile boolean cancelled;
  private volatile boolean installing;
  private volatile HttpURLConnection activeConn;
  private volatile String resolvedManifestUrl = OtaManifest.DEFAULT_MANIFEST_URL;

  OtaUpdater(Context context) {
    this.appContext = context.getApplicationContext();
  }

  static SharedPreferences prefs(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static String manifestUrl(Context context) {
    String custom = prefs(context).getString(KEY_MANIFEST_URL, "");
    return custom != null && !custom.trim().isEmpty()
        ? custom.trim()
        : OtaManifest.DEFAULT_MANIFEST_URL;
  }

  static String mirrorPrefix(Context context) {
    String custom = prefs(context).getString(KEY_MIRROR_PREFIX, "");
    return custom == null ? "" : custom.trim();
  }

  static String consumePendingSelfUpdate(Context context) {
    SharedPreferences p = prefs(context);
    String tag = p.getString(KEY_PENDING_SELF_TAG, "");
    if (tag != null && !tag.isEmpty()) {
      p.edit().remove(KEY_PENDING_SELF_TAG).putString(KEY_LAST_UPDATED_TAG, tag).apply();
      return tag;
    }
    return "";
  }

  static void cleanup(Context context) {
    File dir = new File(context.getApplicationContext().getFilesDir(), "ota");
    if (!dir.exists()) return;
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (!f.delete()) Log.w(TAG, "Failed to delete cached OTA file: " + f.getName());
      }
    }
  }

  boolean isInstalling() {
    return installing;
  }

  void cancel() {
    cancelled = true;
    HttpURLConnection c = activeConn;
    if (c != null) {
      try {
        c.disconnect();
      } catch (Exception ignored) {
      }
    }
  }

  void check(final Callback cb) {
    cancelled = false;
    installing = false;
    new Thread(() -> runCheck(cb), "ota-check").start();
  }

  void update(final OtaManifest.UpdatePlan plan, final Callback cb) {
    cancelled = false;
    installing = false;
    new Thread(() -> runUpdate(plan, cb), "ota-update").start();
  }

  private boolean isOnline() {
    ConnectivityManager cm =
        (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
    return info != null && info.isConnected();
  }

  private String userAgent() {
    String ver = "0";
    try {
      ver = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
    } catch (Exception ignored) {
    }
    return "NeoTalkie-OTA/" + ver;
  }

  private void runCheck(Callback cb) {
    if (!isOnline()) {
      cb.onError(UiContract.T_OFFLINE, UiContract.T_TEXT_130);
      return;
    }
    String custom = prefs(appContext).getString(KEY_MANIFEST_URL, "");
    List<String> candidates = OtaManifest.manifestUrls(custom);
    Exception lastError = null;
    int lastHttpCode = 0;
    boolean sawNotFound = false;

    for (String url : candidates) {
      if (cancelled) return;
      HttpURLConnection conn = null;
      try {
        conn = OtaTls.openGet(url, userAgent(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        activeConn = conn;
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
          sawNotFound = true;
          lastHttpCode = code;
          Log.w(TAG, "OTA manifest 404 at " + url);
          continue;
        }
        if (code < 200 || code >= 300) {
          lastHttpCode = code;
          Log.w(TAG, "OTA manifest HTTP " + code + " at " + url);
          continue;
        }
        String json = readSmallText(conn.getInputStream());
        if (cancelled) return;
        OtaManifest.Release release = OtaManifest.parse(json);
        resolvedManifestUrl = conn.getURL() != null ? conn.getURL().toString() : url;
        Map<String, Integer> installed = readInstalledVersions(release);
        OtaManifest.UpdatePlan plan =
            OtaManifest.plan(release, installed, appContext.getPackageName());
        if (!plan.hasUpdates()) {
          cb.onUpToDate(release, release.packages.size());
        } else {
          cb.onAvailable(plan, OtaAdminReceiver.isSilentInstallReady(appContext));
        }
        return;
      } catch (Exception e) {
        if (cancelled) return;
        lastError = e;
        Log.w(TAG, "OTA check failed at " + url, e);
      } finally {
        activeConn = null;
        if (conn != null) conn.disconnect();
      }
    }

    if (cancelled) return;
    if (sawNotFound && lastError == null && lastHttpCode == HttpURLConnection.HTTP_NOT_FOUND) {
      cb.onError(UiContract.T_OTA_NO_RELEASE, UiContract.T_TEXT_129);
    } else if (lastHttpCode >= 400 && lastError == null) {
      cb.onError(UiContract.T_TEXT_108, "HTTP " + lastHttpCode);
    } else if (lastError != null) {
      reportNetworkError(cb, lastError);
    } else {
      cb.onError(UiContract.T_OTA_NO_RELEASE, UiContract.T_TEXT_129);
    }
  }

  private Map<String, Integer> readInstalledVersions(OtaManifest.Release release) {
    PackageManager pm = appContext.getPackageManager();
    HashMap<String, Integer> installed = new HashMap<>();
    for (OtaManifest.PackageItem item : release.packages) {
      try {
        PackageInfo info = pm.getPackageInfo(item.packageName, 0);
        installed.put(item.packageName, info.versionCode);
      } catch (PackageManager.NameNotFoundException ignored) {
      }
    }
    return installed;
  }

  private void runUpdate(OtaManifest.UpdatePlan plan, Callback cb) {
    if (!OtaAdminReceiver.isSilentInstallReady(appContext)) {
      cb.onError(UiContract.T_OTA_NO_PERMISSION, UiContract.T_OTA_ADB_HINT);
      return;
    }
    if (!isOnline()) {
      cb.onError(UiContract.T_OFFLINE, UiContract.T_TEXT_130);
      return;
    }
    cleanup(appContext);
    File dir = new File(appContext.getFilesDir(), "ota");
    if (!dir.exists() && !dir.mkdirs()) {
      cb.onError(UiContract.T_OTA_STORAGE_ERROR, UiContract.T_TEXT_129);
      return;
    }
    String baseManifestUrl =
        resolvedManifestUrl != null && !resolvedManifestUrl.isEmpty()
            ? resolvedManifestUrl
            : manifestUrl(appContext);
    String mirror = mirrorPrefix(appContext);
    ArrayList<File> downloadedFiles = new ArrayList<>(plan.updates.size());
    long totalBytes = Math.max(1L, plan.totalBytes);
    long completedBytes = 0;

    // Phase 1: Download & SHA-256 verify all pending APKs before touching PackageInstaller.
    for (int i = 0; i < plan.updates.size(); i++) {
      if (cancelled) {
        cleanup(appContext);
        return;
      }
      OtaManifest.PackageItem item = plan.updates.get(i);
      String stepLabel =
          UiContract.T_OTA_DOWNLOADING
              + " "
              + shortName(item.name)
              + " ("
              + (i + 1)
              + "/"
              + plan.updates.size()
              + ")";
      File target = new File(dir, "pkg-" + i + ".apk");
      try {
        String downloadUrl = OtaManifest.resolveUrl(item.url, baseManifestUrl, mirror);
        long written =
            downloadAndVerify(
                downloadUrl, target, item.sha256, item.size, completedBytes, totalBytes, stepLabel, cb);
        completedBytes += written;
        downloadedFiles.add(target);
      } catch (VerifyException e) {
        cleanup(appContext);
        cb.onError(UiContract.T_OTA_VERIFY_FAILED, UiContract.T_TEXT_129);
        return;
      } catch (Exception e) {
        cleanup(appContext);
        if (cancelled) return;
        Log.w(TAG, "OTA download failed for " + item.packageName, e);
        reportNetworkError(cb, e);
        return;
      }
    }

    if (cancelled) {
      cleanup(appContext);
      return;
    }

    // Phase 2: Silent install every APK in order (launcher is guaranteed last).
    installing = true;
    int totalCount = plan.updates.size();
    for (int i = 0; i < totalCount; i++) {
      OtaManifest.PackageItem item = plan.updates.get(i);
      File apkFile = downloadedFiles.get(i);
      boolean isSelf = appContext.getPackageName().equals(item.packageName);
      int pct = OtaManifest.installProgress(i, totalCount, true);
      String stage =
          UiContract.T_OTA_INSTALLING
              + " "
              + shortName(item.name)
              + " ("
              + (i + 1)
              + "/"
              + totalCount
              + ")";
      cb.onProgress(pct, stage, UiContract.T_OTA_SILENT_INSTALLING, true);
      if (isSelf) {
        // Persist marker synchronously before Android kills our process to replace launcher.
        prefs(appContext)
            .edit()
            .putString(KEY_PENDING_SELF_TAG, plan.release.version)
            .commit();
      }
      InstallOutcome outcome = silentInstallApk(item, apkFile);
      if (!outcome.success) {
        if (isSelf) prefs(appContext).edit().remove(KEY_PENDING_SELF_TAG).apply();
        installing = false;
        cleanup(appContext);
        cb.onError(outcome.headline, outcome.detail);
        return;
      }
      cb.onProgress(
          OtaManifest.installProgress(i + 1, totalCount, false),
          stage,
          UiContract.T_OTA_SILENT_INSTALLING,
          true);
    }

    installing = false;
    prefs(appContext).edit().putString(KEY_LAST_UPDATED_TAG, plan.release.version).apply();
    cleanup(appContext);
    cb.onCompleted(plan.release);
  }

  private long downloadAndVerify(
      String url,
      File target,
      String expectedSha256,
      long expectedSize,
      long priorBytes,
      long totalBytes,
      String stageLabel,
      Callback cb)
      throws Exception {
    HttpURLConnection conn = null;
    try {
      conn = OtaTls.openGet(url, userAgent(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
      activeConn = conn;
      int code = conn.getResponseCode();
      if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[16384];
      long fileBytes = 0;
      long lastReport = 0;
      cb.onProgress(
          OtaManifest.downloadProgress(priorBytes, totalBytes),
          stageLabel,
          OtaManifest.formatSize(priorBytes) + "/" + OtaManifest.formatSize(totalBytes),
          false);
      try (InputStream in = conn.getInputStream();
          FileOutputStream out = new FileOutputStream(target)) {
        int n;
        while ((n = in.read(buf)) != -1) {
          if (cancelled) throw new IOException("Cancelled");
          out.write(buf, 0, n);
          md.update(buf, 0, n);
          fileBytes += n;
          long now = SystemClock.elapsedRealtime();
          if (now - lastReport >= 120) {
            lastReport = now;
            long soFar = priorBytes + fileBytes;
            cb.onProgress(
                OtaManifest.downloadProgress(soFar, totalBytes),
                stageLabel,
                OtaManifest.formatSize(soFar) + "/" + OtaManifest.formatSize(totalBytes),
                false);
          }
        }
        out.flush();
      }
      if (expectedSize > 0 && fileBytes != expectedSize) {
        throw new VerifyException("Size mismatch: got " + fileBytes + ", expected " + expectedSize);
      }
      String actualSha = OtaManifest.hex(md.digest());
      if (!actualSha.equalsIgnoreCase(expectedSha256)) {
        throw new VerifyException("SHA-256 mismatch");
      }
      return fileBytes;
    } finally {
      activeConn = null;
      if (conn != null) conn.disconnect();
    }
  }

  private InstallOutcome silentInstallApk(OtaManifest.PackageItem item, File apkFile) {
    PackageInstaller installer = appContext.getPackageManager().getPackageInstaller();
    PackageInstaller.Session session = null;
    int sessionId = -1;
    final int reqId = NEXT_REQ.incrementAndGet();
    final String action = ACTION_INSTALL_RESULT + "." + reqId;
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<Intent> resultRef = new AtomicReference<>();

    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          public void onReceive(Context context, Intent intent) {
            resultRef.set(intent);
            latch.countDown();
          }
        };

    appContext.registerReceiver(receiver, new IntentFilter(action));
    try {
      PackageInstaller.SessionParams params =
          new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
      params.setAppPackageName(item.packageName);
      params.setSize(apkFile.length());
      sessionId = installer.createSession(params);
      session = installer.openSession(sessionId);
      try (InputStream in = new FileInputStream(apkFile);
          OutputStream out = session.openWrite("base.apk", 0, apkFile.length())) {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
          out.write(buf, 0, n);
        }
        session.fsync(out);
      }
      Intent callbackIntent = new Intent(action);
      PendingIntent pending =
          PendingIntent.getBroadcast(
              appContext, reqId, callbackIntent, PendingIntent.FLAG_UPDATE_CURRENT);
      session.commit(pending.getIntentSender());
      session.close();
      session = null;

      if (!latch.await(INSTALL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        return InstallOutcome.fail(UiContract.T_OTA_INSTALL_FAILED, UiContract.T_TEXT_142);
      }
      Intent res = resultRef.get();
      int status =
          res == null
              ? PackageInstaller.STATUS_FAILURE
              : res.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
      String msg = res == null ? "" : res.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
      Log.i(TAG, "Install result for " + item.packageName + ": status=" + status + " msg=" + msg);
      if (status == PackageInstaller.STATUS_SUCCESS) {
        return InstallOutcome.ok();
      }
      if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
        return InstallOutcome.fail(UiContract.T_OTA_NO_PERMISSION, UiContract.T_OTA_ADB_HINT);
      }
      if (status == PackageInstaller.STATUS_FAILURE_CONFLICT
          || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
        return InstallOutcome.fail(UiContract.T_OTA_SIGN_CONFLICT, UiContract.T_OTA_SIGN_HINT);
      }
      if (status == PackageInstaller.STATUS_FAILURE_STORAGE) {
        return InstallOutcome.fail(UiContract.T_OTA_STORAGE_ERROR, UiContract.T_TEXT_129);
      }
      return InstallOutcome.fail(UiContract.T_OTA_INSTALL_FAILED, UiContract.T_TEXT_129);
    } catch (Exception e) {
      Log.w(TAG, "Silent install failed for " + item.packageName, e);
      if (session != null) {
        try {
          session.abandon();
        } catch (Exception ignored) {
        }
        try {
          session.close();
        } catch (Exception ignored) {
        }
      } else if (sessionId >= 0) {
        try {
          installer.abandonSession(sessionId);
        } catch (Exception ignored) {
        }
      }
      return InstallOutcome.fail(UiContract.T_OTA_INSTALL_FAILED, UiContract.T_TEXT_129);
    } finally {
      try {
        appContext.unregisterReceiver(receiver);
      } catch (Exception ignored) {
      }
    }
  }

  private static String readSmallText(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int total = 0;
    try (InputStream s = in) {
      int n;
      while ((n = s.read(buf)) != -1) {
        total += n;
        if (total > MAX_MANIFEST_BYTES) throw new IOException("Manifest too large");
        out.write(buf, 0, n);
      }
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private static String shortName(String name) {
    if (name == null) return "";
    return name.startsWith("NeoTalkie") ? name.substring("NeoTalkie".length()) : name;
  }

  private static void reportNetworkError(Callback cb, Exception e) {
    if (e instanceof UnknownHostException) {
      cb.onError(UiContract.T_TEXT_023, UiContract.T_TEXT_126);
    } else if (e instanceof SSLException) {
      cb.onError(UiContract.T_TEXT_027, UiContract.T_TEXT_126);
    } else if (e instanceof SocketTimeoutException) {
      cb.onError(UiContract.T_TEXT_142, UiContract.T_TEXT_126);
    } else {
      cb.onError(UiContract.T_TEXT_108, UiContract.T_TEXT_126);
    }
  }

  private static final class VerifyException extends IOException {
    VerifyException(String message) {
      super(message);
    }
  }

  private static final class InstallOutcome {
    final boolean success;
    final String headline;
    final String detail;

    private InstallOutcome(boolean success, String headline, String detail) {
      this.success = success;
      this.headline = headline;
      this.detail = detail;
    }

    static InstallOutcome ok() {
      return new InstallOutcome(true, "", "");
    }

    static InstallOutcome fail(String headline, String detail) {
      return new InstallOutcome(false, headline, detail);
    }
  }
}
