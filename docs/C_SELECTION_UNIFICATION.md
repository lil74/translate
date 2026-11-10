# 选词与章节翻译统一记录

日期：2026-11-09。依据用户提供的 `C_SELECTION_TRANSLATION.md`，复用现有接口和 A 的段内选择界面。

## 分支与冲突修复

开始时 C1 位于第一阶段提交 1d5944c，而本地 main 的 b6183bc 已包含 A 的选词界面、C 的完整调度和未清理的合并标记。已将本地 main 快进到当前 C1，再修复 AppContainer、两个 Factory、阅读页、菜单、构建配置和三份文档的冲突；没有覆盖其他未提交的用户源码，没有提交或推送远端。

## 统一后的边界

- 原五参数构造方法保留，临时翻译默认使用 UnconfiguredSelectionService，selectionDemo=false，不会沿用章节协调器。
- A 的七参数构造方法保留：books、reader、settings、chapterCoordinator、chapterDemo、selectionService、selectionDemo；两个模拟标志独立。
- C 原有七参数运行时构造入口也兼容；正式 Factory 使用九参数入口，一并注入 selectionService / selectionDemo、既有 TranslationRuntimeStore 和 TranslationConfigStore。
- AppContainer.SelectionBinding 是 DI 内部的不可变快照，不是新的共享请求模型。弹窗 ViewModel 同时保留服务与模拟标志，旋转后不会因设置变化自动发送或换目标。原 selectionTranslation / selectionDemo 字段仍可读取，配置更新时刷新，新调用方应优先读取同一份 selectionBinding() 快照。
- IsolatedSelectionTranslationService 只依赖现有 TranslationService 和校验器。每次临时查询创建自己的适配器，不查询段落数据库，不调用 Coordinator / Worker / Store，不写任务、译文缓存或阅读位置。
- 单次一项，保留所选文字的标点、首尾与内部空白，使用本次 selection UUID 和所选内容 SHA-256。上限 4000 个 Unicode 码点，不截断、不自动拆分。
- 临时查询仍使用 A 的独立执行器。真实服务即使章节处于模拟模式，也不会自动发送；需要用户点击“翻译所选内容”。失败原样保留 AppException.userMessage，关闭 / 重试继续使用 A 的 generation 隔离迟到结果。
- 每次请求使用独立网络适配器，避免共享 cancelled 标志。Future.cancel(true) 不保证立即中止底层 HTTP 或计费；连接 / 读取超时仍为 10 / 30 秒，不宣传为强制撤销服务端请求。

菜单中的在线地址与模式同时用于后续章节新任务和新打开的选词弹窗，服务实例彼此独立。Release 仅接受 HTTPS，不保存供应商长期密钥。没有新增 HTTP 接口、共享业务模型或数据库表，没有修改原整章翻译接口。

## 验证结果

- Debug Java 编译通过。
- Release Java 编译通过。
- Android 测试源码编译通过，保留 A 的集成脚本及 C 的 Room 测试。
- JVM 测试 23 项通过：原章节核心 13 项，加选词接入 10 项。新增覆盖文字与临时 ID 原样传递、逐次独立适配器、并发取消隔离、错 ID / hash、空译文与缺项、上限与单项约束、错误信息和新查询 ID、独立模拟标志、已打开弹窗配置快照、原五参数兼容。
- Lint：0 错误，54 警告；未禁用检查或引入 baseline。
- 已扫描并清除当前源码和文档中的合并冲突标记，diff --check 通过。

本轮按用户要求没有生成、安装 APK，没有运行新的设备触屏 / 旋转测试；A 原有 2026-11-08 截图和 12 项设备记录是历史验证，不作为本轮新增验证。没有可用团队服务地址，因此真实在线引擎质量与网络及时中断仍待实测。

复现（只编译与测试，不打包）：

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac :app:compileReleaseJavaWithJavac :app:compileDebugAndroidTestJavaWithJavac :app:testDebugUnitTest :app:lintDebug
```

默认 instrumentation runner 统一为 AndroidJUnitRunner，以运行现有 C 的 JUnit 集成测试。AIntegrationRunner 源码保留；其历史脚本依赖 A 的 StoreDemoCoordinator 故障注入夹具，不能把旧脚本当作当前 WorkManager 入口的无条件验收。需要构建该专用夹具时可用 `-PandroidTestRunner=com.example.bilingualreader.AIntegrationRunner` 选择 runner，但本轮没有进行该构建。

机器可读结果见 [selection-unification-results.json](selection-unification-results.json)，原始 JVM 报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。
