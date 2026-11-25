package com.neotalkie.device;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Offline classification and redaction regression checks; no network calls. */
public final class ServiceCheckResultTest {
  private static int checks;

  private static void equal(String expected, String actual) {
    checks++;
    if (!expected.equals(actual)) throw new AssertionError("diagnostic check " + checks);
    if (actual.contains("secret")) throw new AssertionError("sensitive exception leaked");
  }

  public static void main(String[] args) {
    equal("鉴权未通过", ServiceCheckResult.httpError(401));
    equal("鉴权未通过", ServiceCheckResult.httpError(403));
    equal("请求受到限制", ServiceCheckResult.httpError(429));
    equal("服务暂不可用", ServiceCheckResult.httpError(503));
    equal("接口未通过", ServiceCheckResult.httpError(404));
    equal("域名解析失败", ServiceCheckResult.networkError(new UnknownHostException("secret")));
    equal("连接超时", ServiceCheckResult.networkError(new SocketTimeoutException("secret")));
    equal(
        "安全连接失败",
        ServiceCheckResult.networkError(new IOException("secret", new SSLException("secret"))));
    equal("网络连接失败", ServiceCheckResult.networkError(new IOException("Bearer secret")));
    equal("网络连接失败", ServiceCheckResult.networkError(null));
    System.out.println("Offline service diagnostic checks passed: " + checks);
  }
}
