# C 翻译模块四阶段交付

日期：2026-11-07。工作分支沿用用户当前的 C1；本次不自动提交或推送。

## 按任务分工对照

| 阶段 | 已实现 | 验证方式 |
|---|---|---|
| 一：几段文字翻译与对应 | LibreTranslate 适配器、Debug Fake、ID / sourceHash / 请求标识验证、异常响应处理 | 提供的 EPUB 三段参考对照与本机 HTTP 协议测试 33 项通过；在线引擎质量待配置服务 |
| 二：任务与状态接口 | WorkTranslationCoordinator、Java Worker、唯一工作、Room 持久化状态和进度 | 单元测试及真实 Android Room / WorkManager 测试 |
| 三：章节翻译与重试 | 原文段落构造请求、按 ID 写回、双语显示、缓存跳过、局部失败、用户重试和取消恢复 | 真机导入测试书 → Worker → 译文落库 → 再次点击命中缓存 |
| 四：长章节、网络和重复请求 | 8 段 / 4000 码点批次，长段内部分块重组，网络约束、退避、Retry-After、持久化尝试上限、并发去重、删除与迟到结果防护 | Unicode / 长段落单元测试，HTTP 错误测试，数据库重开、并发、取消、删除集成测试 |

“实现”指代码和测试覆盖的流程；没有团队在线服务地址，不能将模拟测试说成真实在线翻译质量通过。

## 使用流程

1. Android Studio 打开包含 settings.gradle 的工程目录，Gradle JDK 21、SDK 35，保持既有版本。
2. 导入 EPUB，打开目录与原文。Debug 默认处于“模拟验证”，所有模拟译文带有明显前缀。
3. 在右上角菜单“翻译服务”配置完整的 LibreTranslate `/translate` 地址；关闭模拟验证后保存。Release 不包含 Fake，不允许本地明文 HTTP。
4. 打开未翻译章节，点击“翻译本章”。首次对该地址使用在线服务会提示发送原文，确认后才开始。
5. 正文立即可读，翻译任务使用数据库快照更新进度。切章、返回或旋转不会重新发起翻译。
6. 主动取消保留成功译文；继续或重试创建新任务，只请求未成功段落。更换供应商不自动覆盖已有成功译文，符合原契约。

不需要供应商长期密钥的团队自建 LibreTranslate 可直接使用；需要长期付费密钥时必须由服务端代理保存，不能把密钥放进 URL 或应用设置。本次没有部署服务器或购买服务。

## 实现结构

- `WorkTranslationCoordinator`：后台线程串行协调 prepare 与 enqueue；唯一工作名 `translate:<chapterId>:<source>:<target>`，KEEP 策略。同一活跃任务复用 taskId；新任务开始时取消同名旧工作。
- `TranslationWorker`：输入仅 taskId，从数据库获取服务快照；在线任务带 CONNECTED 约束，默认 30 秒指数退避。按全局执行锁串行请求；停止时断开正在使用的连接。
- `ChapterTranslationRunner`：可在 JVM 测试的核心；响应先验证再写入。普通段落每批最多 8 段 / 4000 Unicode 码点；每轮约 7 分钟后让出 Worker，已落库的段落不重翻。
- `TranslationBatcher`：按句子边界切块；无法按句子切分时用 Unicode 码点边界。内部 ID 为 parentParagraphId + chunkIndex；全部块成功后才用原 parentParagraphId/sourceHash 写入一个段落译文。
- `TranslationRuntimeStore`：在原 TranslationStore 上增加执行快照、活跃任务查询和尝试日志。它仍由 B 的 `LocalLibraryRepository` 实现；C 不直接写 DAO，不建立第二个译文库。
- `TranslationBookRepository`：删除前持久化取消任务并停止相关 Worker，再调用 B 的正式删除逻辑。
- Debug / Release 两个 AppGraphFactory 均已接上真实书架、原文存储和正式 Coordinator，移除了“真实书籍章节交给旧模拟仓库”的错误接线。

## 恢复、尝试上限和数据一致性

Room 从版本 1 升至 2，使用显式 Migration，保留书籍、段落、阅读位置和既有译文；不使用 destructive migration。新增任务服务快照和 `translation_requests` 表，后者随任务、章节和书籍级联删除。

