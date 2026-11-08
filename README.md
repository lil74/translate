# 轻阅 · BilingualReader

Java + XML 的 Android EPUB 双语阅读器。A 的四阶段界面已完成，已接入 B 的真实 EPUB/Room 书库。完整交付、运行检查与边界见 [A_COMPLETE](docs/A_COMPLETE.md)。

## 当前功能

- 导入真实 EPUB，显示书架、去重提示、目录与连续滚动原文；删除书籍。
- 简洁文字目录，保留层级、目录锚点和继续阅读入口；每章不再使用卡片。
- 保存本地阅读位置，重启后继续阅读；上下章边界、加载/空内容/重试提示。
- 原文与译文按段落 ID、sourceHash 与语种匹配，显示任务进度、错误原因、失败数量、取消、继续与重试。
- 字号 14–30sp、行距 1.2–2.0、日夜模式，设置本地保存。
- 同段落内长按选词或选句 → 翻译 → 底部结果弹窗，支持加载、错误重试和关闭；临时查询不写入章节译文。

Debug 使用 B 的真实数据库和 C 已提供的 FakeTranslationService，经 Debug 联调协调器写入模拟译文和状态。模拟输出明确标注，不发送原文到网络；菜单可模拟下一段失败，用于验证 A 的重试界面。旧内置 DemoRepository 已不接入正常应用。

Release 使用真实书库，可读取保存的译文；在线翻译任务暂由 UnavailableTranslationCoordinator 给出未配置提示，等待 C 的正式 Coordinator/Worker 和团队服务地址。Debug 联调不代表在线翻译、进程中断任务恢复或翻译质量已经验收。

## 启动

Android Studio 打开包含 settings.gradle 的根目录，Gradle JDK 选 21，安装 Platform 35 与 Build Tools 34.0.0；选择 Debug 和安卓 8.0+ 设备运行。local.properties 填本机 SDK 路径。

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
若依赖下载超时，请检查本机 Gradle 用户目录的代理配置；不要将个人代理配置或路径提交到仓库。

## 协作与验证

| 文档 | 内容 |
|---|---|
| [A 完整交付](docs/A_COMPLETE.md) | 四阶段完成项、当前组装、测试与 C 后续接线 |
| [目录说明](docs/DIRECTORY_GUIDE.md) | 文件夹用途与分工 |
| [共同规范](docs/PROJECT_SPEC.md) | 原业务字段与接口约定 |
| [接口说明](docs/INTERFACES.md) | 源码包名与调用边界 |
| [环境](docs/ENVIRONMENT.md) | 固定构建版本与验证记录 |
| [翻译服务](docs/TRANSLATION_PROVIDER.md) | C 的真实适配器与服务部署边界 |
| [选词/选句对接文档](docs/C_SELECTION_TRANSLATION.md) | 可直接转发给 C：复用现有 TranslationService 的临时查询接线与独立模拟标志 |

当前设备集成测试位于 app/src/androidTest，验证真实 EPUB/Room 与 UI，并有独立的杀进程后续读检查；命令见 A_COMPLETE。旧 verify_phase1.py 与 phase1 截图保留作历史记录，已不适用当前真实书库入口。

提交源码、Gradle Wrapper、样本、测试和 docs；忽略 work/、build/、local.properties、密钥及私有书籍。共享 contract 变更先协调 B/C。本次不改变原接口字段，不直接在 UI 写 DAO 或请求供应商。
