# EPUB 双语阅读器项目说明与协作规范

版本：v1.0  
日期：2026-11-01  
适用人员：三人开发团队及辅助开发的 Codex  
项目暂定名：BilingualReader  
Android 包名：`com.example.bilingualreader`

> 本文是第一版的共同开发约定。字段名、接口、状态和职责以本文为准。实现前先建立共享契约，再并行开发；不必等待所有界面完成。本文中的技术选型为项目设计决定，不代表已经完成代码或验证第三方服务。

## 1. 目标和范围

完成一个 Java 编写的 Android APK：导入 EPUB，展示书架和目录，阅读原文，按章节翻译，并按“原文段落＋中文译文”显示，保存译文和阅读位置。

### 1.1 第一版必须完成

- 导入无 DRM 保护、以文字为主的 EPUB 2/3 文件。
- 读取书名、作者、可用封面、阅读顺序和目录。
- 显示书架、目录和连续滚动正文。
- 英语（`en`）翻译为简体中文（`zh-CN`）。
- 用户主动点击翻译当前章节；展示进度，支持取消和失败重试。
- 原文和译文按稳定段落 ID 对应，译文写入本地数据库。
- 保存阅读位置，重启后继续阅读。
- 调整字号、行距和日间/夜间模式。
- 删除书籍及其关联数据。

### 1.2 暂不实现

账号、同步、视频字幕、PDF、OCR、离线翻译、双语 EPUB 导出、注释回跳、复杂图文排版和整本书自动翻译。第一版不承诺还原原书 CSS、表格、竖排或交互内容。

## 2. 技术方案

| 项目 | 统一约定 |
|---|---|
| 开发工具及语言 | Android Studio、Java；应用代码不引入 Kotlin |
| 界面 | XML 布局、单 Activity＋Fragment、RecyclerView |
| 结构 | MVVM：Fragment → ViewModel → Repository/Coordinator → 数据与服务 |
| 本地存储 | Room，Java 使用 annotationProcessor；设置使用 SharedPreferences |
| 异步与观察 | ExecutorService 执行短任务；LiveData 向界面提供状态 |
| 翻译长任务 | WorkManager 的 Java Worker；数据库保存任务，Worker 只传 taskId |
| 网络 | Retrofit＋OkHttp＋Gson；真实服务通过可替换适配器接入 |
| EPUB 处理 | ZipFile＋XML 解析 OPF/目录，正文使用 HTML 解析器（如 jsoup） |
| 文件选择 | Android 系统文件选择器；通过 ContentResolver 读取 Uri |
| 最低系统 | 项目设计下限 minSdk 26 |
| 构建版本 | A 创建可编译工程后，固定 Gradle Wrapper、AGP、JDK、compileSdk、targetSdk 和依赖版本，三人使用同一配置 |

不在本文凭空指定插件或 SDK 的“最新版本”。初始化时以三人能成功同步和构建的同一套版本为准，写入仓库 README；后续升级统一进行。

翻译服务供应商尚未指定。先实现 FakeTranslationService 完成联调，C 再接入一个真实服务。使用团队付费密钥时增加服务端代理，APK 不包含供应商长期密钥。代理属于额外部署项，不能把模拟翻译当作真实翻译验收。

## 3. 页面与操作流程

| 页面 | 内容与操作 | 数据来源 |
|---|---|---|
| 书架 | 封面、书名、作者、导入、删除、继续阅读 | BookRepository |
| 目录 | 目录项、打开指定位置 | BookRepository |
| 阅读 | 原文、译文、上一章/下一章、目录、翻译按钮、进度及重试 | ReaderRepository、TranslationCoordinator |
| 阅读设置 | 字号、行距、日夜模式 | SettingsRepository |

流程：选择文件 → 复制到应用私有目录 → 校验并解析 → 事务写入 → 展示书架 → 打开章节 → 显示已有译文 → 用户发起翻译 → 持续保存结果 → 刷新双语段落。

只有正式导入成功的书籍出现在书架。复制和解析期间显示加载；失败清理临时文件及未提交数据。退出阅读页不会自动取消翻译；用户主动取消后保留已完成译文。

