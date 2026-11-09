package com.example.bilingualreader.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.example.bilingualreader.ReaderApplication;
import com.example.bilingualreader.di.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.translation.TranslationService;
import com.example.bilingualreader.translation.*;

public final class TranslationWorker extends Worker {
  // v1 intentionally serializes provider traffic, even across different chapters.
  private static final Object EXECUTION_LOCK = new Object();
  private volatile TranslationService service;
  public TranslationWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }
  @NonNull @Override public Result doWork() {
    synchronized (EXECUTION_LOCK) {
      if (isStopped()) return Result.retry();
      AppContainer graph = ((ReaderApplication) getApplicationContext()).getContainer();
      String id = getInputData().getString("taskId");
      if (id == null) return Result.failure();
      try {
        TranslationTask task = graph.translationStore.getTask(id);
        if (task == null || (task.getState() != TaskState.QUEUED && task.getState() != TaskState.RUNNING))
          return Result.success();
        String[] selection = graph.translationStore.getService(id);
        service = AppGraphFactory.service(selection[0], selection[1]);
        ChapterTranslationRunner.Outcome result = new ChapterTranslationRunner(graph.translationStore,
            service, this::isStopped, System::currentTimeMillis).run(id);
        return result == ChapterTranslationRunner.Outcome.RETRY ? Result.retry() : Result.success();
      } catch (AppException error) {
        if (isStopped()) return Result.retry();
        try { graph.translationStore.failTask(id, error.getCode().name(), error.getUserMessage()); }
        catch (AppException database) { return Result.failure(); }
        return Result.failure();
      } catch (RuntimeException unexpected) {
        if (isStopped()) return Result.retry();
        try { graph.translationStore.failTask(id, ErrorCode.UNKNOWN.name(), "翻译任务异常终止，请重试未完成段落"); }
        catch (AppException ignored) { /* No recursive work or unbounded retries on database failure. */ }
        return Result.failure();
      } finally { service = null; }
    }
  }
  @Override public void onStopped() {
    TranslationService current = service;
    if (current instanceof LibreTranslateService) ((LibreTranslateService) current).cancel();
    AppContainer graph = ((ReaderApplication) getApplicationContext()).getContainer();
    String id = getInputData().getString("taskId");
    if (graph != null && id != null) graph.io.execute(() -> {
      try { graph.translationStore.markTaskWaiting(id, ErrorCode.NETWORK_ERROR.name(),
          "任务暂停，等待网络或系统恢复，已完成译文已保存"); }
      catch (AppException ignored) { /* Cancellation/deletion may already have removed the task. */ }
    });
  }
}
