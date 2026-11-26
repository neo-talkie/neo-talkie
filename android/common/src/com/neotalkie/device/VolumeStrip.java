package com.neotalkie.device;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioManager;
import android.os.Handler;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;

/**
 * In-view volume feedback; uses the device's exact volume steps for the selected playback stream.
 */
final class VolumeStrip {
  private final AudioManager audio;
  private final View view;
  private final Handler handler = new Handler();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int current, maximum;
  private boolean visible;
  private Bitmap value;
  private final Bitmap[] valueCache = new Bitmap[16];
  private int cachedMaximum = -1;
  private final Runnable dismiss =
      new Runnable() {
        public void run() {
          hide();
        }
      };

  VolumeStrip(Context context, View view) {
    this.view = view;
    audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
  }

  boolean handleKey(KeyEvent event, boolean includeDpad) {
    return handleKey(event, includeDpad, AudioManager.STREAM_MUSIC);
  }

  boolean handleKey(KeyEvent event, boolean includeDpad, int stream) {
    int key = event.getKeyCode();
    boolean up =
        key == KeyEvent.KEYCODE_VOLUME_UP || (includeDpad && key == KeyEvent.KEYCODE_DPAD_UP);
    boolean down =
        key == KeyEvent.KEYCODE_VOLUME_DOWN || (includeDpad && key == KeyEvent.KEYCODE_DPAD_DOWN);
    if (!up && !down) return false;
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      // Reverse this radio's physical encoder, preserving front-panel and headset keys.
      InputDevice device = event.getDevice();
      boolean rotary = device != null && "walktalk_encode_volum".equals(device.getName());
      boolean raise = rotary ? down : up;
      audio.adjustStreamVolume(
          stream,
          raise ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
          0);
      current = audio.getStreamVolume(stream);
      maximum = audio.getStreamMaxVolume(stream);
      if (cachedMaximum != maximum) {
        java.util.Arrays.fill(valueCache, null);
        cachedMaximum = maximum;
      }
      if (current >= 0 && current < valueCache.length) {
        if (valueCache[current] == null)
          valueCache[current] = PixelArt.numbers(current + "/" + maximum, 2, UiContract.C_PAGE_TITLE);
        value = valueCache[current];
      } else value = PixelArt.numbers(current + "/" + maximum, 2, UiContract.C_PAGE_TITLE);
      visible = true;
      handler.removeCallbacks(dismiss);
      handler.postDelayed(dismiss, UiContract.VOLUME_MS);
      view.invalidate();
    }
    return true;
  }

  boolean isVisible() {
    return visible;
  }

  void hide() {
    handler.removeCallbacks(dismiss);
    visible = false;
    view.invalidate();
  }

  // Cover the two lower action rows on the shared logical 160 x 128 canvas.
  void draw(Canvas canvas) {
    if (!visible) return;
    paint.setColor(UiContract.C_VOLUME_BG);
    canvas.drawRect(0, UiContract.FOOTER_TOP, UiContract.WIDTH, UiContract.HEIGHT, paint);
    paint.setColor(UiContract.C_ACCENT);
    canvas.drawRect(0, UiContract.FOOTER_TOP, UiContract.WIDTH, UiContract.FOOTER_TOP + 1, paint);
    paint.setTextSize(UiContract.FOOTER_FONT);
    canvas.drawText(UiContract.T_VOLUME, UiContract.LEFT, UiContract.FOOTER_LABEL_Y, paint);
    if (maximum > 0) {
      // Target radio reports five steps. Keep other devices bounded and truthful too.
      int segments = Math.min(maximum, 15);
      float step = 114f / segments;
      for (int i = 0; i < segments; i++) {
        paint.setColor((i + 1) * maximum <= current * segments ? UiContract.C_ACCENT : UiContract.C_VOLUME_EMPTY);
        canvas.drawRect(
            Math.round(6 + i * step), UiContract.BAR_Y, Math.round(6 + (i + 1) * step) - 3, UiContract.BAR_Y + UiContract.BAR_HEIGHT, paint);
      }
    }
    if (value != null) canvas.drawBitmap(value, 154 - value.getWidth(), 112, null);
  }
}
