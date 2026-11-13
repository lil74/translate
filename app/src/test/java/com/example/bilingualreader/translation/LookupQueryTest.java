package com.example.bilingualreader.translation;

import static org.junit.Assert.*;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class LookupQueryTest {
  @Test
  public void automaticDirectionAndManualOverride() throws Exception {
    assertEquals("en", LookupQuery.source("Hello, world!", 0));
    assertEquals("zh-CN", LookupQuery.source("你好", 0));
    assertEquals("zh-CN", LookupQuery.source("这个 word 是什么？", 0));
    assertEquals("en", LookupQuery.source("这个 word 是什么？", 1));
    assertEquals("zh-CN", LookupQuery.source("hello", 2));
    assertEquals("en", LookupQuery.source("café", 0));
    try {
      LookupQuery.source("123 😀", 0);
      fail();
    } catch (AppException expected) {
      assertTrue(expected.getUserMessage().contains("手动"));
    }
  }

  @Test
  public void inputLimitsCountCodePointsAndRejectBlankBeforeService() throws Exception {
    for (String text : new String[] {"", " \n\t", "中".repeat(4001)}) {
      try {
        LookupQuery.request(text, 0);
        fail();
      } catch (AppException expected) {
        assertEquals(ErrorCode.INVALID_RESPONSE, expected.getCode());
      }
    }
    String full = "中" + "😀".repeat(3999);
    TranslationValidation.validateRequest(LookupQuery.request(full, 0));
  }

  @Test
  public void freshTransientIdsAndExactTextHash() throws Exception {
    String text = " 你好！\n ";
    TranslationBatchRequest a = LookupQuery.request(text, 0), b = LookupQuery.request(text, 0);
    assertTrue(a.getTaskId().startsWith("lookup:"));
    assertNotEquals(a.getTaskId(), b.getTaskId());
    assertNotEquals(a.getRequestId(), b.getRequestId());
    TranslationInput i = a.getItems().get(0);
    assertEquals(a.getTaskId(), i.getParagraphId());
    assertEquals(text, i.getText());
    assertEquals(TranslationValidation.sha256(text), i.getSourceHash());
    assertEquals("zh-CN", a.getSourceLanguage());
    assertEquals("en", a.getTargetLanguage());
  }

  @Test
  public void onlyTwoDirectionsAreAccepted() throws Exception {
    TranslationBatchRequest a = LookupQuery.request("hello", 0), b = LookupQuery.request("你好", 0);
    TranslationValidation.validateRequest(a);
    TranslationValidation.validateRequest(b);
    for (String[] pair : new String[][] {{"fr", "zh-CN"}, {"en", "en"}, {"zh-CN", "fr"}}) {
      try {
        TranslationValidation.validateRequest(
            new TranslationBatchRequest(
                a.getTaskId(), a.getRequestId(), pair[0], pair[1], a.getItems()));
        fail();
      } catch (AppException expected) {
        assertEquals(ErrorCode.UNSUPPORTED_LANGUAGE, expected.getCode());
      }
    }
  }

  @Test
  public void mismatchedOrEmptyResultCannotBecomeSuccess() throws Exception {
    TranslationBatchRequest r = LookupQuery.request("你好", 0);
    TranslationInput i = r.getItems().get(0);
    for (String hash : new String[] {"wrong", i.getSourceHash()}) {
      try {
        LookupQuery.translate(
            request ->
                new TranslationBatchResult(
                    r.getTaskId(),
                    r.getRequestId(),
                    Collections.singletonList(
                        new TranslationOutput(
                            i.getParagraphId(),
                            hash,
                            TranslationStatus.SUCCESS,
                            hash.equals("wrong") ? "hello" : " ",
                            "test",
                            null,
                            null))),
            r);
        fail();
      } catch (AppException expected) {
        assertEquals(ErrorCode.INVALID_RESPONSE, expected.getCode());
      }
    }
  }

  @Test
  public void isolatedQueryAcceptsReverseAndPreservesFailureMessage() throws Exception {
    TranslationBatchRequest r = LookupQuery.request("你好", 0);
    TranslationInput i = r.getItems().get(0);
    IsolatedSelectionTranslationService service =
        new IsolatedSelectionTranslationService(
            () ->
                request ->
                    new TranslationBatchResult(
                        request.getTaskId(),
                        request.getRequestId(),
                        Collections.singletonList(
                            new TranslationOutput(
                                i.getParagraphId(),
                                i.getSourceHash(),
                                TranslationStatus.SUCCESS,
                                "hello",
                                "test",
                                null,
                                null))));
    assertEquals("hello", LookupQuery.translate(service, r));
    try {
      LookupQuery.translate(
          request -> {
            throw new AppException(ErrorCode.NETWORK_ERROR, "断网测试", true);
          },
          r);
      fail();
    } catch (AppException expected) {
      assertEquals("断网测试", expected.getUserMessage());
    }
  }

  @Test
  public void libreTranslateSendsEnglishToChinese() throws Exception {
    checkHttp("hello", "en", "zh", "你好");
  }

  @Test
  public void libreTranslateSendsChineseToEnglish() throws Exception {
    checkHttp("你好", "zh", "en", "hello");
  }

  private void checkHttp(String text, String source, String target, String translated)
      throws Exception {
    AtomicReference<JsonObject> received = new AtomicReference<>();
    ServerSocket server = new ServerSocket();
    server.bind(new InetSocketAddress("127.0.0.1", 0));
    server.setSoTimeout(10000);
    ExecutorService fixture =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread thread = new Thread(r, "lookup-http-fixture");
              thread.setDaemon(true);
              return thread;
            });
    Future<?> responseJob =
        fixture.submit(
            () -> {
              try (Socket socket = server.accept()) {
                socket.setSoTimeout(10000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                String line = readLine(in);
                if (!line.startsWith("POST /translate "))
                  throw new IOException("Unexpected request: " + line);
                int length = 0;
                while (!(line = readLine(in)).isEmpty()) {
                  if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                    length = Integer.parseInt(line.substring(15).trim());
                }
                byte[] body = in.readNBytes(length);
                received.set(
                    JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                        .getAsJsonObject());
                JsonObject response = new JsonObject();
                response.addProperty("translatedText", translated);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(
                    ("HTTP/1.1 200 OK\r\n"
                         + "Content-Type: application/json; charset=utf-8\r\n"
                         + "Content-Length: "
                            + bytes.length
                            + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
                out.flush();
              }
              return null;
            });
    try {
      String result =
          LookupQuery.translate(
              new LibreTranslateService("http://127.0.0.1:" + server.getLocalPort() + "/translate"),
              LookupQuery.request(text, 0));
      responseJob.get(5, TimeUnit.SECONDS);
      assertEquals(translated, result);
      assertEquals(text, received.get().get("q").getAsString());
      assertEquals(source, received.get().get("source").getAsString());
      assertEquals(target, received.get().get("target").getAsString());
    } finally {
      server.close();
      fixture.shutdownNow();
    }
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int value;
    while ((value = in.read()) != -1 && value != '\n') {
      if (value != '\r') bytes.write(value);
      if (bytes.size() > 8192) throw new IOException("Oversized HTTP header");
    }
    if (value == -1) throw new EOFException("Incomplete HTTP header");
    return bytes.toString("US-ASCII");
  }
}
