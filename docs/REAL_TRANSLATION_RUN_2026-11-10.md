# 真实翻译服务运行记录（2026-11-10）

## 当前状态

- Docker Desktop 的 Linux engine 已启动。
- 容器 `libretranslate-libretranslate-1` 正在运行；引擎版本 1.9.6。
- 电脑地址：`http://127.0.0.1:5000/translate`；`/health` 返回 `{"status":"ok"}`。
- 官方英中、中英模型 1.9 已安装到 `libretranslate_models` 持久卷。
- `/languages` 返回 `en`、`zh-Hans`；已实际验证该版本也接受 APP 当前发送的 `target=zh`。
- 手机 USB 反向映射 `tcp:5000 → tcp:5000` 已建立。
- 手机 APP 已设为 `online`，地址为 `http://127.0.0.1:5000/translate`，模拟验证关闭。

## 真实结果

电脑 HTTP 测试：

| 原文 | 引擎实际返回 |
| --- | --- |
| Hello, world! | 哈罗,世界! |
| I enjoy reading books. | 我喜欢看书. |

随后在 Android 16 / API 36 手机上运行显式开启的 `RealTranslationSmokeTest`：1 项通过（0.925 秒）。测试导入原创的 3 段短篇 EPUB，使用正式 WorkManager、HTTP 适配器及 Room 存储。每段记录均为 SUCCESS、provider=libretranslate，包含中文且原文哈希一致；独立打开磁盘数据库重新读取通过，再次启动翻译时直接复用缓存。

这证明“手机导入 EPUB → 真实引擎翻译 → 持久保存 → 重新读取”已跑通，不代表整本小说或长章节的质量和耗时已经验收。没有发送用户的商业 EPUB。

测试书保留在书架，标题前缀为“真实翻译联通测试”。原始日志：`work/device-real-translation-2026-11-10.log`。测试默认跳过，只有加入 `-e runRealTranslation true` 才会调用真实本地引擎。成功后保留 online 配置；测试临时使用的章节发送授权已恢复，翻译用户书籍仍由 APP 弹窗确认。

## 本机网络修复

容器直连时，官方模型索引域名被解析为 0.0.0.0。已验证电脑现有代理可用，采用忽略目录中的本机覆盖配置 `work/libretranslate-proxy.yaml`，通过 `host.docker.internal:10090` 访问官方模型站点。未修改系统 DNS、全局代理或其他应用配置。

本机实际启动命令（仓库根目录）：

```powershell
docker compose -f deploy/libretranslate/compose.yaml -f work/libretranslate-proxy.yaml up -d
```

代理覆盖配置仅适用于这台电脑，不应当复制给没有相同代理端口的队友。模型已下载；当前容器保留自己的配置，日后可用下面命令启动/停止，无需重新创建：

```powershell
docker compose -f deploy/libretranslate/compose.yaml start
docker compose -f deploy/libretranslate/compose.yaml stop
```

若要重新创建容器，在本机应使用包含代理覆盖文件的完整 `up -d` 命令。首次模型加载期间的 connection refused 日志属于初始化阶段，最终以 `/health` 和真实翻译响应验收。

手机拔插 USB 或 ADB 重启后，若无法连接服务，重新执行：

```powershell
& 'C:/Users/dks19/AppData/Local/Android/Sdk/platform-tools/adb.exe' reverse tcp:5000 tcp:5000
```

当前方案要求 Docker/电脑保持运行、USB 调试映射可用；团队脱离电脑独立使用时需另行部署可访问的 HTTPS 服务。此服务只向电脑 loopback 开放，没有发布公网服务。本轮没有提交或推送 GitHub。
