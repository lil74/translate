# C 第一阶段翻译服务选型与边界

日期：2026-11-02。首个可替换适配器选用 LibreTranslate，团队自建、不需要供应商长期密钥的实例。此选型用于验证 Java 请求链路，不代表实例已经部署或在线质量已经通过验收。

## 官方依据

- [API 使用与认证](https://docs.libretranslate.com/guides/api_usage/)：`POST /translate`，请求 `q/source/target/format`，返回 `translatedText`。启用密钥的实例需要 `api_key`；自建实例可不要求密钥。
- [支持语言](https://docs.libretranslate.com/guides/supported_languages/)：英语 `en`、中文 `zh`、繁体中文 `zt`；应用内部继续使用契约的 `en/zh-CN`，适配器将 `zh-CN` 映射为 `zh`。
- [自建安装](https://docs.libretranslate.com/guides/installation/)：根据官方说明部署，安装英文到中文模型，并用实例的 `/languages` 确认实际可用语种。

## 当前实现

`new LibreTranslateService("https://团队域名/translate")` 实现既有 TranslationService。通过构造方法传完整 URL，不在应用里写死公网服务地址。只支持无需 API Key 的自建实例，不接受或保存长期密钥。团队付费服务应另加服务端代理适配器，当前代码不声称已经实现代理。

供应商仅返回译文、不返回段落 ID。因此每个输入单独串行请求，使用当前输入的 paragraphId/sourceHash 生成 TranslationOutput，绝不猜测网络列表顺序。最终经过 TranslationValidation 检查，再交给后续的 TranslationStore。适配器不写数据库。

- 应用批次上限为 8 段 / 4000 Unicode 码点，不是供应商官方限额。
- 连接超时 10 秒、读取超时 30 秒；响应最大 1 MiB；拒绝重定向。
- HTTP 401/403 → AUTH_FAILED；429 → RATE_LIMITED；5xx → NETWORK_ERROR；其他非 200 → TRANSLATION_FAILED。供应商尚未给出稳定的额度错误结构，因此未把所有 403 或 400 猜成 QUOTA_EXCEEDED。
- 超时 → TIMEOUT；连接失败 → NETWORK_ERROR；空译文、非字符串或无效 JSON → INVALID_RESPONSE。
- 第一阶段将失败抛给调用方，尚不自动重试、不承诺部分结果落库、不处理 Retry-After。第二阶段再与调度、持久化尝试次数及取消协调。
- 正常应用端仅 HTTPS；本机 JVM 测试允许 localhost/127.0.0.1 HTTP，未放开 Android 全局明文网络。

## 上线前需确认

自建需要模型与计算资源；实际单请求上限、并发、费用、限流由部署者配置并记录。当前没有实际实例，不能给出已经验证的吞吐量或费用。不要把免费公开镜像当成稳定服务。若转用托管实例，应重新验证其费用、限额与认证并通过代理保存付费密钥。

首次从 App 启用真实服务时，A 需要告知用户“原文会发送到团队翻译服务”，用户主动点击后发起；本次未接入页面，未自动发送该 EPUB 到任何公网服务。日志仅保留错误码，不打印正文、响应体或密钥。

## 验证记录

已通过本地参考译文的 ID 对应验证和本机 HTTP 协议桩测试。协议桩返回预置参考文本，不是 LibreTranslate 引擎，不可作为翻译质量验收。

实际在线验证命令见 `C_PHASE1.md`；提供可用的团队实例后，使用 `--endpoint` 复用同一 EPUB 样本生成真实译文，并由 C 人工检查语义、专名和简体中文输出。
