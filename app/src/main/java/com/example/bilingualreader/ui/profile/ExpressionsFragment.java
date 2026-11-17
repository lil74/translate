package com.example.bilingualreader.ui.profile;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.ui.reader.ReaderLocalStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/** Saved words and phrases from reader selections. */
public final class ExpressionsFragment extends BaseFragment {
  public ExpressionsFragment() { super(android.R.layout.list_content); }

  @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
    super.onViewCreated(view,state);
    host().page("单词与短语收藏", true);
    ListView list=view.findViewById(android.R.id.list);
    ReaderLocalStore store=new ReaderLocalStore(requireContext());
    List<String> rows=store.expressions();
    list.setAdapter(new ArrayAdapter<>(requireContext(),android.R.layout.simple_list_item_1,rows));
    TextView empty=new TextView(requireContext());
    empty.setText("还没有收藏的单词或短语");
    empty.setGravity(Gravity.CENTER);
    ((ViewGroup)view).addView(empty,new ViewGroup.LayoutParams(-1,-1));
    list.setEmptyView(empty);
    list.setOnItemLongClickListener((parent,item,position,id)->{
      new MaterialAlertDialogBuilder(requireContext()).setTitle(rows.get(position))
          .setMessage("删除这条收藏？").setNegativeButton("取消",null)
          .setPositiveButton("删除",(d,w)->{
            store.removeExpression(rows.get(position));
            rows.remove(position);
            ((ArrayAdapter<?>)list.getAdapter()).notifyDataSetChanged();
          }).show();
      return true;
    });
  }
}
