package com.example.bilingualreader.translation;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.example.bilingualreader.contract.model.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts the team's gateway access token, never a vendor API key. Bound to endpoint via AAD. */
final class ServiceTokenVault {
  private static final String ALIAS="reader.gateway.token.v1";
  private static synchronized javax.crypto.SecretKey key() throws Exception {
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
    if(store.containsAlias(ALIAS))return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }
  static String encrypt(String endpoint,String token)throws AppException {
    try {
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
      cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
      return Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+
          Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }catch(Exception error){throw unavailable();}
  }
  static String decrypt(String endpoint,String value)throws AppException {
    if(value.isEmpty())return "";
    try {
      String[] parts=value.split(":",2);
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
      cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }catch(Exception error){throw unavailable();}
  }
  private static AppException unavailable(){return new AppException(ErrorCode.AUTH_FAILED,"无法读取服务访问令牌，请在翻译服务设置中重新填写",false);}
}
