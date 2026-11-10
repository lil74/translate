package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;

/** Stateless transient service: each lookup owns its adapter, independent of chapter Workers. */
public final class IsolatedSelectionTranslationService implements TranslationService {
  public interface Factory { TranslationService create() throws AppException; }
  private final Factory factory;
  public IsolatedSelectionTranslationService(Factory factory) { this.factory = java.util.Objects.requireNonNull(factory); }
  @Override public TranslationBatchResult translate(TranslationBatchRequest request) throws AppException {
    TranslationValidation.validateRequest(request);
    if (request.getItems().size() != 1) throw new AppException(ErrorCode.INVALID_RESPONSE,
        "临时选词翻译每次只接受一项所选文字", false);
    if (Thread.currentThread().isInterrupted()) throw new AppException(ErrorCode.TRANSLATION_FAILED,
        "所选内容翻译已取消", false);
    return TranslationValidation.validateResult(request, factory.create().translate(request));
  }
}
