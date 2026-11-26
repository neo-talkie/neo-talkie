package com.neotalkie.audiolink;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The visible activity owns the link. The service stays alive until the ROM restores its role. */
public final class PttService extends Service implements BluetoothProfile.ServiceListener, PttSequence.Port {
  static final String AUDIO_STATE = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED";
  private static final int HFP_CLIENT = 16, SCO_CONNECTED = 2;
  final class LocalBinder extends Binder { PttService service() { return PttService.this; } }
  private static final ExecutorService ROLE_WORKER = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LocalBinder binder = new LocalBinder();
  private BluetoothAdapter adapter;
  private BluetoothHeadsetClient headset;
  private MxB5Keyboard keyboard;
  private AudioManager audio;
  private boolean sco, transmitting, destroyed;
  private AudioRoleSwitch roles;
  private boolean visible, closing;
  boolean ready, failed;
  private final Runnable retryClose = this::closeLink;
  PttSequence.Mode mode;
  private PttSequence sequence;
  Runnable changed;
  String message = "等待 PTT";

  @Override public void onCreate() {
    super.onCreate();
    adapter = BluetoothAdapter.getDefaultAdapter();
    audio = (AudioManager)getSystemService(AUDIO_SERVICE);
    SharedPreferences prefs = getSharedPreferences("ptt", MODE_PRIVATE);
    String savedMode = prefs.getString("mode", PttSequence.Mode.VOICE.name());
    mode = PttSequence.Mode.fromPreference(savedMode);
    if (!mode.name().equals(savedMode)) prefs.edit().putString("mode", mode.name()).apply();
    sequence = new PttSequence(this, mode);
    roles = new AudioRoleSwitch(ROLE_WORKER, main::post, open -> MxB5AudioRole.apply(this, open));
    IntentFilter filter = new IntentFilter(AUDIO_STATE);
    filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
    registerReceiver(events, filter);
    startForeground(1, new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_speakerphone)
        .setContentTitle(getApplicationInfo().loadLabel(getPackageManager())).setContentText("打开手台应用，按住 PTT 讲话")
        .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, AudioLinkActivity.class), 0)).build());
  }

  private void openLink() {
    visible = true;
    closing = false;
    main.removeCallbacks(retryClose);
    // The last unbind must have time to finish a Bluetooth restart off the main thread.
    startService(new Intent(this, PttService.class));
    retryOpen();
  }

  void retryOpen() {
    if (!visible || ready) return;
    failed = false;
    status("正在启用音频互联…");
    roles.request(true, error -> {
      if (destroyed) return;
      failed = error != null;
      ready = !failed;
      if (ready) bindProfiles();
      status(ready ? "等待 PTT" : error);
    });
  }

  private void closeLink() {
    visible = false;
    closing = true;
    ready = false;
    failed = false;
    main.removeCallbacks(retryClose);
    sequence.reset();
    dropProfiles();
    roles.request(false, error -> {
      if (destroyed) return;
      if (error == null) {
        stopSelf();
      } else {
        Log.w("NeoPttMode", "Restoring Bluetooth role: " + error);
        main.postDelayed(retryClose, 3000);
      }
    });
  }
  private void bindProfiles() {
    setRoute(false);
    if (keyboard == null) keyboard = new MxB5Keyboard(this, main, () -> {
      if (keyboard != null && !keyboard.ready() && mode != PttSequence.Mode.VOICE) sequence.reset();
      notifyUi();
    });
    if (headset == null) adapter.getProfileProxy(this, this, HFP_CLIENT);
  }
  private final BroadcastReceiver events = new BroadcastReceiver() {
    public void onReceive(Context c, Intent intent) {
      if (!ready) return;
      if (AUDIO_STATE.equals(intent.getAction())) {
        sco = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 0) == SCO_CONNECTED;
        if (sco) setRoute(transmitting);
        if (transmitting) status(sco ? "正在传音" : "等待电脑收音");
        notifyUi();
      } else {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
        if (state == BluetoothAdapter.STATE_ON) bindProfiles();
        if (state == BluetoothAdapter.STATE_TURNING_OFF) { sequence.reset(); dropProfiles(); }
      }
    }
  };
  public void onServiceConnected(int profile, BluetoothProfile proxy) {
    if (destroyed || !ready) { adapter.closeProfileProxy(profile, proxy); return; }
    headset = (BluetoothHeadsetClient)proxy;
    sco = false;
    for (BluetoothDevice host : headset.getConnectedDevices())
      if (headset.getAudioState(host) == SCO_CONNECTED) sco = true;
    if (sco) setRoute(transmitting);
    notifyUi();
  }
  public void onServiceDisconnected(int profile) { headset = null; sco = false; sequence.reset(); }
  private void dropProfiles() {
    sco = false;
    if (keyboard != null) keyboard.close();
    keyboard = null;
    if (headset != null) adapter.closeProfileProxy(HFP_CLIENT, headset);
    headset = null;
  }

  private void setRoute(boolean enabled) {
    audio.setParameters("hfp_enable=" + enabled);
    Log.i("NeoPttMode", "HFP gate " + enabled);
  }
  public void audio(boolean open) {
    transmitting = open;
    if (sco) setRoute(open);
    status(open ? (sco ? "正在传音" : "等待电脑收音") : "等待 PTT");
  }
  public boolean fn(boolean down) {
    return keyboard != null && keyboard.fn(down);
  }
  public void schedule(Runnable task, long ms) { main.postDelayed(task, ms); }
  public void cancel(Runnable task) { main.removeCallbacks(task); }
  public void status(String text) { message = text; notifyUi(); }
  void mode(PttSequence.Mode target) {
    sequence.select(target);
    mode = target;
    getSharedPreferences("ptt", MODE_PRIVATE).edit().putString("mode", target.name()).apply();
    notifyUi();
  }
  int playbackStream() {
    return sco ? MxB5AudioRole.STREAM_BLUETOOTH_SCO : AudioManager.STREAM_MUSIC;
  }

  void press() { if (ready) sequence.press(); }
  void release() { sequence.release(); }
  private void notifyUi() { if (changed != null) changed.run(); }
  @Override public IBinder onBind(Intent intent) {
    openLink();
    return binder;
  }
  @Override public void onRebind(Intent intent) { openLink(); }
  @Override public boolean onUnbind(Intent intent) {
    closeLink();
    return true;
  }
  @Override public int onStartCommand(Intent intent, int flags, int id) {
    // A process restart without a visible client should finish cleanup, never reopen the mic.
    if (!visible && !closing) closeLink();
    return START_STICKY;
  }
  @Override public void onDestroy() {
    destroyed=true;
    sequence.reset();
    main.removeCallbacksAndMessages(null);
    setRoute(false);
    dropProfiles();
    unregisterReceiver(events);
    stopForeground(true);
    super.onDestroy();
  }
}
