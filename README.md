# 轻阅 · BilingualReader

Java + XML 的 Android EPUB 双语阅读器。当前为 **A 第一阶段：核心页面与模拟流程**，按 A / B / C 分工组织。第一阶段交付与运行验收见 [验收说明](docs/ACCEPTANCE.md)。

## 现在可以做什么

- Debug 内置一篇原创两章故事，书架 → 目录 → 双语阅读可点击导航。
- 演示按段落追加模拟译文、任务进度、取消与继续；所有模拟译文显式标注。
- 上一章 / 下一章、目录定位、会话内阅读位置恢复。
- 字号 14–30sp、行距 1.2–2.0、日夜模式；设置使用 SharedPreferences 持久化。
- 系统 EPUB 文件选择与删除确认入口；选择文件后会提示真实导入尚未接入，不会生成假导入记录。
- 完整共享 Java 模型与接口，正式数据与翻译实现可在工厂中替换。

**尚未实现**：真实 EPUB 解析、Room 数据库、跨进程书籍/进度/译文保存、真实翻译、WorkManager 调度与崩溃恢复。Release 不包含演示故事，只展示待接入状态；现在不应作为完整产品验收。

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
