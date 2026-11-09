# 项目盘点与 C 第一阶段交付

> 本文保留第一阶段历史状态；当前 C 的四阶段实现、接线和验证以 [C_IMPLEMENTATION.md](C_IMPLEMENTATION.md) 为准。

## 当前项目有什么

实际 Android Studio 工程在本说明上一级目录 `translate/`（包含 settings.gradle），外层放用户提供的说明和 EPUB。工程使用 Java + XML，单 app 模块；包名 com.example.bilingualreader，minSdk 26，compileSdk/targetSdk 35，Gradle 8.10.2、AGP 8.7.3，Java 源码目标 17，约定用 JDK 21 构建。

| 部分 | 当前内容 | 完成程度 |
|---|---|---|
| ui / res | 书架、目录、阅读、设置，单 Activity + Fragment，RecyclerView | 已有页面骨架与导航 |
| viewmodel | 四类 ViewModel、UiState、后台命令与 LiveData 观察入口 | 已有 |
| contract/model | Book、Chapter、Paragraph、Translation、Task、进度、设置、错误等 | 已有共享普通 Java 模型 |
| contract/repository | Book/Reader/SettingsRepository、TranslationStore | 接口已定义；真实书籍和翻译存储未实现 |
| contract/translation | Service、Coordinator、请求与响应模型 | 接口已定义，本次保持不变 |
| debug/DemoRepository | 原创两章故事、模拟译文、会话内进度和任务 | 界面演示，非真实导入或在线翻译 |
| PreferencesSettingsRepository | SharedPreferences 保存字号、行距、主题 | 已实现 |
| di | AppContainer、按 Debug/Release 分开的 AppGraphFactory | Debug 使用 DemoRepository；Release 待接入 |
| epub / data/db / worker | 分工说明 README | 生产实现尚为空 |
| 构建依赖 | Room、WorkManager、Retrofit、OkHttp、Gson、jsoup 等 | 声明依赖不等于已实现功能 |
| docs / samples | 规范、接口、环境、验收、原创两章 EPUB | 已有 |

截图 1 是整体设想；当前书面 v1 规范明确收敛为 EPUB 双语阅读器。TXT、视频字幕、独立查词、收藏和轻复习尚不在本版工程范围内。不能把截图中的全部功能当成已经实现的页面。

## C 第一阶段的范围与交付

按分工图的“验证几段文字能否翻译并正确对应”，本次添加：

1. `translation/TranslationValidation.java`：检查语言、批次 ID、重复输入、UTF-8 SHA-256、8 段 / 4000 Unicode 码点；按 paragraphId 重排响应，检查 sourceHash；少项生成 FAILED，重复/多余/错配/空成功译文拒绝整批。
2. `translation/LibreTranslateService.java`：实现既有 TranslationService，调用团队自建实例；逐段绑定 ID，映射 en→zh，明确超时与 HTTP 错误。不需要变更共享接口。
3. `src/debug/.../translation/FakeTranslationService.java`：明确标注模拟的联调服务，仅 Debug 存在，不能作为真实翻译验收。
4. `tools/verify_translation.py` + `tools/java/TranslationM1Check.java`：从本地 EPUB 提取固定少量段落，以现有共享 Java 模型实际编译和运行校验，同时测试本地 HTTP 传输。
5. `docs/TRANSLATION_PROVIDER.md`：选型、官方文档、配置与验证边界。

没有改 A 的界面和依赖组装，没有接管 B 的导入和数据库。Coordinator、Worker、任务缓存、重试与取消、长段拆分属于后续阶段，尚未实现。超过本阶段批次限制时直接拒绝，不能把此服务单独当作完整章节调度器。

## 结合指定 EPUB 的结果

本地文件元信息：The Deal / Elle Kennedy / en。文件 SHA-256：

`61100b1a2182bbaa4d604b6fb8f4d6de4cc8b50edc5a44301998c7061811281c`

