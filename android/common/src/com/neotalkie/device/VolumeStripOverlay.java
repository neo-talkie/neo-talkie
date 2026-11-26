package com.neotalkie.device;

import android.content.Context;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;

/** Draw the shared radio footer over a screen built with standard Android widgets. */
public final class VolumeStripOverlay extends View {
  private final VolumeStrip strip;

  public VolumeStripOverlay(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    strip = new VolumeStrip(context, this);
  }

  public boolean handleKey(KeyEvent event, int stream) {
    return strip.handleKey(event, false, stream);
  }

  public void hide() {
    strip.hide();
  }

  @Override protected void onDraw(Canvas canvas) {
    canvas.save();
    canvas.scale((float) getWidth() / UiContract.WIDTH, (float) getHeight() / UiContract.HEIGHT);
    strip.draw(canvas);
    canvas.restore();
  }
}
