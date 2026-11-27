package com.neotalkie.device;

import java.io.IOException;
import java.util.*;

/** Offline unit tests for OTA manifest parsing, update planning, URL resolution, and progress. */
public final class OtaManifestTest {
  private static final String SHA_A =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String SHA_B =
      "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
  private static final String SHA_C =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String SHA_D =
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  private static void check(boolean ok, String msg) {
    if (!ok) throw new AssertionError(msg);
  }

  private static void failParse(String json) {
    try {
      OtaManifest.parse(json);
      throw new AssertionError("Expected parse failure for: " + json);
    } catch (IOException expected) {
    }
  }

  public static void main(String[] args) throws Exception {
    String sample =
        "{"
            + "\"schema\":\"neotalkie-ota/v1\","
            + "\"releaseTag\":\"v0.2.4-build.12\","
            + "\"version\":\"0.2.4\","
            + "\"publishedAt\":\"2026-11-27T00:00:00Z\","
            + "\"notes\":\"OTA support\","
            + "\"packages\":["
            + "{\"id\":\"launcher\",\"name\":\"NeoTalkie桌面\",\"packageName\":\"com.neotalkie.launcher\","
            + "\"versionCode\":80012,\"versionName\":\"0.2.4\",\"apk\":\"neotalkie-launcher.apk\","
            + "\"url\":\"https://github.com/neo-talkie/neo-talkie/releases/download/v0.2.4-build.12/neotalkie-launcher.apk\","
            + "\"sha256\":\""
            + SHA_A
            + "\",\"size\":120000,\"self\":true},"
            + "{\"id\":\"talk\",\"name\":\"NeoTalkie对讲\",\"packageName\":\"com.neotalkie.talk\","
            + "\"versionCode\":80012,\"versionName\":\"0.2.4\",\"apk\":\"neotalkie-talk.apk\","
            + "\"url\":\"https://github.com/neo-talkie/neo-talkie/releases/download/v0.2.4-build.12/neotalkie-talk.apk\","
            + "\"sha256\":\""
            + SHA_B
            + "\",\"size\":1400000},"
            + "{\"id\":\"audio-link\",\"name\":\"音频互联\",\"packageName\":\"com.neotalkie.hfpmic\","
            + "\"versionCode\":90012,\"versionName\":\"0.9\",\"apk\":\"neotalkie-audio-link.apk\","
            + "\"url\":\"neotalkie-audio-link.apk\","
            + "\"sha256\":\""
            + SHA_C
            + "\",\"size\":90000},"
            + "{\"id\":\"future-app\",\"name\":\"未来新应用\",\"packageName\":\"com.neotalkie.future\","
            + "\"versionCode\":10012,\"versionName\":\"0.1\",\"apk\":\"neotalkie-future.apk\","
            + "\"url\":\"https://github.com/neo-talkie/neo-talkie/releases/download/v0.2.4-build.12/neotalkie-future.apk\","
            + "\"sha256\":\""
            + SHA_D
            + "\",\"size\":50000}"
            + "]}";

    OtaManifest.Release release = OtaManifest.parse(sample);
    check("0.2.4".equals(release.version), "version");
    check("v0.2.4-build.12".equals(release.releaseTag), "tag");
    check(release.packages.size() == 4, "4 packages");

    // 1. Outdated launcher + talk, up-to-date audio-link, uninstalled future-app => 3 updates, launcher LAST.
    HashMap<String, Integer> installed = new HashMap<>();
    installed.put("com.neotalkie.launcher", 7);
    installed.put("com.neotalkie.talk", 7);
    installed.put("com.neotalkie.hfpmic", 90012);
    OtaManifest.UpdatePlan plan = OtaManifest.plan(release, installed, "com.neotalkie.launcher");
    check(plan.hasUpdates(), "should have updates");
    check(plan.updates.size() == 3, "should update talk, future-app, and launcher");
    check(plan.totalBytes == 120000 + 1400000 + 50000, "total bytes");
    check("com.neotalkie.talk".equals(plan.updates.get(0).packageName), "talk first");
    check("com.neotalkie.future".equals(plan.updates.get(1).packageName), "future app included");
    check("com.neotalkie.launcher".equals(plan.updates.get(2).packageName), "launcher MUST be last");

    // 2. All packages up to date => empty update plan.
    installed.put("com.neotalkie.launcher", 80012);
    installed.put("com.neotalkie.talk", 80012);
    installed.put("com.neotalkie.future", 10012);
    OtaManifest.UpdatePlan none = OtaManifest.plan(release, installed, "com.neotalkie.launcher");
    check(!none.hasUpdates(), "no updates when all current");

    // 3. Relative URL resolution and mirror forwarding prefix rewrite.
    String resolvedRel =
        OtaManifest.resolveUrl(
            "neotalkie-audio-link.apk",
            "https://github.com/neo-talkie/neo-talkie/releases/download/v0.2.4-build.12/ota-manifest.json",
            "");
    check(
        "https://github.com/neo-talkie/neo-talkie/releases/download/v0.2.4-build.12/neotalkie-audio-link.apk"
            .equals(resolvedRel),
        "relative resolve");

    String rewritten =
        OtaManifest.resolveUrl(
            "https://github.com/neo-talkie/neo-talkie/releases/download/v0.2.4-build.12/neotalkie-talk.apk",
            OtaManifest.DEFAULT_MANIFEST_URL,
            "https://ota.example.com/gh");
    check(
        "https://ota.example.com/gh/neo-talkie/neo-talkie/releases/download/v0.2.4-build.12/neotalkie-talk.apk"
            .equals(rewritten),
        "mirror rewrite");

    // 4. Reject non-HTTPS remote URLs.
    try {
      OtaManifest.resolveUrl("http://evil.example.com/app.apk", OtaManifest.DEFAULT_MANIFEST_URL, "");
      throw new AssertionError("Should reject plain HTTP remote URL");
    } catch (IOException expected) {
    }

    // 5. Monotonic progress calculation across download (0..75) and install (75..100).
    check(OtaManifest.downloadProgress(0, 1000) == 0, "dl 0%");
    check(OtaManifest.downloadProgress(500, 1000) == 37, "dl 50% -> 37%");
    check(OtaManifest.downloadProgress(1000, 1000) == 75, "dl 100% -> 75%");
    check(OtaManifest.installProgress(0, 3, true) == 79, "install 1/3 mid");
    check(OtaManifest.installProgress(1, 3, false) == 83, "install 1/3 done");
    check(OtaManifest.installProgress(3, 3, false) == 100, "install 3/3 done");
    check("1.4MB".equals(OtaManifest.formatSize(1468006)), "format MB");
    check("120KB".equals(OtaManifest.formatSize(122880)), "format KB");

    // 6. Malformed / invalid manifests rejected.
    failParse("");
    failParse("{\"schema\":\"wrong\",\"releaseTag\":\"v1\",\"packages\":[]}");
    failParse(
        "{\"schema\":\"neotalkie-ota/v1\",\"releaseTag\":\"v1\",\"packages\":[{\"packageName\":\"a\",\"versionCode\":1,\"url\":\"https://x\",\"sha256\":\"short\",\"size\":1}]}");

    // 7. Default 2-tier fallback chain (custom domain -> GitHub Releases) and custom override.
    List<String> defaults = OtaManifest.manifestUrls("");
    check(defaults.size() == 2, "2 default OTA endpoints");
    check(
        "https://neotalkie.com/ota/latest/ota-manifest.json".equals(defaults.get(0)),
        "custom Cloudflare domain primary first");
    check(defaults.get(1).equals(OtaManifest.GITHUB_FALLBACK_MANIFEST_URL), "GitHub fallback second");
    List<String> custom = OtaManifest.manifestUrls("https://custom.example.com/ota.json");
    check(custom.size() == 1 && "https://custom.example.com/ota.json".equals(custom.get(0)), "custom override");

    System.out.println("OTA manifest parsing, self-last ordering, URL rewrite and progress passed");
  }
}
