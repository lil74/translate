# A 第一阶段交付与验收

> 本文及 verify_phase1.py 是依赖旧模拟故事的历史验收，保留供 A 参考。当前已接入真实书架和翻译调度，请以 [C_IMPLEMENTATION.md](C_IMPLEMENTATION.md) 的测试与限制为准。

第一阶段范围是“验证界面方案”：使用共享接口与模拟数据搭建书架、目录、阅读三个核心页面并跑通导航。当前同时保留已有阅读设置页，供界面联调使用；真实 EPUB、Room 和真实翻译分别在 B/C 的后续工作中接入。

## 已实现的页面与交互

| 页面 | 本阶段交付 |
|---|---|
| 书架 | 演示书籍、默认封面、空书架、导入文件选择入口、删除确认 |
| 目录 | 两章有序列表、层级显示支持、继续阅读、加载与查询失败重试 |
| 阅读 | 段落原文、模拟译文、上下章及边界禁用、返回目录、加载/无正文提示与章节加载失败重试入口 |
| 设置（辅助联调） | 字号、行距预览、日夜模式及 SharedPreferences 保存 |

UI 只使用 ViewModel 和 contract 接口。原共享接口与字段没有改变。新增的重试、加载状态与章节切换状态重置都位于 A 的 ViewModel / UI 内。

本轮重点修正：目录重试恢复加载状态；阅读页首次状态提示；未创建翻译任务的新章节不能沿用上一章节状态；字号变化时按段落恢复；避免 RecyclerView 自带状态恢复与页面手动恢复相互覆盖；日夜系统栏图标使用 WindowInsetsControllerCompat。

## 自动运行检查

脚本为 `tools/verify_phase1.py`，只依赖 Python 3 和 Android SDK 的 adb，必须显式指定设备序列号。应在专用测试模拟器中运行。

```powershell
# 先构建、安装 Debug APK 到自己选定的测试设备。
.\gradlew.bat :app:assembleDebug :app:lintDebug
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
python tools/verify_phase1.py --serial emulator-5580 --output work/phase1-results --reset
```

`--reset` 会清除所选设备上本应用的演示数据与设置；不希望清除时省略。脚本会切换设备横竖屏和关闭系统动画，因此请使用测试模拟器，不要直接在个人日常手机上运行。本次使用自建的临时模拟器，不使用已有虚拟设备数据。模拟器默认英文系统；脚本的工具栏菜单描述使用 `More options`。

输出：`work/phase1-results/result.json` 和六张实际运行截图。脚本失败会返回非零退出码、记录失败原因并保存 failure.png；不会通过替换模拟结果伪造验收。

2026-11-05：新版 Debug APK 构建、Lint 和全部 11 项运行检查通过。结果见 [实际检查记录](phase1-results.json)，环境与限制见 ENVIRONMENT。下表是从这次测试模拟器保存的截图，并非设计稿：

| 页面 | 实际运行截图 |
|---|---|
| 书架 | [日间书架](screenshots/phase1/01-bookshelf.png) |
| 目录 | [两章目录](screenshots/phase1/02-contents.png) |
| 阅读 | [原文阅读](screenshots/phase1/03-reader.png) |
| 双语联调 | [模拟译文与进度](screenshots/phase1/04-bilingual.png) |
| 设置 | [夜间设置](screenshots/phase1/05-settings-night.png) |
| 夜间阅读 | [夜间双语阅读](screenshots/phase1/06-reader-night.png) |

下面保留完整手动检查表，供接入 B/C 后回归使用。

## 当前 Debug 界面，需在手机 / 模拟器操作

1. 启动看到演示标记和 The Little Journey，点击书籍进入目录。
2. 目录有两章；继续阅读进入第一章；返回、选第二章、上一章 / 下一章、边界按钮工作。
3. 点击翻译本章，逐段出现【模拟译文】，进度完成为 4 / 4。
4. 翻译中取消，等待后不再新增译文；继续翻译保留原有结果。
5. 开始翻译后退出阅读页再进入，任务不被页面退出自动取消。
6. 滚动后进入设置，改变字号和行距并保存；返回阅读，恢复段落、设置生效。
7. 切换夜间模式，检查书架 / 目录 / 阅读 / 设置；重启后设置仍保存。
8. 系统文件选择可以取消；选择 EPUB 显示“真实导入待接入”，书架不新增假书。
9. 长按删除弹确认，删除后显示空书架；重启后演示书恢复（预期，仅内存）。
10. 旋转屏幕，检查章节与滚动恢复，不自动启动新翻译。

## B/C 接入后新增验收

真实导入、重复文件哈希去重、spine 顺序、目录锚点、缓存、删除级联、重启继续阅读、长段落、源文哈希校验、乱序响应、失败重试、离线与 Worker 恢复，遵循 PROJECT_SPEC 第 13 节。

自动脚本覆盖三个页面、两章正文切换、会话内继续阅读、横竖屏、模拟翻译对应关系、章节任务状态隔离、夜间设置和系统返回。导入异常、删除、取消中断和真实业务异常仍按上述手动表及 B/C 后续约定验收，不能仅以编译通过代替。
