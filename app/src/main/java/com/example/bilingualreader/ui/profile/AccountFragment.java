package com.example.bilingualreader.ui.profile;

import android.os.Bundle;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.BaseFragment;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Registration and login for the user's own HTTPS account server. */
public final class AccountFragment extends BaseFragment {
  private AccountClient client;
  private TextView status;
  private EditText name,password;
  private Button login,register,logout;
  private Button recover,change,rotate,export,delete;
  private TextView usage;
  private ActivityResultLauncher<Intent> document;
  private String exportJson;
  private boolean busy;

  public AccountFragment(){super(R.layout.fragment_account);}
  private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
  private EditText field(LinearLayout layout,String hint,int type){
    EditText edit=new EditText(requireContext());edit.setHint(hint);edit.setInputType(type);
    layout.addView(edit,new LinearLayout.LayoutParams(-1,dp(56)));return edit;
  }
  @Override public void onCreate(@Nullable Bundle state){super.onCreate(state);
    document=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result->{
      if(result.getResultCode()!=android.app.Activity.RESULT_OK||result.getData()==null||exportJson==null)return;
      try(OutputStream out=requireContext().getContentResolver().openOutputStream(result.getData().getData())){
        if(out==null)throw new Exception("无法写入文件");out.write(exportJson.getBytes(StandardCharsets.UTF_8));toast("云端数据已导出");
      }catch(Exception error){toast("导出失败："+error.getMessage());}finally{exportJson=null;}
    });
  }
  private Button button(LinearLayout form,String label,Runnable action){Button b=new Button(requireContext());b.setText(label);form.addView(b);b.setOnClickListener(v->action.run());return b;}
  private void toast(String message){if(isAdded())Toast.makeText(requireContext(),message,Toast.LENGTH_LONG).show();}
  private LinearLayout dialogForm(){LinearLayout form=new LinearLayout(requireContext());form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),0,dp(24),0);return form;}
  private interface Work {JSONObject go()throws Exception;}
  private void execute(String action,Work work,java.util.function.Consumer<JSONObject> success){
    if(busy)return;busy=true;refresh();new Thread(()->{JSONObject value=null;String error=null;
      try{value=work.go();}catch(Exception ex){error=ex.getMessage();}
      JSONObject result=value;String message=error;
      if(isAdded())requireActivity().runOnUiThread(()->{busy=false;refresh();if(message!=null)toast(message);else if(success!=null)success.accept(result);});
    },"account-"+action).start();
  }
  private void showCode(String value){new AlertDialog.Builder(requireContext()).setTitle("请保存恢复码")
      .setMessage("恢复码只显示这一次。请保存到安全位置，忘记密码时需要它。\n\n"+value)
      .setPositiveButton("我已保存",null).show();}
  @Override public void onViewCreated(@NonNull View root,@Nullable Bundle state){
    super.onViewCreated(root,state);
    client=new AccountClient(requireContext());
    ScrollView scroll=new ScrollView(requireContext());
    LinearLayout form=new LinearLayout(requireContext());form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24),dp(24),dp(24),dp(24));scroll.addView(form);
    ((ViewGroup)root).removeAllViews();((ViewGroup)root).addView(scroll);
    status=new TextView(requireContext());status.setTextSize(18);form.addView(status);
    usage=new TextView(requireContext());form.addView(usage);
    name=field(form,"用户名（3–32 位字母、数字或下划线）",InputType.TYPE_CLASS_TEXT);
    password=field(form,"密码（至少 10 位）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    login=new Button(requireContext());login.setText("登录");form.addView(login);
    register=new Button(requireContext());register.setText("注册");form.addView(register);
    logout=new Button(requireContext());logout.setText("退出登录");form.addView(logout);
    recover=button(form,"忘记密码？使用恢复码",this::recoverPassword);
    change=button(form,"修改密码",this::changePassword);
    rotate=button(form,"生成新的恢复码",this::rotateCode);
    export=button(form,"导出云端书单与收藏",this::exportData);
    delete=button(form,"注销账号并删除云端数据",this::deleteAccount);
    login.setOnClickListener(v->submit("login"));register.setOnClickListener(v->submit("register"));
    logout.setOnClickListener(v->{
      if(busy)return;
      busy=true;refresh();
      new Thread(()->{
        try{client.request("logout",null,null);}catch(Exception ignored){}
        client.clear();
        if(isAdded())requireActivity().runOnUiThread(()->{busy=false;refresh();});
      },"account-logout").start();
    });
    refresh();loadUsage();
    if(client.signedIn())new Thread(()->{
      try{client.request("me",null,null);}
      catch(Exception error){
        if(error.getMessage()!=null && (error.getMessage().contains("过期")||error.getMessage().contains("重新登录")))client.clear();
      }
      if(isAdded())requireActivity().runOnUiThread(this::refresh);
    },"account-check").start();
  }
  private void refresh(){
    if(getView()==null)return;
    boolean signed=client.signedIn();
    status.setText(signed?"已登录："+client.username():"尚未登录");
    name.setVisibility(signed?View.GONE:View.VISIBLE);
    password.setVisibility(signed?View.GONE:View.VISIBLE);
    login.setVisibility(signed?View.GONE:View.VISIBLE);register.setVisibility(signed?View.GONE:View.VISIBLE);
    recover.setVisibility(signed?View.GONE:View.VISIBLE);
    for(View v:new View[]{usage,change,rotate,export,delete})v.setVisibility(signed?View.VISIBLE:View.GONE);
    for(Button b:new Button[]{login,register,recover,change,rotate,export,delete})b.setEnabled(!busy);
    logout.setVisibility(signed?View.VISIBLE:View.GONE);logout.setEnabled(!busy);
  }
  private void submit(String action){
    if(busy)return;
    String user=name.getText().toString().trim(),pass=password.getText().toString();
    if(!user.matches("[A-Za-z0-9_]{3,32}")){name.setError("用户名格式不正确");return;}
    if(pass.length()<10||pass.length()>128){password.setError("密码需为 10–128 位");return;}
    busy=true;refresh();
    new Thread(()->{
      String error=null;
      JSONObject result=null;try{result=client.request(action,user,pass);}catch(Exception failure){error=failure.getMessage();}
      final String message=error;
      final JSONObject value=result;
      if(isAdded())requireActivity().runOnUiThread(()->{
        busy=false;password.setText("");refresh();
        if(message!=null)Toast.makeText(requireContext(),message,Toast.LENGTH_LONG).show();
        else if("register".equals(action)&&value!=null)showCode(value.optString("recoveryCode"));
        if(message==null)loadUsage();
      });
    },"account-"+action).start();
  }
  private void recoverPassword(){
    LinearLayout form=dialogForm();EditText user=field(form,"用户名",InputType.TYPE_CLASS_TEXT);
    EditText code=field(form,"40 位恢复码",InputType.TYPE_CLASS_TEXT);
    EditText next=field(form,"新密码（10–128 位）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AlertDialog.Builder(requireContext()).setTitle("用恢复码重置密码").setView(form).setNegativeButton("取消",null)
      .setPositiveButton("重置",(d,w)->execute("recover",()->client.call("recover","POST",new JSONObject()
        .put("username",user.getText().toString().trim()).put("recoveryCode",code.getText().toString().trim())
        .put("newPassword",next.getText().toString()),false),r->toast("密码已重置。登录后请生成新的恢复码。"))).show();
  }
  private void changePassword(){
    LinearLayout form=dialogForm();EditText old=field(form,"当前密码",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    EditText next=field(form,"新密码（10–128 位）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AlertDialog.Builder(requireContext()).setTitle("修改密码").setView(form).setNegativeButton("取消",null)
      .setPositiveButton("修改",(d,w)->execute("password",()->client.call("password","POST",new JSONObject()
        .put("password",old.getText().toString()).put("newPassword",next.getText().toString()),true),
        r->{client.clear();refresh();toast("密码已修改，请重新登录");})).show();
  }
  private void rotateCode(){
    LinearLayout form=dialogForm();EditText old=field(form,"当前密码",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AlertDialog.Builder(requireContext()).setTitle("生成新的恢复码").setView(form).setNegativeButton("取消",null)
      .setPositiveButton("生成",(d,w)->execute("recovery-code",()->client.call("recovery-code","POST",
        new JSONObject().put("password",old.getText().toString()),true),r->showCode(r.optString("recoveryCode")))).show();
  }
  private void exportData(){execute("export",()->client.call("export","GET",null,true),r->{
    exportJson=r.toString();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
      .setType("application/json").putExtra(Intent.EXTRA_TITLE,"reader-cloud-export.json");document.launch(intent);
  });}
  private void deleteAccount(){
    LinearLayout form=dialogForm();EditText old=field(form,"当前密码",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new AlertDialog.Builder(requireContext()).setTitle("注销账号")
      .setMessage("将永久删除账号、云端书籍和云端收藏。本机书籍与书签会保留。")
      .setView(form).setNegativeButton("取消",null).setPositiveButton("确认删除",(d,w)->
        execute("delete",()->client.call("me","DELETE",new JSONObject().put("password",old.getText().toString()),true),
          r->{client.clear();refresh();toast("账号已注销");})).show();
  }
  private void loadUsage(){
    if(!client.signedIn())return;
    new Thread(()->{try{
      java.util.List<CloudBookClient.RemoteBook> books=new CloudBookClient(requireContext()).list();
      int expressions=new CloudExpressionClient(requireContext()).list().size();long size=0;
      for(CloudBookClient.RemoteBook book:books)size+=book.size;
      String label="云端："+books.size()+" 本书 · "+expressions+" 条收藏 · "+String.format(java.util.Locale.getDefault(),"%.1f MiB",size/1048576.0);
      if(isAdded())requireActivity().runOnUiThread(()->usage.setText(label));
    }catch(Exception error){if(isAdded())requireActivity().runOnUiThread(()->usage.setText("云端用量暂时无法获取"));}},"account-usage").start();
  }
  @Override public void onResume(){super.onResume();host().page("账号",true);}
}
