package com.example.bilingualreader.translation;

import com.example.bilingualreader.ui.reader.SelectionPopupPlacement;
import org.junit.Test;
import static org.junit.Assert.*;

public class SelectionPopupPlacementTest {
  @Test public void prefersAboveTheActualWord() {
    assertArrayEquals(new int[]{210,272},SelectionPopupPlacement.locate(0,60,500,800,250,400,290,425,120,120,8));
  }
  @Test public void usesBelowNearTopWithoutDockingToScreenBottom() {
    assertArrayEquals(new int[]{110,98},SelectionPopupPlacement.locate(0,60,500,800,150,65,190,90,120,120,8));
  }
  @Test public void clampsAtLeftAndRightEdges() {
    assertEquals(10,SelectionPopupPlacement.locate(10,60,490,800,10,400,30,425,200,100,8)[0]);
    assertEquals(290,SelectionPopupPlacement.locate(10,60,490,800,470,400,490,425,200,100,8)[0]);
  }
  @Test public void tallPopupUsesAvailableHorizontalSpace() {
    assertArrayEquals(new int[]{178,25},SelectionPopupPlacement.locate(0,0,700,300,140,140,170,160,150,250,8));
  }
  @Test public void shortLandscapeViewportStaysWithinBounds() {
    int[] p=SelectionPopupPlacement.locate(20,70,600,220,250,100,300,130,300,120,8);
    assertTrue(p[0]>=20&&p[0]+300<=600);assertTrue(p[1]>=70&&p[1]+120<=220);
  }
  @Test public void missingAnchorFallsBackNearTopRatherThanBottom() {
    assertArrayEquals(new int[]{150,60},SelectionPopupPlacement.locate(0,60,500,800,0,-1,0,-1,200,150,8));
  }
}
