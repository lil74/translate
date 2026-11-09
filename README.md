# 轻阅 · BilingualReader

Java + XML 的 Android EPUB 双语阅读器。已合并 A 的页面、B 的真实 EPUB / Room 数据层，以及 C 的章节翻译任务流程。当前交付和验证见 [C 四阶段说明](docs/C_IMPLEMENTATION.md)；[A 第一阶段验收](docs/ACCEPTANCE.md)保留为历史记录。

## 现在可以做什么

- 导入 EPUB，按文件哈希去重；书架 → 目录 → 正文阅读，数据保存到 Room。
- 上一章 / 下一章、目录定位、保存阅读位置和继续阅读。
- 用户发起章节翻译，展示持久化进度；支持取消、重试未完成段落、成功缓存复用与启动恢复。
- WorkManager 等待网络，串行请求；每个网络输入最多 3 次持久化尝试，支持退避及 Retry-After。
- 批次最多 8 段 / 4000 Unicode 码点；长段落按句子或码点拆分，成功后合并到原段落 ID。
- 字号 14–30sp、行距 1.2–2.0、日夜模式；设置使用 SharedPreferences 持久化。
- 删除前取消该书翻译任务，迟到结果不会恢复已删除的数据。
- 菜单“翻译服务”可配置团队 LibreTranslate 地址；首次在线翻译需确认发送原文。

**服务配置**：Debug 默认使用明确标注的模拟译文，适用于流程验证；Release 仅支持配置后的在线服务。尚无团队真实服务地址，因此不能宣称在线翻译质量已验收。切换服务不会覆盖已有成功译文，验证真实翻译请使用未翻译章节。APK 不包含供应商长期密钥。

**仍待完善**：EPUB 2 NCX、真实封面等 B 的兼容性工作；图1中的 TXT、视频、查词、收藏、复习和学习统计尚不属于当前 EPUB 版本。

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
