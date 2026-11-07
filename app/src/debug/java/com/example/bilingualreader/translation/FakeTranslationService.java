package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;
import java.util.*;

/** Debug wiring aid only; this is deliberately not a translation engine. */
public final class FakeTranslationService implements TranslationService {
  @Override public TranslationBatchResult translate(TranslationBatchRequest request)
      throws AppException {
    TranslationValidation.validateRequest(request);
    List<TranslationOutput> outputs = new ArrayList<>();
    for (TranslationInput input : request.getItems()) {
      outputs.add(new TranslationOutput(input.getParagraphId(), input.getSourceHash(),
          TranslationStatus.SUCCESS, "【模拟译文，非真实翻译】" + input.getText(),
          "fake", "m1", null));
    }
    return TranslationValidation.validateResult(request,
        new TranslationBatchResult(request.getTaskId(), request.getRequestId(), outputs));
  }
}
