package com.neotalkie.hfpmic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.*;

public final class MicActivity extends Activity {
  private static final ExecutorService CHANGES = Executors.newSingleThreadExecutor();
  private static final String[] MODES = {"普通 PTT", "Fn 首尾点按", "持续按住 Fn"};
  private PttService ptt;
  private boolean bound, active;
  private TextView status;
  private Button mode, talk, options;
  private final ServiceConnection connection = new ServiceConnection() {
    public void onServiceConnected(ComponentName name, IBinder binder) {
      ptt = ((PttService.LocalBinder)binder).service();
      ptt.changed = () -> render();
      render();
    }
    public void onServiceDisconnected(ComponentName name) {
      ptt = null;
      status.setText("蓝牙服务重新连接中");
      render();
    }
  };
  private final BroadcastReceiver physical = new BroadcastReceiver() {
    public void onReceive(Context c, Intent intent) {
      if (active && ptt != null) {
        if ("com.zello.ptt.down".equals(intent.getAction())) ptt.press(); else ptt.release();
      }
    }
  };

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    ScrollView scroll = new ScrollView(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    status = new TextView(this);
    status.setGravity(Gravity.CENTER);
    box.addView(status);
    mode = button(box, "选择模式");
    mode.setOnClickListener(v -> new AlertDialog.Builder(this).setItems(MODES,
        (d, which) -> { if (ptt != null) ptt.mode(PttSequence.Mode.values()[which]); }).show());
    talk = button(box, "按住讲话");
    talk.setOnTouchListener((v,e) -> {
      if (ptt == null) return false;
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: ptt.press(); break;
        case MotionEvent.ACTION_UP: ptt.release(); v.performClick(); break;
        case MotionEvent.ACTION_CANCEL: ptt.release(); break;
      }
      return true;
    });
    options = button(box, "设置");
    options.setOnClickListener(v -> new AlertDialog.Builder(this)
        .setItems(new String[]{"蓝牙配对","恢复原模式"}, (d, which) -> {
          if (which == 0) startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
          else change(false);
        }).show());
    scroll.addView(box);
    setContentView(scroll);
    change(true);
  }
  private Button button(LinearLayout box, String text) {
    Button b = new Button(this); b.setText(text); box.addView(b); return b;
  }
  private void render() {
    mode.setEnabled(ptt!=null); talk.setEnabled(ptt!=null);
    if (ptt!=null) { mode.setText(MODES[ptt.mode.ordinal()]); status.setText(ptt.message); }
  }
  private void detach() {
    if (ptt != null) { ptt.changed=null; ptt.release(); }
    if (bound) { unbindService(connection); bound=false; }
    ptt=null;
  }
  private void change(boolean mic) {
    detach();
    render();
    options.setEnabled(false);
    status.setText(mic ? "正在启用蓝牙麦克风…" : "正在恢复原模式…");
    CHANGES.execute(() -> {
      String error = null;
      try { MxB5MicMode.apply(getApplicationContext(), mic); }
      catch (IOException | SecurityException e) { error="蓝牙配置失败：" + e.getMessage(); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); error="操作中断"; }
      final String result=error;
      runOnUiThread(() -> {
        if (result == null && !mic) stopService(new Intent(this,PttService.class));
        if (isDestroyed() || isFinishing()) return;
        options.setEnabled(true);
        if (result != null) {
          status.setText(result); talk.setEnabled(true); talk.setText("重试");
          talk.setOnClickListener(v -> change(mic));
        } else if (!mic) {
          Toast.makeText(this,"已恢复原蓝牙模式",Toast.LENGTH_SHORT).show(); finish();
        } else {
          talk.setText("按住讲话"); talk.setOnClickListener(null);
          Intent svc = new Intent(this,PttService.class);
          startService(svc);
          bound=bindService(svc,connection,BIND_AUTO_CREATE);
        }
      });
    });
  }
  @Override protected void onResume() {
    super.onResume();
    active=true;
    IntentFilter keys=new IntentFilter("com.zello.ptt.down");
    keys.addAction("com.zello.ptt.up");
    registerReceiver(physical,keys);
  }
  @Override protected void onPause() {
    active=false; unregisterReceiver(physical);
    if (ptt!=null) ptt.release();
    super.onPause();
  }
  @Override protected void onDestroy() { detach(); super.onDestroy(); }
  @Override public boolean dispatchKeyEvent(KeyEvent e) {
    int k=e.getKeyCode();
    if (ptt!=null && (k==KeyEvent.KEYCODE_F1 ||
        ((k==KeyEvent.KEYCODE_ENTER || k==KeyEvent.KEYCODE_DPAD_CENTER) && talk.hasFocus()))) {
      if (e.getAction()==KeyEvent.ACTION_UP) ptt.release(); else if (e.getRepeatCount()==0) ptt.press();
      return true;
    }
    if(k==KeyEvent.KEYCODE_F7 || k==KeyEvent.KEYCODE_F8) {
      if(e.getAction()==KeyEvent.ACTION_DOWN) {
        View focus=getCurrentFocus();
        View next=focus==null?mode:focus.focusSearch(k==KeyEvent.KEYCODE_F7?View.FOCUS_UP:View.FOCUS_DOWN);
        if(next!=null)next.requestFocus();
      }
      return true;
    }
    return super.dispatchKeyEvent(e);
  }
}
