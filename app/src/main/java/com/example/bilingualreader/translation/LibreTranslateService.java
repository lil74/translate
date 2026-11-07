package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Blocking adapter for a team-owned, keyless LibreTranslate endpoint. Call off the UI thread. */
public final class LibreTranslateService implements TranslationService {
  private final URL endpoint;

  public LibreTranslateService(String endpoint) throws MalformedURLException {
    this.endpoint = new URL(endpoint);
    String host = this.endpoint.getHost();
    boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host);
    if (!("https".equals(this.endpoint.getProtocol())
        || ("http".equals(this.endpoint.getProtocol()) && loopback))
        || this.endpoint.getUserInfo() != null || this.endpoint.getQuery() != null
        || this.endpoint.getRef() != null) {
      throw new IllegalArgumentException("Use HTTPS; HTTP is allowed only for localhost verification");
    }
  }

  @Override public TranslationBatchResult translate(TranslationBatchRequest request)
      throws AppException {
    TranslationValidation.validateRequest(request);
    List<TranslationOutput> outputs = new ArrayList<>();
    // The provider returns text only: bind each individual call to its original ID locally.
    for (TranslationInput item : request.getItems()) {
      if (Thread.currentThread().isInterrupted()) {
        throw new AppException(ErrorCode.TRANSLATION_FAILED, "翻译验证已中断", false);
      }
      outputs.add(new TranslationOutput(item.getParagraphId(), item.getSourceHash(),
          TranslationStatus.SUCCESS, translateOne(item.getText()), "libretranslate", null, null));
    }
    return TranslationValidation.validateResult(request,
        new TranslationBatchResult(request.getTaskId(), request.getRequestId(), outputs));
  }

  private String translateOne(String text) throws AppException {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) endpoint.openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(30000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      JsonObject payload = new JsonObject();
      payload.addProperty("q", text);
      payload.addProperty("source", "en");
      payload.addProperty("target", "zh");
      payload.addProperty("format", "text");
      byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = connection.getOutputStream()) { out.write(body); }
      int status = connection.getResponseCode();
      if (status != 200) {
        ErrorCode code = status == 401 || status == 403 ? ErrorCode.AUTH_FAILED
            : status == 429 ? ErrorCode.RATE_LIMITED
            : status >= 500 ? ErrorCode.NETWORK_ERROR : ErrorCode.TRANSLATION_FAILED;
        throw new AppException(code, "翻译服务请求失败（HTTP " + status + "）",
            status == 429 || status >= 500);
      }
      // Bound untrusted response size; do not log provider bodies or book text.
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream in = connection.getInputStream()) {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
          if (bytes.size() + n > 1024 * 1024) throw invalid();
          bytes.write(buffer, 0, n);
        }
      }
      JsonObject response = JsonParser.parseString(bytes.toString("UTF-8")).getAsJsonObject();
      JsonElement translated = response.get("translatedText");
      if (translated == null || !translated.isJsonPrimitive()
          || !translated.getAsJsonPrimitive().isString()
          || TranslationValidation.isBlank(translated.getAsString())) throw invalid();
      return translated.getAsString();
    } catch (SocketTimeoutException timeout) {
      throw new AppException(ErrorCode.TIMEOUT, "翻译服务连接或读取超时", true, timeout);
    } catch (IOException network) {
      throw new AppException(ErrorCode.NETWORK_ERROR, "无法连接翻译服务", true, network);
    } catch (JsonParseException | IllegalStateException malformed) {
      throw invalid();
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private AppException invalid() {
    return new AppException(ErrorCode.INVALID_RESPONSE, "翻译服务未返回有效文本", false);
  }
}
