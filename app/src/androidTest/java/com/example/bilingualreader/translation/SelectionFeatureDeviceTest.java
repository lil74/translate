package com.example.bilingualreader.translation;

import android.graphics.*;
import android.os.ParcelFileDescriptor;
import android.text.Spannable;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.recyclerview.widget.*;
import com.example.bilingualreader.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.di.AppContainer;
import com.example.bilingualreader.ui.MainActivity;
import com.example.bilingualreader.ui.reader.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SelectionFeatureDeviceTest {
  private static final String PASSAGE="She sat on the bank and watched the river flow.";
  private ActivityScenario<MainActivity> scenario;
  private AppContainer graph;
  private AppContainer.SelectionBinding binding;
  private String bookId;
  private final AtomicReference<TranslationBatchRequest> captured=new AtomicReference<>();
  @Before public void setup()throws Exception {
    android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    graph=((ReaderApplication)context.getApplicationContext()).getContainer();binding=graph.selectionBinding();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->graph.setSelectionTranslation(request->{
      captured.set(request);TranslationInput item=request.getItems().get(0);
      return new TranslationBatchResult(request.getTaskId(),request.getRequestId(),Collections.singletonList(
          new TranslationOutput(item.getParagraphId(),item.getSourceHash(),TranslationStatus.SUCCESS,"河岸","test","1",null)));
    },false));
    File file=File.createTempFile("context-ui-",".txt",context.getCacheDir());
    try(Writer out=new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8)) {
      out.write("Chapter 1\n");for(int i=0;i<45;i++)out.write(PASSAGE+"\n");out.write(UUID.randomUUID().toString());
    }
    try{bookId=graph.books.importBook(android.net.Uri.fromFile(file)).getBook().getBookId();}finally{file.delete();}
    String chapter=graph.books.getChapters(bookId).get(0).getChapterId();
    scenario=ActivityScenario.launch(MainActivity.class);
    scenario.onActivity(activity->activity.openReader(bookId,"Context test",chapter,null));
    await(()->{AtomicBoolean ready=new AtomicBoolean();scenario.onActivity(activity->{
      View view=activity.findViewById(R.id.list);if(view instanceof RecyclerView)
        ready.set(((RecyclerView)view).getAdapter()!=null&&((RecyclerView)view).getAdapter().getItemCount()>20);
    });return ready.get();});
  }
  @After public void cleanup()throws Exception {
    if(scenario!=null)scenario.close();
    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->graph.setSelectionTranslation(binding.service,binding.demo));
    if(bookId!=null)graph.books.deleteBook(bookId);
  }
  private interface Check {boolean test()throws Exception;}
  private static void await(Check check)throws Exception {
    long end=System.currentTimeMillis()+15000;
    while(!check.test()&&System.currentTimeMillis()<end)Thread.sleep(30);
    assertTrue("UI condition timed out",check.test());
  }
  private ReaderFragment reader(MainActivity activity) {
    return (ReaderFragment)activity.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
  }
  private SelectionTranslationSheet popup(MainActivity activity) {
    return (SelectionTranslationSheet)reader(activity).getChildFragmentManager().findFragmentByTag(SelectionTranslationSheet.TAG);
  }
  @Test public void nativeSelectionAnchorsPopupAboveWordAndSendsItsParagraph()throws Exception {
    scenario.onActivity(activity->{
      RecyclerView list=activity.findViewById(R.id.list);
      ((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(20,list.getHeight()-180);
    });
    AtomicReference<TextView> source=new AtomicReference<>();AtomicReference<ActionMode> action=new AtomicReference<>();
    await(()->{scenario.onActivity(activity->{
      RecyclerView list=activity.findViewById(R.id.list);RecyclerView.ViewHolder row=list.findViewHolderForAdapterPosition(20);
      if(row!=null)source.set(row.itemView.findViewById(R.id.original));
    });return source.get()!=null&&source.get().getLayout()!=null;});
    int[] touch=new int[2];
    scenario.onActivity(activity->{
      TextView text=source.get();ActionMode.Callback original=text.getCustomSelectionActionModeCallback();
      text.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
        public boolean onCreateActionMode(ActionMode mode,Menu menu){action.set(mode);return original.onCreateActionMode(mode,menu);}
        public boolean onPrepareActionMode(ActionMode mode,Menu menu){return original.onPrepareActionMode(mode,menu);}
        public boolean onActionItemClicked(ActionMode mode,MenuItem item){return original.onActionItemClicked(mode,item);}
        public void onDestroyActionMode(ActionMode mode){original.onDestroyActionMode(mode);}
      });
      int offset=text.getText().toString().indexOf("bank")+1;
      android.text.Layout layout=text.getLayout();int line=layout.getLineForOffset(offset);
      text.getLocationOnScreen(touch);touch[0]+=text.getTotalPaddingLeft()+Math.round(layout.getPrimaryHorizontal(offset));
      touch[1]+=text.getTotalPaddingTop()+(layout.getLineTop(line)+layout.getLineBottom(line))/2;
    });
    String command="input swipe "+touch[0]+" "+touch[1]+" "+touch[0]+" "+touch[1]+" 1000";
    try(ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
    await(()->action.get()!=null);
    scenario.onActivity(activity->{
      TextView text=source.get();int start=text.getText().toString().indexOf("bank");
      android.text.Selection.setSelection((Spannable)text.getText(),start,start+4);
      assertTrue(action.get().getMenu().performIdentifierAction(R.id.translate_selection,0));
    });
    await(()->{AtomicBoolean ready=new AtomicBoolean();scenario.onActivity(activity->{
      SelectionTranslationSheet sheet=popup(activity);ready.set(sheet!=null&&sheet.getView()!=null&&sheet.requireDialog().getWindow().getDecorView().getHeight()>0);
    });return ready.get();});
    AtomicReference<String> geometry=new AtomicReference<>();
    try {await(()->{AtomicBoolean positioned=new AtomicBoolean();scenario.onActivity(activity->{
      SelectionTranslationSheet sheet=popup(activity);View decor=sheet.requireDialog().getWindow().getDecorView();
      int[] xy=new int[2];decor.getLocationOnScreen(xy);
      Rect viewport=new Rect();activity.findViewById(R.id.list).getGlobalVisibleRect(viewport);
      geometry.set("popup="+xy[0]+","+xy[1]+","+decor.getWidth()+","+decor.getHeight()
          +" anchor="+sheet.requireArguments().getInt("anchorLeft")+","+sheet.requireArguments().getInt("anchorTop")
          +","+sheet.requireArguments().getInt("anchorRight")+","+sheet.requireArguments().getInt("anchorBottom")
          +" viewport="+viewport+" attrs="+sheet.requireDialog().getWindow().getAttributes().x+","+sheet.requireDialog().getWindow().getAttributes().y);
      positioned.set(xy[1]+decor.getHeight()<=sheet.requireArguments().getInt("anchorTop"));
    });return positioned.get();});}catch(AssertionError error) {
      android.os.Bundle evidence=new android.os.Bundle();evidence.putString("geometry",geometry.get());
      InstrumentationRegistry.getInstrumentation().sendStatus(2,evidence);
      captureScreenshot("selection-context-popup-debug.png");throw error;
    }
    scenario.onActivity(activity->{
      SelectionTranslationSheet sheet=popup(activity);WindowManager.LayoutParams attrs=sheet.requireDialog().getWindow().getAttributes();
      assertEquals(0,attrs.flags&WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      assertNotEquals(0,attrs.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
      assertNotNull(sheet.requireView().findViewById(R.id.selection_pronounce));
      assertTrue(sheet.requireView().findViewById(R.id.selection_retry).performClick());
    });
    await(()->captured.get()!=null);
    TranslationInput selected=captured.get().getItems().get(0);assertEquals("bank",selected.getText());
    assertNotNull(selected.getContext());assertEquals(PASSAGE,selected.getContext().getText());
    assertEquals(PASSAGE.indexOf("bank"),selected.getContext().getStart());
    await(()->{AtomicBoolean done=new AtomicBoolean();scenario.onActivity(activity->{
      done.set("河岸".contentEquals(((TextView)popup(activity).requireView().findViewById(R.id.selection_result)).getText()));
    });return done.get();});
    await(()->{AtomicBoolean near=new AtomicBoolean();scenario.onActivity(activity->{
      SelectionTranslationSheet sheet=popup(activity);View decor=sheet.requireDialog().getWindow().getDecorView();
      int[] xy=new int[2];decor.getLocationOnScreen(xy);
      int gap=sheet.requireArguments().getInt("anchorTop")-xy[1]-decor.getHeight();
      int limit=Math.round(20*activity.getResources().getDisplayMetrics().density);
      near.set(gap>=0&&gap<=limit);
    });return near.get();});
    captureScreenshot("selection-context-popup.png");
  }
  private void captureScreenshot(String name)throws Exception {
    Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    if(screenshot!=null)try(OutputStream out=new FileOutputStream(new File(
        InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),name))) {
      screenshot.compress(Bitmap.CompressFormat.PNG,100,out);screenshot.recycle();
    }
  }
}
