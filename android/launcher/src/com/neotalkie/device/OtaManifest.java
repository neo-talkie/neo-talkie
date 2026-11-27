package com.neotalkie.device;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Pure-Java OTA release manifest parser, update planner, and URL resolver (host-testable). */
final class OtaManifest {
  static final String SCHEMA = "neotalkie-ota/v1";
  static final String PRIMARY_MANIFEST_URL =
      "https://neotalkie-ota-1706037644303620.oss-cn-shanghai.aliyuncs.com/ota/latest/ota-manifest.json";
  static final String DOMAIN_FALLBACK_MANIFEST_URL =
      "https://ota.neo-talkie.com/v1/ota-manifest.json";
  static final String GITHUB_FALLBACK_MANIFEST_URL =
      "https://github.com/neo-talkie/neo-talkie/releases/latest/download/ota-manifest.json";
  static final String DEFAULT_MANIFEST_URL = PRIMARY_MANIFEST_URL;

  static List<String> manifestUrls(String customUrl) {
    if (customUrl != null && !customUrl.trim().isEmpty()) {
      return Collections.singletonList(customUrl.trim());
    }
    return Collections.unmodifiableList(
        Arrays.asList(
            PRIMARY_MANIFEST_URL, DOMAIN_FALLBACK_MANIFEST_URL, GITHUB_FALLBACK_MANIFEST_URL));
  }

  static final class PackageItem {
    final String id;
    final String name;
    final String packageName;
    final int versionCode;
    final String versionName;
    final String apk;
    final String url;
    final String sha256;
    final long size;
    final boolean self;

    PackageItem(
        String id,
        String name,
        String packageName,
        int versionCode,
        String versionName,
        String apk,
        String url,
        String sha256,
        long size,
        boolean self) {
      this.id = id;
      this.name = name;
      this.packageName = packageName;
      this.versionCode = versionCode;
      this.versionName = versionName;
      this.apk = apk;
      this.url = url;
      this.sha256 = sha256;
      this.size = size;
      this.self = self;
    }
  }

  static final class Release {
    final String releaseTag;
    final String version;
    final String publishedAt;
    final String notes;
    final List<PackageItem> packages;

    Release(
        String releaseTag,
        String version,
        String publishedAt,
        String notes,
        List<PackageItem> packages) {
      this.releaseTag = releaseTag;
      this.version = version;
      this.publishedAt = publishedAt;
      this.notes = notes;
      this.packages = Collections.unmodifiableList(packages);
    }
  }

  static final class UpdatePlan {
    final Release release;
    final List<PackageItem> updates;
    final int totalPackages;
    final long totalBytes;

    UpdatePlan(Release release, List<PackageItem> updates, int totalPackages, long totalBytes) {
      this.release = release;
      this.updates = Collections.unmodifiableList(updates);
      this.totalPackages = totalPackages;
      this.totalBytes = totalBytes;
    }

    boolean hasUpdates() {
      return !updates.isEmpty();
    }
  }

  static Release parse(String jsonText) throws IOException {
    if (jsonText == null || jsonText.trim().isEmpty()) throw new IOException("Empty OTA manifest");
    Object rootObj = new JsonReader(jsonText).parse();
    Map<String, Object> root = asObject(rootObj, "manifest");
    String schema = reqString(root, "schema");
    if (!SCHEMA.equals(schema)) throw new IOException("Unsupported OTA schema: " + schema);
    String tag = reqString(root, "releaseTag");
    String version = optString(root, "version", tag);
    String publishedAt = optString(root, "publishedAt", "");
    String notes = optString(root, "notes", "");
    List<Object> rawPackages = asArray(root.get("packages"), "packages");
    if (rawPackages.isEmpty()) throw new IOException("OTA manifest has no packages");
    ArrayList<PackageItem> items = new ArrayList<>(rawPackages.size());
    HashSet<String> seen = new HashSet<>();
    for (Object entry : rawPackages) {
      Map<String, Object> obj = asObject(entry, "package");
      String packageName = reqString(obj, "packageName");
      if (!seen.add(packageName)) throw new IOException("Duplicate package: " + packageName);
      String id = optString(obj, "id", packageName);
      String name = optString(obj, "name", id);
      int versionCode = reqInt(obj, "versionCode");
      if (versionCode <= 0) throw new IOException("Invalid versionCode for " + packageName);
      String versionName = optString(obj, "versionName", String.valueOf(versionCode));
      String apk = optString(obj, "apk", "");
      String url = optString(obj, "url", apk);
      if (url.isEmpty()) throw new IOException("Missing url for " + packageName);
      String sha256 = reqString(obj, "sha256").toLowerCase(Locale.US);
      if (!isSha256Hex(sha256)) throw new IOException("Invalid sha256 for " + packageName);
      long size = reqLong(obj, "size");
      if (size <= 0) throw new IOException("Invalid size for " + packageName);
      boolean self = optBoolean(obj, "self", false);
      items.add(new PackageItem(id, name, packageName, versionCode, versionName, apk, url, sha256, size, self));
    }
    return new Release(tag, version, publishedAt, notes, items);
  }

