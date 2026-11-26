package com.neotalkie.hfpmic;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MicActivity extends Activity {
  private static final ExecutorService CHANGES = Executors.newSingleThreadExecutor();
  private TextView status;
  private Button primary, restore;

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    ScrollView scroll = new ScrollView(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    status = new TextView(this);
    status.setGravity(Gravity.CENTER);
    status.setTextSize(15);
    status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
    box.addView(status);
    primary = new Button(this);
    primary.setText("蓝牙配对");
    box.addView(primary);
    restore = new Button(this);
    restore.setText("恢复原模式");
    restore.setOnClickListener(v -> change(false));
    box.addView(restore);
    scroll.addView(box);
    setContentView(scroll);
    change(true);
  }

  private void change(boolean mic) {
    status.setText(mic ? "正在启用蓝牙麦克风…" : "正在恢复原模式…");
    primary.setEnabled(false);
    restore.setEnabled(false);
    CHANGES.execute(() -> {
      String error = null;
      try {
        MxB5MicMode.apply(getApplicationContext(), mic);
      } catch (IOException e) {
        error = e.getMessage();
      } catch (SecurityException e) {
        error = "系统拒绝了蓝牙操作";
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        error = "操作中断，请重试";
      }
      final String result = error;
      runOnUiThread(() -> {
        if (isDestroyed() || isFinishing()) return;
        if (result == null && !mic) {
          Toast.makeText(this, "已恢复原蓝牙模式", Toast.LENGTH_SHORT).show();
          finish();
          return;
        }
        restore.setEnabled(true);
        primary.setEnabled(true);
        if (result == null) {
          status.setText("麦克风模式已启用");
          primary.setText("蓝牙配对");
          primary.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        } else {
          status.setText(result);
          primary.setText("重试");
          primary.setOnClickListener(v -> change(mic));
        }
        primary.requestFocus();
      });
    });
  }

  @Override public boolean dispatchKeyEvent(KeyEvent event) {
    int code = event.getKeyCode();
    if (code == KeyEvent.KEYCODE_F7 || code == KeyEvent.KEYCODE_F8) {
      if (event.getAction() == KeyEvent.ACTION_DOWN)
        (code == KeyEvent.KEYCODE_F7 ? primary : restore).requestFocus();
      return true;
    }
    return super.dispatchKeyEvent(event);
  }
}
