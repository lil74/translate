package com.example.bilingualreader.video;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.translation.TranslationValidation;
import java.util.*;
import java.util.regex.*;

/** Strict plain-text SRT/WebVTT import. Never invents timing for untimed text. */
public final class SubtitleCodec implements TimedTextSource<String> {
  public static final int MAX_CUES = 5000;
  private static final Pattern TIME = Pattern.compile("(?:(\\d{2,}):)?([0-5]\\d):([0-5]\\d)[.,](\\d{3})");

  @Override public List<SubtitleSegment> acquire(String input) throws AppException {
    if (input == null || input.length() > 2 * 1024 * 1024) throw invalid("字幕文件为空或超过 2 MB 限制");
    String text = input.replace("\r\n", "\n").replace('\r', '\n').replaceFirst("^\uFEFF", "");
    boolean vtt = text.startsWith("WEBVTT");
    if (vtt && !(text.startsWith("WEBVTT\n") || text.startsWith("WEBVTT ") || text.startsWith("WEBVTT\t")))
      throw invalid("WebVTT 文件头无效");
    if (vtt && text.contains("X-TIMESTAMP-MAP")) throw invalid("暂不支持带 X-TIMESTAMP-MAP 的流式字幕，请转换为普通 VTT/SRT");
    List<SubtitleSegment> result = new ArrayList<>();
    String prefix = "subtitle:" + TranslationValidation.sha256(text) + ":";
    for (String block : text.split("\n[ \t]*\n+")) {
      if (block.trim().isEmpty()) continue;
      String[] lines = block.split("\n", -1);
      if (vtt && lines[0].startsWith("WEBVTT") && block.contains("-->"))
        throw invalid("WebVTT 文件头与第一条字幕之间需要空行");
      if (vtt && (lines[0].startsWith("WEBVTT") || lines[0].equals("STYLE")
          || lines[0].equals("REGION") || lines[0].matches("NOTE([ \t].*)?"))) continue;
      int timing = lines[0].contains("-->") ? 0 : 1;
      if (timing >= lines.length || !lines[timing].contains("-->")) throw invalid("第 " + (result.size()+1) + " 条字幕缺少时间轴");
      String[] range = lines[timing].trim().split("[ \t]+-->[ \t]+", -1);
      if (range.length != 2) throw invalid("字幕时间轴格式无效");
      long start = timestamp(range[0], vtt), end = timestamp(range[1].split("[ \t]", 2)[0], vtt);
      if (end <= start) throw invalid("字幕结束时间必须晚于开始时间");
      StringBuilder body = new StringBuilder();
      for (int i = timing+1; i < lines.length; i++) {
        if (body.length() > 0) body.append('\n');
        body.append(lines[i]);
      }
      String original = body.toString()
          .replaceAll("(?i)</?(?:b|i|u|font|c|v|lang)(?:[ .][^>]*)?>", "")
          .replaceAll("<\\d{2}:\\d{2}(?::\\d{2})?\\.\\d{3}>", "")
          .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
          .replace("&quot;", "\"").replace("&amp;", "&");
      if (original.trim().isEmpty()) throw invalid("字幕正文为空");
      if (original.codePointCount(0, original.length()) > 40000) throw invalid("单条字幕超过 40000 字符，请先拆分");
      result.add(new SubtitleSegment(prefix + result.size(), start, end, original,
          SubtitleSegment.TextSource.SUBTITLE, null, null, null, null));
      if (result.size() > MAX_CUES) throw invalid("初版最多支持 5000 条字幕，请分段导入");
    }
    if (result.isEmpty()) throw invalid("没有找到可用字幕，请选择 UTF-8/UTF-16 编码的 SRT 或 VTT 文件");
    result.sort(Comparator.comparingLong(c -> c.startMs));
    return Collections.unmodifiableList(result);
  }

  private static long timestamp(String text, boolean vtt) throws AppException {
    Matcher m = TIME.matcher(text);
    if (!m.matches() || !vtt && (m.group(1) == null || !text.contains(","))) throw invalid("字幕时间格式无效：" + text);
    try {
      long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
      if (hours > 999) throw invalid("字幕时间超过支持范围");
      return hours*3600000 + Long.parseLong(m.group(2))*60000 + Long.parseLong(m.group(3))*1000 + Long.parseLong(m.group(4));
    } catch (NumberFormatException error) { throw invalid("字幕时间过大"); }
  }

  public static String export(List<SubtitleSegment> cues, boolean vtt, boolean bilingual) throws AppException {
    if (cues.isEmpty()) throw invalid("没有可导出的字幕");
    StringBuilder out = new StringBuilder(vtt ? "WEBVTT\n\n" : "");
    for (int i = 0; i < cues.size(); i++) {
      SubtitleSegment cue = cues.get(i);
      if (cue.translatedText == null) throw invalid("还有未翻译的字幕，请继续翻译后再导出");
      out.append(i+1).append('\n').append(formatTime(cue.startMs, vtt)).append(" --> ")
          .append(formatTime(cue.endMs, vtt)).append('\n');
      if (bilingual) out.append(escape(cue.sourceText)).append('\n');
      out.append(escape(cue.translatedText)).append("\n\n");
    }
    return out.toString();
  }
  private static String escape(String value) {
    // A blank line would terminate a cue in both formats.
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n[ \t]*\n+", "\n")
        .replaceAll("\\A[ \\t]*\\n|\\n[ \\t]*\\z", "");
  }
  public static String formatTime(long ms, boolean vtt) {
    return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", ms/3600000, ms/60000%60,
        ms/1000%60, vtt ? '.' : ',', ms%1000);
  }
  private static AppException invalid(String message) { return new AppException(ErrorCode.INVALID_RESPONSE, message, false); }
}
