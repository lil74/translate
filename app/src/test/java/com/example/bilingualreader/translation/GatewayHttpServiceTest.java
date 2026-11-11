package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

public class GatewayHttpServiceTest {
  private ServerSocket server;
  private ExecutorService pool;
  private String endpoint;
  private final AtomicReference<String> authorization=new AtomicReference<>();
  private final AtomicReference<String> response=new AtomicReference<>("{\"translatedText\":\"你好\",\"provider\":\"qwen-mt\",\"engineVersion\":\"qwen-mt-plus\"}");
  @Before public void setup()throws Exception {
    server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
    server.setSoTimeout(10000);pool=Executors.newSingleThreadExecutor();
    pool.submit(()->{
      try(Socket socket=server.accept()) {
        socket.setSoTimeout(10000);
        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
        String line;int length=0;
        while((line=reader.readLine())!=null&&!line.isEmpty()) {
          if(line.toLowerCase(Locale.ROOT).startsWith("authorization:"))authorization.set(line.substring(14).trim());
          if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
        }
        for(int i=0;i<length;i++)if(reader.read()==-1)throw new EOFException();
        byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
        OutputStream output=socket.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);output.flush();
      }return null;
    });
    endpoint="http://127.0.0.1:"+server.getLocalPort()+"/translate";
  }
  @After public void cleanup()throws Exception {server.close();pool.shutdownNow();}
  private TranslationBatchRequest request(){return new TranslationBatchRequest("task","request","en","zh-CN",
      Collections.singletonList(new TranslationInput("paragraph",TranslationValidation.sha256("Hello"),"Hello")));}
  @Test public void sendsGatewayTokenAndRetainsActualProviderIdentity()throws Exception {
    TranslationOutput output=new LibreTranslateService(endpoint,"team-test-token").translate(request()).getItems().get(0);
    assertEquals("Bearer team-test-token",authorization.get());
    assertEquals("qwen-mt",output.getProvider());assertEquals("qwen-mt-plus",output.getEngineVersion());
  }
  @Test public void originalKeylessLibreEndpointRemainsCompatible()throws Exception {
    response.set("{\"translatedText\":\"你好\"}");
    TranslationOutput output=new LibreTranslateService(endpoint).translate(request()).getItems().get(0);
    assertNull(authorization.get());assertEquals("libretranslate",output.getProvider());assertNull(output.getEngineVersion());
  }
  @Test public void malformedProviderMetadataCannotBeStoredAsSuccess()throws Exception {
    response.set("{\"translatedText\":\"你好\",\"provider\":123}");
    try {new LibreTranslateService(endpoint,"team-test-token").translate(request());fail();}
    catch(AppException expected){assertEquals(ErrorCode.INVALID_RESPONSE,expected.getCode());}
  }
}
