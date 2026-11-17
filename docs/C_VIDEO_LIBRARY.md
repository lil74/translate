# 给 C：封面书架与多视频库集成

2026-11-17。

## 用户入口

书架已去掉搜索、视频入口、大标题和翻译服务说明，只保留“让故事带你走进另一种语言”、导入和封面网格。封面下显示两行以内的书名，不显示作者、章节数与箭头卡片。缺少可用封面的 EPUB / TXT 使用书名封面占位；没有下载或伪造封面。

首页“我的视频”进入视频库，导入后保留在列表，用户点具体视频才进入现有播放器。首页“继续观看”仍可直接打开上次的视频。视频库使用本地视频帧做缩略图，显示文件名与已保存位置；缩略图提取失败时使用文字占位。

长按书籍仍可删除；长按视频可从库中移除，对应字幕、译文、播放进度移除，原视频文件不会删除。重新导入相同 URI 会恢复已有状态，不产生重复条目。清除过的 URI 再次显式导入则创建新项目。

## C 需要知道的变化

原字幕、OCR、ASR、翻译、倍速和全屏逻辑继续使用 VideoProject。新增存储层将每个视频的完整 VideoProject 分开保存，防止字幕和播放位置互相覆盖。不修改 B 的 Room 模型，也不改变云端请求接口。

- `VideoProjectStore.load()`：兼容原接口，读取最后活动项目，首页继续观看仍可用。
- `load(String uri)`：载入指定视频的完整项目。播放器通过 Fragment 的 `videoUri` 参数和 VideoViewModel 新构造重载使用此入口。
- `list()`：返回视频库摘要，包含 URI、名称、位置和更新时间，不逐个载入全部字幕。
- `add(uri, name)`：显式导入；相同 URI 保留已有项目。
- `delete(uri)`：移除项目并留下删除标记，防止迟到的 checkpoint 重新创建视频。
- `save(project)`：保存独立项目后仍维护旧 `video-project.json`，兼容既有调用。
- `VideoProjectStore.IO`：视频 ViewModel 与视频库共用顺序执行器，确保离开播放器时的 checkpoint 排在视频库刷新和下一次载入之前。

`VideoViewModel(Context, AppContainer)` 原构造方法仍可用，新增第三个 `String requestedUri` 参数用于从库打开。现有 `MainActivity.openVideo()` 保留给旧接线和设备测试；用户正常的素材入口改走 `openVideoLibrary()`，具体项目走 `openVideoProject(uri)`。

正常从视频库打开后隐藏播放器内的“选择视频”，切换视频通过返回视频库完成；导入字幕、识别与翻译等原有控件保留。未带 URI 的原入口仍兼容。

## 存储与迁移

新文件位于应用私有的 `files/video-library/`。文件名为 URI 的 SHA-256，不使用文件名或 URI 路径直接拼接目录。

- `<hash>.json`：完整项目，每项仍上限 16 MiB。
- `<hash>.meta`：小型列表摘要，缺失时可从完整项目重建。
- `<hash>.deleted`：删除标记；旧页面迟到的保存会被拒绝。显式重新导入才清除标记。

采用同目录临时文件、同步写入及原子替换；完整项目与摘要分开写，摘要缺失可以恢复。首次打开视频库或指定项目时会兼容迁移原 `video-project.json`，原文件保留，不直接删除用户旧记录。原活动记录指向已删除项目时不会借迁移复活。

视频库以 URI 去重，未复制视频文件，也不按视频字节计算哈希。设备提供者改变 URI 后可能显示为另一项。首次导入需要持久读取授权；源文件被移动或授权丢失时需重新导入授权。缩略图只在本机后台读取，封面缓存有大小上限，并检查回收视图的绑定标识，避免快速滑动时串图。

## 主要文件

| 文件 | 用途 |
| --- | --- |
| `ui/MediaGridAdapter.java`、`ui/CoverFrame.java` | 共用封面网格、图片后台载入与缓存 |
| `ui/bookshelf/BookshelfFragment.java` | 精简书架与导入、删除、收藏过滤 |
| `ui/video/VideoLibraryFragment.java` | 视频库网格与导入、移除 |
| `viewmodel/VideoLibraryViewModel.java` | 视频库异步读写 |
| `video/VideoLibrary.java` | 可独立测试的分项目持久化 |
| `video/VideoProjectStore.java` | 旧活动记录兼容、迁移及新库入口 |
| `viewmodel/VideoViewModel.java`、`ui/video/VideoFragment.java` | 指定 URI 的恢复与原播放器接线 |

Java 以 `app/src/main/java/com/example/bilingualreader/` 为根。新增布局为 `fragment_video_library.xml`、`item_media_cover.xml`；原 `fragment_bookshelf.xml` 已精简。

## 验收

1. 旧用户进入视频库，原视频、字幕、译文和播放位置仍存在。
2. 导入 A、B，分别翻译并播放到不同位置，互相切换后确认字幕与位置不串。
3. 重启后重复第二步；重复导入 A 不丢进度或重复出现。
4. 移除 A，再等待旧网络回包或 checkpoint；A 不应重新出现，B 保持不变。
5. 视频库移除只影响应用记录，原文件仍在；重导入已移除项从新状态开始。
6. 检查书架实际 EPUB 封面、TXT 占位、长书名、快速滚动、三种主题和横屏。

## 本轮验证记录

Debug、测试 APK、Release 构建通过；75 项单元测试通过，其中 7 项新增 VideoLibraryTest 验证多项目隔离、重导入、删除后迟到保存拒绝、明确重导入、摘要重建、损坏/错项目拒绝及路径边界。Lint 0 错误、147 警告，未禁用检查。构建日志为 `work/media-library-build.log`。

未连接设备，本轮未运行触屏、缩略图显示或 URI 授权验收，也未发起实际收费云端请求。旧项目迁移与 Android 播放恢复仍需按上述设备步骤验证。
