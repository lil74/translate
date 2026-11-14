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
  private final String accessToken;
  private volatile HttpURLConnection activeConnection;
  private volatile boolean cancelled;

  public void cancel() {
    cancelled = true;
    HttpURLConnection current = activeConnection;
    if (current != null) current.disconnect();
  }

  public LibreTranslateService(String endpoint) throws MalformedURLException {
    this(endpoint, "");
  }
  public LibreTranslateService(String endpoint, String accessToken) throws MalformedURLException {
    this.endpoint = new URL(endpoint);
    if (accessToken == null || accessToken.length()>2048 || accessToken.chars().anyMatch(c -> c<33 || c>126))
      throw new IllegalArgumentException("Invalid gateway access token");
    this.accessToken = accessToken;
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
      if (cancelled || Thread.currentThread().isInterrupted()) {
        throw new AppException(ErrorCode.TRANSLATION_FAILED, "翻译验证已中断", false);
      }
      ProviderText translated = translateOne(item, request.getSourceLanguage(), request.getTargetLanguage());
      outputs.add(new TranslationOutput(item.getParagraphId(), item.getSourceHash(),
          TranslationStatus.SUCCESS, translated.text, translated.provider, translated.version, null));
    }
    return TranslationValidation.validateResult(request,
        new TranslationBatchResult(request.getTaskId(), request.getRequestId(), outputs));
  }

  private static final class ProviderText {
    final String text,provider,version;
    ProviderText(String text,String provider,String version){this.text=text;this.provider=provider;this.version=version;}
  }
  private String metadata(JsonObject response,String name,String fallback)throws AppException {
    JsonElement value=response.get(name);if(value==null||value.isJsonNull())return fallback;
    if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()
        ||TranslationValidation.isBlank(value.getAsString())||value.getAsString().length()>128)throw invalid();
    return value.getAsString();
  }
  private ProviderText translateOne(TranslationInput item, String source, String target) throws AppException {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) endpoint.openConnection();
      activeConnection = connection;
      if (cancelled) throw new AppException(ErrorCode.TRANSLATION_FAILED, "翻译已停止", false);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(30000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      if (!accessToken.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + accessToken);
      JsonObject payload = new JsonObject();
      payload.addProperty("q", item.getText());
      payload.addProperty("source", "zh-CN".equals(source) ? "zh" : source);
      payload.addProperty("target", "zh-CN".equals(target) ? "zh" : target);
      payload.addProperty("format", "text");
      if(item.getContext()!=null) {
        TranslationInput.Context context=item.getContext();
        JsonObject sourceContext=new JsonObject();sourceContext.addProperty("text",context.getText());
        sourceContext.addProperty("start",context.getStart());sourceContext.addProperty("end",context.getEnd());
        payload.add("context",sourceContext);
      }
      byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = connection.getOutputStream()) { out.write(body); }
      int status = connection.getResponseCode();
      if (status != 200) {
        ErrorCode code = status == 401 || status == 403 ? ErrorCode.AUTH_FAILED
            : status == 429 ? ErrorCode.RATE_LIMITED
            : status >= 500 ? ErrorCode.NETWORK_ERROR : ErrorCode.TRANSLATION_FAILED;
        if (status == 429 || status == 503) {
          throw new RetryAfterException(code, "翻译服务繁忙，稍后自动重试（HTTP " + status + "）",
              retryAfter(connection.getHeaderField("Retry-After"), System.currentTimeMillis()));
        }
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
      if(item.getContext()!=null) {
        JsonElement contextual=response.get("contextual");
        if(contextual==null || !contextual.isJsonPrimitive() || !contextual.getAsJsonPrimitive().isBoolean()
            || !contextual.getAsBoolean())throw new AppException(ErrorCode.UNSUPPORTED_CONTENT,
                "当前翻译服务不支持段落语境，请在翻译服务设置中使用团队云端服务",false);
      }
      JsonElement translated = response.get("translatedText");
      if (translated == null || !translated.isJsonPrimitive()
          || !translated.getAsJsonPrimitive().isString()
          || TranslationValidation.isBlank(translated.getAsString())) throw invalid();
      return new ProviderText(translated.getAsString(),metadata(response,"provider","libretranslate"),
          metadata(response,"engineVersion",null));
    } catch (SocketTimeoutException timeout) {
      throw new AppException(ErrorCode.TIMEOUT, "翻译服务连接或读取超时", true, timeout);
    } catch (IOException network) {
      throw new AppException(ErrorCode.NETWORK_ERROR, "无法连接翻译服务", true, network);
    } catch (JsonParseException | IllegalStateException malformed) {
      throw invalid();
    } finally {
      activeConnection = null;
      if (connection != null) connection.disconnect();
    }
  }

  public static long retryAfter(String header, long now) {
    if (header == null) return 0;
    try {
      long seconds = Long.parseLong(header.trim());
      return seconds <= 0 ? now : Math.addExact(now, Math.multiplyExact(seconds, 1000L));
    } catch (NumberFormatException date) {
      try { return java.time.ZonedDateTime.parse(header.trim(),
          java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); }
      catch (java.time.format.DateTimeParseException invalid) { return 0; }
    } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
  }

  private AppException invalid() {
    return new AppException(ErrorCode.INVALID_RESPONSE, "翻译服务未返回有效文本", false);
  }
}
