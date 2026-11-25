package com.neotalkie.device;

import android.media.*;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.*;

/** On-device streaming ASR -> Ark Responses SSE -> Seed TTS, no Python/server bridge. */
final class SpeechEngine {
  interface Listener {
    void status(Turn t, String state, String detail);

    void level(Turn t, int level);

    void done(Turn t, byte[] reply);

    void contextChanged();
  }

  static final class Turn {
    final long started;
    final long cpuStarted = android.os.Process.getElapsedCpuTime();
    final Map<String, Long> timeline = new LinkedHashMap<>();
    final ArrayBlockingQueue<byte[]> input = new ArrayBlockingQueue<>(25);
    volatile boolean cancelled, recording = true, inputDone;
    volatile long captureStarted, processingStarted;
    volatile boolean captureLimited;
    boolean captureCheck;
    volatile boolean shortInput;
    boolean liveSpeech;
    volatile long capturedBytes;
    volatile Exception inputFailure;
    volatile AudioRecord recorder;
    volatile AudioTrack track;
    float playbackGain = -1f;
    volatile Call call;
    volatile VoiceSocket asr, tts;
    volatile String partialText = "", userText = "", replyText = "";
    volatile boolean interruptedByPtt, playbackFinished;
    volatile String interruptedUser = "", interruptedReply = "";
    long continuedFromTurn, memoryGeneration;
    final Object audioLock = new Object();
    List<String[]> history = Collections.emptyList();
    volatile long inputBytes, audioBytes, recordEnd, firstAudio;
    volatile boolean limited;
    volatile long bytesBeforeRelease, maxUploadQueue, cpuToFirstWrite;
    volatile int maxInputQueue, searchCalls;
    volatile int reasoningTokens = -1;
    volatile String inputMode = "microphone";
    volatile long lastAudioArrival, maxAudioArrivalGap, maxAudioWriteMs, audioFramesWritten;
    volatile long estimatedBufferEnd, starvedMs, mainThreadMaxLag;
    volatile int starvationEvents;

    Turn() {
      this(SystemClock.elapsedRealtime());
    }

    Turn(long started) {
      this.started = started;
    }

    synchronized boolean mark(String name) {
      if (timeline.containsKey(name)) return false;
      timeline.put(name, SystemClock.elapsedRealtime() - started);
      return true;
    }

    private Object span(String start, String end) {
      Long a = timeline.get(start), b = timeline.get(end);
      return a == null || b == null ? JSONObject.NULL : b - a;
    }

    synchronized JSONObject metrics() throws JSONException {
      return new JSONObject()
          .put("turnStartedMs", started)
          .put("timelineMs", new JSONObject(timeline))
          .put("interruptedByPtt", interruptedByPtt)
          .put("continuedFromTurnMs", continuedFromTurn == 0 ? JSONObject.NULL : continuedFromTurn)
          .put("bytesQueuedBeforeRelease", bytesBeforeRelease)
          .put("maxInputQueuePackets", maxInputQueue)
          .put("maxWebSocketQueueBytes", maxUploadQueue)
          .put("cpuToFirstWriteMs", cpuToFirstWrite)
          .put("searchCalls", searchCalls)
          .put("reasoningTokens", reasoningTokens)
          .put("inputMode", inputMode)
          .put("captureLimited", captureLimited)
          .put("captureLimitMs", RecordingFeedback.MAX_MILLIS)
          .put("shortInput", shortInput)
          .put("capturedAudioMs", capturedBytes / 32)
          .put("contextTurns", history.size())
          .put("asrSocket", asr == null ? JSONObject.NULL : asr.metrics(started))
          .put("ttsSocket", tts == null ? JSONObject.NULL : tts.metrics(started))
          .put("maxAudioArrivalGapMs", maxAudioArrivalGap)
          .put("maxAudioWriteMs", maxAudioWriteMs)
          .put("playbackStarvationEvents", starvationEvents)
          .put("estimatedPlaybackStarvedMs", starvedMs)
          .put("mainThreadMaxLagMs", mainThreadMaxLag)
          .put("asrTailMs", span("recordEnd", "asrFinal"))
          .put("llmFirstTextMs", span("llmRequest", "llmFirstText"))
          .put("textToAudioMs", span("llmFirstText", "ttsFirstAudio"))
          .put("audioToWriteMs", span("ttsFirstAudio", "playbackFirstWrite"))
          .put("releaseToFirstWriteMs", span("releaseRequested", "playbackFirstWrite"));
    }