## 4. 三人分工与文件边界

| 成员 | 负责内容 | 主要目录 | 完成标准 |
|---|---|---|---|
| A：工程与界面 | 工程初始化、导航、四类页面、ViewModel、模拟页面数据、集成与发布 APK | ui/、viewmodel/、res/，初始 build 配置 | 可以导入、导航、显示真实原文/译文和任务状态 |
| B：EPUB 与本地数据 | 私有文件管理、EPUB 解析、Room、Repository、本地缓存、阅读记录、删除 | data/、epub/ | 真实 EPUB 返回正确顺序的章节和段落，重启后数据仍可用 |
| C：翻译与调度 | 翻译适配器、批次、任务调度、Worker、重试与取消；需要时实现代理 | translation/、worker/、可选 server/ | 段落 ID 正确对应，失败可恢复，已成功译文不重复请求 |

共享 `contract/` 由三人共同确认后提交，B 负责维护模型和 Repository 声明，C 维护翻译声明；变更必须先同步给另外两人。A 在首次提交建立依赖组装入口，B/C 在其中接线时协调修改。

数据库只由 B 的存储实现写入。C 通过 TranslationStore 接口保存任务和译文，不能另建一套缓存；A 不直接调用 DAO 或网络。A 负责合并和集成，不代表其独自承担全部测试。

## 5. 通用数据规则

- Java 字段统一 camelCase；数据库表/列可用 snake_case，并用 Room 注解显式映射。
- ID 一律 `String`；时间为 Unix 毫秒 `long`；顺序一律从 0 开始。
- 所有列表返回确定顺序；禁止依赖数据库默认顺序或网络返回顺序。
- 可空字段使用 `@Nullable` 标注；其余字段不可为 null，列表为空时返回空列表。
- 语言代码使用 `en`、`zh-CN`；状态枚举在数据库保存字符串名称，不保存 ordinal。
- 所有正文及译文采用 Unicode 字符串；段落原文是清洗后的纯文本，不是 HTML。
- 共享模型为普通 Java 类，提供构造方法、getter；Room Entity 和网络 DTO 与共享模型分开。

### 5.1 稳定 ID

1. `bookId`：导入 EPUB 原始文件字节的 SHA-256 小写十六进制。相同文件再次导入直接返回已有书籍，不重新解析或覆盖进度。
2. `chapterId`：SHA-256(`bookId + "|" + spineIndex + "|" + canonicalResourcePath`)。
3. `paragraphId`：SHA-256(`chapterId + "|" + paragraphIndex`)。
4. `sourceHash`：清洗后的原文 UTF-8 字节的 SHA-256。
5. 解析规则版本保存为 `parserVersion=1`。普通打开不重新解析。后续更改分段规则必须设计迁移或显式重导入，不得直接复用旧译文。

文件路径用 EPUB 内规范化的根目录相对路径，统一 `/` 分隔，去除 `.` 和可解析的 `..`，保留大小写。禁止路径逃出 EPUB 根目录。

### 5.2 Book

| 字段 | 类型 | 含义 |
|---|---|---|
| bookId | String | 主键，文件哈希 |
| title | String | 缺失时使用原文件名去扩展名 |
| author | String | 多作者以 `、` 连接；缺失用“未知作者” |
| language | String | 元数据语言；缺失用 `und`，不据此自动改变第一版翻译语种 |
| originalFileName | String | 导入时显示名称 |
| localFilePath | String | 应用私有 EPUB 路径 |
| coverPath | String? | 私有封面文件，缺失使用默认封面 |
| chapterCount | int | 正文单元数量 |
| parserVersion | int | 当前为 1 |
| importedAt | long | 导入时间 |
| lastReadAt | Long? | 未阅读为空 |

### 5.3 Chapter 与目录项

第一版 `Chapter` 代表一个 spine 中的线性阅读资源，不保证等于出版物语义上的一章。多个目录项可以指向同一资源的不同锚点，不能据目录重复导入正文。

