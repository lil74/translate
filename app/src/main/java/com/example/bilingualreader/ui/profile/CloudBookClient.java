package com.example.bilingualreader.ui.profile;

import android.content.Context;
import com.example.bilingualreader.contract.model.Book;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Raw EPUB/TXT backup API; calls must run off the UI thread. */
public final class CloudBookClient {
  static final long MAX_BOOK=50L*1024*1024;
  public static final class RemoteBook {
    public final String id,name;
    public final long size;
    RemoteBook(String id,String name,long size){this.id=id;this.name=name;this.size=size;}
  }
  private final AccountClient account;
  private final Context context;
  public CloudBookClient(Context context){this.context=context.getApplicationContext();account=new AccountClient(context);}

  private HttpURLConnection connect(String path,String method)throws Exception {
    if(!account.signedIn())throw new IOException("请先登录账号");
    URL url=new URL(account.endpoint()+path);
    if(!"https".equals(url.getProtocol()))throw new IOException("云端书库需要 HTTPS");
    HttpURLConnection connection=(HttpURLConnection)url.openConnection();
    connection.setConnectTimeout(10000);connection.setReadTimeout(180000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Authorization","Bearer "+account.token());
    return connection;
  }
  private static JSONObject json(HttpURLConnection connection)throws Exception {
    int code=connection.getResponseCode();
    InputStream stream=code<400?connection.getInputStream():connection.getErrorStream();
    if(stream==null)throw new IOException("服务器没有响应");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(InputStream in=stream){byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1){out.write(bytes,0,n);if(out.size()>65536)throw new IOException("服务器响应过大");}}
    JSONObject result=new JSONObject(out.toString("UTF-8"));
    if(code>=400)throw new IOException(result.optString("error",code==401?"请重新登录":"云端请求失败"));
    return result;
  }
  public List<RemoteBook> list()throws Exception {
    HttpURLConnection connection=connect("/books","GET");
    try {
      JSONArray array=json(connection).getJSONArray("books");
      List<RemoteBook> rows=new ArrayList<>();
      for(int i=0;i<array.length();i++){
        JSONObject item=array.getJSONObject(i);
        rows.add(new RemoteBook(item.getString("id"),item.getString("name"),item.getLong("size")));
      }
      return rows;
    }finally{connection.disconnect();}
  }
  public boolean upload(Book book)throws Exception {
    File file=new File(book.getLocalFilePath());
    long size=file.length();
    if(size<1||size>MAX_BOOK)throw new IOException("书籍文件不能超过 50 MiB");
    String name=book.getOriginalFileName();
    if(!name.toLowerCase(Locale.ROOT).endsWith(".txt")&&!name.toLowerCase(Locale.ROOT).endsWith(".epub"))
      throw new IOException("仅支持 TXT/EPUB 书籍");
    HttpURLConnection connection=connect("/books","POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type","application/octet-stream");
    connection.setRequestProperty("X-Book-Name",URLEncoder.encode(name,"UTF-8").replace("+","%20"));
    connection.setFixedLengthStreamingMode(size);
    try {
      try(InputStream in=new FileInputStream(file);OutputStream out=connection.getOutputStream()){
        byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
      }
      JSONObject result=json(connection);
      if(!book.getBookId().equals(result.getString("id")))throw new IOException("云端文件校验失败");
      return result.optBoolean("alreadyExists");
    }finally{connection.disconnect();}
  }
  File download(RemoteBook book)throws Exception {
    if(!book.id.matches("[0-9a-f]{64}")||book.size<1||book.size>MAX_BOOK)
      throw new IOException("云端书籍信息无效");
    String name=book.name;
    if(name.contains("/")||name.contains("\\")||!name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|epub)"))
      throw new IOException("云端文件名无效");
    File folder=new File(new File(context.getCacheDir(),"cloud-books"),book.id);
    if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建下载目录");
    File target=new File(folder,name);
    HttpURLConnection connection=connect("/books/"+book.id,"GET");
    try {
      int code=connection.getResponseCode();
      if(code!=200){json(connection);throw new IOException("下载失败");}
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      long total=0;
      try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(target)){
        byte[] bytes=new byte[65536];int n;
        while((n=in.read(bytes))!=-1){total+=n;if(total>MAX_BOOK)throw new IOException("云端文件过大");digest.update(bytes,0,n);out.write(bytes,0,n);}
      }catch(Exception failure){target.delete();throw failure;}
      StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
      if(total!=book.size||!book.id.equals(hash.toString())){target.delete();throw new IOException("下载文件校验失败");}
      return target;
    }finally{connection.disconnect();}
  }
  void delete(RemoteBook book)throws Exception {
    if(!book.id.matches("[0-9a-f]{64}"))throw new IOException("云端书籍信息无效");
    HttpURLConnection connection=connect("/books/"+book.id,"DELETE");
    try{json(connection);}finally{connection.disconnect();}
  }
}
