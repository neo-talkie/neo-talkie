package com.neotalkie.audiolink;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.*;
import android.widget.*;

public final class AudioLinkActivity extends Activity {
  private static final String[] MODES = {"普通 PTT", "Fn 首尾点按"};
  private static final int DISCOVERABLE = 1;
  private final Button[] rows = new Button[2];
  private TextView title, status;
  private PttService ptt;
  private boolean bound, choosingMode, resumed;

  private final ServiceConnection connection = new ServiceConnection() {
    public void onServiceConnected(ComponentName name, IBinder binder) {
      ptt = ((PttService.LocalBinder) binder).service();
      ptt.changed = AudioLinkActivity.this::render;
      render();
    }

    public void onServiceDisconnected(ComponentName name) {
      ptt = null;
      render();
    }
  };

  private final BroadcastReceiver physical = new BroadcastReceiver() {
    public void onReceive(Context context, Intent intent) {
      if (canTalk()) {
        if ("com.zello.ptt.down".equals(intent.getAction())) ptt.press();
        else ptt.release();
      }
    }
  };

  private boolean canTalk() {
    return resumed && hasWindowFocus() && !choosingMode && ptt != null && ptt.ready;
  }

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    title = label(box);
    status = label(box);
    for (int i = 0; i < rows.length; i++) {
      final int index = i;
      Button button = new Button(this);
      button.setSingleLine();
      button.setFocusableInTouchMode(true);
      button.setTextColor(Color.WHITE);
      StateListDrawable background = new StateListDrawable();
      background.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(0xff30663d));
      background.addState(new int[]{}, new ColorDrawable(0xff303030));
      button.setBackground(background);
      button.setOnClickListener(v -> activate(index));
      box.addView(button, new LinearLayout.LayoutParams(-1, -2));
      rows[i] = button;
    }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(box);
    setContentView(scroll);
    choosingMode = saved != null && saved.getBoolean("modes");
    rows[saved == null ? 0 : saved.getInt("row")].requestFocus();
    render();
  }

  private TextView label(LinearLayout box) {
    TextView view = new TextView(this);
    view.setGravity(Gravity.CENTER);
    box.addView(view);
    return view;
  }

  private void render() {
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    String name = adapter == null ? null : adapter.getName();
    title.setText(choosingMode ? "讲话模式" : name == null ? "音频互联" : name);
    status.setText(ptt == null ? "正在启用音频互联…" : ptt.message);
    if (choosingMode) {
      for (int i = 0; i < rows.length; i++) {
        boolean current = ptt != null && ptt.mode.ordinal() == i;
        rows[i].setText((current ? "✓ " : "") + MODES[i]);
      }
    } else {
      rows[0].setText(ptt != null && ptt.failed ? "重新启用" : "蓝牙配对");
      rows[1].setText("模式 · " + MODES[ptt == null ? 0 : ptt.mode.ordinal()]);
    }
  }

  private void activate(int i) {
    if (ptt == null) return;
    if (ptt.failed && !choosingMode && i == 0) {
      ptt.retryOpen();
    } else if (choosingMode) {
      ptt.mode(PttSequence.Mode.values()[i]);
      showHome();
    } else if (i == 1) {
      ptt.release();
      choosingMode = true;
      render();
      rows[ptt.mode.ordinal()].requestFocus();
    } else if (ptt.ready) {
      Intent request = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
          .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
      try {
        startActivityForResult(request, DISCOVERABLE);
      } catch (ActivityNotFoundException e) {
        Toast.makeText(this, "系统未提供蓝牙配对入口", Toast.LENGTH_SHORT).show();
      }
    }
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == DISCOVERABLE && result > 0)
      Toast.makeText(this, "请在电脑蓝牙中选择本机", Toast.LENGTH_SHORT).show();
  }

  private void showHome() {
    choosingMode = false;
    render();
    rows[1].requestFocus();
  }

  @Override public void onBackPressed() {
    if (choosingMode) showHome();
    else finish();
  }

  @Override public boolean dispatchKeyEvent(KeyEvent event) {
    int key = event.getKeyCode();
    if (key == KeyEvent.KEYCODE_F1) {
      if (canTalk()) {
        if (event.getAction() == KeyEvent.ACTION_UP) ptt.release();
        else if (event.getRepeatCount() == 0) ptt.press();
      }
      return true;
    }
    boolean up = key == KeyEvent.KEYCODE_F7 || key == KeyEvent.KEYCODE_DPAD_UP;
    boolean down = key == KeyEvent.KEYCODE_F8 || key == KeyEvent.KEYCODE_DPAD_DOWN;
    boolean ok = key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_ENTER
        || key == KeyEvent.KEYCODE_DPAD_CENTER;
    if (up || down || ok) {
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
        if (ok) activate(rows[1].hasFocus() ? 1 : 0);
        else rows[up ? 0 : 1].requestFocus();
      }
      return true;
    }
    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
    return super.dispatchKeyEvent(event);
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean("modes", choosingMode);
    state.putInt("row", rows[1].hasFocus() ? 1 : 0);
  }

  @Override protected void onStart() {
    super.onStart();
    bound = bindService(new Intent(this, PttService.class), connection, BIND_AUTO_CREATE);
  }

  @Override protected void onResume() {
    super.onResume();
    resumed = true;
    IntentFilter filter = new IntentFilter("com.zello.ptt.down");
    filter.addAction("com.zello.ptt.up");
    registerReceiver(physical, filter);
  }

  @Override public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (!focus && ptt != null) ptt.release();
  }

  @Override protected void onPause() {
    resumed = false;
    unregisterReceiver(physical);
    if (ptt != null) ptt.release();
    super.onPause();
  }

  @Override protected void onStop() {
    if (ptt != null) ptt.changed = null;
    if (bound) unbindService(connection);
    bound = false;
    ptt = null;
    if (!isChangingConfigurations()) {
      choosingMode = false;
      rows[0].requestFocus();
    }
    super.onStop();
  }
}
