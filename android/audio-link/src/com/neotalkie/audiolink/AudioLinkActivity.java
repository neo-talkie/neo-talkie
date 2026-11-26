package com.neotalkie.audiolink;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.*;

public final class AudioLinkActivity extends Activity {
  private static final ExecutorService CHANGES = Executors.newSingleThreadExecutor();
  private static final String[] MODES = {"普通 PTT", "Fn 首尾点按"};
  private PttService ptt;
  private boolean bound, active;
  private static final String[] GUIDE_TITLES = {"连接电脑", "选择声音设备", "按住 PTT 讲话"};
  private TextView status, deviceName;
  private ScrollView home;
  private Button mode, talk, pair;
  private int guideStep;
  private boolean changing;
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
      if (active && guideStep == 0 && hasWindowFocus() && ptt != null) {
        if ("com.zello.ptt.down".equals(intent.getAction())) ptt.press(); else ptt.release();
      }
    }
  };

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    home = new ScrollView(this);
    LinearLayout box = column();
    label(box, "点「蓝牙配对」，用电脑连接");
    deviceName = label(box, "");
    status = label(box, "");
    LinearLayout top = row(box);
    pair = button(top, "蓝牙配对", v -> openPairing());
    button(top, "使用指南", v -> showGuide(1));
    LinearLayout bottom = row(box);
    mode = button(bottom, "选择模式", v -> chooseMode());
    talk = button(bottom, "按住讲话", null);
    talk.setOnTouchListener((v,e) -> {
      if (ptt == null) return false;
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: ptt.press(); break;
        case MotionEvent.ACTION_UP: ptt.release(); v.performClick(); break;
        case MotionEvent.ACTION_CANCEL: ptt.release(); break;
      }
      return true;
    });
    home.addView(box);
    setContentView(home);
    change(true);
    if (saved != null && saved.getInt("guide") > 0) showGuide(saved.getInt("guide"));
  }

  private LinearLayout column() {
    LinearLayout result = new LinearLayout(this);
    result.setOrientation(LinearLayout.VERTICAL);
    return result;
  }

  private LinearLayout row(LinearLayout parent) {
    LinearLayout result = new LinearLayout(this);
    parent.addView(result);
    return result;
  }

  private TextView label(LinearLayout parent, String text) {
    TextView result = new TextView(this);
    result.setText(text);
    result.setGravity(Gravity.CENTER);
    parent.addView(result);
    return result;
  }

  private Button button(LinearLayout parent, String text, View.OnClickListener click) {
    Button result = new Button(this);
    result.setText(text);
    result.setSingleLine();
    result.setPadding(0, 0, 0, 0);
    result.setOnClickListener(click);
    parent.addView(result, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    return result;
  }

  private void openPairing() {
    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
  }

  private void updateDeviceName() {
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    String name = adapter == null ? null : adapter.getName();
    deviceName.setText(name == null || name.trim().isEmpty() ? "见手台蓝牙设置" : name);
  }

  private void chooseMode() {
    if (ptt == null) return;
    AlertDialog dialog = new AlertDialog.Builder(this).setSingleChoiceItems(MODES, ptt.mode.ordinal(),
        (d, which) -> {
          if (ptt != null) ptt.mode(PttSequence.Mode.values()[which]);
          d.dismiss();
        }).create();
    dialog.setOnKeyListener((d, code, event) -> {
      ListView list = dialog.getListView();
      int selected = list.getCheckedItemPosition();
      boolean up = code == KeyEvent.KEYCODE_F7 || code == KeyEvent.KEYCODE_DPAD_UP;
      boolean down = code == KeyEvent.KEYCODE_F8 || code == KeyEvent.KEYCODE_DPAD_DOWN;
      boolean confirm = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER;
      if (!up && !down && !confirm) return false;
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
        if (confirm) {
          if (ptt != null && selected >= 0) ptt.mode(PttSequence.Mode.values()[selected]);
          dialog.dismiss();
        } else {
          int next = Math.max(0, Math.min(MODES.length - 1, selected + (down ? 1 : -1)));
          list.setItemChecked(next, true);
          list.setSelection(next);
        }
      }
      return true;
    });
    dialog.show();
  }

  private void showGuide(int step) {
    if (ptt != null) ptt.release();
    guideStep = step;
    if (step == 0) {
      setContentView(home);
      return;
    }
    ScrollView scroll = new ScrollView(this);
    LinearLayout page = column();
    label(page, GUIDE_TITLES[step - 1]);
    if (step == 1) {
      label(page, "点「蓝牙配对」，在电脑上选");
      label(page, deviceName.getText().toString());
      label(page, "核对配对码，在两端确认。");
    } else if (step == 2) {
      label(page, "在电脑声音设置里，麦克风选");
      label(page, deviceName.getText().toString());
      label(page, "通话输出可选同名设备。");
    } else {
      label(page, "普通 PTT 按住传音，松开静音。Fn 模式先点按 Mac Fn，等半秒传音，松开后再点按 Fn。");
    }
    LinearLayout actions = row(page);
    if (step == 1) button(actions, "蓝牙配对", v -> openPairing()).setEnabled(!changing);
    if (step == 2) button(actions, "上一步", v -> showGuide(1));
    if (step == 3) button(actions, "恢复原模式", v -> { showGuide(0); change(false); }).setEnabled(!changing);
    button(actions, step == 3 ? "返回讲话" : "下一步", v -> showGuide(step == 3 ? 0 : step + 1));
    scroll.addView(page);
    setContentView(scroll);
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putInt("guide", guideStep);
  }

  @Override public void onBackPressed() {
    if (guideStep > 0) showGuide(guideStep - 1);
    else super.onBackPressed();
  }

  private void render() {
    mode.setEnabled(ptt!=null); talk.setEnabled(ptt!=null);
    if (ptt!=null) { mode.setText(MODES[ptt.mode.ordinal()]); status.setText("等待 PTT".equals(ptt.message) ? "电脑声音输入选此手台" : ptt.message); }
  }
  private void detach() {
    if (ptt != null) { ptt.changed=null; ptt.release(); }
    if (bound) { unbindService(connection); bound=false; }
    ptt=null;
  }
  private void change(boolean mic) {
    detach();
    render();
    changing = true;
    pair.setEnabled(false);
    status.setText(mic ? "正在启用音频互联…" : "正在恢复原模式…");
    CHANGES.execute(() -> {
      String error = null;
      try { MxB5AudioRole.apply(getApplicationContext(), mic); }
      catch (IOException | SecurityException e) { error="蓝牙配置失败：" + e.getMessage(); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); error="操作中断"; }
      final String result=error;
      runOnUiThread(() -> {
        if (result == null && !mic) stopService(new Intent(this,PttService.class));
        if (isDestroyed() || isFinishing()) return;
        changing = false;
        pair.setEnabled(true);
        updateDeviceName();
        if (guideStep > 0) showGuide(guideStep);
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
    updateDeviceName();
    IntentFilter keys=new IntentFilter("com.zello.ptt.down");
    keys.addAction("com.zello.ptt.up");
    registerReceiver(physical,keys);
  }
  @Override public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (!hasFocus && ptt != null) ptt.release();
  }

  @Override protected void onPause() {
    active=false; unregisterReceiver(physical);
    if (ptt!=null) ptt.release();
    super.onPause();
  }
  @Override protected void onDestroy() { detach(); super.onDestroy(); }
  @Override public boolean dispatchKeyEvent(KeyEvent e) {
    int k=e.getKeyCode();
    if (guideStep == 0 && ptt!=null && (k==KeyEvent.KEYCODE_F1 ||
        ((k==KeyEvent.KEYCODE_ENTER || k==KeyEvent.KEYCODE_DPAD_CENTER) && talk.hasFocus()))) {
      if (e.getAction()==KeyEvent.ACTION_UP) ptt.release(); else if (e.getRepeatCount()==0) ptt.press();
      return true;
    }
    if(k==KeyEvent.KEYCODE_F7 || k==KeyEvent.KEYCODE_F8) {
      if(e.getAction()==KeyEvent.ACTION_DOWN) {
        View focus=getCurrentFocus();
        View next = focus == null ? getWindow().getDecorView().focusSearch(
            k == KeyEvent.KEYCODE_F7 ? View.FOCUS_BACKWARD : View.FOCUS_FORWARD)
            : focus.focusSearch(k == KeyEvent.KEYCODE_F7 ? View.FOCUS_BACKWARD : View.FOCUS_FORWARD);
        if(next!=null)next.requestFocus();
      }
      return true;
    }
    return super.dispatchKeyEvent(e);
  }
}
