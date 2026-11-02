# 两章联调样本

`two-chapters.epub` 为本项目原创测试文字，可随仓库分发。无 DRM、无封面，使用默认封面。

预期：2 个 Chapter，每章标题 + 3 段正文（各 4 个块）；第一章 `z-first.xhtml`，第二章 `a-second.xhtml`，故意让文件名字母顺序与 spine 相反。目录 3 项，嵌套的 The door 指向第一章第二个正文段落；`em` 不产生重复块，`br` 保留块内换行。

Debug 模拟页面不解析这个文件；真实导入验收由 B 接入后进行。
