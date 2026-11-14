# 轻阅 · BilingualReader

Java + XML 的 Android EPUB / TXT 双语阅读器。已合并 A 的页面、B 的真实 EPUB / Room 数据层，以及 C 的章节翻译任务流程。当前交付和验证见 [C 四阶段说明](docs/C_IMPLEMENTATION.md)；[A 第一阶段验收](docs/ACCEPTANCE.md)保留为历史记录。

## 现在可以做什么

- 首页顶部搜索框支持中英互译、自动或手动方向、复制和失败重试。见 [给 C 的首页互译对接说明](docs/C_HOME_LOOKUP.md)。

- 阅读默认隐藏操作栏，轻点正文中间显示设置；支持底部字号、行距、页边距、字重、日夜背景、窗口亮度、章节切换，以及本地收藏和段落书签。见 [阅读设置说明](docs/READER_CONTROLS.md)。
**C2 选词更新（2026-11-12）**：结果窗口按实际选区贴近单词显示，支持当前段落语境辨义和点击扬声器播放英语读音。云端语境接口已部署；代码、能力确认协议及模拟器验证见 [C2 选词说明](docs/C2_SELECTION_CONTEXT_2026-11-12.md)。

**云端运行更新（2026-11-10）**：已部署百炼 `qwen-mt-plus` HTTPS 网关，手机可联网独立翻译，不依赖电脑或 USB。支持加密保存独立网关令牌、记录实际模型，以及在顶部翻译菜单中确认后重新翻译旧译文；实际样本、验收与限制见 [云端运行记录](docs/QWEN_CLOUD_RUN_2026-11-10.md)。下文的本机 Docker 记录保留为先前阶段历史。

- 导入真实 EPUB / TXT，按文件哈希去重，显示书架、目录与连续滚动原文；数据保存到 Room。TXT 支持自定义目录规则，见 [TXT 导入](docs/TXT_IMPORT.md)。
- 支持 EPUB 2 NCX、EPUB 3 nav 目录、段落锚点与可用封面提取，见 [B 数据验收](docs/B_DATA_ACCEPTANCE.md)。
- 简洁文字目录，保留层级、目录锚点和继续阅读入口；每章不再使用卡片。
- 保存本地阅读位置，重启后继续阅读；上下章边界、加载/空内容/重试提示。
- 原文与译文按段落 ID、sourceHash 与语种匹配，显示任务进度、错误原因、失败数量、取消、继续与重试。
- WorkManager 等待网络、持久化进度与启动恢复；支持退避及 Retry-After，每个网络输入最多 3 次持久化尝试。
- 批次最多 8 段 / 4000 Unicode 码点；长段落拆分后合并回原段落 ID。
- 删除前取消该书翻译任务，迟到结果不会恢复已删除的数据。
- 菜单“翻译服务”可配置团队 LibreTranslate 地址；首次在线整章翻译需确认发送原文。
- 字号 14–30sp、行距 1.2–2.0、日夜模式，设置本地保存。
- 同段落内长按选词或选句 → 翻译 → 可拖动的小浮窗，原文不变暗；支持加载、错误重试和关闭，临时查询不写入章节译文。真实服务需主动确认发送，与整章任务独立。详见 [翻译浮窗](docs/SELECTION_FLOATING_UI.md)及 [C 选词统一说明](docs/C_SELECTION_UNIFICATION.md)。
- 阅读工具栏左侧显示实际章节/内容标题，前言和目录不计入正文章节编号；右侧翻译图标点开后显示操作与状态，未翻译段落隐藏译文框。详见 [精简阅读页](docs/READER_COMPACT_UI.md)。

**服务配置**：Debug 初始默认使用模拟译文；Release 仅支持配置后的在线服务。2026-11-10 已启动本机真实 LibreTranslate，通过 USB 在手机完成原创短篇 EPUB 的真实翻译、保存和重新读取，并将该手机切换为在线模式，见 [本次真实运行记录](docs/REAL_TRANSLATION_RUN_2026-11-10.md)。完整小说和长章节质量仍待验收。切换在线后再次翻译本章会替换模拟缓存、保留有效真实译文。部署及缓存规则见 [真实翻译接入说明](docs/REAL_TRANSLATION_SETUP.md)。APK 不包含供应商长期密钥。

**仍待完善**：复杂 EPUB 排版与长篇小说翻译质量需要持续验收；纯图片正文无 OCR。已提供段内选词／选句翻译，但不提供词典音标、词性或语境释义承诺；视频、复习和学习统计尚不属于当前版本。

## 启动

1. Android Studio 打开本目录（包含 `settings.gradle`），不是只打开 `app/`。
2. Gradle JDK 选 **21**，安装 SDK Platform 35 与 Build Tools 34.0.0，完成 Gradle Sync。
3. Build Variant 选 `debug`，连接安卓 8.0+ 手机或模拟器，点击 Run。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。首次同步需要下载构建工具和依赖。
本机路径只放 `local.properties`，已忽略；不要提交 SDK、密钥或缓存。

## 团队入口

| 说明 | 文件 |
|---|---|
| 每个文件夹做什么、成员接线步骤 | [目录与协作说明](docs/DIRECTORY_GUIDE.md) |
| 原始共同约定（原文复制，未修改） | [项目规范](docs/PROJECT_SPEC.md) |
| 已落地接口、包名与调用约定 | [接口对接说明](docs/INTERFACES.md) |
| 固定工具版本、构建步骤与验证记录 | [开发环境](docs/ENVIRONMENT.md) |
| 真实翻译服务待办 | [供应商接入模板](docs/TRANSLATION_PROVIDER.md) |
| 点击验收与后续集成检查 | [验收步骤](docs/ACCEPTANCE.md) |

准备上传 GitHub 时提交根目录工程文件、`app/src/`、`gradle/`、Wrapper 脚本与 `docs/`；`.gitignore` 会排除 `work/`、`build/`、`local.properties`、IDE 私有配置和常见密钥文件。`gradle-wrapper.jar` 必须提交。

建议分支：A `feature/ui`，B `feature/epub-data`，C `feature/translation`。先合并共享契约，再接真实数据，最后接真实翻译；不要同时改同一个 AppGraphFactory。
