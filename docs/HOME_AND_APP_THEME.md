# 首页、我的与全局配色

更新：2026-11-17。

## 本次更新

当前 ui 分支已快进到 GitHub 的 `origin/main`（8fb6d84），包含现有视频字幕、OCR、语音识别、倍速与全屏功能。原本暂存的 LibreTranslateService 修复已备份在 Git stash，最新远程版本包含等价修复及更新的上下文翻译接口，因此没有覆盖回旧代码。本轮没有向 GitHub 推送。

- 首页采用“双入口卡片”：顶部查词、上次阅读、上次观看、我的书架、我的视频。记录来自真实本地数据，不填充演示书名或时长。
- 阅读卡片恢复原有阅读位置，显示实际章节与已保存的阅读进度；观看卡片恢复现有 VideoProjectStore 保存的视频及位置。视频库更新后支持多项目，详情见 [C 视频库对接说明](C_VIDEO_LIBRARY.md)。
- 底部为“首页 / 我的”，只在这两页显示。书架、查询、阅读、目录、视频和应用设置等下级页面隐藏底栏；“我的”按系统返回先回首页。
- “我的”包含实际收藏、跨书籍书签、查词入口和应用设置。复习区域明确标记“即将开放”，没有假数据或不可兑现的复习按钮。
- 全局菜单原“阅读设置”改为“应用设置”，提供主题配色、翻译服务与关于。
- 应用设置可切换苔绿、雾蓝、暖棕。选择立即保存，重建界面后整体生效；重启保持选择。字体、行距、页边距、字重和阅读亮度仍由阅读页自己的设置负责，配色切换不重置这些参数。

## 配色如何生效

全局主题通过七个语义属性管理：`palettePrimary`、`paletteBackground`、`paletteSurface`、`paletteInk`、`paletteMuted`、`paletteSoft`、`paletteTranslation`。现有布局、卡片、矢量图标和浮窗统一改用这些属性。视频画面与全屏播放的黑色背景保留媒体用途。

- 基础苔绿颜色：`res/values/colors.xml` 与 `res/values-night/colors.xml`。
- 雾蓝、暖棕颜色：`res/values/palettes.xml` 与 `res/values-night/palettes.xml`。
- 属性定义：`res/values/palette_attrs.xml`；主题映射：`res/values/themes.xml`。
- 主题选择：`ui/settings/AppAppearance.java`，存在应用私有的 `app_appearance` preferences；Activity 在创建布局之前应用。
- 现有阅读日夜选择仍决定明暗模式，三种配色均提供对应的明暗方案。全局色系与字号等阅读排版参数独立。

后续新页面请用 `?attr/palettePrimary` 等语义属性，避免直接引用固定绿色 `@color/primary`。主题选项里的颜色样本有意固定，用于辨识三种备选配色。

## 代码入口

| 文件 | 用途 |
| --- | --- |
| `ui/home/HomeFragment.java`、`viewmodel/HomeViewModel.java` | 首页真实记录与书籍/视频入口 |
| `ui/profile/ProfileFragment.java` | 我的、收藏和书签入口 |
| `ui/settings/AppSettingsFragment.java`、`AppAppearance.java` | 应用设置与持久化配色 |
| `ui/MainActivity.java` | 首页与我的切换、返回栈、底栏可见性与主题应用 |
| `res/layout/fragment_home.xml` | 双入口卡片布局 |
| `res/layout/fragment_profile.xml` | 我的布局 |
| `res/layout/fragment_app_settings.xml` | 配色选择与预览 |

Java 路径均以 `app/src/main/java/com/example/bilingualreader/` 为根。

## 设备验收

1. 分别从首页进入阅读、视频、查词，再返回，检查底栏和上栏显示正确。
2. 我的 → 应用设置，逐一切换三种配色；检查首页、书架、目录、查询、阅读、浮窗和视频操作区，再重启检查保存。
3. 在阅读页切换日夜，检查三种配色的明暗版本；确认字号、行距、亮度没有因配色切换丢失。
4. 阅读或观看后返回首页，检查真实书名、章节、进度或播放位置更新。
5. 收藏一本书，去我的收藏检查；从我的书签跳回对应段落位置。

## 本轮验证

Debug、测试 APK、Release 构建通过；68 项单元测试全部通过；Lint 0 错误、148 警告，未禁用检查。三种配色的日间/夜间共 48 项文字对比度检查通过，所有资源 XML 解析通过。配色检查可复跑 `python tools/check_app_palettes.py`。

完整集成构建日志为 `work/home-theme-build.log`，最终资源调整后的构建日志为 `work/home-theme-final-build.log`。构建中曾遇到新增资源的增量缓存重复登记，已仅清理生成的 Release 合并缓存并重建成功。

ADB 未发现连接设备，本轮未运行手机或模拟器的触屏、主题重建与视觉验收；上述设备步骤仍需执行。
