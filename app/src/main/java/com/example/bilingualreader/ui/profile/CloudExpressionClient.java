package com.example.bilingualreader.ui.profile;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Account-scoped saved words and phrases; call off the UI thread. */
public final class CloudExpressionClient {
  public static final class Entry {
    public final String id,text;
    Entry(String id,String text){this.id=id;this.text=text;}
  }
  private final AccountClient account;
  public CloudExpressionClient(Context context){account=new AccountClient(context);}

  private HttpURLConnection connect(String path,String method)throws Exception {
    if(!account.signedIn())throw new IOException("请先登录账号");
    URL url=new URL(account.endpoint()+path);
    if(!"https".equals(url.getProtocol()))throw new IOException("云端收藏需要 HTTPS");
    HttpURLConnection connection=(HttpURLConnection)url.openConnection();
    connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Authorization","Bearer "+account.token());
    return connection;
  }
  private static JSONObject response(HttpURLConnection connection)throws Exception {
    int code=connection.getResponseCode();
    InputStream stream=code<400?connection.getInputStream():connection.getErrorStream();
    if(stream==null)throw new IOException("服务器没有响应");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(InputStream in=stream){byte[] bytes=new byte[4096];int n;
      while((n=in.read(bytes))!=-1){out.write(bytes,0,n);if(out.size()>1024*1024)throw new IOException("收藏列表过大");}}
    JSONObject value=new JSONObject(out.toString("UTF-8"));
    if(code>=400)throw new IOException(value.optString("error",code==401?"请重新登录":"云端收藏请求失败"));
    return value;
  }
  public List<Entry> list()throws Exception {
    HttpURLConnection connection=connect("/expressions","GET");
    try{
      JSONArray array=response(connection).getJSONArray("expressions");
      List<Entry> rows=new ArrayList<>();
      for(int i=0;i<array.length();i++){
        JSONObject row=array.getJSONObject(i);rows.add(new Entry(row.getString("id"),row.getString("text")));
      }
      return rows;
    }finally{connection.disconnect();}
  }
  public boolean save(String text)throws Exception {
    HttpURLConnection connection=connect("/expressions","POST");
    connection.setRequestProperty("Content-Type","application/json");
    connection.setDoOutput(true);
    byte[] body=new JSONObject().put("text",text).toString().getBytes(StandardCharsets.UTF_8);
    connection.setFixedLengthStreamingMode(body.length);
    try{
      try(OutputStream out=connection.getOutputStream()){out.write(body);}
      return response(connection).optBoolean("alreadyExists");
    }finally{connection.disconnect();}
  }
  public void delete(Entry entry)throws Exception {
    if(!entry.id.matches("[0-9a-f]{64}"))throw new IOException("收藏信息无效");
    HttpURLConnection connection=connect("/expressions/"+entry.id,"DELETE");
    try{response(connection);}finally{connection.disconnect();}
  }
}