| Chapter 字段 | 类型 | 含义 |
|---|---|---|
| chapterId | String | 主键 |
| bookId | String | 外键 |
| title | String | 首个匹配目录标题，否则正文标题，否则“第 N 节” |
| orderIndex | int | 按线性 spine 排序 |
| resourcePath | String | EPUB 内规范路径 |
| paragraphCount | int | 可展示的非空文本块数量 |

目录使用独立 `TocEntry`：`tocId:String`、`bookId:String`、`parentTocId:String?`、`orderIndex:int`（深度优先显示次序）、`title:String`、`chapterId:String`、`paragraphId:String?`。可在 UI 缩进展示层级；找不到锚点时跳到对应 Chapter 开头。无法映射到已导入资源的目录链接忽略，并记录导入警告。缺少可用目录时由 Chapter 列表生成目录。

### 5.4 Paragraph

| 字段 | 类型 | 含义 |
|---|---|---|
| paragraphId | String | 主键 |
| chapterId | String | 外键 |
| orderIndex | int | 章节内顺序 |
| blockType | BlockType | HEADING、TEXT；两者均可翻译 |
| originalText | String | 非空纯文本 |
| sourceHash | String | 防止译文与变更后的原文混用 |

### 5.5 Translation

复合主键：`(paragraphId, targetLanguage)`。第一版只保留当前译文，不实现历史版本。

| 字段 | 类型 | 含义 |
|---|---|---|
| paragraphId | String | 原文外键 |
| targetLanguage | String | 第一版 zh-CN |
| sourceLanguage | String | 第一版 en |
| sourceHash | String | 必须匹配当前 Paragraph |
| translatedText | String? | 成功时必须非空；未成功可为空 |
| status | TranslationStatus | PENDING、RUNNING、SUCCESS、FAILED |
| provider | String? | 如 fake 或实际供应商标识 |
| engineVersion | String? | 模型/配置版本标识 |
| errorCode | String? | 失败原因 |
| updatedAt | long | 更新时间 |

无 Translation 行代表“未翻译”；取消时把未完成 RUNNING 行恢复为 PENDING。缓存命中条件：同段落、目标语言、源语言、sourceHash 且状态 SUCCESS。更换服务不自动重翻译，显式重翻译留待后续版本。

### 5.6 ReadingProgress

`bookId:String` 为主键，另有 `chapterId:String`、`paragraphId:String`、`offsetPx:int`、`updatedAt:long`。`offsetPx` 指该 RecyclerView 条目顶部相对内容可视区顶部的像素偏移，可为负数。保存第一个可见段落；字号或屏幕宽度改变后仅恢复段落并使用 offset=0。无进度时打开第一章首段；不存在的旧位置回退到可用章节首段。

### 5.7 TranslationTask

| 字段 | 类型 | 含义 |
|---|---|---|
| taskId | String | UUID 主键 |
| chapterId | String | 任务章节 |
| sourceLanguage / targetLanguage | String | en / zh-CN |
| state | TaskState | 见下一节 |
| totalCount | int | 章节全部可翻译段落数量 |
| successCount | int | 当前匹配缓存的成功段落数，包含先前缓存 |
| failedCount | int | 本轮已经终止尝试的失败段落数 |
| createdAt / updatedAt | long | 时间 |
| errorCode / errorMessage | String? | 任务级错误及用户提示 |

`pendingCount = totalCount - successCount - failedCount`。进度用 successCount/totalCount；全部缓存命中直接完成，空章节直接完成且显示 100%。任务快照是数据库记录的观察结果，不是网络回调临时变量。

### 5.8 阅读组合模型与数据库约束

`ParagraphItem`：`paragraph:Paragraph`＋`translation:Translation?`；`ReaderSettings`：`fontSizeSp:int=18`、`lineSpacingMultiplier:float=1.5`、`theme:ReaderTheme=DAY`；主题枚举 DAY/NIGHT，字号范围 14–30sp，行距倍数范围 1.2–2.0。

Room 唯一索引：Chapter(bookId, orderIndex)、Paragraph(chapterId, orderIndex)；外键层级 Book → Chapter → Paragraph → Translation；目录和阅读记录关联 Book。任务关联 Chapter。删除时先取消该书 Worker，再阻止结果回写，最后事务删除关联记录并删除私有文件。所有外键列建索引。

