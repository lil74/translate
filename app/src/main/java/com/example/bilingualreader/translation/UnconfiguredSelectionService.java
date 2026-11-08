package com.example.bilingualreader.translation;

import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.*;

public final class UnconfiguredSelectionService implements TranslationService {
  public TranslationBatchResult translate(TranslationBatchRequest request) throws AppException {
    throw new AppException(ErrorCode.TRANSLATION_FAILED, "选词翻译服务尚未配置，请联系团队接入", false);
  }
}
