# B：解析与本地数据验收

## 交付范围

- EPUB 从 `Uri` 复制到应用私有目录，按原始字节 SHA-256 去重，解析成功后事务写入 Room。
- 按 OPF 线性 spine 读取章节；EPUB 3 nav 和 EPUB 2 NCX 映射目录及段落锚点。缺目录时按章节生成目录。
- 生成稳定 chapterId、paragraphId、sourceHash；提取非空纯文本块，保存可用封面。
- `BookRepository`、`ReaderRepository`、`SettingsRepository`、`TranslationStore` 使用真实本地数据。译文按段落 ID 和源文本哈希保存，取消任务及删除书籍后拒绝迟到结果。
- 阅读位置、译文和设置跨 Repository 重建保存；删除书籍时取消对应 WorkManager 工作并清理关联记录和私有文件。

## 验证结果（2026-11-10）

使用 JDK 21 执行 `:app:assembleDebug :app:assembleDebugAndroidTest --offline`，构建通过。后台模拟器 `Medium_Phone`（Android 15 / API 35）执行 `LibraryInstrumentation`，通过：导入、重复导入、译文 LiveData 对应、缓存幂等、取消、阅读位置与设置重启读取、删除、迟到结果拒绝、损坏导入清理。

解析器只读检查通过：

| 测试材料 | 预期与结果 |
| --- | --- |
| `samples/two-chapters.epub` | 2 章节、8 文本块、3 目录项；spine 顺序和 `The door` 锚点正确。 |
| 原创 EPUB 2 夹具 | NCX 层级、锚点、多作者、封面正确。 |
| 缺目录夹具 | 回退到章节目录。 |
| 仅 div 正文夹具 | 可见文本块降级提取。 |
| 字体混淆夹具 | 不误判整本 DRM。 |
| 正文加密夹具 | 返回 `DRM_UNSUPPORTED`。 |
| 无正文、损坏夹具 | 分别返回 `UNSUPPORTED_CONTENT`、`INVALID_EPUB`。 |
| 用户提供的本地 EPUB | 55 个可阅读资源、4784 文本块、55 目录项；未复制或提交正文。3 条警告涉及无文本资源及其目录链接。 |

检查还校验了 paragraphId 和 sourceHash 的生成规则，以及每章段落计数与目录目标的有效性。

## 复测方式

1. 运行 `python tools/generate_epub_compat_fixtures.py <临时目录>` 生成原创夹具。
2. 运行 `tools/java/EpubParserCheck.java`，把 Debug 编译类目录及仓库中的 jsoup JAR 放入 Java classpath；输入样本和生成的夹具。
3. 启动模拟器，安装 `app-debug.apk` 与 `app-debug-androidTest.apk`，执行：

   `adb shell am instrument -w com.example.bilingualreader.debug.test/com.example.bilingualreader.data.LibraryInstrumentation`

测试仅导入并清理自身生成的唯一书籍；其他书籍和阅读数据不受影响。

## 第一版范围

文字型、无 DRM 的 EPUB 2/3 是目标。纯图片正文无 OCR；复杂 CSS、表格和交互排版不还原。目录指向跳过的无正文资源时记录警告。真实翻译供应商、Worker 调度和界面入口由 C/A 接入 B 的 `TranslationStore` 与 `ReaderRepository`。