  /**
   * Plan updates for every package declared in the release. Missing packages (including newly
   * introduced apps in future releases) and outdated packages are both queued. The launcher itself
   * is always ordered last so all other packages finish installing before the launcher restarts.
   */
  static UpdatePlan plan(Release release, Map<String, Integer> installedVersions, String selfPackage) {
    ArrayList<PackageItem> others = new ArrayList<>();
    ArrayList<PackageItem> selfList = new ArrayList<>();
    long totalBytes = 0;
    for (PackageItem item : release.packages) {
      Integer installed = installedVersions == null ? null : installedVersions.get(item.packageName);
      if (installed != null && installed >= item.versionCode) continue;
      boolean isSelf = item.self || (selfPackage != null && selfPackage.equals(item.packageName));
      if (isSelf) selfList.add(item);
      else others.add(item);
      totalBytes += item.size;
    }
    ArrayList<PackageItem> ordered = new ArrayList<>(others.size() + selfList.size());
    ordered.addAll(others);
    ordered.addAll(selfList);
    return new UpdatePlan(release, ordered, release.packages.size(), totalBytes);
  }

  /**
   * Resolve a package download URL against the manifest URL and an optional mirror/forwarding
   * prefix (reserved for custom domain forwarding).
   */
  static String resolveUrl(String packageUrl, String manifestUrl, String mirrorPrefix)
      throws IOException {
    if (packageUrl == null || packageUrl.trim().isEmpty()) {
      throw new IOException("Empty download URL");
    }
    String trimmed = packageUrl.trim();
    String resolved;
    if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
      resolved = trimmed;
    } else if (manifestUrl != null && !manifestUrl.trim().isEmpty()) {
      try {
        resolved = URI.create(manifestUrl.trim()).resolve(trimmed).toString();
      } catch (IllegalArgumentException e) {
        throw new IOException("Invalid relative URL: " + trimmed, e);
      }
    } else {
      throw new IOException("Cannot resolve relative URL without manifest base: " + trimmed);
    }
    if (mirrorPrefix != null && !mirrorPrefix.trim().isEmpty()) {
      String prefix = mirrorPrefix.trim();
      if (resolved.startsWith("https://github.com/")) {
        if (!prefix.endsWith("/")) prefix = prefix + "/";
        resolved = prefix + resolved.substring("https://github.com/".length());
      }
    }
    if (!isAllowedUrl(resolved)) throw new IOException("Blocked non-HTTPS URL: " + resolved);
    return resolved;
  }

  static boolean isAllowedUrl(String url) {
    if (url == null) return false;
    if (url.startsWith("https://")) return true;
    // Allow loopback HTTP solely for local offline testing harnesses.
    return url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:");
  }

  static boolean isSha256Hex(String value) {
    if (value == null || value.length() != 64) return false;
    for (int i = 0; i < 64; i++) {
      char c = value.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (!ok) return false;
    }
    return true;
  }

  static String hex(byte[] digest) {
    char[] digits = "0123456789abcdef".toCharArray();
    char[] out = new char[digest.length * 2];
    for (int i = 0; i < digest.length; i++) {
      int v = digest[i] & 0xff;
      out[i * 2] = digits[v >>> 4];
      out[i * 2 + 1] = digits[v & 0x0f];
    }
    return new String(out);
  }

  static int downloadProgress(long downloadedBytes, long totalBytes) {
    if (totalBytes <= 0) return 0;
    long clamped = Math.max(0, Math.min(downloadedBytes, totalBytes));
    return (int) Math.min(75, (clamped * 75L) / totalBytes);
  }

  static int installProgress(int completedPackages, int totalPackages, boolean inFlight) {
    if (totalPackages <= 0) return 100;
    int clamped = Math.max(0, Math.min(completedPackages, totalPackages));
    double unit = clamped + (inFlight && clamped < totalPackages ? 0.5 : 0.0);
    int pct = 75 + (int) Math.round((unit / totalPackages) * 25.0);
    return Math.max(75, Math.min(100, pct));
  }

  static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + "B";
    if (bytes < 1024L * 1024L) return Math.max(1, (bytes + 512) / 1024) + "KB";
    long tenths = (bytes * 10L + 524288L) / 1048576L;
    return (tenths / 10) + "." + (tenths % 10) + "MB";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObject(Object value, String name) throws IOException {
    if (!(value instanceof Map)) throw new IOException("Expected object for " + name);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asArray(Object value, String name) throws IOException {
    if (!(value instanceof List)) throw new IOException("Expected array for " + name);
    return (List<Object>) value;
  }

  private static String reqString(Map<String, Object> obj, String key) throws IOException {
    Object v = obj.get(key);
    if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
      throw new IOException("Missing string field: " + key);
    }
    return ((String) v).trim();
  }

  private static String optString(Map<String, Object> obj, String key, String fallback) {
    Object v = obj.get(key);
    return v instanceof String ? ((String) v).trim() : fallback;
  }

  private static int reqInt(Map<String, Object> obj, String key) throws IOException {
    long v = reqLong(obj, key);
    if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new IOException("Int overflow: " + key);
    return (int) v;
  }

  private static long reqLong(Map<String, Object> obj, String key) throws IOException {
    Object v = obj.get(key);
    if (!(v instanceof Number)) throw new IOException("Missing integer field: " + key);
    return ((Number) v).longValue();
  }

  private static boolean optBoolean(Map<String, Object> obj, String key, boolean fallback) {
    Object v = obj.get(key);
    return v instanceof Boolean ? (Boolean) v : fallback;
  }

  /** Minimal strict JSON parser with zero Android or third-party dependencies. */
  private static final class JsonReader {
    private final String src;
    private int pos;

    JsonReader(String src) {
      this.src = src;
    }

    Object parse() throws IOException {
      Object v = readValue();
      skipWs();
      if (pos != src.length()) throw new IOException("Trailing JSON content at " + pos);
      return v;
    }

    private Object readValue() throws IOException {
      skipWs();
      if (pos >= src.length()) throw new IOException("Unexpected end of JSON");
      char c = src.charAt(pos);
      if (c == '{') return readObject();
      if (c == '[') return readArray();
      if (c == '"') return readString();
      if (c == 't') { expect("true"); return Boolean.TRUE; }
      if (c == 'f') { expect("false"); return Boolean.FALSE; }
      if (c == 'n') { expect("null"); return null; }
      if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
      throw new IOException("Unexpected JSON char '" + c + "' at " + pos);
    }

    private Map<String, Object> readObject() throws IOException {
      pos++; // '{'
      LinkedHashMap<String, Object> map = new LinkedHashMap<>();
      skipWs();
      if (peek('}')) { pos++; return map; }
      while (true) {
        skipWs();
        if (!peek('"')) throw new IOException("Expected JSON string key at " + pos);
        String key = readString();
        skipWs();
        if (!peek(':')) throw new IOException("Expected ':' at " + pos);
        pos++;
        map.put(key, readValue());
        skipWs();
        if (peek(',')) { pos++; continue; }
        if (peek('}')) { pos++; return map; }
        throw new IOException("Expected ',' or '}' at " + pos);
      }
    }

    private List<Object> readArray() throws IOException {
      pos++; // '['
      ArrayList<Object> list = new ArrayList<>();
      skipWs();
      if (peek(']')) { pos++; return list; }
      while (true) {
        list.add(readValue());
        skipWs();
        if (peek(',')) { pos++; continue; }
        if (peek(']')) { pos++; return list; }
        throw new IOException("Expected ',' or ']' at " + pos);
      }
    }

    private String readString() throws IOException {
      pos++; // '"'
      StringBuilder sb = new StringBuilder();
      while (pos < src.length()) {
        char c = src.charAt(pos++);
        if (c == '"') return sb.toString();
        if (c == '\\') {
          if (pos >= src.length()) throw new IOException("Unterminated JSON escape");
          char e = src.charAt(pos++);
          switch (e) {
            case '"': sb.append('"'); break;
            case '\\': sb.append('\\'); break;
            case '/': sb.append('/'); break;
            case 'b': sb.append('\b'); break;
            case 'f': sb.append('\f'); break;
            case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break;
            case 't': sb.append('\t'); break;
            case 'u':
              if (pos + 4 > src.length()) throw new IOException("Short unicode escape");
              try {
                sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
              } catch (NumberFormatException ex) {
                throw new IOException("Invalid unicode escape", ex);
              }
              pos += 4;
              break;
            default:
              throw new IOException("Invalid JSON escape: \\" + e);
          }
        } else if (c < 0x20) {
          throw new IOException("Unescaped control char in JSON string");
        } else {
          sb.append(c);
        }
      }
      throw new IOException("Unterminated JSON string");
    }

    private Number readNumber() throws IOException {
      int start = pos;
      if (peek('-')) pos++;
      while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
      boolean floating = false;
      if (peek('.')) {
        floating = true;
        pos++;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
      }
      if (peek('e') || peek('E')) {
        floating = true;
        pos++;
        if (peek('+') || peek('-')) pos++;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
      }
      String token = src.substring(start, pos);
      try {
        return floating ? Double.valueOf(token) : Long.valueOf(token);
      } catch (NumberFormatException e) {
        throw new IOException("Invalid JSON number: " + token, e);
      }
    }

    private void expect(String literal) throws IOException {
      if (!src.startsWith(literal, pos)) throw new IOException("Expected " + literal + " at " + pos);
      pos += literal.length();
    }

    private boolean peek(char expected) {
      return pos < src.length() && src.charAt(pos) == expected;
    }

    private void skipWs() {
      while (pos < src.length()) {
        char c = src.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
        else break;
      }
    }
  }
}
