package com.example.bilingualreader.ui.profile;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.bilingualreader.BuildConfig;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Account API and device-bound session storage. Network calls must run off the UI thread. */
public final class AccountClient {
  private static final String KEY = "bilingualreader_account_session";
  private final SharedPreferences prefs;
  public AccountClient(Context context) {
    prefs=context.getSharedPreferences("reader_account",Context.MODE_PRIVATE);
  }
  String endpoint() { return BuildConfig.ACCOUNT_BASE_URL; }
  String username() { return prefs.getString("username",""); }
  public boolean signedIn() { return !username().isEmpty() && prefs.contains("session"); }

  void clear() { prefs.edit().remove("username").remove("session").apply(); }

  private static SecretKey key() throws Exception {
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
    if (store.containsAlias(KEY)) return ((KeyStore.SecretKeyEntry)store.getEntry(KEY,null)).getSecretKey();
    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }
  String token() throws Exception {
    String stored=prefs.getString("session","");if(stored.isEmpty())return "";
    byte[] bytes=Base64.getDecoder().decode(stored);
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,bytes,0,12));
    cipher.updateAAD(endpoint().getBytes(StandardCharsets.UTF_8));
    return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
  }
  private void save(String name,String token)throws Exception {
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
    cipher.updateAAD(endpoint().getBytes(StandardCharsets.UTF_8));
    byte[] iv=cipher.getIV(), encrypted=cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
    byte[] bytes=new byte[iv.length+encrypted.length];
    System.arraycopy(iv,0,bytes,0,iv.length);System.arraycopy(encrypted,0,bytes,iv.length,encrypted.length);
    prefs.edit().putString("username",name).putString("session",Base64.getEncoder().encodeToString(bytes)).apply();
  }
  JSONObject request(String action,String name,String password)throws Exception {
    URL url=new URL(endpoint()+"/account/"+action);
    if (!"https".equals(url.getProtocol()) || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null)
      throw new IOException("请输入 HTTPS 服务器地址");
    HttpURLConnection connection=(HttpURLConnection)url.openConnection();
    connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod("me".equals(action)?"GET":"POST");
    connection.setRequestProperty("Accept","application/json");
    if ("me".equals(action)||"logout".equals(action)) connection.setRequestProperty("Authorization","Bearer "+token());
    else {
      JSONObject data=new JSONObject();data.put("username",name);data.put("password",password);
      byte[] body=data.toString().getBytes(StandardCharsets.UTF_8);
      connection.setRequestProperty("Content-Type","application/json");
      connection.setDoOutput(true);
      try(OutputStream out=connection.getOutputStream()){out.write(body);}
    }
    try {
      int code=connection.getResponseCode();
      InputStream stream=code<400?connection.getInputStream():connection.getErrorStream();
      if(stream==null)throw new IOException("服务器没有响应");
      ByteArrayOutputStream buffer=new ByteArrayOutputStream();
      try(InputStream in=stream){byte[] chunk=new byte[1024];int n;while((n=in.read(chunk))!=-1){buffer.write(chunk,0,n);if(buffer.size()>8192)throw new IOException("服务器响应过大");}}
      JSONObject result=new JSONObject(buffer.toString("UTF-8"));
      if(code>=400)throw new IOException(result.optString("error","账号请求失败"));
      if("login".equals(action)||"register".equals(action)) save(result.getString("username"),result.getString("token"));
      return result;
    } finally {connection.disconnect();}
  }
}