第一章资源 `OEBPS/part0005.xhtml`，线性 spineIndex=5（之前有书名页、前置内容等，不能把语义“第 1 章”误认为 spineIndex=0）。探针按叶级标题和段落提取出 124 个非空文本块，选择 orderIndex=2、3、4 三个连续正文段落。

章节 ID：`549c154caa942b19447f4ef66d30684e88ec771bfb15260eb5ebeb9129b1dc15`。段落 ID 按 SHA-256(chapterId + "|" + orderIndex) 生成，sourceHash 对清洗后的原文 UTF-8 计算。

注意：这是仅用于本 EPUB 验证的 Python 探针，不是 Android EPUB 解析器，也不代替 B 的 parserVersion=1 实现。探针的段落计数和 ID 应在 B 完成后逐项比对，不能提前写入正式数据库。

工具生成 `work/translation-m1/epub-sample.json`、`report.json` 和 `report.md`；Markdown 包含三个原文段落、中文参考译文和完整段落 ID，可直接检查对应关系。中文参考由人工给定，不宣称来自服务供应商；HTTP 协议桩同样只返回这些预置文本。

实际执行结果：**33 项检查通过**，包括乱序、漏项、重复 ID、未知 ID、taskId/requestId 错配、哈希错误、空译文、非法语种、批次超限、补充平面 Unicode 字符计数、Fake 标记、本机 HTTP 正常往返、401/429/503、无效响应和连接失败。

**真实翻译引擎尚未配置，因此第一阶段的“在线翻译能力与质量”仍待实际服务验证；目前已完成代码与段落对应关系验证，不能把 33 项通过写成真实服务验收通过。**

## 复现命令

在包含 settings.gradle 的工程目录执行，需要 Python 3.10+、JDK 17+（java 与 javac 可用）和本地 Gson JAR。不依赖 Android SDK、Gradle 或 Python 三方包。

```powershell
python tools/verify_translation.py --epub '../The Deal (Off-Campus, Book_ (z-library.sk, 1lib..epub'
```

脚本默认从当前用户 Gradle 缓存查找 Gson（项目原本已声明 converter-gson 依赖）；其他电脑若未缓存，请先 Gradle Sync，或显式传本地 JAR：

```powershell
python tools/verify_translation.py --epub 'D:/你的本地目录/测试书.epub' --gson 'D:/依赖/gson-2.11.0.jar'
```

该抽样规则专门对应提供的 The Deal 版本；不同 EPUB 应调整抽样位置及参考，脚本遇到版本不符会停止，避免套用错误参考译文。仓库不包含用户的完整商业 EPUB 或提取正文；生成的 work/ 已被 .gitignore 排除。

真实自建服务可用后，显式执行以下命令会把这三个段落发送到给定实例：

```powershell
python tools/verify_translation.py --epub '../The Deal (Off-Campus, Book_ (z-library.sk, 1lib..epub' --endpoint 'https://你的团队服务/translate'
```

成功后 report.md 另列真实返回译文，provider=libretranslate；仍需人工复核语义，不要求与参考译文逐字相同。这里的 URL 是占位符，当前没有已部署的默认服务。

## 后续对接与验证限制

- C 第二阶段实现 Coordinator / Worker，通过 B 的 TranslationStore 保存；不要另建缓存。应用组装时再将实现注入 AppGraphFactory。
- B 交付真实 Paragraph 后，C 直接使用其 paragraphId/sourceHash/text 构造 TranslationInput，不能重新计算段落位置或改 ID。
- A 继续通过 Coordinator 观察任务，不从 Fragment 调用网络；接线前原有 DemoRepository 行为保持现状。
- 本次编译的是实际翻译 Java 源码及契约，运行的是 JVM 校验。Android 代码避免使用高于 minSdk 的 String.isBlank API。本机缺少工程锁定的 Gradle 8.10.2 与 Android Platform 35，本次未重新构建 APK、运行 Android Lint 或真机验证；原 ENVIRONMENT.md 中的成功记录是工程已有历史记录，不能当成本次结果。
