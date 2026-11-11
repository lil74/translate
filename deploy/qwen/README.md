# 百炼翻译网关

手机通过 HTTPS 访问 `/translate`，服务器调用 Qwen-MT，返回现有 APP 兼容的 `translatedText`，并附带 `provider=qwen-mt` 和模型名。与电脑 Docker/USB 无关。需要服务器可访问百炼 API、模型调用权限和额度。

## 私密配置

复制 `.env.example` 为本目录 `.env`（Git 忽略）。在本机填写 `DASHSCOPE_API_KEY`、控制台对应地域的 `QWEN_BASE_URL`，选已开通模型。`SERVICE_TOKEN` 是独立的网关访问令牌（至少 24 个随机字符），与百炼 Key 不同。

APP 的“翻译服务”填写服务器完整 HTTPS `/translate` 地址及 SERVICE_TOKEN。服务令牌使用 Android Keystore 加密并绑定完整地址；任务数据库和 Worker 参数不保存令牌，重定向不会携带令牌。API Key 仅在服务器环境变量中，不能填进 APP 或提交到 Git。

本机试运行可执行 `docker compose -f deploy/qwen/compose.yaml up -d --build`，但这里只向电脑 loopback 开放 8000。服务器安装入口是 `install-server.sh`，固定使用 `/opt/bilingualreader`、非特权服务账号和 systemd。HTTPS 配置应结合服务器实际端口和证书情况，不能直接覆盖既有网站。

## 质量策略与限制

- 默认候选 `qwen-mt-plus`；通过 Qwen-MT 专用 `translation_options.domains` 描述英语小说到自然简体中文的目标，不使用该模型不支持的 system 角色。
- 可在 `TRANSLATION_TERMS_JSON` 提供经人工确认的人名/术语表，例如 `[{"source":"Alice","target":"爱丽丝"}]`。这只是配置示例，具体书籍译名需要人工决定。
- 当前仍逐段翻译，没有自动传递整章背景，不能宣称已解决所有代词、双关和人物语气问题。更换模型后必须用代表性段落人工对比，确认人名、遗漏、语气和事实一致性。
- APP 增加“重新翻译”确认入口，用于替换此前 LibreTranslate 的真实缓存；普通“翻译本章”仍复用有效缓存。
- 每次 4000 个 Unicode 码点，网关并发 2，单进程每分钟最多 120 次；超限返回 429 和 Retry-After。该内存限流会随进程重启清空，多用户正式发布需增加账户与持久化额度管理。
- 不自动重试供应商请求；章节 Worker 继续使用现有的持久化退避策略。访问令牌为团队共享原型方案，不等同于个人账号系统。
- `/health` 只检查配置可加载，不产生付费调用，不代表凭据、额度和供应商网络已验证。
- 没有密钥时服务返回 503，不会自动降级到模拟翻译。

## 验证

`python -m unittest discover -s backend -v` 使用可控 transport，验证协议与错误边界，不能代替真实百炼质量测试。手机 `GatewayAccessDeviceTest` 验证真实 HTTP adapter 鉴权与模型元数据、令牌加密和地址隔离，但不发出付费请求。

官方依据：[Qwen-MT 参数、领域提示与术语表](https://help.aliyun.com/zh/model-studio/machine-translation)、[公网 IP 短期 HTTPS 证书与 Certbot](https://letsencrypt.org/2026/03/11/shorter-certs-certbot.html)。公网 IP 证书为短期证书，必须配置自动续期及 nginx reload。
