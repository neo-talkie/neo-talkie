package com.neotalkie.device;

import android.app.Activity;
import android.content.*;
import android.database.ContentObserver;
import android.graphics.*;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;
import android.view.*;
import java.io.*;
import org.json.JSONObject;

/** Small, keyboard-first native UI; owns audio only while this activity is foreground. */
public final class MainActivity extends Activity implements SpeechEngine.Listener, PttNavigation.TalkPage {
  private final Handler main = new Handler();
  private SpeechEngine engine;
  private SpeechEngine.Turn active;
  private Panel panel;
  private StatusStrip statusStrip;
  private VolumeStrip volumeStrip;
  private String state = UiContract.T_PHASE_IDLE, detail = UiContract.T_HOLD_PTT;
  // Active/retained turn content; overflow expands at a fixed 20 px font.
  private boolean compactText, answerText, hasContext;
  private Boolean previewContext;
  private final Runnable contextExpiry =
      new Runnable() {
        public void run() {
          refreshContext();
        }
      };
  private boolean browsingText, textKeyHeld;
  private int textOffset;
  private final Runnable finishTextBrowsing =
      new Runnable() {
        public void run() {
          browsingText = false;
          textOffset =
              panel.processing() && panel.textDocument != null ? panel.textDocument.maxOffset() : 0;
          panel.refreshTextWindow();
          if (active == null) returnToReady.run();
          else panel.invalidate();
        }
      };
  private final Runnable returnToReady =
      new Runnable() {
        public void run() {
          if (active != null || !foreground) return;
          resetTextBrowsing();
          compactText = false;
          answerText = false;
          refreshContext();
          showReady();
          panel.invalidate();
        }
      };
  private final RecordingFeedback feedback = new RecordingFeedback();
  private boolean foreground, held;
  private long engineVersion = Long.MIN_VALUE;
  private JSONObject selectedConfig;
  private String channelName = UiContract.T_DEFAULT_CHANNEL;
  private boolean voiceConfigured;
  private final ContentObserver configObserver = new ContentObserver(main) {
    @Override public void onChange(boolean selfChange) {
      if (foreground) readSelectedConfig();
    }
  };
  private long lastMenu;
  private AudioManager audio;
  private PowerManager.WakeLock wake;
  private WifiManager.WifiLock wifi;
  private final AudioManager.OnAudioFocusChangeListener focus =
      new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(final int change) {
          if (change < 0)
            main.post(
                new Runnable() {
                  public void run() {
                    stop(UiContract.T_AUDIO_BUSY);
                  }
                });
        }
      };
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    wake =
        ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeoTalkie:turn");
    wake.setReferenceCounted(false);
    wifi =
        ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NeoTalkie:stream");
    wifi.setReferenceCounted(false);
    panel = new Panel();
    statusStrip = new StatusStrip(this, panel);
    volumeStrip = new VolumeStrip(this, panel);
    setContentView(panel);
  }

  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
  }

  protected void onResume() {
    super.onResume();
    foreground = true;
    getContentResolver().registerContentObserver(CompanionProvider.CONFIG_URI, false, configObserver);
    readSelectedConfig();
    statusStrip.start();
    readPrivateCommand();
    PttNavigation.resume(this);
    panel.invalidate();
  }

  private void readSelectedConfig() {
    try {
      selectConfig(CompanionConfig.read(this));
    } catch (Exception e) {
      selectedConfig = null;
      channelName = UiContract.T_DEFAULT_CHANNEL;
    }
    // Legacy ADB provisioning files have no revision, including when first adding credentials.
    if (selectedVersion() == 0 && active == null) engineVersion = Long.MIN_VALUE;
    useSelectedConfig();
    refreshContext();
  }

  private void selectConfig(JSONObject config) throws Exception {
    channelName = CompanionConfig.active(config).getString("name");
    selectedConfig = config;
  }

  private long selectedVersion() {
    return selectedConfig == null ? -1 : selectedConfig.optLong("version", -1);
  }

  // Each turn keeps its original engine until it finishes or the operator starts another turn.
  private void useSelectedConfig() {
    if (active != null || engineVersion == selectedVersion()) return;
    Config config = null;
    if (selectedConfig != null) {
      try {
        config = new Config(this, selectedConfig, null);
      } catch (Exception ignored) {
        // Channel selection is available before voice credentials have been provisioned.
      }
    }
    if (engine != null) engine.close();
    engineVersion = selectedVersion();
    voiceConfigured = config != null;
    String epoch = selectedConfig == null ? "" : selectedConfig.optString("contextEpoch");
    String filename = epoch.isEmpty() ? "conversation.bin" : "conversation-" + epoch + ".bin";
    if (selectedConfig != null) {
      File[] files = getFilesDir().listFiles();
      if (files != null) for (File f : files) {
        if ((f.getName().equals("conversation.bin") || f.getName().matches("conversation-[a-f0-9-]{36}\\.bin"))
            && !filename.equals(f.getName())) f.delete();
      }
    }
    engine = new SpeechEngine(config, this, new File(getFilesDir(), filename), audio);
    if (!voiceConfigured || state.equals(UiContract.T_PHASE_UNCONFIGURED)) showReady();
  }

  private void showReady() {
    state = voiceConfigured ? idleTitle() : UiContract.T_PHASE_UNCONFIGURED;
    detail = voiceConfigured ? UiContract.T_HOLD_PTT : UiContract.T_TEXT_122;
  }

  private void nextChannel() {
    try {
      selectConfig(CompanionConfig.nextChannel(this));
    } catch (Exception e) {
      android.widget.Toast.makeText(this, UiContract.T_CHANNEL_SWITCH_FAILED, android.widget.Toast.LENGTH_SHORT).show();
      return;
    }
    if (engineVersion == selectedVersion()) return;
    previewContext = null;
    volumeStrip.hide();
    if (active == null) {
      useSelectedConfig();
      main.removeCallbacks(returnToReady);
      returnToReady.run();
    } else {
      refreshContext();
    }
  }

  protected void onPause() {
    foreground = false;
    getContentResolver().unregisterContentObserver(configObserver);
    PttNavigation.pause(this);
    main.removeCallbacks(contextExpiry);
    statusStrip.stop();
    volumeStrip.hide();
    stop(UiContract.T_HOLD_PTT);
    super.onPause();
  }

  protected void onDestroy() {
    if (active != null) active.cancel();
    if(engine!=null)engine.close();
    super.onDestroy();
  }

  private boolean online() {
    NetworkInfo n =
        ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
    return n != null && n.isConnected();
  }

  private String idleTitle() {
    return hasContext ? UiContract.T_FOLLOWUP : UiContract.T_PHASE_IDLE;
  }

  private void refreshContext() {
    main.removeCallbacks(contextExpiry);
    long remaining = engineVersion == selectedVersion() ? engine.contextRemaining(System.currentTimeMillis()) : 0;
    hasContext = previewContext != null ? previewContext : remaining > 0;
    if (active == null && !compactText && (state.equals(UiContract.T_PHASE_IDLE) || state.equals(UiContract.T_FOLLOWUP)))
      state = idleTitle();
    if (panel != null) panel.invalidate();
    if (foreground && previewContext == null && remaining > 0)
      main.postDelayed(contextExpiry, remaining + 1);
  }

  public void contextChanged() {
    main.post(
        new Runnable() {
          public void run() {
            if (foreground) refreshContext();
          }
        });
  }

  private void newConversation() {
    refreshContext();
    if (!hasContext) return;
    try {
      if (previewContext != null) previewContext = false;
      else engine.clearConversation();
      stop(UiContract.T_HOLD_PTT);
    } catch (IOException e) {
      state = UiContract.T_PHASE_CLEARFAILED;
      detail = UiContract.T_TEXT_129;
      panel.invalidate();
    }
  }

  private boolean pttWakeCheck; // Process-only, explicit offline device acceptance.

  public void press() {
    if (held || !foreground) return;
    held = true;
    start(null, pttWakeCheck, pttWakeCheck);
  }

  private void openMenu() {
    long now = SystemClock.elapsedRealtime();
    if (!foreground || now - lastMenu < 400) return;
    lastMenu = now;
    stop(UiContract.T_HOLD_PTT);
    startActivity(new Intent(this, TalkMenuActivity.class));
  }

  public void release() {
    held = false;
    if (active != null && active.recording) {
      active.stopInput();
      state = UiContract.eventLabel("pttUp");
      detail = UiContract.T_PLEASE_WAIT;
      panel.invalidate();
    }
  }

  private boolean focus() {
    if (audio.requestAudioFocus(
            focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      state = UiContract.T_PHASE_BUSY;
      detail = UiContract.T_AUDIO_BUSY;
      panel.invalidate();
      return false;
    }
    ScreenPower.busy(this, true);
    wake.acquire(215000); // Longest turn timeout plus teardown.
    wifi.acquire();
    return true;
  }

  private void unfocus() {
    ScreenPower.busy(this, false);
    audio.abandonAudioFocus(focus);
    if (wake.isHeld()) wake.release();
    if (wifi.isHeld()) wifi.release();
  }

  private void start(File fixture, boolean local) {
    start(fixture, local, false);
  }

  private void start(File fixture, boolean local, boolean captureCheck) {
    resetTextBrowsing();
    main.removeCallbacks(returnToReady);
    compactText = false;
    answerText = false;
    long requestedAt = SystemClock.elapsedRealtime();
    SpeechEngine.Turn old = active;
    active = null;
    if (old != null) {
      if (local) old.cancel();
      else old.interruptForPtt();
    }
    boolean sameChannelConfig = engineVersion == selectedVersion();
    useSelectedConfig();
    if (!local && !online()) {
      unfocus();
      state = UiContract.T_OFFLINE;
      detail = UiContract.T_CONNECT_WIFI;
      held = false;
      panel.invalidate();
      return;
    }
    if (!focus()) {
      unfocus();
      held = false;
      return;
    }
    feedback.reset();
    active = new SpeechEngine.Turn(requestedAt);
    compactText = true;
    active.captureCheck = captureCheck;
    if (old != null && sameChannelConfig) active.continuedFromTurn = old.started;
    heartbeat(active);
    state = local ? UiContract.T_PHASE_RECORDCHECK : UiContract.eventLabel("pttDown");
    detail = local ? UiContract.T_TEXT_042 : UiContract.T_RELEASE_SEND;
    if (captureCheck) detail = UiContract.T_TEXT_056;
    panel.invalidate();
    engine.start(active, fixture, local);
  }

  private void stop(String message) {
    resetTextBrowsing();
    main.removeCallbacks(returnToReady);
    compactText = false;
    answerText = false;
    held = false;
    SpeechEngine.Turn old = active;
    active = null;
    if (old != null) old.cancel();
    useSelectedConfig();
    unfocus();
    refreshContext();
    showReady();
    if (voiceConfigured) detail = message;
    if (old != null) {
      old.mark("cancelled");
      writeState(old, true);
    }
    if (panel != null) panel.invalidate();
  }

  private void heartbeat(final SpeechEngine.Turn turn) {
    final long due = SystemClock.elapsedRealtime() + 200;
    main.postDelayed(
        new Runnable() {
          public void run() {
            if (active != turn || turn.cancelled) return;
            turn.mainThreadMaxLag =
                Math.max(turn.mainThreadMaxLag, Math.max(0, SystemClock.elapsedRealtime() - due));
            boolean textChanged = false;
            if ((state.equals(UiContract.T_PHASE_SYNTHESIZING) || state.equals(UiContract.T_PHASE_SPEAKING))
                && !turn.replyText.isEmpty()
                && !turn.replyText.equals(detail)) {
              detail = turn.replyText;
              answerText = true;
              textChanged = true;
            }
            if (turn.recording || textChanged || panel.processing()) panel.invalidate();
            heartbeat(turn);
          }
        },
        200);
  }

  @Override
  public void onBackPressed() {
    boolean homePrompt = state.equals(idleTitle()) && UiContract.T_HOLD_PTT.equals(detail);
    boolean setupPrompt = state.equals(UiContract.T_PHASE_UNCONFIGURED) && UiContract.T_TEXT_122.equals(detail);
    if (active == null && !compactText && !volumeStrip.isVisible() && (homePrompt || setupPrompt)) {
      finish();
      return;
    }
    volumeStrip.hide();
    stop(UiContract.T_HOLD_PTT);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent e) {
    int k = e.getKeyCode();
    boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
    if (e.getRepeatCount() == 0)
      Log.d(
          "NeoTalkieKeys",
          "code="
              + k
              + " scan="
              + e.getScanCode()
              + " action="
              + e.getAction()
              + " repeat="
              + e.getRepeatCount());
    if (PttNavigation.key(this, e)) return true;
    // The front MENU key is DPAD_CENTER on this ROM.
    if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MENU) {
      if (down && e.getRepeatCount() == 0) openMenu();
      return true;
    }
    if (k == KeyEvent.KEYCODE_F7 || k == KeyEvent.KEYCODE_DPAD_LEFT) {
      if (down && e.getRepeatCount() == 0) newConversation();
      return true;
    }
    if (k == KeyEvent.KEYCODE_F8) {
      if (down && e.getRepeatCount() == 0) nextChannel();
      return true;
    }
    if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
      // Consume the matching key-up even if the idle timer expired while held.
      if (!down && textKeyHeld) {
        textKeyHeld = false;
        return true;
      }
      if (compactText) {
        panel.layoutDetail();
        if (panel.textDocument != null && panel.textDocument.maxOffset() > 0) {
          if (down) {
            textKeyHeld = true;
            browsingText = true;
            textOffset =
                panel.textDocument.clamp(textOffset + (k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
            panel.refreshTextWindow();
            volumeStrip.hide();
            main.removeCallbacks(returnToReady);
            main.removeCallbacks(finishTextBrowsing);
            main.postDelayed(finishTextBrowsing, UiContract.REVIEW_HOLD_MS);
          }
          return true;
        }
      }
      return true;
    }
    if (volumeStrip.handleKey(e, false)) {
      if (down) engine.updatePlaybackVolume(active);
      return true;
    }
    if (k == KeyEvent.KEYCODE_BACK) {
      if (down && e.getRepeatCount() == 0) onBackPressed();
      return true;
    }
    return super.dispatchKeyEvent(e);
  }

  public void status(final SpeechEngine.Turn t, final String s, final String d) {
    UiContract.requireState(s);
    main.post(
        new Runnable() {
          public void run() {
            if (active != t || t.cancelled) return;
            if (panel.processing() && s.equals(UiContract.T_PHASE_SPEAKING)) resetTextBrowsing();
            state = s;
            detail = d;
            answerText =
                d != null && !d.isEmpty() && !t.replyText.isEmpty() && t.replyText.startsWith(d);
            panel.invalidate();
            writeState(t, false);
          }
        });
  }

  public void level(final SpeechEngine.Turn t, final int value) {
    main.post(
        new Runnable() {
          public void run() {
            if (active != t || t.cancelled) return;
            feedback.sample(value);
          }
        });
  }

  public void done(final SpeechEngine.Turn t) {
    main.post(
        new Runnable() {
          public void run() {
            if (active != t || t.cancelled) return;
            // A capped turn must not re-arm PTT until the physical key is released.
            active = null;
            useSelectedConfig();
            compactText = true;
            if (t.playbackFinished && !t.replyText.isEmpty()) {
              state = UiContract.eventLabel("playbackDone");
              detail = t.replyText;
              answerText = true;
            }
            unfocus();
            panel.invalidate();
            writeState(t, true);
            main.removeCallbacks(returnToReady);
            if (!browsingText) main.postDelayed(returnToReady, t.shortInput ? UiContract.SHORT_NOTICE_MS : UiContract.REPLY_HOLD_MS);
          }
        });
  }

  private void resetTextBrowsing() {
    main.removeCallbacks(finishTextBrowsing);
    browsingText = false;
    textKeyHeld = false;
    textOffset = 0;
  }

  // Developer commands are read only from app-private storage populated through adb run-as.
  // No exported diagnostic receiver, no plaintext secrets, no automatic network smoke tests.
  private void readPrivateCommand() {
    File f = new File(getFilesDir(), "command.json");
    if (!f.isFile()) return;
    try {
      JSONObject o = new JSONObject(Config.read(new FileInputStream(f)));
      if (!f.delete()) throw new IOException("command removal failed");
      String action = o.optString("action");
      if (action.startsWith("ui-") && o.has("hasContext")) {
        previewContext = o.optBoolean("hasContext");
        refreshContext();
      }
      if (action.equals("ptt-wake-check")) {
        pttWakeCheck = true;
        android.util.Log.i("NeoTalkiePower", "Offline PTT wake check armed until process exit");
      } else if (action.equals("local-audio")) start(null, true);
      else if (action.equals("capture-limit")) start(null, true, true);
      else if (action.equals("ui-context")) {
        stop(UiContract.T_HOLD_PTT);
      } else if (action.equals("ui-text") || action.equals("ui-level")) {
        // Private, explicit UI check: no engine, recording, playback or history writes.
        main.removeCallbacks(returnToReady);
        resetTextBrowsing();
        final SpeechEngine.Turn t = new SpeechEngine.Turn();
        boolean levelPreview = action.equals("ui-level");
        String previewState = o.optString("state", UiContract.T_PHASE_SPEAKING);
        if (!levelPreview
            && (previewState.equals(UiContract.T_PHASE_SYNTHESIZING)
                || previewState.equals(UiContract.T_PHASE_SPEAKING)
                || previewState.equals(UiContract.T_PHASE_FINISHED))) t.replyText = o.optString("text");
        t.partialText = o.optString("transcript");
        t.processingStarted = SystemClock.elapsedRealtime() - Math.max(0, o.optInt("progressMs"));
        t.recording = levelPreview;
        active = t;
        compactText = true;
        if (levelPreview) {
          feedback.reset();
          feedback.sample(o.optInt("level"));
          t.captureStarted = SystemClock.elapsedRealtime();
        }
        heartbeat(t);
        status(
            t,
            levelPreview ? UiContract.T_PHASE_LISTENING : o.optString("state", UiContract.T_PHASE_SPEAKING),
            levelPreview ? UiContract.T_RELEASE_SEND : o.optString("text"));
        if (levelPreview || o.optBoolean("complete")) {
          main.postDelayed(
              new Runnable() {
                public void run() {
                  t.recording = false;
                  done(t);
                }
              },
              levelPreview ? 5000 : Math.max(0, Math.min(30000, o.optInt("completeAfterMs"))));
        }
      } else if (action.equals("live-pcm")) {
        File fixture = new File(getFilesDir(), "acceptance.pcm");
        if (!fixture.isFile()
            || fixture.length() == 0
            || fixture.length() > RecordingFeedback.MAX_BYTES
            || (fixture.length() & 1) != 0) throw new IOException("invalid PCM");
        start(fixture, false);
      }
    } catch (Exception e) {
      state = UiContract.T_PHASE_CHECKFAILED;
      detail = UiContract.T_TEXT_072;
      panel.invalidate();
    }
  }

  private void writeState(SpeechEngine.Turn t, boolean complete) {
    try {
      JSONObject o =
          new JSONObject()
              .put("state", state)
              .put("detail", detail)
              .put("complete", complete)
              .put("cancelled", t.cancelled)
              .put("userText", t.userText)
              .put("replyText", t.replyText)
              .put("inputBytes", t.inputBytes)
              .put("outputBytes", t.audioBytes)
              .put("elapsedMs", SystemClock.elapsedRealtime() - t.started)
              .put("turnStartedMs", t.started)
              .put("metrics", t.metrics())
              .put("firstAudioMs", t.firstAudio == 0 ? 0 : t.firstAudio - t.recordEnd);
      try (FileOutputStream out = openFileOutput("last-turn.json", MODE_PRIVATE)) {
        out.write(o.toString().getBytes(Protocol.UTF8));
      }
    } catch (Exception ignored) {
    }
  }

  final class Panel extends View {
    static final int LEFT_EDGE = UiContract.LEFT, CONTENT_X = UiContract.CONTENT_X, CONTENT_WIDTH = UiContract.CONTENT_WIDTH;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    String cachedDetail, cachedState;
    boolean cachedCompact, cachedAnswer, cachedProcessing, expandedText;
    final TurnText.FooterFade footerFade = new TurnText.FooterFade();
    String[] lines = new String[0];
    TurnText.Document textDocument;
    final TurnText.Measure measure =
        new TurnText.Measure() {
          public int fit(String text) {
            return p.breakText(text, true, CONTENT_WIDTH, null);
          }
        };
    int touchTarget;

    Panel() {
      super(MainActivity.this);
      setFocusable(true);
      setFocusableInTouchMode(true);
    }

    void text(Canvas c, String s, float x, float y, float size, int color) {
      p.setTextSize(size);
      p.setColor(color);
      c.drawText(s, x, y, p);
    }

    boolean processing() {
      return active != null && TurnText.processingStage(state) > 0;
    }

    boolean waiting() {
      return active != null && !active.recording && TurnText.processingStage(state) > 1;
    }

    void layoutDetail() {
      boolean processing = processing();
      // ASR text stays inside the speech pipeline, including after PTT release.
      String label = state;
      if (hasContext && state.equals(UiContract.T_FOLLOWUP)) {
        String topic = engine.contextTopic();
        if (!topic.isEmpty()) label = topic;
      }
      String content = processing ? "" : detail == null ? "" : detail;
      if (content.equals(cachedDetail)
          && label.equals(cachedState)
          && compactText == cachedCompact
          && answerText == cachedAnswer
          && processing == cachedProcessing) return;
      // Keep the reading position as streaming text grows; a different body starts at the top.
      if (cachedDetail == null
          || !content.startsWith(cachedDetail)
          || !cachedCompact
          || processing != cachedProcessing) textOffset = 0;
      cachedDetail = content;
      cachedState = label;
      cachedCompact = compactText;
      cachedAnswer = answerText;
      cachedProcessing = processing;
      p.setTextSize(UiContract.FONT_BODY);
      if (processing) textDocument = TurnText.processing(state, measure);
      else textDocument = TurnText.document(label, content, measure, answerText);
      expandedText = !processing && compactText && textDocument.expanded();
      footerFade.setExpanded(expandedText, SystemClock.elapsedRealtime());
      refreshTextWindow();
    }

    void refreshTextWindow() {
      if (textDocument != null) {
        textOffset = textDocument.clamp(textOffset);
        lines = textDocument.window(textOffset);
      }
      invalidate();
    }

    void sideButton(Canvas canvas, Bitmap icon, String label, int top, boolean pressed, int alpha) {
      // Mirror the two physical side keys: upper circle, lower vertical stroke.
      canvas.drawBitmap(icon, LEFT_EDGE, top + 5, null);
      p.setTextSize(16);
      text(
          canvas,
          TurnText.ellipsis(label, measure),
          CONTENT_X,
          top + 19,
          16,
          (alpha << 24) | ((pressed ? UiContract.C_ACCENT : Color.WHITE) & 0xffffff));
    }

    int buttonAt(MotionEvent event) {
      float x = event.getX() * 160f / getWidth(), y = event.getY() * 128f / getHeight();
      if (x < 0 || x >= 160) return 0;
      layoutDetail();
      if (waiting() && y >= 83) return 0;
      if (expandedText && y >= 83 && x >= CONTENT_X) return 0;
      if (y >= 83 && y < 105) return hasContext ? 1 : 0;
      if (y >= 105 && y < 128) return 2;
      if (y >= 28 && y < 83) return 3;
      return 0;
    }

    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      canvas.scale(getWidth() / (float) UiContract.WIDTH, getHeight() / (float) UiContract.HEIGHT);
      canvas.drawColor(UiContract.C_BG);
      statusStrip.draw(canvas);
      layoutDetail();
      long now = SystemClock.elapsedRealtime();
      int footerAlpha = footerFade.alpha(now);
      if (hasContext)
        sideButton(
            canvas, PixelArt.NEW_CONVERSATION_BUTTON, UiContract.T_NEW_CONVERSATION, 83, touchTarget == 1, footerAlpha);
      sideButton(canvas, PixelArt.MORE_BUTTON, channelName, 105, touchTarget == 2, footerAlpha);
      if (footerFade.running(now)) postInvalidateDelayed(20);
      boolean listening = active != null && active.recording;
      canvas.drawBitmap(listening ? PixelArt.PTT_FRAME : PixelArt.PTT, LEFT_EDGE, UiContract.PTT_Y, null);
      if (listening) {
        p.setColor(UiContract.C_METER_BG);
        canvas.drawRect(UiContract.METER_X, UiContract.METER_Y, UiContract.METER_X + UiContract.METER_WIDTH, UiContract.METER_Y + UiContract.METER_HEIGHT, p);
        p.setColor(UiContract.C_METER);
        canvas.drawRect(UiContract.METER_X, UiContract.METER_Y + UiContract.METER_HEIGHT - feedback.barHeight(), UiContract.METER_X + UiContract.METER_WIDTH, UiContract.METER_Y + UiContract.METER_HEIGHT, p);
      }
      canvas.save();
      canvas.clipRect(CONTENT_X, UiContract.CONTENT_TOP, UiContract.RIGHT, expandedText ? UiContract.HEIGHT : UiContract.COMPACT_BOTTOM);
      for (int row = 0; row < (expandedText ? UiContract.EXPANDED_ROWS : UiContract.COMPACT_ROWS); row++) {
        int color =
            row == 0 && textOffset == 0 && state.equals(UiContract.T_PHASE_LISTENING) ? PixelArt.PTT_INK : UiContract.C_INK;
        if (row >= 2) color = ((255 - footerAlpha) << 24) | (color & 0xffffff);
        text(canvas, lines[row], CONTENT_X, UiContract.TEXT_BASELINE + row * UiContract.LINE_HEIGHT, UiContract.FONT_BODY, color);
      }
      canvas.restore();
      if (waiting()) {
        p.setColor(UiContract.C_WAIT_BG);
        canvas.drawRect(0, UiContract.FOOTER_TOP, UiContract.WIDTH, UiContract.HEIGHT, p);
        p.setColor(UiContract.C_WAIT_EDGE);
        canvas.drawRect(0, UiContract.FOOTER_TOP, UiContract.WIDTH, UiContract.FOOTER_TOP + 1, p);
        text(canvas, UiContract.T_PHASE_THINKING, LEFT_EDGE, UiContract.FOOTER_LABEL_Y, UiContract.FOOTER_FONT, Color.WHITE);
        p.setColor(UiContract.C_WAIT_BAR);
        canvas.drawRect(
            LEFT_EDGE,
            UiContract.BAR_Y,
            LEFT_EDGE + RecordingFeedback.processingWidth(now - active.processingStarted),
            UiContract.BAR_Y + UiContract.BAR_HEIGHT,
            p);
      }
      volumeStrip.draw(canvas);
      canvas.restore();
    }

    public boolean onTouchEvent(android.view.MotionEvent e) {
      if (e.getAction() == MotionEvent.ACTION_DOWN) {
        touchTarget = buttonAt(e);
        if (touchTarget == 3) press();
        invalidate();
        return true;
      }
      if (e.getAction() == MotionEvent.ACTION_MOVE && touchTarget > 0 && touchTarget < 3) {
        if (buttonAt(e) != touchTarget) touchTarget = 0;
        invalidate();
        return true;
      }
      if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
        int target = touchTarget;
        touchTarget = 0;
        if (target == 3) release();
        else if (e.getAction() == MotionEvent.ACTION_UP && buttonAt(e) == target) {
          if (target == 1) newConversation();
          if (target == 2) nextChannel();
          if (target != 0) performClick();
        }
        invalidate();
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
