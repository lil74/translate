package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.util.*;

/** Pure request assembly for transient homepage queries, without any persistence. */
public final class LookupQuery {
  private LookupQuery() {}

  public static final int AUTO = 0, EN_TO_ZH = 1, ZH_TO_EN = 2;

  public static String source(String text, int direction) throws AppException {
    if (text == null || text.codePoints().allMatch(Character::isWhitespace))
      throw invalid("请输入需要翻译的单词、短语或句子");
    if (text.codePointCount(0, text.length()) > 4000) throw invalid("最多可查询 4000 个字符，请缩短内容");
    if (direction == EN_TO_ZH) return "en";
    if (direction == ZH_TO_EN) return "zh-CN";
    if (direction != AUTO) throw invalid("请选择有效的翻译方向");
    if (text.codePoints()
        .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
      return "zh-CN";
    if (text.codePoints()
        .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN)) return "en";
    throw invalid("暂时无法判断方向，请输入中文或英文，或手动选择方向");
  }

  public static TranslationBatchRequest request(String text, int direction) throws AppException {
    String source = source(text, direction), id = "lookup:" + UUID.randomUUID();
    return new TranslationBatchRequest(
        id,
        UUID.randomUUID().toString(),
        source,
        "en".equals(source) ? "zh-CN" : "en",
        Collections.singletonList(
            new TranslationInput(id, TranslationValidation.sha256(text), text)));
  }

  public static String translate(TranslationService service, TranslationBatchRequest request)
      throws AppException {
    TranslationBatchResult result =
        TranslationValidation.validateResult(request, service.translate(request));
    TranslationOutput output = result.getItems().get(0);
    if (output.getStatus() != TranslationStatus.SUCCESS)
      throw new AppException(ErrorCode.TRANSLATION_FAILED, "查询失败，请稍后重试", true);
    return output.getTranslatedText();
  }

  private static AppException invalid(String message) {
    return new AppException(ErrorCode.INVALID_RESPONSE, message, false);
  }
}
