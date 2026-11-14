package com.example.bilingualreader.ui.reader;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

/** Let short results wrap naturally; long results scroll without covering the whole reader. */
public final class SelectionResultScrollView extends ScrollView {
  private int maximumHeight=Integer.MAX_VALUE;
  public void setMaximumHeight(int value) {
    if(value!=maximumHeight){maximumHeight=value;requestLayout();}
  }
  public SelectionResultScrollView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int limit = Math.min(maximumHeight,Math.round(getResources().getDisplayMetrics().heightPixels * .24f));
    int mode = View.MeasureSpec.getMode(heightSpec);
    if (mode != View.MeasureSpec.UNSPECIFIED)
      limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
    super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
  }
}
