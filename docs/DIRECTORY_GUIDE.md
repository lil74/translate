# 工程目录与分工说明

## 根目录

| 路径 | 用途 | 维护者 |
|---|---|---|
| `app/` | 唯一 Android 应用模块 | 三人 |
| `build.gradle` | 固定 Android Gradle 插件版本 | A 统一维护 |
| `settings.gradle` | 模块列表与依赖仓库 | A |
| `gradle.properties` | 公共构建参数 | A |
| `gradle/wrapper/`、`gradlew*` | 固定 Gradle、让其他电脑可以直接构建 | A，需提交 JAR |
| `docs/` | 共同规范、接口、环境与协作说明 | 三人 |
| `samples/` | 原创、可再分发的两章 EPUB 联调样本 | B 后续扩充 |
| `local.properties` | 每个人自己的 Android SDK 路径 | 本地文件，不提交 |
| `work/` | 本次生成和验证的临时脚本、SDK 与构建缓存 | 不提交 |

## 主源码

以下路径以 `app/src/main/java/com/example/bilingualreader/` 为根。

| 包 / 文件 | 具体职责 | 维护者 / 状态 |
|---|---|---|
| `ReaderApplication.java` | 应用启动时创建 AppContainer | A，已实现 |
| `di/AppContainer.java` | 持有 Book、Reader、Settings、Translation 实现与后台线程池 | A，已实现 |
| `contract/model/` | Book、Chapter、段落、译文、任务、阅读位置、设置、枚举和异常 | 已建立，B 维护 |
| `contract/repository/` | BookRepository、ReaderRepository、SettingsRepository、TranslationStore | 已建立，B 维护 |
| `contract/translation/` | TranslationCoordinator、TranslationService、请求和响应模型 | 已建立，C 维护 |
| `ui/MainActivity.java` | 单 Activity 导航、顶部工具栏、系统栏适配、主题 | A |
| `ui/BaseFragment.java` | 取得依赖 / ViewModel，展示命令结果 | A |
| `ui/EntryAdapter.java` | 书架与目录复用的列表卡片 | A |
| `ui/bookshelf/` | 书架、系统文件选择、删除确认、导入提示 | A |
| `ui/toc/` | 目录列表、层级缩进、继续阅读、锚点入口 | A |
| `ui/reader/` | 连续滚动原文与译文、章节跳转、任务状态、阅读位置 | A |
| `ui/settings/` | 字号、行距、夜间模式、预览与保存 | A |
| `viewmodel/` | UiState、异步命令、页面状态、订阅和节流保存 | A |
| `data/db/` | Room Entity、DAO、Database、索引、迁移 | B，目录占位 |
| `data/repository/` | 正式 Repository、TranslationStore 实现 | B；A 已提供可复用设置实现 |
| `epub/` | Uri 复制、ZIP/XML/HTML 解析、稳定 ID、目录与资源路径 | B，目录占位 |
| `translation/` | 正式服务适配、Coordinator、分批、校验、重试 | C，目录占位 |
| `worker/` | Java Worker、唯一任务、网络约束与恢复 | C，目录占位 |

`app/src/main/res/layout/`：四个页面、Activity 和列表条目的 XML。
`res/values/` 与 `res/values-night/`：日夜配色、公共样式。
`res/drawable/`：默认书籍图标和圆角背景。
`AndroidManifest.xml`：应用、启动页和联网权限；文件选择使用系统选择器，不申请全盘存储权限。

## Debug 与 Release 为什么有不同目录

- `app/src/debug/java/.../di/AppGraphFactory.java`：把 UI 接到演示 Repository。
- `app/src/debug/java/.../debug/DemoRepository.java`：内存书籍、会话阅读进度、定时模拟翻译。不是生产数据库或调度器。
- `app/src/release/java/.../di/AppGraphFactory.java`：待接入的正式装配点，没有模拟故事。
- Android 构建只会选入当前变体的 Factory，两份相同类名不会互相冲突。不能把两份都移动到 main。
- 若最终 Debug / Release 都使用正式实现，可删除这两份 Factory，再在 main 中建立唯一正式 Factory；不能保留重复类。

## B 从哪里开始

1. 阅读 PROJECT_SPEC 与 contract，使用 `samples/two-chapters.epub` 验证两章、标题、段落和目录锚点。
2. 在 `data/db/` 建 Room；在 `epub/` 实现导入；在 `data/repository/` 实现共享 Repository 和 TranslationStore。
3. 设置可以沿用 `PreferencesSettingsRepository`，不必重写。
4. 与 A 协调改 Factory，先把 books / reader 换成真实实现；UI 不直接依赖你的 DAO。
5. 模拟 Translator 只认识 demo ID；真实数据接入后不要继续接 DemoRepository 做翻译。先禁用翻译或同时接入 C 的 Coordinator。

## C 从哪里开始

1. 实现 `TranslationService`（可先 Fake），按段落 ID 和 sourceHash 返回结果。
2. 实现 `TranslationCoordinator` 与 Java Worker，通过 B 的 TranslationStore 写任务和译文。
3. 生产 FakeTranslationService 与当前 Debug 的 UI 定时演示是两回事；现有 DemoRepository 不作为 Worker 骨架。
4. Worker 输入只用 taskId，任务状态通过 B 提供的 LiveData 驱动 UI。
5. 与 A 协调接入正式 Factory，补 `TRANSLATION_PROVIDER.md`。需要代理时再建立 `server/`，本次没有生成空后端工程。

## A 后续工作

先集成 B 的真实原文，再集成 C 的任务与译文。保持 Fragment → ViewModel → contract 的方向；不在页面手写业务数据、不直接请求供应商、不直接写数据库。
当前封面是默认图标；B 提供 coverPath 后在书架卡片接图片加载。真实导入耗时、空章节、网络异常与进程恢复在联调时验收。
