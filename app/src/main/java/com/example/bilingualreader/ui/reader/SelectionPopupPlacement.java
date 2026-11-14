package com.example.bilingualreader.ui.reader;

/** Screen-coordinate placement, independent of Android so edge cases can be regression-tested. */
public final class SelectionPopupPlacement {
  private SelectionPopupPlacement() {}
  public static int[] locate(int left,int top,int right,int bottom,int aLeft,int aTop,int aRight,int aBottom,
      int width,int height,int gap) {
    width=Math.min(Math.max(0,width),Math.max(0,right-left));
    height=Math.min(Math.max(0,height),Math.max(0,bottom-top));
    int maxX=Math.max(left,right-width),maxY=Math.max(top,bottom-height);
    if(aRight<=aLeft || aBottom<=aTop)
      return new int[]{clamp(left+(right-left-width)/2,left,maxX),top};
    int x=clamp(aLeft+(aRight-aLeft-width)/2,left,maxX),y;
    if(aTop-gap-height>=top)y=aTop-gap-height;
    else if(aBottom+gap+height<=bottom)y=aBottom+gap;
    else if(aRight+gap+width<=right) {
      x=aRight+gap;y=clamp(aTop+(aBottom-aTop-height)/2,top,maxY);
    } else if(aLeft-gap-width>=left) {
      x=aLeft-gap-width;y=clamp(aTop+(aBottom-aTop-height)/2,top,maxY);
    } else y=aTop-top>bottom-aBottom?top:maxY;
    return new int[]{clamp(x,left,maxX),clamp(y,top,maxY)};
  }
  private static int clamp(int value,int low,int high){return Math.max(low,Math.min(high,value));}
}
