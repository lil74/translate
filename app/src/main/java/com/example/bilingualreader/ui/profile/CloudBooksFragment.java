package com.example.bilingualreader.ui.profile;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.contract.model.Book;
import com.example.bilingualreader.contract.model.ImportResult;
import com.example.bilingualreader.ui.BaseFragment;
import com.example.bilingualreader.viewmodel.BookshelfViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.util.*;

/** Cloud copies are separate from the local reading library. */
public final class CloudBooksFragment extends BaseFragment {
  private CloudBookClient cloud;
  private TextView status;
  private Button upload,refresh;
  private ArrayAdapter<String> adapter;
  private final List<CloudBookClient.RemoteBook> remote=new ArrayList<>();
  private List<Book> local=Collections.emptyList();
  private boolean busy;

  public CloudBooksFragment(){super(R.layout.fragment_cloud_books);}
  @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){
    super.onViewCreated(view,state);
    cloud=new CloudBookClient(requireContext());
    status=view.findViewById(R.id.cloud_status);
    upload=view.findViewById(R.id.cloud_upload);refresh=view.findViewById(R.id.cloud_refresh);
    ListView list=view.findViewById(R.id.cloud_list);
    adapter=new ArrayAdapter<>(requireContext(),android.R.layout.simple_list_item_1,new ArrayList<>());
    list.setAdapter(adapter);
    models().get(BookshelfViewModel.class).books.observe(getViewLifecycleOwner(),stateValue->{
      if(stateValue.data!=null)local=stateValue.data;
    });
    upload.setOnClickListener(v->chooseUpload());refresh.setOnClickListener(v->load());
    list.setOnItemClickListener((parent,row,position,id)->confirmRestore(remote.get(position)));
    list.setOnItemLongClickListener((parent,row,position,id)->{
      confirmDelete(remote.get(position));return true;
    });
    if(new AccountClient(requireContext()).signedIn())load();
    else status.setText("请先在“我的 → 账号登录”中登录，再使用云端书库");
  }
  @Override public void onResume(){super.onResume();host().page("云端书库",true);}
  private void setBusy(boolean value){
    busy=value;upload.setEnabled(!value);refresh.setEnabled(!value);
  }
  private void showError(Exception error){
    String text=error.getMessage()==null?"云端操作失败":error.getMessage();
    status.setText(text);
    Toast.makeText(requireContext(),text,Toast.LENGTH_LONG).show();
  }
  private void load(){
    if(busy)return;
    if(!new AccountClient(requireContext()).signedIn()){
      status.setText("请先登录账号");return;
    }
    setBusy(true);status.setText("正在读取云端书库…");
    new Thread(()->{
      try{
        List<CloudBookClient.RemoteBook> rows=cloud.list();
        if(isAdded())requireActivity().runOnUiThread(()->{
          remote.clear();remote.addAll(rows);adapter.clear();
          for(CloudBookClient.RemoteBook book:rows)adapter.add("☁ 已上传 · "+book.name+"  ·  "+(book.size+1023)/1024+" KB");
          status.setText(rows.isEmpty()?"云端还没有书籍。点击“上传本地书籍”开始备份。":"云端已有 "+rows.size()+" 本书 · 点击可恢复，长按可删除云端副本");
          setBusy(false);
        });
      }catch(Exception error){if(isAdded())requireActivity().runOnUiThread(()->{setBusy(false);showError(error);});}
    },"cloud-list").start();
  }
  private void chooseUpload(){
    if(busy)return;
    if(local.isEmpty()){
      Toast.makeText(requireContext(),"请先导入本地书籍",Toast.LENGTH_LONG).show();return;
    }
    String[] names=new String[local.size()];for(int i=0;i<local.size();i++)names[i]=local.get(i).getTitle();
    new MaterialAlertDialogBuilder(requireContext()).setTitle("选择要上传的本地书籍")
        .setItems(names,(dialog,index)->{
          Book book=local.get(index);
          new MaterialAlertDialogBuilder(requireContext()).setTitle("上传《"+book.getTitle()+"》？")
              .setMessage("原始 EPUB/TXT 文件将保存到你的账号云端书库。")
              .setNegativeButton("取消",null)
              .setPositiveButton("上传",(d,w)->upload(book)).show();
        }).setNegativeButton("取消",null).show();
  }
  private void upload(Book book){
    if(busy)return;setBusy(true);status.setText("正在上传《"+book.getTitle()+"》…");
    new Thread(()->{
      try{
        boolean exists=cloud.upload(book);
        if(isAdded())requireActivity().runOnUiThread(()->{
          setBusy(false);
          Toast.makeText(requireContext(),exists?"云端已存在这本书":"上传完成",Toast.LENGTH_SHORT).show();load();
        });
      }catch(Exception error){if(isAdded())requireActivity().runOnUiThread(()->{setBusy(false);showError(error);});}
    },"cloud-upload").start();
  }
  private void confirmRestore(CloudBookClient.RemoteBook book){
    if(busy)return;
    new MaterialAlertDialogBuilder(requireContext()).setTitle("恢复《"+book.name+"》？")
        .setMessage("将从云端下载原始文件并导入本机书架。")
        .setNegativeButton("取消",null).setPositiveButton("恢复",(dialog,which)->restore(book)).show();
  }
  private void restore(CloudBookClient.RemoteBook book){
    if(busy)return;setBusy(true);status.setText("正在恢复《"+book.name+"》…");
    new Thread(()->{
      File file=null;
      try{
        file=cloud.download(book);
        ImportResult result=graph().books.importBook(Uri.fromFile(file));
        final boolean exists=result.isAlreadyExists();
        if(isAdded())requireActivity().runOnUiThread(()->{
          setBusy(false);status.setText(exists?"本机书架已存在这本书":"已恢复到本机书架");
          Toast.makeText(requireContext(),status.getText(),Toast.LENGTH_SHORT).show();
        });
      }catch(Exception error){if(isAdded())requireActivity().runOnUiThread(()->{setBusy(false);showError(error);});}
      finally{if(file!=null){File folder=file.getParentFile();file.delete();if(folder!=null)folder.delete();}}
    },"cloud-restore").start();
  }
  private void confirmDelete(CloudBookClient.RemoteBook book){
    if(busy)return;
    new MaterialAlertDialogBuilder(requireContext()).setTitle("删除云端《"+book.name+"》？")
        .setMessage("只删除云端副本，本机书籍会保留。")
        .setNegativeButton("取消",null)
        .setPositiveButton("删除云端副本",(dialog,which)->{
          setBusy(true);status.setText("正在删除云端副本…");
          new Thread(()->{
            try{cloud.delete(book);if(isAdded())requireActivity().runOnUiThread(()->{setBusy(false);load();});}
            catch(Exception error){if(isAdded())requireActivity().runOnUiThread(()->{setBusy(false);showError(error);});}
          },"cloud-delete").start();
        }).show();
  }
}