    void stopInput() {
      if (recording) {
        mark("releaseRequested");
        if (processingStarted == 0) processingStarted = SystemClock.elapsedRealtime();
      }
      recording = false;
      AudioRecord r = recorder;
      if (r != null) {
        try {
          r.stop();
        } catch (IllegalStateException ignored) {
        }
      }
    }

    void cancel() {
      mark("cancelRequested");
      cancelled = true;
      // Silence first. Network teardown and the worker's finally block must not delay this.
      synchronized (audioLock) {
        AudioTrack p = track;
        if (p != null) {
          try {
            p.pause();
            p.flush();
          } catch (IllegalStateException ignored) {
          }
        }
      }
      mark("playbackSilenced");
      stopInput();
      if (call != null) call.cancel();
      if (asr != null) asr.close();
      if (tts != null) tts.close();
    }

    void interruptForPtt() {
      if (!playbackFinished) {
        interruptedUser = userText;
        interruptedReply = replyText;
        interruptedByPtt = true;
      }
      cancel();
    }

    void check() throws IOException {
      if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException(UiContract.T_TEXT_033);
      if (SystemClock.elapsedRealtime() - started > 120000) throw new IOException("本轮超时");
    }
  }

  private final Config config;
  private final AudioManager audio;
  private final Listener listener;
  private final OkHttpClient client =
      new OkHttpClient.Builder()
          .connectTimeout(12, TimeUnit.SECONDS)
          .readTimeout(45, TimeUnit.SECONDS)
          .writeTimeout(15, TimeUnit.SECONDS)
          .retryOnConnectionFailure(false)
          .build();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService streams = Executors.newFixedThreadPool(2);
  private final ScheduledExecutorService captureTimer =
      Executors.newSingleThreadScheduledExecutor();
  private final ConversationMemory memory;
  private final Object microphoneLock = new Object();

  SpeechEngine(Config config, Listener listener, File historyFile, AudioManager audio) {
    this.config = config;
    this.audio = audio;
    this.listener = listener;
    memory = new ConversationMemory(historyFile);
    memory.snapshot(System.currentTimeMillis());
  }

  long contextRemaining(long now) {
    return memory.remainingMillis(now);
  }

  void clearConversation() throws IOException {
    memory.clear();
  }

