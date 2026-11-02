package com.example.bilingualreader.contract.translation;

import com.example.bilingualreader.contract.model.*;

/** observe 方法可从主线程调用；其他方法只在后台线程调用。详见 docs/PROJECT_SPEC.md。 */
public interface TranslationService {
  TranslationBatchResult translate(TranslationBatchRequest request) throws AppException;
}
