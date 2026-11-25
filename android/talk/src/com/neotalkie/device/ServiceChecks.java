package com.neotalkie.device;

import android.os.SystemClock;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.ByteString;

/** Only invoked by an explicit status-page button; no microphone, generation or playback. */
final class ServiceChecks implements java.io.Closeable {
  static final String[] NAMES = UiContract.SERVICES;
  private final Config config;
  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .callTimeout(12, TimeUnit.SECONDS)
          .connectTimeout(8, TimeUnit.SECONDS)
          .readTimeout(8, TimeUnit.SECONDS)
          .writeTimeout(8, TimeUnit.SECONDS)
          .retryOnConnectionFailure(false)
          .followRedirects(false)
          .followSslRedirects(false)
          .build();
  private volatile boolean cancelled;
  private volatile Call call;
  private volatile WebSocket socket;
  private volatile CountDownLatch waiting;

  ServiceChecks(Config config) {
    this.config = config;
  }

  ServiceCheckResult check(int service) throws InterruptedException {
    if (cancelled) throw new InterruptedException();
    long start = SystemClock.elapsedRealtime();
    if (config == null)
      return new ServiceCheckResult(NAMES[service], false, UiContract.T_TEXT_032, UiContract.T_TEXT_123, 0);
    ServiceCheckResult result = service == 1 ? models(start) : speech(service, start);
    if (cancelled) throw new InterruptedException();
    return result;
  }

  private ServiceCheckResult models(long start) {
    try {
      call =
          client.newCall(
              new Request.Builder()
                  .url("https://ark.cn-beijing.volces.com/api/v3/models")
                  .header("Authorization", "Bearer " + config.arkKey)
                  .get()
                  .build());
      if (cancelled) call.cancel();
      try (Response response = call.execute()) {
        int code = response.code();
        return result(
            1,
            response.isSuccessful(),
            response.isSuccessful() ? UiContract.T_TEXT_049 : ServiceCheckResult.httpError(code),
            response.isSuccessful() ? UiContract.T_TEXT_062 : "HTTP " + code,
            start);
      }
    } catch (IOException e) {
      return result(1, false, ServiceCheckResult.networkError(e), UiContract.T_TEXT_126, start);
    } finally {
      call = null;
    }
  }

  private ServiceCheckResult speech(final int service, final long start)
      throws InterruptedException {
    final boolean tts = service == 2;
    final CountDownLatch done = new CountDownLatch(1);
    final AtomicReference<ServiceCheckResult> outcome = new AtomicReference<>();
    waiting = done;
    Request.Builder request =
        new Request.Builder()
            .url(
                tts
                    ? "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
                    : "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async")
            .header("X-Api-Key", config.voiceKey)
            .header("X-Api-Resource-Id", tts ? "seed-tts-2.0" : "volc.seedasr.sauc.duration");
    if (tts) request.header("X-Api-Connect-Id", UUID.randomUUID().toString());
    else
      request
          .header("X-Api-Request-Id", UUID.randomUUID().toString())
          .header("X-Api-Sequence", "-1");
    socket =
        client.newWebSocket(
            request.build(),
            new WebSocketListener() {
              void finish(ServiceCheckResult value) {
                if (outcome.compareAndSet(null, value)) done.countDown();
              }

              public void onOpen(WebSocket ws, Response response) {
                if (cancelled) {
                  ws.cancel();
                  return;
                }
                if (!tts) finish(result(service, true, UiContract.T_TEXT_139, UiContract.T_TEXT_064, start));
                else
                  try {
                    if (!ws.send(ByteString.of(Protocol.tts(1, "", "{}"))))
                      finish(result(service, false, UiContract.T_TEXT_138, UiContract.T_TEXT_132, start));
                  } catch (IOException e) {
                    finish(result(service, false, UiContract.T_TEXT_141, UiContract.T_TEXT_132, start));
                  }
              }

              public void onMessage(WebSocket ws, ByteString bytes) {
                if (!tts) return;
                try {
                  if (bytes.size() > 65536) throw new IOException();
                  Protocol.Frame frame = Protocol.decode(bytes.toByteArray(), false);
                  if (frame.error != 0 || frame.event == 51)
                    finish(result(service, false, UiContract.T_TEXT_059, UiContract.T_TEXT_125, start));
                  else if (frame.event == 50)
                    finish(result(service, true, UiContract.T_TEXT_139, UiContract.T_TEXT_063, start));
                  else finish(result(service, false, UiContract.T_TEXT_020, UiContract.T_TEXT_132, start));
                } catch (IOException e) {
                  finish(result(service, false, UiContract.T_TEXT_020, UiContract.T_TEXT_132, start));
                }
              }

              public void onMessage(WebSocket ws, String text) {
                finish(result(service, false, UiContract.T_TEXT_020, UiContract.T_TEXT_125, start));
              }

              public void onFailure(WebSocket ws, Throwable error, Response response) {
                finish(
                    result(
                        service,
                        false,
                        response == null
                            ? ServiceCheckResult.networkError(error)
                            : ServiceCheckResult.httpError(response.code()),
                        response == null ? UiContract.T_TEXT_126 : "HTTP " + response.code(),
                        start));
              }

              public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, null);
                finish(result(service, false, UiContract.T_TEXT_058, UiContract.T_TEXT_132, start));
              }
            });
    try {
      if (cancelled) {
        socket.cancel();
        throw new InterruptedException();
      }
      if (!done.await(12, TimeUnit.SECONDS))
        return result(service, false, UiContract.T_TEXT_142, UiContract.T_TEXT_126, start);
      if (cancelled) throw new InterruptedException();
      ServiceCheckResult result = outcome.get();
      return result == null ? result(service, false, UiContract.T_TEXT_075, "", start) : result;
    } finally {
      socket.cancel();
      socket = null;
      waiting = null;
    }
  }

  private ServiceCheckResult result(
      int service, boolean success, String message, String detail, long start) {
    return new ServiceCheckResult(
        NAMES[service], success, message, detail, SystemClock.elapsedRealtime() - start);
  }

  public void close() {
    cancelled = true;
    Call current = call;
    if (current != null) current.cancel();
    WebSocket ws = socket;
    if (ws != null) ws.cancel();
    CountDownLatch latch = waiting;
    if (latch != null) latch.countDown();
    client.dispatcher().cancelAll();
    client.connectionPool().evictAll();
    client.dispatcher().executorService().shutdown();
  }
}
