# 给 C：同段落选词 / 选句翻译对接说明

> 2026-11-09 C 接入更新：已按本文统一现有代码。保留原五参数和本文七参数构造方法，并用九参数入口同时接入 C 的任务存储与配置。选词服务通过独立适配器调用复用的 TranslationService；服务设置变化后对新弹窗生效，已打开弹窗保留原服务与模拟标志，确保确认后不会改变发送目标。见 [本轮统一记录](C_SELECTION_UNIFICATION.md)。原 A 的设备验证记录保留其原始日期，本轮没有生成或安装 APK。

本轮已实现 A 的“长按原文 → 调整选择范围 → 点击翻译 → 底部结果弹窗”。只支持同一个原文段落内选择。单词、短语和句子使用同一条查询路径，不提供词典音标、词性或语境消歧承诺。

## C 需要做什么

**复用已有 TranslationService，无需新增 HTTP 接口、共享模型、数据库表或修改整章翻译接口。** 当前两个 Factory 均已接入 `AppContainer.selectionTranslation`：Debug 默认使用已有 FakeTranslationService，配置在线模式后使用 LibreTranslateService；Release 只使用在线模式，未配置地址时给出明确提示。真实服务地址与质量仍待团队配置和验收。

新 AppContainer 七参数构造方法：

```java
new AppContainer(
    books, reader, settings,
    chapterCoordinator,
    chapterDemo,       // 原有整章翻译的模拟标志
    selectionService, // 新增，类型为已有 TranslationService
    selectionDemo     // 新增，真实服务必须 false；只对模拟服务设 true
);
```

原五参数构造方法仍可用，其临时查询服务默认报“尚未配置”，不会偷偷沿用章节协调器。

`selectionDemo` 独立于原 `demo`：即使整章任务仍是模拟，真实选词服务也必须设 `selectionDemo=false`，这样弹窗会显示发送原文的说明，用户点击确认后才发请求。切勿为了让整章显示模拟而把真实短文本服务标为模拟。

两个 AppGraphFactory 的包名均为 `com.example.bilingualreader.di`，分别在 src/debug 和 src/release 中；请协调修改。不要只换页面文案而没有替换实际服务。

## 请求与返回约定（已有类型）

每次查询将生成一个 TranslationBatchRequest：

- `sourceLanguage=en`、`targetLanguage=zh-CN`。
- `items` 只有 1 项，`text` 是用户实际选中的文字；保留其中的标点和空白。
- `taskId` 与 `paragraphId` 使用本次临时生成的 `selection:<UUID>`；`requestId` 为另一 UUID。
- `sourceHash` 是**所选文字本身**的 UTF-8 SHA-256，并非完整段落的 sourceHash。
- 当前只传所选文字，不附带整段上下文；不能把返回结果宣传为“这个词在本句中的准确释义”。后续若要词典/语境释义，另行共同设计。

C 返回同一个 taskId/requestId/paragraphId/sourceHash，provider 非空。成功时 status=SUCCESS、translatedText 非空；失败可抛 AppException（推荐，保留 userMessage），或返回符合原契约的 FAILED 条目。

这些临时 ID 在数据库中不存在。适配器必须按请求直接翻译文本，**不能要求根据 paragraphId 查询数据库**。C 现有 LibreTranslateService 逐项调用并绑定原 ID 的方式可复用。

## 数据与线程边界

- A 在独立后台执行器调用 TranslationService，不阻塞阅读页，也不占用存储命令线程池。
- 回包经过已有 TranslationValidation 验证；少项、错 ID、错 hash、空译文不会按成功展示。
- 临时查询不调用 TranslationCoordinator、TranslationStore、Worker 或 DAO；不写入整章译文缓存、任务或阅读位置。
- 页面旋转由弹窗自己的 ViewModel 保留当前结果/请求；不会因视图重建重复发起查询。
- 用户关闭弹窗或放弃请求会调用 Future.cancel(true)，并使旧请求的结果失效。阻塞网络调用是否立即断开取决于 C 的适配器；A 不承诺 Java 中断一定中止网络或计费。
- 用户主动重试会创建新查询标识，避免旧响应覆盖新结果。当前无额外自动重试。
- 若 C 的整章与临时查询会并发执行，请使用独立服务实例或保证适配器线程安全，避免取消操作相互干扰。
- 当前上限沿用 C 已有校验器：4000 个 Unicode 码点。超过上限提示缩小选择范围，不截断原文，也不自动分块。

## A 的代码落点

| 文件 | 作用 |
|---|---|
| ui/reader/ParagraphAdapter.java | 原生段内选择菜单，捕获实际选择范围 |
| ui/reader/SelectionTranslationSheet.java | 底部弹窗、所选原文、结果、加载、错误重试、真实服务发送确认 |
| viewmodel/SelectionTranslationViewModel.java | 临时请求组装、响应校验、取消、迟到结果隔离 |
| di/AppContainer.java | selectionTranslation 与 selectionDemo 两个独立接入字段 |
| translation/UnconfiguredSelectionService.java | 未配置真实服务时的明确提示 |

路径以 `app/src/main/java/com/example/bilingualreader/` 为根。XML 在 `res/layout/sheet_selection_translation.xml`，新文案在 `res/values/selection_strings.xml`。

## 联调步骤

1. Debug 启动并导入 EPUB，长按原文、拖动选择柄，点击“翻译”。应显示当前选中的单词或句子，模拟结果明确标注。
2. C 接入真实实例，设 selectionDemo=false。打开弹窗时不自动发送，点击“翻译所选内容”后才请求。
3. 验证正常、空译文、响应 ID/hash 错配、断网、超时、错误后重试、关闭后迟到响应、旋转屏幕；检查未增加章节译文和任务。
4. 同时运行章节翻译，确认临时查询结果没有混入段落缓存，临时请求的失败/取消不影响整章任务。

真实实例的地址、认证与质量由 C 配置和验收；APK 不保存供应商长期密钥。当前模拟输出只证明交互可用，不作为词义或译文质量验收。

## 本轮验证记录

2026-11-08：Debug、测试 APK、Release 构建成功，Lint 0 错误、49 警告，未禁用检查。API 37 临时模拟器设备检查共 12 项通过，其中包含实际触屏长按打开原生选择菜单、单词/整句结果对应、弹窗关闭、临时查询不写章节译文、错误重试和迟到响应隔离。原阅读、章节翻译、取消与设置检查也通过。

测试对同一段落中的两种选择使用了实际长按、指定范围、执行自定义菜单动作；错误与迟到响应通过受控服务验证。尚未验证真实在线翻译质量、网络调用是否及时中止和所有手机厂商的系统选词菜单。

仓库中的结果为 `docs/selection-results.txt`，截图在 `docs/screenshots/selection/`。临时模拟器已关闭。本轮增加了独立测试样本重置，设备测试会删除并重新导入同一测试 EPUB，不清除其他书籍；请在测试模拟器运行。
