package com.example.bilingualreader.video;

import com.example.bilingualreader.translation.TranslationValidation;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Per-video atomic checkpoints plus small summaries; URI-derived names never contain user paths.
 */
public final class VideoLibrary {
  private static final Object LOCK = new Object();
  private final File directory;
  private final Gson gson = new Gson();

  public static final class Entry {
    public final String uri, name;
    public final long positionMs, updatedAt;

    Entry(VideoProject project, long time) {
      uri = project.videoUri;
      name = project.videoName;
      positionMs = project.positionMs;
      updatedAt = time;
    }
  }

  public VideoLibrary(File directory) {
    this.directory = directory;
  }

  private String key(String uri) {
    if (uri == null || uri.trim().isEmpty()) throw new IllegalArgumentException("视频标识为空");
    return TranslationValidation.sha256(uri);
  }

  private File path(String uri, String suffix) {
    return new File(directory, key(uri) + suffix);
  }

  public boolean contains(String uri) {
    synchronized (LOCK) {
      return !path(uri, ".deleted").exists() && path(uri, ".json").isFile();
    }
  }

  public VideoProject load(String uri) throws IOException {
    synchronized (LOCK) {
      if (!contains(uri)) throw new IOException("此视频已移除，请重新导入");
      VideoProject project = decode(read(path(uri, ".json"), 16 * 1024 * 1024));
      if (!uri.equals(project.videoUri)) throw new IOException("视频记录标识不匹配");
      return project;
    }
  }

  public VideoProject add(String uri, String name) throws IOException {
    synchronized (LOCK) {
      File deleted = path(uri, ".deleted");
      if (deleted.exists()) {
        Files.deleteIfExists(path(uri, ".json").toPath());
        Files.deleteIfExists(path(uri, ".meta").toPath());
        Files.delete(deleted.toPath());
      }
      if (contains(uri)) return load(uri);
      VideoProject project =
          new VideoProject(uri, name, null, 0, null, false, 0, Collections.emptyList());
      save(project);
      return project;
    }
  }

  public VideoProject rename(String uri, String name) throws IOException {
    synchronized (LOCK) {
      VideoProject project = load(uri).named(name);
      save(project);
      return project;
    }
  }

  public boolean save(VideoProject project) throws IOException {
    synchronized (LOCK) {
      if (project.videoUri == null) return false;
      if (path(project.videoUri, ".deleted").exists()) return false;
      byte[] raw = gson.toJson(project).getBytes(StandardCharsets.UTF_8);
      if (raw.length > 16 * 1024 * 1024) throw new IOException("视频项目超过本地保存限制");
      write(path(project.videoUri, ".json"), raw);
      write(
          path(project.videoUri, ".meta"),
          gson.toJson(new Entry(project, System.currentTimeMillis()))
              .getBytes(StandardCharsets.UTF_8));
      return true;
    }
  }

  public List<Entry> list() throws IOException {
    synchronized (LOCK) {
      List<Entry> rows = new ArrayList<>();
      File[] projects = directory.listFiles((dir, name) -> name.matches("[a-f0-9]{64}\\.json"));
      if (projects == null) return rows;
      for (File file : projects) {
        String hash = file.getName().substring(0, 64);
        if (new File(directory, hash + ".deleted").exists()) continue;
        File meta = new File(directory, hash + ".meta");
        Entry entry = null;
        if (meta.isFile())
          try {
            entry =
                gson.fromJson(
                    new String(read(meta, 64 * 1024), StandardCharsets.UTF_8), Entry.class);
          } catch (RuntimeException | IOException ignored) {
          }
        if (entry == null
            || entry.uri == null
            || entry.uri.trim().isEmpty()
            || entry.positionMs < 0
            || entry.updatedAt < 0
            || !hash.equals(key(entry.uri))) {
          VideoProject restored = decode(read(file, 16 * 1024 * 1024));
          if (restored.videoUri == null
              || restored.videoUri.trim().isEmpty()
              || !hash.equals(key(restored.videoUri))) throw new IOException("视频记录标识不匹配");
          entry = new Entry(restored, file.lastModified());
          write(meta, gson.toJson(entry).getBytes(StandardCharsets.UTF_8));
        }
        rows.add(entry);
      }
      rows.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
      return rows;
    }
  }

  public void delete(String uri) throws IOException {
    synchronized (LOCK) {
      write(path(uri, ".deleted"), new byte[0]);
      Files.deleteIfExists(path(uri, ".json").toPath());
      Files.deleteIfExists(path(uri, ".meta").toPath());
    }
  }

  private byte[] read(File file, int limit) throws IOException {
    try (InputStream input = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int size;
      while ((size = input.read(buffer)) != -1) {
        if (out.size() + size > limit) throw new IOException("视频记录过大");
        out.write(buffer, 0, size);
      }
      return out.toByteArray();
    }
  }

  private void write(File file, byte[] raw) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建视频目录");
    File temp = File.createTempFile("checkpoint-", ".tmp", directory);
    try {
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(raw);
        out.getFD().sync();
      }
      try {
        Files.move(
            temp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp.toPath());
    }
  }

  public static VideoProject decode(byte[] raw) throws IOException {
    try {
      VideoProject value =
          new Gson().fromJson(new String(raw, StandardCharsets.UTF_8), VideoProject.class);
      if (value == null || value.cues == null) throw new IllegalArgumentException();
      List<SubtitleSegment> cues = new ArrayList<>();
      for (SubtitleSegment c : value.cues)
        cues.add(
            new SubtitleSegment(
                c.id,
                c.startMs,
                c.endMs,
                c.sourceText,
                c.textSource,
                c.confidence,
                c.translatedText,
                c.provider,
                c.engineVersion));
      if (value.translationKey == null && cues.stream().anyMatch(c -> c.translatedText != null))
        throw new IllegalArgumentException();
      return new VideoProject(
          value.videoUri,
          value.videoName,
          value.subtitleName,
          value.direction,
          value.translationKey,
          value.demo,
          value.positionMs,
          cues);
    } catch (RuntimeException malformed) {
      throw new IOException("视频项目无法读取，请重新导入", malformed);
    }
  }
}
