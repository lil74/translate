package com.example.bilingualreader.ui.profile;

import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import com.example.bilingualreader.R;
import com.example.bilingualreader.ui.BaseFragment;

/** Registration and login for the user's own HTTPS account server. */
public final class AccountFragment extends BaseFragment {
  private AccountClient client;
  private TextView status;
  private EditText name,password;
  private Button login,register,logout;
  private boolean busy;

  public AccountFragment(){super(R.layout.fragment_account);}
  private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
  private EditText field(LinearLayout layout,String hint,int type){
    EditText edit=new EditText(requireContext());edit.setHint(hint);edit.setInputType(type);
    layout.addView(edit,new LinearLayout.LayoutParams(-1,dp(56)));return edit;
  }
  @Override public void onViewCreated(@NonNull View root,@Nullable Bundle state){
    super.onViewCreated(root,state);
    client=new AccountClient(requireContext());
    ScrollView scroll=new ScrollView(requireContext());
    LinearLayout form=new LinearLayout(requireContext());form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24),dp(24),dp(24),dp(24));scroll.addView(form);
    ((ViewGroup)root).removeAllViews();((ViewGroup)root).addView(scroll);
    status=new TextView(requireContext());status.setTextSize(18);form.addView(status);
    name=field(form,"用户名（3–32 位字母、数字或下划线）",InputType.TYPE_CLASS_TEXT);
    password=field(form,"密码（至少 10 位）",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    login=new Button(requireContext());login.setText("登录");form.addView(login);
    register=new Button(requireContext());register.setText("注册");form.addView(register);
    logout=new Button(requireContext());logout.setText("退出登录");form.addView(logout);
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
    refresh();
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
    login.setEnabled(!busy);register.setEnabled(!busy);
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
      try{client.request(action,user,pass);}catch(Exception failure){error=failure.getMessage();}
      final String message=error;
      if(isAdded())requireActivity().runOnUiThread(()->{
        busy=false;password.setText("");refresh();
        if(message!=null)Toast.makeText(requireContext(),message,Toast.LENGTH_LONG).show();
      });
    },"account-"+action).start();
  }
  @Override public void onResume(){super.onResume();host().page("账号",true);}
}
