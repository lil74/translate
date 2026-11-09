package com.example.bilingualreader.translation;

import android.net.Uri;
import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.*;
import java.util.List;

/** Coordinate book removal with translation, while B continues to own file and database writes. */
public final class TranslationBookRepository implements BookRepository {
  private final BookRepository books;
  private final TranslationRuntimeStore store;
  private final WorkTranslationCoordinator coordinator;
  public TranslationBookRepository(BookRepository books, TranslationRuntimeStore store, WorkTranslationCoordinator coordinator) {
    this.books = books; this.store = store; this.coordinator = coordinator;
  }
  public ImportResult importBook(Uri source) throws AppException { return books.importBook(source); }
  public LiveData<List<Book>> observeBooks() { return books.observeBooks(); }
  public List<Chapter> getChapters(String id) throws AppException { return books.getChapters(id); }
  public List<TocEntry> getTableOfContents(String id) throws AppException { return books.getTableOfContents(id); }
  public void deleteBook(String id) throws AppException {
    synchronized (WorkTranslationCoordinator.COMMAND_LOCK) {
      for (TranslationTask task : store.getBookTasks(id)) coordinator.cancelTask(task.getTaskId());
      books.deleteBook(id);
    }
  }
}
