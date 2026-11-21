package com.example.bilingualreader.video;

import static org.junit.Assert.*;

import com.example.bilingualreader.translation.TranslationValidation;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class VideoLibraryTest {
  @Test
  public void customNamePersistsWithoutChangingLearningData() throws Exception {
    VideoProject original = project("content://video/named", 321, "Original subtitle");
    library.save(original);
    library.rename(original.videoUri, "  我的英语练习  ");
    VideoProject restored = new VideoLibrary(directory).load(original.videoUri);
    assertEquals("我的英语练习", restored.videoName);
    assertEquals(321, restored.positionMs);
    assertEquals(original.subtitleName, restored.subtitleName);
    assertEquals(original.cues.get(0).translatedText, restored.cues.get(0).translatedText);
    assertEquals("我的英语练习", library.list().get(0).name);
  }

  @Test
  public void invalidCustomNameDoesNotReplaceSavedProject() throws Exception {
    library.add("content://video/unchanged", "Original.mp4");
    try {
      library.rename("content://video/unchanged", "   ");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertEquals("Original.mp4", library.load("content://video/unchanged").videoName);
  }

  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private File directory;
  private VideoLibrary library;

  @Before
  public void create() throws Exception {
    directory = temporary.newFolder("videos");
    library = new VideoLibrary(directory);
  }

  private VideoProject project(String uri, long position, String text) {
    return new VideoProject(
        uri,
        uri + ".mp4",
        "captions.srt",
        0,
        "service-key",
        false,
        position,
        Collections.singletonList(
            new SubtitleSegment(
                "cue",
                0,
                1000,
                text,
                SubtitleSegment.TextSource.SUBTITLE,
                null,
                "translated:" + text,
                "test",
                "1")));
  }

  @Test
  public void differentVideosKeepIndependentCuesAndPositionsAcrossRestart() throws Exception {
    library.save(project("content://a", 1200, "alpha"));
    library.save(project("content://b", 3500, "beta"));
    VideoLibrary restarted = new VideoLibrary(directory);
    assertEquals(2, restarted.list().size());
    assertEquals(1200, restarted.load("content://a").positionMs);
    assertEquals("translated:alpha", restarted.load("content://a").cues.get(0).translatedText);
    assertEquals(3500, restarted.load("content://b").positionMs);
    assertEquals("beta", restarted.load("content://b").cues.get(0).sourceText);
  }

  @Test
  public void duplicateImportPreservesExistingLearningState() throws Exception {
    library.save(project("content://a", 123, "alpha"));
    VideoProject existing = library.add("content://a", "new name");
    assertEquals(1, library.list().size());
    assertEquals(123, existing.positionMs);
    assertEquals(1, existing.cues.size());
  }

  @Test
  public void deletionRejectsLateCheckpointAndKeepsOtherVideo() throws Exception {
    VideoProject old = project("content://a", 123, "alpha");
    library.save(old);
    library.save(project("content://b", 456, "beta"));
    library.delete("content://a");
    assertFalse(library.save(old.position(999)));
    assertFalse(library.contains("content://a"));
    assertEquals(1, library.list().size());
    assertEquals(456, library.load("content://b").positionMs);
  }

  @Test
  public void explicitReimportAfterDeletionStartsFresh() throws Exception {
    library.save(project("content://a", 123, "alpha"));
    library.delete("content://a");
    VideoProject fresh = library.add("content://a", "video.mp4");
    assertEquals(0, fresh.positionMs);
    assertTrue(fresh.cues.isEmpty());
    assertEquals(1, library.list().size());
  }

  @Test
  public void missingSummaryCanBeRecoveredFromCheckpoint() throws Exception {
    library.save(project("content://a", 321, "alpha"));
    Files.delete(
        new File(directory, TranslationValidation.sha256("content://a") + ".meta").toPath());
    assertEquals(1, library.list().size());
    assertEquals(321, library.list().get(0).positionMs);
  }

  @Test
  public void malformedOrCrossVideoCheckpointIsRejected() throws Exception {
    String uri = "content://a";
    library.save(project(uri, 0, "alpha"));
    File file = new File(directory, TranslationValidation.sha256(uri) + ".json");
    Files.write(
        file.toPath(),
        new Gson().toJson(project("content://b", 0, "beta")).getBytes(StandardCharsets.UTF_8));
    try {
      library.load(uri);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("标识"));
    }
    Files.write(file.toPath(), "invalid".getBytes(StandardCharsets.UTF_8));
    try {
      library.load(uri);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void uriDoesNotBecomeFilesystemPath() throws Exception {
    String uri = "content://videos/../../outside?path=/another/file";
    library.add(uri, "A title with / and ..");
    assertEquals(1, library.list().size());
    for (File file : Objects.requireNonNull(directory.listFiles()))
      assertEquals(directory.getCanonicalFile(), file.getCanonicalFile().getParentFile());
  }
}
