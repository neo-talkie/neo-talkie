package com.neotalkie.device;

import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS helper for Android 5.1.1 OTA downloads from GitHub Releases.
 *
 * <p>Android 5.1.1's system trust store includes USERTrust ECC (used by github.com) but predates
 * ISRG Root X1 (used by release-assets.githubusercontent.com / objects.githubusercontent.com).
 * This factory trusts both the system CAs and the public ISRG Root X1 CA, and ensures TLS 1.2 is
 * enabled on every socket.
 */
final class OtaTls {
  private static final int MAX_REDIRECTS = 6;

  // Public X.509 DER certificate for ISRG Root X1 (valid until 2035-06-04).
  private static final String ISRG_ROOT_X1_DER_B64 =
      "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";

  private static volatile SSLSocketFactory cachedFactory;

  static HttpURLConnection openGet(
      String initialUrl, String userAgent, int connectTimeoutMs, int readTimeoutMs)
      throws IOException {
    String currentUrl = initialUrl;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      if (!OtaManifest.isAllowedUrl(currentUrl)) {
        throw new IOException("Refused redirect to non-HTTPS URL: " + currentUrl);
      }
      URL url = new URL(currentUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      if (conn instanceof HttpsURLConnection) {
        ((HttpsURLConnection) conn).setSSLSocketFactory(socketFactory());
      }
      conn.setInstanceFollowRedirects(false);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(connectTimeoutMs);
      conn.setReadTimeout(readTimeoutMs);
      conn.setUseCaches(false);
      conn.setRequestProperty("User-Agent", userAgent);
      conn.setRequestProperty("Accept", "*/*");
      int code = conn.getResponseCode();
      if (code == HttpURLConnection.HTTP_MOVED_PERM
          || code == HttpURLConnection.HTTP_MOVED_TEMP
          || code == HttpURLConnection.HTTP_SEE_OTHER
          || code == 307
          || code == 308) {
        String location = conn.getHeaderField("Location");
        conn.disconnect();
        if (location == null || location.trim().isEmpty()) {
          throw new IOException("Redirect without Location header");
        }
        currentUrl = new URL(url, location.trim()).toString();
        continue;
      }
      return conn;
    }
    throw new IOException("Too many redirects");
  }

  private static SSLSocketFactory socketFactory() throws IOException {
    SSLSocketFactory existing = cachedFactory;
    if (existing != null) return existing;
    synchronized (OtaTls.class) {
      if (cachedFactory != null) return cachedFactory;
      try {
        X509TrustManager systemTm = trustManagerFor(null);
        X509TrustManager extraTm = trustManagerFor(bundledKeyStore());
        CompositeTrustManager combined = new CompositeTrustManager(systemTm, extraTm);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {combined}, null);
        cachedFactory = new Tls12SocketFactory(ctx.getSocketFactory());
        return cachedFactory;
      } catch (GeneralSecurityException e) {
        throw new IOException("Failed to initialize OTA TLS context", e);
      }
    }
  }

  private static KeyStore bundledKeyStore() throws GeneralSecurityException, IOException {
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    ks.load(null, null);
    byte[] der = Base64.decode(ISRG_ROOT_X1_DER_B64, Base64.DEFAULT);
    Certificate cert =
        CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    ks.setCertificateEntry("isrg-root-x1", cert);
    return ks;
  }

  private static X509TrustManager trustManagerFor(KeyStore ks) throws GeneralSecurityException {
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ks);
    for (TrustManager tm : tmf.getTrustManagers()) {
      if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
    }
    throw new GeneralSecurityException("No X509TrustManager available");
  }

  private static final class CompositeTrustManager implements X509TrustManager {
    private final X509TrustManager[] delegates;

    CompositeTrustManager(X509TrustManager... delegates) {
      this.delegates = delegates;
    }

    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      CertificateException last = null;
      for (X509TrustManager tm : delegates) {
        try {
          tm.checkClientTrusted(chain, authType);
          return;
        } catch (CertificateException e) {
          last = e;
        }
      }
      throw last != null ? last : new CertificateException("Untrusted client certificate");
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      CertificateException last = null;
      for (X509TrustManager tm : delegates) {
        try {
          tm.checkServerTrusted(chain, authType);
          return;
        } catch (CertificateException e) {
          last = e;
        }
      }
      throw last != null ? last : new CertificateException("Untrusted server certificate");
    }

    public X509Certificate[] getAcceptedIssuers() {
      ArrayList<X509Certificate> all = new ArrayList<>();
      for (X509TrustManager tm : delegates) all.addAll(Arrays.asList(tm.getAcceptedIssuers()));
      return all.toArray(new X509Certificate[0]);
    }
  }

  private static final class Tls12SocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;

    Tls12SocketFactory(SSLSocketFactory delegate) {
      this.delegate = delegate;
    }

    public String[] getDefaultCipherSuites() {
      return delegate.getDefaultCipherSuites();
    }

    public String[] getSupportedCipherSuites() {
      return delegate.getSupportedCipherSuites();
    }

    public Socket createSocket(Socket s, String host, int port, boolean autoClose)
        throws IOException {
      return patch(delegate.createSocket(s, host, port, autoClose));
    }

    public Socket createSocket(String host, int port) throws IOException {
      return patch(delegate.createSocket(host, port));
    }

    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException {
      return patch(delegate.createSocket(host, port, localHost, localPort));
    }

    public Socket createSocket(InetAddress host, int port) throws IOException {
      return patch(delegate.createSocket(host, port));
    }

    public Socket createSocket(
        InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
      return patch(delegate.createSocket(address, port, localAddress, localPort));
    }

    private static Socket patch(Socket socket) {
      if (socket instanceof SSLSocket) {
        SSLSocket ssl = (SSLSocket) socket;
        ArrayList<String> enabled = new ArrayList<>();
        for (String p : ssl.getSupportedProtocols()) {
          if ("TLSv1.2".equals(p) || "TLSv1.3".equals(p)) enabled.add(p);
        }
        if (!enabled.isEmpty()) ssl.setEnabledProtocols(enabled.toArray(new String[0]));
      }
      return socket;
    }
  }
}
