package com.example.bilingualreader.video;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.translation.TranslationValidation;
import java.util.*;

/** One cue at a time to also support the existing isolated single-item service binding. */
public final class SubtitleTranslator {
  public interface Progress { void completed(SubtitleSegment cue) throws AppException; }
  public void translate(List<SubtitleSegment> cues, TranslationService service,
      String source, String target, Progress progress) throws AppException {
    for (SubtitleSegment cue : cues) {
      if (Thread.currentThread().isInterrupted()) throw cancelled();
      if (cue.translatedText != null) continue;
      List<String> parts = split(cue.sourceText);
      StringBuilder translated = new StringBuilder();
      String provider = null, version = null;
      for (int n=0; n<parts.size(); n++) {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
        String text = parts.get(n), id = cue.id + ":part:" + n;
        if (text.codePoints().allMatch(Character::isWhitespace)) { translated.append(text); continue; }
        TranslationBatchRequest request = new TranslationBatchRequest("video:" + UUID.randomUUID(),
            UUID.randomUUID().toString(), source, target, Collections.singletonList(
                new TranslationInput(id, TranslationValidation.sha256(text), text)), TranslationBatchRequest.Purpose.SUBTITLE);
        TranslationBatchResult response = TranslationValidation.validateResult(request, service.translate(request));
        TranslationOutput output = response.getItems().get(0);
        if (output.getStatus() != TranslationStatus.SUCCESS)
          throw new AppException(ErrorCode.TRANSLATION_FAILED, "字幕翻译失败，已完成的片段已保留，可点击继续翻译", true);
        if (provider != null && (!provider.equals(output.getProvider()) || !Objects.equals(version, output.getEngineVersion())))
          throw new AppException(ErrorCode.INVALID_RESPONSE, "同条字幕返回了不同翻译引擎，请重试", false);
        provider = output.getProvider(); version = output.getEngineVersion();
        if (translated.length() > 0) translated.append('\n');
        translated.append(output.getTranslatedText());
      }
      if (Thread.currentThread().isInterrupted()) throw cancelled();
      progress.completed(cue.translated(translated.toString(), provider, version));
    }
  }
  static List<String> split(String text) {
    List<String> pieces = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int remaining = text.codePointCount(start, text.length());
      int end = remaining <= 4000 ? text.length() : text.offsetByCodePoints(start, 4000);
      if (end < text.length()) {
        int preferred = end;
        for (int p=end; p>start+(end-start)/2; p--)
          if (Character.isWhitespace(text.charAt(p-1))) { preferred=p; break; }
        end=preferred;
      }
      pieces.add(text.substring(start, end));
      start=end;
    }
    return pieces;
  }
  private static AppException cancelled() { return new AppException(ErrorCode.TRANSLATION_FAILED, "字幕翻译已取消", false); }
}
