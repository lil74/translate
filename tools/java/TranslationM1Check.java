import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.translation.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Executable JVM assertions, including a real HTTP transport against a local protocol stub. */
public final class TranslationM1Check {
  static int checks;
  interface Checked { void run() throws Exception; }
  static void check(boolean value) {
    if (!value) throw new AssertionError("Check " + (checks + 1));
    checks++;
  }
  static void rejects(Checked action, ErrorCode code) throws Exception {
    try { action.run(); } catch (AppException error) {
      check(error.getCode() == code); return;
    }
    throw new AssertionError("Expected " + code);
  }
  static TranslationBatchResult result(TranslationBatchRequest r, List<TranslationOutput> outputs) {
    return new TranslationBatchResult(r.getTaskId(), r.getRequestId(), outputs);
  }
  static TranslationOutput success(TranslationInput i, String text) {
    return new TranslationOutput(i.getParagraphId(), i.getSourceHash(),
        TranslationStatus.SUCCESS, text, "manual-reference", "m1", null);
  }
  static TranslationBatchRequest request(List<TranslationInput> inputs) {
    return new TranslationBatchRequest("m1-task", "m1-batch", "en", "zh-CN", inputs);
  }

  public static void main(String[] args) throws Exception {
    JsonObject fixture = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
    List<TranslationInput> inputs = new ArrayList<>();
    List<TranslationOutput> outputs = new ArrayList<>();
    for (JsonElement element : fixture.getAsJsonArray("items")) {
      JsonObject item = element.getAsJsonObject();
      TranslationInput input = new TranslationInput(item.get("paragraphId").getAsString(),
          item.get("sourceHash").getAsString(), item.get("text").getAsString());
      inputs.add(input);
      outputs.add(success(input, item.get("reference").getAsString()));
    }
    TranslationBatchRequest r = request(inputs);
    TranslationValidation.validateRequest(r);
    check(TranslationValidation.sha256("abc")
        .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    List<TranslationOutput> reversed = new ArrayList<>(outputs);
    Collections.reverse(reversed);
    TranslationBatchResult ordered = TranslationValidation.validateResult(r, result(r, reversed));
    for (int i = 0; i < inputs.size(); i++) {
      check(ordered.getItems().get(i).getParagraphId().equals(inputs.get(i).getParagraphId()));
      check(ordered.getItems().get(i).getTranslatedText().equals(outputs.get(i).getTranslatedText()));
    }
    TranslationBatchResult missing = TranslationValidation.validateResult(r,
        result(r, outputs.subList(1, outputs.size())));
    check(missing.getItems().get(0).getStatus() == TranslationStatus.FAILED);
    check(missing.getItems().get(1).getParagraphId().equals(inputs.get(1).getParagraphId()));
    rejects(() -> TranslationValidation.validateResult(r,
        result(r, Arrays.asList(outputs.get(0), outputs.get(0)))), ErrorCode.INVALID_RESPONSE);
    rejects(() -> TranslationValidation.validateResult(r, new TranslationBatchResult(
        "wrong-task", r.getRequestId(), outputs)), ErrorCode.INVALID_RESPONSE);
    rejects(() -> TranslationValidation.validateResult(r, new TranslationBatchResult(
        r.getTaskId(), "wrong-request", outputs)), ErrorCode.INVALID_RESPONSE);
    rejects(() -> TranslationValidation.validateResult(r, result(r, Arrays.asList(
        success(new TranslationInput("unknown", inputs.get(0).getSourceHash(), "x"), "译文")))),
        ErrorCode.INVALID_RESPONSE);
    rejects(() -> TranslationValidation.validateResult(r, result(r, Arrays.asList(
        success(new TranslationInput(inputs.get(0).getParagraphId(), "bad-hash", "x"), "译文")))),
        ErrorCode.INVALID_RESPONSE);
    rejects(() -> TranslationValidation.validateResult(r, result(r, Arrays.asList(
        success(inputs.get(0), "  ")))), ErrorCode.INVALID_RESPONSE);
    rejects(() -> TranslationValidation.validateRequest(request(Arrays.asList(
        inputs.get(0), inputs.get(0)))), ErrorCode.INVALID_RESPONSE);
    rejects(() -> TranslationValidation.validateRequest(request(Arrays.asList(
        new TranslationInput("p", "bad-hash", "x")))), ErrorCode.INVALID_RESPONSE);
    rejects(() -> TranslationValidation.validateRequest(new TranslationBatchRequest(
        "t", "r", "fr", "zh-CN", inputs)), ErrorCode.UNSUPPORTED_LANGUAGE);
    List<TranslationInput> nine = new ArrayList<>();
    for (int i = 0; i < 9; i++) nine.add(new TranslationInput("p"+i, TranslationValidation.sha256("a"), "a"));
    rejects(() -> TranslationValidation.validateRequest(request(nine)), ErrorCode.INVALID_RESPONSE);
    String unicode = "😀".repeat(4000);
    TranslationValidation.validateRequest(request(Arrays.asList(
        new TranslationInput("unicode", TranslationValidation.sha256(unicode), unicode))));
    check(true);
    String longText = unicode + "a";
    rejects(() -> TranslationValidation.validateRequest(request(Arrays.asList(
        new TranslationInput("long", TranslationValidation.sha256(longText), longText)))), ErrorCode.INVALID_RESPONSE);
    check(new FakeTranslationService().translate(r).getItems().get(0)
        .getTranslatedText().startsWith("【模拟译文"));

    // This server is a protocol stub, not a translation engine.
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/translate", exchange -> {
      JsonObject payload = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject();
      String source = payload.get("q").getAsString();
      String response = "";
      for (int i = 0; i < inputs.size(); i++) if (source.equals(inputs.get(i).getText())) {
        JsonObject json = new JsonObject();
        json.addProperty("translatedText", outputs.get(i).getTranslatedText());
        response = json.toString();
      }
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (java.io.OutputStream stream = exchange.getResponseBody()) { stream.write(body); }
    });
    for (int status : new int[]{401, 429, 503}) {
      server.createContext("/http"+status, exchange -> {
        exchange.sendResponseHeaders(status, -1); exchange.close();
      });
    }
    server.createContext("/invalid", exchange -> {
      byte[] body = "{\"translatedText\":42}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (java.io.OutputStream stream = exchange.getResponseBody()) { stream.write(body); }
    });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      TranslationBatchResult http = new LibreTranslateService(base+"/translate").translate(r);
      for (int i = 0; i < inputs.size(); i++) {
        check(http.getItems().get(i).getParagraphId().equals(inputs.get(i).getParagraphId()));
        check(http.getItems().get(i).getTranslatedText().equals(outputs.get(i).getTranslatedText()));
      }
      rejects(() -> new LibreTranslateService(base+"/http401").translate(r), ErrorCode.AUTH_FAILED);
      rejects(() -> new LibreTranslateService(base+"/http429").translate(r), ErrorCode.RATE_LIMITED);
      rejects(() -> new LibreTranslateService(base+"/http503").translate(r), ErrorCode.NETWORK_ERROR);
      rejects(() -> new LibreTranslateService(base+"/invalid").translate(r), ErrorCode.INVALID_RESPONSE);
    } finally { server.stop(0); }
    rejects(() -> new LibreTranslateService(base+"/translate").translate(r), ErrorCode.NETWORK_ERROR);
    JsonObject report = new JsonObject();
    report.addProperty("checks", checks);
    report.addProperty("realService", "未配置；本次仅验证人工参考和本地 HTTP 协议桩");
    if (args.length > 2) {
      TranslationBatchResult real = new LibreTranslateService(args[2]).translate(r);
      report.addProperty("realService", "已调用配置的服务；译文需人工复核质量");
      report.add("realOutputs", new Gson().toJsonTree(real.getItems()));
    }
    Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    System.out.println("PASS: " + checks + " checks; real engine: " + (args.length > 2));
  }
}
