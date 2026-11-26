package com.neotalkie.device;

import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONException;
import org.json.JSONObject;

final class VoiceSocket extends WebSocketListener implements java.io.Closeable {
  private static final class Packet {
    final byte[] bytes;
    final long received;

    Packet(byte[] bytes, long received) {
      this.bytes = bytes;
      this.received = received;
    }
  }

  private final ArrayBlockingQueue<Packet> incoming = new ArrayBlockingQueue<>(64);
  private final CountDownLatch opened = new CountDownLatch(1);
  private final AtomicInteger queuedBytes = new AtomicInteger();
  private volatile IOException failure;
  private volatile boolean closed;
  private final WebSocket socket;
  private long firstReceived, lastReceived, receivedBytes, receivedMessages;
  private long maxArrivalGap, maxConsumerLag, maxBufferedBytes;
  private volatile long packetReceived;

  private final boolean playback;

  VoiceSocket(OkHttpClient client, Request request, boolean playback) {
    this.playback = playback;
    socket = client.newWebSocket(request, this);
  }

  public void onOpen(WebSocket ws, Response response) {
    opened.countDown();
  }

  public void onMessage(WebSocket ws, ByteString bytes) {
    if (closed) return;
    long now = SystemClock.elapsedRealtime();
    // TTS can generate a long answer faster than the speaker consumes it. Let the
    // WebSocket reader wait for buffer space, keeping playback memory bounded.
    if (playback && bytes.size() <= Protocol.MAX) {
      synchronized (this) {
        while (!closed && (incoming.remainingCapacity() == 0 || queuedBytes.get() + bytes.size() > Protocol.MAX)) {
          try { wait(); }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        if (closed) return;
      }
    }
    int total = queuedBytes.addAndGet(bytes.size());
    if (bytes.size() > Protocol.MAX
        || total > Protocol.MAX
        || !incoming.offer(new Packet(bytes.toByteArray(), now))) {
      queuedBytes.addAndGet(-bytes.size());
      failure = new IOException("语音接收缓冲区溢出");
      ws.cancel();
    } else {
      synchronized (this) {
        if (firstReceived == 0) firstReceived = now;
        if (lastReceived != 0) maxArrivalGap = Math.max(maxArrivalGap, now - lastReceived);
        lastReceived = now;
        receivedBytes += bytes.size();
        receivedMessages++;
        maxBufferedBytes = Math.max(maxBufferedBytes, total);
      }
    }
  }

  public void onMessage(WebSocket ws, String text) {
    failure = new IOException("收到非预期语音协议");
    ws.cancel();
  }

  public void onFailure(WebSocket ws, Throwable t, Response response) {
    failure =
        new IOException(
            response == null
                ? "语音网络连接失败（" + t.getClass().getSimpleName() + "）"
                : "语音鉴权/连接失败 HTTP " + response.code());
    opened.countDown();
  }

  public void onClosing(WebSocket ws, int code, String reason) {
    closed = true;
    ws.close(code, null);
  }

  public void send(byte[] bytes) throws Exception {
    if (!opened.await(12, TimeUnit.SECONDS)) throw new IOException("语音连接超时");
    check();
    if (socket.queueSize() > 256000 || !socket.send(ByteString.of(bytes)))
      throw new IOException("语音上传受阻");
  }

  public byte[] poll(long millis) throws Exception {
    check();
    Packet p = incoming.poll(millis, TimeUnit.MILLISECONDS);
    if (p == null) {
      check();
      return null;
    }
    queuedBytes.addAndGet(-p.bytes.length);
    packetReceived = p.received;
    synchronized (this) {
      if (playback) notifyAll();
      maxConsumerLag = Math.max(maxConsumerLag, SystemClock.elapsedRealtime() - p.received);
    }
    return p.bytes;
  }

  long packetReceivedMs() {
    return packetReceived;
  }

  synchronized JSONObject metrics(long origin) throws JSONException {
    return new JSONObject()
        .put("receivedBytes", receivedBytes)
        .put("receivedMessages", receivedMessages)
        .put("firstReceivedMs", firstReceived == 0 ? JSONObject.NULL : firstReceived - origin)
        .put("lastReceivedMs", lastReceived == 0 ? JSONObject.NULL : lastReceived - origin)
        .put("maxArrivalGapMs", maxArrivalGap)
        .put("maxConsumerLagMs", maxConsumerLag)
        .put("maxBufferedBytes", maxBufferedBytes)
        .put("pendingUploadBytes", socket.queueSize());
  }

  long queueSize() {
    return socket.queueSize();
  }

  public byte[] next() throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    while (System.nanoTime() < end) {
      byte[] b = poll(100);
      if (b != null) return b;
    }
    throw new IOException("语音服务响应超时");
  }

  private void check() throws IOException {
    if (failure != null) throw failure;
    if (closed && incoming.isEmpty()) throw new IOException("语音连接已关闭");
  }

  public void close() {
    synchronized (this) {
      closed = true;
      notifyAll();
    }
    opened.countDown();
    socket.cancel();
    incoming.clear();
  }
}
