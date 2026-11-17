package com.example.bilingualreader.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public final class CoverFrame extends FrameLayout {
  private float ratio = 2f / 3f;

  public CoverFrame(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public void ratio(float value) {
    ratio = value;
    requestLayout();
  }

  @Override
  protected void onMeasure(int width, int height) {
    int w = MeasureSpec.getSize(width);
    super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.round(w / ratio), MeasureSpec.EXACTLY));
  }
}
