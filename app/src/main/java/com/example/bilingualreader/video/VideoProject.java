package com.example.bilingualreader.video;

import java.util.*;

/** One local video-learning project. No credentials, book IDs, or chapter cache keys. */
public final class VideoProject {
  public final String videoUri, videoName, subtitleName, translationKey;
  public final int direction;
  public final boolean demo;
  public final long positionMs;
  public final List<SubtitleSegment> cues;

  public VideoProject(String videoUri, String videoName, String subtitleName, int direction,
      String translationKey, boolean demo, long positionMs, List<SubtitleSegment> cues) {
    if (direction < 0 || direction > 3 || positionMs < 0 || cues == null || cues.size() > SubtitleCodec.MAX_CUES)
      throw new IllegalArgumentException("视频项目数据无效");
    Set<String> ids = new HashSet<>();
    long previous = -1;
    for (SubtitleSegment cue : cues) {
      if (cue == null || !ids.add(cue.id) || cue.startMs < previous)
        throw new IllegalArgumentException("字幕顺序或标识无效");
      previous = cue.startMs;
    }
    this.videoUri = videoUri;
    this.videoName = videoName;
    this.subtitleName = subtitleName;
    this.direction = direction;
    this.translationKey = translationKey;
    this.demo = demo;
    this.positionMs = positionMs;
    this.cues = Collections.unmodifiableList(new ArrayList<>(cues));
  }

  public static VideoProject empty() {
    return new VideoProject(null, null, null, 0, null, false, 0, Collections.emptyList());
  }

  public VideoProject named(String name) {
    String value = name == null ? "" : name.trim();
    if (value.isEmpty() || value.length() > 80)
      throw new IllegalArgumentException("视频名称需为 1–80 个字符");
    return new VideoProject(
        videoUri, value, subtitleName, direction, translationKey, demo, positionMs, cues);
  }

  public VideoProject withCues(List<SubtitleSegment> cues, String key, boolean demo) {
    return new VideoProject(
        videoUri, videoName, subtitleName, direction, key, demo, positionMs, cues);
  }

  public VideoProject position(long position) {
    return new VideoProject(
        videoUri,
        videoName,
        subtitleName,
        direction,
        translationKey,
        demo,
        Math.max(0, position),
        cues);
  }

  public int translatedCount() {
    int count = 0;
    for (SubtitleSegment cue : cues) if (cue.translatedText != null) count++;
    return count;
  }

  public String sourceLanguage() {
    return new String[]{"en", "zh-CN", "ko", "ja"}[direction];
  }

  public String targetLanguage() {
    return direction == 1 ? "en" : "zh-CN";
  }
}