## 6. 统一 Java 接口

以下为 `contract/` 中的设计签名。每个 public 类型独立文件，补充 import、注解及构造方法后使用。不是可以直接作为一个文件编译的代码。`List` 为 java.util.List，`LiveData` 为 AndroidX LiveData，`Uri` 为 android.net.Uri。

### 6.1 异步约定

- 名称为 `observe...` 的方法可从主线程调用，只返回 LiveData，不执行阻塞 I/O。
- 其他方法均为阻塞方法，只能从 ExecutorService 或 Worker 调用；失败抛 AppException，禁止返回 null 表示失败。
- Fragment 通过 ViewModel 执行命令，并观察结果。LiveData 随生命周期订阅；不得保留 Activity 引用。
- 列表观察首次查询完成前不当作空书架；加载、成功、失败由 ViewModel 的 UiState 表示。

```java
public interface BookRepository {
    ImportResult importBook(Uri sourceUri) throws AppException;
    LiveData<List<Book>> observeBooks();
    List<Chapter> getChapters(String bookId) throws AppException;
    List<TocEntry> getTableOfContents(String bookId) throws AppException;
    void deleteBook(String bookId) throws AppException;
}

public interface ReaderRepository {
    LiveData<List<ParagraphItem>> observeParagraphs(
        String chapterId, String targetLanguage);
    ReadingProgress getReadingProgress(String bookId) throws AppException;
    void saveReadingProgress(ReadingProgress progress) throws AppException;
}

public interface SettingsRepository {
    LiveData<ReaderSettings> observeSettings();
    void saveSettings(ReaderSettings settings) throws AppException;
}

public interface TranslationCoordinator {
    String startOrResumeChapter(String chapterId, String sourceLanguage,
        String targetLanguage) throws AppException;
    LiveData<TranslationTask> observeTask(String taskId);
    LiveData<TranslationTask> observeLatestTask(
        String chapterId, String targetLanguage);
    void cancelTask(String taskId) throws AppException;
}

public interface TranslationService {
    TranslationBatchResult translate(TranslationBatchRequest request)
        throws AppException;
}
```

`ReaderRepository.getReadingProgress` 的返回类型例外：声明为 `@Nullable`，无进度返回 null；书籍不存在抛 BOOK_NOT_FOUND。`observeLatestTask` 无任务时可发出 null，`observeTask` 的已知任务被删除后可发出 null。其余对象结果不可空。

`ImportResult` 包含 `book:Book`、`alreadyExists:boolean`、`warnings:List<String>`。书架排序为 lastReadAt 降序（未阅读按 0），再按 importedAt 降序、bookId 升序；章节/目录/段落按 orderIndex 升序。

### 6.2 B 提供给 C 的存储边界

```java
public interface TranslationStore {
    TranslationTask prepareTask(String chapterId, String sourceLanguage,
        String targetLanguage) throws AppException;
    List<Paragraph> getPendingParagraphs(String taskId) throws AppException;
    void markTaskRunning(String taskId) throws AppException;
    void markBatchRunning(String taskId, List<String> paragraphIds)
        throws AppException;
    void applyBatchResult(String taskId, TranslationBatchResult result)
        throws AppException;
    void markBatchFailed(String taskId, List<String> paragraphIds,
        String errorCode, String message) throws AppException;
    void finishTask(String taskId) throws AppException;
    void failTask(String taskId, String errorCode, String message)
        throws AppException;
    void cancelTask(String taskId) throws AppException;
    boolean isTaskCancelled(String taskId) throws AppException;
    LiveData<TranslationTask> observeTask(String taskId);
    LiveData<TranslationTask> observeLatestTask(
        String chapterId, String targetLanguage);
}
```

