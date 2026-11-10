package com.example.bilingualreader.translation;

import android.content.Context;
import androidx.lifecycle.LiveData;
import androidx.work.*;
import com.example.bilingualreader.contract.model.*;
import com.example.bilingualreader.contract.repository.TranslationRuntimeStore;
import com.example.bilingualreader.contract.translation.TranslationCoordinator;
import com.example.bilingualreader.worker.TranslationWorker;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** All commands run off the main thread; snapshots always come from Room. */
public final class WorkTranslationCoordinator implements TranslationCoordinator {
  public static final Object COMMAND_LOCK = new Object();
  private final TranslationRuntimeStore store;
  private final TranslationConfigStore config;
  private final WorkManager work;
  public WorkTranslationCoordinator(Context context, TranslationRuntimeStore store, TranslationConfigStore config) {
    this.store = store; this.config = config; this.work = WorkManager.getInstance(context);
  }
  public static String workName(TranslationTask task) {
    return "translate:" + task.getChapterId() + ":" + task.getSourceLanguage() + ":" + task.getTargetLanguage();
  }
  private static boolean active(TranslationTask task) {
    return task != null && (task.getState() == TaskState.QUEUED || task.getState() == TaskState.RUNNING);
  }
  @Override public String startOrResumeChapter(String chapterId, String source, String target) throws AppException {
    synchronized (COMMAND_LOCK) {
      String mode = config.mode(), endpoint = config.endpoint();
      config.validate(mode, endpoint);
      if (config.needsConsent(mode, endpoint)) throw new AppException(ErrorCode.AUTH_FAILED, "请先确认向翻译服务发送本章原文", false);
      TranslationTask task = store.prepareConfiguredTask(chapterId, source, target, mode, endpoint);
      if (active(task)) ensureScheduled(task);
      return task.getTaskId();
    }
  }
  private void ensureScheduled(TranslationTask task) throws AppException {
    if (!active(store.getTask(task.getTaskId()))) return;
    String[] selection = store.getService(task.getTaskId());
    if (selection[0].isEmpty()) {
      throw new AppException(ErrorCode.AUTH_FAILED, "旧任务缺少服务配置，请重新确认并重试翻译", false);
    }
    try {
      String name = workName(task), tag = "task:" + task.getTaskId();
      List<WorkInfo> existing = work.getWorkInfosForUniqueWork(name).get(20, TimeUnit.SECONDS);
      for (WorkInfo info : existing) if (!info.getState().isFinished() && !info.getTags().contains(tag)) {
        // A previous attempt may still be winding down while the user starts a new task.
        work.cancelUniqueWork(name).getResult().get(20, TimeUnit.SECONDS); break;
      }
      Constraints constraints = new Constraints.Builder().setRequiredNetworkType(
          TranslationConfigStore.ONLINE.equals(selection[0]) ? NetworkType.CONNECTED : NetworkType.NOT_REQUIRED).build();
      OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TranslationWorker.class)
          .setInputData(new Data.Builder().putString("taskId", task.getTaskId()).build())
          .addTag(tag).addTag("translation")
          .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
      work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).getResult().get(20, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AppException(ErrorCode.UNKNOWN, "翻译调度被中断，可再次点击继续", true, interrupted);
    } catch (Exception scheduling) {
      // Keep QUEUED: recovery on next launch repairs prepare-before-enqueue crashes.
      throw new AppException(ErrorCode.UNKNOWN, "翻译调度暂未完成，可再次点击或重启应用恢复", true, scheduling);
    }
  }
  public void recoverUnfinishedTasks() throws AppException {
    synchronized (COMMAND_LOCK) {
      AppException last = null;
      for (TranslationTask task : store.getActiveTasks()) {
        try { ensureScheduled(task); }
        catch (AppException error) {
          if (!error.isRetryable()) store.failTask(task.getTaskId(), error.getCode().name(), error.getUserMessage());
          last = error;
        }
      }
      if (last != null) throw last;
    }
  }
  @Override public void cancelTask(String id) throws AppException {
    synchronized (COMMAND_LOCK) {
      store.cancelTask(id); // Reject late results before interrupting the network operation.
      try { work.cancelAllWorkByTag("task:" + id).getResult().get(20, TimeUnit.SECONDS); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      catch (Exception ignored) { /* Persisted cancellation already makes the Worker harmless. */ }
    }
  }
  @Override public LiveData<TranslationTask> observeTask(String id) { return store.observeTask(id); }
  @Override public LiveData<TranslationTask> observeLatestTask(String chapterId, String language) {
    return store.observeLatestTask(chapterId, language);
  }
}