  void start(final Turn t, final File inputFile, final boolean localOnly) {
    t.memoryGeneration = memory.generation();
    t.inputMode = inputFile == null ? "microphone" : "pacedPcm";
    t.liveSpeech = !localOnly;
    // Begin capture without waiting for the old network turn or history disk write.
    // Serializing microphone ownership also protects very fast release/press sequences.
    final Thread mic =
        new Thread(
            new Runnable() {
              public void run() {
                synchronized (microphoneLock) {
                  record(t, inputFile);
                }
              }
            },
            "neotalkie-microphone");
    if (config != null || localOnly) mic.start();
    worker.execute(
        new Runnable() {
          public void run() {
            boolean completed = false;
            try {
              t.check();
              // The preceding worker saves interrupted context before this snapshot.
              if (!localOnly) t.history = memory.snapshot(System.currentTimeMillis());
              if (config == null && !localOnly) throw new IOException("尚未配置语音密钥");
              if (localOnly && t.captureCheck) localCaptureCheck(t);
              else if (localOnly) localLoopback(t);
              else {
                String text = recognize(t);
                if (t.shortInput) {
                  listener.status(t, UiContract.eventLabel("shortInput"), UiContract.T_REPEAT_SPEECH);
                  listener.done(t, null);
                  return;
                }
                if (text.isEmpty()) {
                  listener.status(t, UiContract.eventLabel("emptyInput"), UiContract.T_REPEAT_SPEECH);
                  playPcm(t, doubleTone());
                  listener.done(t, null);
                  return;
                }
                t.partialText = text;
                t.userText = text;
                listener.status(t, UiContract.eventLabel("recognitionDone"), UiContract.T_PLEASE_WAIT);
                byte[] reply = generate(t, text);
                completed = !t.limited;
                Log.i(
                    "NeoTalkie",
                    "turn_complete input_bytes="
                        + t.inputBytes
                        + " output_bytes="
                        + t.audioBytes
                        + " elapsed_ms="
                        + (SystemClock.elapsedRealtime() - t.started)
                        + " first_audio_ms="
                        + (t.firstAudio - t.recordEnd)
                        + " limited="
                        + t.limited);
                listener.status(t, UiContract.eventLabel("playbackDone"), t.replyText);
                listener.done(t, reply);
              }
            } catch (Exception e) {
              if (!t.cancelled) {
                if (t.shortInput) {
                  listener.status(t, UiContract.eventLabel("shortInput"), UiContract.T_REPEAT_SPEECH);
                  listener.done(t, null);
                  return;
                }
                Log.w("NeoTalkie", "turn_failed " + e.getClass().getSimpleName());
                // Provider response bodies and credential-bearing exceptions never reach logs/UI.
                listener.status(t, UiContract.eventLabel("failure"), safeError(e));
                listener.done(t, null);
              }
            } finally {
              t.stopInput();
              if (t.asr != null) t.asr.close();
              if (t.tts != null) t.tts.close();
              if (t.call != null) t.call.cancel();
              if (mic != null) {
                try {
                  mic.join(1500);
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
              }
              releaseTrack(t);
              // Exactly one history entry, committed before the next queued request. A
              // stopped replay/local check has no userText and cannot duplicate history.
              try {
                if (t.interruptedByPtt) {
                  memory.rememberIfCurrent(
                      t.memoryGeneration,
                      t.interruptedUser,
                      t.interruptedReply,
                      System.currentTimeMillis(),
                      true);
                } else if (completed) {
                  memory.rememberIfCurrent(
                      t.memoryGeneration,
                      t.userText,
                      t.replyText,
                      System.currentTimeMillis(),
                      false);
                }
              } catch (IOException e) {
                Log.w("NeoTalkie", "history_save_failed");
              }
              listener.contextChanged();
              try {
                Log.i("NeoTalkie", "latency " + t.metrics());
              } catch (JSONException ignored) {
              }
            }
          }
        });
  }

  private String safeError(Exception e) {
    String s = e.getMessage();
    if (s == null) return UiContract.T_TEXT_129;
    if (s.contains("配置")) return UiContract.T_TEXT_122;
    if (s.contains("麦克风") || s.contains("录音")) return "录音异常";
    if (s.contains("扬声器") || s.contains("播放")) return "播放异常";
    if (s.contains("超时") || s.toLowerCase(Locale.ROOT).contains("timeout")) return UiContract.T_TEXT_142;
    if (s.contains("网络")) return "请检查网络";
    return UiContract.T_TEXT_129;
  }

  private void record(Turn t, File inputFile) {
    AudioRecord r = null;
    ScheduledFuture<?> deadline = null;
    try {
      t.check();
      if (inputFile != null) {
        deadline = beginCapture(t);
        try (InputStream in = new FileInputStream(inputFile)) {
          byte[] b = new byte[6400];
          int n;
          long total = 0;
          while (t.recording && (n = in.read(b)) != -1) {
            t.check();
            total += n;
            t.capturedBytes = total;
            if ((n & 1) != 0 || total > RecordingFeedback.MAX_BYTES)
              throw new IOException("输入 PCM 超出限制");
            if (!t.input.offer(Arrays.copyOf(b, n), 12, TimeUnit.SECONDS))
              throw new IOException("录音发送超时");
            t.maxInputQueue = Math.max(t.maxInputQueue, t.input.size());
            // Match microphone cadence; the streaming ASR endpoint expects paced audio.
            Thread.sleep(n / 32);
          }
        }
        return;
      }
      int min =
          AudioRecord.getMinBufferSize(
              16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (min <= 0) throw new IOException("设备不支持16kHz录音");
      r =
          new AudioRecord(
              MediaRecorder.AudioSource.MIC,
              16000,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              Math.max(min * 2, 12800));
      if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("麦克风初始化失败");
      t.recorder = r;
      t.check();
      if (!t.recording) return;
      r.startRecording();
      deadline = beginCapture(t);
      if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IOException("麦克风被占用");
      listener.status(t, UiContract.eventLabel("pttDown"), UiContract.T_RELEASE_SEND);
      byte[] b = new byte[6400];
      long total = 0;
      long peakSum = 0, rmsSum = 0;
      int peakMax = 0, rmsMax = 0, meterMax = 0, meterSamples = 0;
      RecordingFeedback.PcmMeter pcmMeter = new RecordingFeedback.PcmMeter();
      while (t.recording && !t.cancelled && total < RecordingFeedback.MAX_BYTES) {
        int n = r.read(b, 0, (int) Math.min(b.length, RecordingFeedback.MAX_BYTES - total));
        if (n <= 0) {
          if (!t.recording) break;
          throw new IOException("录音读取失败 " + n);
        }
        total += n;
        t.capturedBytes = total;
        if (!t.input.offer(Arrays.copyOf(b, n))) throw new IOException("网络太慢，请重试");
        t.maxInputQueue = Math.max(t.maxInputQueue, t.input.size());
        int meter = pcmMeter.measure(b, n);
        int peak = pcmMeter.peak;
        rmsSum += pcmMeter.rms;
        rmsMax = Math.max(rmsMax, pcmMeter.rms);
        peakSum += peak;
        peakMax = Math.max(peakMax, peak);
        meterMax = Math.max(meterMax, meter);
        meterSamples++;
        listener.level(t, meter);
      }
      if (total >= RecordingFeedback.MAX_BYTES) limitCapture(t);
      Log.i(
          "NeoTalkie",
          "microphone_complete bytes="
              + total
              + " peakMax="
              + peakMax
              + " peakMean="
              + peakSum / Math.max(1, meterSamples)
              + " rmsMax="
              + rmsMax
              + " rmsMean="
              + rmsSum / Math.max(1, meterSamples)
              + " meterMax="
              + meterMax
              + " meterSamples="
              + meterSamples);
    } catch (Exception e) {
      if (!t.cancelled) t.inputFailure = e;
    } finally {
      if (deadline != null) deadline.cancel(false);
      t.recording = false;
      t.recorder = null;
      if (r != null) {
        try {
          r.stop();
        } catch (IllegalStateException ignored) {
        }
        r.release();
      }
      t.recordEnd = SystemClock.elapsedRealtime();
      if (t.processingStarted == 0) t.processingStarted = t.recordEnd;
      t.mark("recordEnd");
      // ASR starts immediately. Only after capture ends do we discard a short turn.
      // Closing also wakes a worker still waiting for the WebSocket handshake.
      if (t.liveSpeech
          && !t.cancelled
          && t.inputFailure == null
          && RecordingFeedback.tooShort(t.capturedBytes)) {
        t.shortInput = true;
        t.mark("shortInputRejected");
        if (t.asr != null) t.asr.close();
      }
      t.inputDone = true;
    }
  }

  private ScheduledFuture<?> beginCapture(final Turn t) {
    t.captureStarted = SystemClock.elapsedRealtime();
    t.mark("captureStarted");
    return captureTimer.schedule(
        new Runnable() {
          public void run() {
            limitCapture(t);
          }
        },
        RecordingFeedback.MAX_MILLIS,
        TimeUnit.MILLISECONDS);
  }

  private void limitCapture(Turn t) {
    synchronized (t) {
      if (!t.recording || t.cancelled) return;
      t.captureLimited = true;
      t.mark("captureLimitReached");
      t.stopInput();
      listener.status(t, UiContract.eventLabel("pttUp"), UiContract.T_CAPTURE_LIMIT);
    }
  }

  private String recognize(final Turn t) throws Exception {
    Request req =
        new Request.Builder()
            .url("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async")
            .header("X-Api-Key", config.voiceKey)
            .header("X-Api-Resource-Id", "volc.seedasr.sauc.duration")
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Sequence", "-1")
            .build();
    t.asr = new VoiceSocket(client, req);
    t.mark("asrConnectStarted");
    t.check();
    if (t.shortInput) {
      t.asr.close();
      return "";
    }
    t.asr.send(
        Protocol.asr(
            "{\"audio\":{\"format\":\"pcm\",\"codec\":\"raw\",\"rate\":16000,\"bits\":16,\"channel\":1},\"request\":{\"model_name\":\"bigmodel\"}}"
                .getBytes(Protocol.UTF8),
            false,
            false));
    t.mark("asrReady");
    String text = "";
    byte[] pending = null;
    while (!t.inputDone || !t.input.isEmpty()) {
      t.check();
      if (t.inputFailure != null) throw t.inputFailure;
      byte[] b = t.input.poll(100, TimeUnit.MILLISECONDS);
      if (b != null) {
        if (pending != null) {
          t.inputBytes += pending.length;
          t.asr.send(Protocol.asr(pending, true, false));
          t.mark("asrFirstAudioQueued");
          if (t.recording) t.bytesBeforeRelease += pending.length;
          t.maxUploadQueue = Math.max(t.maxUploadQueue, t.asr.queueSize());
        }
        pending = b;
      }
      byte[] response;
      while ((response = t.asr.poll(1)) != null) {
        Protocol.Frame f = Protocol.decode(response, true);
        checkFrame(f, "ASR");
        JSONObject o = new JSONObject(new String(f.body, Protocol.UTF8)).optJSONObject("result");
        if (o != null) text = o.optString("text", text);
        t.partialText = text;
        if (!text.isEmpty()) t.mark("asrFirstPartial");
        if (f.last) throw new IOException("识别服务提前结束");
      }
    }
    if (t.inputFailure != null) throw t.inputFailure;
    if (t.shortInput) {
      t.asr.close();
      return "";
    }
    listener.status(t, UiContract.eventLabel("pttUp"), t.captureLimited ? UiContract.T_CAPTURE_LIMIT : UiContract.T_PLEASE_WAIT);
    if (pending == null) return "";
    t.inputBytes += pending.length;
    t.asr.send(Protocol.asr(pending, true, true));
    t.mark("asrLastAudioQueued");
    while (true) {
      t.check();
      Protocol.Frame f = Protocol.decode(t.asr.next(), true);
      checkFrame(f, "ASR");
      JSONObject o = new JSONObject(new String(f.body, Protocol.UTF8)).optJSONObject("result");
      if (o != null) text = o.optString("text", text);
      t.partialText = text;
      if (f.last) {
        t.mark("asrFinal");
        t.asr.close();
        Log.i("NeoTalkie", "asr_complete chars=" + text.length());
        return text.trim();
      }
    }
  }

  private static void checkFrame(Protocol.Frame f, String stage) throws IOException {
    if (f.error != 0 || f.event == 51 || f.event == 151 || f.event == 153)
      throw new IOException(stage + " 服务错误 " + (f.error != 0 ? f.error : f.event));
  }

  private byte[] generate(final Turn t, final String text) throws Exception {
    final ArrayBlockingQueue<String> deltas = new ArrayBlockingQueue<>(256);
    final String end = new String("END");
    Future<?> llm =
        streams.submit(
            new Callable<Void>() {
              public Void call() throws Exception {
                ark(t, text, deltas);
                put(t, deltas, end);
                return null;
              }
            });
    Future<?> sender = null;
    try {
      final String sid = UUID.randomUUID().toString();
      t.tts =
          new VoiceSocket(
              client,
              new Request.Builder()
                  .url("wss://openspeech.bytedance.com/api/v3/tts/bidirection")
                  .header("X-Api-Key", config.voiceKey)
                  .header("X-Api-Resource-Id", "seed-tts-2.0")
                  .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                  .build());
      t.check();
      t.tts.send(Protocol.tts(1, "", "{}"));
      expect(t, 50, null);
      t.mark("ttsConnected");
      JSONObject params =
          new JSONObject()
              .put("speaker", config.speaker)
              .put("audio_params", new JSONObject().put("format", "pcm").put("sample_rate", 24000));
      t.tts.send(
          Protocol.tts(
              100,
              sid,
              new JSONObject()
                  .put("event", 100)
                  .put("namespace", "BidirectionalTTS")
                  .put("req_params", params)
                  .toString()));
      expect(t, 150, sid);
      t.mark("ttsSessionReady");
      final Future<?> llmTask = llm;
      sender =
          streams.submit(
              new Callable<Void>() {
                public Void call() throws Exception {
                  while (true) {
                    t.check();
                    String delta = deltas.poll(100, TimeUnit.MILLISECONDS);
                    if (delta == null) {
                      if (llmTask.isDone()) llmTask.get();
                      continue;
                    }
                    if (delta == end) break;
                    t.tts.send(
                        Protocol.tts(
                            200,
                            sid,
                            new JSONObject()
                                .put("event", 200)
                                .put("namespace", "BidirectionalTTS")
                                .put("req_params", new JSONObject().put("text", delta))
                                .toString()));
                    t.mark("ttsFirstTextQueued");
                  }
                  t.tts.send(Protocol.tts(102, sid, "{}"));
                  return null;
                }
              });
      ByteArrayOutputStream pcm = new ByteArrayOutputStream(48000);
      while (true) {
        t.check();
        if (sender.isDone()) sender.get();
        if (llm.isDone()) llm.get();
        byte[] message = t.tts.poll(100);
        if (message == null) continue;
        Protocol.Frame f = Protocol.decode(message, false);
        checkFrame(f, "TTS");
        if (!sid.equals(f.session)) throw new IOException("TTS 会话不匹配");
        if (f.event == 352 && f.body.length > 0) {
          long arrived = t.tts.packetReceivedMs();
          if (t.lastAudioArrival != 0)
            t.maxAudioArrivalGap = Math.max(t.maxAudioArrivalGap, arrived - t.lastAudioArrival);
          t.lastAudioArrival = arrived;
          if ((f.body.length & 1) != 0) throw new IOException("TTS 音频格式错误");
          int n = Math.min(f.body.length, 1440000 - pcm.size());
          if (t.firstAudio == 0) {
            t.firstAudio = SystemClock.elapsedRealtime();
            t.mark("ttsFirstAudio");
            listener.status(t, UiContract.eventLabel("reply"), t.replyText);
          }
          pcm.write(f.body, 0, n);
          writeAudio(t, f.body, n);
          t.audioBytes += n;
          if (pcm.size() >= 1440000) {
            t.limited = true;
            t.tts.send(Protocol.tts(101, sid, "{}"));
            break;
          }
        } else if (f.event == 152) break;
      }
      if (pcm.size() == 0) throw new IOException("没有收到回复音频");
      if (!t.limited) {
        llm.get(5, TimeUnit.SECONDS);
        sender.get(5, TimeUnit.SECONDS);
      }
      drain(t);
      t.playbackFinished = true;
      t.mark("playbackDone");
      return pcm.toByteArray();
    } finally {
      llm.cancel(true);
      if (sender != null) sender.cancel(true);
      if (t.call != null) t.call.cancel();
      if (t.tts != null) t.tts.close();
    }
  }

  private void expect(Turn t, int event, String sid) throws Exception {
    t.check();
    Protocol.Frame f = Protocol.decode(t.tts.next(), false);
    checkFrame(f, "TTS");
    if (f.event != event || (sid != null && !sid.equals(f.session)))
      throw new IOException("TTS 握手失败");
  }

  private static void put(Turn t, BlockingQueue<String> q, String value) throws Exception {
    t.check();
    if (!q.offer(value, 5, TimeUnit.SECONDS)) throw new IOException("回复文本队列超时");
  }

  private void ark(Turn t, String text, BlockingQueue<String> deltas) throws Exception {
    JSONArray input = new JSONArray();
    for (String[] pair : t.history) {
      input.put(new JSONObject().put("role", "user").put("content", pair[0]));
      input.put(new JSONObject().put("role", "assistant").put("content", pair[1]));
    }
    input.put(new JSONObject().put("role", "user").put("content", text));
    JSONObject req =
        new JSONObject()
            .put("model", config.model)
            .put("instructions", config.prompt)
            .put("input", input)
            .put("stream", true)
            .put("store", false)
            .put("max_output_tokens", 180)
            .put("thinking", new JSONObject().put("type", "disabled"));
    if (config.webSearch)
      req.put(
              "tools",
              new JSONArray()
                  .put(
                      new JSONObject()
                          .put("type", "web_search")
                          .put("sources", new JSONArray().put("search_engine"))
                          .put("max_keyword", 2)))
          .put("max_tool_calls", 2);
    t.call =
        client.newCall(
            new Request.Builder()
                .url("https://ark.cn-beijing.volces.com/api/v3/responses")
                .header("Authorization", "Bearer " + config.arkKey)
                .post(
                    RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"), req.toString()))
                .build());
    t.check();
    t.mark("llmRequest");
    try (Response response = t.call.execute()) {
      t.mark("llmHeaders");
      if (!response.isSuccessful()) throw new IOException("方舟请求失败 HTTP " + response.code());
      Sse sse =
          new Sse(
              new BufferedReader(
                  new InputStreamReader(response.body().byteStream(), Protocol.UTF8)));
      String raw;
      while ((raw = sse.next()) != null) {
        t.check();
        if (raw.equals("[DONE]")) break;
        JSONObject e = new JSONObject(raw);
        t.mark("llmFirstEvent");
        String kind = e.optString("type");
        if (kind.equals("response.web_search_call.in_progress")) {
          t.searchCalls++;
          t.mark("searchStarted");
          listener.status(t, UiContract.eventLabel("search"), UiContract.T_PLEASE_WAIT);
        } else if (kind.equals("response.web_search_call.completed")) {
          t.mark("searchFinished");
          listener.status(t, UiContract.eventLabel("recognitionDone"), UiContract.T_PLEASE_WAIT);
        }
        if (kind.equals("response.output_text.delta")) {
          String delta = e.optString("delta");
          int remaining = 120 - t.replyText.length();
          if (delta.length() > remaining) delta = delta.substring(0, remaining);
          if (!delta.isEmpty()) {
            boolean first = t.mark("llmFirstText");
            t.replyText += delta;
            if (first) listener.status(t, UiContract.eventLabel("synthesis"), t.replyText);
            put(t, deltas, delta);
          }
          if (t.replyText.length() >= 120) {
            return;
          }
        } else if (kind.equals("response.completed") || kind.equals("response.incomplete")) {
          t.mark("llmDone");
          JSONObject value = e.getJSONObject("response");
          JSONObject usage = value.optJSONObject("usage");
          JSONObject detail = usage == null ? null : usage.optJSONObject("output_tokens_details");
          if (detail != null) t.reasoningTokens = detail.optInt("reasoning_tokens", -1);
          if (t.replyText.isEmpty()) throw new IOException("模型没有返回文字");
          Log.i("NeoTalkie", "ark_complete chars=" + t.replyText.length());
          return;
        } else if (kind.equals("error") || kind.equals("response.failed"))
          throw new IOException("方舟生成失败");
      }
      throw new IOException("方舟响应提前结束");
    }
  }

  void updatePlaybackVolume(Turn t) {
    if (t == null || t.cancelled) return;
    synchronized (t.audioLock) {
      if (t.track == null) return;
      int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
      float gain = PlaybackVolume.gain(level);
      if (gain == t.playbackGain) return;
      if (t.track.setVolume(gain) == AudioTrack.SUCCESS) {
        t.playbackGain = gain;
        Log.i("NeoTalkie", "playback_gain level=" + level + " gain=" + gain);
      } else Log.w("NeoTalkie", "playback_gain_update_failed");
    }
  }

  private void writeAudio(Turn t, byte[] pcm, int n) throws Exception {
    synchronized (t.audioLock) {
      t.check();
      if (t.track == null) {
        int min =
            AudioTrack.getMinBufferSize(
                24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("设备不支持24kHz播放");
        t.track =
            new AudioTrack(
                AudioManager.STREAM_MUSIC,
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min * 2, 9600),
                AudioTrack.MODE_STREAM);
        if (t.track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("扬声器初始化失败");
        updatePlaybackVolume(t);
        t.track.play();
      }
    }
    for (int offset = 0; offset < n; ) {
      t.check();
      long beforeWrite = SystemClock.elapsedRealtime();
      // A drained playback head plus a late next write indicates an audible gap.
      // Exclude startup, normal completion and <=60 ms sampling/HAL jitter.
      if (t.audioFramesWritten > 0
          && beforeWrite > t.estimatedBufferEnd + 60
          && (t.track.getPlaybackHeadPosition() & 0xffffffffL) >= t.audioFramesWritten) {
        t.starvationEvents++;
        t.starvedMs += beforeWrite - t.estimatedBufferEnd;
      }
      int written = t.track.write(pcm, offset, Math.min(4800, n - offset));
      long afterWrite = SystemClock.elapsedRealtime();
      t.maxAudioWriteMs = Math.max(t.maxAudioWriteMs, afterWrite - beforeWrite);
      if (written <= 0) throw new IOException("扬声器播放失败");
      t.audioFramesWritten += written / 2;
      long bufferedFrames =
          Math.max(0, t.audioFramesWritten - (t.track.getPlaybackHeadPosition() & 0xffffffffL));
      t.estimatedBufferEnd = afterWrite + bufferedFrames * 1000 / 24000;
      if (t.cpuToFirstWrite == 0) {
        t.mark("playbackFirstWrite");
        t.cpuToFirstWrite = android.os.Process.getElapsedCpuTime() - t.cpuStarted;
      }
      offset += written;
    }
  }

  private void drain(Turn t) throws Exception {
    if (t.track == null) return;
    long deadline = SystemClock.elapsedRealtime() + 35000;
    while ((t.track.getPlaybackHeadPosition() & 0xffffffffL) < t.audioBytes / 2) {
      t.check();
      if (SystemClock.elapsedRealtime() > deadline) throw new IOException("音频播放超时");
      Thread.sleep(20);
    }
  }

  private void playPcm(Turn t, byte[] pcm) throws Exception {
    writeAudio(t, pcm, pcm.length);
    t.audioBytes = pcm.length;
    drain(t);
  }

  private static void releaseTrack(Turn t) {
    synchronized (t.audioLock) {
      AudioTrack a = t.track;
      t.track = null;
      if (a != null) {
        try {
          a.stop();
        } catch (IllegalStateException ignored) {
        }
        a.release();
      }
    }
  }

  // Explicit private ADB diagnostic: discard microphone bytes, no files/network/playback.
  private void localCaptureCheck(Turn t) throws Exception {
    while (!t.inputDone || !t.input.isEmpty()) {
      t.check();
      if (t.inputFailure != null) throw t.inputFailure;
      byte[] b = t.input.poll(100, TimeUnit.MILLISECONDS);
      if (b != null) t.inputBytes += b.length;
    }
    if (t.inputFailure != null) throw t.inputFailure;
    listener.status(t, UiContract.T_PHASE_CHECKDONE, t.captureLimited ? UiContract.T_CAPTURE_AUTO_STOP : UiContract.T_CAPTURE_STOPPED);
    listener.done(t, null);
  }

  private void localLoopback(Turn t) throws Exception {
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    long end = SystemClock.elapsedRealtime() + 3000;
    while (!t.inputDone || !t.input.isEmpty()) {
      t.check();
      if (SystemClock.elapsedRealtime() > end) t.stopInput();
      if (t.inputFailure != null) throw t.inputFailure;
      byte[] b = t.input.poll(100, TimeUnit.MILLISECONDS);
      if (b != null) raw.write(b);
    }
    if (t.inputFailure != null) throw t.inputFailure;
    byte[] in = raw.toByteArray(), out = new byte[(in.length / 4) * 6];
    long energy = 0;
    int peak = 0;
    for (int i = 0, j = 0; i + 3 < in.length; i += 4) {
      short a = (short) ((in[i] & 255) | (in[i + 1] << 8)),
          b = (short) ((in[i + 2] & 255) | (in[i + 3] << 8));
      energy += (long) a * a + (long) b * b;
      peak = Math.max(peak, Math.max(Math.abs((int) a), Math.abs((int) b)));
      short[] samples = {a, (short) (((int) a + b) / 2), b};
      for (short s : samples) {
        out[j++] = (byte) s;
        out[j++] = (byte) (s >> 8);
      }
    }
    Log.i(
        "NeoTalkie",
        "local_audio_check samples="
            + in.length / 2
            + " peak="
            + peak
            + " rms="
            + (int) Math.sqrt(energy / Math.max(1, in.length / 2)));
    listener.status(t, UiContract.T_PHASE_PLAYBACKCHECK, UiContract.T_PLAYBACK_ACTIVE);
    playPcm(t, out);
    listener.status(t, UiContract.T_PHASE_CHECKDONE, UiContract.T_PEAK_PREFIX + peak);
    listener.done(t, null);
  }

  private static byte[] doubleTone() {
    byte[] p = new byte[19200];
    for (int i = 0; i < 9600; i++) {
      double time = i / 24000.0;
      short s =
          (short)
              (((time < .12) || (time >= .22 && time < .34))
                  ? Math.sin(2 * Math.PI * 1000 * time) * 2400
                  : 0);
      p[i * 2] = (byte) s;
      p[i * 2 + 1] = (byte) (s >> 8);
    }
    return p;
  }

  void close() {
    captureTimer.shutdownNow();
    worker.shutdownNow();
    streams.shutdownNow();
    client.dispatcher().cancelAll();
    client.connectionPool().evictAll();
  }
}
