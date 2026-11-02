package com.example.bilingualreader.contract.repository;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.util.List;

/** observe 方法可从主线程调用；其他方法只在后台线程调用。详见 docs/PROJECT_SPEC.md。 */
public interface ReaderRepository {
  LiveData<List<ParagraphItem>> observeParagraphs(String chapterId, String targetLanguage);

  @Nullable
  ReadingProgress getReadingProgress(String bookId) throws AppException;

  void saveReadingProgress(ReadingProgress progress) throws AppException;
}