- prepareTask 在事务内检查章节、校验语种、统计有效缓存，并返回同章节/语种已有活跃任务或创建新任务。旧失败、取消任务恢复时创建新 taskId；成功缓存保留，其余设 PENDING。
- 活跃状态为 QUEUED/RUNNING；同章节、源语言、目标语言只能有一个活跃任务。第一版只允许 en→zh-CN，其他组合直接报错。
- getPendingParagraphs 按段落顺序返回未成功缓存的段落；进程恢复时同任务遗留 RUNNING 也需重取。
- 写入均检查 taskId 仍活跃、书籍仍存在、sourceHash 一致；失效或已取消任务的迟到结果不写入。
- applyBatchResult 事务更新译文和统计；markBatchFailed 只标记所列未成功段落；重复应用成功结果不得重复增加 successCount。
- finishTask 在全部处理后按统计设 COMPLETED/PARTIAL_FAILED/FAILED；failTask 用于无法继续的任务级错误，保留已成功数据。
- cancelTask 同时标记任务 CANCELLED 并恢复未完成 RUNNING 译文为 PENDING；Coordinator 随后取消对应 WorkManager 工作。
- B 负责上述事务语义及 LiveData，C 负责调用时机与网络。

## 7. 翻译请求与响应契约

### 7.1 请求模型

`TranslationBatchRequest`：`taskId:String`、`requestId:String`（批次 UUID）、`sourceLanguage:String`、`targetLanguage:String`、`items:List<TranslationInput>`。

`TranslationInput`：`paragraphId:String`、`sourceHash:String`、`text:String`。

```json
{
  "taskId": "task-example",
  "requestId": "batch-example",
  "sourceLanguage": "en",
  "targetLanguage": "zh-CN",
  "items": [
    {"paragraphId": "paragraph-example", "sourceHash": "example-hash", "text": "Where are you going?"}
  ]
}
```

示例 ID/哈希是说明占位值，真实实现按第 5 节生成。

### 7.2 响应模型

`TranslationBatchResult`：`taskId:String`、`requestId:String`、`items:List<TranslationOutput>`。

`TranslationOutput`：`paragraphId:String`、`sourceHash:String`、`status:TranslationStatus`（仅 SUCCESS/FAILED）、`translatedText:String?`、`provider:String`、`engineVersion:String?`、`errorCode:String?`。

服务适配器必须验证：响应请求标识一致；段落 ID 属于本批次且无重复；sourceHash 一致；成功文本非空。少返回的段落标记失败，不按位置补齐；多余、重复或错配 ID 作为无效响应处理，不能落库。供应商不支持 ID 回传时，由适配器逐条调用并绑定输入 ID，不能假设任意模型能遵守 JSON 要求。

若有代理，可采用 `POST /v1/translations/batch`，请求响应使用上述结构；这只是本项目代理接口，不能直接当成供应商接口。所有真实 API 字段、限制、计费和认证由 C 依据所选供应商文档验证后实现。

### 7.3 批次和长段落

默认每批最多 8 段、原文总计 4000 个 Unicode 码点；两者都是可调项目初值，实际取供应商更严格限制。第一版串行请求，避免重复扣费和限流。

超过单批限制的段落由 C 按句子边界分块，必要时按 Unicode 码点边界切分。内部使用 parentParagraphId＋chunkIndex 关联，全部子块成功后按次序合并为一个段落译文，再按原 paragraphId 写入；任一子块失败，整个段落失败。不得因此修改 B 的 Paragraph ID。第一版子块缓存仅在任务执行内保留，重试长段落可能重新请求其已成功子块。

原文可能发送给在线翻译服务；首次使用真实服务时明确告知用户并由其主动发起翻译。日志不记录密钥或整段书籍内容。

## 8. 任务状态、恢复与错误

### 8.1 任务状态

| TaskState | 含义 | 后续 |
|---|---|---|
| QUEUED | 已建任务，等待 Worker | RUNNING、CANCELLED、FAILED |
| RUNNING | 正在请求和保存 | COMPLETED、PARTIAL_FAILED、FAILED、CANCELLED |
| COMPLETED | 全部成功或缓存齐全 | 再次点击直接使用缓存 |
| PARTIAL_FAILED | 有成功且有未完成/失败 | 用户点击重试创建新任务 |
| FAILED | 无成功，或任务级错误无法继续 | 用户点击重试创建新任务 |
| CANCELLED | 用户主动取消 | 用户恢复创建新任务，保留缓存 |

