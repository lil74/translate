# 开发环境与构建

## 团队固定版本

| 项目 | 版本 |
|---|---|
| 应用语言 / 字节码目标 | Java 17（源码无 Kotlin） |
| Gradle 运行 JDK | 21，本次验证使用 OpenJDK 21.0.2 |
| Android Gradle Plugin | 8.7.3 |
| Gradle Wrapper | 8.10.2 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 26（Android 8.0） |
| Build Tools | AGP 默认 34.0.0 |
| AppCompat / Material / Fragment / RecyclerView | 1.7.0 / 1.12.0 / 1.8.5 / 1.3.2 |
| Lifecycle / Room / WorkManager | 2.8.7 / 2.6.1 / 2.9.1 |
| Retrofit / OkHttp / jsoup | 2.11.0 / 4.12.0 / 1.18.3 |

依赖完整版本见 `app/build.gradle`。这些版本为本骨架固定组合，不表示最新版本；后续升级由团队统一操作。JDK 21 用于运行构建，compileOptions 输出 Java 17 代码，两者不矛盾。依赖可能传递引入 Kotlin 运行库，不代表应用源码使用 Kotlin。

AGP 8.7 官方兼容说明（要求至少 Gradle 8.9 / JDK 17，支持 API 35）：[Android 官方文档](https://developer.android.com/build/releases/agp-8-7-0-release-notes)。

## Android Studio 打开

1. 打开仓库根目录；在 SDK Manager 安装 Android SDK Platform 35 与 Build Tools 34.0.0。
2. Settings → Build, Execution, Deployment → Build Tools → Gradle，Gradle JDK 选 21。不要使用本机默认 JDK 25 运行本版本 Gradle。
3. `local.properties` 内写自己的 SDK 路径，例如 `sdk.dir=E:/Android/sdk`；Android Studio 通常自动生成。此文件不提交。
4. Gradle Sync 后选 debug 和安卓 8.0+ 设备运行。无需配置真实服务或密钥即可看演示页。

## 命令行

Windows PowerShell 中先将 JAVA_HOME 指向 JDK 21，并确保 Java 可用，然后运行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:lintDebug
```

macOS / Linux：`chmod +x gradlew` 后使用 `./gradlew`。

- Debug 输出：`app/build/outputs/apk/debug/app-debug.apk`。
- Release 输出：`app/build/outputs/apk/release/app-release-unsigned.apk`；没有签名配置，且正式业务尚未接入。
- Lint 报告：`app/build/reports/lint-results-debug.html`。

## 网络与本机验证环境

共享配置只使用 Google / Maven Central / Gradle 官方仓库，无本机代理地址。若同步下载超时，在自己的 Android Studio 或 Gradle 用户设置里配置网络；不要把个人代理、SDK 绝对路径或账号配置写进公共文件。

本次验证使用本机 OpenJDK 21.0.2、已有 Gradle 8.10.2，以及项目 `work/` 内的临时 SDK（官方下载 Platform 35、复制本机 Build Tools 34.0.0）。所有临时缓存与本机 local.properties 已排除。此次 Windows 沙箱中 JBR 21.0.10 的可写性检查返回错误结果，改用现有 OpenJDK 21.0.2 后继续构建；这不需要修改应用代码。

## GitHub 上传准备

仓库已初始化为 `main`，未创建提交或配置远程地址。先在 Android Studio 中确认 Debug 运行，再将源码提交到自己的仓库。`gradle-wrapper.jar` 和 `samples/two-chapters.epub` 要提交，`work/`、`local.properties`、`.gradle/`、`app/build/` 不提交。

本机工作目录所有者为 Administrators，Git 可能报告 `dubious ownership`。本次仅使用命令级信任参数检查，没有修改全局 Git 配置。例如在此目录检查文件：

```powershell
git -c safe.directory=D:/idea/translate status
```

如通过命令行提交，同样在 `add` / `commit` / `remote` / `push` 命令前加该 `-c` 参数；其他成员在自己的克隆目录通常不需要它。

## 实际验证记录

2026-11-02 本机验证结果：

| 检查 | 实际结果 |
|---|---|
| `:app:assembleDebug` | 通过，生成约 7 MB 的 `app-debug.apk` |
| `:app:assembleRelease` | 通过，生成未签名 Release APK；正式业务仍是待接入状态 |
| `:app:lintDebug` | 通过，0 错误、43 警告，未启用 baseline 或禁用检查 |
| Debug APK 元信息 | 包名 `com.example.bilingualreader.debug`；minSdk 26 / targetSdk 35；启动类正确 |
| XML 与 EPUB 结构检查 | 13 个 Android XML 和样本 EPUB 内 XML 均可解析 |
| Git 忽略规则 | `local.properties`、`work/`、APK 和构建目录均被排除 |

Lint 警告分类：依赖更新提示 9、备份规则建议 1、整体列表刷新 4、背景重复绘制 1、文字拼接 3、XML 硬编码文字 25。当前仅提供中文界面；后续可统一提取 strings.xml、改用 DiffUtil，再逐步消除警告。

本次用本机已缓存的同版本 Gradle 8.10.2 启动构建，官方 Wrapper JAR / 脚本已随仓库提供，并固定分发包 SHA-256。Windows 首次遇到临时缓存目录占用，使用 `--no-watch-fs --max-workers=2` 后 Release 重试成功；这些本机启动参数没有写入共享构建配置。

当前没有连接安卓设备，未进行实际点击、排版截图或真机验收；请按 `ACCEPTANCE.md` 操作。编译通过不能代替界面体验和真实业务验收。
