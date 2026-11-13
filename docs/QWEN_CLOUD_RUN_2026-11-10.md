# 手机独立联网翻译：百炼部署与验收

日期：2026-11-10。基于已合并的 main `d94117f`，包含本地合并修复及本次尚未提交的云端接入修改。

## 实际运行

手机 → `https://8.148.28.233/translate` → 阿里云 ECS 上的 nginx / Python 网关 → 百炼 `qwen-mt-plus` → 手机 Room 数据库。

手机已覆盖安装新版 Debug、保留原有书库并配置网关令牌。无需电脑、Docker 或 USB；手机需要网络，服务器及百炼账户需要保持可用。无需 APK 内保存供应商 Key。

服务器运行 Ubuntu 22.04，以非特权 `readertranslate` 用户运行 `bilingualreader.service`，后端仅监听 127.0.0.1:8000；nginx 对外提供 HTTPS。服务已设置开机启动。部署目录 `/opt/bilingualreader`，私密配置 `/opt/bilingualreader/.env`（root 0600）。未上传私钥或小说文件。

公网 IP HTTPS 证书已签发，当前有效期至 2026-11-17 06:24:45 UTC。`bilingualreader-cert-renew.timer` 每日检查两次，已启用并通过 Certbot 模拟续期；续期后自动验证并 reload nginx。80 端口需保留给 ACME 验证，443 为 APP 入口。

## 翻译质量改进

- 从本机 LibreTranslate 切换到实际百炼 Qwen-MT Plus API。
- 使用 Qwen-MT 的 `translation_options.domains` 设定小说翻译目标：自然简体中文，保留叙述、对白、原意与专名，不摘要或增写。
- 服务器可通过 `TRANSLATION_TERMS_JSON` 配置人工审定的人名、术语表。
- HTTP 响应中的实际 provider 和模型版本写入现有译文记录；旧无元数据的 LibreTranslate 接口仍兼容。
- 增加“重新翻译”入口，明确确认后清除本章旧译文并用当前服务重做；普通翻译继续复用缓存。此操作可能产生新的 API 费用，不会自动批量重译用户书库。

原创句子的实际云端结果：

| 原文 | 译文 | 本次单次耗时 |
| --- | --- | --- |
| When the suitcase got stuck, he gave me a hand. | 当行李箱卡住的时候，他帮了我一把。 | 0.720 秒 |
| "You're kidding," she said, folding her arms. | “你在开玩笑吧，”她抱着胳膊说道。 | 0.525 秒 |
| I wasn't angry—just disappointed. | 我并不生气——只是有些失望。 | 0.537 秒 |

以上是少量样本，不代表文学翻译质量保证。当前请求仍逐段发送，尚未引入自动整章上下文；长篇中的代词、人物称呼、双关与前后风格仍需人工检查。未经用户发起，没有向百炼发送其商业 EPUB 正文。

## 凭据边界

百炼 Key 仅由服务器环境读取，本机填写文件为忽略的 `deploy/qwen/.env`。手机仅保存独立服务访问令牌，采用 Android Keystore AES-GCM 加密、以完整服务地址为 AAD；切换地址不会携带其他地址的令牌。令牌不进入任务表或 Worker Data，HTTP 重定向被拒绝。

服务器校验 Bearer 令牌，限制 4000 个 Unicode 码点、并发 2、单进程每分钟 120 次，错误不回传供应商响应体或凭据。没有凭据时返回 503，不会回退到模拟翻译。当前是团队共享令牌方案；正式多用户发布还需要独立账号、持久化额度及费用管理。

SSH 已恢复：原 `gapminder_cloud` 私钥有口令保护，未修改。项目专用部署密钥已另存本机 `C:\Users\dks19\.ssh\bilingualreader_deploy`；不要上传私钥或把其内容发到聊天。

## 验证结果

- Android JVM：26 项通过（章节 13、选词 10、网关 HTTP 3）。
- Python 网关：8 项通过，包含鉴权、请求限制、隐私错误处理与供应商请求格式。
- Debug 与测试包构建通过；Release Java 编译通过；Lint 0 错误、55 警告。
- Android 16 / API 36 真机：19 项通过（持久化/重译 16、网关 HTTP 与 Keystore 3）。
- 另运行 1 项真实百炼云端测试通过（5.064 秒）：导入原创 3 段 EPUB，真实翻译、落库、独立 Room 连接重新读取、缓存复用，以及独立选词翻译均通过。
- 云端验收时 ADB reverse 列表为空，APP 使用公网 HTTPS；USB 仅用于安装和测试控制，不转发翻译请求。
- 测试成功后保留“云端翻译联通测试 …”书籍及 online 配置，临时明文网关令牌文件已删除。供应商 Key 未发送给手机。

原始报告在忽略目录 `work/device-gateway-2026-11-10.log`、`work/device-cloud-translation-2026-11-10.log`、`work/cloud-translation-samples.json`。真实 API 测试需要显式 `runCloudTranslation=true` 和一次性手机私有配置，不在普通回归中自动调用付费 API。

## 运维

```bash
systemctl status bilingualreader nginx bilingualreader-cert-renew.timer
journalctl -u bilingualreader -n 50 --no-pager
systemctl restart bilingualreader
```

修改 `/opt/bilingualreader/.env` 后重启后端。日志不应记录 Key、Authorization 或书籍正文。模型、地域 URL 与权限以百炼账户实际开通配置为准。

代码和通用部署模板见 [部署说明](../deploy/qwen/README.md)。本次没有提交或推送 GitHub。
