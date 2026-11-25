package com.neotalkie.device;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/** Tiny original pixel artwork. Rasterized only when values change, never per frame. */
final class PixelArt {
  static final int INK = UiContract.C_ACCENT;
  static final int PTT_INK = UiContract.C_PTT;
  static final Bitmap PTT = ptt(true), PTT_FRAME = ptt(false);
  static final Bitmap NEW_CONVERSATION_BUTTON = sideButton(true), MORE_BUTTON = sideButton(false);
  static final Bitmap HOME_MARK = mark(2);
  static final Bitmap PAGE_ARROWS = pageArrows();
  static final Bitmap[] WIFI = {wifi(0), wifi(1), wifi(2), wifi(3), wifi(4)};
  private static final int[][] DIGITS = UiContract.DIGITS;
  private static final int[] COLON = {0, 1, 0, 1, 0},
      SLASH = {1, 1, 2, 4, 4},
      PERCENT = {5, 1, 2, 4, 5},
      DASH = {0, 0, 7, 0, 0};

  private static Bitmap pageArrows() {
    Bitmap bitmap = Bitmap.createBitmap(UiContract.STATUS_ARROW_WIDTH, UiContract.STATUS_ARROW_HEIGHT, Bitmap.Config.ARGB_8888);
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint();
    paint.setColor(INK);
    for (int[] r : UiContract.PAGE_ARROWS)
      canvas.drawRect(r[0], r[1], r[0] + r[2], r[1] + r[3], paint);
    return bitmap;
  }

  static Bitmap numbers(String text, int scale, int color) {
    int width = -scale;
    for (int i = 0; i < text.length(); i++) width += (text.charAt(i) == ':' ? 2 : 4) * scale;
    Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), 5 * scale, Bitmap.Config.ARGB_8888);
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint();
    paint.setColor(color);
    int x = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      int columns = ch == ':' ? 1 : 3;
      int[] rows =
          ch >= '0' && ch <= '9'
              ? DIGITS[ch - '0']
              : ch == ':' ? COLON : ch == '%' ? PERCENT : ch == '-' ? DASH : SLASH;
      for (int y = 0; y < 5; y++)
        for (int col = 0; col < columns; col++)
          if ((rows[y] & (1 << (columns - col - 1))) != 0)
            canvas.drawRect(
                x + col * scale, y * scale, x + (col + 1) * scale, (y + 1) * scale, paint);
      x += (columns + 1) * scale;
    }
    return bitmap;
  }

  private static Bitmap mark(int scale) {
    Bitmap bitmap = Bitmap.createBitmap(22 * scale, 16 * scale, Bitmap.Config.ARGB_8888);
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint();
    paint.setColor(INK);
    int[] heights = {8, 16, 12, 4};
    for (int i = 0; i < 4; i++) {
      int top = (16 - heights[i]) / 2;
      canvas.drawRect(
          i * 6 * scale, top * scale, (i * 6 + 4) * scale, (top + heights[i]) * scale, paint);
    }
    return bitmap;
  }

  private static Bitmap wifi(int level) {
    Bitmap bitmap = Bitmap.createBitmap(19, 17, Bitmap.Config.ARGB_8888);
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint();
    // Three nested upward-spreading arcs plus a dot, never cellular-style vertical bars.
    paint.setColor(level >= 4 ? UiContract.C_WHITE : UiContract.C_DIM);
    canvas.drawRect(6, 0, 13, 2, paint);
    canvas.drawRect(3, 2, 6, 4, paint);
    canvas.drawRect(13, 2, 16, 4, paint);
    canvas.drawRect(0, 4, 3, 6, paint);
    canvas.drawRect(16, 4, 19, 6, paint);
    paint.setColor(level >= 3 ? UiContract.C_WHITE : UiContract.C_DIM);
    canvas.drawRect(7, 5, 12, 7, paint);
    canvas.drawRect(4, 7, 7, 9, paint);
    canvas.drawRect(12, 7, 15, 9, paint);
    canvas.drawRect(3, 9, 5, 10, paint);
    canvas.drawRect(14, 9, 16, 10, paint);
    paint.setColor(level >= 2 ? UiContract.C_WHITE : UiContract.C_DIM);
    canvas.drawRect(8, 10, 11, 12, paint);
    canvas.drawRect(6, 12, 8, 13, paint);
    canvas.drawRect(11, 12, 13, 13, paint);
    paint.setColor(level >= 1 ? UiContract.C_WHITE : UiContract.C_DIM);
    canvas.drawRect(8, 14, 11, 17, paint);
    return bitmap;
  }

  private static Bitmap sideButton(boolean circle) {
    Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint();
    paint.setColor(UiContract.C_WHITE);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2);
    canvas.drawCircle(8, 8, 7, paint);
    if (circle) canvas.drawCircle(8, 8, 3, paint);
    else {
      paint.setStyle(Paint.Style.FILL);
      canvas.drawRect(7, 4, 9, 12, paint);
    }
    return bitmap;
  }

  private static Bitmap ptt(boolean centerLine) {
    Bitmap bitmap = Bitmap.createBitmap(16, 45, Bitmap.Config.ARGB_8888);
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint();
    paint.setColor(PTT_INK);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(2);
    canvas.drawRoundRect(1, 1, 15, 44, 7, 7, paint);
    paint.setStyle(Paint.Style.FILL);
    if (centerLine) canvas.drawRect(6, 10, 10, 35, paint);
    return bitmap;
  }
}
