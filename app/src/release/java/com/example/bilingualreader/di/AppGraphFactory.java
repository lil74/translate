package com.example.bilingualreader.di;

import android.content.Context;
import android.net.Uri;
import androidx.lifecycle.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.*;
import com.example.bilingualreader.contract.translation.*;
import com.example.bilingualreader.data.repository.PreferencesSettingsRepository;
import com.example.bilingualreader.data.repository.LocalLibraryRepository;
import java.util.*;

/** Release 不带演示正文；B/C 完成后在此接入正式实现。 */
public final class AppGraphFactory {
  public static AppContainer create(Context context) {
    LocalLibraryRepository library = new LocalLibraryRepository(context);
    PendingIntegration p = new PendingIntegration();
    return new AppContainer(library, library, new PreferencesSettingsRepository(context), p, false);
  }

  private static final class PendingIntegration
      implements BookRepository, ReaderRepository, TranslationCoordinator {
    private AppException pending() {
      return new AppException(
          ErrorCode.UNSUPPORTED_CONTENT, "正式 EPUB 与翻译模块尚未接入，请先使用 Debug 演示版", false);
    }

    public ImportResult importBook(Uri u) throws AppException {
      throw pending();
    }

    public LiveData<List<Book>> observeBooks() {
      return new MutableLiveData<>(Collections.emptyList());
    }

    public List<Chapter> getChapters(String id) throws AppException {
      throw pending();
    }

    public List<TocEntry> getTableOfContents(String id) throws AppException {
      throw pending();
    }

    public void deleteBook(String id) throws AppException {
      throw pending();
    }

    public LiveData<List<ParagraphItem>> observeParagraphs(String id, String lang) {
      return new MutableLiveData<>(Collections.emptyList());
    }

    public ReadingProgress getReadingProgress(String id) throws AppException {
      throw pending();
    }

    public void saveReadingProgress(ReadingProgress p) throws AppException {
      throw pending();
    }

    public String startOrResumeChapter(String id, String s, String t) throws AppException {
      throw pending();
    }

    public LiveData<TranslationTask> observeTask(String id) {
      return new MutableLiveData<>();
    }

    public LiveData<TranslationTask> observeLatestTask(String id, String t) {
      return new MutableLiveData<>();
    }

    public void cancelTask(String id) throws AppException {
      throw pending();
    }
  }
}