WorkManager 使用唯一工作名 `translate:<chapterId>:<sourceLanguage>:<targetLanguage>`，KEEP 策略避免重复调度；prepareTask 和入队由 Coordinator 串行协调。若崩溃发生在建任务后、入队前，应用启动检查 QUEUED/RUNNING 任务并重新确保对应唯一工作存在。Worker 重新运行时只处理尚未成功的段落。

第一版不保证服务器“恰好一次”执行：如果服务成功后本地未保存就崩溃，恢复可能重复付费。代理若支持幂等，重试同批次复用 requestId。不能宣称靠本地缓存能完全避免重复计费。

### 8.2 重试规则

- 网络异常、超时、限流及可重试服务器错误：最多重试 2 次，共最多 3 次尝试，使用退避；限流优先尊重服务端等待提示。
- 无网络：等待 WorkManager 网络约束满足；不要把整章立即判失败。
- 鉴权错误、额度不足、无效请求：停止自动重试并展示原因。
- 局部失败继续处理其余段落；最终保留已完成结果。
- Worker 重调度也纳入持久化尝试上限：C 使用 WorkManager runAttemptCount 或额外持久化计数，不能每次重启无限重试。
- 用户主动重试是新一轮任务；只请求非 SUCCESS 或 sourceHash 不匹配的段落。

### 8.3 AppException

统一包含 `code:ErrorCode`、`userMessage:String`、`retryable:boolean`，可附 cause 供调试。界面显示 userMessage，不直接暴露堆栈。

| 错误码 | 用途 |
|---|---|
| IMPORT_PERMISSION_DENIED / IMPORT_IO_ERROR | Uri 读取、复制或空间问题 |
| INVALID_EPUB / EPUB_PARSE_ERROR | 文件结构损坏或解析失败 |
| DRM_UNSUPPORTED | 已确认内容加密且无法读取；不能把所有 encryption.xml 都当 DRM |
| EPUB_TOO_LARGE / UNSUPPORTED_CONTENT | 超出限制或无可用正文 |
| BOOK_NOT_FOUND / CHAPTER_NOT_FOUND | 数据不存在 |
| UNSUPPORTED_LANGUAGE | 不支持的语种组合 |
| NETWORK_ERROR / TIMEOUT / RATE_LIMITED | 可恢复请求问题 |
| AUTH_FAILED / QUOTA_EXCEEDED | 配置或额度问题 |
| INVALID_RESPONSE / TRANSLATION_FAILED | 返回错配或翻译失败 |
| DATABASE_ERROR / UNKNOWN | 本地或未分类异常 |

## 9. EPUB 解析与文件规则（B）

1. 使用 Uri 流复制 EPUB 到私有临时目录，计算哈希，不把 `content://` 当成普通文件路径。导入成功后不依赖外部 Uri 权限。
2. 校验 ZIP 结构，读取 `META-INF/container.xml`，定位 OPF；XML 解析禁用外部实体和网络资源。
3. 从 OPF 提取元信息、manifest、spine；按线性 spine 顺序构建 Chapter，不能按 ZIP 文件名排序。第一版跳过 linear=no 资源，并在必要时提示范围限制。
4. EPUB 3 优先 nav 目录，EPUB 2 使用 NCX；链接和锚点相对各自资源解析，映射到 Chapter/Paragraph。目录不决定正文顺序。
5. 正文取标题、段落、列表项、引用等文本块；以不重叠的叶级块为主，容器不再次提取子块全文。保留行内文本顺序，br 转为块内换行，解码实体，清理首尾和多余水平空白，不把相邻段落合并。无语义块时对可见正文做降级分段。
6. 忽略脚本、样式和导航重复文本，不执行 EPUB JavaScript，不加载外链。图片不作为翻译段落；纯图片资源可提示无文本，不承诺 OCR。
7. 建立 HTML id/锚点到最近可用段落的映射。标题也是独立段落并可翻译。空块不入库；无文本资源跳过并记录警告；整本无正文则导入失败。
8. 解析完成后一次事务提交书籍、正文和目录，正式移动文件；文件移动或数据库失败要回滚/补偿清理，启动时清理孤立临时目录。

