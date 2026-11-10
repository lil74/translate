# A 四阶段交付说明

日期：2026-11-08。范围按团队分工表中的 A 列执行。

| 阶段 | 实现与接入 |
|---|---|
| 1：核心页面 | 单 Activity + Fragment，书架 → 目录 → 阅读，保留设置页；ViewModel 通过共享接口读取数据 |
| 2：真实阅读 | Debug/Release 均接入 B 的 LocalLibraryRepository；真实 EPUB 导入、去重、书架/目录/段落展示、保存/恢复阅读位置与删除入口 |
| 3：双语展示 | 按 paragraphId、sourceHash、语种匹配译文；任务进度、失败数量、错误原因、取消、继续与重试入口；章节任务状态隔离 |
| 4：阅读体验 | 字号、行距、日夜模式、加载/空内容/失败提示、导入处理中提示、章节边界与段落位置恢复 |

## 简洁目录

目录使用专用 `ui/toc/TocAdapter.java` 和 `res/layout/item_toc.xml`，每项只有标题、层级缩进和细分隔线。没有每章卡片、封面或重复的“打开章节”副标题。点击仍保留目录锚点跳转。书架继续使用原来的卡片布局。

## 已修复的组装错误

原 Debug Factory 把真实书库与旧 DemoRepository 混接，翻译模块只认识 demo-* ID，不能处理真实 EPUB。

现在 Debug 使用 B 的 LocalLibraryRepository 作为 BookRepository、ReaderRepository、TranslationStore；`debug/StoreDemoCoordinator` 调用 C 已提供的 FakeTranslationService，并通过 TranslationStore 写入段落译文和任务。因此新导入的任意书籍也能验证 A 的双语、进度、失败、取消与重试界面。模拟输出明确标注“模拟译文，非真实翻译”，不发送原文到网络。

Debug 工具栏提供“模拟翻译失败（联调）”：开启后，下一个尚未完成的段落模拟失败。可先查看已保存的译文，再点击重试，验证缓存保留和错误状态。此菜单与协调器不进入 Release。

Release 保留真实书库和已保存译文的读取，使用明确报未配置的 UnavailableTranslationCoordinator。C 的正式 Coordinator/Worker 和部署实例尚未提供，A 没有把模拟翻译冒充在线翻译，也没有实现一套平行数据库。正式服务接入后，非模拟翻译入口会提示原文发送到团队服务，由用户主动确认。

## B/C 的后续接入点

- 共享 contract 方法和字段没有变化；无需 B/C 重写模型。
- C 完成正式 TranslationCoordinator 与 Worker 后，在两个 AppGraphFactory 中替换协调器，设置 demo=false。页面继续观察同一接口。
- Debug 联调协调器不是生产调度器：不自动恢复杀进程时正在执行的任务，不实现长段落分块、联网约束或生产重试策略。这些保持 C 的职责；A 的取消、继续与重试入口已就绪。
- B 的数据库、解析器和存储实现保留原有内容。A 不直接调用 DAO；所有译文写入均经过 TranslationStore。
- 原契约尚未提供 Room 异步观察查询的错误流；观察流异常通道仍需 B 协调设计。本次没有擅自更改返回类型。

## 设备集成检查

新增 `app/src/androidTest/.../AIntegrationRunner.java`，使用 Android 框架 Instrumentation，不额外引入测试依赖。运行在专用 Debug 模拟器，直接用测试 APK 内样本验证真实 Repository 和 UI；不会发送数据到在线翻译服务。测试不清除整个应用数据库，但会导入样本、生成样本模拟译文和调整阅读设置。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r com.example.bilingualreader.debug.test/com.example.bilingualreader.AIntegrationRunner
adb -s emulator-5580 shell am force-stop com.example.bilingualreader.debug
adb -s emulator-5580 shell am instrument -w -r -e resume true com.example.bilingualreader.debug.test/com.example.bilingualreader.AIntegrationRunner
```

成功输出含 `ALL PASSED`；失败包含堆栈，不能仅凭 adb 命令的退出码判断通过。`resume=true` 单独检查杀进程后的书库、A 保存的章节位置和继续阅读入口。

系统文件选择器导入入口仍需按手动验收表选择实际文件；设备集成测试通过 file Uri 调用同一导入接口，不声称自动操作了系统文件选择器。

早期 `tools/verify_phase1.py` 面向已停用的内置演示书，当前请使用上述真实书库测试。旧阶段记录和截图保留用于对照，不代表当前目录样式。

## 本轮验证结果

2026-11-08：Debug APK、测试 APK、Release APK 构建成功，Lint 0 错误、47 警告，未设置忽略基线。API 37 临时模拟器上的第一轮 8 项检查和杀进程后第二轮 3 项检查均通过。

- [真实书库/UI/失败重试/取消/设置：8 项通过](a-ui-results.txt)
- [杀进程后书库、进度与继续阅读：3 项通过](a-resume-results.txt)
- [简洁目录实际截图](screenshots/a-complete/02-simple-toc.png)
- [失败与重试入口实际截图](screenshots/a-complete/04-retry.png)
- [模拟双语实际截图](screenshots/a-complete/05-bilingual.png)

构建使用已有 Gradle 8.10.2 / JDK 21，并补齐缺少的 Lint 依赖；没有把个人代理写入工程。临时模拟器已关闭。没有在物理手机、多种 Android 版本、真实在线实例或大型书库上做覆盖验收。

Debug APK 在 `app/build/outputs/apk/debug/app-debug.apk`；Release 为未签名 APK，并保留在线服务未配置提示。当前改动尚未提交或推送，便于团队先审阅。
