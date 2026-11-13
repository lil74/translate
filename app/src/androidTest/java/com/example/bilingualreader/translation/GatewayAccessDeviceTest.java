package com.example.bilingualreader.translation;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Tests the real HTTP adapter and Android Keystore, without paid provider requests. */
@RunWith(AndroidJUnit4.class)
public class GatewayAccessDeviceTest {
  @Test public void gatewayTokenIsSentAndProviderMetadataIsPreserved() throws Exception {
    ExecutorService pool=Executors.newSingleThreadExecutor();
    try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
      server.setSoTimeout(10000);
      Future<String> headers=pool.submit(()->{
        try(Socket socket=server.accept()) {
          socket.setSoTimeout(10000);
          BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
          StringBuilder all=new StringBuilder();String line;int length=0;
          while((line=reader.readLine())!=null&&!line.isEmpty()) {
            all.append(line).append('\n');
            if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
          }
          // Fixture request contains ASCII only, so character and UTF-8 byte lengths agree.
          for(int i=0;i<length;i++)assertNotEquals(-1,reader.read());
          byte[] body="{\"translatedText\":\"你好\",\"provider\":\"qwen-mt\",\"engineVersion\":\"qwen-mt-plus\"}".getBytes(StandardCharsets.UTF_8);
          OutputStream output=socket.getOutputStream();
          output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          output.write(body);output.flush();return all.toString();
        }
      });
      String source="Hello";
      TranslationBatchRequest request=new TranslationBatchRequest("t","r","en","zh-CN",Collections.singletonList(
          new TranslationInput("p",TranslationValidation.sha256(source),source)));
      TranslationOutput translated=new LibreTranslateService("http://127.0.0.1:"+server.getLocalPort()+"/translate","test-gateway-token")
          .translate(request).getItems().get(0);
      assertTrue(headers.get(10,TimeUnit.SECONDS).contains("Authorization: Bearer test-gateway-token"));
      assertEquals("qwen-mt",translated.getProvider());assertEquals("qwen-mt-plus",translated.getEngineVersion());
      assertEquals("你好",translated.getTranslatedText());
    }finally{pool.shutdownNow();}
  }

  @Test public void gatewayTokenIsEncryptedAndScopedToExactEndpoint() throws Exception {
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    TranslationConfigStore config=((ReaderApplication)context.getApplicationContext()).getContainer().translationConfig;
    String oldMode=config.mode(),oldEndpoint=config.endpoint();
    String endpoint="https://test-"+UUID.randomUUID()+".example/translate",token="test-access-"+UUID.randomUUID();
    try {
      config.save("online",endpoint,token);
      assertEquals(token,config.tokenForEndpoint(endpoint));
      assertEquals("",config.tokenForEndpoint(endpoint+"-different"));
      String stored=context.getSharedPreferences("translation_settings",Context.MODE_PRIVATE)
          .getString("gatewayToken:"+TranslationValidation.sha256(endpoint),"");
      assertFalse(stored.isEmpty());assertFalse(stored.contains(token));
      TranslationConfigStore reopened=new TranslationConfigStore(context,true);
      assertEquals(token,reopened.tokenForEndpoint(endpoint));
      config.save("online",endpoint,(String)null);assertEquals(token,config.tokenForEndpoint(endpoint));
      config.save("online",endpoint,"");assertEquals("",config.tokenForEndpoint(endpoint));
    } finally {config.save("online",endpoint,"");config.save(oldMode,oldEndpoint);}
  }

  @Test public void headerInjectionIsRejectedBeforeSendingRequest()throws Exception {
    try {new LibreTranslateService("https://team.example/translate","token\r\nInjected: true");fail();}
    catch(IllegalArgumentException expected) {assertNotNull(expected);}
  }
}
