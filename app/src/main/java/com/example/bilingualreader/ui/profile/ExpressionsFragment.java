package com.example.bilingualreader.ui.profile;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.ui.reader.ReaderLocalStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/** Account collection is authoritative while signed in; old local items need explicit migration. */
public final class ExpressionsFragment extends BaseFragment {
  private ReaderLocalStore local;
  private AccountClient account;
  private CloudExpressionClient cloud;
  private TextView status;
  private Button sync;
  private ArrayAdapter<String> adapter;
  private final List<String> texts=new ArrayList<>();
  private final List<CloudExpressionClient.Entry> remote=new ArrayList<>();
  private boolean showingCloud,busy;

  public ExpressionsFragment(){super(R.layout.fragment_expressions);}

  @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){
    super.onViewCreated(view,state);
    local=new ReaderLocalStore(requireContext());
    account=new AccountClient(requireContext());
    cloud=new CloudExpressionClient(requireContext());
    status=view.findViewById(R.id.expressions_status);
    sync=view.findViewById(R.id.expressions_sync_local);
    ListView list=view.findViewById(R.id.expressions_list);
    adapter=new ArrayAdapter<>(requireContext(),android.R.layout.simple_list_item_1,texts);
    list.setAdapter(adapter);
    list.setOnItemClickListener((parent,item,position,id)->host().openWord(texts.get(position)));
    list.setOnItemLongClickListener((parent,item,position,id)->{
      if(!busy)confirmDelete(position);return true;
    });
    sync.setOnClickListener(v->confirmSync());
  }
  @Override public void onResume(){
    super.onResume();host().page("单词与短语收藏",true);load();
  }
  private void setBusy(boolean value){busy=value;sync.setEnabled(!value);}
  private void showTexts(Collection<String> values){
    texts.clear();texts.addAll(values);adapter.notifyDataSetChanged();
  }
  private void load(){
    if(busy||status==null)return;
    remote.clear();
    if(!account.signedIn()){
      showingCloud=false;
      List<String> rows=local.expressions();showTexts(rows);
      status.setText(rows.isEmpty()?"本机还没有收藏。登录后可以按账号同步。":"本机收藏 "+rows.size()+" 条 · 登录后可手动同步");
      sync.setVisibility(View.GONE);return;
    }
    showingCloud=true;showTexts(Collections.emptyList());
    setBusy(true);sync.setVisibility(View.GONE);status.setText("正在读取账号 "+account.username()+" 的云端收藏…");
    new Thread(()->{
      try{
        List<CloudExpressionClient.Entry> rows=cloud.list();
        if(isAdded())requireActivity().runOnUiThread(()->{
          remote.clear();remote.addAll(rows);
          List<String> values=new ArrayList<>();for(CloudExpressionClient.Entry row:rows)values.add(row.text);
          showTexts(values);setBusy(false);
          status.setText(rows.isEmpty()?"当前账号还没有云端收藏。":"账号 "+account.username()+" · 云端收藏 "+rows.size()+" 条");
          sync.setVisibility(local.expressions().isEmpty()?View.GONE:View.VISIBLE);
        });
      }catch(Exception error){if(isAdded())requireActivity().runOnUiThread(()->{
        setBusy(false);status.setText("读取云端收藏失败："+error.getMessage());
        sync.setVisibility(local.expressions().isEmpty()?View.GONE:View.VISIBLE);
      });}
    },"cloud-expressions-list").start();
  }
  private void confirmDelete(int position){
    String text=texts.get(position);
    new MaterialAlertDialogBuilder(requireContext()).setTitle(text)
        .setMessage(showingCloud?"删除当前账号的云端收藏？":"删除这条本机收藏？")
        .setNegativeButton("取消",null)
        .setPositiveButton("删除",(dialog,which)->{
          if(!showingCloud){local.removeExpression(text);load();return;}
          CloudExpressionClient.Entry entry=remote.get(position);
          setBusy(true);status.setText("正在删除云端收藏…");
          new Thread(()->{
            try{cloud.delete(entry);if(isAdded())requireActivity().runOnUiThread(()->{setBusy(false);load();});}
            catch(Exception error){if(isAdded())requireActivity().runOnUiThread(()->{setBusy(false);status.setText("删除失败："+error.getMessage());});}
          },"cloud-expression-delete").start();
        }).show();
  }
  private void confirmSync(){
    List<String> pending=local.expressions();if(pending.isEmpty()||busy)return;
    new MaterialAlertDialogBuilder(requireContext()).setTitle("同步本机收藏？")
        .setMessage("将 "+pending.size()+" 条本机收藏上传到账号 “"+account.username()+"”。成功上传的条目会从本机待同步列表移除。")
        .setNegativeButton("取消",null).setPositiveButton("同步",(dialog,which)->sync(pending)).show();
  }
  private void sync(List<String> pending){
    setBusy(true);status.setText("正在同步 "+pending.size()+" 条本机收藏…");
    new Thread(()->{
      int completed=0;String failure=null;
      for(String text:pending){
        try{cloud.save(text);local.removeExpression(text);completed++;}
        catch(Exception error){failure=error.getMessage();break;}
      }
      final int count=completed;final String message=failure;
      if(isAdded())requireActivity().runOnUiThread(()->{
        setBusy(false);
        Toast.makeText(requireContext(),message==null?"已同步 "+count+" 条收藏":"已同步 "+count+" 条，剩余未上传："+message,Toast.LENGTH_LONG).show();
        load();
      });
    },"cloud-expressions-sync").start();
  }
}