第一版项目限制：输入 EPUB 最大 50 MiB、解压累计上限 200 MiB、单资源最大 10 MiB、条目最多 10000 个。这些是可调整的应用限制。读取时累计检查实际解压字节，不能仅相信 ZIP 声明大小；封面保存和路径访问同样受限。

## 10. 阅读与持久化约定（A/B）

- RecyclerView 每条显示一个原文块和对应译文；原文立即可读。
- 未翻译显示“未翻译”，运行显示“翻译中”，失败显示“翻译失败”；页面提供统一重试失败段落入口，调用 startOrResumeChapter。
- 切换章节不清空已保存译文，观察新章节数据和该章节最新任务。
- ViewModel 节流保存位置（约 1 秒），页面离开时再提交一次；先保存内存快照，异步写数据库。
- 旋转屏幕和返回页面不重复发起翻译。只有用户操作或未结束任务恢复才调度。
- 目录跳转若有 paragraphId 则定位该段；正常继续阅读按 ReadingProgress 定位。
- 阅读正文离线可用；仅首次翻译或重试需要网络。

## 11. 推荐工程目录

| 路径 | 职责 |
|---|---|
| app/src/main/java/com/example/bilingualreader/contract/model/ | 共享模型、状态、错误 |
| .../contract/repository/ | Book/Reader/SettingsRepository、TranslationStore |
| .../contract/translation/ | Coordinator、Service、请求响应模型 |
| .../ui/、.../viewmodel/ | Fragment、Adapter、ViewModel、UiState |
| .../data/db/、.../data/repository/ | Room Entity、DAO、Repository 实现 |
| .../epub/ | 文件管理和 EPUB 解析 |
| .../translation/、.../worker/ | 翻译适配器、Coordinator、Worker |
| .../di/ | AppContainer；第一版手动依赖注入 |
| app/src/debug/ | 模拟数据和 FakeTranslationService 配置 |
| docs/PROJECT_SPEC.md | 本文作为仓库共享契约 |
| docs/ENVIRONMENT.md | 实际构建版本和启动步骤 |
| docs/TRANSLATION_PROVIDER.md | C 填写真实服务配置、限制和代理启动步骤 |

上表 `.../` 均表示同一 Java 包根目录。保持单 app 模块，先按包分工，避免课程项目初期引入过多模块。

## 12. 开发步骤与里程碑

### M0：共同骨架

A 创建 Java＋XML 工程、固定版本、建立仓库和 AppContainer。B 提交共享模型与存储接口；C 确认翻译接口。三人拉取后各自能够构建 Debug APK。A 用假 Repository；C 用 fake 服务，fake 结果显式标记“模拟译文”。

### M1：验证核心能力（同时进行）

A 做书架→目录→阅读的可点击页面；B 使用同一测试 EPUB 验证 spine 顺序和分段；C 翻译几段固定文本并校验 ID，同时尽早选定真实服务。每天合并一次可编译的小改动，不能等全部做完才联调。

### M2：完整原文阅读

A 接入 B 的真实 Repository；完成导入、书架、目录、正文和阅读记录。退出/重启后仍能打开本地书籍。此阶段不依赖真实翻译服务。

### M3：完整双语阅读

C 接入任务和 B 的 TranslationStore；A 展示任务和译文。先用 fake 验证状态，再切换真实适配器。完成缓存、失败重试、取消和恢复。

### M4：验证与打包

测试不同 EPUB、长章节、网络错误、杀进程与恢复；完善设置及提示。产出可安装 APK、源码、环境说明和真实服务配置说明。未配置真实服务时明确说明只能演示模拟流程。

## 13. 共享测试材料与验收

团队统一使用合法取得、无 DRM、允许用于测试的 EPUB；B 准备一份精简联调样本（至少两章，含标题、多个段落、一个目录锚点），三人共同使用。不要提交付费书籍或不允许再分发的正文。