每个输入（或长段子块）按 taskId + 输入 ID + sourceHash 记录尝试次数，在请求之前持久化占用一次，最多三次。进程异常退出也不会把计数重置。等待 Retry-After 时不发请求、不增加计数。HTTP 429 / 503 的 Retry-After 支持秒数和 HTTP 日期。

网络异常、超时和限流会自动退避；鉴权、额度、无效请求及无效响应不会无限重试。返回中已知成功的条目会保存，失败条目单独处理；鉴权和额度问题终止整项任务。用户主动重试使用新 taskId，保留成功缓存。

建任务和服务快照在同一个事务中保存。应用启动重新确保 QUEUED / RUNNING 的唯一工作存在。旧版本遗留的任务没有服务快照时，不擅自把原文发送到当前地址，而是提示重新确认发起。

取消、删除和批次写入均在现有 Room 的事务边界检查任务状态；迟到响应被忽略，不能把 CANCELLED 改回完成或重建删除记录。成功数由当前有效缓存重新统计，重复应用结果不会累加计数。

长段子块译文仅在单次执行中保留；重试可能再次请求已成功的子块，每块仍受持久化尝试上限保护。极长段落若多次执行中断可能整段失败，用户可显式重试。不保证供应商恰好一次执行：响应成功但本地未保存就崩溃时仍可能重复计费；requestId 对同一批输入保持稳定，但 LibreTranslate 未承诺服务端幂等。

## 测试与复现

本次实际结果：

可提交的结果摘要见 [c-results.json](c-results.json)，原始本机报告位于以下忽略目录。

- JVM JUnit：13 个测试通过，涵盖乱序与缺项、成功缓存、三次上限、Retry-After、取消、长段重组、子块失败、部分暂时失败及认证 / 额度错误。
- 真机：9 个 Android 集成测试通过。使用临时数据库和独立测试书籍，验证真实 Room 升级、数据库重开、并发去重、取消与迟到结果、删除级联、空章和真实 WorkManager 流程；不清除手机原有书籍。
- 提供的 EPUB：33 项参考对照 / HTTP 协议检查通过；报告在 `work/translation-m1/report.md`，正文材料不入仓库。
- Android Lint：0 错误；遗留及新增样式、硬编码文字、依赖版本等警告保留在报告中，没有关闭检查或设置 baseline。
- Debug / Release 编译均通过。用户提出“先不用生成 APK”之前已经发起的最后一轮打包已结束；之后不再安排打包或安装。

不打包的检查命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
python tools/verify_translation.py --epub '../The Deal (Off-Campus, Book_ (z-library.sk, 1lib..epub'
```

13 项 JVM 测试报告在 `app/build/reports/tests/testDebugUnitTest/index.html`；真机原始输出与结果在 `work/c-android-results/`；Lint 在 `app/build/reports/lint-results-debug.html`。真机 9 项测试在最后一项“混合成功与额度失败响应”的 JVM 修正前通过，该修正已通过新增 JVM 测试，未再安排安装。

下次允许准备和安装测试包时可用：

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
python tools/run_android_checks.py --adb '你的SDK/platform-tools/adb.exe' --serial '设备序列号' --install
```

工具要求明确指定设备。若 Gradle 的 connectedDebugAndroidTest 报找不到设备，可用上面的 ADB 工具直接运行同一组 instrumentation 测试。测试不需要切换手机真实网络，不调用付费服务。

## 验证边界

真实在线引擎的可用性、译文质量、部署限额和计费尚未实测；需要团队提供合法可用的服务地址和英文→中文模型后，在未翻译章节验证。手机系统强制停止应用后，Android 会阻止后台工作，必须再次启动应用才能恢复。当前已测试数据库重开和持久化尝试，未宣称覆盖所有厂商的杀进程策略。

EPUB 2 NCX、封面及其他解析兼容性仍属于 B；TXT、视频字幕、查词、收藏、复习不属于这次 C 的四阶段范围。历史 A 界面测试脚本依赖旧演示故事，不能直接当作当前真实书架流程的验收。
