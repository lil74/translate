package com.example.bilingualreader.contract.repository;

import android.net.Uri;
import androidx.lifecycle.LiveData;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.util.List;

/** observe 方法可从主线程调用；其他方法只在后台线程调用。详见 docs/PROJECT_SPEC.md。 */
public interface BookRepository {
  ImportResult importBook(Uri sourceUri) throws AppException;

  LiveData<List<Book>> observeBooks();

  List<Chapter> getChapters(String bookId) throws AppException;

  List<TocEntry> getTableOfContents(String bookId) throws AppException;

  void deleteBook(String bookId) throws AppException;
}
