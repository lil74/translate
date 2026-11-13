# 接口对接说明 · M0

> 2026-11-09 统一：保留原五参数与 A 的七参数 AppContainer，同时使用九参数入口接入选词服务和 C 的运行时依赖。选词服务和模拟标志通过 selectionBinding() 一起快照，保留到弹窗 ViewModel；不新增 HTTP / 业务共享模型 / 表。当前说明见 [C_SELECTION_UNIFICATION.md](C_SELECTION_UNIFICATION.md)，下文早期接线记录保留其日期。

选词/选句翻译补充：已有 TranslationService、请求和响应类型均不变。AppContainer 新增 selectionTranslation 与 selectionDemo，提供七参数构造方法，原五参数构造方法保留。临时查询不经过章节 Coordinator/TranslationStore。C 的详细接线说明见 C_SELECTION_TRANSLATION.md。

2026-11-08 补充：共享签名与字段未变。当前两个 Factory 已接真实 LocalLibraryRepository；Debug 用 StoreDemoCoordinator + FakeTranslationService + TranslationStore 验证 A 界面，Release 使用 UnavailableTranslationCoordinator 等待 C 正式调度。具体替换方法见 A_COMPLETE.md。下文保留最初契约说明。

> 2026-11-07 补充：原 Book / Reader / TranslationStore / Coordinator / Service 的方法和业务字段未改变。新增 `TranslationRuntimeStore extends TranslationStore`，用于服务快照、活跃任务、可运行段落及持久化尝试计数；仍由 LocalLibraryRepository 唯一写库。任务与配置用 prepareConfiguredTask 原子创建，数据库升级为版本 2，见 C_IMPLEMENTATION.md。AppException 移除 final，允许 RetryAfterException 携带服务端等待时间，原构造和 getter 不变。

本次没有修改 v1.0 业务字段、方法签名或状态枚举。原说明完整保存在 `PROJECT_SPEC.md`；此文件只说明源码落点和调用方式。

## 包名

- `com.example.bilingualreader.contract.model`：Book、Chapter、TocEntry、Paragraph、Translation、ReadingProgress、TranslationTask、ReaderSettings、ParagraphItem、ImportResult、全部枚举、AppException。
- `com.example.bilingualreader.contract.repository`：BookRepository、ReaderRepository、SettingsRepository、TranslationStore。
- `com.example.bilingualreader.contract.translation`：TranslationCoordinator、TranslationService、TranslationBatchRequest、TranslationInput、TranslationBatchResult、TranslationOutput。

每个 public 类型单独文件；模型为不可变字段 + 全参数构造方法 + getter；boolean 的 alreadyExists 使用 `isAlreadyExists()`。列表防御复制、返回不可修改列表。可空字段 / 返回值以 `@Nullable` 标记。
ReaderSettings 构造时校验字号和行距范围，`defaults()` 返回 18sp / 1.5 / DAY。其他模型复杂合法性由 B/C 的业务层验证。

## 主线程与返回状态

所有 observe 方法主线程调用，仅返回 LiveData；其余阻塞方法从 AppContainer.io 或 Worker 调用。不要让 Repository 持有 Activity。

UI 已接入：书架观察、导入与删除；目录查询；正文观察；阅读位置读取和节流保存；设置观察与保存；当前章节最新翻译任务、开始 / 继续和取消。

LiveData 的首次数据到达之前显示加载状态。空列表代表查询完成但没有数据；不要在真实查询完成前人为发一个空列表。原接口未给观察流定义错误通道，因此书架 / 正文观察异常如何上报仍需团队后续确认；本次不擅自改为新返回类型。

`getReadingProgress()` 无记录返回 null；`observeLatestTask()` 无任务发 null。ImportResult.warnings 会显示给用户。UiState 在 viewmodel 包内，仅表达 UI 加载与命令结果，不是共享数据库模型。

## 必须保持的边界

- B 唯一写数据库；C 通过 TranslationStore；A 通过 Repository / Coordinator。
- 翻译显示前校验 paragraphId（组合模型由 B 关联）、sourceHash、en / zh-CN 与 SUCCESS。
- 翻译重试入口仍调用 `startOrResumeChapter`，不新增按位置拼接译文逻辑。
- Debug 的 demo-* ID 只用于演示，不适用正式稳定 ID 规则。B 正式导入必须使用 v1.0 规定的 SHA-256。
- 当前没有 HTTP 后端。若 C 增设代理，文档里的 `/v1/translations/batch` 只是团队自有接口。

## 正式接线示意（不代表类已经实现）

```java
// AppGraphFactory.create(context) 内由团队替换：
// db = B 的 Room 数据库
// books / reader / store = B 的正式实现
// coordinator = C 的正式实现（依赖 store 和 WorkManager）
return new AppContainer(books, reader,
    new PreferencesSettingsRepository(context), coordinator, false,
    runtimeStore, translationConfig);
```

将来确需改签名时，先修改 PROJECT_SPEC 的版本号和本文件，再同时修改 contract、模拟实现、正式实现和调用方；不要各自再定义同名 Book 或 Paragraph。


## 2026-11-13：首页独立互译补充

首页临时查询复用 TranslationService，允许 `en → zh-CN` 和 `zh-CN → en`；共享校验器同步接受这两个方向。LibreTranslate 供应商边界将 `zh-CN` 映射为 `zh`。整章和阅读选词仍为 `en → zh-CN`，首页请求使用数据库外的 `lookup:<UUID>`，不写书籍缓存。完整约定和本轮共享代码变更见 [C_HOME_LOOKUP.md](C_HOME_LOOKUP.md)。
