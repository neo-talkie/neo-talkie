package com.neotalkie.device;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Safe, bounded diagnostics: never display provider bodies, credentials or exception messages. */
final class ServiceCheckResult {
  final String name, message, detail;
  final boolean success;
  final long elapsedMs;

  ServiceCheckResult(String name, boolean success, String message, String detail, long elapsedMs) {
    this.name = name;
    this.success = success;
    this.message = message;
    this.detail = detail;
    this.elapsedMs = elapsedMs;
  }

  static String httpError(int code) {
    if (code == 401 || code == 403) return UiContract.T_TEXT_144;
    if (code == 429) return UiContract.T_TEXT_127;
    if (code >= 500) return UiContract.T_TEXT_060;
    return UiContract.T_TEXT_048;
  }

  static String networkError(Throwable error) {
    for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
      if (error instanceof UnknownHostException) return UiContract.T_TEXT_023;
      if (error instanceof SocketTimeoutException) return UiContract.T_TEXT_142;
      if (error instanceof SSLException) return UiContract.T_TEXT_027;
    }
    return UiContract.T_TEXT_108;
  }
}
