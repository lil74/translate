# 真实翻译接入与模拟缓存隔离

适用代码：2026-11-10 的本地工作区。APP 使用现有 LibreTranslate HTTP 适配器；导入 EPUB、逐段任务执行及 Room 译文保存已经接通。服务启动和真实译文质量不等于模拟流程测试通过，需要按下面单独验收。

**本机执行更新**：2026-11-10 已实际启动容器，并在 USB 真机完成原创短篇的真实翻译与落库。本机存在容器 DNS/下载问题，使用本机代理覆盖配置解决；实际运行命令及已完成的验收见 [真实运行记录](REAL_TRANSLATION_RUN_2026-11-10.md)。下面保留通用部署步骤，其中“尚未启动”等文字描述的是准备配置时的历史状态。

## 1. 在电脑启动一个真正的翻译引擎

本地开发可以使用 Docker Desktop（Linux containers）运行 LibreTranslate。仓库已提供 `deploy/libretranslate/compose.yaml`，仅向电脑本机开放 5000 端口，加载英语与中文模型。此配置尚未在本机实际启动；首次运行需要下载 Docker 镜像及模型。

在仓库根目录 PowerShell 执行：

```powershell
docker compose -f deploy/libretranslate/compose.yaml up -d
docker compose -f deploy/libretranslate/compose.yaml logs -f
```

首次启动等待模型安装完成，Ctrl+C 退出日志跟踪不会停止容器。当前开发配置跟随官方镜像 latest；正式部署前应固定已验收的镜像版本或 digest。

另开一个 PowerShell，先验证支持 `en` 和 `zh`，再只发送测试短句：

```powershell
Invoke-RestMethod 'http://127.0.0.1:5000/languages'
$translationBody = @{ q='Hello, world!'; source='en'; target='zh'; format='text' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:5000/translate' -ContentType 'application/json; charset=utf-8' -Body $translationBody -TimeoutSec 120
```

正常响应中应有非空 `translatedText` 中文译文，不应出现“模拟译文”。模型尚未加载时不要直接开始长章节翻译。

来源：[官方安装说明](https://docs.libretranslate.com/guides/installation/)、[官方 Compose 示例](https://github.com/LibreTranslate/LibreTranslate/blob/main/docker-compose.yml)、[语言模型列表](https://docs.libretranslate.com/guides/supported_languages/)、[/translate 接口](https://docs.libretranslate.com/api/operations/translate/)。

## 2. USB 真机访问电脑服务

手机与电脑的 `127.0.0.1` 各自指向自己，需要建立 ADB 反向端口映射。手机已解锁并允许 USB 调试后：

```powershell
& 'C:/Users/dks19/AppData/Local/Android/Sdk/platform-tools/adb.exe' reverse tcp:5000 tcp:5000
& 'C:/Users/dks19/AppData/Local/Android/Sdk/platform-tools/adb.exe' reverse --list
```

多个设备时加 `-s 设备序列号`。拔插 USB、重启 ADB/手机后可能需要重新建立映射。本地 HTTP 只适用于 Debug 版本的 localhost/127.0.0.1；不需要把电脑局域网 HTTP 地址填进 APP。

## 3. 在 APP 配置

1. 使用包含本次缓存隔离修改的最新 Debug 安装包。
2. 右上角菜单 → 翻译服务 → 关闭“模拟验证”。
3. 地址填写 `http://127.0.0.1:5000/translate`，点击保存。
4. 若本章仍有旧任务在执行，先取消它；然后点击“翻译本章”，确认发送本章原文。
5. 等待进度完成。APP 自动按段落 ID、原文哈希和目标语言保存译文，不需要另点保存。

选词翻译使用同一配置的新服务实例，关闭已有弹窗再重新选择文字，点击确认发送；选词结果为临时查询，不写入章节缓存。

## 4. 缓存隔离规则（已实现）

- 使用已有 `provider`、任务 `executionMode` 等字段，不新增数据库表或迁移。
- 模拟任务写入结果时统一标记 `provider=fake`。
- 在线任务不复用 `fake/mock/demo`、旧模拟文字标记及缺失来源的成功缓存；相关段落在用户启动在线翻译后重新进入待翻译。
- 已有有效真实译文继续复用；切回模拟模式也不会覆盖它们。这里隔离的是模拟与在线模式，不按每个在线供应商单独保存一份译文。
- 服务模式或地址变化后再次启动，会取消旧活动任务并创建新任务；旧任务的迟到结果不能写入。当前 UI 在任务运行时需要先点取消，再启动新任务。
- 在线任务拒绝保存带模拟来源的成功响应。数据库仍然只有每段每种目标语言的一份当前译文；旧模拟内容会被替换，不会额外备份。
- 切换设置本身不清库、不自动发送书籍；需用户再次点击“翻译本章”。原有书籍、目录和阅读位置保留。

## 5. 保存闭环的验收

先用短章节验证：模拟翻译一章 → 切在线 → 再翻译同章，应实际调用引擎并替换模拟结果。然后关闭并重新打开 APP，断开服务后重新进入本章，译文仍应可读；恢复服务再次点翻译，有效真实缓存应直接复用。不要通过删除整本书来解决模拟缓存问题。

本轮已添加五项 Room 真机回归用例验证缓存规则；它们使用本地可控返回值，不能替代真实引擎的联通、性能与质量验收。2026-11-10 在 Android 16 / API 36 手机上运行 `TranslationPersistenceTest` 共 14 项，全部通过（1.338 秒）；Debug 更新已覆盖安装并保留书库。23 项 JVM 测试通过，Debug/Release Java 编译通过，Lint 为 0 错误、54 警告；Compose 配置语法检查通过。尚未启动 Docker 引擎或进行真实翻译联通验收。原始本轮设备报告在忽略目录 `work/device-cache-isolation-2026-11-10.log`。这些结果与之前的选词 UI 测试分轮记录，不混算为同一轮全量测试。

## 6. 团队远程服务

团队共同使用时，将同样服务部署到服务器并配置受信任 HTTPS，例如 `https://translate.example.com/translate`（占位示例）。APP 填完整路径，手机必须能访问该地址；Release 版本只接受 HTTPS。

当前适配器发送：

```json
{"q":"Hello, world!","source":"en","target":"zh","format":"text"}
```

要求 HTTP 200 响应：

```json
{"translatedText":"你好，世界！"}
```

这是接口示例，不是固定返回实现。若使用其他供应商，可让团队后端调用该供应商并转换成此格式；真实供应商密钥放在后端环境变量中。当前 APP 不发送 `api_key` 或 Authorization，不能直接填写需要鉴权或格式不同的供应商地址。共享公网部署还需要团队后端的访问控制/额度管理，此本地开发 Compose 不承担公网接入网关职责。
