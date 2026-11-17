package com.example.bilingualreader.translation;

import static org.junit.Assert.*;
import android.view.View;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.MainActivity;
import com.example.bilingualreader.ui.lookup.WordDetailFragment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DictionarySearchDeviceTest {
  private static void capture(String name)throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    android.graphics.Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(image);
    try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}
    finally{image.recycle();}
  }
  private static void await(BooleanSupplier predicate)throws Exception {
    long end=System.currentTimeMillis()+30000;
    while(!predicate.getAsBoolean() && System.currentTimeMillis()<end)Thread.sleep(50);
    assertTrue("Dictionary UI timed out",predicate.getAsBoolean());
  }
  @Test public void typingShowsChineseCandidatesThenOpensOfflineDetailAndBackRestoresInput()throws Exception {
    try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity->{activity.openLookup();activity.getSupportFragmentManager().executePendingTransactions();((EditText)activity.findViewById(R.id.lookup_input)).setText("spr");});
      await(()->{AtomicBoolean found=new AtomicBoolean();scenario.onActivity(activity->{LinearLayout list=activity.findViewById(R.id.lookup_suggestions);found.set(list!=null && list.getChildCount()>0);});return found.get();});
      // Rapid edits must not leave the earlier prefix's rows on screen.
      scenario.onActivity(activity->{EditText input=activity.findViewById(R.id.lookup_input);input.setText("bank");input.setText("spring");});
      await(()->{AtomicBoolean found=new AtomicBoolean();scenario.onActivity(activity->{LinearLayout list=activity.findViewById(R.id.lookup_suggestions);if(list!=null && list.getChildCount()>0)found.set(((TextView)((LinearLayout)list.getChildAt(0)).getChildAt(0)).getText().toString().equals("spring"));});return found.get();});
      capture("dictionary-candidates.png");
      scenario.onActivity(activity->{
        LinearLayout row=(LinearLayout)((LinearLayout)activity.findViewById(R.id.lookup_suggestions)).getChildAt(0);
        assertTrue(((TextView)row.getChildAt(1)).getText().toString().contains("春"));row.performClick();activity.getSupportFragmentManager().executePendingTransactions();
        assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container) instanceof WordDetailFragment);
      });
      await(()->{AtomicBoolean done=new AtomicBoolean();scenario.onActivity(activity->{TextView meaning=activity.findViewById(R.id.word_meaning);done.set(meaning!=null && meaning.getText().toString().contains("春"));});return done.get();});
      scenario.onActivity(activity->{
        String text=((TextView)activity.findViewById(R.id.word_meaning)).getText().toString();
        assertFalse(text.contains("英文释义"));assertTrue(text.contains("n."));assertTrue(text.contains("v"));
        assertEquals(View.VISIBLE,activity.findViewById(R.id.word_speak).getVisibility());
        assertTrue(((TextView)activity.findViewById(R.id.word_phonetic)).length()>0);
      });
      capture("dictionary-detail.png");
      scenario.recreate();
      await(()->{AtomicBoolean done=new AtomicBoolean();scenario.onActivity(activity->done.set(((TextView)activity.findViewById(R.id.word_meaning)).getText().toString().contains("春")));return done.get();});
      scenario.onActivity(activity->{activity.getOnBackPressedDispatcher().onBackPressed();activity.getSupportFragmentManager().executePendingTransactions();assertEquals("spring",((EditText)activity.findViewById(R.id.lookup_input)).getText().toString());});
    }
  }
}