| 测试场景 | 通过条件 | 主责 |
|---|---|---|
| 正常导入、重复导入 | 元信息正确；相同文件只保留一份，进度不丢 | B |
| spine 顺序和目录锚点 | 正文不按文件名错序；目录定位正确，无重复正文 | B |
| 缺封面、缺目录、无文本、损坏文件 | 有回退或清晰失败；失败不留半本书 | B |
| 双语对应 | 每段译文绑定正确 ID，不遗漏、不串段 | C/A |
| 网络返回乱序、少项、重复 ID | 正确匹配或拒绝异常响应，禁止错位落库 | C |
| 重复点击及已有缓存 | 同章节无重复活跃任务；成功段落不重复请求 | C/B |
| 长段落与部分失败 | 内部分块重组正确；其余段落保留成功结果 | C |
| 断网、取消、进程重启 | 已完成数据保留，未完成可恢复，无无限重试 | 三人 |
| 阅读恢复和设置 | 重启恢复同一段落；字号/模式生效 | A/B |
| 翻译中删除书籍 | 任务停止，迟到响应不能重建已删数据 | B/C |
| 真机安装 | Debug APK 能安装并完成导入到双语阅读 | A/三人 |

优先为 ID 生成、顺序解析、响应 ID 校验和幂等写入编写小型自动测试。界面样式以手动验收为主，不要求为每个按钮写形式化测试。

## 14. Git 与 Codex 协作规范

- 分支：`main` 保持可编译；A 使用 `feature/ui`，B 使用 `feature/epub-data`，C 使用 `feature/translation`，小步提交后合并。
- 契约变更先改本文及 contract，再同步实现。禁止各自新增同名但字段不一致的 Book/Paragraph。
- 不同时让不同 Codex 会话修改同一工作目录；使用各自本地克隆或独立 worktree。
- 不提交密钥、local.properties、本机构建目录和私有测试书籍。依赖版本不能由三个会话分别升级。
- 每次任务只覆盖一个可验收目标。向 Codex 提供本文、负责目录、当前接口和验收方式；要求先阅读已有代码再修改。
- 共享模拟数据复用真实模型与接口；切换实现通过 AppContainer，不能在 Fragment 中写死模拟原文。
- Codex 生成后由负责成员构建并检查核心结果；工具无法运行模拟器或真实服务时，明确列出尚未验证项。

### 给 A 的首个 Codex 任务

> 阅读 docs/PROJECT_SPEC.md。使用 Java 和 XML 创建/完善 Android 工程骨架、AppContainer、书架/目录/阅读/设置页面及 ViewModel。严格调用 contract 接口，先使用 fake Repository，支持两个章节和双语段落样例。不得自行变更数据字段或调用真实翻译网络。记录实际构建版本和构建步骤；完成可点击导航及 Debug 构建。

### 给 B 的首个 Codex 任务

> 阅读 docs/PROJECT_SPEC.md 和现有 contract。先补齐已确认的共享模型与接口，再实现 EPUB 导入、稳定 ID、spine 正文顺序、目录映射、段落提取及 Room 持久化。实现 BookRepository、ReaderRepository、SettingsRepository 和 TranslationStore。保持 UI 和翻译服务独立，用统一测试 EPUB 验证导入、重复导入、重启读取和错误清理。契约有矛盾时先指出，不另建替代模型。

### 给 C 的首个 Codex 任务

> 阅读 docs/PROJECT_SPEC.md 和现有 contract。先实现 FakeTranslationService、TranslationCoordinator 和 Java Worker，通过 TranslationStore 保存状态，完成按 ID 匹配、批次、缓存跳过、重试、取消和恢复。再以可替换适配器接入选定真实服务；付费供应商密钥由代理保存。不得在 APK 中写入长期密钥，也不得自己另建译文数据库。补充异常响应及断网测试，说明真实服务尚需的配置。

## 15. 开工检查清单

- [ ] 三人同意本文字段、接口和第一版范围。
- [ ] A 建好可构建工程，三人统一 JDK/SDK/Gradle 环境。
- [ ] 共享仓库及三个开发分支可用。
- [ ] 同一份合法测试 EPUB 已准备。
- [ ] contract 与模拟实现能够编译，A/B/C 可分别开工。
- [ ] C 选定真实翻译服务并记录费用、限制、密钥方案。
- [ ] 第一轮联调目标固定为“一本书、一个章节、真实原文和对应译文”。
